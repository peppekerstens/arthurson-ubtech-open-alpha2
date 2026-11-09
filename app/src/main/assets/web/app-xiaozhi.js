// Open Alpha2 — client logic (app-xiaozhi.js)
// 小智 (XiaoZhi) AI chat tab - the client-side WebSocket out to xiaozhi.me is done
// server-side (XiaozhiClient.java). This file owns: a single toggle (connect/disconnect/always-on voice
// chat in one), text input, status display, and rendering xiaozhi_* WebSocket events from the EventBus as chat bubbles.
//
// UI design: exactly one toggle (xiaozhiSessionToggle) - on = connect + auto-start listening, voice
// chat anytime; off = disconnect, release the mic. Flipping it on triggers an OTA/device-activation flow
// (check_version -> read out the pairing code -> poll -> only then connect the WebSocket). The user does not fill in
// the WS URL/token - the server fetches those from the official xiaozhi.me OTA endpoint. See
// XiaozhiOtaClient.java / MainActivity#runXiaozhiActivationFlow() class javadoc.
// After the connect finishes, the server-side auto_mode is turned on too (see the
// "connected" case in xiaozhiPollActivationStatus()). After each TTS playback it listens again automatically (driven by server-side
// XiaozhiClient.TtsStateListener; this file does not poll TTS state itself). Text input uses
// listen state:detect with text (see the javadoc of XiaozhiClient#sendListenDetectText().
// This borrows the wake-word-detected message shape; it is not 100% officially guaranteed to work - trust the field tests).
//
// All files share the window/global scope (no ES modules). Load order is set by the
// <script src="..."> order in index.html - see the comment block at the head of index.html.
//
// Depends on escapeHtml()/nowTimeStr()/MAX_LOG_LINES from app-log.js (app-log.js loads
// after this file in index.html). This is safe: every use of these
// identifiers here sits inside a function body (xiaozhiAppendChatLine()), not in module
// top-level code. At actual call time all <script> tags are already loaded (DOMContentLoaded
// passes first, then user actions/WebSocket events trigger these functions). If you later use these
// identifiers directly at top level (outside functions) in this file, you must move <script src="app-log.js">
// ahead of this file, otherwise you get a ReferenceError.

// api()/hwApi() are alpha2/lynx specific (they add the "alpha2/"/"lynx/" prefix). The XiaoZhi
// namespace on the server is backend-agnostic ("/api/xiaozhi/...", see
// MainActivity#handleXiaozhiApi), so this file defines its own helper and does not follow the
// backend prefix logic of api()/hwApi().
function xiaozhiApi(path, params) {
  clearError();
  // Experimental tab panel token: like api()/sysApi(), it travels in the Authorization: Bearer header
  // (panelAuthHeaders + apiQuery live in app-core.js; boot_voice/set needs it when auth is on, missing = 401).
  // The business token of ota_config/set still goes in the query - it is a different key from the header panel token, so they never clash (see PanelAuth).
  const clean = (typeof stripPanelToken === "function") ? stripPanelToken(params) : params;
  const qs = clean ? "?" + new URLSearchParams(clean).toString() : "";
  const hdrs = (typeof panelAuthHeaders === "function") ? panelAuthHeaders(params) : {};
  return fetch(API + "xiaozhi/" + path + qs, { headers: hdrs }).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /xiaozhi/" + path, new Error(json.error || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError("Network error calling /xiaozhi/" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

// Global flag, set once at page load by xiaozhiCheckSupport(). Connecting is not enough - the device
// must also support Opus before voice chat really works (if unsupported, unsupportedNotice shows a hint,
// but the single toggle itself is not disabled for this; plain text chat still works).
let xiaozhiAudioSupported = false;
let xiaozhiMicActive = false;
let xiaozhiAutoModeOn = false;
// TTS output engine choice. "xiaozhi" (default) = original behavior: the server sends opus audio and
// the backend XiaozhiAudioController decodes and plays it. Pick "iflytek"/"nuance"/"android" and the backend
// mutes that opus stream (see the MainActivity.xiaozhiTtsEngine field javadoc); this file then queues via
// xiaozhiTtsQueue and, sentence by sentence at each xiaozhi_tts "sentence_start", calls the local
// speech/tts API (the same API speakTts() in app-speech.js uses) to read it out.
// At page load xiaozhiLoadTtsConfig() reads back the last choice stored on the backend, and syncs this variable with the
// button active state.
// Poll timer handle for the activation flow (checking/awaiting_code/polling/
// connecting stages) - cleared once CONNECTED or ERROR is reached. Kept as a module
// -level variable (not a closure-local) so xiaozhiDisconnect()/a page reload mid
// -activation can't leave a stray setTimeout still firing after the fact.
let xiaozhiActivationPollTimer = null;

// Queue of XiaoZhi reply sentences for the local TTS engine to read - see the comment in the
// "xiaozhi_tts" case of xiaozhiHandleEvent(). One reply can fire several "sentence_start" events
// in a row (sometimes only ~100 ms apart). If each arrival stopped the previous sentence and read the new one,
// the unfinished sentence would be cut off mid-way ("iFlytek only said the first few words"; nuance/android looked fine
// purely because the gap never landed there). So now: queue. A new sentence joins the tail and reads immediately only when
// nothing is playing; while reading, a new sentence only queues, and the next one starts when the
// tts_end event says this sentence finished (the backend robot-side TTS onServerPlayEnd and the Android
// TTS UtteranceProgressListener both publish it, see the two
// comments in MainActivity).
let xiaozhiTtsQueue = [];
let xiaozhiTtsSpeaking = false;

/** When a local TTS engine is selected, enqueue one XiaoZhi reply sentence. If nothing is playing, read it
 *  at once; otherwise it waits at the queue tail for xiaozhiProcessTtsQueue() (triggered by the tts_end event). */
function xiaozhiEnqueueTts(text) {
  xiaozhiTtsQueue.push(text);
  if (!xiaozhiTtsSpeaking) {
    xiaozhiProcessTtsQueue();
  }
}

/** Read the head sentence of the queue (if any). xiaozhiTtsSpeaking is set to true here, and
 *  cleared back to false only when that sentence's tts_end event arrives, then the next
 *  sentence is read (see the "tts_end" case in xiaozhiHandleEvent()). */
function xiaozhiProcessTtsQueue() {
  if (xiaozhiTtsQueue.length === 0) {
    xiaozhiTtsSpeaking = false;
    return;
  }
  xiaozhiTtsSpeaking = true;
  const text = xiaozhiTtsQueue.shift();
  Alpha2Api.speechTts( { text: text, engine: xiaozhiTtsEngine });
}

/** Clear the queue on engine switch (xiaozhiSetTtsEngine()) and on disconnect. Otherwise, after the
 *  engine changes, sentences queued for the old engine would be read by the new one, which does not match the switch moment the user sees. */
function xiaozhiResetTtsQueue() {
  xiaozhiTtsQueue = [];
  xiaozhiTtsSpeaking = false;
}

function xiaozhiElements() {
  return {
    sessionToggle: document.getElementById("xiaozhiSessionToggle"),
    statusBadge: document.getElementById("xiaozhiStatusBadge"),
    chatLog: document.getElementById("xiaozhiChatLog"),
    unsupportedNotice: document.getElementById("xiaozhiUnsupportedNotice"),
    activationBox: document.getElementById("xiaozhiActivationBox"),
    activationCode: document.getElementById("xiaozhiActivationCode"),
    micLed: document.getElementById("xiaozhiMicLed"),
    micStatusBadge: document.getElementById("xiaozhiMicStatusBadge"),
    textInput: document.getElementById("xiaozhiTextInput"),
    sendTextBtn: document.getElementById("xiaozhiSendTextBtn"),
  };
}

function xiaozhiSetStatus(stateKey, extraText) {
  const el = xiaozhiElements().statusBadge;
  if (!el) return;
  el.textContent = t(stateKey) + (extraText ? " " + extraText : "");
}

/** Mic ownership light. Green = this app holds the mic now (releaseMicForAudioIo() has taken effect,
 *  voice chat is using it). Grey = released back to the robot's own wake-word engine. This differs from the
 *  xiaozhiMicActive pure-UI flag: the light reflects the real server-side state of
 *  xiaozhiMicHeld (see MainActivity#startXiaozhiMic()/stopXiaozhiMic() and XIAOZHI_MIC_STATE_EVENT). */
function xiaozhiSetMicLed(held) {
  const els = xiaozhiElements();
  if (els.micLed) {
    els.micLed.classList.toggle("led-dot-on", held);
    els.micLed.classList.toggle("led-dot-off", !held);
  }
  if (els.micStatusBadge) {
    els.micStatusBadge.textContent = t(held ? "xiaozhi_mic_held" : "xiaozhi_mic_released");
  }
}

// One toggle stands for the whole session state: on = connected (link + auto_mode/mic, voice chat anytime),
// off = not connected. This function only mirrors the toggle itself and the enable state of the text input. There is no separate
// mic button/badge - the statusBadge is enough to show the voice chat state.
function xiaozhiSetConnectedUi(connected) {
  const els = xiaozhiElements();
  if (els.sessionToggle) els.sessionToggle.checked = connected;
  // Text input does not go through mic/Opus; it is a plain text message and only needs a connection - see
  // the comment in the "send_text" case of MainActivity#handleXiaozhiApi.
  if (els.sendTextBtn) els.sendTextBtn.disabled = !connected;
  if (!connected) {
    xiaozhiMicActive = false;
    xiaozhiSetMicLed(false);
  }
}

let xiaozhiLastShownActivationCode = null;

/** Append the pairing code into xiaozhiChatLog; xiaozhiLastShownActivationCode stops the same code being inserted twice (the activation_status poll keeps returning it). els.activationBox stays display:none. */
function xiaozhiShowActivationCode(code) {
  const els = xiaozhiElements();
  if (els.activationCode) els.activationCode.textContent = code || "";
  if (code && code !== xiaozhiLastShownActivationCode) {
    xiaozhiLastShownActivationCode = code;
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_activation_code_chat_prefix") + " " + code);
  }
}

/** The pairing code shows only in the conversation UI; two no-op stubs stay so old cached pages do not hit ReferenceError. */

function xiaozhiShowActivationModal() { /* the pairing code shows only in the conversation UI */ }
function xiaozhiHideActivationModal() { /* see above */ }

function xiaozhiHideActivationCode() {
  const els = xiaozhiElements();
  if (els.activationBox) els.activationBox.style.display = "none";
  if (els.activationCode) els.activationCode.textContent = "";
  xiaozhiLastShownActivationCode = null;
}

function xiaozhiStopActivationPolling() {
  if (xiaozhiActivationPollTimer) {
    clearTimeout(xiaozhiActivationPollTimer);
    xiaozhiActivationPollTimer = null;
  }
}

/** Kicks off the OTA/device-activation flow (see MainActivity#handleXiaozhiApi's
 *  "connect" case) and starts polling "xiaozhi/activation_status" to follow its
 *  progress through checking -> (optionally) awaiting_code/polling -> connecting ->
 *  connected, updating the status badge and activation-code box at each stage. Once
 *  connected, xiaozhiPollActivationStatus()'s "connected" branch turns auto_mode on
 *  so mic capture starts immediately without a separate button (see single-toggle
 *  design in xiaozhiToggleSession()). */
function xiaozhiConnect() {
  xiaozhiHideActivationCode();
  xiaozhiSetStatus("xiaozhi_status_checking");
  Alpha2Api.xiaozhiConnect({}).then(function (res) {
    if (!res.ok) {
      xiaozhiSetStatus("xiaozhi_status_error");
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || "connect failed");
      xiaozhiSetConnectedUi(false);
      return;
    }
    xiaozhiPollActivationStatus();
  });
}

function xiaozhiPollActivationStatus() {
  xiaozhiStopActivationPolling();
  Alpha2Api.xiaozhiActivationStatus({}).then(function (res) {
    if (!res.ok) {
      xiaozhiSetStatus("xiaozhi_status_error");
      return;
    }
    switch (res.stage) {
      case "checking":
        xiaozhiSetStatus("xiaozhi_status_checking");
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 1000);
        break;
      case "awaiting_code":
      case "polling":
        xiaozhiSetStatus("xiaozhi_status_awaiting_code");
        xiaozhiShowActivationCode(res.code);
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 2000);
        break;
      case "connecting":
        xiaozhiSetStatus("xiaozhi_status_connecting");
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 1000);
        break;
      case "connected":
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
        // Single-toggle design: once connected, auto_mode turns on in the same step, which means auto mic grab and
        // voice chat anytime, so the user never presses a second time - see the toggle label in index.html.
        xiaozhiAutoModeOn = true;
        Alpha2Api.xiaozhiAutoMode({ enabled: "true" });
        break;
      case "error":
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_error");
        xiaozhiSetConnectedUi(false);
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || "activation failed");
        break;
      case "idle":
      default:
        // Nothing in progress - stop polling rather than spinning forever; a fresh
        // xiaozhiConnect() call will restart polling from "checking".
        break;
    }
  });
}

function xiaozhiDisconnect() {
  xiaozhiStopActivationPolling();
  xiaozhiHideActivationCode();
  // Reflects server-side behaviour: MainActivity#handleXiaozhiApi's "disconnect" case
  // clears auto_mode itself, so the local flag should reset here too rather than
  // staying true against a session that's about to go away.
  xiaozhiAutoModeOn = false;
  // After a disconnect, sentences still queued are meaningless (XiaoZhi is gone and no new conversation will
  // follow). Clear them all; do not leave old sentences waiting for the next connect to read them.
  xiaozhiResetTtsQueue();
  Alpha2Api.xiaozhiDisconnect({}).then(function () {
    xiaozhiSetStatus("xiaozhi_status_disconnected");
    xiaozhiSetConnectedUi(false);
  });
}

/** Single toggle: on = connect (it internally runs the OTA/activation flow, then auto_mode grabs the
 *  mic automatically, giving voice chat anytime), off = disconnect (auto_mode and mic stop together). The user only needs
 *  "on means usable, off means not". The toggle mirrors the user's intent at once; the real
 *  connection state is driven by xiaozhi_state events from xiaozhiPollActivationStatus()/
 *  xiaozhiHandleEvent(), and a failed connect flips the toggle back through
 *  xiaozhiSetConnectedUi(false). */
function xiaozhiToggleSession() {
  const els = xiaozhiElements();
  const wantOn = !!(els.sessionToggle && els.sessionToggle.checked);
  if (wantOn) {
    xiaozhiConnect();
  } else {
    xiaozhiDisconnect();
  }
}

// Flag for a text send in progress - see xiaozhiSendText(). Kept outside the function (module level,
// shared through the window/global scope like xiaozhiTtsQueue).
let xiaozhiSendTextInFlight = false;
// The original text + time of the last typed send - see the xiaozhi_stt filtering below.
let xiaozhiLastSentText = "";
let xiaozhiLastSentAtMs = 0;

/** PHASE 4 (text input): sends whatever's typed in the text box as a "detect" message
 *  (see XiaozhiClient#sendListenDetectText()'s javadoc for the protocol caveat this
 *  relies on) rather than through the mic/Opus path - works regardless of
 *  xiaozhiAudioSupported since no audio codec is involved.
 *
 *  The server echo (stt message) is the only source for the chat log - this function only clears the input, it never appends itself.
 */
function xiaozhiSendText() {
  // Two rapid typed sends (Enter plus button at once, or holding Enter with key-repeat) open two identical
  // LLM turns; the server then queues two take_photo calls, the camera collides, and the server drops the line. Synchronous
  // check + lock inside the single thread: the first call gets in, every later one (including Enter repeats before the
  // response) is swallowed. The lock releases only on the response (see the then below); a button disabled flag is not
  // enough because the Enter path does not go through it.
  if (xiaozhiSendTextInFlight) return;
  const els = xiaozhiElements();
  const text = els.textInput ? (els.textInput.value || "").trim() : "";
  if (!text) return;
  // Defensive check: the real-device WebView autofill can put the placeholder into .value by mistake. If the text equals the placeholder translation, treat it as non-user input: do not send, and prompt to retype.
  if (text === t("xiaozhi_text_placeholder")) {
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_send_text_error"));
    if (els.textInput) els.textInput.value = "";
    return;
  }
  xiaozhiSendTextInFlight = true;
  if (els.sendTextBtn) els.sendTextBtn.disabled = true;
  // The timestamp used for echo matching must be recorded synchronously before the fetch: the two STT lines
  // (phantom + echo) travel over the WebSocket and can beat this HTTP response; recording it in the then would always be too late.
  xiaozhiLastSentText = text;
  xiaozhiLastSentAtMs = Date.now();
  Alpha2Api.xiaozhiSendText({ text: text }).then(function (res) {
    if (res.ok) {
      if (els.textInput) els.textInput.value = "";
    } else {
      // If the send failed, act as if nothing was sent, so real voice STT within the next 2.5 s is not killed by mistake.
      xiaozhiLastSentAtMs = 0;
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_send_text_error"));
    }
    xiaozhiSendTextInFlight = false;
    if (els.sendTextBtn) els.sendTextBtn.disabled = !xiaozhiClientIsConnected();
  });
}

/** Stop-all button - interrupts in one press: action playback (action/stop), TTS (speech/stop, see the
 *  handleApi() "speech/stop" case), and local music (audio/local_music/stop). Uses api(), not
 *  xiaozhiApi() - these endpoints belong to the alpha2/lynx backend-specific namespace (see
 *  handleApi()), not the backend-agnostic "xiaozhi/" namespace of handleXiaozhiApi().
 *
 *  The three requests go out together via Promise.all (not awaited one by one). Reasons: (1) the three
 *  jobs are independent, so one failing must not delay when the other two start; (2) a user pressing this
 *  button usually wants "shut up now", which is latency-sensitive, and sequential sends would make the total delay the sum
 *  of the three request times. A single failed request (for example a backend without support) must not affect the
 *  other two. api() already calls showError() itself in its network/non-ok response case, so no extra
 *  try/catch layer is needed here. */
function xiaozhiStopAll() {
  // Pressing stop-all means the user wants instant silence - queued sentences must not pop out and read
  // themselves later. Clear them all.
  xiaozhiResetTtsQueue();
  Promise.all([
    Alpha2Api.actionStop(),
    Alpha2Api.speechStop(),
    Alpha2Api.audioLocalMusicStop(),
    Alpha2Api.audioRadioStop(), // FM/internet radio are also "playing" states, so they join local music under the stop-all button.
    Alpha2Api.ledHeadSet({ preset: "stop" }), // Stop-all also stops the head LED (the mouth LED follows the TTS stop, see speech/stop).
    Alpha2Api.ledEyeSet({ preset: "stop" }), // Eye LED: same as above.
  ]);
}

/** Cheap local read of the last-known connected state via the session toggle's
 *  checked flag (set by xiaozhiSetConnectedUi()) - avoids a redundant round-trip to
 *  "xiaozhi/status" just to decide whether to re-enable the send button after a
 *  send_text call. */
function xiaozhiClientIsConnected() {
  const els = xiaozhiElements();
  return !!(els.sessionToggle && els.sessionToggle.checked);
}

/** Appends one chat-log line. roleClass drives the bubble's CSS styling
 *  (xiaozhi-msg-user/xiaozhi-msg-assistant/xiaozhi-msg-system - see style.css).
 *  The timestamp stays (small text under each sentence shows the send time). */
function clearXiaozhiChatLog() {
  const log = xiaozhiElements().chatLog;
  if (log) log.innerHTML = "";
}

function xiaozhiAppendChatLine(roleClass, text) {
  const log = xiaozhiElements().chatLog;
  if (!log) return;
  const line = document.createElement("div");
  line.className = "log-line xiaozhi-msg " + roleClass;
  const bodyHtml = escapeHtml(text);
  const metaHtml = (roleClass === "xiaozhi-msg-assistant" || roleClass === "xiaozhi-msg-user")
      ? "<div class=\"xiaozhi-msg-meta\">" + nowTimeStr() + "</div>"
      : "";
  line.innerHTML = roleClass === "xiaozhi-msg-system"
      ? "<span class=\"log-time\">[" + nowTimeStr() + "]</span> " + bodyHtml
      : bodyHtml + metaHtml;
  log.appendChild(line);
  while (log.childElementCount > MAX_LOG_LINES) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

/** MCP tool-call card - follows the xiaozhi.me console "conversation history" style: an expandable "🔧 tool call"
 *  card that shows the tool and its arguments. Elapsed time is not tracked: request/response are separate events with no shared id
 *  (see EVT_MCP), so timing would need extra matching, and the value does not justify the cost. */
function xiaozhiAppendMcpToolCallCard(toolName, argsObj) {
  const log = xiaozhiElements().chatLog;
  if (!log) return;
  const card = document.createElement("div");
  card.className = "xiaozhi-mcp-card";
  const header = document.createElement("div");
  header.className = "xiaozhi-mcp-card-header";
  header.textContent = "🔧 " + t("xiaozhi_mcp_call_label");
  const body = document.createElement("div");
  body.className = "xiaozhi-mcp-card-body";
  let argsStr = "{}";
  try { argsStr = JSON.stringify(argsObj || {}); } catch (e) { /* leave default */ }
  body.textContent = toolName + "(" + argsStr + ")";
  card.appendChild(header);
  card.appendChild(body);
  log.appendChild(card);
  while (log.childElementCount > MAX_LOG_LINES) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

/** Called from appendLog() in app-log.js for every xiaozhi_* WebSocket event -
 *  kept as a single entry point (rather than each xiaozhi_* type having its own
 *  "if (msg.type === ...)" block inline in app-log.js) so all the XiaoZhi-specific
 *  rendering logic lives in this file instead of being scattered across app-log.js.
 *  Note: xiaozhi_state events fire from XiaozhiClient's own WebSocket lifecycle,
 *  which is a *different* signal from the activation_status polling above - both are
 *  kept (rather than replacing polling with purely event-driven updates) because the
 *  activation flow's "checking"/"awaiting_code"/"polling"/"connecting" stages all
 *  happen *before* XiaozhiClient's WebSocket exists to emit any state at all. */
function xiaozhiHandleEvent(type, data) {
  switch (type) {
    case "xiaozhi_state":
      if (data.state === "disconnected") {
        xiaozhiStopActivationPolling();
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_disconnected");
        xiaozhiSetConnectedUi(false);
        if (data.reason) {
          xiaozhiAppendChatLine("xiaozhi-msg-system", data.reason);
        }
      } else if (data.state === "error") {
        xiaozhiStopActivationPolling();
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_error");
        xiaozhiSetConnectedUi(false);
        xiaozhiAppendChatLine("xiaozhi-msg-system", data.message || "error");
      }
      break;
    // The chat view shows only real conversation (user speech/typing + XiaoZhi replies), right/left aligned and
    // color-coded - see xiaozhiAppendChatLine() and .xiaozhi-msg-user/.xiaozhi-msg-assistant in style.css.
    // MCP tool calls (xiaozhi_mcp), emotion hints (xiaozhi_llm), and system commands
    // (xiaozhi_system) are protocol noise and no longer enter the chat log - the full content still goes into the main event
    // log (eventLog, see appendLog() in app-log.js); it just stays out of this chat view.
    // xiaozhi_alert is the exception: it is a server-pushed warning (for example low battery) that the user should
    // see in the chat view, so it is kept.
    case "xiaozhi_stt":
      // Typing produces two lines: before sending detect the backend stops the mic, and the server
      // settles the leftover background audio received before the stop into a phantom STT sentence (for example "嗯"/"我"; in tests it
      // always lands a few hundred ms before the echo of your own sentence). That makes every typed send show two lines, the first one unrelated.
      // Echo matching: any STT that differs from the last sent original text (compared with trailing punctuation removed) and arrives within
      // 2.5 s of the send is treated as phantom and kept out of the chat bubbles. The main event log (appendLog) still records
      // everything, so debugging loses nothing. Spoken voice does not pass here (no lastSent) and is unaffected.
      if (data.text) {
        const norm = function (s) { return (s || "").replace(/[ 。？！!?….]+$/g, ""); };
        const ageMs = Date.now() - xiaozhiLastSentAtMs;
        const isEcho = xiaozhiLastSentText && norm(data.text) === norm(xiaozhiLastSentText) && ageMs < 15000;
        if (!isEcho && ageMs < 2500 && xiaozhiLastSentAtMs > 0) break;
        xiaozhiAppendChatLine("xiaozhi-msg-user", data.text);
      }
      break;
    case "xiaozhi_tts":
      // The real XiaoZhi reply text is this event's "sentence_start" (the chat bubbles are
      // rendered from it too). xiaozhi_llm's data.text is actually an emotion emoji (for example "😆"),
      // not conversation content, so it must never be read aloud.
      if (data.state === "sentence_start" && data.text) {
        xiaozhiAppendChatLine("xiaozhi-msg-assistant", data.text);
        if (xiaozhiTtsEngine !== "xiaozhi") {
          xiaozhiEnqueueTts(data.text);
        }
      }
      // "stop" drives auto-continue server-side (XiaozhiClient.TtsStateListener) -
      // mic automatically re-starts there, nothing to do here beyond the chat line
      // above.
      if (data.state === "stop" && xiaozhiAutoModeOn) {
        xiaozhiMicActive = true;
      }
      break;
    // The local TTS engine finished one sentence (both the backend robot-side onServerPlayEnd and
    // the Android TTS UtteranceProgressListener publish this event, see the two comments in
    // MainActivity) - this triggers the queue to read the next sentence. Not only the XiaoZhi tab's
    // speech/tts calls receive it; speakTts() manual tests on the Speech tab also receive it, but that side has no
    // queue logic, so it is unaffected. The event type has no "xiaozhi_" prefix (tts_end is the
    // global TTS-done signal, not XiaoZhi-only). The dispatch is a separate line in app-log.js's appendLog()
    // that forwards it; it must not rely on the "xiaozhi_" prefix catch-all path - see the comment there.
    case "tts_end":
      xiaozhiProcessTtsQueue();
      break;
    case "xiaozhi_alert":
      xiaozhiAppendChatLine("xiaozhi-msg-system", "⚠ " + data.status + ": " + data.message);
      break;
    // Select only the direction:"in" payload with method:"tools/call" (the moment the server asks to run a tool; not initialize/tools/list noise, and not the response), then insert the "tool call" card.
    case "xiaozhi_mcp": {
      const payload = data.payload;
      if (data.direction === "in" && payload && payload.method === "tools/call"
          && payload.params && payload.params.name) {
        xiaozhiAppendMcpToolCallCard(payload.params.name, payload.params.arguments);
      }
      break;
    }
    // Server-side mic-ownership state (see MainActivity#startXiaozhiMic()/
    // stopXiaozhiMic()'s XIAOZHI_MIC_STATE_EVENT publish) - drives the green/grey
    // LED so the person can see whether this app actually holds the mic hardware,
    // separate from xiaozhiMicActive (a UI-only flag) or xiaozhi_tts's auto-continue
    // bookkeeping above.
    case "xiaozhi_mic_state":
      xiaozhiSetMicLed(!!data.held);
      break;
    default:
      break;
  }
}

/** Runs once at page load: asks the robot whether this Android version can support
 *  the Opus audio path (see XiaozhiClient.isAudioSupported() / handleXiaozhiApi
 *  "supported" endpoint), stores the result in xiaozhiAudioSupported for
 *  xiaozhiSetConnectedUi() to gate the mic button on, and shows/hides the
 *  unsupported-hint text accordingly. */
function xiaozhiCheckSupport() {
  Alpha2Api.xiaozhiSupported({}).then(function (res) {
    xiaozhiAudioSupported = !!(res.ok && res.audioSupported);
    const notice = xiaozhiElements().unsupportedNotice;
    if (notice) {
      notice.style.display = xiaozhiAudioSupported ? "none" : "block";
    }
    // Re-apply gating in case xiaozhiRefreshStatus() already ran and enabled/disabled
    // the connect-dependent buttons before this (async) support check resolved -
    // whichever of the two finishes last should reflect both conditions correctly.
    xiaozhiSetConnectedUi(xiaozhiClientIsConnected());
  });
}

/** Runs once at page load: reflects whatever connection/auto-mode/mic state the
 *  robot is already in (e.g. browser tab was reloaded while a XiaoZhi session was
 *  already active from before) rather than always starting the UI in "disconnected". */
function xiaozhiRefreshStatus() {
  Alpha2Api.xiaozhiStatus({}).then(function (res) {
    if (!res.ok) return;
    if (res.connected) {
      xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
      xiaozhiSetConnectedUi(true);
      xiaozhiAutoModeOn = !!res.autoMode;
      xiaozhiMicActive = !!res.micActive;
      xiaozhiSetMicLed(!!res.micHeld);
      return;
    }
    // Not connected yet - check whether an activation attempt is still in flight
    // (e.g. the person reloaded the page while waiting to enter the code) and resume
    // polling it rather than showing a plain "disconnected" that would make them
    // think they need to press Connect again mid-pairing.
    Alpha2Api.xiaozhiActivationStatus({}).then(function (actRes) {
      if (actRes.ok && (actRes.stage === "checking" || actRes.stage === "awaiting_code"
          || actRes.stage === "polling" || actRes.stage === "connecting")) {
        xiaozhiPollActivationStatus();
      } else {
        xiaozhiSetStatus("xiaozhi_status_disconnected");
        xiaozhiSetConnectedUi(false);
      }
    });
  });
}

/** Background resident poll, one check every 8 seconds. The background auto-reconnect
 *  (xiaozhiScheduleReconnect() -> runXiaozhiActivationFlow()) does not notify the frontend on its own, so if a
 *  reconnect needs a new pairing code (token expired / server unbound), this keeps watching for state changes without a manual refresh.
 *  It does not clash with the short-interval poll: both share xiaozhiStopActivationPolling() to clear the timer, so no double polling. */
function xiaozhiBackgroundStatusWatch() {
  // An activation polling loop is already running here (xiaozhiActivationPollTimer is not
  // null). That means the user just pressed connect manually, or is in awaiting_code/polling/connecting.
  // This slow poll does not need to step in.
  if (!xiaozhiActivationPollTimer) {
    Alpha2Api.xiaozhiStatus({}).then(function (res) {
      if (!res.ok) return;
      if (res.connected) {
        xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
        xiaozhiAutoModeOn = !!res.autoMode;
        xiaozhiMicActive = !!res.micActive;
        xiaozhiSetMicLed(!!res.micHeld);
      } else {
        Alpha2Api.xiaozhiActivationStatus({}).then(function (actRes) {
          if (actRes.ok && (actRes.stage === "checking" || actRes.stage === "awaiting_code"
              || actRes.stage === "polling" || actRes.stage === "connecting")) {
            // Found an activation attempt that just started in the background (usually triggered by auto-reconnect).
            // Hand it back to the original short-interval poll so the pairing code/state shows up live.
            xiaozhiPollActivationStatus();
          } else {
            xiaozhiSetConnectedUi(false);
          }
        });
      }
    });
  }
  setTimeout(xiaozhiBackgroundStatusWatch, 8000);
}

/** Read back the saved custom server settings (see the MainActivity "ota_config/get" endpoint) and
 *  reflect them in the toggle and input boxes. Call at page load so the user sees the settings they picked
 *  before, instead of the panel resetting to "never configured" every time it opens. */
function xiaozhiLoadOtaConfig() {
  Alpha2Api.xiaozhiOtaConfigGet({}).then(function (res) {
    if (!res.ok) return;
    const toggle = document.getElementById("xiaozhiOtaCustomToggle");
    const box = document.getElementById("xiaozhiOtaCustomBox");
    const urlInput = document.getElementById("xiaozhiOtaCustomUrl");
    const wsInput = document.getElementById("xiaozhiWsUrlOverride");
    const deviceIdInput = document.getElementById("xiaozhiDeviceIdOverride");
    const tokenInput = document.getElementById("xiaozhiTokenOverride");
    if (toggle) toggle.checked = !!res.customEnabled;
    if (box) box.style.display = res.customEnabled ? "" : "none";
    if (urlInput && res.customUrl) urlInput.value = res.customUrl;
    if (wsInput && res.wsUrlOverride) wsInput.value = res.wsUrlOverride;
    if (deviceIdInput && res.deviceIdOverride) deviceIdInput.value = res.deviceIdOverride;
    if (tokenInput && res.tokenOverride) tokenInput.value = res.tokenOverride;
  });
}

/** Toggle switch - on shows the input box and waits for the user to fill the URL (nothing saves yet; press "Save" to take effect,
 *  see xiaozhiSaveOtaCustom()); off saves at once (follow the official xiaozhi.me; do not make the user
 *  press anything extra) and hides the input box. */
function xiaozhiToggleOtaCustom() {
  const toggle = document.getElementById("xiaozhiOtaCustomToggle");
  const box = document.getElementById("xiaozhiOtaCustomBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (!wantOn) {
    Alpha2Api.xiaozhiOtaConfigSet({ enabled: "false" }).then(function (res) {
      if (!res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
      }
    });
  }
  // When wantOn=true this only shows the input box; it does not save yet. The send waits until the user really fills a
  // url and presses "Save". This stops a lone toggle press (url still empty) from triggering ota_config/set
  // and leaving the next connect without a url.
}

/** The "Save" button - sends the OTA URL plus three optional overrides (WebSocket address/MAC/Token;
 *  all left empty = follow the automatic flow; see the comment on the MainActivity "ota_config/set"
 *  endpoint) with enabled=true. That endpoint itself rejects changes while connected and validates the
 *  format, so the error handling here mainly reports the validation the server already did, instead of
 *  duplicating the validation logic. */
function xiaozhiSaveOtaCustom() {
  const urlInput = document.getElementById("xiaozhiOtaCustomUrl");
  const wsInput = document.getElementById("xiaozhiWsUrlOverride");
  const deviceIdInput = document.getElementById("xiaozhiDeviceIdOverride");
  const tokenInput = document.getElementById("xiaozhiTokenOverride");
  const url = urlInput ? (urlInput.value || "").trim() : "";
  const wsUrl = wsInput ? (wsInput.value || "").trim() : "";
  const deviceId = deviceIdInput ? (deviceIdInput.value || "").trim() : "";
  const token = tokenInput ? (tokenInput.value || "").trim() : "";
  if (!url) {
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_ota_custom_url_required"));
    return;
  }
  Alpha2Api.xiaozhiOtaConfigSet({ enabled: "true", url: url, wsUrl: wsUrl, deviceId: deviceId, token: token })
    .then(function (res) {
      if (res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_ota_custom_saved"));
      } else {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
        // The server refused (for example a connect in progress, or a bad url format). Keep the toggle flipped back to
        // checked so the user still sees the input box and can edit it. Do not auto-off the toggle and scare
        // them; just tell them the reason and let them decide whether to fix the url or disconnect first.
      }
    });
}

/** Built-in MCP tool list card - one "Expand" toggle at the top (same
 *  toggle-switch style as the custom server card). On = expand the whole tool list (with per-item enable/disable
 *  buttons shown too); off = collapse the whole box, nothing visible. List content reads/writes go through MainActivity's
 *  "mcp_config/set"/"mcp_tools/list" (mcp_tools/list returns the full list with per-item enabled
 *  state; mcp_config/set writes one tool's enabled state). The first expand fetches the list; after that,
 *  collapse only hides it and never clears the loaded data, so re-expanding needs no reload. */
function xiaozhiToggleMcpExpandAll() {
  const toggle = document.getElementById("xiaozhiMcpExpandToggle");
  const box = document.getElementById("xiaozhiMcpToolsBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (wantOn) xiaozhiLoadMcpTools();
}

function xiaozhiToggleMcpTool(toolName, checkbox) {
  const wantOn = !!(checkbox && checkbox.checked);
  Alpha2Api.xiaozhiMcpConfigSet({ tool: toolName, enabled: wantOn ? "true" : "false" }).then(function (res) {
    if (!res.ok) {
      // The server refused - flip the checkbox back to its previous state. Do not leave the false impression of
      // "looks changed but nothing took effect".
      if (checkbox) checkbox.checked = !wantOn;
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_mcp_tools_error"));
    }
  });
}

function xiaozhiLoadMcpTools() {
  const box = document.getElementById("xiaozhiMcpToolsList");
  if (!box) return;
  box.textContent = t("xiaozhi_mcp_tools_loading");
  Alpha2Api.xiaozhiMcpToolsList({}).then(function (res) {
    if (!res.tools || !Array.isArray(res.tools) || res.tools.length === 0) {
      box.textContent = t("xiaozhi_mcp_tools_empty");
      return;
    }
    box.innerHTML = "";
    box.removeAttribute("data-i18n");
    res.tools.forEach(function (tool) {
      const wrapper = document.createElement("div");
      wrapper.style.borderBottom = "1px solid var(--border)";
      wrapper.style.padding = "6px 0";

      const item = document.createElement("div");
      item.className = "row";
      item.style.alignItems = "center";

      const name = document.createElement("div");
      name.style.flex = "1";
      name.style.fontWeight = "600";
      name.textContent = tool.name || "";

      const switchLabel = document.createElement("label");
      switchLabel.className = "switch-label";
      const switchSpan = document.createElement("span");
      switchSpan.className = "toggle-switch";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = !!tool.enabled;
      checkbox.onchange = function () { xiaozhiToggleMcpTool(tool.name, checkbox); };
      const track = document.createElement("span");
      track.className = "toggle-track";
      switchSpan.appendChild(checkbox);
      switchSpan.appendChild(track);
      switchLabel.appendChild(switchSpan);

      item.appendChild(name);
      item.appendChild(switchLabel);

      const desc = document.createElement("div");
      desc.className = "hint";
      desc.textContent = tool.description || "";
      desc.style.marginTop = "4px";

      wrapper.appendChild(item);
      wrapper.appendChild(desc);
      box.appendChild(wrapper);
    });
  }).catch(function () {
    box.textContent = t("xiaozhi_mcp_tools_error");
  });
}

/** At page load this only initializes the toggle state (collapsed by default, no API call). Expanding the list needs the user
 *  to open the toggle, which then triggers xiaozhiLoadMcpTools(). */
function xiaozhiLoadMcpConfig() {
  // There is no persisted state to read. The toggle only controls the expand/collapse UI; every page open defaults to
  // collapsed (same as the custom server card), and the last state is not remembered.
}

// TTS output engine choice - see the xiaozhiTtsEngine variable javadoc.
let xiaozhiTtsEngine = "xiaozhi";

function xiaozhiSetTtsEngineUi(engine) {
  // Only "xiaozhi"/"android" remain - old settings (iflytek/nuance) all display as "xiaozhi".
  if (engine !== "xiaozhi" && engine !== "android") engine = "xiaozhi";
  xiaozhiTtsEngine = engine;
  document.getElementById("xiaozhiTtsEngineXiaozhiBtn").classList.toggle("active", engine === "xiaozhi");
  document.getElementById("xiaozhiTtsEngineAndroidBtn").classList.toggle("active", engine === "android");
  // Also clear the old queue on an engine switch (or on the page-load read-back of the last choice). Otherwise, after the
  // engine changes, sentences queued for the old engine would be read by the new engine. That would not match the switch
  // moment the user sees, and the engine swap could also leave xiaozhiTtsSpeaking stuck at true (the previous
  // engine's sentence would never return a tts_end).
  xiaozhiResetTtsQueue();
}

/** Button press - writes the choice back to the backend (xiaozhi/tts_config/set, which also makes MainActivity's
 *  onIncomingOpusFrame sink start/stop muting opus immediately, no reconnect needed). Update the button
 *  active state only on success; on failure change nothing, so the user sees the button did not jump and can press again. */
function xiaozhiSetTtsEngine(engine) {
  Alpha2Api.xiaozhiTtsConfigSet({ engine: engine }).then(function (res) {
    if (res && res.ok) {
      xiaozhiSetTtsEngineUi(engine);
    }
  });
}

/** Boot voice card toggle (Experimental tab) - it only opens/collapses the content, the same approach as the UUID card.
 *  Collapsed by default on every page visit; not remembered. */
function bootVoiceCardToggle() {
  const on = document.getElementById("bootVoiceCardEnabled");
  const body = document.getElementById("bootVoiceCardBody");
  const hint = document.getElementById("bootVoiceCardDisabledHint");
  const show = !!(on && on.checked);
  if (body) body.style.display = show ? "" : "none";
  if (hint) hint.style.display = show ? "none" : "";
  if (show) bootVoiceLoad();
}

/** Point the three radios at the given mode; pass null/an unknown value and all three stay unchecked. */
function bootVoiceUi(mode) {
  const radios = document.querySelectorAll('input[name="bootVoiceMode"]');
  radios.forEach(function (r) { r.checked = (r.value === mode); });
}

/** Read back the boot voice mode at page load / card open. Old API-19 devices have no Vosk, so lock the Vosk radio there. */
function bootVoiceLoad() {
  Alpha2Api.xiaozhiBootVoiceGet({}).then(function (res) {
    bootVoiceUi(res && res.ok ? res.mode : null);
  });
  Alpha2Api.status().then(function (st) {
    if (st && st.ok && st.apiLevel && st.apiLevel < 21) {
      const radio = document.querySelector('input[name="bootVoiceMode"][value="vosk"]');
      if (radio) radio.disabled = true;
    }
  });
}

/** Pick one of three: the choice saves at once and takes effect on the next app start (no immediate connect / immediate listening). */
function bootVoiceSet(mode) {
  const out = document.getElementById("bootVoiceStatus");
  Alpha2Api.xiaozhiBootVoiceSet({ mode: mode }).then(function (res) {
    if (res && res.ok) {
      bootVoiceUi(res.mode);
      if (out) out.textContent = t("boot_voice_saved");
    } else {
      if (out) out.textContent = t("boot_voice_failed_prefix") + (res && res.error ? res.error : "?");
      bootVoiceLoad();
    }
  });
}

/** Read back the last selected TTS engine at page load and sync the button active state - follows the
 *  same pattern as xiaozhiLoadOtaConfig(). */
function xiaozhiLoadTtsConfig() {
  Alpha2Api.xiaozhiTtsConfigGet().then(function (res) {
    if (res && res.ok && res.engine) {
      xiaozhiSetTtsEngineUi(res.engine);
    }
  });
}

/** Random-move-while-speaking toggle (xiaozhi/filler_action/get|set), on by default. */
function xiaozhiLoadFillerAction() {
  Alpha2Api.xiaozhiFillerActionGet().then(function (res) {
    const box = document.getElementById("xiaozhiFillerToggle");
    if (box && res && res.ok) box.checked = !!res.enabled;
  });
}

function xiaozhiSetFillerAction(enabled) {
  Alpha2Api.xiaozhiFillerActionSet({ enabled: enabled ? "true" : "false" }).then(function (res) {
    const box = document.getElementById("xiaozhiFillerToggle");
    // On failure re-read the real backend state, so the switch never drifts out of sync with the backend.
    if (box && res && res.ok) box.checked = !!res.enabled;
    else xiaozhiLoadFillerAction();
  });
}

window.addEventListener("DOMContentLoaded", function () {
  xiaozhiCheckSupport();
  xiaozhiRefreshStatus();
  xiaozhiLoadOtaConfig();
  xiaozhiLoadMcpConfig();
  xiaozhiLoadTtsConfig();
  xiaozhiLoadFillerAction();
  setTimeout(xiaozhiBackgroundStatusWatch, 8000);
});

