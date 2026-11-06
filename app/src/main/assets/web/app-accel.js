// Open Alpha2 — client logic (app-accel.js)
// Content: accelerometer / sonar charts, head noise reduction, UUID query.
// All files share the window/global scope (no ES modules). The load order follows
// the <script src="..."> order in index.html. See the comment block at its head.

// ---------------- Accelerometer: toggle + live X/Y/Z chart ----------------
//
// Readings arrive as "accel" WebSocket events (published from MainActivity's
// onSensorChanged - see appendLog()'s companion handling below), not as a response to
// any API call here - accelerometer/set only turns the feed on/off server-side.
// ACCEL_HISTORY_LEN samples are kept client-side and redrawn on a plain 2D canvas
// (no charting library) each time a new sample arrives.

const ACCEL_HISTORY_LEN = 150;
const ACCEL_RANGE = 12; // ±12 m/s^2 covers gravity (±9.8) plus headroom for motion
let accelHistory = []; // [{x,y,z}, ...], oldest first

// Sonar obstacle history, driven by the "sonar_obstacle" WebSocket event (fired by
// MainActivity#handleChestObstacleFrame whenever a CHES_SEND_OBSTACLE frame arrives).
// Plotted as a step chart: 1 = triggered, 0 = clear, alongside the current threshold
// as a reference line so you can visually confirm the reading matches what the slider
// requested.
const SONAR_HISTORY_LEN = 150;
let sonarHistory = []; // [{triggered: bool}, ...], oldest first
let sonarThresholdCm = 30; // mirrors the slider; kept as its own var since configureSonar()
                            // updates it eagerly on release, ahead of any server round-trip

// Alpha2 PIR sensor indicator, driven by the "alpha2_pir_state" WebSocket event
// (see the CHEST_ACTION case in RobotEventReceiver.java). It shows only the state
// (red / green LED) of the latest broadcast. It ignores the separate "alert reaction"
// (LED + bell) switch (see MainActivity#alpha2SetPirAlertEnabled()). So you still
// see whether a broadcast arrives, even when the alert reaction is off.
function onAlpha2PirState(data) {
  const triggered = !!data.triggered;
  const indicator = document.getElementById("alpha2PirIndicator");
  if (indicator) {
    indicator.className = "pir-indicator " + (triggered ? "pir-indicator-triggered" : "pir-indicator-clear");
  }
}

function toggleAccelerometer() {
  const on = document.getElementById("accelToggle").checked;
  const hint = document.getElementById("accelHint");
  hint.textContent = on ? t("accel_turning_on_hint") : "";
  // Plain Android SensorManager, not implemented by the AIDL backend itself - same
  // single physical IMU regardless of robot SDK version.
  return Alpha2Api.accelerometerSet( { on: String(on) }).then(function (json) {
    if (!json.ok) {
      document.getElementById("accelToggle").checked = false;
      hint.textContent = json.error || t("accel_turn_on_failed_hint");
      return json;
    }
    // On success, sync with the backend echo. Changes from the mapping toggle or another page stay correct.
    try {
      const el = document.getElementById("accelToggle");
      if (el && el.checked !== !!json.enabled) el.checked = !!json.enabled;
    } catch (e) {}
    if (!on) {
      accelHistory = [];
      drawAccelChart();
      document.getElementById("accelXVal").textContent = "-";
      document.getElementById("accelYVal").textContent = "-";
      document.getElementById("accelZVal").textContent = "-";
      hint.textContent = "";
    } else {
      hint.textContent = t("accel_move_hint");
    }
    return json;
  });
}

// Sync on page entry: read the real accelerometer state. Changes from the mapping toggle are picked up.
function syncAccelToggle() {
  return Alpha2Api.accelerometerGet().then(function (json) {
    if (!json || !json.ok) return json;
    const el = document.getElementById("accelToggle");
    if (el) el.checked = !!json.enabled;
    return json;
  }).catch(function () { return null; });
}

// Called from appendLog() whenever an "accel" WebSocket event arrives. Kept to plain
// readout + chart duties only - anything that *reacts* to accelerometer data (LED
// colours, triggering actions, etc) is left to Blockly programs / index.html samples
// built on top of this data rather than hardcoded here. See blockly-toolbox.js's
// "Tilt to control head/eye LED" example and index.html's fall-detection sample script.
function onAccelSample(data) {
  document.getElementById("accelXVal").textContent = data.x.toFixed(2);
  document.getElementById("accelYVal").textContent = data.y.toFixed(2);
  document.getElementById("accelZVal").textContent = data.z.toFixed(2);
  accelHistory.push(data);
  if (accelHistory.length > ACCEL_HISTORY_LEN) {
    accelHistory.shift();
  }
  drawAccelChart();
}

function drawSonarChart() {
  const canvas = document.getElementById("sonarChart");
  if (!canvas) return;
  const cssWidth = canvas.clientWidth || 900;
  const cssHeight = canvas.clientHeight || 160;
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== cssWidth * dpr || canvas.height !== cssHeight * dpr) {
    canvas.width = cssWidth * dpr;
    canvas.height = cssHeight * dpr;
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const w = cssWidth, h = cssHeight;
  ctx.clearRect(0, 0, w, h);

  // Step chart: y=0 (bottom) = clear, y=1 (top) = triggered.
  const topY = h * 0.15, bottomY = h * 0.85;

  // Gridlines at clear/triggered levels for reference.
  ctx.strokeStyle = "#e2e6ec";
  ctx.lineWidth = 1;
  [topY, bottomY].forEach(function (py) {
    ctx.beginPath();
    ctx.moveTo(0, py);
    ctx.lineTo(w, py);
    ctx.stroke();
  });

  // Threshold reference line (dashed), labelled with the current cm setting - purely
  // informational since the actual trigger/clear line comes back from the sensor
  // itself, not computed client-side.
  ctx.save();
  ctx.strokeStyle = "#a855f7";
  ctx.setLineDash([4, 4]);
  ctx.lineWidth = 1.5;
  const midY = (topY + bottomY) / 2;
  ctx.beginPath();
  ctx.moveTo(0, midY);
  ctx.lineTo(w, midY);
  ctx.stroke();
  ctx.restore();
  ctx.fillStyle = "#a855f7";
  ctx.font = "11px sans-serif";
  ctx.fillText((uiLang === "en" ? "Threshold " : "門檻 ") + sonarThresholdCm + " cm", 6, midY - 6);

  if (sonarHistory.length < 2) return;

  ctx.strokeStyle = "#db2777";
  ctx.lineWidth = 1.8;
  ctx.beginPath();
  sonarHistory.forEach(function (sample, i) {
    const px = (i / (SONAR_HISTORY_LEN - 1)) * w;
    const py = sample.triggered ? topY : bottomY;
    if (i === 0) {
      ctx.moveTo(px, py);
    } else {
      // Step (not diagonal) transitions: draw the horizontal segment at the previous
      // level up to this sample's x, then jump vertically if the state changed.
      const prevPy = sonarHistory[i - 1].triggered ? topY : bottomY;
      ctx.lineTo(px, prevPy);
      ctx.lineTo(px, py);
    }
  });
  ctx.stroke();
}

function drawAccelChart() {
  const canvas = document.getElementById("accelChart");
  if (!canvas) return;
  // Match the canvas's drawing-buffer size to its actual on-screen CSS size (which
  // varies with the responsive layout - see style.css's @media rule), otherwise the
  // chart is blurry/mis-scaled on narrow screens where CSS shrinks a fixed-attribute
  // canvas down.
  const cssWidth = canvas.clientWidth || 900;
  const cssHeight = canvas.clientHeight || 180;
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== cssWidth * dpr || canvas.height !== cssHeight * dpr) {
    canvas.width = cssWidth * dpr;
    canvas.height = cssHeight * dpr;
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const w = cssWidth, h = cssHeight;
  ctx.clearRect(0, 0, w, h);

  // Zero-line + a couple of gridlines for scale reference.
  ctx.strokeStyle = "#e2e6ec";
  ctx.lineWidth = 1;
  [-ACCEL_RANGE / 2, 0, ACCEL_RANGE / 2].forEach(function (gy) {
    const py = h / 2 - (gy / ACCEL_RANGE) * h;
    ctx.beginPath();
    ctx.moveTo(0, py);
    ctx.lineTo(w, py);
    ctx.stroke();
  });

  if (accelHistory.length < 2) return;

  function plot(key, color) {
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    accelHistory.forEach(function (sample, i) {
      const px = (i / (ACCEL_HISTORY_LEN - 1)) * w;
      const clamped = Math.max(-ACCEL_RANGE, Math.min(ACCEL_RANGE, sample[key]));
      const py = h / 2 - (clamped / ACCEL_RANGE) * (h / 2);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.stroke();
  }
  plot("x", "#dc2626");
  plot("y", "#16a34a");
  plot("z", "#3b7dff");
}

// ---------------- Head / misc ----------------

function requestUuid() {
  document.getElementById("uuidOut").innerHTML = t("uuid_querying_hint");
  // The backend misc/request_uuid reads the chest cmd55 directly. The HTTP response
  // also carries {"ok":true,"uuid":"..."} (see MainActivity). Handle both: show a uuid
  // from HTTP at once (no need to wait for the WS). The WS event still updates once
  // through appendLog() -> uuidUpdateCard() (same value, idempotent).
  // When HTTP returns ok:false, show the error instead of staying "Querying" forever.
  return Alpha2Api.miscRequestUuid().then(function (res) {
    if (res && res.uuid) {
      var clean = String(res.uuid).replace(/[^A-Za-z0-9\-_]/g, "").trim();
      if (clean) {
        document.getElementById("uuidOut").textContent = clean;
        if (typeof uuidUpdateCard === "function") uuidUpdateCard(clean);
        return res;
      }
    }
    if (res && res.ok === false && res.error) {
      document.getElementById("uuidOut").textContent = "❌ " + res.error;
    }
    // ok:true but no uuid (old backend), or still waiting for the WS event: keep "Querying".
    // When the WS event arrives, appendLog() updates the field.
    return res;
  });
}

// ---------------- UUID card switch ---------------------------
// High-risk operation (writes the chest EEPROM directly). Hide the body by default;
// the user must open it before use. No localStorage memory: every tab entry or page
// reload starts closed, so it cannot stay open by accident.

function uuidCardToggle() {
  const enabled = document.getElementById("uuidCardEnabled");
  const body = document.getElementById("uuidCardBody");
  const hint = document.getElementById("uuidCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
}

// ---------------- UUID card -----------------------
// Show the UUID + QR code (offline generation, app-qr.js) + change the ID (cmd54 writes
// the chest EEPROM, server side misc/set_uuid). The QR content is robotSeq=<ID>, which
// matches the official app bind QR.
//
// The input box stays on screen. Typing (oninput) previews a new QR in the same
// uuidQrCanvas (not written to the EEPROM). The "Write EEPROM" button sends cmd54
// for real. An empty box makes the canvas show the known UUID (uuidCardLast).

let uuidCardLast = null;

function uuidUpdateCard(uuid) {
  uuidCardLast = uuid;
  const val = document.getElementById("uuidCardValue");
  if (val) val.textContent = uuid || "-";
  const status = document.getElementById("uuidWriteStatus");
  if (status && uuid) status.textContent = t("uuid_write_done_prefix") + uuid;
  // Content in the input box means the user is previewing a new ID: do not overwrite the
  // preview QR with the old queried value. Only an empty box shows the current UUID QR.
  const input = document.getElementById("uuidNewInput");
  if (!input || !input.value.trim()) {
    uuidDrawQr(uuid);
  }
}

// Draws the QR only. It does not change uuidCardLast/status. Shared by
// uuidUpdateCard() and uuidOnInputChange().
function uuidDrawQr(uuid) {
  const canvas = document.getElementById("uuidQrCanvas");
  if (canvas && uuid) {
    try {
      // The QR content is robotSeq=<ID>, not only the ID (verified against the official format).
      qrDrawToCanvas(canvas, "robotSeq=" + uuid);
    } catch (e) {
      showError("QR", e);
    }
  }
}

// Typing a new ID previews a new QR in the same uuidQrCanvas. Front-end math only, nothing
// reaches the EEPROM. uuidWriteNew() ("Write EEPROM") does that. Clearing the input shows the current UUID.
function uuidOnInputChange() {
  const input = document.getElementById("uuidNewInput");
  const hint = document.getElementById("uuidQrHint");
  const v = input ? input.value.trim() : "";
  if (!v) {
    uuidDrawQr(uuidCardLast);
    if (hint) hint.setAttribute("data-i18n", "uuid_qr_hint"), hint.textContent = t("uuid_qr_hint");
    return;
  }
  if (!/^[A-Za-z0-9\-_]{1,31}$/.test(v)) {
    if (hint) hint.removeAttribute("data-i18n"), hint.textContent = t("uuid_preview_invalid");
    return;
  }
  uuidDrawQr(v);
  if (hint) hint.removeAttribute("data-i18n"), hint.textContent = t("uuid_preview_hint_short");
}

// Random ID generator. Pick a random ID in the known valid range
// BAF006UBT10000001 ~ BAF006UBT10000504, put it in the input box, and trigger the
// QR preview. This only fills the box. It never writes by itself. The user must press
// "Write EEPROM" to confirm the real EEPROM write.
function uuidGenerateRandom() {
  const min = 10000001;
  const max = 10000504;
  const n = min + Math.floor(Math.random() * (max - min + 1));
  const id = "BAF006UBT" + String(n);
  const input = document.getElementById("uuidNewInput");
  if (input) {
    input.value = id;
    uuidOnInputChange();
  }
}

function uuidWriteNew() {
  const input = document.getElementById("uuidNewInput");
  const status = document.getElementById("uuidWriteStatus");
  const v = input ? input.value.trim() : "";
  if (!/^[A-Za-z0-9\-_]{1,31}$/.test(v)) {
    if (status) status.textContent = t("uuid_write_invalid");
    return;
  }
  if (!confirm(t("uuid_write_confirm") + "\n\n" + v)) return;
  if (status) status.textContent = t("uuid_write_writing");
  Alpha2Api.miscSetUuid( { value: v }).then(function (res) {
    if (!res.ok) {
      if (status) status.textContent = t("uuid_write_failed") + ": " + (res.error || "?");
      return;
    }
    if (status) status.textContent = t("uuid_write_wrote") + v +
        " — " + t("uuid_write_restart_hint");
    // After a successful write, clear the input box. The main display / QR switches to the
    // new written value, so the user sees the flow moved to the next step (reboot), not a preview.
    if (input) input.value = "";
    const hint = document.getElementById("uuidQrHint");
    if (hint) hint.setAttribute("data-i18n", "uuid_qr_hint"), hint.textContent = t("uuid_qr_hint");
    uuidCardLast = v;
    const val = document.getElementById("uuidCardValue");
    if (val) val.textContent = v;
    uuidDrawQr(v);
    // Note: alpha2services caches the SN read at boot, so an immediate request_uuid may show
    // the old value. Restart alpha2services (or reboot) to re-read it from the EEPROM.
  });
}

function uuidCopyId() {
  const v = uuidCardLast || "";
  const status = document.getElementById("uuidWriteStatus");
  if (!v) return;
  function done() {
    if (status) {
      status.textContent = t("uuid_copied");
      setTimeout(function () {
        if (status) status.textContent = t("uuid_write_done_prefix") + uuidCardLast;
      }, 1500);
    }
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(v).then(done).catch(function () { uuidCopyFallback(v, done); });
  } else {
    uuidCopyFallback(v, done);
  }
}

function uuidCopyFallback(text, done) {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand("copy");
    done();
  } catch (e) {
    showError("copy", e);
  }
  document.body.removeChild(ta);
}

