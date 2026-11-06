// Open Alpha2 — client logic (app-chest.js)
// This file holds the front-end logic for the chest board firmware upgrade (Chest 17/18).
// It matches the chest board firmware upgrade card in index.html.
// All files share the window/global scope (no ES modules). The load order follows the <script src> order in index.html.

function chestToggle() {
  const enabled = document.getElementById("chestCardEnabled");
  const body = document.getElementById("chestCardBody");
  const hint = document.getElementById("chestCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  // Opening the card shows the version and the device firmware file state, so you can confirm an upgrade is possible at a glance.
  if (on) { chestCheck(); chestFileInfo(); }
}

// Firmware uploaded during this page session. Shape: {name, size}. A reload clears it.
// "Start upgrade" gate: without it, a user click on upgrade is blocked. The old file
// on the unit is never reused (old bug: a stale file on the unit upgraded without a
// file pick, burning the wrong image).
var chestFreshUpload = null;
// Last chestUpgradeStatus response (chestFileInfo saves it to compare the size before an upgrade).
var chestLastStatus = null;

function chestUpload() {
  const input = document.getElementById("chestBinFile");
  const status = document.getElementById("chestUploadStatus");
  // Picking a new file voids the old proof. It is re-issued only after this upload succeeds.
  chestFreshUpload = null;
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = uiLang === "en" ? "Select a .bin file first" : "請先選擇一個 .bin 文件";
    return;
  }
  const file = input.files[0];
  // Pre-upload check: accept only app small images of 1..114688 bytes, 4-byte aligned
  // (same rule as ChestUpgrade.isAcceptedFirmwareSize). The backend re-checks at upgrade
  // start, so a bad file fails without a wasted upload.
  if (!chestSizeOk(file.size, null)) {
    if (status) status.textContent = t("chest_upload_bad_size", { n: file.size });
    return;
  }
  if (status) status.textContent = uiLang === "en" ? "Uploading " + file.name + " (" + file.size + " bytes)…" : "上傳中 " + file.name + " (" + file.size + " 字節)…";
  const reader = new FileReader();
  reader.onload = function(e) {
    const bytes = e.target.result;
    // Experimental tab panel token: /upload/chest checks it when enabled (Bearer header
    // first, see PanelAuth). Read it from localStorage like the api() helpers, not
    // through Alpha2Api (the upload uses raw fetch).
    let url = "/upload/chest?name=" + encodeURIComponent(file.name);
    let upHeaders = {};
    try {
      if (typeof panelAuthHeaders === "function") upHeaders = panelAuthHeaders();
    } catch (ign) {}
    fetch(url, {
      method: "POST",
      body: bytes,
      headers: Object.assign({ "Content-Type": "application/octet-stream" }, upHeaders)
    }).then(function(res) { return res.json(); }).then(function(json) {
      if (json.ok) {
        // After a successful upload, issue the session proof (name + size, compared with
        // the unit file before an upgrade), then check the device file. The result goes
        // into the status line.
        chestFreshUpload = { name: file.name, size: file.size };
        chestFileInfo();
      } else {
        if (status) status.textContent = (uiLang === "en" ? "❌ Upload failed: " : "❌ 上傳失敗：") + (json.error || JSON.stringify(json));
      }
    }).catch(function(err) {
      if (status) status.textContent = (uiLang === "en" ? "❌ Upload error: " : "❌ 上傳錯誤：") + err.message;
    });
  };
  reader.onerror = function() { if (status) status.textContent = uiLang === "en" ? "Failed to read the file" : "讀取文件失敗"; };
  reader.readAsArrayBuffer(file);
}

function chestCheck() {
  const ver = document.getElementById("chestVersionText");
  if (ver) ver.textContent = uiLang === "en" ? "Querying…" : "查詢中…";
  Alpha2Api.chestVersion().then(function(json) {
    if (ver) ver.textContent = json.version || (json.ok ? "" : "not found");
  }).catch(function(err) {
    if (ver) ver.textContent = (uiLang === "en" ? "Error: " : "錯誤: ") + err.message;
  });
}

// Firmware size gate (same rule as ChestUpgrade.isAcceptedFirmwareSize): accept only
// app small images, 1..small-image max, 4-byte aligned. A 256KB full image contains the
// bootloader, which an app update cannot use, so reject it. Limits come from the status
// line (an old backend without smallMaxBytes falls back to 114688).
function chestSizeOk(n, s) {
  const smallMax = (s && Number(s.smallMaxBytes)) || 114688;
  return n > 0 && n <= smallMax && n % 4 === 0;
}

// Query the firmware file on the device (exists + size valid). The result goes into the
// status line. true means an upgrade may start. Even a valid file on the unit counts as
// stale without this session upload proof (show stale, block).
function chestFileInfo() {
  const status = document.getElementById("chestUploadStatus");
  return Alpha2Api.chestUpgradeStatus().then(function(s) {
    chestLastStatus = s;
    if (!s || s.ok === false) return false;
    if (!s.fileExists) {
      if (status) status.textContent = t("chest_file_missing");
      return false;
    }
    if (!chestSizeOk(Number(s.fileSizeBytes), s)) {
      if (status) status.textContent = t("chest_file_bad", { n: s.fileSizeBytes });
      return false;
    }
    if (!chestFreshUpload) {
      if (status) status.textContent = t("chest_file_stale", { n: s.fileSizeBytes, p: s.filePages });
      return false;
    }
    if (status) status.textContent = t("chest_file_ok", { n: s.fileSizeBytes, p: s.filePages });
    return true;
  }).catch(function() { return false; });
}

function chestUpgrade() {
  const status = document.getElementById("chestUploadStatus");
  const wrap = document.getElementById("chestProgressWrap");
  const bar = document.getElementById("chestProgressBar");
  const txt = document.getElementById("chestProgressText");
  // Gate 1: stop when this session has not uploaded. Never reuse an old unit file. The reason goes into the status line.
  if (!chestFreshUpload) {
    if (status) status.textContent = t("chest_need_fresh_upload");
    return;
  }
  // Gate 2: check the device file. Stop when it fails (the reason already shows in the
  // status line). Also compare the size (another tab may have overwritten the unit
  // file). After both pass, ask for confirmation (brick warning), then start the burn.
  chestFileInfo().then(function(ok) {
    if (!ok) return;
    if (chestLastStatus && Number(chestLastStatus.fileSizeBytes) !== Number(chestFreshUpload.size)) {
      if (status) status.textContent = t("chest_file_mismatch", { n: chestLastStatus.fileSizeBytes, m: chestFreshUpload.size });
      return;
    }
    if (!confirm(t("chest_upgrade_confirm"))) return;
    if (status) status.textContent = uiLang === "en" ? "Starting upgrade…" : "開始升級…";
    if (wrap) wrap.style.display = "block";
    if (bar) bar.style.width = "0%";
    if (txt) txt.textContent = "0%";
    startChestUpgradePoll();
  });
}

function startChestUpgradePoll() {
  const status = document.getElementById("chestUploadStatus");
  const bar = document.getElementById("chestProgressBar");
  const txt = document.getElementById("chestProgressText");
  Alpha2Api.chestUpgrade().then(function(json) {
    if (!json.ok) { if (status) status.textContent = "❌ " + (json.error || (uiLang === "en" ? "Start failed" : "啟動失敗")); return; }
    if (status) status.textContent = uiLang === "en" ? "Upgrading… Do not power off (takes 1-2 minutes)" : "升級中…請勿斷電（約需 1–2 分鐘）";
    const timer = setInterval(function() {
      Alpha2Api.chestUpgradeStatus().then(function(s) {
        const p = s.progress || 0;
        if (bar) bar.style.width = p + "%";
        if (txt) txt.textContent = p + "% (" + (s.currentPage||0) + "/" + (s.totalPages||0) + ") " + (s.status||"");
        if (!s.inProgress) {
          clearInterval(timer);
          if (s.status && s.status.indexOf("success") !== -1) {
            if (status) status.textContent = t("chest_upgrade_done_power");
          } else if (p === 100) {
            if (status) status.textContent = uiLang === "en" ? "✅ Done" : "✅ 完成";
          } else {
            if (status) status.textContent = "❌ " + (s.status || (uiLang === "en" ? "Failed" : "失敗"));
          }
          chestCheck();
        }
      }).catch(function(){});
    }, 500);
  }).catch(function(err){ if (status) status.textContent = "❌ " + err.message; });
}

// Auto-refresh the progress (event push when the upgrade runs in the background)
if (typeof EventBus !== "undefined" && EventBus.subscribe) {
  // Old EventBus support: subscribe when available, otherwise fall back to polling
  try {
    EventBus.subscribe(function(evt){
      if (evt && evt.type === "chest_upgrade_progress") {
        try {
          const d = JSON.parse(evt.data);
          const bar = document.getElementById("chestProgressBar");
          const txt = document.getElementById("chestProgressText");
          if (bar && d.progress!=null) bar.style.width = d.progress+"%";
          if (txt) txt.textContent = (d.progress||0)+"%";
        } catch(e){}
      }
    });
  } catch(e){}
}

