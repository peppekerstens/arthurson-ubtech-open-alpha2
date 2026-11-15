// Open Alpha2 — client logic (app-actions-pack.js)
// Actions pack area in the resource download card (experimental tab): a fixed
// actions.zip, direct backend download + auto unzip to the sdcard. The old
// /sdcard/actions is renamed to /sdcard/actions-backup. Backend:
// ActionsPackController + action/pack/download|status|cancel. One shared download
// channel with Vosk (see DownloadGate): only one side runs at a time. All files
// share the window/global scope (no ES modules). The load order follows the
// <script src="..."> order in index.html.

// Download polling handle.
var actionsPackDlTimer = null;
// Last status. Used to redraw the status line on a language switch, with no new backend request.
var actionsPackLastStatus = null;

// Actions pack downloading / unzipping: lock the Vosk buttons too. The backend gate is the real guard.
function actionsPackActive() {
  const st = actionsPackLastStatus && actionsPackLastStatus.state;
  return st === "downloading" || st === "unzipping";
}

// Redraw the status line on a global language switch (setUiLanguage). No new backend request.
function actionsPackApplyUiLanguage() {
  if (actionsPackLastStatus) actionsPackRenderStatus(actionsPackLastStatus);
}

function actionsPackRefreshStatus() {
  return Alpha2Api.actionPackStatus().then(function (res) {
    if (!res || !res.ok) return res;
    actionsPackRenderStatus(res);
    // A page reload during a download: follow it quietly.
    if (res.state === "downloading" || res.state === "unzipping") {
      actionsPackPollDownload();
    }
    return res;
  });
}

// Start the fixed actions.zip download (the old actions folder is renamed to backup automatically).
function actionsPackDownload() {
  const out = document.getElementById("actionsPackStatusOut");
  return Alpha2Api.actionPackDownload().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("actions_pack_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    actionsPackRenderStatus(res);
    actionsPackPollDownload();
    return res;
  });
}

function actionsPackCancel() {
  return Alpha2Api.actionPackCancel().then(function (res) {
    return actionsPackPollDownloadOnce();
  });
}

// Poll status every 1 second until done / error / cancelled.
function actionsPackPollDownload() {
  if (actionsPackDlTimer) return;
  actionsPackDlTimer = setInterval(actionsPackPollDownloadOnce, 1000);
}

function actionsPackPollDownloadOnce() {
  return Alpha2Api.actionPackStatus().then(function (res) {
    if (!res || !res.ok) return res;
    actionsPackRenderStatus(res);
    if (res.state === "done") {
      if (actionsPackDlTimer) { clearInterval(actionsPackDlTimer); actionsPackDlTimer = null; }
      const out = document.getElementById("actionsPackStatusOut");
      if (out) out.textContent = t("actions_pack_done");
      return res;
    }
    if (res.state === "error" || res.state === "cancelled" || res.state === "idle") {
      if (actionsPackDlTimer) { clearInterval(actionsPackDlTimer); actionsPackDlTimer = null; }
    }
    return res;
  });
}

// actions_pack event (pushed by the backend ActionsPackController) shares this render
// path with the poll above. Progress shows in the experimental tab download card. done / error / cancelled end in the poll (timer stop).
function actionsPackRenderStatus(res) {
  if (!res) return;
  actionsPackLastStatus = res;
  const out = document.getElementById("actionsPackStatusOut");
  const active = res.state === "downloading" || res.state === "unzipping";
  if (out) {
    if (res.state === "unzipping") {
      out.textContent = t("actions_pack_unzipping_hint");
    } else if (res.state === "downloading") {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("actions_pack_downloading_prefix") + pct;
    } else if (res.state === "done") {
      out.textContent = t("actions_pack_done");
    } else if (res.state === "error") {
      out.textContent = t("actions_pack_fail_prefix") + (res.message || "?");
    } else if (res.state === "cancelled") {
      out.textContent = t("actions_pack_cancelled");
    } else {
      out.textContent = "—";
    }
  }
  const cancelBtn = document.getElementById("actionsPackCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // Cross-lock the Vosk buttons (single channel, see syncResourceDownloadButtons + DownloadGate).
  try {
    if (typeof syncResourceDownloadButtons === "function") syncResourceDownloadButtons();
  } catch (e) {}
}
