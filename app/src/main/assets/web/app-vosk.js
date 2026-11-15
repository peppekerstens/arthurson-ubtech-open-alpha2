// Open Alpha2 — client logic (app-vosk.js)
// Vosk offline ASR (voice tab).
// Models sit on the sdcard (VoskController.scanModels detects them automatically), one loaded at a time; finished results reuse
// the asr_result event (see app-log.js - user bubble + semantic match + TTS all automatic). This file only
// handles the model buttons (one press loads + auto-listens), start/stop, and live partial display.
// All files share the window/global scope (no ES modules). Load order follows the
// <script src="..."> order in index.html.

// Download polling handle.
var voskDlTimer = null;
// Models from the last scan (voskStatus cannot get the list, so the active-model highlight relies on this).
var voskCachedModels = [];
// Full catalog from the last scan (including downloaded ones; the front end filters the view, so a language redraw needs no backend ask).
var voskCachedCatalog = [];
// Latest status/download (used to redraw the status rows on a language switch, no re-ask).
var voskLastStatus = null;
var voskLastDownload = null;
// The press-to-use model is waiting for ready (loading takes seconds; ready starts listening automatically).
var voskPendingModel = null;

// The backend gives a Chinese name (lang) plus an English name (langEn); show one by panel language; an old backend without langEn falls back to lang.
function voskLangName(m) {
  if (!m) return "";
  if (typeof uiLang !== "undefined" && uiLang === "en") {
    return m.langEn || m.lang || m.id || "";
  }
  return m.lang || m.langEn || m.id || "";
}

// Redraw on a global language switch (setUiLanguage): model buttons + download buttons + both status rows, no backend re-ask.
function voskApplyUiLanguage() {
  voskRenderModelBtns(voskCachedModels, voskLastStatus && voskLastStatus.model);
  voskRenderCatalogBtns();
  if (voskLastStatus) voskRenderStatus(voskLastStatus);
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

function voskRefreshModels() {
  // API 19 fuse: hide the whole card when the device is too old (the backend voskOrError blocks it anyway, but do not waste the space).
  return Alpha2Api.status().then(function (st) {
    if (st && st.ok && st.apiLevel && st.apiLevel < 21) {
      const card = document.getElementById("voskCard");
      if (card) card.style.display = "none";
      // No Vosk means nothing to follow, so collapse the TTS card's follow row too.
      try {
        if (typeof ttsFollowVoskSyncVisibility === "function") ttsFollowVoskSyncVisibility();
      } catch (e) {}
      return st;
    }
    return Alpha2Api.voskModels();
  }).then(function (res) {
    const card = document.getElementById("voskCard");
    if (card && card.style.display === "none") return res; // API<21 is already hidden, stop bothering it
    if (!res || !res.ok) return res;
    voskCachedModels = res.models || [];
    voskRenderModelBtns(voskCachedModels, null);
    // A page switch that reloads during a download: follow it quietly (opening the page no longer auto-pops a download alert).
    if (res.download && (res.download.state === "downloading" || res.download.state === "unzipping")) {
      voskRenderDownload(res.download);
      voskPollDownload();
    }
    return voskStatus();
  });
}

// Show one button per installed model: the name follows the panel language (Chinese/English), and only one model is active -
// the active one is blue (active), the rest grey; the switching one blinks and the whole row locks to stop double presses.
// A press runs voskUseModel: press loads, ready auto-starts listening.
function voskRenderModelBtns(models, activeId) {
  const box = document.getElementById("voskModelBtns");
  if (!box) return;
  box.innerHTML = "";
  (models || []).forEach(function (m) {
    const btn = document.createElement("button");
    btn.textContent = "🎙️ " + voskLangName(m);
    btn.title = m.id;
    btn.className = "vosk-model-btn" + (m.id === activeId ? " active" : "")
        + (m.id === voskPendingModel ? " switching" : "");
    btn.onclick = (function (id) {
      return function () { voskUseModel(id); };
    })(m.id);
    box.appendChild(btn);
  });
  if (!models || !models.length) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_no_model_hint");
    box.appendChild(hint);
  }
  voskSyncModelBtns();
}

// Sync the whole model-button row (enable/active/switching) at once: lock everything while a switch runs
// (inline onclick is not blocked, only disabled truly prevents a press). A download does not lock - it runs in the background
// thread and writes a different directory, so button load/listen still work (when the backend's auto-load on finish meets a live listen, it yields).
// active (blue) is only the model now listening -
// consistent with the state dot (voskStateDot): after stop, even ready shows grey, not blue.
function voskSyncModelBtns() {
  const box = document.getElementById("voskModelBtns");
  if (!box || !voskCachedModels.length) return;
  const locked = !!voskPendingModel;
  const listening = !!(voskLastStatus && (voskLastStatus.state === "listening"
      || !!voskLastStatus.listening));
  const activeId = voskPendingModel
      || (listening ? voskLastStatus.model : null);
  const btns = box.querySelectorAll("button");
  for (let i = 0; i < btns.length && i < voskCachedModels.length; i++) {
    btns[i].disabled = locked;
    btns[i].classList.toggle("active", voskCachedModels[i].id === activeId && !voskPendingModel);
    btns[i].classList.toggle("switching", voskCachedModels[i].id === voskPendingModel);
  }
}

// Experiment-tab resource-download card switch (same pattern as panelAuthCardToggle): collapsed by default, state not remembered.
// When open, refresh all three sides at once (Vosk catalog + action-pack status + APK status; a page switch during activity follows the progress).
function resDlCardToggle() {
  const enabled = document.getElementById("resDlCardEnabled");
  const body = document.getElementById("resDlCardBody");
  const hint = document.getElementById("resDlCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) {
    voskRefreshCatalog();
    if (typeof actionsPackRefreshStatus === "function") actionsPackRefreshStatus();
    if (typeof apkRefreshStatus === "function") apkRefreshStatus();
  }
}

// Collapse button for the no-dialogue (dictation only) area: press expands, collapsed by default.
function voskUnsupportedToggle() {
  const wrap = document.getElementById("voskUnsupportedWrap");
  if (!wrap) return;
  wrap.style.display = (wrap.style.display === "none" || !wrap.style.display) ? "block" : "none";
  voskSyncUnsupportedBtn();
}

function voskSyncUnsupportedBtn() {
  const btn = document.getElementById("voskUnsupportedToggleBtn");
  const wrap = document.getElementById("voskUnsupportedWrap");
  if (!btn) return;
  // The wrap starts with style="display:none": only block counts as expanded.
  const isOpen = !!(wrap && wrap.style.display === "block");
  btn.textContent = t(isOpen ? "res_dl_hide_unsupported" : "res_dl_show_unsupported");
}

// The resource download channel is busy (a Vosk download/extract in flight): lock the action-pack buttons too (only the backend gate truly blocks).
function voskDownloadActive() {
  const st = voskLastDownload && voskLastDownload.state;
  return st === "downloading" || st === "unzipping";
}

// Cross-lock both download buttons: whichever side runs locks the other (each cancel button follows its own render).
function syncResourceDownloadButtons() {
  let busy = false;
  try {
    if (typeof voskDownloadActive === "function" && voskDownloadActive()) busy = true;
  } catch (e) {}
  try {
    if (typeof actionsPackActive === "function" && actionsPackActive()) busy = true;
  } catch (e) {}
  try {
    if (typeof apkActive === "function" && apkActive()) busy = true;
  } catch (e) {}
  ["voskCatalogList", "voskCatalogSupported"].forEach(function (boxId) {
    const list = document.getElementById(boxId);
    if (!list) return;
    const btns = list.querySelectorAll("button");
    for (let i = 0; i < btns.length; i++) btns[i].disabled = busy;
  });
  const packBtn = document.getElementById("actionsPackDlBtn");
  if (packBtn) packBtn.disabled = busy;
  const apkBtn = document.getElementById("apkDlBtn");
  if (apkBtn) apkBtn.disabled = busy;
}

// Experiment-tab download card: list everything downloadable (hide what is already downloaded), press one button to download it.
function voskRefreshCatalog() {
  const list = document.getElementById("voskCatalogList");
  if (!list) return Promise.resolve();
  return Alpha2Api.voskCatalog().then(function (res) {
    if (!res || !res.ok) return res;
    voskCachedCatalog = res.catalog || [];
    voskRenderCatalogBtns();
    return Alpha2Api.voskDownloadStatus().then(function (st) {
      if (st && st.ok) voskRenderDownload(st);
      return res;
    });
  });
}

// Download-button names follow the panel language; downloaded items stay hidden; when nothing is left, show a note instead of blank space.
function voskRenderCatalogBtns() {
  const list = document.getElementById("voskCatalogList");
  if (!list) return;
  list.innerHTML = "";
  let shown = 0;
  (voskCachedCatalog || []).forEach(function (c) {
    if (c.dialogue) return; // models with dialogue render in their own area; this covers the no-dialogue part
    if (c.downloaded) {
      const chip = document.createElement("span");
      chip.className = "hint";
      chip.textContent = "✓ " + voskLangName(c) + "（" + t("vosk_dl_transcribe_only") + ")";
      chip.title = c.id;
      list.appendChild(chip);
      shown++;
      return;
    }
    list.appendChild(voskDlButton(c));
    shown++;
  });
  if (!shown) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_dl_all_done");
    list.appendChild(hint);
  }
  voskRenderCatalogSupported();
  voskSyncUnsupportedBtn();
  // Lock while a download runs (voskRenderDownload locks again too; this covers the language-switch redraw moment).
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

// Shared download-button builder (same in both areas): name follows the panel language + MB, with the id as the title.
function voskDlButton(c) {
  const btn = document.createElement("button");
  btn.className = "secondary vosk-dl-btn";
  btn.textContent = "⬇ " + voskLangName(c) + " (~" + c.sizeMb + "MB)";
  btn.title = c.id;
  btn.onclick = (function (id) {
    return function () { voskCatalogDownload(id); };
  })(c.id);
  return btn;
}

// Dialogue area: it has a matcher and can answer (ten languages: zh/en/es/fr/ja/de/it/pt/ko/ru, the last five are skeletons waiting for content). A downloaded one shows ✓ (click it on the voice page to use it;
// this area only displays, no controls here); a missing one shows the download button.
function voskRenderCatalogSupported() {
  const box = document.getElementById("voskCatalogSupported");
  if (!box) return;
  box.innerHTML = "";
  let shown = 0;
  (voskCachedCatalog || []).forEach(function (c) {
    if (!c.dialogue) return;
    if (c.downloaded) {
      const chip = document.createElement("span");
      chip.className = "hint";
      chip.textContent = "✓ " + voskLangName(c) + " (~" + c.sizeMb + "MB)";
      chip.title = c.id;
      box.appendChild(chip);
      shown++;
      return;
    }
    box.appendChild(voskDlButton(c));
    shown++;
  });
  if (!shown) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_dl_none_hint");
    box.appendChild(hint);
  }
}

// The name shown during download also follows the panel language (translated only when the catalog knows it, else fall back to the id).
function voskDownloadName(id) {
  if (!id) return "";
  for (let i = 0; i < voskCachedCatalog.length; i++) {
    if (voskCachedCatalog[i].id === id) return voskLangName(voskCachedCatalog[i]);
  }
  for (let i = 0; i < voskCachedModels.length; i++) {
    if (voskCachedModels[i].id === id) return voskLangName(voskCachedModels[i]);
  }
  return id;
}

// Start downloading one model (the directory name, see the catalog): the backend fetches the official zip, then auto-unzips and auto-loads.
function voskCatalogDownload(id) {
  const out = document.getElementById("voskCatStatusOut");
  return Alpha2Api.voskDownload({ model: id }).then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_download_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    // The backend returns ok + current state for both "already exists" and "already downloading": the first means idle
    // (the other side finished it), so refresh directly with no poll; otherwise follow the progress.
    if (res.state !== "downloading" && res.state !== "unzipping" && res.state !== "done") {
      return voskRefreshCatalog().then(function () { return voskRefreshModels(); });
    }
    voskRenderDownload(res);
    voskPollDownload();
    return res;
  });
}

function voskCancelDownload() {
  return Alpha2Api.voskDownloadCancel().then(function (res) {
    return voskPollDownloadOnce();
  });
}

// Poll download_status every 1 s until done/error/cancelled.
function voskPollDownload() {
  if (voskDlTimer) return;
  voskDlTimer = setInterval(voskPollDownloadOnce, 1000);
}

function voskPollDownloadOnce() {
  return Alpha2Api.voskDownloadStatus().then(function (res) {
    if (!res || !res.ok) return res;
    voskRenderDownload(res);
    if (res.state === "done") {
      if (voskDlTimer) { clearInterval(voskDlTimer); voskDlTimer = null; }
      const out = document.getElementById("voskCatStatusOut");
      if (out) out.textContent = t("vosk_download_done");
      // The backend already auto-loaded it: the download card drops the finished item, and the voice page gains one button + highlight.
      return voskRefreshCatalog().then(function () {
        return voskRefreshModels().then(function () { return voskStatus(); });
      });
    }
    if (res.state === "error" || res.state === "cancelled" || res.state === "idle") {
      if (voskDlTimer) { clearInterval(voskDlTimer); voskDlTimer = null; }
    }
    return res;
  });
}

// The vosk_download event (pushed by the backend VoskController) and the poll above share this renderer:
// progress shows on the experiment-tab download card. done/error/cancelled are wrapped up by the poll (stop timer + refresh).
function voskRenderDownload(res) {
  if (!res) return;
  voskLastDownload = res;
  const out = document.getElementById("voskCatStatusOut");
  const active = res.state === "downloading" || res.state === "unzipping";
  if (out && active) {
    if (res.state === "unzipping") {
      out.textContent = t("vosk_unzipping_hint") + (res.model ? " (" + voskDownloadName(res.model) + ")" : "");
    } else {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("vosk_downloading_prefix") + pct + (res.model ? " (" + voskDownloadName(res.model) + ")" : "");
    }
  } else if (out && res.state === "error") {
    out.textContent = t("vosk_download_fail_prefix") + (res.message || "?");
  } else if (out && res.state === "cancelled") {
    out.textContent = t("vosk_download_cancelled");
  }
  // During a download, lock only the two download-button areas (the backend fetches one model at a time); voice-page model buttons still work -
  // downloading never touches listening, and after extraction the refresh re-adds the new button.
  // Cross-lock the action-pack buttons (single channel, see syncResourceDownloadButtons + DownloadGate).
  ["voskCatalogList", "voskCatalogSupported"].forEach(function (boxId) {
    const list = document.getElementById(boxId);
    if (!list) return;
    const btns = list.querySelectorAll("button");
    for (let i = 0; i < btns.length; i++) btns[i].disabled = !!active;
  });
  const cancelBtn = document.getElementById("voskCatCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // Sync the voice-page model buttons' lock/unlock (voskSyncModelBtns knows to check voskLastDownload).
  voskSyncModelBtns();
  syncResourceDownloadButtons();
}

// Press-to-use: pressing the listening model again stops it (grey); a different model stops the old one first, then loads and auto-listens on ready.
// Pressing a ready (stopped) model starts listening directly without a reload.
function voskUseModel(id) {
  if (!id || voskPendingModel === id) return Promise.resolve();
  const st = voskLastStatus;
  const sameModel = !!(st && st.model === id);
  const active = !!(st && (st.state === "listening" || !!st.listening));
  if (sameModel && active) return voskStop(); // pressing the blue one again = stop, back to grey
  if (sameModel && st.state === "ready") return voskStart(); // ready: start listening directly, no reload
  if (sameModel && st.state === "loading") {
    // The backend is already loading this model (e.g. a page reload caught it): follow along, listen on ready.
    voskPendingModel = id;
    voskSyncModelBtns();
    return voskWaitReady(id, 0);
  }
  voskPendingModel = id;
  voskSyncModelBtns(); // grey them at once and blink the switching one, so the user never guesses
  const out = document.getElementById("voskStatusOut");
  const switching = !!(voskLastStatus && voskLastStatus.model && voskLastStatus.model !== id
      && (voskLastStatus.state === "listening" || voskLastStatus.state === "ready"
        || !!voskLastStatus.listening));
  if (out) out.textContent = t(switching ? "vosk_switching_hint" : "vosk_loading_hint");
  // Stop the old one first (stop is a harmless no-op when idle), then load, so only one model is ever active.
  return Alpha2Api.voskStop().then(function () {
    if (voskPendingModel !== id) return null; // the user pressed Stop during the stop step, so back off
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = "";
    return Alpha2Api.voskLoad({ model: id });
  }).then(function (res) {
    if (!res) return voskStatus(); // backed off above
    if (!res.ok) {
      voskPendingModel = null;
      if (out) out.textContent = t("vosk_load_fail_prefix") + (res.error ? res.error : "?");
      voskSyncModelBtns();
      return voskStatus();
    }
    return voskWaitReady(id, 0);
  });
}

// Loading takes seconds: poll status every half second, auto-listen on ready; give up after 30 s without ready.
function voskWaitReady(id, tries) {
  if (voskPendingModel !== id) return Promise.resolve();
  if (tries > 60) {
    voskPendingModel = null;
    return voskStatus();
  }
  return Alpha2Api.voskStatus().then(function (res) {
    if (voskPendingModel !== id) return res;
    if (!res || !res.ok) {
      return new Promise(function (resolve) {
        setTimeout(function () { resolve(voskWaitReady(id, tries + 1)); }, 500);
      });
    }
    voskRenderStatus(res);
    if (res.state === "ready") {
      voskPendingModel = null;
      voskSyncModelBtns();
      return voskStart(); // listen at once on ready - press-to-use delivered
    }
    if (res.state === "listening" || res.state === "error") {
      voskPendingModel = null;
      voskSyncModelBtns();
      return res;
    }
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(voskWaitReady(id, tries + 1)); }, 500);
    });
  });
}

function voskStart() {
  const out = document.getElementById("voskStatusOut");
  return Alpha2Api.voskStart().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_start_fail_prefix") + (res && res.error ? res.error : "?");
    }
    return voskStatus();
  });
}

function voskStop() {
  // A manual stop = no auto-listen wanted, so cancel the press-to-use wait.
  voskPendingModel = null;
  return Alpha2Api.voskStop().then(function () {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = "";
    return voskStatus();
  });
}

function voskStatus() {
  return Alpha2Api.voskStatus().then(function (res) {
    if (!res || !res.ok) return res;
    voskRenderStatus(res);
    return res;
  });
}

// The vosk_state event (pushed by the backend VoskController) and the voskStatus() HTTP poll above
// share this renderer: the status row (follows the panel language) + stop-button enable/disable + one-active model highlight.
function voskRenderStatus(res) {
  if (!res) return;
  voskLastStatus = res;
  const out = document.getElementById("voskStatusOut");
  if (out) {
    const st = (res.state || "idle").toLowerCase();
    const key = "vosk_state_" + st;
    // t() returns an unknown key verbatim: an unknown state prints as-is, so nothing floods the row.
    let stateName = t(key);
    if (stateName === key) stateName = res.state || "-";
    let modelName = "";
    if (res.model) {
      let found = null;
      for (let i = 0; i < voskCachedModels.length; i++) {
        if (voskCachedModels[i].id === res.model) { found = voskCachedModels[i]; break; }
      }
      modelName = found ? voskLangName(found) : res.model;
    }
    out.textContent = stateName
        + (modelName ? " (" + modelName + ")" : "")
        + (res.message ? " — " + res.message : "");
  }
  const listening = res.state === "listening" || !!res.listening;
  const dot = document.getElementById("voskStateDot");
  if (dot) {
    dot.classList.toggle("vosk-state-dot-on", listening);
    dot.classList.toggle("vosk-state-dot-off", !listening);
  }
  // Model-button highlight goes through voskSyncModelBtns in one place: only the listening model is blue (press stops it), the rest grey.
  voskSyncModelBtns();
  // When TTS follows Vosk, a model change refreshes the follow display (when off, just record it and skip the backend ask).
  try {
    if (typeof ttsFollowVoskOnModelChange === "function") ttsFollowVoskOnModelChange(res.model || null);
  } catch (e) {}
}



