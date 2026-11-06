// Open Alpha2 — client logic (app-led.js)
// Contents: head/eye/mouth LED color picking and presets (shared ledPresetApply()).
// All files share the window/global scope (no ES modules). Load order is set by the
// <script src="..."> order in index.html - see the comment block at the head of index.html.


// ---------------- LEDs ----------------
// Colour/brightness/preset values are user-confirmed on real 5-mic hardware:
//   color: 1=Red 2=Green 3=Blue 4=Yellow 5=Purple 6=Cyan 7=White
//   brightness: 1 (dimmest) .. 9 (brightest)
//   preset (p5 upTime / p6 downTime / p7 runTime / p8 mode, set server-side in
//   MainActivity.java): On (long) p5=MAX,p6=0,p8=0 · Flash p5=100,p6=100,p8=0 · Chase
//   p5=100,p6=0 · Breathe (head only) p5=5,p6=20 · Dual color p5=500,p6=0 · Stop (no LED
//   params, calls the stop endpoint)
// Every control (colour dot, brightness slider, preset button) sends immediately -
// there is no separate "Start" button. Picking a colour or dragging brightness re-sends
// whatever preset was last used, so the robot updates live as you adjust either one.

const LED_COLORS = [
  { code: 1, name: uiLang === "en" ? "Red" : "紅", hex: "#ff3b3b" },
  { code: 2, name: uiLang === "en" ? "Green" : "綠", hex: "#3bff5c" },
  { code: 3, name: uiLang === "en" ? "Blue" : "藍", hex: "#3b6bff" },
  { code: 4, name: uiLang === "en" ? "Yellow" : "黃", hex: "#ffe93b" },
  { code: 5, name: uiLang === "en" ? "Purple" : "紫", hex: "#a83bff" },
  { code: 6, name: uiLang === "en" ? "Cyan" : "青", hex: "#3bfff0" },
  { code: 7, name: uiLang === "en" ? "White" : "白", hex: "#ffffff" },
];

let selectedHeadColor = 5; // Purple - matches Alpha2Connection.beginLedEffect()'s confirmed-working payload
let selectedEyeColor = 7;  // White
let lastHeadPreset = "long";
let lastEyePreset = "long";
// The three dual-mode picks (the real device outputs these three dual pairs): blue-green = p1 blue, red-green = p1 yellow, red-blue = p1 purple.
// Why not all 7 colors: every other p1 value also comes out as blue-green, so there is no variety; showing them would only take up space.
const DUAL_COLORS = [
  { code: 3, name: "藍綠", hex: "linear-gradient(135deg, #3b6bff 50%, #3bff5c 50%)" },
  { code: 4, name: uiLang === "en" ? "Red-Green" : "紅綠", hex: "linear-gradient(135deg, #ff3b3b 50%, #3bff5c 50%)" },
  { code: 5, name: uiLang === "en" ? "Red-Blue" : "紅藍", hex: "linear-gradient(135deg, #ff3b3b 50%, #3b6bff 50%)" },
];
let prevHeadColor = null; // the color picked before entering dual; restored when leaving
let prevEyeColor = null;
function dualValid(code) { return code === 3 || code === 4 || code === 5; }

function buildColorPicker(wrapId, getSelected, setSelected, onPick, palette) {
  // If the element is missing, skip this picker; do not block other initialization.
  const wrap = document.getElementById(wrapId);
  if (!wrap) {
    console.error("buildColorPicker: element #" + wrapId + " not found, skipping");
    return;
  }
  const colors = palette || LED_COLORS;
  wrap.innerHTML = "";
  colors.forEach(function (c) {
    const dot = document.createElement("button");
    dot.type = "button";
    dot.className = "color-dot" + (c.code === getSelected() ? " selected" : "");
    dot.style.background = c.hex;
    dot.title = c.name;
    dot.onclick = function () {
      setSelected(c.code);
      wrap.querySelectorAll(".color-dot").forEach(function (d) { d.classList.remove("selected"); });
      dot.classList.add("selected");
      onPick();
    };
    wrap.appendChild(dot);
  });
}

function buildHeadColorPicker() {
  buildColorPicker("headColorPicker",
    function () { return selectedHeadColor; },
    function (code) { selectedHeadColor = code; },
    headLedApply,
    perledHeadMode === "dual" ? DUAL_COLORS : LED_COLORS);
}

function buildEyeColorPicker() {
  buildColorPicker("eyeColorPicker",
    function () { return selectedEyeColor; },
    function (code) { selectedEyeColor = code; },
    eyeLedApply,
    perledEyeMode === "dual" ? DUAL_COLORS : LED_COLORS);
}

/** Shared logic for headLedPreset()/eyeLedPreset() - the two have identical structure (stop sends
 *  directly, the other presets combine color+brightness); only the api path/color/brightness source differs.
 *  A preset carries the currently mirrored mask (when per-LED presses left half lit, flash/chase etc. move only that half;
 *  never synced / server never wrote (-1) means no mask carried, and the server defaults to all-on).
 *  @param apiPath    "led/head/set" / "led/eye/set"
 *  @param preset     the preset string to send
 *  @param color      the currently selected color code
 *  @param brightnessElId the id of the brightness slider
 *  @param curMasks   {p3, p4} the currently lit mask (per-LED mirror state)
 */
function ledPresetApply(apiPath, preset, color, brightnessElId, curMasks, speedElId) {
  const brightness = document.getElementById(brightnessElId).value;
  const full = apiPath === "led/head/set" ? 31 : 255;
  const zone = apiPath === "led/head/set" ? "head" : "eye";
  let p;
  // Matches openapi /api/led/head/set /api/led/eye/set - apiPath is dynamic, so branch conditionally
  if (preset === "stop") {
    p = apiPath === "led/head/set" ? Alpha2Api.ledHeadSet({preset: "stop"}) : Alpha2Api.ledEyeSet({preset: "stop"});
    perledNoteSent(apiPath, 0, 0, 0, 0);
  } else {
    const params = {preset: preset, color: color, brightness: brightness};
    const speedEl = speedElId ? document.getElementById(speedElId) : null;
    if (speedEl) params.speed = String(Math.max(50, Math.min(500, parseInt(speedEl.value, 10) || 100)));
    // chase is the exception: the hardware ignores the mask and scans all (even when off), so omitting the mask = all-on,
    // which is what makes the mirror match. Other presets: carry the mask only when something in the mirror is lit;
    // pressing while fully off (right after stop) means all-on, so omit it.
    if (preset !== "chase" && curMasks) {
      const pm = perledPartialMasks(zone, curMasks);
      if (pm) {
        params.p3 = String(pm.p3);
        params.p4 = String(pm.p4);
        perledNoteSent(apiPath, pm.p3, pm.p4, Number(color), Number(brightness));
      } else {
        // Someone else's show / unknown: the server will use the full mask, so record that (color + brightness too,
        // because the disco eye is fully on like a preset with only a different color - matching the mask alone would never match the eye!).
        perledNoteSent(apiPath, full, full, Number(color), Number(brightness));
      }
    } else {
      // chase: the hardware ignores the mask and scans all, so omit the mask = all-on, and the mirror matches.
      perledNoteSent(apiPath, full, full, Number(color), Number(brightness));
    }
    p = apiPath === "led/head/set" ? Alpha2Api.ledHeadSet(params) : Alpha2Api.ledEyeSet(params);
  }
  return p;
}

// Record the mask just sent (optimistic, matching the local paint): if the next poll finds the
// server is not on this set (disco / something else moved), restore the animation to static and draw straight from the mask.
// stop records 0,0. Never sent = null (skip the check).
// ours flag: true = we are showing our own thing now (the mask is trusted and can be modified further);
// false = someone else's show (disco etc.) / unknown - preset/toggle always start from all-on.
// Do not take another show's pattern and edit it as ours (the eye got burned exactly that way: the disco mask was treated as our own).
let perledHeadSent = null;
let perledEyeSent = null;
let perledHeadOurs = false;
let perledEyeOurs = false;
function perledNoteSent(apiPath, p3, p4, color, brightness) {
  const rec = { p3: p3, p4: p4, color: color | 0, brightness: brightness | 0 };
  // New intent: restore memory + clear counters + claim (re-align starting from this mask set).
  if (apiPath === "led/head/set") {
    perledHeadSent = rec;
    perledHeadMismatch = 0;
    perledHeadDivergedFrom = null;
    perledHeadOurs = true;
  } else {
    perledEyeSent = rec;
    perledEyeMismatch = 0;
    perledEyeDivergedFrom = null;
    perledEyeOurs = true;
  }
}

// One mask output rule (preset optimistic + actual send use the same set; do not write two that drift apart):
// return {p3,p4} for partial / null for all-on (omit).
// chase is always all-on (the hardware scans anyway); not-our-show / unknown is always all-on;
// our-show + known + fully off (every LED switched off) sends 0 as is (do not flip back to all-on!).
function perledPartialMasks(zone, st) {
  const ours = zone === "head" ? perledHeadOurs : perledEyeOurs;
  if (!ours) return null;
  if (!(st.p3 >= 0 && st.p4 >= 0)) return null;
  if ((st.p3 | st.p4) === 0) return { p3: 0, p4: 0 };
  const max = zone === "head" ? 31 : 255;
  return { p3: Math.min(max, Math.max(0, st.p3 | 0)),
           p4: Math.min(max, Math.max(0, st.p4 | 0)) };
}

// Re-sends whatever preset was last active, using the current colour/brightness.
// Called on every colour click and every brightness drag so changes apply live.
function headLedApply() {
  return headLedPreset(lastHeadPreset);
}
function eyeLedApply() {
  return eyeLedPreset(lastEyePreset);
}

// Preset default speed (ms): pressing a preset first moves the slider back to the classic value, then sends;
 // after that, dragging the slider re-sends live (as immediate as brightness). long/stop ignore speed.
const PERLED_HEAD_SPEED_DEFAULT = { flash: 150, chase: 100, dual: 150, breathe: 100 };
const PERLED_EYE_SPEED_DEFAULT = { flash: 150, chase: 100, dual: 150 };
function perledSetSpeed(sliderId, valId, value) {
  const slider = document.getElementById(sliderId);
  const val = document.getElementById(valId);
  if (slider) slider.value = String(value);
  if (val) val.textContent = String(value);
}

function headLedPreset(preset) {
  // Color/brightness re-sends (headLedApply comes through here) do not reset the speed; only pressing a new preset moves it back to the classic value.
  const changed = preset !== lastHeadPreset;
  const wasStop = lastHeadPreset === "stop"; // pressing after stop = all-on (do not reuse the old 0,0)
  // Entering dual remembers the old color (anything not one of the three becomes blue); leaving dual restores it; the picker switches between the 3-color and 7-color sets.
  const wasDual = lastHeadPreset === "dual";
  const willDual = preset === "dual";
  if (willDual && !wasDual) {
    prevHeadColor = selectedHeadColor;
    if (!dualValid(selectedHeadColor)) selectedHeadColor = 3;
  }
  if (!willDual && wasDual && prevHeadColor != null) {
    selectedHeadColor = prevHeadColor;
    prevHeadColor = null;
  }
  lastHeadPreset = preset;
  perledHeadMode = (preset === "flash" || preset === "chase" || preset === "breathe" || preset === "dual") ? preset : "static";
  if (changed && PERLED_HEAD_SPEED_DEFAULT[preset] != null) perledSetSpeed("headSpeed", "headSpeedVal", PERLED_HEAD_SPEED_DEFAULT[preset]);
  if (changed && (wasDual || willDual) && typeof buildHeadColorPicker === "function") buildHeadColorPicker();
  // Optimistic local update: swap the mirror at once (consistent with what was sent, using the same
  // perledPartialMasks; no waiting for the poll - it works offline too, and the returning poll auto-calibrates). Brightness is
  // recorded together, so the display follows the dimming. Pressing a non-stop preset after stop (wasStop) means all-on; do not reuse the old 0,0.
  const headBright = perledReadBrightness("headBrightness");
  if (preset === "stop") {
    perledHead = { p3: 0, p4: 0, color: 0, brightness: 0 };
  } else {
    const hm = (preset === "chase" || wasStop) ? null : perledPartialMasks("head", perledHead);
    if (hm) perledHead = { p3: hm.p3, p4: hm.p4, color: selectedHeadColor, brightness: headBright };
    else perledHead = { p3: 31, p4: 31, color: selectedHeadColor, brightness: headBright };
  }
  perledRender();
  return ledPresetApply("led/head/set", preset, selectedHeadColor, "headBrightness", perledHead, "headSpeed");
}

function perledReadBrightness(elId) {
  const el = document.getElementById(elId);
  const v = el ? parseInt(el.value, 10) : 9;
  return (v >= 1 && v <= 9) ? v : 9;
}

function eyeLedPreset(preset) {
  const changed = preset !== lastEyePreset;
  const wasStop = lastEyePreset === "stop"; // pressing after stop = all-on
  lastEyeWasCountdown = false; // one press moves the speed slider under preset control, no longer under the countdown
  const wasDual = lastEyePreset === "dual";
  const willDual = preset === "dual";
  if (willDual && !wasDual) {
    prevEyeColor = selectedEyeColor;
    if (!dualValid(selectedEyeColor)) selectedEyeColor = 3;
  }
  if (!willDual && wasDual && prevEyeColor != null) {
    selectedEyeColor = prevEyeColor;
    prevEyeColor = null;
  }
  lastEyePreset = preset;
  perledEyeMode = (preset === "flash" || preset === "chase" || preset === "dual") ? preset : "static";
  if (changed && PERLED_EYE_SPEED_DEFAULT[preset] != null) perledSetSpeed("eyeSpeed", "eyeSpeedVal", PERLED_EYE_SPEED_DEFAULT[preset]);
  if (changed && (wasDual || willDual) && typeof buildEyeColorPicker === "function") buildEyeColorPicker();
  const eyeBright = perledReadBrightness("eyeBrightness");
  if (preset === "stop") {
    perledEye = { p3: 0, p4: 0, color: 0, brightness: 0 };
  } else {
    const em = (preset === "chase" || wasStop) ? null : perledPartialMasks("eye", perledEye);
    if (em) perledEye = { p3: em.p3, p4: em.p4, color: selectedEyeColor, brightness: eyeBright };
    else perledEye = { p3: 255, p4: 255, color: selectedEyeColor, brightness: eyeBright };
  }
  perledRender();
  return ledPresetApply("led/eye/set", preset, selectedEyeColor, "eyeBrightness", perledEye, "eyeSpeed");
}

function perledZonePace(zone) {
  const el = document.getElementById(zone === "head" ? "headSpeed" : "eyeSpeed");
  const v = el ? parseInt(el.value, 10) : 100;
  return Math.max(50, Math.min(500, isNaN(v) ? 100 : v));
}

// Eye speed slider only: during countdown mode the countdown owns it - dragging does not send (on release it reruns
// once with the new speed). Otherwise any move would jump back to the last preset (this is exactly what caused the old "cannot change speed" complaint).
function eyeSpeedInput(value) {
  const valEl = document.getElementById("eyeSpeedVal");
  if (valEl) valEl.textContent = value;
  // Countdown mode: dragging does not send; on release (onchange) it reruns with the new speed.
  // Other modes still send live (as immediate as brightness).
  if (lastEyeWasCountdown) return;
  eyeLedApply();
}
function eyeSpeedCommit() {
  // Release: countdown mode + not running = rerun with the new speed; otherwise send one final exact value.
  if (lastEyeWasCountdown && !eyeCountdownRunning) {
    eyeCountdown3s(false);
  } else if (!lastEyeWasCountdown) {
    eyeLedApply();
  }
}

// Mirror foreign state: the wifi LED (red/blue/off, remembered by the server) drives head LED #5;
// the mouth LED on/off (server mouth mode) is for the button display; mute is the local press state (the server does not record it).
let wifiMirror = "off";
let mouthLitMirror = false;
let muteLitLocal = false;
let sysPadLast = null; // last pad state (redraw the buttons on render, so zh/en switch follows at once without waiting for the poll)

// Eye "3s countdown": both eyes fill in 3 rounds (green to yellow to red, about 1 s per round), then white 3 s, then off.
// It runs ledEyeSet (long + raw mask, exactly what debugJniLed pushes to the hardware -
// p5/p6/p7/p8 all MAX,0,MAX,0), but through the bookkeeping endpoint: every step has a server record,
// so the poll always matches. Even if the chain breaks halfway (one step fails) and the hardware stops mid-way, the state is correct,
// and the next preset press / poll resumes normally. The old debugJniLed direct pass-through kept no books:
// on a broken chain the server, mirror, and hardware each told a different story, and that froze the eye - the head never had that path.
let eyeCountdownRunning = false;
let eyeCountdownStartMs = 0;
// During the countdown the eye speed slider belongs to the countdown: dragging does not send; on release it reruns once
// with the new speed (see eyeSpeedInput/eyeSpeedCommit). Any other preset / per-LED press clears this flag.
let lastEyeWasCountdown = false;
function eyeCountdown3s(resetSpeed) {
  if (eyeCountdownRunning) return Promise.resolve();
  eyeCountdownRunning = true;
  eyeCountdownStartMs = Date.now();
  lastEyeWasCountdown = true;
  if (resetSpeed !== false) perledSetSpeed("eyeSpeed", "eyeSpeedVal", 100); // countdown default 100ms per step (on the beat)
  perledEyeMode = "static";
  const masks = [16, 24, 28, 30, 31, 159, 223, 255];
  const colors = ["2", "4", "1"]; // Green Yellow Red
  function sleep(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  function step(color, mask) {
    const t0 = Date.now();
    const pace = perledZonePace("eye"); // countdown step follows the eye speed slider (min 50ms; do not hammer the driver)
    return Alpha2Api.ledEyeSet({ preset: "long", color: String(color), brightness: "9",
      p3: String(mask), p4: String(mask) }).then(function () {
      perledEye = { p3: mask, p4: mask, color: Number(color) };
      perledEyeOurs = true; // every countdown step is ours (though it skips NoteSent)
      perledRender();
      const dt = Date.now() - t0;
      if (dt < pace) return sleep(pace - dt);
    });
  }
  let p = Promise.resolve();
  colors.forEach(function (color) {
    masks.forEach(function (mask) {
      p = p.then(function () { return step(color, mask); });
    });
  });
  function done() { eyeCountdownRunning = false; }
  return p.then(function () {
    return step("7", 255); // white, fully on
  }).then(function () {
    return sleep(3000);
  }).then(function () {
    return eyeLedPreset("stop"); // goes through the preset path (sent/mode/picker update together; no loose end)
  }).then(done, done);
}

// ---------------- System indicator LEDs ----------------
// Wi-Fi LED (red/blue/off): fire-and-forget. The system restores it automatically when the network state changes.
// Volume-key LEDs (14/16): one press on, the next press off (the server records the state;
// switching off is a global off - the head and eyes go off together, and the Wi-Fi LED restores automatically).
// Mute LED: test use only. The XiaoZhi connection state or the physical keys overwrite it.
function sysWifiLed(color) {
  // Optimistic: the mirror flips at once (the poll calibrates; a server-side off / auto-restore both come back via the poll).
  // The wifi button itself was removed (pressing head LED #5 toggles it instead), so no button paint is needed.
  wifiMirror = color;
  return Alpha2Api.ledWifiSet({ color: color });
}

// Pressing the wifi display LED (head #5) toggles red/blue: red now goes to blue, anything else goes to red
// (off/unknown counts as blue lit; the real device only ever shows red or blue).
function sysWifiCycle() {
  return sysWifiLed(wifiEffective() === "red" ? "blue" : "red");
}

function wifiEffective() {
  return (wifiMirror === "red" || wifiMirror === "blue") ? wifiMirror : "blue";
}

function sysPadToggle(key) {
  return Alpha2Api.ledPadSet({ key: key }).then(function (res) {
    if (res && res.ok) sysPadRender(res);
    return res;
  });
}

function sysPadRender(res) {
  const minus = document.getElementById("padMinusBtn");
  const plus = document.getElementById("padPlusBtn");
  const m = !!(res && res.minus);
  const p = !!(res && res.plus);
  // Triangle buttons: no text inside, only color (yellow on / grey off). The title already says which one it is.
  // Nothing to do on zh/en switch (no text).
  if (minus) {
    minus.classList.remove("on");
    if (m) minus.classList.add("on");
  }
  if (plus) {
    plus.classList.remove("on");
    if (p) plus.classList.add("on");
  }
}

function sysMuteToggle() {
  muteLitLocal = !muteLitLocal;
  muteRender();
  return Alpha2Api.ledMuteSet({ on: String(muteLitLocal) });
}

function muteRender() {
  // Mic icon button: no text inside, only color (red on / grey off).
  const b = document.getElementById("muteBtn");
  if (!b) return;
  b.className = muteLitLocal ? "sys-toggle-on-red" : "sys-toggle-off";
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "嘴部 LED" section for what was tried and ruled out).
// Single-button toggle: on = red, off = grey; state follows the server mirror (mouth mode).
// Optimistic local update: press flips at once, the poll calibrates (works offline too).
function mouthToggle() {
  if (mouthLitMirror) {
    mouthLitMirror = false;
    mouthRender();
    return mouthLedOff().then(function (json) { perledPollOnce(); return json; });
  }
  mouthLitMirror = true;
  mouthRender();
  return mouthLedApply().then(function (json) { perledPollOnce(); return json; });
}

function mouthSpeedInput(value) {
  const valEl = document.getElementById("mouthSpeedVal");
  if (valEl) valEl.textContent = value;
  // Only re-send live while lit (with the new speed); dragging the slider while off does not turn the light on.
  if (mouthLitMirror) mouthLedApply();
}

function mouthRender() {
  // The mouth button (power-key style) was removed; only the bowl (perledMouthArc) remains as display + switch.
  const arc = document.getElementById("perledMouthArc");
  if (arc) {
    arc.classList.remove("on");
    if (mouthLitMirror) {
      arc.classList.add("on");
      const sEl = document.getElementById("mouthSpeed");
      const sv = sEl ? parseInt(sEl.value, 10) : 100;
      const cyc = Math.max(100, Math.min(1000, isNaN(sv) ? 100 : sv) * 2);
      arc.style.animationDuration = String(cyc) + "ms";
    } else {
      arc.style.animationDuration = "";
    }
  }
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "嘴部 LED" section for what was tried and ruled out).
function mouthLedApply() {
  const speed = document.getElementById("mouthSpeed").value;
  return Alpha2Api.ledMouthSet( { speed: speed });
}

function mouthLedOff() {
  return Alpha2Api.ledMouthSet( { preset: "off" });
}

// ---------------- Per-LED test lights ----------------
// Bit table (confirmed by testing positions on the real device, see the LedCenter notes):
// head-left P4 forward order [1,2,4,8,16], head-right P3 reverse order [16,8,4,2,1]; the top LED (no. 5) is the wifi slot;
// each eye ring has 8 LEDs, clockwise from 12 o'clock [16,8,4,2,1,128,64,32].
// The hardware is write-only and cannot be read back - display relies on /api/led/state/get (the server stores the last round).
// Pressing a button sends led/head/raw or led/eye/raw, using the color + brightness picked above.
// Preset animations (flash/chase/breathe/dual) run on the hardware itself, and the server does not know the frames -
// the front end simulates them in the mirror with the known timing; long-on/stop/raw show statically from the mask.
const PERLED_HEAD_L_BITS = [1, 2, 4, 8, 16];
const PERLED_HEAD_R_BITS = [16, 8, 4, 2, 1];
const PERLED_EYE_BITS = [16, 8, 4, 2, 1, 128, 64, 32];
let perledHead = { p3: -1, p4: -1, color: -1, brightness: -1 }; // -1 = never written by the server (unknown)
let perledEye = { p3: -1, p4: -1, color: -1, brightness: -1 };
let perledHeadMode = "static";
let perledEyeMode = "static";
let perledBuilt = false;
let perledPollTimer = null;
let perledAnimTimer = null;

// Dual color LED (real-device report, taken literally):
// red/green/blue/cyan/white = blue↔green, yellow = red↔green, purple = red↔blue (the selected color itself never appears).
function perledDualHex(selected, phase) {
  if (selected === 4) return perledColorHex(phase % 2 === 0 ? 1 : 2); // yellow: red↔green
  if (selected === 5) return perledColorHex(phase % 2 === 0 ? 1 : 3); // purple: red↔blue
  return perledColorHex(phase % 2 === 0 ? 3 : 2); // the rest: blue↔green
}

function perledColorHex(code) {
  for (let i = 0; i < LED_COLORS.length; i++) {
    if (LED_COLORS[i].code === code) return LED_COLORS[i].hex;
  }
  return "#9aa0a6";
}

// Mask cleanup: unknown (-1/NaN) becomes 0, clamped to the limit - the client assert will not blow up again.
function perledCleanMask(v, max) {
  v = Number(v);
  if (!(v >= 0)) return 0;
  return Math.min(max, v | 0);
}

function perledMakeDot(num, wifi) {
  const dot = document.createElement("button");
  dot.type = "button";
  dot.className = "perled-dot" + (wifi ? " perled-wifi" : "");
  dot.title = wifi ? (uiLang === "en" ? "#5 wifi slot (off may not actually turn off)" : "#5 wifi 位（熄未必熄到）") : "#" + num;
  return dot;
}

function perledBuild() {
  if (perledBuilt) return;
  perledBuilt = true;
  // Head: two vertical strips of 5 LEDs, left and right; top is no. 5, bottom is no. 1 (like a VU meter counted from below).
  const strips = [["perledHeadL", "p4", PERLED_HEAD_L_BITS], ["perledHeadR", "p3", PERLED_HEAD_R_BITS]];
  strips.forEach(function (cfg) {
    const wrap = document.getElementById(cfg[0]);
    if (!wrap) return;
    wrap.innerHTML = "";
    for (let i = 4; i >= 0; i--) {
      const dot = perledMakeDot(i + 1, i === 4);
      if (i === 4) {
        // LED no. 5 is the wifi slot: pressing toggles red/blue directly (it does not go through the mask; the hardware does not follow that set).
        dot.onclick = function () { sysWifiCycle(); };
      } else {
        (function (mask, bit) {
          dot.onclick = function () { perledToggle("head", mask, bit); };
        })(cfg[1], cfg[2][i]);
      }
      wrap.appendChild(dot);
    }
  });
  // Eyes: 8 LEDs per side in a ring, no. 1 at 12 o'clock, clockwise.
  const rings = [["perledEyeL", "p3"], ["perledEyeR", "p4"]];
  rings.forEach(function (cfg) {
    const wrap = document.getElementById(cfg[0]);
    if (!wrap) return;
    wrap.innerHTML = "";
    for (let i = 0; i < 8; i++) {
      const dot = perledMakeDot(i + 1, false);
      const ang = (-90 + i * 45) * Math.PI / 180;
      dot.style.left = String(Math.round(54 + 44 * Math.cos(ang))) + "px";
      dot.style.top = String(Math.round(54 + 44 * Math.sin(ang))) + "px";
      (function (mask, bit) {
        dot.onclick = function () { perledToggle("eye", mask, bit); };
      })(cfg[1], PERLED_EYE_BITS[i]);
      wrap.appendChild(dot);
    }
  });
  // Animation tick (100ms: flash is exactly 5Hz, matching the hardware p5/p6=100).
  if (!perledAnimTimer) {
    perledAnimTimer = setInterval(function () {
      const tab = document.getElementById("tab-led");
      if (tab && tab.classList.contains("active")) perledRender();
    }, 100);
  }
}

function perledRender() {
  // Paint: static mask lights + simulated preset animations.
  // All phases come from the wall clock + the speed slider (not render counts - poll, animation, and presses
  // all trigger renders, so counting renders would drift): flash period = 2 x speed, chase step = speed,
  // dual toggle = speed, one breath cycle = speed x 12 (minimum 300ms).
  // flash = all blink together; chase = single point sweeping (eyes counter-clockwise; the hardware sweeps even when fully off, so it ignores the mask)
  // breathe = whole zone breathing together; dual = see perledDualHex (real-device reported pairs).
  const now = Date.now();
  function zonePhase(zone) {
    const pace = Math.max(50, perledZonePace(zone));
    const cyc = Math.max(300, pace * 12);
    const ph = (now % cyc) / cyc;
    return {
      flash: Math.floor(now / pace) % 2 === 0,
      chaseStep: Math.floor(now / pace),
      dual: Math.floor(now / pace),
      breathe: 0.35 + 0.65 * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph))
    };
  }
  const headPh = zonePhase("head");
  const eyePh = zonePhase("eye");
  function paint(wrapId, bits, mask, colorCode, brightness, mode, side, zone, count, ph, wifi) {
    const wrap = document.getElementById(wrapId);
    if (!wrap) return;
    const dots = wrap.querySelectorAll(".perled-dot");
    // The head strip DOM order is 5→1 (matching the build); the eye ring is 1→8.
    const order = wrapId.indexOf("Head") >= 0 ? [4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];
    const baseHex = perledColorHex(colorCode);
    // Brightness 1-9 is reflected in the display (opacity): 9 solid, 1 faintest. Breathe multiplies it in.
    const b = (brightness >= 1 && brightness <= 9) ? brightness : 9;
    const brightAlpha = 0.3 + 0.7 * (b / 9);
    // Eye chase runs counter-clockwise: from no. 1 to 8, 7, 6...; the head strip sweeps upward from no. 1.
    const chaseK = zone === "eye" ? (count - (ph.chaseStep % count)) % count : (ph.chaseStep % count);
    // -1 means the server never wrote it: draw fully off (do not use -1 as a mask; the bit positions would scramble).
    const m = mask < 0 ? 0 : mask;
    // Head LED no. 5 (bi===4) is the wifi slot: it follows the real state (red/blue; off/unknown counts as blue -
    // the real device only shows red or blue, one is always lit). The other positions follow the mask. That is how the hardware is; no faking.
    const WIFI_HEX = { red: "#ff3b3b", blue: "#3b6bff" };
    const wifiEff = zone === "head" ? wifiEffective() : null;
    order.forEach(function (bi, di) {
      const dot = dots[di];
      if (!dot) return;
      let on;
      let hex = baseHex;
      if (bi === 4 && wifiEff) {
        on = true;
        hex = WIFI_HEX[wifiEff];
      } else {
        const base = (m & bits[bi]) !== 0;
        on = base;
        if (mode === "flash") {
          on = base && ph.flash;
        } else if (mode === "chase") {
          on = (bi === chaseK);
        } else if (mode === "dual") {
          on = base;
          if (base) hex = perledDualHex(colorCode, ph.dual);
        }
      }
      dot.classList.toggle("perled-on", on);
      dot.style.background = on ? hex : "";
      // All colors get a black border (white disappears on light backgrounds); off restores the CSS default border.
      dot.style.borderColor = on ? "#222" : "";
      // Brightness opacity (the wifi slot does not follow: adjusting brightness does not move the wifi LED).
      // Breathe uses one opacity for the whole zone (x brightness); the others clear it.
      // The wifi slot does not breathe (it is an independent light); it stays solid throughout.
      const isWifiDot = bi === 4 && wifiEff;
      let alpha = "";
      if (on) {
        alpha = isWifiDot ? "1" : String(brightAlpha);
        if (mode === "breathe" && !isWifiDot) alpha = String(ph.breathe * brightAlpha);
      }
      dot.style.opacity = alpha;
    });
  }
  paint("perledHeadL", PERLED_HEAD_L_BITS, perledHead.p4, perledHead.color, perledHead.brightness, perledHeadMode, "L", "head", 5, headPh, wifiMirror);
  paint("perledHeadR", PERLED_HEAD_R_BITS, perledHead.p3, perledHead.color, perledHead.brightness, perledHeadMode, "R", "head", 5, headPh, wifiMirror);
  paint("perledEyeL", PERLED_EYE_BITS, perledEye.p3, perledEye.color, perledEye.brightness, perledEyeMode, "L", "eye", 8, eyePh, null);
  paint("perledEyeR", PERLED_EYE_BITS, perledEye.p4, perledEye.color, perledEye.brightness, perledEyeMode, "R", "eye", 8, eyePh, null);
  // Button text follows zh/en at once (render runs every 100ms, so a language switch needs no wait for the next poll/press).
  if (typeof sysPadRender === "function") sysPadRender(sysPadLast);
  if (typeof muteRender === "function") muteRender();
  if (typeof mouthRender === "function") mouthRender();
}

function perledToggle(zone, mask, bit) {
  // Per-LED presses do not kill the effect: while flash/chase etc. run, send the same preset + the new mask,
  // so the effect continues and the display refreshes at once (pressing during stop switches to long-on). Color + brightness are
  // recorded together, and the display follows.
  // Base rule: our own show follows the current state; someone else's show (disco etc.) starts from all-on and never
  // edits their pattern; never-synced starts from zero and lights LEDs one by one (the eye previously failed exactly
  // here: it treated the disco mask as its own and the re-send was all wrong).
  const max = zone === "head" ? 31 : 255;
  const full = max;
  if (zone === "head") {
    const st = perledHead;
    const base = perledHeadOurs ? st[mask]
      : ((st.p3 >= 0 && st.p4 >= 0) ? full : 0);
    perledHeadOurs = true; // press claims it: the set sent out from now on is ours
    perledHead[mask] = (perledCleanMask(base, max) ^ bit) & max;
    perledHead.color = selectedHeadColor;
    perledHead.brightness = perledReadBrightness("headBrightness");
    perledRender();
    if (lastHeadPreset === "stop") { lastHeadPreset = "long"; perledHeadMode = "static"; }
    ledPresetApply("led/head/set", lastHeadPreset, selectedHeadColor, "headBrightness", perledHead, "headSpeed")
      .then(function () { perledPollOnce(); });
  } else {
    const st = perledEye;
    const base = perledEyeOurs ? st[mask]
      : ((st.p3 >= 0 && st.p4 >= 0) ? full : 0);
    perledEyeOurs = true; // press claims it
    perledEye[mask] = (perledCleanMask(base, max) ^ bit) & max;
    perledEye.color = selectedEyeColor;
    perledEye.brightness = perledReadBrightness("eyeBrightness");
    perledRender();
    lastEyeWasCountdown = false; // per-LED press moves the eye back under preset control
    if (lastEyePreset === "stop") { lastEyePreset = "long"; perledEyeMode = "static"; }
    ledPresetApply("led/eye/set", lastEyePreset, selectedEyeColor, "eyeBrightness", perledEye, "eyeSpeed")
      .then(function () { perledPollOnce(); });
  }
}

// Live mirror: poll the server's last LED state every 100ms (only while the LED tab is open;
// the rhythm LED pushes once per 100ms, a 1-second poll would miss everything). -1 = the server never wrote it; keep as is
// (render draws it as off; sending a preset without a mask = all-on).
// Note: this is a background mirror - failures do not pop a toast (that would flood 10 times per second),
// calls do not overlap (the next waits for the last one), failures auto-back off to 2s, and success returns to 100ms at once.
let perledPollInFlight = false;
let perledPollInterval = 100; // rhythm/display LED 100ms (user request; failures back off to 2s)
let perledPollSeq = 0; // cache-bust counter (old responses must not be eaten by any cache)
function quietStateGet() {
  // Silent channel: bad responses do not pop a toast; they write to console (visible only with F12; the tab has no status line now).
  try {
    // Panel token travels in the Bearer header (see PanelAuth; the query no longer carries the token).
    const hdrs = (typeof panelAuthHeaders === "function") ? panelAuthHeaders() : {};
    let qs = "?_=" + (perledPollSeq++);
    return fetch(API + "alpha2/led/state/get" + qs, { headers: hdrs }).then(function (res) {
      return res.text().then(function (txt) {
        if (!txt) {
          console.warn("[perled] state/get empty body, status=" + res.status);
          return null;
        }
        try {
          // BOM tolerance: if the first char is U+FEFF, cut it before parsing (source unknown; cut it and carry on).
          if (txt.charCodeAt(0) === 0xFEFF) txt = txt.slice(1);
          return JSON.parse(txt);
        } catch (e) {
          // Record length + head/tail + head/tail char codes - tells truncation, middle corruption, BOM, and odd bytes apart.
          console.warn("[perled] state/get bad JSON, status=" + res.status + " len=" + txt.length + ": " + txt.slice(0, 120));
          return null;
        }
      });
    }).catch(function (e) {
      console.warn("[perled] state/get network failure: " + e);
      return null;
    });
  } catch (e) {
    return Promise.resolve(null);
  }
}
function perledApplyState(res) {
    if (res.head) perledHead = { p3: numOrNeg1(res.head.p3), p4: numOrNeg1(res.head.p4), color: numOrNeg1(res.head.color), brightness: numOrNeg1(res.head.brightness) };
    // While the countdown runs, the eye ignores the poll (debugJniLed keeps no state record; following it would cover the real timeline).
    if (res.eye && !eyeCountdownRunning) perledEye = { p3: numOrNeg1(res.eye.p3), p4: numOrNeg1(res.eye.p4), color: numOrNeg1(res.eye.color), brightness: numOrNeg1(res.eye.brightness) };
  if (res.pad && typeof sysPadRender === "function") {
    sysPadLast = res.pad;
    sysPadRender(res.pad);
  }
  if (typeof res.wifi === "string") {
    wifiMirror = res.wifi;
  }
  if (res.mouth) {
    mouthLitMirror = res.mouth.mode === 1;
    if (typeof mouthRender === "function") mouthRender();
  }
  // The chest mute LED has a real state (XiaoZhi connection OR playing indicator): the mute button follows the device color (red on / grey off).
  // Presses keep the optimistic local update (see sysMuteToggle); the poll here calibrates at once - the same discipline as the mouth button.
  if (typeof res.mute === "boolean" && typeof muteRender === "function") {
    if (muteLitLocal !== res.mute) {
      muteLitLocal = res.mute;
      muteRender();
    }
  }
  perledCheckDiverged(res);
  perledRender();
}

// Someone changed the LEDs behind our back (disco is the most common): the server mask does not match
// what we last sent - restore static immediately and draw straight from the mask (follow the real device; do not fake with a stale animation).
// A send/poll collision in that 100ms window is a safe false kill: the mode is remembered before the restore, and the next
// match switches it back (worst case one flicker). A new press clears it (see perledNoteSent).
let perledHeadMismatch = 0;
let perledEyeMismatch = 0;
let perledHeadDivergedFrom = null;
let perledEyeDivergedFrom = null;
function perledSameState(a, b) {
  // Mask + color + brightness must all match to count as the same state: the disco eye is fully on like a preset,
  // with only the color different - matching the mask alone would make the eye always match and never restore!
  return a && b && a.p3 === b.p3 && a.p4 === b.p4
    && (a.color | 0) === (b.color | 0) && (a.brightness | 0) === (b.brightness | 0);
}
function perledCheckDiverged(res) {
  if (res.head && perledHeadSent) {
    if (!perledSameState(res.head, perledHeadSent)) {
      perledHeadMismatch++;
      perledHeadOurs = false; // someone else moved it: this board is no longer ours
      if (perledHeadMode !== "static") {
        perledHeadDivergedFrom = perledHeadMode;
        perledHeadMode = "static";
      }
    } else {
      perledHeadMismatch = 0;
      perledHeadOurs = true; // back in sync: it is ours
      if (perledHeadDivergedFrom && perledHeadMode === "static") {
        perledHeadMode = perledHeadDivergedFrom;
        perledHeadDivergedFrom = null;
      }
    }
  }
  if (res.eye && !eyeCountdownRunning && perledEyeSent) {
    if (!perledSameState(res.eye, perledEyeSent)) {
      perledEyeMismatch++;
      perledEyeOurs = false;
      if (perledEyeMode !== "static") {
        perledEyeDivergedFrom = perledEyeMode;
        perledEyeMode = "static";
      }
    } else {
      perledEyeMismatch = 0;
      perledEyeOurs = true;
      if (perledEyeDivergedFrom && perledEyeMode === "static") {
        perledEyeMode = perledEyeDivergedFrom;
        perledEyeDivergedFrom = null;
      }
    }
  }
}

function perledPollOnce() {
  // A press triggers one instant refresh - uses the silent channel (the set itself toasts on failure, so stay quiet here),
  // and shares the same tick + inFlight with the loop, so it can never collide with itself.
  perledPollInterval = 100;
  if (perledPollTimer) {
    clearTimeout(perledPollTimer);
    perledPollTimer = null;
  }
  perledTickOnce();
}

function numOrNeg1(v) {
  v = Number(v);
  return (v >= 0) ? (v | 0) : -1;
}

function perledTickOnce() {
  perledPollTimer = null;
  // Watchdog: a normal countdown finishes in ~15s max (24 steps x 500ms + 3s white). If after 30s
  // eyeCountdownRunning is still true, the chain lost its tail (older versions had no
  // done-on-reject etc.), and the eye would ignore the poll forever - force restore; do not stay jammed.
  if (typeof eyeCountdownRunning !== "undefined" && eyeCountdownRunning
      && typeof eyeCountdownStartMs !== "undefined"
      && Date.now() - eyeCountdownStartMs > 30000) {
    eyeCountdownRunning = false;
    lastEyeWasCountdown = false;
    if (typeof console !== "undefined" && console.warn) {
      console.warn("[perled] eyeCountdownRunning jammed, force reset");
    }
  }
  const tab = document.getElementById("tab-led");
  if (tab && tab.classList.contains("active") && !perledPollInFlight) {
    perledPollInFlight = true;
    quietStateGet().then(function (res) {
      perledPollInFlight = false;
      if (res && res.ok) {
        perledPollInterval = 100;
        perledApplyState(res);
      } else {
        // Came back but ok=false / bad response / network failure - back off; success returns to 100ms automatically.
        perledPollInterval = Math.min(2000, perledPollInterval * 2);
      }
      perledSchedule();
    });
  } else {
    perledSchedule();
  }
}

function perledSchedule() {
  if (perledPollTimer) clearTimeout(perledPollTimer);
  perledPollTimer = setTimeout(perledTickOnce, perledPollInterval);
}

function perledPollLoop() {
  perledSchedule();
}

