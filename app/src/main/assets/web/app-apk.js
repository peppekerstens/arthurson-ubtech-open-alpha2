// Open Alpha2 — client logic (app-apk.js)
// Google TTS area in the resource download card (experimental tab): a fixed APK.
// The backend downloads it straight to the sdcard. Installation skips the panel
// (run adb install once per unit by hand). Backend: ApkDownloadController +
// apk/download|status|cancel. One shared download channel with Vosk / actions
// pack (see DownloadGate): only one side downloads at a time. All files share the
// window/global scope (no ES modules). The load order follows the <script src="..."> order in index.html.

// Download polling handle.
var apkDlTimer = null;
// Last status. Used to redraw the status line on a language switch, with no new backend request.
var apkLastStatus = null;

// APK downloading: lock the Vosk / actions pack buttons too. The backend gate is the real guard.
function apkActive() {
  const st = apkLastStatus && apkLastStatus.state;
  return st === "downloading";
}

// Redraw the status line on a global language switch (setUiLanguage). No new backend request.
function apkApplyUiLanguage() {
  if (apkLastStatus) apkRenderStatus(apkLastStatus);
}

function apkRefreshStatus() {
  return Alpha2Api.apkStatus().then(function (res) {
    if (!res || !res.ok) return res;
    apkRenderStatus(res);
    // A page reload during a download: follow it quietly.
    if (res.state === "downloading") {
      apkPollDownload();
    }
    return res;
  });
}

// Start the download.
function apkDownload() {
  const out = document.getElementById("apkStatusOut");
  return Alpha2Api.apkDownload().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("apk_dl_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    apkRenderStatus(res);
    apkPollDownload();
    return res;
  });
}

function apkCancel() {
  return Alpha2Api.apkCancel().then(function (res) {
    return apkPollDownloadOnce();
  });
}

// Poll status every 1 second until done / error / cancelled.
function apkPollDownload() {
  if (apkDlTimer) return;
  apkDlTimer = setInterval(apkPollDownloadOnce, 1000);
}

function apkPollDownloadOnce() {
  return Alpha2Api.apkStatus().then(function (res) {
    if (!res || !res.ok) return res;
    apkRenderStatus(res);
    if (res.state === "done" || res.state === "error"
        || res.state === "cancelled" || res.state === "idle") {
      if (apkDlTimer) { clearInterval(apkDlTimer); apkDlTimer = null; }
    }
    return res;
  });
}

// apk event (pushed by the backend ApkDownloadController) shares this render
// path with the poll above. Progress shows in the experimental tab download card. done / error / cancelled end in the poll (timer stop).
function apkRenderStatus(res) {
  if (!res) return;
  apkLastStatus = res;
  const out = document.getElementById("apkStatusOut");
  const active = res.state === "downloading";
  if (out) {
    if (res.state === "downloading") {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("actions_pack_downloading_prefix") + pct;
    } else if (res.state === "done") {
      out.textContent = t("apk_dl_done");
    } else if (res.state === "error") {
      out.textContent = t("apk_dl_fail_prefix") + (res.message || "?");
    } else if (res.state === "cancelled") {
      out.textContent = t("actions_pack_cancelled");
    } else {
      out.textContent = "—";
    }
  }
  const cancelBtn = document.getElementById("apkCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // Cross-lock the Vosk / actions pack buttons (single channel, see syncResourceDownloadButtons + DownloadGate).
  try {
    if (typeof syncResourceDownloadButtons === "function") syncResourceDownloadButtons();
  } catch (e) {}
}
