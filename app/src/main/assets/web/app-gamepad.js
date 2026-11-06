// Open Alpha2 — client logic (app-gamepad.js)
// Content: BT gamepad/keyboard tabs (A-side direct link, see GamepadCenter.java).
//
// This page is A-side only (direct robot link): BT HID pairs to the robot's local
// Bluetooth. MainActivity.dispatchKeyEvent/onGenericMotionEvent capture the physical keys, and
// the robot runs the mapping. "Connected devices" lists exactly these devices (InputDevice output).
// The gamepad/keyboard pictures light up live over WebSocket (A-side keys). The mapping
// table has separate gamepad/keyboard zones. (Browser remote control for the B side is removed. The gamepad/key and axis backends stay for direct script calls.)
// All files share the window/global scope (no ES modules). Load order follows the
// <script src="..."> order in index.html.

// Full logical key set (matches GamepadCenter.PAD_KEYS/KB_KEYS, used for grouped display). The name is the key, no prefix.
// Gamepad group (22 keys) plus keyboard group (105 keys). Same-named keys keep separate values and bind on their own.
var GAMEPAD_PAD_GROUPS = [
  { title: "dir", keys: ["up", "down", "left", "right"] },
  { title: "pad", keys: ["a", "b", "x", "y", "start", "select",
    "l1", "r1", "l2", "r2"] },
  { title: "stick_l", keys: ["lx_neg", "lx_pos", "ly_neg", "ly_pos"] },
  { title: "stick_r", keys: ["rx_neg", "rx_pos", "ry_neg", "ry_pos"] }
];
var GAMEPAD_KB_GROUPS = [
  { title: "dir", keys: ["up", "down", "left", "right"] },
  { title: "kbletter", keys: ["a", "b", "c", "d", "e", "f", "g", "h", "i",
    "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
    "u", "v", "w", "x", "y", "z"] },
  { title: "kbdigit", keys: ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"] },
  { title: "kbctrl", keys: ["space", "enter", "tab", "backspace", "esc",
    "shift", "ctrl", "alt", "caps", "vol_up", "vol_down",
    "media_play", "media_next", "media_prev", "media_stop", "mute"] },
  { title: "kbsym", keys: ["minus", "equals", "lbracket", "rbracket",
    "backslash", "semicolon", "quote", "comma", "period", "slash", "grave"] },
  { title: "kbnav", keys: ["ins", "del", "home", "end", "pgup", "pgdn",
    "print", "scroll", "pause", "menu"] },
  { title: "kbnum", keys: ["num_7", "num_8", "num_9", "num_div",
    "num_4", "num_5", "num_6", "num_mul",
    "num_1", "num_2", "num_3", "num_sub",
    "num_0", "num_dot", "num_enter", "num_add"] },
  { title: "fn", keys: ["f1", "f2", "f3", "f4", "f5", "f6",
    "f7", "f8", "f9", "f10", "f11", "f12"] }
];

// Action kinds (servo: the axis and direction dropdowns build servo<N>±. music: pick a function
// (with volume +/-). ring: pick a ringtone (system default phone ring + ringtone names). led: pick part +
// effect + colour. camera: pick photo/track. action: picked from the action dropdown.)
var GAMEPAD_KINDS = ["none", "stop", "servo", "music", "radio", "ring", "led", "camera", "sensor", "voice", "action"];
var GAMEPAD_KIND_KEYS = { none: "gamepad_kind_none", stop: "gamepad_kind_stop",
  servo: "gamepad_kind_servo", music: "gamepad_kind_music", radio: "gamepad_kind_radio",
  ring: "gamepad_kind_ring",
  led: "gamepad_kind_led", camera: "gamepad_kind_camera", sensor: "gamepad_kind_sensor",
  voice: "gamepad_kind_voice", action: "gamepad_kind_action" };
var GAMEPAD_VOICE_FIXED = [["xiaozhi_toggle", "gamepad_voice_xiaozhi"],
  ["vosk", "gamepad_voice_vosk"]];

// Camera (aligned with backend isCameraAction). Tracking is a toggle: face_toggle/color_toggle.
var GAMEPAD_CAMERA_FNS = ["camera_photo", "face_toggle", "color_toggle", "video_toggle"];

// Sensor toggles (aligned with backend isSensorAction). All single-key switches.
var GAMEPAD_SENSOR_FNS = ["sonar_toggle", "pir_toggle", "accel_toggle"];
var GAMEPAD_SENSOR_FN_KEYS = { sonar_toggle: "gamepad_sen_sonar", pir_toggle: "gamepad_sen_pir",
  accel_toggle: "gamepad_sen_accel" };

// Used to re-label in place on language switch (value → i18n key; colour codes: see GAMEPAD_LED_COLOR_KEYS and the three dual pairs).
var GAMEPAD_CAM_FN_KEYS = { camera_photo: "gamepad_cam_photo", face_toggle: "gamepad_cam_face",
  color_toggle: "gamepad_cam_color", video_toggle: "gamepad_cam_video" };
var GAMEPAD_RING_FIXED_KEYS = { ring_phone: "gamepad_ring_phone", ring_stop: "gamepad_ring_stop",
  ring_random: "gamepad_ring_random" };
var GAMEPAD_RING_TYPE_KEYS = { phone: "gamepad_ring_type_phone",
  notification: "gamepad_ring_type_notification" };
var GAMEPAD_LED_PART_KEYS = { head: "gamepad_led_head", eye: "gamepad_led_eye",
  mouth: "gamepad_led_mouth" };
var GAMEPAD_LED_PRESET_KEYS = { long: "gamepad_led_long", flash: "gamepad_led_flash",
  breathe: "gamepad_led_breathe", chase: "gamepad_led_chase", dual: "gamepad_led_dual",
  stop: "gamepad_led_stop", off: "gamepad_led_off", breathing: "gamepad_led_breathing",
  random: "gamepad_led_random" };
var GAMEPAD_DUAL_KEYS = { 3: "gamepad_dual_bg", 4: "gamepad_dual_rg", 5: "gamepad_dual_rb" };

// Head/eye LED: part + effect + colour (aligned with backend isLedAction. Eye has no breathe. Colours: 1 red, 2 green, 3 blue, 4 yellow, 5 purple, 6 cyan, 7 white).
var GAMEPAD_LED_PARTS = ["head", "eye"];
var GAMEPAD_LED_PRESETS = {
  head: ["long", "flash", "breathe", "chase", "dual", "stop"],
  eye: ["long", "flash", "chase", "dual", "stop"]
};
var GAMEPAD_LED_COLOR_KEYS = [["1", "color_name_red"], ["2", "color_name_green"],
  ["3", "color_name_blue"], ["4", "color_name_yellow"], ["5", "color_name_purple"],
  ["6", "color_name_cyan"], ["7", "color_name_white"]];

// Music/volume/light effects (aligned with backend MUSIC_ACTIONS + vol_up/vol_down. toggle: play → stop,
// paused → resume, no song → play the first song. random: one random song. disco_toggle: rhythm-light switch.
// filler_toggle: random-move switch during playback. Radio is its own kind, not here).
var GAMEPAD_MUSIC_FNS = ["music_play", "music_next", "music_prev", "music_pause", "music_resume",
  "music_toggle", "music_stop", "music_random", "disco_toggle", "filler_toggle",
  "vol_up", "vol_down"];
var GAMEPAD_MUSIC_FN_KEYS = { music_play: "gamepad_mfn_play", music_next: "gamepad_mfn_next",
  music_prev: "gamepad_mfn_prev", music_pause: "gamepad_mfn_pause", music_resume: "gamepad_mfn_resume",
  music_toggle: "gamepad_mfn_toggle", music_stop: "gamepad_mfn_stop", music_random: "gamepad_mfn_random",
  disco_toggle: "gamepad_mfn_disco", filler_toggle: "gamepad_mfn_filler",
  vol_up: "gamepad_mfn_volup", vol_down: "gamepad_mfn_voldn", "eq:": "gamepad_mfn_eq" };
// Radio kind (single toggle. Future preset stations are added here too).
var GAMEPAD_RADIO_FN_KEYS = { radio_toggle: "gamepad_mfn_radio" };

// Radio-favourite cache (live data from audio/radio/favorites/list. Users add/remove often, so every render re-fetches).
var __gpRadioFavsCache = null;

function gamepadLoadRadioFavs(force) {
  if (!force && __gpRadioFavsCache) return Promise.resolve(__gpRadioFavsCache);
  return Alpha2Api.audioRadioFavoritesList().then(function (json) {
    var out = [];
    if (json && json.ok && json.favorites) {
      json.favorites.forEach(function (f) {
        if (f && f.name) out.push(f);
      });
    }
    __gpRadioFavsCache = out;
    return out;
  }).catch(function () {
    __gpRadioFavsCache = __gpRadioFavsCache || [];
    return __gpRadioFavsCache;
  });
}

// Build the radio dropdown (switch + favourites. The favourite list arrives later and fills in).
function gamepadBuildRadioSelect(side, k, parsed) {
  var sel = document.createElement("select");
  sel.id = "gpRadioSel_" + side + "_" + k;
  sel.style.maxWidth = "220px";
  sel.style.display = parsed.kind === "radio" ? "inline" : "none";
  gamepadFillRadioSelect(sel, parsed.radioFn);
  return sel;
}

// Fill favourites (stored value radio:<uuid>. An old value missing from the list gets a (?) entry. With no favourites, keep only the switch).
function gamepadFillRadioSelect(sel, cur) {
  if (!sel) return;
  for (var i = sel.options.length - 1; i >= 0; i--) {
    if (sel.options[i].value.indexOf("radio:") === 0) sel.remove(i);
  }
  // Old radio_toggle stored value: keep that entry (no longer offered by default, so a saved value is never wiped on its own).
  if (cur === "radio_toggle") {
    var leg = document.createElement("option");
    leg.value = cur;
    leg.textContent = t("gamepad_mfn_radio");
    leg.selected = true;
    sel.appendChild(leg);
  }
  var favs = __gpRadioFavsCache || [];
  favs.forEach(function (f) {
    var key = f.stationuuid || f.name;
    var v = "radio:" + key;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = f.name;
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("radio:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      // Old name form radio:<name> where the name is in the favourites: no (?), it still plays.
      var bare = cur.substring(6);
      var known = false;
      favs.forEach(function (f) { if (f.name === bare) known = true; });
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = bare + (known ? "" : " (?)");
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
  sel.dataset.radioFilled = favs.length ? "1" : "";
}

// The 5 action classes (sub → class map. The small sub names are no longer listed one by one).
// basic: core (move/gesture/head). dance: dances. story: fable stories.
// yoga: yoga (standing balance/stretch/dynamic kicks). others: the rest + uncategorised.
var GAMEPAD_ACTION_KINDS5 = ["basic", "dance", "story", "yoga", "others"];
var GAMEPAD_ACTION_KIND_KEYS = { basic: "gamepad_act_basic", dance: "gamepad_act_dance",
  story: "gamepad_act_story", yoga: "gamepad_act_yoga", others: "gamepad_act_others" };
var GAMEPAD_ACTION_SUB2KIND = {
  "移動類": "basic", "手勢類": "basic", "頭部類": "basic",
  "兒童歌曲 / 卡通舞蹈": "dance", "流行 / 節奏舞蹈": "dance", "品牌 / 客製舞蹈": "dance",
  "中國寓言": "story", "西方寓言 / 故事": "story",
  "站立式 / 平衡式": "yoga", "伸展式": "yoga", "踢腿 / 動態式": "yoga"
};

// (B-side switch/polling removed: this page only does A-side display + mapping.)

// ---------------- Management: state + Bluetooth switch ----------------

function gamepadRefresh() {
  return Alpha2Api.gamepadStatus().then(function (json) {
    var st0 = document.getElementById("gamepadScanState");
    if (!json || !json.ok) {
      if (st0) st0.textContent = t("gamepad_read_failed");
      return json;
    }
    var bt = json.bt || {};
    gamepadSyncBtToggle(bt.enabled);
    // Draw once from the known switch state first (init defaults both on), then check again when input/get returns.
    gamepadRenderMapping(json.mapping || null);
    Alpha2Api.gamepadInputGet().then(function (ijson) {
      if (ijson && ijson.ok) {
        gamepadSyncInputToggles(!!ijson.pad, !!ijson.kb);
        gamepadRenderMapping(json.mapping || null);
      }
    });
    gamepadRenderMapping(json.mapping || null);
    gamepadSetLastText(gamepadLastText(json.last || null));
    return json;
  }).catch(function (err) {
    var st9 = document.getElementById("gamepadScanState");
    if (st9) st9.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadBtSet(on) {
  return Alpha2Api.gamepadBt({ on: on ? "true" : "false" }).then(function () {
    return gamepadRefresh();
  });
}

function gamepadBtToggle() {
  var el = document.getElementById("gamepadBtToggle");
  return gamepadBtSet(!!(el && el.checked));
}

// Panel switch state follows the real robot state (gamepadRefresh syncs every run, so the display never disagrees with reality).
function gamepadSyncBtToggle(enabled) {
  var el = document.getElementById("gamepadBtToggle");
  if (el) el.checked = !!enabled;
}

function gamepadInputToggle(side) {
  var el = document.getElementById(side === "kb" ? "gamepadInputKbToggle" : "gamepadInputPadToggle");
  return Alpha2Api.gamepadInputSet({ side: side, on: (el && el.checked) ? "true" : "false" }).then(function () {
    return gamepadRefresh();
  });
}

function gamepadSyncInputToggles(padOn, kbOn) {
  var p = document.getElementById("gamepadInputPadToggle");
  if (p) p.checked = padOn !== false;
  var k = document.getElementById("gamepadInputKbToggle");
  if (k) k.checked = kbOn !== false;
  window.__gpPadOn = padOn !== false;
  window.__gpKbOn = kbOn !== false;
}

// ---------------- A-side pairing: scan + bond ----------------
// Flow: put the gamepad in pairing mode → gamepadScan() (~12 s, found devices push live over WS
// gamepad_bt, plus one poll here until the scan ends) → press Pair in the list → gamepad/pair → wait
// for the gamepad_bt bond event (visible in Event Log) → _done scans once more to confirm bonded.

function gamepadBondText(bond) {
  if (bond === "bonded") return "✅ " + t("gamepad_bonded");
  if (bond === "bonding") return "… " + t("gamepad_bonding");
  return "";
}

function gamepadRenderFound(found, bonded) {
  var wrap = document.getElementById("gamepadFoundOut");
  if (!wrap) return;
  wrap.innerHTML = "";
  var all = [];
  (bonded || []).forEach(function (d) {
    d._bondedList = true;
    all.push(d);
  });
  (found || []).forEach(function (d) {
    var dup = all.some(function (x) { return x.address === d.address; });
    if (!dup) all.push(d);
  });
  if (!all.length) {
    wrap.textContent = t("gamepad_found_empty");
    return;
  }
  all.forEach(function (d) {
    var row = document.createElement("div");
    row.className = "row";
    row.style.alignItems = "center";
    var label = document.createElement("span");
    label.style.flex = "1";
    var icon = d.kind === "keyboard" ? "⌨️" : "🎮";
    label.textContent = icon + " " + (d.name || "(unknown)") + "  " + d.address + "  " + gamepadBondText(d.bond);
    row.appendChild(label);
    if (d.bond === "bonded") {
      // Bonded does not always mean connected. If the input list misses it, press Connect to link it. Pressing Connect while already linked is harmless.
      // Press Unpair to drop a device you do not want.
      var cbtn = document.createElement("button");
      cbtn.className = "secondary";
      cbtn.textContent = t("gamepad_connect_btn");
      cbtn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_connecting");
          // The 5-second result (re-check + result wording) runs inside gamepadConnect. This part only collects the error.
          gamepadConnect(addr).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_connect_btn");
          });
        };
      })(d.address, cbtn);
      row.appendChild(cbtn);
      var ubtn = document.createElement("button");
      ubtn.className = "danger-btn";
      ubtn.textContent = t("gamepad_unpair_btn");
      ubtn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_unpairing");
          gamepadUnpair(addr).then(function () {
            // Unpair takes effect at once: rescan + refresh the connected-device list after 4 s to confirm.
            setTimeout(function () { gamepadScan(); gamepadRefresh(); }, 4000);
          }).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_unpair_btn");
          });
        };
      })(d.address, ubtn);
      row.appendChild(ubtn);
    }
    if (d.bond !== "bonded") {
      var btn = document.createElement("button");
      btn.textContent = t("gamepad_pair_btn");
      btn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_pairing");
          gamepadPair(addr).then(function () {
            // Pairing takes a few seconds: auto rescan after 6 s to confirm. Bond events during the wait land in the Event Log.
            setTimeout(gamepadScan, 6000);
          }).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_pair_btn");
          });
        };
      })(d.address, btn);
      row.appendChild(btn);
    }
    wrap.appendChild(row);
  });
}

function gamepadScan() {
  var st = document.getElementById("gamepadScanState");
  if (st) st.textContent = t("gamepad_scanning");
  return Alpha2Api.gamepadScan().then(function (json) {
    if (json && json.ok) {
      gamepadRenderFound(json.found, json.bonded);
      if (st) st.textContent = json.scanning ? t("gamepad_scanning") : t("gamepad_scan_done");
      // During a scan (~12 s window): auto re-scan after 8 s so a late device is not missed.
      if (json.scanning) {
        setTimeout(function () {
          Alpha2Api.gamepadScan().then(function (j2) {
            if (j2 && j2.ok) {
              gamepadRenderFound(j2.found, j2.bonded);
              if (st) st.textContent = j2.scanning ? t("gamepad_scanning") : t("gamepad_scan_done");
            }
          });
        }, 8000);
      }
    } else if (st) {
      st.textContent = (json && json.error) || t("gamepad_read_failed");
    }
    return json;
  }).catch(function (err) {
    if (st) st.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadConnect(address) {
  return Alpha2Api.gamepadConnect({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    var ok = !!(json && json.ok);
    if (st) {
      st.textContent = ok ? t("gamepad_connecting") : t("gamepad_read_failed");
    }
    if (ok) {
      // The connect hint must not stick: re-check after 5 s and replace it with the result wording.
      setTimeout(function () {
        gamepadRefresh();
        var st2 = document.getElementById("gamepadScanState");
        if (st2) st2.textContent = t("gamepad_connected");
      }, 5000);
    }
    return json;
  });
}

function gamepadUnpair(address) {
  return Alpha2Api.gamepadUnpair({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    if (st) {
      st.textContent = (json && json.ok) ? t("gamepad_unpaired") : t("gamepad_read_failed");
    }
    return json;
  });
}

// Last-key row: key ↓/↑ → action [source] ✓/✗ (record only).
// taken=false (keyboard direct to HTML only, release events, play failure) shows ✗, so one look separates
// "an action name exists" from "the action really ran".
function gamepadLastText(d) {
  if (!d || typeof d !== "object" || !d.key) return "-";
  var k = d.key;
  var arrow = (d.pressed === false) ? " ↑" : " ↓";
  var s = (k === "unmapped" && d.code != null ? (k + "(" + d.code + ")") : k)
    + arrow + " → " + (d.action || "-") + " [" + (d.source || "-") + "]"
    + (d.taken === true ? " ✓" : (d.taken === false ? " " + t("gamepad_taken_no") : ""));
  return s;
}

// Write the last key to both cards (the same row under the gamepad picture and the keyboard picture).
function gamepadSetLastText(s) {
  ["gamepadLastOut", "gamepadKbLastOut"].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.textContent = s;
  });
}

// ---------------- WS realtime: live last-key refresh ----------------
// When app-log.js appendLog() gets gamepad_key/gamepad_bt/gamepad_axis, it forwards them here.
function onGamepadKeyEvent(type, data) {
  if (!data || typeof data !== "object") return;
  if (type === "gamepad_key") {
    // The two pictures + the mapping row light up together (colour follows the mapping: the WS event carries action).
    gamepadLightLogical(data.key, data.pressed !== false, data.action);
    // Release does not refresh the text (the backend last does not record releases either. This is a second guard so the display never flickers to ↑✗).
    if (data.pressed === false) return;
    gamepadSetLastText(gamepadLastText(data));
  } else if (type === "gamepad_axis") {
    // A-side joystick moves go through here.
    gamepadMoveNubs(Number(data.lx || 0), Number(data.ly || 0),
      Number(data.rx || 0), Number(data.ry || 0));
    try { cameraPadMirrorAxis(data); } catch (e) {}
  } else if (type === "gamepad_state") {
    // After a mapping toggle the robot pushes the new state, so the control follows at once (no need to reload the page).
    gamepadApplyToggleState(data);
  } else if (type === "gamepad_bt" && data.event) {
    // When bond/scan_done lands in the Event Log, refresh the management card state too.
    if (data.event === "bond" || data.event === "scan_done" || data.event === "unbond") {
      if (typeof gamepadRefresh === "function") {
        setTimeout(gamepadRefresh, 1500);
      }
    }
  }
}

// Mapping toggle writes the new state straight to the control (gamepad_state event. Same result as each page's enter-page sync, without waiting for them).
function gamepadApplyToggleState(d) {
  if (!d || typeof d !== "object") return;
  try {
    if (d.domain === "sonar" && typeof d.thresholdCm === "number") {
      var th = Math.max(0, Math.min(100, d.thresholdCm));
      var slider = document.getElementById("sonarDist");
      var val = document.getElementById("sonarDistVal");
      var tg = document.getElementById("sonarToggle");
      if (slider) slider.value = String(th);
      if (val) val.textContent = th + " cm";
      if (tg) tg.checked = th > 0;
      try { sonarThresholdCm = th; } catch (e) {}
      if (typeof drawSonarChart === "function") { try { drawSonarChart(); } catch (e) {} }
    } else if (d.domain === "pir" && typeof d.enabled === "boolean") {
      var hw = document.getElementById("alpha2Pir");
      if (hw) hw.checked = d.enabled;
    } else if (d.domain === "accel" && typeof d.enabled === "boolean") {
      var ac = document.getElementById("accelToggle");
      if (ac) ac.checked = d.enabled;
    } else if (d.domain === "disco" && typeof d.enabled === "boolean") {
      var dt = document.getElementById("musicDiscoToggle");
      if (dt) dt.checked = d.enabled;
      var dl = document.getElementById("musicDiscoStateLabel");
      if (dl) dl.textContent = t(d.enabled ? "music_disco_on" : "music_disco_off");
      try { musicDiscoEnabled = d.enabled; } catch (e) {}
    } else if (d.domain === "filler" && typeof d.enabled === "boolean") {
      var ft = document.getElementById("musicFillerToggle");
      if (ft) ft.checked = d.enabled;
      var fl = document.getElementById("musicFillerStateLabel");
      if (fl) fl.textContent = t(d.enabled ? "music_filler_on" : "music_filler_off");
    } else if (d.domain === "facetrack" && typeof d.running === "boolean") {
      try { faceTrackRunning = d.running; } catch (e) {}
      var ftg = document.getElementById("faceTrackToggle");
      if (ftg) ftg.checked = d.running;
      if (d.running) {
        try { faceTrackStartPolling(); } catch (e) {}
        try { faceTrackRefreshStatus(); } catch (e) {}
      } else {
        try { faceTrackStopPolling(); } catch (e) {}
        try { faceTrackRenderBox({ running: false }); } catch (e) {}
      }
    } else if (d.domain === "colortrack" && typeof d.running === "boolean") {
      try { colorTrackRunning = d.running; } catch (e) {}
      var ctg = document.getElementById("colorTrackToggle");
      if (ctg) ctg.checked = d.running;
      if (d.running) {
        try { colorTrackStartPolling(); } catch (e) {}
        try { colorTrackRefreshStatus(); } catch (e) {}
      } else {
        try { colorTrackStopPolling(); } catch (e) {}
        try { colorTrackRenderBox({ running: false }); } catch (e) {}
      }
    }
  } catch (e) {}
}

function gamepadPair(address) {
  return Alpha2Api.gamepadPair({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    if (st) {
      st.textContent = (json && json.bonded) ? t("gamepad_bonded")
        : ((json && json.ok) ? t("gamepad_pairing") : t("gamepad_read_failed"));
    }
    return json;
  });
}

// ---------------- Management: mapping table ----------------

// Action-list cache (live action/list data first; fall back to the embedded 202 catalog).
// Grouping comes from action_classification.json (static, fetched from the same directory).
var __gpActionsCache = null;
var __gpActionCatCache = null;
// id → action object (used to re-label action names in place on language switch; recorded while filling dropdowns).
var __gpActionById = {};

function gamepadActionDisplayName(a) {
  // Show only the Chinese/English name, not numeric ids (14xxxxx/15xxxxx). Stored values still use the id, so nothing changes.
  return (uiLang === "en" ? (a.nameEn || a.nameCn) : (a.nameCn || a.nameEn)) || a.id;
}

function gamepadLoadActions() {
  if (__gpActionsCache && __gpActionCatCache) return Promise.resolve(__gpActionsCache);
  var acts = null;
  return Alpha2Api.actionList().then(function (json) {
    if (json && json.ok && json.actions && json.actions.length) {
      acts = json.actions;
    } else {
      return fetch("xiaozhi_actions.json").then(function (r) { return r.json(); }).then(function (j) {
        acts = j;
        return fetch("action_classification.json");
      }).then(function (r) { return r.json(); }).then(function (cat) {
        __gpActionCatCache = cat || {};
        __gpActionsCache = acts;
        return acts;
      });
    }
    // Live data arrived: the classification table still reads the static file.
    return fetch("action_classification.json").then(function (r) { return r.json(); }).then(function (cat) {
      __gpActionCatCache = cat || {};
      __gpActionsCache = acts;
      return acts;
    }).catch(function () {
      __gpActionCatCache = __gpActionCatCache || {};
      __gpActionsCache = acts;
      return acts;
    });
  }).catch(function () {
    __gpActionsCache = __gpActionsCache || [];
    __gpActionCatCache = __gpActionCatCache || {};
    return __gpActionsCache;
  });
}

function gamepadActionGroupOf(id) {
  try {
    var c = (__gpActionCatCache || {})[id];
    if (c && c.sub) return c.sub;
  } catch (e) { /* ignore */ }
  return "其他";
}

// Value parse: none/stop return directly; music_*/vol_* map to the music kind;
// ring_phone/ring_stop/ring_random/ring:[phone:|notification:]<title> map to the ring kind; head:/eye: map to the led kind;
// servo<N>± splits the axis; action:<id> splits the id.
function gamepadParseValue(v) {
  var out = { kind: "none", servoId: 20, servoDir: "+", musicFn: "music_play",
    ringSel: "ring_phone", ledPart: "head", ledPreset: "flash", ledColor: "1",
    ledSpeed: "500", cameraFn: "camera_photo", sensorFn: "sonar_toggle",
    voiceFn: "xiaozhi_toggle", radioFn: "radio_toggle", eqPreset: "normal", actionId: "" };
  if (!v) return out;
  if (v === "none" || v === "stop") {
    out.kind = v;
    return out;
  }
  if (GAMEPAD_CAMERA_FNS.indexOf(v) !== -1) {
    out.kind = "camera";
    out.cameraFn = v;
    return out;
  }
  if (GAMEPAD_SENSOR_FNS.indexOf(v) !== -1) {
    out.kind = "sensor";
    out.sensorFn = v;
    return out;
  }
  if (v === "ring_phone" || v === "ring_stop" || v === "ring_random" || v.indexOf("ring:") === 0) {
    out.kind = "ring";
    out.ringSel = v;
    return out;
  }
  if (v === "head:stop" || v === "eye:stop" || v === "mouth:off"
      || v === "head:random" || v === "eye:random"
      || v.match(/^(head|eye):[a-z]+:[1-7]$/) || v.match(/^mouth:breathing:\d+$/)) {
    out.kind = "led";
    var lp = v.split(":");
    out.ledPart = lp[0];
    out.ledPreset = (lp[0] === "mouth" && lp[1] === "breathing") ? "breathing" : lp[1];
    out.ledColor = lp[2] || "1";
    if (lp[0] === "mouth" && lp[1] === "breathing") out.ledSpeed = lp[2] || "500";
    return out;
  }
  if (v === "radio_toggle" || v.indexOf("radio:") === 0) {
    out.kind = "radio";
    out.radioFn = v;
    return out;
  }
  if (GAMEPAD_MUSIC_FNS.indexOf(v) !== -1 || v.indexOf("eq:") === 0) {
    out.kind = "music";
    if (v.indexOf("eq:") === 0) {
      out.musicFn = "eq:";
      out.eqPreset = v.substring(3) || "normal";
    } else {
      out.musicFn = v;
    }
    return out;
  }
  if (v === "xiaozhi_toggle" || v === "vosk_toggle" || v.indexOf("vosk:") === 0) {
    out.kind = "voice";
    out.voiceFn = v;
    return out;
  }
  var m = v.match(/^servo(\d{1,2})([+-])$/);
  if (m) {
    out.kind = "servo";
    out.servoId = Math.max(1, Math.min(22, parseInt(m[1], 10) || 22));
    out.servoDir = m[2];
    return out;
  }
  if (v.indexOf("action:") === 0) {
    out.kind = "action";
    out.actionId = v.substring(7);
    return out;
  }
  return out;
}

// Build the servo single dropdown (#1+/#1-/#2+...#22-, 44 entries. The id prefixes the side's group name, so same-named keys in the two zones never clash).
function gamepadBuildServoControls(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpServoWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "servo" ? "inline" : "none";
  var sel = document.createElement("select");
  sel.id = "gpServo_" + side + "_" + k;
  var cur = "servo" + parsed.servoId + parsed.servoDir;
  for (var i = 1; i <= 22; i++) {
    ["+", "-"].forEach(function (d) {
      var o = document.createElement("option");
      o.value = "servo" + i + d;
      o.textContent = "#" + i + d;
      if (o.value === cur) o.selected = true;
      sel.appendChild(o);
    });
  }
  wrap.appendChild(sel);
  return wrap;
}

// Action class lookup (same rule as the action tab: use the action's own type; before the cache arrives, use the classification sub as a stopgap).
function gamepadActionKindOf(id) {
  var acts = __gpActionsCache || [];
  for (var i = 0; i < acts.length; i++) {
    if (acts[i] && acts[i].id === id && typeof categoryOf === "function") {
      return categoryOf(acts[i].type);
    }
  }
  var sub = gamepadActionGroupOf(id);
  return GAMEPAD_ACTION_SUB2KIND[sub] || "others";
}

// Build the 202-action cascading dropdown (class first, then actions inside it. Before data arrives, show the hint only; fill after load).
function gamepadBuildActionSelect(side, k, parsed) {
  var pfx = side + "_";
  var wrap = document.createElement("span");
  wrap.id = "gpActionWrap_" + pfx + k;
  wrap.style.display = parsed.kind === "action" ? "inline" : "none";
  var kindSel = document.createElement("select");
  kindSel.id = "gpActionKind_" + pfx + k;
  GAMEPAD_ACTION_KINDS5.forEach(function (g) {
    var ko = document.createElement("option");
    ko.value = g;
    ko.textContent = t(GAMEPAD_ACTION_KIND_KEYS[g] || g);
    kindSel.appendChild(ko);
  });
  var sel = document.createElement("select");
  sel.id = "gpActionSel_" + pfx + k;
  sel.style.maxWidth = "220px";
  wrap.appendChild(kindSel);
  wrap.appendChild(sel);
  kindSel.onchange = (function (s, kk) {
    return function () {
      gamepadFillActionRow(s, kk, this.value, "");
    };
  })(side, k);
  gamepadFillActionSelectEl(kindSel, sel, null, parsed.actionId);
  return wrap;
}

// Fill one row's action dropdown (empty cat: derive the class from the current value. A curId missing from the class gets a (?) entry).
function gamepadFillActionRow(side, k, cat, curId) {
  var pfx = side + "_";
  gamepadFillActionSelectEl(
    document.getElementById("gpActionKind_" + pfx + k),
    document.getElementById("gpActionSel_" + pfx + k), cat, curId);
}

// Element version (not in the DOM yet during build, so no getElementById).
// dataset.actionFilled marks a fill done with real data (late-fill must not count options: missing entries also count).
function gamepadFillActionSelectEl(kindSel, sel, cat, curId) {
  if (!sel) return;
  if (!cat) cat = (curId ? gamepadActionKindOf(curId) : GAMEPAD_ACTION_KINDS5[0]);
  if (kindSel) kindSel.value = cat;
  sel.innerHTML = "";
  var ph = document.createElement("option");
  ph.value = "";
  ph.textContent = "— " + t("gamepad_pick_action") + " —";
  sel.appendChild(ph);
  var acts = __gpActionsCache || [];
  acts.forEach(function (a) {
    if (a && a.id) __gpActionById[a.id] = a;
    if (gamepadActionKindOf(a.id) !== cat) return;
    var o = document.createElement("option");
    o.value = a.id;
    o.textContent = gamepadActionDisplayName(a);
    if (a.id === curId) o.selected = true;
    sel.appendChild(o);
  });
  // Current value not in the list (deleted action): add an entry so the user still sees it.
  if (curId) {
    var found = false;
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === curId) { found = true; break; }
    }
    if (!found) {
      var miss = document.createElement("option");
      miss.value = curId;
      miss.textContent = curId + " (?)";
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
  sel.dataset.actionFilled = acts.length ? "1" : "";
}

// Ringtone-name cache (phone/notification tables; live audio/ringtones/list data, lazy-loaded like the action list).
var __gpRingTitlesCache = {};

// Pull the ringtone table name from a value (a non-ringtone value returns phone; old-style ring: looks its name up in the phone table).
function gamepadRingTypeOf(v) {
  if (v && v.indexOf("ring:notification:") === 0) return "notification";
  return "phone";
}

// Pull the bare title from a value (the three fixed entries return ""; old style strips the ring: prefix).
function gamepadRingBareTitle(v) {
  if (!v) return "";
  if (v.indexOf("ring:notification:") === 0) return v.substring("ring:notification:".length);
  if (v.indexOf("ring:phone:") === 0) return v.substring("ring:phone:".length);
  if (v.indexOf("ring:") === 0) return v.substring("ring:".length);
  return "";
}

function gamepadLoadRingTitles(type) {
  type = (type === "notification") ? "notification" : "phone";
  if (__gpRingTitlesCache[type]) return Promise.resolve(__gpRingTitlesCache[type]);
  var apiType = (type === "notification") ? "notification" : "ringtone";
  return Alpha2Api.audioRingtonesList({ type: apiType }).then(function (json) {
    var out = [];
    if (json && json.ok && json.sounds) {
      json.sounds.forEach(function (s) {
        if (s && s.title) out.push(s.title);
      });
    }
    __gpRingTitlesCache[type] = out;
    return out;
  }).catch(function () {
    __gpRingTitlesCache[type] = __gpRingTitlesCache[type] || [];
    return __gpRingTitlesCache[type];
  });
}

// Build the two ringtone dropdowns (system default phone ring + ring stop + random + phone/notification tables; the name list arrives later and fills in).
function gamepadBuildRingSelect(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpRingWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "ring" ? "inline" : "none";
  var typeSel = document.createElement("select");
  typeSel.id = "gpRingType_" + side + "_" + k;
  [["phone", "gamepad_ring_type_phone"], ["notification", "gamepad_ring_type_notification"]].forEach(function (p) {
    var to = document.createElement("option");
    to.value = p[0];
    to.textContent = t(p[1]);
    if (p[0] === gamepadRingTypeOf(parsed.ringSel)) to.selected = true;
    typeSel.appendChild(to);
  });
  var sel = document.createElement("select");
  sel.id = "gpRingSel_" + side + "_" + k;
  sel.style.maxWidth = "200px";
  var fixed = [["ring_phone", "gamepad_ring_phone"], ["ring_stop", "gamepad_ring_stop"], ["ring_random", "gamepad_ring_random"]];
  fixed.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f[0];
    o.textContent = t(f[1]);
    if (parsed.ringSel === f[0]) o.selected = true;
    sel.appendChild(o);
  });
  wrap.appendChild(typeSel);
  wrap.appendChild(sel);
  typeSel.onchange = (function (s, kk) {
    return function () {
      var pfx2 = s + "_";
      var sel2 = document.getElementById("gpRingSel_" + pfx2 + kk);
      var curText = "";
      if (sel2 && sel2.selectedIndex >= 0) curText = sel2.options[sel2.selectedIndex].text;
      gamepadFillRingSelect(sel2, this.value, "");
      // For duplicate names the match follows the newer table (clashes between the two tables are common; the three fixed names never clash with a title).
      if (sel2 && curText) {
        for (var i = 0; i < sel2.options.length; i++) {
          if (sel2.options[i].text === curText && sel2.options[i].value.indexOf("ring:") === 0) {
            sel2.selectedIndex = i;
            break;
          }
        }
      }
    };
  })(side, k);
  gamepadFillRingSelect(sel, gamepadRingTypeOf(parsed.ringSel), parsed.ringSel);
  return wrap;
}

function gamepadFillRingSelect(sel, type, cur) {
  if (!sel) return;
  type = (type === "notification") ? "notification" : "phone";
  var prefix = (type === "notification") ? "ring:notification:" : "ring:phone:";
  var titles = __gpRingTitlesCache[type] || [];
  // Before a refill, clear old list entries (the three fixed entries have no colon and stay; missing/old values have colons, so clear them all and re-add).
  for (var i = sel.options.length - 1; i >= 0; i--) {
    if (sel.options[i].value.indexOf("ring:") === 0) sel.remove(i);
  }
  if (!titles.length) {
    // Name list not arrived: if the current value is a ringtone, add an entry so it stays visible (same as the action case).
    if (cur && cur.indexOf("ring:") === 0) {
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = gamepadRingBareTitle(cur);
      miss.selected = true;
      sel.appendChild(miss);
    }
    sel.dataset.ringType = type;
    sel.dataset.ringFilled = "";
    return;
  }
  titles.forEach(function (title) {
    var v = prefix + title;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = title;
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("ring:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      // Old-style ring: value whose name is in the table (only the prefix is missing): no (?), it still plays.
      var bare = gamepadRingBareTitle(cur);
      var miss2 = document.createElement("option");
      miss2.value = cur;
      miss2.textContent = bare + (titles.indexOf(bare) === -1 ? " (?)" : "");
      miss2.selected = true;
      sel.appendChild(miss2);
    }
  }
  sel.dataset.ringType = type;
  sel.dataset.ringFilled = "1";
}

// Build the three LED dropdowns (part + effect + colour. Effects change with the part; dual swaps in the 3-pair colour list; colour names follow the language).
// LED colour dropdown (dual uses the LED tab's set: 3=blue-green, 4=red-green, 5=red-blue. Other codes
// also come out blue-green, so they are not offered. See DUAL_COLORS in app-led.js; a wrong current value falls back to the first entry).
function gamepadFillLedColors(colorSel, dual, curVal) {
  if (!colorSel) return;
  colorSel.innerHTML = "";
  var list = dual ? [["3", "gamepad_dual_bg"], ["4", "gamepad_dual_rg"], ["5", "gamepad_dual_rb"]]
    : GAMEPAD_LED_COLOR_KEYS;
  var want = String(curVal != null ? curVal : "1");
  var hasWant = false;
  list.forEach(function (c) { if (c[0] === want) hasWant = true; });
  if (!hasWant) want = list[0][0];
  list.forEach(function (c) {
    var o = document.createElement("option");
    o.value = c[0];
    o.textContent = t(c[1]);
    if (c[0] === want) o.selected = true;
    colorSel.appendChild(o);
  });
}

function gamepadBuildLedControls(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpLedWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "led" ? "inline" : "none";
  var part = document.createElement("select");
  part.id = "gpLedPart_" + side + "_" + k;
  [["head", "gamepad_led_head"], ["eye", "gamepad_led_eye"], ["mouth", "gamepad_led_mouth"]].forEach(function (p) {
    var o = document.createElement("option");
    o.value = p[0];
    o.textContent = t(p[1]);
    if (p[0] === parsed.ledPart) o.selected = true;
    part.appendChild(o);
  });
  var preset = document.createElement("select");
  preset.id = "gpLedPreset_" + side + "_" + k;
  var color = document.createElement("select");
  color.id = "gpLedColor_" + side + "_" + k;
  gamepadFillLedColors(color, false, parsed.ledColor);
  // Mouth speed (used only with breathing, 100-1000 ms. The backend validates it too).
  var speed = document.createElement("input");
  speed.id = "gpLedSpeed_" + side + "_" + k;
  speed.type = "number";
  speed.min = "100";
  speed.max = "1000";
  speed.step = "50";
  speed.style.width = "76px";
  speed.value = parsed.ledSpeed || "500";
  speed.style.display = "none";
  var refill = function () {
    var isMouth = part.value === "mouth";
    var list = isMouth ? [["breathing", "gamepad_led_breathing"], ["off", "gamepad_led_off"]]
      : (part.value === "eye"
        ? [["long", "gamepad_led_long"], ["flash", "gamepad_led_flash"], ["chase", "gamepad_led_chase"], ["dual", "gamepad_led_dual"], ["stop", "gamepad_led_stop"], ["random", "gamepad_led_random"]]
        : [["long", "gamepad_led_long"], ["flash", "gamepad_led_flash"], ["breathe", "gamepad_led_breathe"], ["chase", "gamepad_led_chase"], ["dual", "gamepad_led_dual"], ["stop", "gamepad_led_stop"], ["random", "gamepad_led_random"]]);
    preset.innerHTML = "";
    list.forEach(function (p) {
      var o3 = document.createElement("option");
      o3.value = p[0];
      o3.textContent = t(p[1]);
      if (p[0] === parsed.ledPreset) o3.selected = true;
      preset.appendChild(o3);
    });
    syncLedExtras();
  };
  var syncLedExtras = function () {
    var isMouth = part.value === "mouth";
    var stopping = (!isMouth && preset.value === "stop") || (isMouth && preset.value === "off");
    var noColor = stopping || (!isMouth && preset.value === "random");
    // dual switches to the 3-pair list, the rest switch back to 7 colours (keep the current value; only a wrong one falls back to the first).
    gamepadFillLedColors(color, !isMouth && preset.value === "dual", color.value);
    // Head/eye stop/random need no colour; the mouth needs a speed only for breathing; off needs nothing.
    color.style.display = (!isMouth && !noColor) ? "inline" : "none";
    speed.style.display = (isMouth && preset.value === "breathing") ? "inline" : "none";
  };
  part.onchange = refill;
  preset.onchange = syncLedExtras;
  refill();
  wrap.appendChild(part);
  wrap.appendChild(preset);
  wrap.appendChild(color);
  wrap.appendChild(speed);
  return wrap;
}

function gamepadCacheSide(side, map) {
  var key = side === "kb" ? "__gpKbCache" : "__gpPadCache";
  try { window[key] = JSON.parse(JSON.stringify(map)); } catch (e) { window[key] = map; }
}
function gamepadCachedSide(side) {
  return window[side === "kb" ? "__gpKbCache" : "__gpPadCache"] || {};
}

// Mapping-table visibility: follows the mapping switch inside each card (default closed, saved in localStorage; shown only when opened).
// This is separate from the execution switch (the A-side direct-connect button).
function gamepadMappingShown(side) {
  try {
    return localStorage.getItem(side === "kb" ? "gamepad_mapping_kb" : "gamepad_mapping_pad") === "1";
  } catch (e) {
    return false;
  }
}

function gamepadMappingToggle(side) {
  var el = document.getElementById(side === "kb" ? "gamepadMappingKbToggle" : "gamepadMappingPadToggle");
  var on = !el || !!el.checked;
  try {
    localStorage.setItem(side === "kb" ? "gamepad_mapping_kb" : "gamepad_mapping_pad", on ? "1" : "0");
  } catch (e) { /* ignore */ }
  gamepadRenderMapping(null);
}

function gamepadSyncMappingToggles() {
  var p = document.getElementById("gamepadMappingPadToggle");
  if (p) p.checked = gamepadMappingShown("pad");
  var k = document.getElementById("gamepadMappingKbToggle");
  if (k) k.checked = gamepadMappingShown("kb");
}

function gamepadRenderMapping(mapping) {
  var padMap = null, kbMap = null;
  if (mapping && mapping.pad) {
    padMap = mapping.pad;
    kbMap = mapping.kb || {};
    gamepadCacheSide("pad", padMap);
    gamepadCacheSide("kb", kbMap);
  } else {
    padMap = gamepadCachedSide("pad");
    kbMap = gamepadCachedSide("kb");
  }
  gamepadRenderSideGrid("pad", GAMEPAD_PAD_GROUPS, padMap);
  gamepadRenderSideGrid("kb", GAMEPAD_KB_GROUPS, kbMap);
  // Action list/ringtone list arrive late: draw the skeleton first (empty dropdowns), then fill both zones after load.
  gamepadLoadActions().then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid) return;
      var sels = grid.querySelectorAll("select[id^='gpActionSel_" + side + "_']");
      for (var i = 0; i < sels.length; i++) {
        var k = sels[i].id.substring(("gpActionSel_" + side + "_").length);
        var cur = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k] || "none");
        // Fill when a real-data fill has not happened yet (do not count options: missing entries also inflate the count).
        if (!sels[i].dataset.actionFilled) gamepadFillActionRow(side, k, null, cur.actionId);
      }
    });
  });
  Promise.all([gamepadLoadRingTitles("phone"), gamepadLoadRingTitles("notification")]).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid2 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid2) return;
      var rsels = grid2.querySelectorAll("select[id^='gpRingSel_" + side + "_']");
      for (var j = 0; j < rsels.length; j++) {
        var k2 = rsels[j].id.substring(("gpRingSel_" + side + "_").length);
        var cur2 = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k2] || "none");
        var rt = gamepadRingTypeOf(cur2.ringSel);
        // Skip when the table matches and a real-data fill happened; refill when the table mismatches or no data fill happened yet.
        if (rsels[j].dataset.ringType !== rt || !rsels[j].dataset.ringFilled) {
          gamepadFillRingSelect(rsels[j], rt, cur2.ringSel);
        }
      }
    });
  });
  // Package lists differ per robot, so re-fetch every render (no stale cache); refill only rows holding just the hint entry.
  gamepadLoadVoskPacks(true).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid3 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid3) return;
      var psels = grid3.querySelectorAll("select[id^='gpVoskPackSel_" + side + "_']");
      for (var k3 = 0; k3 < psels.length; k3++) {
        var k4 = psels[k3].id.substring(("gpVoskPackSel_" + side + "_").length);
        var cur3 = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k4] || "none");
        if (!psels[k3].dataset.voiceFilled) gamepadFillVoskPacks(psels[k3], cur3.voiceFn);
      }
    });
  });
  // Favourites change often, so re-fetch every render; refill only dropdowns holding just the switch entry (more entries mean data exists or the user is picking).
  gamepadLoadRadioFavs(true).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid4 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid4) return;
      var fsels = grid4.querySelectorAll("select[id^='gpRadioSel_" + side + "_']");
      for (var k5 = 0; k5 < fsels.length; k5++) {
        var k6 = fsels[k5].id.substring(("gpRadioSel_" + side + "_").length);
        var cur4 = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k6] || "none");
        if (!fsels[k5].dataset.radioFilled) gamepadFillRadioSelect(fsels[k5], cur4.radioFn);
      }
    });
  });
  // Visibility: always shown.
  var pw = document.getElementById("gamepadMappingPadWrap");
  var kw = document.getElementById("gamepadMappingKbWrap");
  if (pw) pw.style.display = gamepadMappingShown("pad") ? "block" : "none";
  if (kw) kw.style.display = gamepadMappingShown("kb") ? "block" : "none";
}

// On language switch, re-label the mapping table in place (unsaved edits survive; no rebuild, no backend call).
// Key names, servo #N±, ringtone names and (?) entries are language-independent and stay untouched.
function gamepadApplyUiLanguage() {
  var grids = [document.getElementById("gamepadMappingPadGrid"),
    document.getElementById("gamepadMappingKbGrid")];
  for (var gi = 0; gi < grids.length; gi++) {
    var grid = grids[gi];
    if (!grid) continue;
    var heads = grid.querySelectorAll("[data-grp]");
    for (var hi = 0; hi < heads.length; hi++) {
      var grp = heads[hi].getAttribute("data-grp");
      if (grp) heads[hi].textContent = t("gamepad_group_" + grp);
    }
    var sels = grid.getElementsByTagName("select");
    for (var si = 0; si < sels.length; si++) {
      gamepadRelabelSelect(sels[si]);
    }
  }
  try { cameraPadRelabel(); } catch (e) {}
}

function gamepadRelabelSelect(sel) {
  if (!sel || !sel.id) return;
  var id = sel.id;
  var map = null;
  if (id.indexOf("gpSel_") === 0) map = GAMEPAD_KIND_KEYS;
  else if (id.indexOf("gpMusicSel_") === 0) map = GAMEPAD_MUSIC_FN_KEYS;
  else if (id.indexOf("gpRadioSel_") === 0) map = GAMEPAD_RADIO_FN_KEYS;
  else if (id.indexOf("gpEqSel_") === 0) {
    // Stored value eq:<preset> (names come from the same set as the music page).
    for (var ei = 0; ei < sel.options.length; ei++) {
      var ev = sel.options[ei].value;
      if (ev === "eq:") {
        sel.options[ei].text = t("gamepad_mfn_eq");
      } else if (ev.indexOf("eq:") === 0) {
        sel.options[ei].text = t("music_eq_preset_" + ev.substring(3));
      }
    }
    return;
  }
  else if (id.indexOf("gpRingSel_") === 0) map = GAMEPAD_RING_FIXED_KEYS;
  else if (id.indexOf("gpRingType_") === 0) map = GAMEPAD_RING_TYPE_KEYS;
  else if (id.indexOf("gpLedPart_") === 0) map = GAMEPAD_LED_PART_KEYS;
  else if (id.indexOf("gpLedPreset_") === 0) map = GAMEPAD_LED_PRESET_KEYS;
  else if (id.indexOf("gpCameraSel_") === 0) map = GAMEPAD_CAM_FN_KEYS;
  else if (id.indexOf("gpSensorSel_") === 0) map = GAMEPAD_SENSOR_FN_KEYS;
  else if (id.indexOf("gpActionKind_") === 0) map = GAMEPAD_ACTION_KIND_KEYS;
  if (id.indexOf("gpVoiceSel_") === 0) {
    // The "小智"/Vosk group names follow the language.
    for (var vi = 0; vi < sel.options.length; vi++) {
      var vk = gamepadVoiceFixedKey(sel.options[vi].value);
      if (vk) sel.options[vi].text = t(vk);
    }
    return;
  }
  if (id.indexOf("gpVoskPackSel_") === 0) {
    // The hint follows the language; package names recompute in place from the cache ((?) deleted-file entries stay untouched).
    for (var pi2 = 0; pi2 < sel.options.length; pi2++) {
      var pv = sel.options[pi2].value;
      if (pv === "") {
        sel.options[pi2].text = "— " + t("gamepad_pick_pack") + " —";
        continue;
      }
      if (pv.indexOf("vosk:") === 0) {
        var packs2 = __gpVoskPacksCache || [];
        for (var qi = 0; qi < packs2.length; qi++) {
          if (("vosk:" + packs2[qi].id) === pv) {
            sel.options[pi2].text = gamepadVoskPackName(packs2[qi]);
            break;
          }
        }
      }
    }
    return;
  }
  if (map) {
    for (var i = 0; i < sel.options.length; i++) {
      var key = map[sel.options[i].value];
      if (key) sel.options[i].text = t(key);
    }
    return;
  }
  if (id.indexOf("gpLedColor_") === 0) {
    // 3 entries mean the dual three pairs; 7 entries mean the normal seven colours.
    var dual = sel.options.length === 3;
    for (var j = 0; j < sel.options.length; j++) {
      var val = sel.options[j].value;
      var ck = dual ? GAMEPAD_DUAL_KEYS[val] : null;
      if (!ck) {
        for (var c = 0; c < GAMEPAD_LED_COLOR_KEYS.length; c++) {
          if (GAMEPAD_LED_COLOR_KEYS[c][0] === val) {
            ck = GAMEPAD_LED_COLOR_KEYS[c][1];
            break;
          }
        }
      }
      if (ck) sel.options[j].text = t(ck);
    }
    return;
  }
  if (id.indexOf("gpActionSel_") === 0) {
    for (var k = 0; k < sel.options.length; k++) {
      var v = sel.options[k].value;
      if (v === "") {
        sel.options[k].text = "— " + t("gamepad_pick_action") + " —";
      } else {
        var a = __gpActionById[v];
        if (a) sel.options[k].text = gamepadActionDisplayName(a);
      }
    }
  }
  // Ignore gpServo_ (#N±).
}

function gamepadRenderSideGrid(side, groups, map) {
  var wrap = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
  if (!wrap) return;
  wrap.innerHTML = "";
  groups.forEach(function (grp) {
    var h = document.createElement("div");
    h.className = "servo-group-title";
    h.setAttribute("data-grp", grp.title);
    h.textContent = t("gamepad_group_" + grp.title);
    wrap.appendChild(h);
    grp.keys.forEach(function (k) {
      var parsed = gamepadParseValue(map[k] || "none");
      var row = document.createElement("div");
      row.className = "row";
      row.style.alignItems = "center";
      row.setAttribute("data-maprow", k);
      row.setAttribute("data-mapside", side);
      var label = document.createElement("span");
      label.style.minWidth = "90px";
      label.style.fontWeight = "bold";
      label.textContent = gamepadKeyLabel(k);
      var pfx = side + "_";
      var kind = document.createElement("select");
      kind.id = "gpSel_" + pfx + k;
      GAMEPAD_KINDS.forEach(function (c) {
        var opt = document.createElement("option");
        opt.value = c;
        opt.textContent = t(GAMEPAD_KIND_KEYS[c] || c);
        if (c === parsed.kind) opt.selected = true;
        kind.appendChild(opt);
      });
      kind.onchange = (function (kk) {
        return function () {
          var v = document.getElementById("gpSel_" + pfx + kk).value;
          var sw = document.getElementById("gpServoWrap_" + pfx + kk);
          var aw = document.getElementById("gpActionWrap_" + pfx + kk);
          var mu = document.getElementById("gpMusicSel_" + pfx + kk);
          var eq = document.getElementById("gpEqSel_" + pfx + kk);
          var ra = document.getElementById("gpRadioSel_" + pfx + kk);
          var rg = document.getElementById("gpRingWrap_" + pfx + kk);
          var lw = document.getElementById("gpLedWrap_" + pfx + kk);
          var cm = document.getElementById("gpCameraSel_" + pfx + kk);
          var sn = document.getElementById("gpSensorSel_" + pfx + kk);
          var vc = document.getElementById("gpVoiceWrap_" + pfx + kk);
          if (sw) sw.style.display = v === "servo" ? "inline" : "none";
          if (aw) aw.style.display = v === "action" ? "inline" : "none";
          if (mu) mu.style.display = v === "music" ? "inline" : "none";
          if (eq) gamepadSyncMusicEq(side, kk);
          if (ra) ra.style.display = v === "radio" ? "inline" : "none";
          if (rg) rg.style.display = v === "ring" ? "inline" : "none";
          if (lw) lw.style.display = v === "led" ? "inline" : "none";
          if (cm) cm.style.display = v === "camera" ? "inline" : "none";
          if (sn) sn.style.display = v === "sensor" ? "inline" : "none";
          if (vc) vc.style.display = v === "voice" ? "inline" : "none";
        };
      })(k);
      var servoWrap = gamepadBuildServoControls(side, k, parsed);
      var musicSel = document.createElement("select");
      musicSel.id = "gpMusicSel_" + pfx + k;
      musicSel.style.display = parsed.kind === "music" ? "inline" : "none";
      GAMEPAD_MUSIC_FNS.forEach(function (f) {
        var o = document.createElement("option");
        o.value = f;
        o.textContent = t(GAMEPAD_MUSIC_FN_KEYS[f] || f);
        if (f === parsed.musicFn) o.selected = true;
        musicSel.appendChild(o);
      });
      // Equaliser entry (shown only after the neighbouring eq dropdown is picked; stored value eq:<preset>).
      var eqEntry = document.createElement("option");
      eqEntry.value = "eq:";
      eqEntry.textContent = t("gamepad_mfn_eq");
      if (parsed.musicFn === "eq:") eqEntry.selected = true;
      musicSel.appendChild(eqEntry);
      musicSel.onchange = (function (s, kk) {
        return function () {
          gamepadSyncMusicEq(s, kk);
        };
      })(side, k);
      // EQ presets (same names as the music page; stored value eq:<preset>).
      var eqSel = document.createElement("select");
      eqSel.id = "gpEqSel_" + pfx + k;
      eqSel.style.display = (parsed.kind === "music" && parsed.musicFn === "eq:") ? "inline" : "none";
      ["normal", "classical", "dance", "rock", "jazz", "pop", "bass", "treble", "custom"].forEach(function (p) {
        var oe = document.createElement("option");
        oe.value = "eq:" + p;
        oe.textContent = t("music_eq_preset_" + p);
        if (p === parsed.eqPreset) oe.selected = true;
        eqSel.appendChild(oe);
      });
      var actionWrap = gamepadBuildActionSelect(side, k, parsed);
      var ringWrap = gamepadBuildRingSelect(side, k, parsed);
      var ledWrap = gamepadBuildLedControls(side, k, parsed);
      var cameraSel = document.createElement("select");
      cameraSel.id = "gpCameraSel_" + pfx + k;
      cameraSel.style.display = parsed.kind === "camera" ? "inline" : "none";
      [["camera_photo", "gamepad_cam_photo"], ["face_toggle", "gamepad_cam_face"],
       ["color_toggle", "gamepad_cam_color"], ["video_toggle", "gamepad_cam_video"]].forEach(function (f) {
        var o = document.createElement("option");
        o.value = f[0];
        o.textContent = t(f[1]);
        if (f[0] === parsed.cameraFn) o.selected = true;
        cameraSel.appendChild(o);
      });
      var sensorSel = document.createElement("select");
      sensorSel.id = "gpSensorSel_" + pfx + k;
      sensorSel.style.display = parsed.kind === "sensor" ? "inline" : "none";
      GAMEPAD_SENSOR_FNS.forEach(function (f) {
        var o = document.createElement("option");
        o.value = f;
        o.textContent = t(GAMEPAD_SENSOR_FN_KEYS[f] || f);
        if (f === parsed.sensorFn) o.selected = true;
        sensorSel.appendChild(o);
      });
      var voiceSel = gamepadBuildVoiceSelect(side, k, parsed);
      row.appendChild(label);
      row.appendChild(kind);
      row.appendChild(servoWrap);
      row.appendChild(musicSel);
      row.appendChild(eqSel);
      var radioSel = gamepadBuildRadioSelect(side, k, parsed);
      row.appendChild(radioSel);
      row.appendChild(ringWrap);
      row.appendChild(ledWrap);
      row.appendChild(cameraSel);
      row.appendChild(sensorSel);
      row.appendChild(voiceSel);
      row.appendChild(actionWrap);
      wrap.appendChild(row);
    });
  });
}

// Vosk language-pack cache (live vosk/models data: installed packs; lazy-loaded like the ringtone list).
var __gpVoskPacksCache = null;

function gamepadLoadVoskPacks(force) {
  if (!force && __gpVoskPacksCache) return Promise.resolve(__gpVoskPacksCache);
  return Alpha2Api.voskModels().then(function (json) {
    var out = [];
    if (json && json.ok && json.models) {
      json.models.forEach(function (m) {
        if (m && m.id) out.push(m);
      });
    }
    __gpVoskPacksCache = out;
    return out;
  }).catch(function () {
    __gpVoskPacksCache = __gpVoskPacksCache || [];
    return __gpVoskPacksCache;
  });
}

function gamepadVoskPackName(m) {
  return (uiLang === "en" ? (m.langEn || m.lang) : (m.lang || m.langEn)) || m.id;
}

// Build the two voice dropdowns ("小智"/Vosk group + that group's language packs; the pack list arrives later and fills in; old vosk_toggle values map to the Vosk group).
function gamepadBuildVoiceSelect(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpVoiceWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "voice" ? "inline" : "none";
  var sel = document.createElement("select");
  sel.id = "gpVoiceSel_" + side + "_" + k;
  var isVosk = parsed.voiceFn !== "xiaozhi_toggle";
  GAMEPAD_VOICE_FIXED.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f[0];
    o.textContent = t(f[1]);
    if ((isVosk && f[0] === "vosk") || (!isVosk && f[0] === parsed.voiceFn)) o.selected = true;
    sel.appendChild(o);
  });
  var pack = document.createElement("select");
  pack.id = "gpVoskPackSel_" + side + "_" + k;
  pack.style.maxWidth = "220px";
  wrap.appendChild(sel);
  wrap.appendChild(pack);
  sel.onchange = (function (s, kk) {
    return function () {
      gamepadSyncVoicePack(s, kk);
    };
  })(side, k);
  gamepadFillVoskPacks(pack, isVosk ? parsed.voiceFn : "");
  // Not in the DOM yet during build, so set it directly (after entering the DOM, group changes go through the onchange + sync above).
  pack.style.display = isVosk ? "inline" : "none";
  return wrap;
}

// Only the Vosk group shows the pack list (the "小智" group keeps it collapsed).
function gamepadSyncVoicePack(side, k) {
  var pfx = side + "_";
  var sel = document.getElementById("gpVoiceSel_" + pfx + k);
  var pack = document.getElementById("gpVoskPackSel_" + pfx + k);
  if (!pack) return;
  var isVosk = !sel || sel.value !== "xiaozhi_toggle";
  pack.style.display = isVosk ? "inline" : "none";
}

// Equaliser dropdown: appears only when the music kind selects the equaliser entry; every other value hides it.
function gamepadSyncMusicEq(side, k) {
  var pfx = side + "_";
  var kd = document.getElementById("gpSel_" + pfx + k);
  var mu = document.getElementById("gpMusicSel_" + pfx + k);
  var eq = document.getElementById("gpEqSel_" + pfx + k);
  if (!eq) return;
  var showKind = !kd || kd.value === "music";
  eq.style.display = (showKind && mu && mu.value === "eq:") ? "inline" : "none";
}

// For re-labelling the two fixed entries (value → i18n key; language-pack names recompute in place from the cache).
function gamepadVoiceFixedKey(v) {
  for (var i = 0; i < GAMEPAD_VOICE_FIXED.length; i++) {
    if (GAMEPAD_VOICE_FIXED[i][0] === v) return GAMEPAD_VOICE_FIXED[i][1];
  }
  return null;
}

// Fill the pack list (hint + installed packs; an old value missing from the list gets a (?) entry; with no packs, keep only the hint).
function gamepadFillVoskPacks(sel, cur) {
  if (!sel) return;
  sel.innerHTML = "";
  var ph = document.createElement("option");
  ph.value = "";
  ph.textContent = "— " + t("gamepad_pick_pack") + " —";
  sel.appendChild(ph);
  var packs = __gpVoskPacksCache || [];
  packs.forEach(function (m) {
    var v = "vosk:" + m.id;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = gamepadVoskPackName(m);
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("vosk:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = cur.substring(5) + " (?)";
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
  sel.dataset.voiceFilled = packs.length ? "1" : "";
}

// Read one row's current value (same assembly as the old per-row save; action not picked returns null and the whole row is skipped).
function gamepadReadRowValue(side, k) {
  var pfx = side + "_";
  var kindEl = document.getElementById("gpSel_" + pfx + k);
  if (!kindEl) return null;
  var kind = kindEl.value;
  if (kind === "servo") {
    var sv = document.getElementById("gpServo_" + pfx + k);
    return (sv && sv.value) ? sv.value : "servo20+";
  }
  if (kind === "music") {
    var mu = document.getElementById("gpMusicSel_" + pfx + k);
    var muv = (mu && mu.value) ? mu.value : "music_play";
    // Equaliser entry: take the value from the neighbouring eq dropdown.
    if (muv === "eq:") {
      var eq = document.getElementById("gpEqSel_" + pfx + k);
      return (eq && eq.value) ? eq.value : "eq:normal";
    }
    return muv;
  }
  if (kind === "radio") {
    var rd = document.getElementById("gpRadioSel_" + pfx + k);
    // Empty list when even the favourites miss it: return null and skip the row instead of storing a wrong value.
    return (rd && rd.value) ? rd.value : null;
  }
  if (kind === "camera") {
    var cm = document.getElementById("gpCameraSel_" + pfx + k);
    return (cm && cm.value) ? cm.value : "camera_photo";
  }
  if (kind === "sensor") {
    var sn = document.getElementById("gpSensorSel_" + pfx + k);
    return (sn && sn.value) ? sn.value : "sonar_toggle";
  }
  if (kind === "voice") {
    var vc = document.getElementById("gpVoiceSel_" + pfx + k);
    var vcv = (vc && vc.value) ? vc.value : "xiaozhi_toggle";
    if (vcv !== "vosk") return vcv;
    var pk = document.getElementById("gpVoskPackSel_" + pfx + k);
    var pkv = (pk && pk.value) ? pk.value.trim() : "";
    return pkv || "vosk_toggle";
  }
  if (kind === "ring") {
    var rg = document.getElementById("gpRingSel_" + pfx + k);
    var rv = rg ? (rg.value || "").trim() : "";
    return rv || null;
  }
  if (kind === "led") {
    var lp = document.getElementById("gpLedPart_" + pfx + k);
    var pr = document.getElementById("gpLedPreset_" + pfx + k);
    var co = document.getElementById("gpLedColor_" + pfx + k);
    var sp = document.getElementById("gpLedSpeed_" + pfx + k);
    var part = lp ? lp.value : "head";
    var preset = pr ? pr.value : "flash";
    if (part === "mouth") {
      if (preset === "off") return "mouth:off";
      var speed = sp ? parseInt(sp.value, 10) : 500;
      if (!isFinite(speed)) speed = 500;
      speed = Math.max(100, Math.min(1000, speed));
      return "mouth:breathing:" + speed;
    }
    if (preset === "stop") return part + ":stop";
    if (preset === "random") return part + ":random";
    var color = co ? co.value : "1";
    return part + ":" + preset + ":" + color;
  }
  if (kind === "action") {
    var as = document.getElementById("gpActionSel_" + pfx + k);
    var id = as ? (as.value || "").trim() : "";
    return id ? ("action:" + id) : null;
  }
  return kind;
}

// Save a whole zone at once: send only rows that differ from the cache, with one button (replaces per-row saves).
function gamepadSaveAll(side) {
  side = side || "pad";
  var groups = side === "kb" ? GAMEPAD_KB_GROUPS : GAMEPAD_PAD_GROUPS;
  var cache = gamepadCachedSide(side);
  var jobs = [];
  groups.forEach(function (grp) {
    grp.keys.forEach(function (k) {
      var v = gamepadReadRowValue(side, k);
      if (v === null || v === cache[k]) return;
      jobs.push({ key: k, action: v });
    });
  });
  var st = document.getElementById("gamepadScanState");
  if (!jobs.length) {
    if (st) st.textContent = t("gamepad_nothing_changed");
    return Promise.resolve();
  }
  if (st) st.textContent = t("gamepad_saving");
  var chain = Promise.resolve();
  jobs.forEach(function (j) {
    chain = chain.then(function () {
      return Alpha2Api.gamepadConfigSet({ side: side, key: j.key, action: j.action });
    });
  });
  return chain.then(function () {
    if (st) st.textContent = "";
    return gamepadRefresh();
  }).then(function () {
    try {
      if (cameraPadViewOn && typeof cameraPadRefresh === "function") cameraPadRefresh();
    } catch (e) {}
  }).catch(function (err) {
    if (st) st.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadResetMapping(side) {
  side = side || "pad";
  return Alpha2Api.gamepadConfigSet({ side: side, reset: "1" }).then(function (json) {
    if (json && json.ok && json.mapping) gamepadCacheSide(side, json.mapping);
    return gamepadRefresh();
  }).then(function () {
    try {
      if (side === "pad" && cameraPadViewOn && typeof cameraPadRefresh === "function") cameraPadRefresh();
    } catch (e) {}
  });
}

// (B-side Gamepad API polling/switch removed: this page only does A-side display + mapping.
// The diagram lights and joystick positions are driven entirely over the WebSocket channel.)

// (B-side browser keyboard listener/switch removed: diagram lights are driven entirely over the WebSocket channel.)

// ---------------- Live diagrams: gamepad + full keyboard lights ----------------
// Driven over the WebSocket gamepad_key channel (A-side physical keys; gamepad/key HTTP echoes arrive here too).

// Keyboard zone definitions (real keyboard layout: the F row sits at the end of the main zone, aligned with backspace; prt/scr/paus sit at the top of the navigation zone).
// Each cell: [display text, logical key, width class or ""].
var GAMEPAD_KB_MAIN = [
  [["esc", "esc", ""], ["F1", "f1", ""], ["F2", "f2", ""], ["F3", "f3", ""],
   ["F4", "f4", ""], ["F5", "f5", ""], ["F6", "f6", ""], ["F7", "f7", ""],
   ["F8", "f8", ""], ["F9", "f9", ""], ["F10", "f10", ""], ["F11", "f11", ""],
   ["F12", "f12", ""]],
  [["`", "grave", ""], ["1", "1", ""], ["2", "2", ""], ["3", "3", ""],
   ["4", "4", ""], ["5", "5", ""], ["6", "6", ""], ["7", "7", ""],
   ["8", "8", ""], ["9", "9", ""], ["0", "0", ""], ["-", "minus", ""],
   ["=", "equals", ""], ["←", "backspace", "wider"]],
  [["tab", "tab", "wide"], ["q", "q", ""], ["w", "w", ""], ["e", "e", ""],
   ["r", "r", ""], ["t", "t", ""], ["y", "y", ""], ["u", "u", ""],
   ["i", "i", ""], ["o", "o", ""], ["p", "p", ""], ["[", "lbracket", ""],
   ["]", "rbracket", ""], ["\\", "backslash", ""]],
  [["caps", "caps", "wide"], ["a", "a", ""], ["s", "s", ""], ["d", "d", ""],
   ["f", "f", ""], ["g", "g", ""], ["h", "h", ""], ["j", "j", ""],
   ["k", "k", ""], ["l", "l", ""], [";", "semicolon", ""], ["'", "quote", ""],
   ["enter", "enter", "wider"]],
  [["shift", "shift", "wide"], ["z", "z", ""], ["x", "x", ""], ["c", "c", ""],
   ["v", "v", ""], ["b", "b", ""], ["n", "n", ""], ["m", "m", ""],
   [",", "comma", ""], [".", "period", ""], ["/", "slash", ""],
   ["shift", "shift", "wide"]],
  [["ctrl", "ctrl", ""], ["alt", "alt", ""], ["space", "space", "space"],
   ["alt", "alt", ""], ["menu", "menu", ""], ["ctrl", "ctrl", ""]]
];
var GAMEPAD_KB_NAV = [
  [["prt", "print", ""], ["scr", "scroll", ""], ["paus", "pause", ""]],
  [["ins", "ins", ""], ["home", "home", ""], ["pgup", "pgup", ""]],
  [["del", "del", ""], ["end", "end", ""], ["pgdn", "pgdn", ""]],
  [["◀", "left", ""], ["▲", "up", ""], ["▼", "down", ""], ["▶", "right", ""]]
];
// Media row (7 keys: play/stop + volume, above the navigation/number zones, full width).
var GAMEPAD_KB_MEDIA = [
  [["⏮", "media_prev", ""], ["▶⏸", "media_play", ""], ["⏭", "media_next", ""],
   ["⏹", "media_stop", ""], ["🔇", "mute", ""], ["🔊", "vol_up", ""], ["🔉", "vol_down", ""]]
];
var GAMEPAD_KB_NUM = [
  [["7", "num_7", ""], ["8", "num_8", ""], ["9", "num_9", ""], ["/", "num_div", ""]],
  [["4", "num_4", ""], ["5", "num_5", ""], ["6", "num_6", ""], ["x", "num_mul", ""]],
  [["1", "num_1", ""], ["2", "num_2", ""], ["3", "num_3", ""], ["-", "num_sub", ""]],
  [["0", "num_0", ""], [".", "num_dot", ""], ["⏎", "num_enter", ""], ["+", "num_add", ""]]
];
function gamepadKbRowEl(cells) {
  var r = document.createElement("div");
  r.className = "kb-row";
  cells.forEach(function (cell) {
    if (!cell) {
      var gap = document.createElement("div");
      gap.className = "kb-gap";
      r.appendChild(gap);
      return;
    }
    var k = document.createElement("div");
    k.className = "kb-key" + (cell[2] ? " " + cell[2] : "");
    k.textContent = cell[0];
    k.setAttribute("data-kb", cell[1]);
    r.appendChild(k);
  });
  return r;
}

function gamepadBuildKeyboardDiagram() {
  var board = document.getElementById("kbBoardDiagram");
  if (!board || board.childElementCount > 0) return;
  var zones = document.createElement("div");
  zones.className = "kb-zones";
  var main = document.createElement("div");
  main.className = "kb-zone-main";
  GAMEPAD_KB_MAIN.forEach(function (row) { main.appendChild(gamepadKbRowEl(row)); });
  var nav = document.createElement("div");
  nav.className = "kb-zone-nav";
  GAMEPAD_KB_NAV.forEach(function (row) { nav.appendChild(gamepadKbRowEl(row)); });
  var num = document.createElement("div");
  num.className = "kb-zone-num";
  GAMEPAD_KB_NUM.forEach(function (row) { num.appendChild(gamepadKbRowEl(row)); });
  // Right column: media row on top, navigation + number zones side by side below (equal aligned widths, never over the main zone's F row).
  var right = document.createElement("div");
  right.className = "kb-zone-right";
  GAMEPAD_KB_MEDIA.forEach(function (row) { right.appendChild(gamepadKbRowEl(row)); });
  var sub = document.createElement("div");
  sub.className = "kb-zones-sub";
  sub.appendChild(nav);
  sub.appendChild(num);
  right.appendChild(sub);
  zones.appendChild(main);
  zones.appendChild(right);
  board.appendChild(zones);
}

// Punctuation keys show the symbol itself (; / , …), not its English name.
var GAMEPAD_KEY_LABELS = {
  minus: "-", equals: "=", lbracket: "[", rbracket: "]", backslash: "\\",
  semicolon: ";", quote: "'", comma: ",", period: ".", slash: "/", grave: "`"
};
function gamepadKeyLabel(k) {
  return GAMEPAD_KEY_LABELS[k] || k;
}

// Three places light together: gamepad picture + keyboard picture + mapping-table row (a/b/x/y and the arrow keys exist on both sides, so lighting all is right).
// A held key keeps its row lit until release, so you never hunt through dozens of mapping rows.
// Colour: an event with a mapped action (not none) shows blue, a mapping-free one shows green. The mapping-table row colours itself from its own current value.
function gamepadLightLogical(logical, on, action) {
  if (!logical) return;
  var lit = !!on;
  var mapped = lit && action && action !== "none";
  var css = mapped ? "lit" : "lit-free";
  var cssRow = mapped ? "gprow-lit" : "gprow-free";
  var g = document.getElementById("gpcap-" + logical);
  if (g) {
    g.classList.remove("lit");
    g.classList.remove("lit-free");
    if (lit) g.classList.add(css);
  }
  var caps = document.querySelectorAll('[data-kb="' + logical + '"]');
  for (var i = 0; i < caps.length; i++) {
    caps[i].classList.remove("lit");
    caps[i].classList.remove("lit-free");
    if (lit) caps[i].classList.add(css);
  }
  try {
    __cameraPadLit[logical] = lit ? true : false;
    if (cameraPadViewOn) cameraPadDraw();
  } catch (e) {}
  var rows = document.querySelectorAll('[data-maprow="' + logical + '"]');
  for (var j = 0; j < rows.length; j++) {
    var side = rows[j].getAttribute("data-mapside");
    var cur = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[logical];
    var rowCls = (lit && cur && cur !== "none") ? "gprow-lit" : "gprow-free";
    rows[j].classList.remove("gprow-lit");
    rows[j].classList.remove("gprow-free");
    if (lit) rows[j].classList.add(rowCls);
  }
}

// Joystick position (a 26px nub inside a 64px ring, centre at 19px, full deflection ±14px).
function gamepadMoveNubs(lx, ly, rx, ry) {
  var ln = document.getElementById("gpnub-l");
  var rn = document.getElementById("gpnub-r");
  if (ln) {
    ln.style.left = (19 + Math.max(-1, Math.min(1, lx || 0)) * 14) + "px";
    ln.style.top = (19 + Math.max(-1, Math.min(1, ly || 0)) * 14) + "px";
  }
  if (rn) {
    rn.style.left = (19 + Math.max(-1, Math.min(1, rx || 0)) * 14) + "px";
    rn.style.top = (19 + Math.max(-1, Math.min(1, ry || 0)) * 14) + "px";
  }
}


// ---------------- Camera-page gamepad view (pad keys + dual joysticks, canvas inside the viewport) ----------------
// Key definitions follow the gamepad settings (mapping snapshot __cameraPadMap; button faces show only key names, no action text).
// Buttons: a press sends gamepad/key down, a release sends up (same executeAction + toggle semantics as physical buttons;
// servo jog runs while held and stops on release; taken:false flashes red and the status row shows ✗).
// Joysticks: dragging deflects, and gamepad/axis injects lx/ly/rx/ry (same applyAxis as a real pad: a mapped direction
// emits its channel + continuous run; an unmapped right stick falls back to legacy head turns 19/20). During deflection a 200 ms repeat keeps legacy steps moving;
// releasing to centre sends 0 and stops. The readout bar below shows live deflection.
// Layout follows X360: joysticks bottom left/right (auto-centre on release); D-pad mid-left (one plus shape, joystick-style,
// so the finger slides an arm without lifting); ABXY mid-right (A bottom, B right, four separate keys); start-select bottom middle;
// L1L2 top left, R1R2 top right. Multi-touch (pointerId → button/stick/D-pad) allows two-handed use.
// Feature keys are either/or: the two switches are exclusive, at most one side on at a time (see cameraFeatureToggle).
var cameraPadViewOn = false;
var __cameraPadMap = {};
var __cameraPadShapes = [];
var __cameraPadDown = {};
var __cameraPadLit = {};
var __cameraPadNo = {};
var __cameraPadPtr = {};
var __cameraPadBound = false;
// Joystick state (dx/dy −1..1, right+/down+; held = a finger on it. Local deflection sends axis; a physical pad mirrors via WS echo).
var __campadStickL = { dx: 0, dy: 0, held: false };
var __campadStickR = { dx: 0, dy: 0, held: false };
var __campadMirror = { lx: 0, ly: 0, rx: 0, ry: 0 };
var __campadAxisSentAt = 0;
var __campadAxisTimer = null;

// Feature-key switch: on closes the gamepad (either/or; off is free, so both sides can be off).
function cameraFeatureToggle() {
  var f = document.getElementById("featureEnabled");
  if (f && f.checked) {
    var tg = document.getElementById("cameraPadToggle");
    if (tg) tg.checked = false;
    cameraPadViewOn = false;
    var cv = document.getElementById("cameraPadCanvas");
    if (cv) cv.style.display = "none";
    try { cameraPadReleaseAll(); } catch (e) {}
  }
  try { updateCrosshairVisibility(); } catch (e) {}
}

function cameraPadToggleView() {
  var tg = document.getElementById("cameraPadToggle");
  cameraPadViewOn = !!(tg && tg.checked);
  if (cameraPadViewOn) {
    var f = document.getElementById("featureEnabled");
    if (f) f.checked = false;
  }
  var cv = document.getElementById("cameraPadCanvas");
  if (cv) cv.style.display = cameraPadViewOn ? "block" : "none";
  try { updateCrosshairVisibility(); } catch (e) {}
  if (cameraPadViewOn) { cameraPadBind(); cameraPadRefresh(); }
  else {
    // Closing the view releases every held button and centres the joysticks, so servo jog/head turn never keep running.
    try { cameraPadReleaseAll(); } catch (e) {}
  }
}

function cameraPadReleaseAll() {
  for (var k in __cameraPadDown) {
    if (__cameraPadDown[k]) {
      __cameraPadDown[k] = false;
      __cameraPadLit[k] = false;
      try {
        Alpha2Api.gamepadKey({ side: "pad", key: k, action: "up", source: "campad" }).catch(function () {});
      } catch (e) {}
    }
  }
  __cameraPadPtr = {};
  campadStickReset(__campadStickL);
  campadStickReset(__campadStickR);
  campadAxisSend(true);
  cameraPadDraw();
}

function cameraPadBind() {
  if (__cameraPadBound) return;
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv || !cv.addEventListener) return;
  __cameraPadBound = true;
  cv.addEventListener("pointerdown", cameraPadOnDown);
  cv.addEventListener("pointermove", cameraPadOnMove);
  cv.addEventListener("pointerup", cameraPadOnUp);
  cv.addEventListener("pointercancel", cameraPadOnUp);
  cv.addEventListener("pointerleave", cameraPadOnUp);
  cv.addEventListener("lostpointercapture", cameraPadOnUp);
  cv.addEventListener("contextmenu", function (e) { try { e.preventDefault(); } catch (x) {} });
  // Long-press vibration guard: on old WebViews/some mobile browsers, pointerdown preventDefault cannot stop long-press vibration +
  // callout, so non-passive touchstart/touchmove block it again. Only touches that hit a button/stick/D-pad arm get blocked;
  // blank areas pass through (dblclick fullscreen and normal touch need them; viewport oncontextmenu already blocks the menu).
  try {
    cv.addEventListener("touchstart", function (e) {
      try {
        var t = e.touches && e.touches[0];
        if (t && cameraPadViewOn && campadTouchHits(t.clientX, t.clientY)) e.preventDefault();
      } catch (x) {}
    }, { passive: false });
    cv.addEventListener("touchmove", function (e) { try { e.preventDefault(); } catch (x) {} }, { passive: false });
  } catch (e) {}
  try {
    window.addEventListener("resize", function () {
      if (cameraPadViewOn) cameraPadDraw();
    });
    // Window focus loss (call/notification/app switch) releases everything: the finger may never send its up event, and a
    // servo would then run to the 40-step ceiling.
    window.addEventListener("blur", function () {
      if (cameraPadViewOn) { try { cameraPadReleaseAll(); } catch (e2) {} }
    });
  } catch (e) {}
}

function cameraPadPos(e) {
  var cv = document.getElementById("cameraPadCanvas");
  var r = cv.getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

function cameraPadOnDown(e) {
  if (!cameraPadViewOn) return;
  var p = null;
  try { p = cameraPadPos(e); } catch (x) { return; }
  // Only a hit button counts: preventDefault only on a hit; blank areas pass through, because dblclick fullscreen depends on it
  // (preventDefault kills the synthesized click, so blocking everything earlier meant double-tap never reached the viewport).
  var consumed = false;
  // Joystick zones take priority (big area, buttons never overlap it; see the layout audit).
  var st = cameraPadStickAt(p.x, p.y);
  if (st) {
    // A second finger on the same stick is not accepted: its early release would cut the live grab and orphan the moving finger. Ignoring it costs at most no reaction.
    if (!st.st.held) {
      try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { stick: st }; } catch (x) {}
      campadStickGrab(st, p.x, p.y);
    }
    consumed = true;
  } else {
    var dir = campadDpadDir(p.x, p.y);
    if (dir) {
      try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { dpad: true, dir: dir }; } catch (x) {}
      cameraPadPress(dir, true);
      consumed = true;
    } else {
      var k = cameraPadHit(p.x, p.y);
      if (k) {
        try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { key: k }; } catch (x) {}
        cameraPadPress(k, true);
        consumed = true;
      }
    }
  }
  if (consumed && e) {
    try { e.preventDefault(); } catch (x) {}
    // Explicit capture: even when the finger slides out of the canvas or window, up/cancel still come back (implicit capture can lose
    // the release event and leave both the nub and the servo stuck at the deflected position).
    try {
      var cv = document.getElementById("cameraPadCanvas");
      if (cv && cv.setPointerCapture && e.pointerId != null) cv.setPointerCapture(e.pointerId);
    } catch (x) {}
  }
}

function cameraPadOnMove(e) {
  if (!cameraPadViewOn) return;
  var hold = null;
  try { hold = (e && e.pointerId != null) ? __cameraPadPtr[e.pointerId] : null; } catch (x) {}
  if (!hold || (!hold.stick && !hold.dpad)) return;
  if (e) { try { e.preventDefault(); } catch (x) {} }
  var p = null;
  try { p = cameraPadPos(e); } catch (x) { return; }
  if (hold.stick) {
    campadStickGrab(hold.stick, p.x, p.y);
    return;
  }
  // Joystick-style D-pad: the finger slides from arm to arm without lifting (old arm gets up, new arm gets down).
  var nd = campadDpadDir(p.x, p.y);
  if (nd !== hold.dir) {
    if (hold.dir) cameraPadPress(hold.dir, false);
    hold.dir = nd;
    if (nd) cameraPadPress(nd, true);
  }
}

function cameraPadOnUp(e) {
  var hold = null;
  try {
    if (e && e.pointerId != null && __cameraPadPtr[e.pointerId]) {
      hold = __cameraPadPtr[e.pointerId];
      delete __cameraPadPtr[e.pointerId];
    }
    var cv = document.getElementById("cameraPadCanvas");
    if (cv && cv.releasePointerCapture && e && e.pointerId != null) {
      try { cv.releasePointerCapture(e.pointerId); } catch (x2) {}
    }
  } catch (x) {}
  if (!hold) return;
  // Stick release: centre + send 0 immediately (forced past the throttle) + redraw at once. Skipping the redraw would leave the nub drawn
  // off-centre, which is exactly "it never centres itself".
  if (hold.stick) {
    campadStickReset(hold.stick.st);
    campadAxisSend(true);
    cameraPadDraw();
  }
  else if (hold.dpad) { if (hold.dir) cameraPadPress(hold.dir, false); }
  else if (hold.key) cameraPadPress(hold.key, false);
}

// ---- Joysticks ----

function campadStickDefs(W, H) {
  var S = Math.min(W * 0.75, H);
  return [
    { id: "L", st: __campadStickL, x: W * 0.10, y: H * 0.80, R: S * 0.115, nub: S * 0.05 },
    { id: "R", st: __campadStickR, x: W * 0.90, y: H * 0.80, R: S * 0.115, nub: S * 0.05 }
  ];
}

function cameraPadStickAt(x, y) {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv) return null;
  var W = cv.clientWidth || 480, H = cv.clientHeight || 360;
  var defs = campadStickDefs(W, H);
  for (var i = 0; i < defs.length; i++) {
    var dx = x - defs[i].x, dy = y - defs[i].y;
    if (dx * dx + dy * dy <= defs[i].R * defs[i].R) return defs[i];
  }
  return null;
}

// Convert the finger position to a deflection (clamped to the base ring when outside; sends axis live).
function campadStickGrab(def, x, y) {
  var dx = (x - def.x) / def.R, dy = (y - def.y) / def.R;
  var len = Math.sqrt(dx * dx + dy * dy);
  if (len > 1) { dx /= len; dy /= len; }
  def.st.dx = Math.round(dx * 100) / 100;
  def.st.dy = Math.round(dy * 100) / 100;
  def.st.held = true;
  campadAxisSend(false);
  campadAxisRepeat();
  cameraPadDraw();
}

function campadStickReset(st) {
  st.dx = 0; st.dy = 0; st.held = false;
}

// Repeat during deflection (legacy head turn steps per send; stopping the sends stops it. The 200 ms repeat passes the backend's 150 ms throttle).
function campadAxisRepeat() {
  if (__campadAxisTimer) return;
  __campadAxisTimer = setInterval(function () {
    var moving = __campadStickL.held || __campadStickR.held;
    if (!moving || !cameraPadViewOn) {
      clearInterval(__campadAxisTimer);
      __campadAxisTimer = null;
      return;
    }
    campadAxisSend(false);
  }, 200);
}

function campadAxisSend(force) {
  var now = Date.now();
  if (!force && now - __campadAxisSentAt < 80) return;
  __campadAxisSentAt = now;
  try {
    Alpha2Api.gamepadAxis({ lx: __campadStickL.dx, ly: __campadStickL.dy,
      rx: __campadStickR.dx, ry: __campadStickR.dy, source: "campad" }).catch(function () {});
  } catch (e) {}
}

// Physical-pad echo mirror (only a stick with no finger on it follows; self-injected echoes carry the same value, and the changed gate blocks the redraw).
// Exception: never follow echoes with source=campad. The release 0 may hit the backend's 150 ms throttle and get
// dropped, and a late echo of the old deflection would push the centred nub back out and leave it there (the intermittent no-centre bug).
// Local dx/dy is the truth; physical pads (source robot etc.) do follow.
function cameraPadMirrorAxis(d) {
  if (!cameraPadViewOn || !d) return;
  try { if (d.source === "campad") return; } catch (e) {}
  var changed = false;
  var pairs = [["lx", __campadStickL], ["ly", __campadStickL], ["rx", __campadStickR], ["ry", __campadStickR]];
  for (var i = 0; i < pairs.length; i++) {
    var v = Number(d[pairs[i][0]]);
    if (!isFinite(v)) continue;
    v = Math.max(-1, Math.min(1, Math.round(v * 100) / 100));
    if (!pairs[i][1].held && __campadMirror[pairs[i][0]] !== v) {
      __campadMirror[pairs[i][0]] = v;
      changed = true;
    }
  }
  if (changed) cameraPadDraw();
}

// Hit test for the touchstart vibration guard (a button/stick/D-pad arm, any one of them counts as a hit).
function campadTouchHits(cx, cy) {
  try {
    var cv = document.getElementById("cameraPadCanvas");
    if (!cv) return false;
    var r = cv.getBoundingClientRect();
    var x = cx - r.left, y = cy - r.top;
    if (cameraPadStickAt(x, y)) return true;
    if (campadDpadDir(x, y)) return true;
    if (cameraPadHit(x, y)) return true;
  } catch (e) {}
  return false;
}

function cameraPadHit(x, y) {
  for (var i = __cameraPadShapes.length - 1; i >= 0; i--) {
    var sh = __cameraPadShapes[i];
    if (sh.kind === "c") {
      var dx = x - sh.x, dy = y - sh.y;
      if (dx * dx + dy * dy <= sh.r * sh.r) return sh.key;
    } else if (x >= sh.x && x <= sh.x + sh.w && y >= sh.y && y <= sh.y + sh.h) {
      return sh.key;
    }
  }
  return null;
}

// D-pad geometry (fixed layout, shared by draw/hit/slide; the centre-axis dead zone = pivot).
var __campadDpad = { cx: 0, cy: 0, L: 0, r: 0, dead: 0 };

// Layout (X360: joysticks bottom left/right, auto-centre on release; D-pad mid-left, one plus shape, the finger can slide arms to turn;
// ABXY mid-right, A bottom B right; start-select bottom middle; L1L2 top left, R1R2 top right; all relative to W/H).
function cameraPadLayout(W, H) {
  var S = Math.min(W * 0.75, H);
  var dcx = W * 0.135, dcy = H * 0.45, L = S * 0.115, r = S * 0.055;
  __campadDpad = { cx: dcx, cy: dcy, L: L, r: r, dead: r * 0.6 };
  var shapes = [];
  function circle(key, cx, cy, cr) {
    shapes.push({ key: key, kind: "c", x: cx, y: cy, r: cr });
  }
  function rect(key, cx, cy, w, h) {
    shapes.push({ key: key, kind: "r", x: cx - w / 2, y: cy - h / 2, w: w, h: h });
  }
  var bcx = W * 0.865, bcy = H * 0.45;
  circle("y", bcx, bcy - L, r); circle("a", bcx, bcy + L, r);
  circle("x", bcx - L, bcy, r); circle("b", bcx + L, bcy, r);
  var sw = S * 0.12, sh = S * 0.062;
  rect("select", W * 0.43, H * 0.90, sw, sh);
  rect("start", W * 0.57, H * 0.90, sw, sh);
  var tw = S * 0.12, th = S * 0.06;
  rect("l1", W * 0.06, H * 0.075, tw, th);
  rect("l2", W * 0.06, H * 0.185, tw, th);
  rect("r1", W * 0.94, H * 0.075, tw, th);
  rect("r2", W * 0.94, H * 0.185, tw, th);
  return shapes;
}

// D-pad direction resolver (a direction exists only on an arm: the centre dead zone, the corner gap or outside returns null. Used by down/slide/guard hits).
function campadDpadDir(x, y) {
  var g = __campadDpad;
  if (!g || !g.r) return null;
  var dx = x - g.cx, dy = y - g.cy;
  var adx = Math.abs(dx), ady = Math.abs(dy);
  if (Math.max(adx, ady) > g.L + g.r) return null;
  if (Math.min(adx, ady) > g.r) return null;
  if (Math.max(adx, ady) < g.dead) return null;
  return adx > ady ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
}

function campadRoundRect(ctx, x, y, w, h, rad) {
  try {
    if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, rad); return; }
  } catch (e) {}
  ctx.beginPath();
  ctx.rect(x, y, w, h);
}

function campadFmtAxis(v) {
  var r = Math.round(v * 100) / 100;
  return (r < 0 ? "−" : "+") + Math.abs(r).toFixed(2);
}

// Draw: buttons (key name centred; mapped = solid colour, none = dim, pressed = blue, taken flashes red) + sticks (base +
// nub + L/R marks) + the bottom readout bar (live stick deflection / min-max servo limits while held).
function cameraPadDraw() {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv || !cameraPadViewOn) return;
  var W = cv.clientWidth || 480, H = cv.clientHeight || 360;
  var dpr = 1;
  try { dpr = window.devicePixelRatio || 1; } catch (e) {}
  var pw = Math.max(1, Math.round(W * dpr)), ph = Math.max(1, Math.round(H * dpr));
  if (cv.width !== pw || cv.height !== ph) { cv.width = pw; cv.height = ph; }
  var ctx = null;
  try { ctx = cv.getContext("2d"); } catch (e) { return; }
  if (!ctx) return;
  try { ctx.setTransform(dpr, 0, 0, dpr, 0, 0); } catch (e) {}
  ctx.clearRect(0, 0, W, H);
  __cameraPadShapes = cameraPadLayout(W, H);
  var now = Date.now();
  for (var i = 0; i < __cameraPadShapes.length; i++) {
    drawCampadShape(ctx, __cameraPadShapes[i], now);
  }
  // D-pad (one plus shape; the active arm gets a round highlight on its face).
  drawCampadCross(ctx, W, H, now);
  var defs = campadStickDefs(W, H);
  for (var s = 0; s < defs.length; s++) {
    drawCampadStick(ctx, defs[s]);
  }
  drawCampadReadout(ctx, W, H);
}

// D-pad body (12-vertex plus shape; arm = centre ±L, half-width r, tip L+r, matching the direction geometry).
function drawCampadCross(ctx, W, H, now) {
  var g = __campadDpad;
  if (!g || !g.r) return;
  var cx = g.cx, cy = g.cy, L = g.L, r = g.r, T = L + r;
  var map = __cameraPadMap;
  function mapped(k) { var v = map[k]; return v && v !== "none"; }
  var anyMapped = mapped("up") || mapped("down") || mapped("left") || mapped("right");
  ctx.beginPath();
  ctx.moveTo(cx - r, cy - T);
  ctx.lineTo(cx + r, cy - T);
  ctx.lineTo(cx + r, cy - r);
  ctx.lineTo(cx + T, cy - r);
  ctx.lineTo(cx + T, cy + r);
  ctx.lineTo(cx + r, cy + r);
  ctx.lineTo(cx + r, cy + T);
  ctx.lineTo(cx - r, cy + T);
  ctx.lineTo(cx - r, cy + r);
  ctx.lineTo(cx - T, cy + r);
  ctx.lineTo(cx - T, cy - r);
  ctx.lineTo(cx - r, cy - r);
  ctx.closePath();
  ctx.fillStyle = anyMapped ? "rgba(20,22,28,0.72)" : "rgba(20,22,28,0.42)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = "rgba(255,255,255,0.45)";
  ctx.stroke();
  // Active-arm highlight (blue; taken:false flashes red).
  var arms = [["up", cx, cy - L], ["down", cx, cy + L], ["left", cx - L, cy], ["right", cx + L, cy]];
  for (var i = 0; i < arms.length; i++) {
    var k = arms[i][0];
    var lit = !!__cameraPadLit[k];
    var bad = __cameraPadNo[k] && (now - __cameraPadNo[k] < 700);
    if (lit || bad) {
      ctx.beginPath();
      ctx.arc(arms[i][1], arms[i][2], r, 0, Math.PI * 2);
      ctx.fillStyle = bad ? "rgba(220,38,38,0.88)" : "rgba(37,99,235,0.9)";
      ctx.fill();
      ctx.lineWidth = 1.5;
      ctx.strokeStyle = "rgba(255,255,255,0.9)";
      ctx.stroke();
    }
  }
  // Arm names (dim when unmapped).
  var labels = [["up", "▲", cx, cy - L], ["down", "▼", cx, cy + L],
    ["left", "◀", cx - L, cy], ["right", "▶", cx + L, cy]];
  var kfs = Math.max(10, Math.min(19, r * 0.62));
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  try { ctx.font = "700 " + Math.round(kfs) + "px sans-serif"; } catch (e) {}
  for (var j = 0; j < labels.length; j++) {
    ctx.fillStyle = (mapped(labels[j][0]) || __cameraPadLit[labels[j][0]])
      ? "#ffffff" : "rgba(255,255,255,0.55)";
    ctx.fillText(labels[j][1], labels[j][2], labels[j][3]);
  }
}

function drawCampadShape(ctx, sh, now) {
  var v = __cameraPadMap[sh.key] || "none";
  var none = (!v || v === "none");
  var lit = !!__cameraPadLit[sh.key];
  var bad = __cameraPadNo[sh.key] && (now - __cameraPadNo[sh.key] < 700);
  ctx.beginPath();
  var cx, cy;
  if (sh.kind === "c") {
    ctx.arc(sh.x, sh.y, sh.r, 0, Math.PI * 2);
    cx = sh.x; cy = sh.y;
  } else {
    campadRoundRect(ctx, sh.x, sh.y, sh.w, sh.h, Math.min(sh.w, sh.h) * 0.3);
    cx = sh.x + sh.w / 2; cy = sh.y + sh.h / 2;
  }
  ctx.fillStyle = bad ? "rgba(220,38,38,0.88)"
    : lit ? "rgba(37,99,235,0.9)"
    : none ? "rgba(20,22,28,0.42)" : "rgba(20,22,28,0.72)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = (lit || bad) ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.45)";
  ctx.stroke();
  var baseR = sh.kind === "c" ? sh.r : Math.min(sh.w, sh.h) / 2;
  var kfs = Math.max(10, Math.min(19, baseR * 0.62));
  ctx.fillStyle = (none && !lit) ? "rgba(255,255,255,0.55)" : "#ffffff";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  try { ctx.font = "700 " + Math.round(kfs) + "px sans-serif"; } catch (e) {}
  ctx.fillText(cameraPadKeyShort(sh.key), cx, cy);
}

function drawCampadStick(ctx, def) {
  var st = def.st;
  var mx = def.id === "L" ? __campadMirror.lx : __campadMirror.rx;
  var my = def.id === "L" ? __campadMirror.ly : __campadMirror.ry;
  var dx = st.held ? st.dx : mx, dy = st.held ? st.dy : my;
  ctx.beginPath();
  ctx.arc(def.x, def.y, def.R, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(20,22,28,0.55)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = st.held ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.45)";
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(def.x - def.R, def.y); ctx.lineTo(def.x + def.R, def.y);
  ctx.moveTo(def.x, def.y - def.R); ctx.lineTo(def.x, def.y + def.R);
  ctx.lineWidth = 1;
  ctx.strokeStyle = "rgba(255,255,255,0.22)";
  ctx.stroke();
  var nx = def.x + dx * (def.R - def.nub), ny = def.y + dy * (def.R - def.nub);
  ctx.beginPath();
  ctx.arc(nx, ny, def.nub, 0, Math.PI * 2);
  ctx.fillStyle = st.held ? "rgba(37,99,235,0.92)" : "rgba(160,165,175,0.85)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = "rgba(255,255,255,0.7)";
  ctx.stroke();
  try { ctx.font = "700 11px sans-serif"; } catch (e) {}
  ctx.fillStyle = "rgba(255,255,255,0.85)";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(def.id, def.x, def.y - def.R - 9);
}

function drawCampadReadout(ctx, W, H) {
  var parts = [];
  if (__campadStickL.held || Math.abs(__campadStickL.dx) > 0.005 || Math.abs(__campadStickL.dy) > 0.005) {
    parts.push("LX " + campadFmtAxis(__campadStickL.dx) + " LY " + campadFmtAxis(__campadStickL.dy));
  }
  if (__campadStickR.held || Math.abs(__campadStickR.dx) > 0.005 || Math.abs(__campadStickR.dy) > 0.005) {
    parts.push("RX " + campadFmtAxis(__campadStickR.dx) + " RY " + campadFmtAxis(__campadStickR.dy));
  }
  for (var k in __cameraPadDown) {
    if (!__cameraPadDown[k]) continue;
    var m = (__cameraPadMap[k] || "").match(/^servo(\d{1,2})([+-])$/);
    if (!m) continue;
    var id = parseInt(m[1], 10);
    var cal = null;
    try { cal = SERVO_CALIBRATION[id]; } catch (e) {}
    parts.push("#" + id + (cal ? " [" + cal.min + "-" + cal.max + "]" : ""));
  }
  if (!parts.length) return;
  var txt = parts.join("   ");
  try { ctx.font = "11px sans-serif"; } catch (e) {}
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  var tw = 0;
  try { tw = ctx.measureText(txt).width; } catch (e) { tw = txt.length * 7; }
  var bx = W / 2 - tw / 2 - 8, by = 8, bw = tw + 16, bh = 18;
  ctx.beginPath();
  if (ctx.roundRect) { try { ctx.roundRect(bx, by, bw, bh, 9); } catch (e) { ctx.rect(bx, by, bw, bh); } }
  else ctx.rect(bx, by, bw, bh);
  ctx.fillStyle = "rgba(0,0,0,0.55)";
  ctx.fill();
  ctx.fillStyle = "#ffffff";
  ctx.fillText(txt, W / 2, by + bh / 2);
}

// Short canvas button names (▲▼◀▶ / uppercase).
function cameraPadKeyShort(k) {
  if (k === "up") return "▲";
  if (k === "down") return "▼";
  if (k === "left") return "◀";
  if (k === "right") return "▶";
  return String(k).toUpperCase();
}

function cameraPadRefresh() {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv) return Promise.resolve(null);
  return Alpha2Api.gamepadConfigGet({ side: "pad" }).then(function (json) {
    var map = (json && json.mapping) || gamepadCachedSide("pad");
    try { gamepadCacheSide("pad", map); } catch (e) {}
    __cameraPadMap = map;
    cameraPadDraw();
    return map;
  }).catch(function () {
    __cameraPadMap = gamepadCachedSide("pad");
    cameraPadDraw();
    return null;
  });
}

function cameraPadPress(k, isDown) {
  if (isDown) {
    if (__cameraPadDown[k]) return;
    __cameraPadDown[k] = true;
    __cameraPadLit[k] = true;
    cameraPadDraw();
    Alpha2Api.gamepadKey({ side: "pad", key: k, action: "down", source: "campad" }).then(function (json) {
      if (json && json.taken === false) {
        __cameraPadNo[k] = Date.now();
        cameraPadDraw();
        var hint = document.getElementById("cameraStatusHint");
        if (hint) hint.textContent = cameraPadKeyShort(k) + " → " + (json.action || "-") + " " + t("gamepad_taken_no");
        setTimeout(function () { cameraPadDraw(); }, 750);
      }
    }).catch(function () {
      __cameraPadDown[k] = false;
      __cameraPadLit[k] = false;
      cameraPadDraw();
    });
  } else {
    if (!__cameraPadDown[k]) return;
    __cameraPadDown[k] = false;
    __cameraPadLit[k] = false;
    cameraPadDraw();
    Alpha2Api.gamepadKey({ side: "pad", key: k, action: "up", source: "campad" }).catch(function () {});
  }
}

// ---------------- Init (called from app-log.js init) ----------------

function gamepadInitPage() {
  gamepadBuildKeyboardDiagram();
  gamepadSyncMappingToggles();
  return gamepadRefresh();
}
