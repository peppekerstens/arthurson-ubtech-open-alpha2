// Open Alpha2 — client logic (app-log.js)
// Content: WebSocket event log, page init (DOMContentLoaded). Load this file last:
// init() uses the build*()/refresh*() functions defined by all other app-*.js files.
// All files share the window/global scope (no ES modules). The load order follows
// the <script src="..."> order in index.html. See the comment block at its head.

// ---------------- WebSocket event log ----------------

let ws;
let wsBootId = null; // the server issues a new bootId on every start (see WebSocketServer.BOOT_ID).
function connectWs() { // An old tab (open during install) reloads for new JS when a reconnect shows a different bootId.
  const proto = location.protocol === "https:" ? "wss://" : "ws://";
  // Panel token: a WebSocket cannot set headers. When the unit is locked, use the ?panel_token= query fallback (see PanelAuth).
  let wsUrl = proto + location.host + "/ws";
  try {
    const pt = (typeof panelTokenGet === "function") ? panelTokenGet() : "";
    if (pt) wsUrl += "?panel_token=" + encodeURIComponent(pt);
  } catch (ign) {}
  ws = new WebSocket(wsUrl);

  ws.onopen = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "Connected (WebSocket live)" });
  };
  ws.onclose = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "Disconnected, reconnecting in 3s…" });
    setTimeout(connectWs, 3000);
  };
  ws.onerror = function () { ws.close(); };
  ws.onmessage = function (evt) {
    try {
      const msg = JSON.parse(evt.data);
      if (msg && msg.type === "connected" && msg.bootId) {
        if (wsBootId && wsBootId !== msg.bootId) {
          location.reload(); // the app was reinstalled / restarted, this page JS is stale - get the new version now.
          return;
        }
        wsBootId = msg.bootId;
      }
      appendLog(msg);
    } catch (e) {
      appendLog({ type: "raw", time: "", data: evt.data });
    }
  };
}

function nowTimeStr() {
  return new Date().toLocaleTimeString("zh-HK", { hour12: false });
}

/** Unified semantic match + TTS + action trigger function. Every input path (text entry
 *  included) uses it, matching the second half of sendSpeechChatText(). */
function triggerSemanticSimulate(text, fromVoice) {
  if (!text) return;
  Alpha2Api.speechSemanticSimulate( { text: text }).then(function (res) {
    if (!res || !res.ok) return;
    if (!res.matched) {
      appendSpeechChatLine("xiaozhi-msg-system", t("speech_chat_simulate_no_match"));
      return;
    }
    // The chat UI shows only spoken lines. [TYPE operation] action-ID detail lines stay
    // out of the chat flow (the Event Log carries the same information).
    if (res.answer) {
      appendSpeechChatLine("xiaozhi-msg-assistant", res.answer);
    }
  });
}

/** Chat text filter: when the recognition result is still a JSON string
 *  (for example {"text":"你好","rc":4}), extract the text field. Return an empty string
 *  when it is missing, so braces / quotes never show in a user bubble. */
function cleanChatText(s) {
  if (!s) return "";
  const t = String(s).trim();
  if (t.indexOf("{") === 0 && t.indexOf("}") > 0) {
    const m = t.match(/"text"\s*:\s*"([^"]*)"/);
    return m ? m[1] : "";
  }
  return t;
}

const MAX_LOG_LINES = 200;

// The chest_broadcast_debug / mic_broadcast_debug event types only serve
// RobotEventReceiver.java diagnostics for "unknown payload" collection (see the
// CHEST_ACTION case and the 8 mic-related case comments). They have no UI
// tile/chart, and printing them just floods the Event Log with no real value (to
// inspect a payload, use Log.i in logcat, not this WebSocket event). Like accel,
// skip the log DOM, but do not stop the event from publishing through the
// EventBus. The front-end hides it only. Nothing in RobotEventReceiver.java changed.
const SILENCED_LOG_TYPES = ["accel", "chest_broadcast_debug", "mic_broadcast_debug"];

function appendLog(msg) {
  if (SILENCED_LOG_TYPES.indexOf(msg.type) === -1) {
    const log = document.getElementById("eventLog");
    const line = document.createElement("div");
    // Whitelist the type before it enters the className (a server-controlled string). escapeHtml time/data as before.
    const safeType = /^[A-Za-z0-9_-]+$/.test(msg.type || "") ? msg.type : "raw";
    line.className = "log-line log-type-" + safeType;
    const dataStr = typeof msg.data === "object" ? JSON.stringify(msg.data) : msg.data;
    line.innerHTML = "<span class=\"log-time\">[" + escapeHtml(msg.time) + "]</span> <b>" + escapeHtml(msg.type) + "</b> " + escapeHtml(dataStr);
    log.appendChild(line);
    // Cap the number of DOM nodes kept around for any other, lower-frequency event
    // type too, as a safety net against unbounded growth over a long session.
    while (log.childElementCount > MAX_LOG_LINES) {
      log.removeChild(log.firstChild);
    }
    if (document.getElementById("autoScroll").checked) {
      log.scrollTop = log.scrollHeight;
    }
  }

  // A couple of event types also update a dedicated tile, not just the scrolling log,
  // since the HTTP call that triggered them (requestRobotUUID(), the battery receiver)
  // doesn't carry the actual result back in its own response.
  if (msg.type === "robot_uuid" && msg.data) {
    const el = document.getElementById("uuidOut");
    // When the backend read fails (timeout / empty), it publishes {"uuid":null}. For uuid:null show the read failure. Only a present uuid enters the clean + display flow.
    if (!msg.data.uuid) {
      if (el) el.textContent = uiLang === "en" ? "❌ Read failed (no reply from chest cmd55, check logcat)" : "❌ 讀取失敗 (chest cmd55 無回覆, 見 logcat)";
      return;
    }
    // The SN field may carry \0 padding - strip control characters before display.
    // Trash bytes left at the EEPROM tail can show as boxes / mojibake. The Java side (RobotEventReceiver) already whitelists. This is the second guard. Valid SN characters are only letters, digits, - and _.
    const clean = String(msg.data.uuid).replace(/[^A-Za-z0-9\-_]/g, "").trim();
    if (el) el.textContent = clean;
    uuidUpdateCard(clean);
  }
  // The activation code publishes "xiaozhi_activation" through the EventBus. Besides printing into the general #eventLog, it also triggers xiaozhiShowActivationCode().
  // The typeof check guards the case where app-log.js runs before app-xiaozhi.js, or a build has no 小智 feature. xiaozhiShowActivationCode() already prevents duplicate display, so do not block here.
  if (msg.type === "xiaozhi_activation" && msg.data && msg.data.code) {
    if (typeof xiaozhiShowActivationCode === "function") {
      xiaozhiShowActivationCode(msg.data.code);
    }
  }
  if (msg.type === "battery" && msg.data) {
    const el = document.getElementById("batteryOut");
    if (el) el.textContent = msg.data.level + "/" + msg.data.scale + " " + (msg.data.charging ? (uiLang === "en" ? "⚡Charging" : "⚡充電中") : "") + " (" + msg.data.status + ")";
  }
  if (msg.type === "asr_result" && msg.data) {
    // Chat UI: show the recognition result as a user bubble (filtered through cleanChatText, so JSON fragments never enter the chat flow).
    const cleanAsr = cleanChatText(msg.data.text);
    if (cleanAsr && typeof appendSpeechChatLine === "function") {
      // A single-character final (noise like 啊/嗯/哦) shows no bubble. A real single-word
      // command (嗨) shows no bubble too. Use 你好 instead. Typing is unaffected.
      // To see what Vosk actually heard: the live partial line on the voice page and "final:" in logcat still show it.
      if (cleanAsr.length < 2) {
        // No bubble, no matching. Move to the next event directly.
      } else {
        appendSpeechChatLine("xiaozhi-msg-user", cleanAsr);
        triggerSemanticSimulate(cleanAsr, true);
      }
    }
  }
  // Vosk live partial: shows only in the Vosk card status line on the voice page, not in the chat flow (a full sentence enters the bubble + pipeline through asr_result).
  if (msg.type === "asr_partial" && msg.data && msg.data.text) {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = msg.data.text;
  }
  if (msg.type === "vosk_state" && msg.data) {
    if (typeof voskRenderStatus === "function") voskRenderStatus(msg.data);
  }
  // Vosk model download progress (the backend VoskController.publishDl pushes it actively: one event every ~0.5 s while downloading/unzipping, one final event for done/error/cancelled).
  if (msg.type === "vosk_download" && msg.data) {
    if (typeof voskRenderDownload === "function") voskRenderDownload(msg.data);
    // A pushed done must also run the poll wrap-up (stop timer + refresh + auto-load
    // the state). Rendering the percent alone would sit at 100% and never move to ready.
    if (msg.data.state === "done" || msg.data.state === "error" || msg.data.state === "cancelled") {
      if (typeof voskPollDownloadOnce === "function") voskPollDownloadOnce();
    } else if (typeof voskPollDownload === "function") {
      voskPollDownload();
    }
  }
  // Actions pack download progress (the backend ActionsPackController.publishPack pushes it, same pattern as vosk_download).
  if (msg.type === "actions_pack" && msg.data) {
    if (typeof actionsPackRenderStatus === "function") actionsPackRenderStatus(msg.data);
    if (msg.data.state === "done" || msg.data.state === "error" || msg.data.state === "cancelled") {
      if (typeof actionsPackPollDownloadOnce === "function") actionsPackPollDownloadOnce();
    } else if (typeof actionsPackPollDownload === "function") {
      actionsPackPollDownload();
    }
  }
  // APK download progress (the backend ApkDownloadController.publishApk pushes it, same pattern).
  if (msg.type === "apk" && msg.data) {
    if (typeof apkRenderStatus === "function") apkRenderStatus(msg.data);
    if (msg.data.state === "done" || msg.data.state === "error" || msg.data.state === "cancelled") {
      if (typeof apkPollDownloadOnce === "function") apkPollDownloadOnce();
    } else if (typeof apkPollDownload === "function") {
      apkPollDownload();
    }
  }
  if (msg.type === "sonar_obstacle" && msg.data) {
    sonarThresholdCm = msg.data.thresholdCm;
    sonarHistory.push({ triggered: !!msg.data.triggered });
    if (sonarHistory.length > SONAR_HISTORY_LEN) {
      sonarHistory.shift();
    }
    drawSonarChart();
  }
  if (msg.type === "alpha2_pir_state" && msg.data) {
    onAlpha2PirState(msg.data);
  }
  // Reverse volume sync: when the robot side changes the volume (the head +/- keys /
  // voice volume commands / the slider on the second tab), the LedCenter volume
  // receiver publishes volume_changed, and both sliders follow at once (see
  // onVolumeChangedEvent in app-servo.js. The forward HTML -> robot path syncs through
  // the API response, not here).
  if (msg.type === "volume_changed" && msg.data) {
    if (typeof onVolumeChangedEvent === "function") onVolumeChangedEvent(msg.data);
  }
  if (msg.type === "accel" && msg.data) {
    onAccelSample(msg.data);
  }
  // 小智 (XiaoZhi) AI chat - hand every xiaozhi_* event type to
  // xiaozhiHandleEvent() in app-xiaozhi.js (state/stt/llm/tts/mcp/system/alert).
  // Only dispatch here. Do not repeat render logic. app-xiaozhi.js loads before this
  // file in index.html, but appendLog() runs only when a WebSocket message arrives,
  // so xiaozhiHandleEvent() is always defined at that point.
  if (msg.type.indexOf("xiaozhi_") === 0 && msg.data) {
    xiaozhiHandleEvent(msg.type, msg.data);
  }
  // Gamepad events go straight to the live fields on the gamepad page, such as the
  // "last key" (gamepad_key/bt/axis), before any Log display. app-gamepad.js loads
  // before this file, and this runs only on a WS message, so it is defined. The typeof
  // guard covers an old cached page.
  if ((msg.type === "gamepad_key" || msg.type === "gamepad_bt" || msg.type === "gamepad_axis"
      || msg.type === "gamepad_state")
      && msg.data && typeof onGamepadKeyEvent === "function") {
    try { onGamepadKeyEvent(msg.type, msg.data); } catch (e) { /* ignore */ }
  }
  // tts_end carries no "xiaozhi_" prefix (a global signal that the body / Android local
  // TTS finished one line, not only for 小智. See the two comments in MainActivity
  // onServerPlayEnd / UtteranceProgressListener.onDone()), so the catch-all above does
  // not match it. Route it here into xiaozhiHandleEvent() separately, so the local TTS
  // queue on the 小智 tab (xiaozhiTtsQueue) knows it can read the next line (see the
  // "tts_end" case in xiaozhiHandleEvent()).
  if (msg.type === "tts_end") {
    xiaozhiHandleEvent(msg.type, msg.data);
  }
}

function clearLog() {
  document.getElementById("eventLog").innerHTML = "";
}

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- init ----------------

window.addEventListener("DOMContentLoaded", function () {
  // Run the panel-lock check first: when enabled and not unlocked, open the overlay to cover the panel and suppress the later 401 false alarms from init (see app-core.js).
  if (typeof panelLockCheck === "function") panelLockCheck();
  buildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  if (typeof perledBuild === "function") perledBuild();
  if (typeof perledPollLoop === "function") perledPollLoop();
  setTtsEngine("android"); // Android built-in TTS, load the engine / language lists
  // Vosk card init (scan models + sync state, only when the card exists).
  if (document.getElementById("voskModelBtns") && typeof voskRefreshModels === "function") {
    voskRefreshModels();
  }
  // Device info: show the UUID + chest firmware automatically on page entry (no button press needed).
  refreshDeviceInfo();
  requestUuid();
  refreshChestFw();
  // Experimental tab panel token status line (only when the card exists. auth/status stays open, so no 401 concern).
  if (typeof panelAuthRefreshStatus === "function") panelAuthRefreshStatus();
  applyUiLanguage();
  refreshVolume();
  if (typeof gamepadInitPage === "function") gamepadInitPage();
  // For the switch groups that only push, with no read path: ask the unit for the real state on page entry and set the switches (changes from the mapping toggle are picked up).
  if (typeof syncSonarUi === "function") syncSonarUi();
  if (typeof syncPirUi === "function") syncPirUi();
  if (typeof syncAccelToggle === "function") syncAccelToggle();
  connectWs();
  musicInit();
  if (typeof radioInit === "function") radioInit();
  if (typeof refreshSupportedSizes === "function") refreshSupportedSizes();
  if (typeof buildCameraPhoto9Grid === "function") buildCameraPhoto9Grid();
});


