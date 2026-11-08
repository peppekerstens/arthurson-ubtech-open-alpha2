// Open Alpha2 — client logic (app-speech.js)
// Content: TTS/ASR/self-interrupt/service_config presets/three-way voice input test.
// All files share the window/global scope (no ES modules). Load order follows the
// <script src="..."> order in index.html - see the comment block at the top of index.html.

// ---------------- Speech / TTS (built-in Android only) ----------------
//
// The voice tab keeps only the Android system TTS. currentTtsEngine is always "android", and setTtsEngine()
// only loads the Android engine/language list (for page-open init. The name stays, so app-log.js
// does not need its init call renamed as well).
let currentTtsEngine = "android";

// ---------------- Speech / chat UI (copies the "XiaoZhi" tab approach exactly) ----------------
//
// Compare xiaozhiAppendChatLine()/xiaozhiSendText() in app-xiaozhi.js -
// this is only the display layer. It renders the two data flows, asr_result (recognised text) and
// speakTts() (what TTS says), as user/assistant chat bubbles separately, and changes no underlying API. The CSS classes reuse
// the existing style.css xiaozhi-msg / xiaozhi-msg-user / xiaozhi-msg-assistant /
// xiaozhi-msg-system (see the xiaozhiAppendChatLine comment), so both tabs look identical.
//
// appendSpeechChatLine() is nearly identical to xiaozhiAppendChatLine() (timestamps,
// MAX_LOG_LINES cap, scrollTop auto-scroll to bottom). Reason for no shared function: the two
// tabs each drive their own chat-log DOM element (speechChatLog vs xiaozhiChatLog),
// so a shared function would need an extra elementId parameter, and the extra indirection buys no readability.
function appendSpeechChatLine(roleClass, text) {
  const log = document.getElementById("speechChatLog");
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

function clearSpeechChatLog() {
  const log = document.getElementById("speechChatLog");
  if (log) log.innerHTML = "";
}

/** "Send" in the text box - treat the typed text as already recognised and feed it straight to the question matcher
 *  (1000 entries each for Chinese/English, SemanticMatcherZh/SemanticMatcherEn, chosen by whether the input
 *  has Han characters). A hit runs TTS + (possibly) an action at once - no real speech needed to test the
 *  whole "heard -> speak/act" pipeline.
 *
 *  This path runs no on-device AIDL recognition, only local text matching,
 *  so it never fires the asr_result WebSocket event. Show the user bubble optimistically here at send time
 *  (the same way speakTts() shows assistant bubbles. This is not "show on send + server echo shows it again",
 *  because this path has no server echo coming back). Show the assistant bubble
 *  (the matched reply) from response.answer, together with type/operation, so you can see
 *  which question matched and whether an action fired. On a miss show one system note instead of faking a reply. */
function sendSpeechChatText() {
  const input = document.getElementById("speechChatTextInput");
  const text = input ? (input.value || "").trim() : "";
  if (!text) return;
  const btn = document.getElementById("speechChatSendBtn");
  if (btn) btn.disabled = true;
  appendSpeechChatLine("xiaozhi-msg-user", text);
  return Alpha2Api.speechSemanticSimulate( { text: text }).then(function (res) {
    if (input) input.value = "";
    if (!res || !res.ok) {
      appendSpeechChatLine("xiaozhi-msg-system",
          t("speech_chat_simulate_error_prefix") + (res && res.error ? res.error : t("asr_reset_failed_unknown")));
      return;
    }
    if (!res.matched) {
      appendSpeechChatLine("xiaozhi-msg-system", t("speech_chat_simulate_no_match"));
      return;
    }
    if (res.answer) {
      appendSpeechChatLine("xiaozhi-msg-assistant", res.answer);
    }
    const detail = "[" + res.type + (res.operation ? " " + res.operation : "") + "]"
        + (res.actionId ? " " + t("speech_chat_simulate_action_prefix") + res.actionId : "");
    appendSpeechChatLine("xiaozhi-msg-system", detail);
  }).finally(function () {
    if (btn) btn.disabled = false;
  });
}

function setTtsEngine(engine) {
  // Always Android: parameters are still accepted, any value counts as android; load the engine/language list directly.
  currentTtsEngine = "android";
  loadAndroidTtsEngines();
  loadAndroidTtsLanguages();
  loadTtsFollowVosk();
}

/** Pick the Android TTS engine (the system TTS that the speech/tts engine=android branch actually speaks with)
 *  - switched by speech/set_tts_engine. That switch
 *  is async itself (the backend tears down the old and builds a new TextToSpeech instance), so after it finishes, wait a short
 *  delay, then re-read speech/cur_tts_engine to confirm, matching the "not ready immediately" note in the backend's MainActivity
 *  initAndroidTts() javadoc. After an engine switch, the old engine's language
 *  list no longer applies and must be reloaded. */
function setAndroidTtsEngine() {
  const select = document.getElementById("ttsAndroidEngineSelect");
  const enginePkg = select ? select.value : "";
  if (!enginePkg) return;
  // Engine switch: the old language may not apply, so reset front and back end together (the backend pref clears too). A voice is bound to engine + language, so clear it and collapse the voice row.
  currentAndroidTtsLang = "";
  currentAndroidTtsVoice = "";
  hideAndroidTtsVoiceRow();
  Alpha2Api.speechSetTtsLang( { lang: "" });
  Alpha2Api.speechSetTtsEngine( { engine: enginePkg }).then(function () {
    setTimeout(function () {
      loadCurAndroidTtsEngine();
      loadAndroidTtsLanguages();
      loadTtsFollowVosk();
    }, 800);
  });
}

/** Load every Android TTS engine installed on the device, fill the <select>, then read back which one is actually
 *  selected now and mark it as the selected option. */
function loadAndroidTtsEngines() {
  Alpha2Api.speechTtsEngines().then(function (res) {
    const select = document.getElementById("ttsAndroidEngineSelect");
    if (!select || !res || !res.ok || !res.engines) return;
    select.innerHTML = "";
    res.engines.forEach(function (pkg) {
      const opt = document.createElement("option");
      opt.value = pkg;
      opt.textContent = pkg;
      select.appendChild(opt);
    });
    loadCurAndroidTtsEngine();
  });
}

function loadCurAndroidTtsEngine() {
  Alpha2Api.speechCurTtsEngine().then(function (res) {
    const select = document.getElementById("ttsAndroidEngineSelect");
    if (!select || !res || !res.ok || !res.engine) return;
    select.value = res.engine;
  });
}

// The BCP-47 tag of the Android TTS language now selected - an empty string means keep the language the engine
// already uses, with no forced switch (see the backend speech/tts android branch comment).
// speakTts() carries this value; the conversation pipeline (backend speakAndroidTts) reads the same backend pref,
// so picking here makes conversation TTS follow at once (see setAndroidTtsLang).
let currentAndroidTtsLang = "";
// The specific voice now selected (Voice.getName(), empty = that language's default voice). It is bound to engine + language,
// so an engine or language switch clears it at once (the backend pref clears too, see setAndroidTtsEngine/
// setAndroidTtsLang). speakTts() carries it; the conversation pipeline follows the backend pref automatically.
let currentAndroidTtsVoice = "";

/** Load every language the currently selected Android TTS engine knows (server side via
 *  TextToSpeech.getVoices(), see the MainActivity#listAndroidTtsLanguages()
 *  javadoc). displayName is already the display name in the ui_lang the server picked, so the front end
 *  needs no tag->name table of its own. */
function loadAndroidTtsLanguages() {
  Alpha2Api.speechTtsLanguages( { ui_lang: uiLang }).then(function (res) {
    const select = document.getElementById("ttsAndroidLangSelect");
    if (!select || !res || !res.ok || !res.languages) return;
    select.innerHTML = "";
    // The "keep the engine's current language" option sits first with an empty value - it maps to the backend branch where the lang
    // parameter is empty/null (no forced setLanguage()).
    const keepOpt = document.createElement("option");
    keepOpt.value = "";
    keepOpt.textContent = t("tts_android_lang_keep_option");
    select.appendChild(keepOpt);
    res.languages.forEach(function (lang) {
      const opt = document.createElement("option");
      opt.value = lang.tag;
      opt.textContent = lang.name;
      select.appendChild(opt);
    });
    select.value = currentAndroidTtsLang;
    // The backend pref may still hold the last choice (front-end vars are lost after a restart), so sync it.
    Alpha2Api.speechCurTtsLang().then(function (cur) {
      if (cur && cur.ok && cur.lang !== undefined && cur.lang !== currentAndroidTtsLang) {
        currentAndroidTtsLang = cur.lang || "";
        select.value = currentAndroidTtsLang;
      }
      // Load voices only after the language sync finishes (the voice list hangs off a concrete language).
      loadAndroidTtsVoices();
    });
  });
}

function setAndroidTtsLang() {
  const select = document.getElementById("ttsAndroidLangSelect");
  currentAndroidTtsLang = select ? select.value : "";
  // A language switch voids the old voice (each language has its own voices): clear front and back end, then reload the voice row.
  currentAndroidTtsVoice = "";
  // Write the choice back to the backend pref at once - the conversation pipeline TTS follows it immediately (see MainActivity.speakAndroidTts).
  Alpha2Api.speechSetTtsLang( { lang: currentAndroidTtsLang }).then(function () {
    loadAndroidTtsVoices();
  });
}

/** Collapse and clear the voice row (used when no concrete language is selected or right after an engine switch). */
function hideAndroidTtsVoiceRow() {
  const row = document.getElementById("ttsAndroidVoiceRow");
  if (row) row.style.display = "none";
  const select = document.getElementById("ttsAndroidVoiceSelect");
  if (select) select.innerHTML = "";
}

/** Load every voice of the language now selected and fill the dropdown. When no concrete language is picked (the engine keeps its
 *  current language), collapse the whole row - unknown language means no meaningful voice list. Names follow uiLang with a local/network suffix. */
function loadAndroidTtsVoices() {
  const row = document.getElementById("ttsAndroidVoiceRow");
  const select = document.getElementById("ttsAndroidVoiceSelect");
  if (!row || !select) return;
  // Auto-follow on: the voice row stays collapsed and is not loaded (so it never fights ttsFollowSyncRows over display).
  if (currentTtsFollowVosk) {
    row.style.display = "none";
    return;
  }
  if (!currentAndroidTtsLang) {
    hideAndroidTtsVoiceRow();
    return;
  }
  row.style.display = "";
  select.innerHTML = "";
  const loading = document.createElement("option");
  loading.value = "";
  loading.textContent = t("tts_android_voice_loading");
  select.appendChild(loading);
  Alpha2Api.speechTtsVoices( { lang: currentAndroidTtsLang }).then(function (res) {
    // Old WebViews lack Node.isConnected, so re-get and confirm the select is still here before filling it
    // (if the return is late during a page switch/rebuild, leave it alone).
    const live = document.getElementById("ttsAndroidVoiceSelect");
    if (!live || live !== select) return;
    select.innerHTML = "";
    const keepOpt = document.createElement("option");
    keepOpt.value = "";
    keepOpt.textContent = t("tts_android_voice_keep_option");
    select.appendChild(keepOpt);
    if (res && res.ok && res.voices) {
      // Same as the Google TTS system settings: number the voices "Voice I, II, III..." in order and list only on-device
      // voices of one locale (network voices need internet, and Google's list omits them; the entry with the "-language"
      // suffix is a fake default voice, which Google's list also lacks, so skip it). Fall back to the same language only when nothing matches.
      const list = googleStyleVoices(res.voices, currentAndroidTtsLang);
      list.forEach(function (v, idx) {
        const opt = document.createElement("option");
        opt.value = v.name;
        opt.textContent = t("tts_android_voice_name") + " " + toRoman(idx + 1);
        opt.title = v.name;
        select.appendChild(opt);
      });
    }
    select.value = currentAndroidTtsVoice;
    // The backend pref may still hold the last choice (a language switch clears it, so sync first for accuracy).
    Alpha2Api.speechCurTtsVoice().then(function (cur) {
      const live = document.getElementById("ttsAndroidVoiceSelect");
      if (!live || live !== select) return;
      if (cur && cur.ok && cur.voice !== undefined && cur.voice !== currentAndroidTtsVoice) {
        currentAndroidTtsVoice = cur.voice || "";
        select.value = currentAndroidTtsVoice;
      }
    });
  });
}

/** The same filter + sort as the Google TTS system settings: on-device voices of the same locale (network=false),
 *  dropping the fake default voice with the "-language" suffix, in the backend's name order (jar→yuc→..., matching Google's
 *  I, II, III... numbering). When the locale has none, fall back to on-device voices of the same language. */
function googleStyleVoices(voices, langTag) {
  const norm = function (x) { return String(x || "").toLowerCase(); };
  const isRealLocal = function (v) {
    return v && !v.network && !/-language$/i.test(v.name || "");
  };
  const sameLocale = function (v) { return norm(v.locale) === norm(langTag); };
  const sameLang = function (v) {
    return norm(v.locale).split("-")[0] === norm(langTag).split("-")[0];
  };
  const exact = (voices || []).filter(function (v) { return isRealLocal(v) && sameLocale(v); });
  if (exact.length) return exact;
  return (voices || []).filter(function (v) { return isRealLocal(v) && sameLang(v); });
}

/** 1→I, 2→II, 3→III... (Google's list numbers voices with Roman numerals). */
function toRoman(n) {
  n = parseInt(n, 10);
  if (!(n > 0)) return String(n);
  const table = [[10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  let out = "";
  // Google's list never exceeds about ten voices per language, so X suffices; beyond that, keep stacking X-translate.
  while (n > 0) {
    for (let i = 0; i < table.length; i++) {
      while (n >= table[i][0]) {
        out += table[i][1];
        n -= table[i][0];
      }
    }
  }
  return out;
}

function setAndroidTtsVoice() {
  const select = document.getElementById("ttsAndroidVoiceSelect");
  currentAndroidTtsVoice = select ? select.value : "";
  // Write the voice back to the backend pref at once - the conversation pipeline TTS follows this voice immediately (see TtsCenter.speakAndroidTts).
  Alpha2Api.speechSetTtsVoice( { voice: currentAndroidTtsVoice });
}

// ---------------- TTS manual button (manual/auto choice) ----------------
//
// The button sits above the engine/language/voice rows: on = manual choice (three rows visible, current state unchanged, default);
// off = auto-follow the current Vosk model (the backend picks the best match among the engine's existing voices,
// see TtsCenter.resolveFollowLocale; the three rows collapse completely).
// The backend pref stores enabled (auto-follow; persisted, default false = button on = manual),
// so the front-end button is inverted: checked = manual.
let currentTtsFollowVosk = false;
// Last seen vosk model (compared by ttsFollowVoskOnModelChange; ask the backend only on change, so nothing flickers).
let ttsFollowLastVoskModel = null;
// Latest follow state (used to redraw the status row on a language switch without re-asking the backend).
let ttsFollowLastStatus = null;

function loadTtsFollowVosk() {
  ttsFollowVoskSyncVisibility();
  return Alpha2Api.speechCurTtsFollowVosk().then(function (res) {
    if (!res || !res.ok) return res;
    currentTtsFollowVosk = !!res.enabled;
    // Button inverted: checked = manual (enabled = auto-follow).
    const toggle = document.getElementById("ttsFollowVoskToggle");
    if (toggle) toggle.checked = !currentTtsFollowVosk;
    ttsFollowRenderStatus(res);
    return res;
  });
}

function setTtsFollowVosk() {
  const toggle = document.getElementById("ttsFollowVoskToggle");
  // Button inverted: checked = manual, backend enabled = auto-follow.
  const manual = !!(toggle && toggle.checked);
  return Alpha2Api.speechSetTtsFollowVosk({ enabled: !manual }).then(function (res) {
    return loadTtsFollowVosk();
  });
}

function ttsFollowRenderStatus(res) {
  if (!res) return;
  ttsFollowLastStatus = res;
  const on = !!res.enabled;
  // Auto-follow on: collapse the engine/language/voice rows entirely (not merely locked); they reappear when you switch back to manual.
  ttsFollowSyncRows(on);
  const out = document.getElementById("ttsFollowVoskStatus");
  if (out) {
    if (on && res.effectiveLang) {
      out.textContent = t("tts_follow_vosk_following") + res.effectiveLang;
    } else if (on) {
      out.textContent = t("tts_follow_vosk_no_match");
    } else {
      out.textContent = "";
    }
  }
}

// Redraw the follow-state row on a global language switch (setUiLanguage) without re-asking the backend.
function ttsFollowApplyUiLanguage() {
  if (ttsFollowLastStatus) ttsFollowRenderStatus(ttsFollowLastStatus);
}

// Refresh only after a Vosk model change (app-vosk.js voskRenderStatus enters here;
// when follow is off, just record it and skip the backend ask). Boot-time auto-load and post-download auto-load arrive
// through the vosk_state event too, so nothing is missed.
function ttsFollowVoskOnModelChange(model) {
  const m = model || null;
  if (m === ttsFollowLastVoskModel) return;
  ttsFollowLastVoskModel = m;
  if (!currentTtsFollowVosk) return;
  loadTtsFollowVosk();
}

// On old devices (API 19, no Vosk) following is meaningless, so collapse the row. A hidden voskCard means no Vosk.
function ttsFollowVoskSyncVisibility() {
  const card = document.getElementById("voskCard");
  const row = document.getElementById("ttsFollowVoskRow");
  if (!row) return;
  row.style.display = (card && card.style.display === "none") ? "none" : "";
}

// The auto/manual switch drives row visibility: auto collapses engine/language/voice entirely; manual shows engine + language,
// and the voice row keeps its own logic (visible only with a concrete language, see loadAndroidTtsVoices).
function ttsFollowSyncRows(on) {
  const engineRow = document.getElementById("ttsAndroidEngineRow");
  const langRow = document.getElementById("ttsAndroidLangRow");
  const voiceRow = document.getElementById("ttsAndroidVoiceRow");
  if (on) {
    if (engineRow) engineRow.style.display = "none";
    if (langRow) langRow.style.display = "none";
    if (voiceRow) voiceRow.style.display = "none";
  } else {
    if (engineRow) engineRow.style.display = "";
    if (langRow) langRow.style.display = "";
    if (voiceRow) voiceRow.style.display = currentAndroidTtsLang ? "" : "none";
  }
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) { showError(uiLang === "en" ? "Voice" : "語音", t("speech_test_enter_text_alert")); return; }
  // Always Android TTS. Manual mode carries lang/voice; auto-follow mode sends neither (the backend resolves it from the current Vosk model).
  const params = { text: text, engine: "android" };
  if (!currentTtsFollowVosk) {
    // Empty string = keep the engine's current language (see the backend speech/tts android branch comment).
    if (currentAndroidTtsLang) {
      params.lang = currentAndroidTtsLang;
    }
    // Carry the voice only when one is picked (empty = the language's default voice; if the backend cannot find it, it falls back to the lang path).
    if (currentAndroidTtsVoice) {
      params.voice = currentAndroidTtsVoice;
    }
  }
  // Chat UI: when the robot "speaks", append an assistant bubble at once - the difference from "XiaoZhi" is that
  // the TTS request itself has no async event that sends the spoken text back (unlike asr_result),
  // so append the text from the moment of sending directly, with no wait for a server response.
  appendSpeechChatLine("xiaozhi-msg-assistant", text);
  // Stop the old sentence before playing a new one, otherwise two TTS runs can collide (the half-spoken one has not
  // finished while the new request starts, and the audio sounds doubled/muddy). If stopTts() fails, still continue
  // with the new playback (for example when nothing is playing, stop itself may error/no-op, and that
  // must not block the user from speaking).
  return stopTts().catch(function () {}).then(function () {
    return Alpha2Api.speechTts( params);
  });
}

function stopTts() {
  return Alpha2Api.speechStop();
}

