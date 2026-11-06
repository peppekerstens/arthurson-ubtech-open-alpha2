// Open Alpha2 — client logic (app-status.js)
// Content: tab switching, device info
// (battery / WiFi / Bluetooth / direct UUID display / chest firmware).
// All files share the window/global scope (no ES modules). The load order follows
// the <script src="..."> order in index.html. See the comment block at its head.

// ---------------- Tabs ----------------

function switchTab(tabId) {
  document.querySelectorAll(".tab-page").forEach(function (el) { el.classList.remove("active"); });
  document.querySelectorAll(".tab-btn").forEach(function (el) { el.classList.remove("active"); });
  document.getElementById(tabId).classList.add("active");
  document.querySelector(".tab-btn[data-tab=\"" + tabId + "\"]").classList.add("active");
}

// ---------------- Device info: battery / WiFi / Bluetooth ----------------
// For the direct UUID display see requestUuid() in app-accel.js. For the chest firmware see refreshChestFw() below.

function refreshDeviceInfo() {
  return Alpha2Api.batteryStatus().then(function (battery) {
    document.getElementById("batteryOut").textContent = battery.ok
      ? (battery.level + "/" + battery.scale + " " + (battery.charging ? (uiLang === "en" ? "⚡Charging" : "⚡充電中") : "") + " (" + battery.status + ")")
      : (uiLang === "en" ? "Read failed" : "讀取失敗");

    return Alpha2Api.wifiStatus();
  }).then(function (wifi) {
    document.getElementById("wifiOut").textContent = wifi.ok
      ? (wifi.enabled ? ((wifi.ssid || (uiLang === "en" ? "(connected)" : "(已連接)")) + " — " + wifi.ip) : (uiLang === "en" ? "Off" : "已關閉"))
      : (uiLang === "en" ? "Read failed" : "讀取失敗");

    return Alpha2Api.btStatus();
  }).then(function (bt) {
    document.getElementById("btOut").textContent = bt.ok
      ? (bt.available ? ((bt.name || (uiLang === "en" ? "(unnamed)" : "(未命名)")) + " — " + (bt.enabled ? (uiLang === "en" ? "On" : "已開啟") : (uiLang === "en" ? "Off" : "已關閉"))) : (uiLang === "en" ? "Not supported" : "不支援"))
      : (uiLang === "en" ? "Read failed" : "讀取失敗");
  });
}

// ---------------- Direct chest firmware display ----------------
// Reads the same chest/version endpoint as chestCheck() on the ADVANCED card. This writes its own field (chestFwOut).
// Query once automatically on page entry. When the chest MCU does not answer, show not found (same as the ADVANCED card, not a bug).
function refreshChestFw() {
  const out = document.getElementById("chestFwOut");
  if (out) out.textContent = t("uuid_querying_hint");
  return Alpha2Api.chestVersion().then(function (json) {
    if (out) out.textContent = (json && json.version) || "not found";
  }).catch(function (err) {
    if (out) out.textContent = (uiLang === "en" ? "Error: " : "錯誤: ") + err.message;
  });
}


