// Open Alpha2 — client logic (app-servo.js)
// Content: media volume, servo grid (shared buildServoGridInto()), sonar.
// All files share the window/global scope (no ES modules). Load order follows the
// <script src="..."> order in index.html - see the comment block at the top of index.html.

// ---------------- Media volume (STREAM_MUSIC) ----------------
//
// Backed by the robot's standard Android STREAM_MUSIC - the same stream the physical
// +/- gesture buttons and TTS/walkie-talkie playback all use, so this slider always
// reflects the robot's real current volume, not just whatever this browser tab last
// set. refreshVolume() is called on page load; the slider's max attribute is set from
// the device's actual getStreamMaxVolume() rather than assumed, since that can vary.

let servoVolLastPushAt = 0;
function onVolumeSliderInput(value) {
  // Live-update the numeric readout while dragging. Like the music page, a drag pushes
  // immediately under a 150ms throttle (no wait for release), and the release onchange sends the exact value once more.
  document.getElementById("volumeVal").textContent = value;
  markVolLocalInput(); // reverse-sync guard: for 400ms a drag must not get yanked back by the WS echo
  const now = Date.now();
  if (now - servoVolLastPushAt < 150) return;
  servoVolLastPushAt = now;
  Alpha2Api.audioVolumeSet( { level: String(value) }).then(function (json) {
    if (json.ok) {
      document.getElementById("volumeSlider").value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) shSlider.value = json.volume;
      if (shVal) shVal.textContent = json.volume;
    }
  });
}

function setVolume(value) {
  markVolLocalInput(); // reverse-sync guard (see onVolumeChangedEvent above)
  return Alpha2Api.audioVolumeSet( { level: String(value) }).then(function (json) {
    if (json.ok) {
      document.getElementById("volumeSlider").value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      // Sync the shared player's volume slider
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) { shSlider.max = document.getElementById("volumeSlider").max; shSlider.value = json.volume; }
      if (shVal) shVal.textContent = json.volume;
    }
    return json;
  });
}

function refreshVolume() {
  return Alpha2Api.audioVolumeGet().then(function (json) {
    if (json.ok) {
      const slider = document.getElementById("volumeSlider");
      slider.max = json.max;
      slider.value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) { shSlider.max = json.max; shSlider.value = json.volume; }
      if (shVal) shVal.textContent = json.volume;
    }
    return json;
  });
}

// Reverse sync: whenever the robot side changes the volume by any route (head +/- keys, voice volume commands, another tab's slider),
// the LedCenter VOLUME_CHANGED receiver broadcasts a volume_changed WS event
// (see the app-log.js appendLog dispatch). On receipt, set both sliders and the readouts to the new value.
// To avoid fighting a slider the user is dragging (a late server echo would yank it back to the old value),
// events within 400ms after a local slider move are all skipped - the timestamps are written by the local
// push points below and by app-music.js.
function markVolLocalInput() {
  try { window.__volLastLocalAt = Date.now(); } catch (e) { /* ignore */ }
}
function onVolumeChangedEvent(data) {
  if (!data) return;
  const v = Number(data.volume);
  if (!isFinite(v)) return;
  if (Date.now() - (window.__volLastLocalAt || 0) < 400) return;
  const max = Number(data.max);
  const pairs = [["volumeSlider", "volumeVal"], ["sharedVolumeSlider", "sharedVolumeVal"]];
  pairs.forEach(function (pair) {
    const slider = document.getElementById(pair[0]);
    if (!slider) return;
    if (isFinite(max)) slider.max = String(max);
    slider.value = String(v);
    const val = document.getElementById(pair[1]);
    if (val) val.textContent = String(v);
  });
}

// ---------------- Servos: grouped sliders (head / arms / legs) ----------------
//
// Each servo is now a single <input type="range"> instead of a plain number box.
// Dragging the slider updates the live value readout immediately, but the actual
// robot command is only sent on "change" (i.e. when the user releases / lifts the
// finger) - not on every "input" tick - so a drag doesn't flood the AIDL bridge
// with dozens of intermediate servo/one calls per second.

function servoTime() {
  return document.getElementById("servoAllTime").value;
}

/** Shared servo-grid build logic, kept as its own function so a second grid can be built later
 *  without copying a nearly identical block of code.
 *  @param wrapId       id of the outer container ("servoGroups")
 *  @param sliderPrefix id prefix of the slider inputs ("servoSlider_")
 *  @param valPrefix    id prefix of the value-display spans ("servoSliderVal_")
 *  @param sendFn       (id, angle) => void, the call that actually goes to the robot after a drag (servo/one)
 *  @param readPrefix   optional id prefix of the "read all angles" result spans - when it is not passed,
 *                       (Alpha2 works like that today) the column is not added and the layout stays as before.
 */
function buildServoGridInto(wrapId, sliderPrefix, valPrefix, sendFn, readPrefix) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) {
    console.error("buildServoGridInto: #" + wrapId + " not found, skipping");
    return;
  }
  wrap.innerHTML = "";
  SERVO_GROUPS.forEach(function (group) {
    const groupEl = document.createElement("div");
    groupEl.className = "servo-group";

    const title = document.createElement("div");
    title.className = "servo-group-title";
    title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + (uiLang === "en" ? group.labelEn : group.label);
    groupEl.appendChild(title);

    group.ids.forEach(function (id) {
      const cal = SERVO_CALIBRATION[id];
      const row = document.createElement("div");
      row.className = "servo-slider-row" + (readPrefix ? " has-readout" : "");
      row.innerHTML =
          "<span class=\"servo-slider-label\">#" + id + " " + servoNameOf(id) + "</span>" +
          "<input type=\"range\" id=\"" + sliderPrefix + id + "\" min=\"" + cal.min + "\" max=\"" + cal.max + "\" value=\"" + cal.home + "\">" +
          "<span class=\"servo-slider-value\" id=\"" + valPrefix + id + "\">" + cal.home + "</span>" +
          (readPrefix ? "<span class=\"servo-slider-readout\" id=\"" + readPrefix + id + "\">-</span>" : "");
      const slider = row.querySelector("input");
      const valueLabel = row.querySelector(".servo-slider-value");

      // Live readout while dragging - no network call yet.
      slider.addEventListener("input", function () {
        valueLabel.textContent = slider.value;
      });
      // Actually move the servo once the drag ends.
      slider.addEventListener("change", function () {
        const raw = parseInt(slider.value, 10);
        const clamped = clampServoAngle(id, isNaN(raw) ? cal.home : raw);
        if (clamped !== raw) {
          slider.value = clamped;
          valueLabel.textContent = clamped;
        }
        sendFn(id, clamped);
      });

      groupEl.appendChild(row);
    });

    wrap.appendChild(groupEl);
  });
}

function buildServoGrid() {
  buildServoGridInto("servoGroups", "servoSlider_", "servoSliderVal_", function (id, angle) {
    return Alpha2Api.servoOne({id: id, angle: angle, time: servoTime()});
  }, "servoAngleVal_");
}

/** Read all 22 axis angles at once (servo/back-angle-all: cmd 0x17 pure read,
 *  no wait, no write-back, about half a second), filling each row's right-hand readout cell. A failed read shows -. */
function servoReadAllAngles() {
  const rows = document.querySelectorAll("#tab-servo [onclick^='servoRead']");
  const status = document.getElementById("servoReadStatus");
  rows.forEach(function (b) { b.disabled = true; });
  for (let i = 1; i <= 22; i++) {
    const cell = document.getElementById("servoAngleVal_" + i);
    if (cell) cell.textContent = "…";
  }
  if (status) status.textContent = t("servo_reading_hint");
  return Alpha2Api.servoBackAngleAll().then(function (res) {
    rows.forEach(function (b) { b.disabled = false; });
    if (!res || !res.ok || !res.angles) {
      if (status) status.textContent = t("servo_read_failed_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const failed = res.failed || [];
    for (let i = 1; i <= 22; i++) {
      const cell = document.getElementById("servoAngleVal_" + i);
      if (!cell) continue;
      const v = res.angles[i - 1];
      cell.textContent = (v === null || v === undefined) ? "-" : v;
    }
    if (status) {
      status.textContent = failed.length
        ? t("servo_read_done") + (uiLang === "en"
            ? " (unreadable: " + failed.join(",") + ")"
            : "（讀不到：" + failed.join(",") + "）")
        : t("servo_read_done");
    }
    return res;
  }).catch(function (e) {
    rows.forEach(function (b) { b.disabled = false; });
    if (status) status.textContent = t("servo_read_failed_prefix") + e;
  });
}

/** "Return all to home" writes the factory angles directly (servoAll) and the sliders show home; the real pose follows the write.
 *  This card is pure manual fine-tuning, not the position single source of truth. */
// STOP_RECOVERY_ACTION_ID_HINT: "squat and stand up" (id 1510818174706) - kept
// equal to STOP_RECOVERY_ACTION_ID in MainActivity.java; each side maintains its own constant copy
// (the JS front end and Java backend share no constant mechanism), so a change must edit both sides.
const SERVO_RESET_ACTION_ID = "1510818174706";

function resetServoGrid() {
  const homes = [];
  for (let i = 1; i <= 22; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const slider = document.getElementById("servoSlider_" + i);
    const label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
    const advInp = document.getElementById("advServoVal_" + i);
    if (advInp) advInp.value = cal.home;
  }
  // Write the factory screenshot angles directly (1:120 2:120 3:120 4:120 5:120 6:120 7:120 8:65 9:145 10:140 11:120 12:120 13:175 14:95 15:100 16:120 17:120 18:120 19:120 20:120 21:120 22:120), instead of playing an action
  return Alpha2Api.servoAll({angles: homes.join(","), time: servoTime()});
}

function servoAll() {
  const angles = [];
  for (let i = 1; i <= 22; i++) {
    const slider = document.getElementById("servoSlider_" + i);
    const cal = SERVO_CALIBRATION[i];
    const raw = slider ? parseInt(slider.value, 10) : cal.home;
    const clamped = clampServoAngle(i, isNaN(raw) ? cal.home : raw);
    angles.push(clamped);
  }
  return Alpha2Api.servoAll( { angles: angles.join(","), time: servoTime() });
}

// ---------------- Sonar (chest ultrasonic obstacle sensor) ----------------
//
// distance is assumed to be centimetres directly (unverified on real hardware - see
// the card's own hint text and Alpha2RobotApi#chest_configureSonar's javadoc).
// Slider drag updates the live cm readout only; the actual API call fires on release
// (onchange), matching the servo sliders' drag-then-send pattern elsewhere.
function onSonarSliderInput(v) {
  document.getElementById("sonarDistVal").textContent = v + " cm";
}

function configureSonar(v) {
  const distance = v !== undefined ? v : document.getElementById("sonarDist").value;
  document.getElementById("sonarDistVal").textContent = distance + " cm";
  sonarThresholdCm = Number(distance);
  drawSonarChart();
  return Alpha2Api.servoSonar({distance: distance}).then(function (json) {
    if (!json || json.ok === false) return syncSonarUi();
    return json;
  }).catch(function () { return syncSonarUi(); });
}

// Toggle switch: off sends distance=0 (sonar disabled); on re-sends whatever the
// slider is currently set to, so flipping back on restores the last distance rather
// than requiring the person to re-drag the slider.
function toggleSonar() {
  const on = document.getElementById("sonarToggle").checked;
  const distance = on ? document.getElementById("sonarDist").value : "0";
  return Alpha2Api.servoSonar( { distance: distance }).then(function (json) {
    if (!json || json.ok === false) return syncSonarUi();
    return json;
  }).catch(function () { return syncSonarUi(); });
}

// Enter-page sync: read the threshold back and set the slider + switch (0 = off; a mapping toggle or another page's change re-aligns).
function syncSonarUi() {
  return Alpha2Api.servoSonarGet().then(function (json) {
    if (!json || !json.ok) return json;
    let th = Number(json.thresholdCm);
    if (!isFinite(th)) return json;
    th = Math.max(0, Math.min(100, th));
    const slider = document.getElementById("sonarDist");
    const val = document.getElementById("sonarDistVal");
    const toggle = document.getElementById("sonarToggle");
    if (slider) slider.value = String(th);
    if (val) val.textContent = th + " cm";
    if (toggle) toggle.checked = th > 0;
    try { sonarThresholdCm = th; } catch (e) {}
    try { if (typeof drawSonarChart === "function") drawSonarChart(); } catch (e) {}
    return json;
  }).catch(function () { return null; });
}

// A real device confirmed cmd=72 switches correctly and PIR triggering works. A toggle press sends at once; read ok/error from the API response, and the indicator changes light colour only on the "alpha2_pir_state" WS event.
function alpha2SetPir() {
  const on = document.getElementById("alpha2Pir").checked;
  return Alpha2Api.pirSet({on: on}).then(function (json) {
    if (!json || json.ok === false) return syncPirUi();
    return json;
  }).catch(function () { return syncPirUi(); });
}

// Separate from the alpha2SetPir() hardware switch - it only enables/disables the "flash red/buzz when a person is detected" alert reaction, matching MainActivity#setPirAlertEnabledAlpha2().
function alpha2SetPirAlertEnabled() {
  const on = document.getElementById("alpha2PirAlertEnabled").checked;
  return Alpha2Api.pirAlertEnabled( { on: on }).then(function (json) {
    if (!json || json.ok === false) return syncPirUi();
    return json;
  }).catch(function () { return syncPirUi(); });
}

// Enter-page sync: the hardware switch's self-recorded state + the alert switch set both controls at once (a mapping toggle change re-aligns).
function syncPirUi() {
  return Alpha2Api.pirGet().then(function (json) {
    if (!json || !json.ok) return json;
    const hw = document.getElementById("alpha2Pir");
    const al = document.getElementById("alpha2PirAlertEnabled");
    if (hw) hw.checked = !!json.enabled;
    if (al) al.checked = !!json.alertEnabled;
    return json;
  }).catch(function () { return null; });
}

// ---------------- Advanced Servo Angle Tuner (tab-advanced) ----------------
function buildAdvTuner() {
  const grid = document.getElementById("advServoTunerGrid");
  if (!grid) return;
  grid.innerHTML = "";
  grid.style.display = "block";
  grid.style.gridTemplateColumns = "none";
  const groups = (typeof SERVO_GROUPS !== 'undefined' && SERVO_GROUPS) ? SERVO_GROUPS : [
    {key:'head', label:'頭', labelEn:'Head', icon:'🧠', ids:[19,20]},
    {key:'right-arm', label:'右手', labelEn:'R Arm', icon:'💪', ids:[1,2,3,17]},
    {key:'left-arm', label:'左手', labelEn:'L Arm', icon:'💪', ids:[4,5,6,18]},
    {key:'right-leg', label:'右腳', labelEn:'R Leg', icon:'🦵', ids:[7,8,9,10,11]},
    {key:'left-leg', label:'左腳', labelEn:'L Leg', icon:'🦵', ids:[12,13,14,15,16]},
    {key:'extra', label:'擴展', labelEn:'Extra', icon:'🧩', ids:[21,22]}
  ];
  groups.forEach(function(g){
    const groupEl = document.createElement("div");
    groupEl.style.cssText = "margin:10px 0;border:1px solid var(--border);border-radius:10px;padding:8px;background:var(--card-bg)";
    const title = document.createElement("div");
    title.style.cssText = "font-weight:700;margin-bottom:6px";
    title.textContent = g.icon + " " + (typeof uiLang !== 'undefined' && uiLang === 'en' ? g.labelEn : g.label) + " (" + g.ids.join(",") + ")";
    groupEl.appendChild(title);
    const subGrid = document.createElement("div");
    subGrid.style.cssText = "display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px";
    g.ids.forEach(function(id){
      const cal = SERVO_CALIBRATION[id];
      const name = servoNameOf(id);
      const cell = document.createElement("div");
      cell.className = "servo-tuner-cell";
      cell.style.cssText = "border:1px solid var(--border);border-radius:8px;padding:8px;background:var(--card-bg)";
    cell.innerHTML =
      '<div style="font-weight:600;font-size:12px">#' + id + ' ' + name + '</div>' +
      '<div style="font-size:11px;color:var(--muted)">' + t('servo_tuner_range') + ' ' + cal.min + '-' + cal.max + ' home ' + cal.home + '</div>' +
      '<div style="display:flex;justify-content:space-between;align-items:center;margin:6px 0;font-size:12px">' +
        '<span>' + t('servo_tuner_angle') + ' <input type="number" id="advServoVal_' + id + '" value="' + cal.home + '" min="' + cal.min + '" max="' + cal.max + '" style="width:60px" onkeydown="if(event.key===\'Enter\'){advTunerSend(' + id + ');}"></span>' +
        '<span>' + t('servo_tuner_offset') + ' <span id="advServoOff_' + id + '" style="font-weight:600">-</span></span>' +
      '</div>' +
        '<div style="margin:4px 0"><button onmousedown="advHoldStart(' + id + ',1)" onmouseup="advHoldStop()" onmouseleave="advHoldStop()" ontouchstart="advHoldStart(' + id + ',1)" ontouchend="advHoldStop()" onclick="advTunerInc(' + id + ')" style="width:100%;padding:6px;background:#16a34a;color:white;border:none;border-radius:6px;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none">+1</button></div>' +
        '<div style="margin:4px 0"><button onmousedown="advHoldStart(' + id + ',-1)" onmouseup="advHoldStop()" onmouseleave="advHoldStop()" ontouchstart="advHoldStart(' + id + ',-1)" ontouchend="advHoldStop()" onclick="advTunerDec(' + id + ')" style="width:100%;padding:6px;background:#2563eb;color:white;border:none;border-radius:6px;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none">-1</button></div>';
      advPrevAngle[id] = cal.home;
      subGrid.appendChild(cell);
    });
    groupEl.appendChild(subGrid);
    grid.appendChild(groupEl);
  });
  grid.addEventListener('contextmenu', function(e){ e.preventDefault(); });
  grid.addEventListener('selectstart', function(e){ e.preventDefault(); });
  // Init keeps "-" in the cells (unknown) and does not fill a computed 0 - a 0 would overwrite the factory trim.
  advTrimScanned = {};
  // Version marker: checks whether the browser runs old JS (not seeing this line = a cached old version, press Ctrl+F5).
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = t("servo_tuner_ready", { v: "0906l" });
}
// The 校准 button (same as the official 校准 one): write the 22 cells' valid trims in order into the chest EEPROM (kept across power loss). Cells with no value/uns scanned are skipped and reported; each write carries its own angle (time=100, so one already in place does not move).
function advCalibrateAll() {
  const statusEl = document.getElementById("advTunerStatus");
  const jobs = [];
  const skipped = [];
  for (let i = 1; i <= 22; i++) {
    const el = document.getElementById("advServoOff_" + i);
    const t = el ? parseInt(el.textContent, 10) : NaN;
    if (isNaN(t) || !advTrimScanned[i]) { skipped.push(i); continue; }
    const inp = document.getElementById("advServoVal_" + i);
    let v = inp ? parseInt(inp.value, 10) : SERVO_CALIBRATION[i].home;
    if (isNaN(v)) v = SERVO_CALIBRATION[i].home;
    jobs.push({ id: i, angle: clampServoAngle(i, v), trim: t });
  }
  if (!jobs.length) {
    statusEl.textContent = t("servo_tuner_cal_none");
    return Promise.resolve();
  }
  let idx = 0, okCount = 0;
  const failed = [], unknown = [];
  function next() {
    if (idx >= jobs.length) {
      let msg = t("servo_tuner_cal_done", { ok: okCount, n: jobs.length });
      if (unknown.length) msg += t("servo_tuner_cal_unknown", { ids: "#" + unknown.join(",#") });
      if (failed.length) msg += t("servo_tuner_cal_failed", { ids: "#" + failed.join(",#") });
      if (skipped.length) msg += t("servo_tuner_cal_skipped", { ids: "#" + skipped.join(",#") });
      statusEl.textContent = msg;
      return Promise.resolve();
    }
    const j = jobs[idx++];
    statusEl.textContent = t("servo_tuner_cal_progress", { i: idx, n: jobs.length, id: j.id, v: advFmtOff(j.trim) });
    return Alpha2Api.servoOne({ id: j.id, angle: j.angle, time: 100, trim: j.trim }).then(function(json){
      if (json.ok && json.trimWritten) okCount++;
      else if (json.ok && json.trimUnknown) unknown.push(j.id);
      else failed.push(j.id);
      return next();
    }).catch(function(){ failed.push(j.id); return next(); });
  }
  return next();
}
let advPrevAngle = {};
// Ids whose trim this session scanned/restored: an Enter write stores EEPROM only for these,
// so a fresh page's computed numbers never overwrite the factory trim (the official tool reads on connect, so it always has a baseline).
let advTrimScanned = {};
function advTunerToggle(){
  const en = document.getElementById("advTunerEnabled").checked;
  document.getElementById("advTunerBody").style.display = en ? "block" : "none";
  document.getElementById("advTunerDisabledHint").style.display = en ? "none" : "block";
  if (en) buildAdvTuner();
}
let advHoldTimer=null, advHoldInt=null;
function advHoldStart(id, dir){
  advHoldStop();
  advHoldTimer=setTimeout(function(){ advHoldInt=setInterval(function(){ if(dir>0) advTunerInc(id); else advTunerDec(id); }, 120); }, 400);
}
function advHoldStop(){ clearTimeout(advHoldTimer); clearInterval(advHoldInt); }
document.addEventListener('mouseup', advHoldStop);
document.addEventListener('touchend', advHoldStop);
document.addEventListener('touchcancel', advHoldStop);
document.addEventListener('mouseleave', function(e){ if(e.target && e.target.closest && e.target.closest('.servo-tuner-cell')) advHoldStop(); });
function advServoTime() {
  return 500;
}
// Two sources for the offset (matching the official tuner, angle:offset = 1:3).
// - Live value: the cell's current value + angle change ×3 (built on the scanned trim: -33 then +1 gives -30).
//   When the cell has no number (-/read failed), start from 3×(angle−home) instead.
// - Real read value: a scan reads the chest's stored trim via cmd13. A write (cmd05) does not change the chest
//   trim, so the value never reverts automatically; the next scan aligns it.
function advFmtOff(v) {
  return (v > 0 ? "+" + v : "" + v);
}
// Chinese/English map for the backend servo-read error strings (backend Java has no i18n, so the front end recognises and translates;
// an unrecognised string prints as-is).
function advErrText(e) {
  if (!e) return "";
  if (String(e).indexOf("no feedback") >= 0) return t("servo_tuner_err_nofeedback");
  return String(e);
}
function advErrSuffix(e) {
  const m = advErrText(e);
  return m ? ((typeof uiLang !== "undefined" && uiLang === "en" ? ": " : "：") + m) : "";
}
// Exception-object suffixes (zh/en colon pairs; the message itself is browser/system English, keep it as-is).
function advEx(e) {
  if (e === undefined || e === null || e === "") return "";
  const m = (e && e.message !== undefined) ? e.message : String(e);
  if (!m) return "";
  return ((typeof uiLang !== "undefined" && uiLang === "en" ? ": " : "：") + m);
}
function advHomeOf(id) {
  const cal = (typeof SERVO_CALIBRATION !== 'undefined' && SERVO_CALIBRATION[id]) ? SERVO_CALIBRATION[id] : null;
  return cal ? cal.home : 120;
}
function advRefreshOffComputed(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = inp ? parseInt(inp.value, 10) : advHomeOf(id);
  if (isNaN(v)) v = advHomeOf(id);
  const el = document.getElementById("advServoOff_" + id);
  if (el) el.textContent = advFmtOff(3 * (v - advHomeOf(id))) + t("servo_tuner_live_suffix");
}
function advNudgeOff(id, deltaAngle) {
  const el = document.getElementById("advServoOff_" + id);
  if (!el) return;
  const cur = parseInt(el.textContent, 10);
  if (isNaN(cur)) { advRefreshOffComputed(id); return; }
  el.textContent = advFmtOff(cur + deltaAngle * 3) + t("servo_tuner_live_suffix");
}
function advTunerSend(id, timeMs) {
  // Step buttons/Enter in cells only send the angle via cmd05 (Enter 500ms, step 100ms). Trims write as a group only through the 校准 button; EEPROM is not touched here.
  if (timeMs === undefined || timeMs === null) timeMs = advServoTime();
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10);
  v = clampServoAngle(id, isNaN(v) ? SERVO_CALIBRATION[id].home : v);
  const prev = advPrevAngle[id] !== undefined ? advPrevAngle[id] : v;
  const delta = v - prev;
  advPrevAngle[id] = v;
  inp.value = v;
  if (delta !== 0) advNudgeOff(id, delta);
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = t("servo_tuner_nudge_ok", { id: id, v: v });
  return Alpha2Api.servoOne({ id: id, angle: v, time: timeMs }).then(function(json){
    if (!json.ok) statusEl.textContent = t("servo_tuner_nudge_fail", { id: id, e: (json.error || JSON.stringify(json)) });
    return json;
  }).catch(function(e){ statusEl.textContent = t("servo_tuner_nudge_err", { id: id, e: e.message }); });
}
function advTunerInc(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v + 1);
  inp.value = v;
  return advTunerSend(id, 100);
}
function advTunerDec(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v - 1);
  inp.value = v;
  return advTunerSend(id, 100);
}
function advTunerReadAll() {
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = t("servo_tuner_read_all");
  return Alpha2Api.servoReadAll().then(function(json){
    if (!json.ok || !json.live || !json.trims || json.trims.length !== 22) {
      statusEl.textContent = t("servo_tuner_read_fail", { id: "1-22" }) + advErrSuffix(json.error);
      return json;
    }
    let okCount = 0;
    for (let i = 1; i <= 22; i++) {
      const v = json.trims[i - 1];
      if (v === null || v === undefined) {
        document.getElementById("advServoOff_" + i).textContent = t("servo_tuner_no_feedback");
      } else {
        okCount++;
        document.getElementById("advServoOff_" + i).textContent = advFmtOff(v);
        advTrimScanned[i] = true;
      }
    }
    const fails = (json.failed && json.failed.length) ? t("servo_tuner_read_fails", { ids: "#" + json.failed.join(",#") }) : "";
    statusEl.textContent = t("servo_tuner_read_done", { ok: okCount }) + fails;
    return json;
  }).catch(function(e){ statusEl.textContent = t("servo_tuner_read_err", { e: e.message }); });
}
function advTunerReset() {
  // Reset = the group returns home, then trims rescan automatically (the chest's stored values are not affected by moved angles). Cells show "…" while waiting for the scan, with no computed number filled in; also clear the scanned flag - a filled 0 would write into EEPROM and overwrite the factory trim.
  const homes = [];
  for (let i = 1; i <= 22; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const inp = document.getElementById("advServoVal_" + i);
    if (inp) inp.value = cal.home;
    advPrevAngle[i] = cal.home;
    const cell = document.getElementById("advServoOff_" + i);
    if (cell) cell.textContent = "…";
    delete advTrimScanned[i];
  }
  document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_sending");
  return Alpha2Api.servoAll( { angles: homes.join(","), time: 500 }).then(function(json){
    if (!json.ok) {
      document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_fail", { e: (json.error || "") });
      return json;
    }
    document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_done");
    return new Promise(function(resolve) {
      setTimeout(function() { resolve(advTunerReadAll()); }, 1500);
    });
  });
}
function _advPerformBackupNow() {
  // Back up offsets only (angle cells are live targets, so saving them to the file is useless). A failed read stores null; validity comes from advTrimScanned.
  const offsets = {};
  for (let i = 1; i <= 22; i++) {
    if (!advTrimScanned[i]) { offsets[i] = null; continue; }
    const offEl = document.getElementById("advServoOff_" + i);
    const rawTxt = offEl ? offEl.textContent.trim() : "";
    const parsed = parseInt(rawTxt, 10);
    offsets[i] = isNaN(parsed) ? null : parsed;
  }
  const payload = {
    version: 2,
    type: "servo_tuner_backup",
    exportedAt: new Date().toISOString(),
    offsets: offsets
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], {type: "application/json"});
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  const ts = new Date().toISOString().slice(0,19).replace(/[:T]/g,"-");
  a.download = "servo_offset_backup_" + ts + ".json";
  a.click();
  URL.revokeObjectURL(url);
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = t("servo_tuner_backup_done") + " (" + ts + ")";
}
function advBackup() {
  const statusEl = document.getElementById("advTunerStatus");
  // One offset scan is required first, or there is no result: detect cells still showing "-"
  let needScan = false;
  let unscanned = 0;
  for (let i = 1; i <= 22; i++) {
    const offEl = document.getElementById("advServoOff_" + i);
    const txt = offEl ? offEl.textContent.trim() : "-";
    // "-" (fresh page) and "…" (armed, waiting) trigger the auto scan; numbers (pending or stored) and "read failed"
    // do not rescan - never overwrite a value the user is tuning.
    if (txt === "-" || txt === "" || txt === "…") { needScan = true; unscanned++; }
  }
  if (needScan) {
    if (statusEl) statusEl.textContent = t("servo_tuner_backup_need_scan") + " " + t("servo_tuner_unscanned", { n: unscanned });
    // Auto-scan once first, then back up
    return advTunerReadAll().then(function() {
      // advTunerReadAll already set status to read-done, so overwrite it next with backup-done
      _advPerformBackupNow();
    }).catch(function(err){
      if (statusEl) statusEl.textContent = t("servo_tuner_backup_fail") + advEx(err);
    });
  } else {
    _advPerformBackupNow();
    return Promise.resolve();
  }
}
function advImport(input) {
  const file = input.files[0];
  if (!file) return;
  const statusEl = document.getElementById("advTunerStatus");
  const reader = new FileReader();
  reader.onload = function(e) {
    try {
      const data = JSON.parse(e.target.result);
      // Restore offsets only (an old file's angles are ignored, the angle cells do not move).
      let offsets = null;
      if (data && typeof data === "object" && data.offsets) {
        offsets = data.offsets;
      } else if (data && typeof data === "object" && !data.angles) {
        offsets = data;
      }
      let countOff = 0;
      for (let i = 1; i <= 22; i++) {
        const k = String(i);
        // offsets: restore display
        let raw = null;
        if (offsets && offsets[k] !== undefined) raw = offsets[k];
        else if (offsets && offsets[i] !== undefined) raw = offsets[i];
        if (raw !== null && raw !== undefined) {
          let num = typeof raw === "number" ? raw : parseInt(String(raw).trim(), 10);
          if (isNaN(num)) continue;
          const el = document.getElementById("advServoOff_" + i);
          if (el) {
            el.textContent = advFmtOff(num);
            advTrimScanned[i] = true;
            countOff++;
          }
        }
      }
      if (statusEl) {
        if (countOff === 0) {
          statusEl.textContent = t("servo_tuner_restore_fail") + t("servo_tuner_restore_empty");
        } else {
          statusEl.textContent = t("servo_tuner_restore_done") + t("servo_tuner_restore_detail", { off: countOff });
        }
      }
    } catch (err) {
      if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + advEx(err);
    } finally {
      // allow re-selecting same file
      try { input.value = ""; } catch(e) {}
    }
  };
  reader.onerror = function() {
    if (statusEl) status.textContent = t("servo_tuner_restore_fail") + t("servo_tuner_restore_readerr");
    try { input.value = ""; } catch(e) {}
  };
  reader.readAsText(file);
}

// ---------------- Change servo ID card (tab-advanced) ----------------
// Chest cmd 10 MODIFY_ID [old][new] (servo code 205), servo EEPROM persistent.
// Same collapsible pattern as uuidCardToggle/chestToggle: default collapsed,
// no localStorage, reopened collapsed on every page load.
function servoIdCardToggle() {
  const enabled = document.getElementById("servoIdCardEnabled");
  const body = document.getElementById("servoIdCardBody");
  const hint = document.getElementById("servoIdCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) servoIdBusUpdate();
}

// Chest servo bus sides (user-confirmed wiring: wrong side = unrecognised).
// Right port: head 19,20 / right arm 1,2,3,17 / right leg 7-11 / 21.
// Left port: left arm 4,5,6,18 / left leg 12-16 / 22.
const SERVO_BUS_RIGHT = [1, 2, 3, 7, 8, 9, 10, 11, 17, 19, 20, 21];
const SERVO_BUS_LEFT = [4, 5, 6, 12, 13, 14, 15, 16, 18, 22];
function servoBusSideOf(id) {
  if (SERVO_BUS_RIGHT.indexOf(id) !== -1) return "right";
  if (SERVO_BUS_LEFT.indexOf(id) !== -1) return "left";
  return "";
}
function servoBusSideName(side) {
  if (side === "right") return t("servo_id_bus_right");
  if (side === "left") return t("servo_id_bus_left");
  return "?";
}
function servoIdBusUpdate() {
  const info = document.getElementById("servoIdBusInfo");
  if (!info) return;
  const id = parseInt((document.getElementById("servoIdOld") || {}).value, 10);
  const newId = parseInt((document.getElementById("servoIdNew") || {}).value, 10);
  const sOld = servoBusSideOf(id), sNew = servoBusSideOf(newId);
  if (!sOld || !sNew) { info.textContent = ""; return; }
  let msg = t("servo_id_bus_info", { sideOld: servoBusSideName(sOld), sideNew: servoBusSideName(sNew) });
  if (sOld !== sNew) msg += " \u2014 " + t("servo_id_bus_cross", { sideNew: servoBusSideName(sNew) });
  info.textContent = msg;
}

function servoModifyId() {
  const oldEl = document.getElementById("servoIdOld");
  const newEl = document.getElementById("servoIdNew");
  const status = document.getElementById("servoIdStatus");
  const id = oldEl ? parseInt(oldEl.value, 10) : NaN;
  const newId = newEl ? parseInt(newEl.value, 10) : NaN;
  if (isNaN(id) || isNaN(newId) || id < 1 || id > 22 || newId < 1 || newId > 22) {
    if (status) status.textContent = t("servo_id_range_error");
    return Promise.resolve();
  }
  if (id === newId) {
    if (status) status.textContent = t("servo_id_same_error");
    return Promise.resolve();
  }
  const sOld = servoBusSideOf(id), sNew = servoBusSideOf(newId);
  let confirmMsg = t("servo_id_confirm", { id: id, newId: newId });
  if (sOld && sNew && sOld !== sNew) confirmMsg += "\n" + t("servo_id_bus_cross", { sideNew: servoBusSideName(sNew) });
  if (!confirm(confirmMsg)) return Promise.resolve();
  if (status) status.textContent = t("servo_id_sending", { id: id, newId: newId });
  return Alpha2Api.servoModifyId({ id: id, newId: newId }).then(function (json) {
    if (json && json.ok) {
      if (status) status.textContent = t("servo_id_done", { id: id, newId: newId });
    } else {
      if (status) status.textContent = t("servo_id_fail_prefix") + ((json && (json.error || JSON.stringify(json))) || "?");
    }
    return json;
  }).catch(function (e) {
    if (status) status.textContent = t("servo_id_fail_prefix") + (e && e.message ? e.message : e);
  });
}

// ---------------- Servo firmware version card (tab-advanced) ----------------
// Chest cmd 14 READ_MOTOR_VERSION, pure read. Reply layout UNVERIFIED:
// rows show parsed version (may be missing) plus raw hex (authoritative).
// Same collapsible pattern as servoIdCardToggle: default collapsed.
function servoFwCardToggle() {
  const enabled = document.getElementById("servoFwCardEnabled");
  const body = document.getElementById("servoFwCardBody");
  const hint = document.getElementById("servoFwCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) { servoFwBuildGrid(); servoUpFileInfo(); }
}

// Servo torque groups (user-confirmed hardware fact:
// 8kg = [2,3,5,6,17,18,19,20], 20kg = [1,4,7-16], 21/22 = extra/unknown).
// Colours: 8kg blue, 20kg orange, unknown default grey.
const SERVO_TORQUE_8KG = [2, 3, 5, 6, 17, 18, 19, 20];
const SERVO_TORQUE_20KG = [1, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
function servoTorqueOf(id) {
  if (SERVO_TORQUE_8KG.indexOf(id) !== -1) return "8kg";
  if (SERVO_TORQUE_20KG.indexOf(id) !== -1) return "20kg";
  return "";
}
function servoTorqueColor(id) {
  const m = servoTorqueOf(id);
  if (m === "8kg") return "#2563eb";
  if (m === "20kg") return "#d97706";
  return "#6b7280";
}

function servoFwBuildGrid() {
  const grid = document.getElementById("servoFwGrid");
  if (!grid) return;
  if (grid.children.length === 22) return;
  grid.innerHTML = "";
  for (let i = 1; i <= 22; i++) {
    const cell = document.createElement("div");
    cell.style.cssText = "border:1px solid " + servoTorqueColor(i) + ";border-radius:8px;padding:8px;background:" + servoTorqueColor(i) + ";color:#fff";
    cell.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:baseline;gap:12px;font-weight:600;font-size:13px;color:#fff"><span>#' + i + '</span><span id="servoFwVer_' + i + '"></span></div>';
    grid.appendChild(cell);
  }
}

function servoFwSetRow(i, ver) {
  const vEl = document.getElementById("servoFwVer_" + i);
  if (vEl) vEl.textContent = (ver === null || ver === undefined || ver === "") ? t("servo_fw_unreadable") : ver;
}

function servoFwReadAll() {
  const status = document.getElementById("servoFwStatus");
  servoFwBuildGrid();
  for (let i = 1; i <= 22; i++) {
    const vEl = document.getElementById("servoFwVer_" + i);
    if (vEl) vEl.textContent = "…";
  }
  if (status) status.textContent = t("servo_fw_reading");
  return Alpha2Api.servoVersionAll().then(function (res) {
    if (!res || !res.ok || !res.versions) {
      if (status) status.textContent = t("servo_fw_fail_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const failed = res.failed || [];
    for (let i = 1; i <= 22; i++) {
      servoFwSetRow(i, res.versions[i - 1]);
    }
    if (status) {
      status.textContent = failed.length
        ? t("servo_fw_done") + (uiLang === "en"
            ? " (unreadable: " + failed.join(",") + ")"
            : "（無法讀取：" + failed.join(",") + "）")
        : t("servo_fw_done");
    }
    return res;
  }).catch(function (e) {
    if (status) status.textContent = t("servo_fw_fail_prefix") + (e && e.message ? e.message : e);
  });
}

// ---------------- Servo firmware upgrade card (tab-advanced) ----------------
// Flow: pick 7168B .bin -> upload to /upload/servo-fw -> double confirm ->
// servo/upgrade?id= -> poll servo/upgrade/status. Same collapsible pattern,
// default collapsed. File gate (exactly 7168B) enforced client AND server.

function servoUpFileInfo() {
  const status = document.getElementById("servoUpFileStatus");
  return Alpha2Api.servoUpgradeStatus().then(function (s) {
    if (!s || s.ok === false) return false;
    if (!s.fileExists) {
      if (status) status.textContent = t("servo_up_file_missing");
      return false;
    }
    if (Number(s.fileSizeBytes) !== 7168) {
      if (status) status.textContent = t("servo_up_file_bad", { n: s.fileSizeBytes });
      return false;
    }
    if (status) status.textContent = t("servo_up_file_ok", { n: s.fileSizeBytes });
    return true;
  }).catch(function () { return false; });
}

function servoUpUpload() {
  const input = document.getElementById("servoUpFile");
  const status = document.getElementById("servoUpFileStatus");
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = t("servo_up_no_file");
    return;
  }
  const file = input.files[0];
  if (file.size !== 7168) {
    if (status) status.textContent = t("servo_up_bad_size", { n: file.size });
    return;
  }
  if (status) status.textContent = t("servo_up_uploading");
  const reader = new FileReader();
  reader.onload = function (e) {
    const bytes = e.target.result;
    let upHeaders = {};
    try {
      if (typeof panelAuthHeaders === "function") upHeaders = panelAuthHeaders();
    } catch (ign) {}
    fetch("/upload/servo-fw?name=" + encodeURIComponent(file.name), {
      method: "POST",
      body: bytes,
      headers: Object.assign({ "Content-Type": "application/octet-stream" }, upHeaders)
    }).then(function (res) { return res.json(); }).then(function (json) {
      if (json.ok) {
        servoUpFileInfo();
      } else {
        if (status) status.textContent = t("servo_up_fail_prefix") + (json.error || JSON.stringify(json));
      }
    }).catch(function (err) {
      if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
    });
  };
  reader.onerror = function () { if (status) status.textContent = t("servo_up_fail_prefix") + "read file"; };
  reader.readAsArrayBuffer(file);
}

function servoUpStart() {
  const status = document.getElementById("servoUpStatus");
  const idEl = document.getElementById("servoUpId");
  const id = idEl ? parseInt(idEl.value, 10) : NaN;
  if (isNaN(id) || id < 1 || id > 22) {
    if (status) status.textContent = t("servo_id_range_error");
    return Promise.resolve();
  }
  servoUpFileInfo().then(function (ok) {
    if (!ok) return;
    if (!confirm(t("servo_up_confirm1", { id: id }))) return;
    if (!confirm(t("servo_up_confirm2", { id: id }))) return;
    if (status) status.textContent = t("servo_up_started", { id: id });
    const wrap = document.getElementById("servoUpProgressWrap");
    const bar = document.getElementById("servoUpProgressBar");
    const txt = document.getElementById("servoUpProgressText");
    if (wrap) wrap.style.display = "block";
    if (bar) bar.style.width = "0%";
    if (txt) txt.textContent = "0%";
    Alpha2Api.servoUpgrade({ id: id }).then(function (json) {
      if (!json.ok) {
        if (status) status.textContent = t("servo_up_fail_prefix") + (json.error || "start failed");
        return;
      }
      const timer = setInterval(function () {
        Alpha2Api.servoUpgradeStatus().then(function (s) {
          const p = s.progress || 0;
          if (bar) bar.style.width = p + "%";
          if (txt) txt.textContent = p + "% (" + (s.currentPage || 0) + "/" + (s.totalPages || 0) + ") " + (s.status || "");
          if (!s.inProgress) {
            clearInterval(timer);
            if (s.status && s.status.indexOf("success") !== -1) {
              if (status) status.textContent = t("servo_up_done_ok", { id: id });
            } else {
              if (status) status.textContent = t("servo_up_fail_prefix") + (s.status || "");
            }
          }
        }).catch(function () {});
      }, 500);
    }).catch(function (err) {
      if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
    });
  });
}

function servoUpAbort() {
  const status = document.getElementById("servoUpStatus");
  return Alpha2Api.servoUpgradeAbort().then(function (json) {
    if (status) status.textContent = t("servo_up_abort_btn") + (json.ok ? " OK" : ": " + (json.error || ""));
    return json;
  }).catch(function (err) {
    if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
  });
}
