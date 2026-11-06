// Open Alpha2 — client logic (app-radio.js)
// Internet radio tab: connects to the existing "audio/radio/*" endpoints in
// MainActivity.java (they search public stations worldwide through the Radio Browser
// API radio-browser.info. See the searchRadioStations()/resolveRadioStation() javadoc).
// The real streaming (MediaPlayer setDataSource url_resolved, STREAM_MUSIC) happens on
// the server side. This file is pure UI - search, list, play/stop, status display.
// All functions share the window/global scope (no ES modules). See index.html for load order.

let radioStations = [];          // list from the last radioSearch() {name, country, stationuuid, url, url_resolved}
let radioCurrentName = null;     // name of the station now playing (from status or play)
let radioCurrentId = null;
let radioFavorites = [];         // favorites list (persisted on the unit, through audio/radio/favorites/*). Item shape matches radioStations.

function radioInit() {
  radioRefreshStatus();
  radioLoadFavorites();
  // The shared spectrum / volume / EQ / random controls already live in the shared card, so no extra init is needed. radioRefreshStatus syncs the status into sharedNowPlaying.
}

// ── Favorites ─────────────────────────────────────────────
// The backend is the source of truth (all browsers share one on-unit list). Do not
// mirror it to localStorage (reaching the robot means reaching the backend). Offline, the panel cannot open at all.
function radioLoadFavorites() {
  if (!Alpha2Api.audioRadioFavoritesList) return;
  Alpha2Api.audioRadioFavoritesList().then(function (res) {
    if (!res || !res.ok) return;
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

function radioIsFavorite(st) {
  if (!st) return false;
  const nm = (st.name || "").trim().toLowerCase();
  const uuid = (st.stationuuid || st.id || "").trim();
  const uuidTemp = uuid.indexOf("frontend-") === 0;
  for (let i = 0; i < radioFavorites.length; i++) {
    const f = radioFavorites[i];
    const fn = (f.name || "").trim().toLowerCase();
    const fu = (f.stationuuid || "").trim();
    const fuTemp = fu.indexOf("frontend-") === 0;
    if (uuid && fu && !uuidTemp && !fuTemp && uuid === fu) return true;
    if (nm && nm === fn) return true;
  }
  return false;
}

function radioToggleFavorite(st, ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  if (!st || !st.name) return;
  if (radioIsFavorite(st)) radioRemoveFavorite(st);
  else radioAddFavorite(st);
}

function radioAddFavorite(st) {
  const params = { name: st.name };
  if (st.country) params.country = st.country;
  if (st.stationuuid) params.stationuuid = st.stationuuid;
  if (st.url) params.url = st.url;
  if (st.url_resolved) params.url_resolved = st.url_resolved;
  Alpha2Api.audioRadioFavoritesAdd(params).then(function (res) {
    if (!res || !res.ok) return;
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

function radioRemoveFavorite(st) {
  const params = {};
  // Pass the uuid when it is stable enough. For a frontend- temp uuid the backend falls back to name matching, so passing it is harmless.
  if (st.stationuuid) params.stationuuid = st.stationuuid;
  if (st.name) params.name = st.name;
  Alpha2Api.audioRadioFavoritesRemove(params).then(function (res) {
    if (!res || !res.ok) {
      // The backend reports not found (case / temp uuid mismatch): reload once to realign.
      radioLoadFavorites();
      return;
    }
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

// Favorite playback: with url_resolved, skip the search and use play_url (an http
// station still plays even when the robot has no internet to search). Without an
// address, fall back to search-by-name.
function radioPlayFavorite(fav) {
  if (!fav) return;
  const url = fav.url_resolved || fav.url;
  if (url) {
    clearError();
    if (typeof sharedActiveSource !== "undefined") sharedActiveSource = "radio";
    if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(fav.name || url, true);
    if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();
    Alpha2Api.audioRadioPlayUrl({ url: url, name: fav.name || url }).then(function (res) {
      if (!res || !res.ok) {
        radioPlay(fav.name);
        return;
      }
      radioCurrentName = res.playing || fav.name;
      radioRefreshStatus();
    }).catch(function () { radioPlay(fav.name); });
    return;
  }
  radioPlay(fav.name);
}

function radioRenderFavorites() {
  const container = document.getElementById("radioFavContainer");
  const countEl = document.getElementById("radioFavCount");
  if (!container) return;
  container.innerHTML = "";
  if (countEl) {
    countEl.textContent = radioFavorites.length === 0
      ? ""
      : t("radio_search_found_prefix") + radioFavorites.length + t("radio_fav_count_suffix");
  }
  if (radioFavorites.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("radio_fav_empty_hint");
    container.appendChild(p);
    return;
  }
  radioFavorites.forEach(function (fav) {
    const row = document.createElement("div");
    row.className = "radio-row" + (fav.name === radioCurrentName ? " active" : "");
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = fav.name === radioCurrentName ? "🔊" : "▶";
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = fav.name;
    nameSpan.title = fav.name;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = fav.country || "";
    const rmBtn = document.createElement("button");
    rmBtn.className = "radio-fav-btn radio-fav-remove";
    rmBtn.textContent = "✕";
    rmBtn.title = t("radio_fav_remove_btn");
    rmBtn.onclick = function (ev) { radioRemoveFavorite(fav); if (ev && ev.stopPropagation) ev.stopPropagation(); };
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.appendChild(rmBtn);
    row.onclick = function () { radioPlayFavorite(fav); };
    container.appendChild(row);
  });
}

function radioSearch() {
  const input = document.getElementById("radioSearchInput");
  const container = document.getElementById("radioListContainer");
  const statusEl = document.getElementById("radioSearchStatus");
  if (!input || !container) return;
  const q = (input.value || "").trim();
  if (!q) {
    if (statusEl) statusEl.textContent = t("radio_search_empty_hint");
    return;
  }
  container.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "hint";
  loading.textContent = t("radio_search_loading");
  container.appendChild(loading);
  if (statusEl) statusEl.textContent = "";
  clearError();
  Alpha2Api.audioRadioSearch( { query: q }).then(function (res) {
    if (res.ok) {
      radioStations = res.stations || [];
      if (statusEl) {
        statusEl.textContent = radioStations.length === 0
          ? t("radio_search_no_result")
          : t("radio_search_found_prefix") + radioStations.length + t("radio_search_found_suffix");
      }
      radioRenderList();
      return;
    }
    // Backend search failed (usually the robot has no internet / DNS cannot resolve
    // de1.api.radio-browser.info, see the note at MainActivity.java:4385). Let the
    // front-end fetch radio-browser.info directly through the browser network, not the robot.
    radioSearchFrontendFallback(q);
  }).catch(function () {
    radioSearchFrontendFallback(q);
  });
}

// Direct front-end fallback - ask radio-browser.info through the browser network, bypassing the robot DNS / no-internet problem
function radioSearchFrontendFallback(q) {
  const container = document.getElementById("radioListContainer");
  const statusEl = document.getElementById("radioSearchStatus");
  // Try the mirrors in order: de1 -> all -> de2
  const mirrors = [
    "https://de1.api.radio-browser.info",
    "https://all.api.radio-browser.info",
    "https://de2.api.radio-browser.info"
  ];
  let mirrorIdx = 0;

  function tryNextMirror() {
    if (mirrorIdx >= mirrors.length) {
      if (statusEl) statusEl.textContent = t("radio_search_no_result") + (uiLang === "en" ? " (The robot has no internet. The browser also cannot connect to radio-browser.info.)" : "（機械人無外網，前端亦無法連接 radio-browser.info）");
      if (container) {
        container.innerHTML = "";
        const p = document.createElement("p");
        p.className = "hint";
        p.textContent = uiLang === "en" ? "⚠️ The robot has no internet now (DNS failed). Check that the WiFi has internet. A direct browser connection also failed." : "⚠️ 機械人目前無法上網（DNS 失敗），請檢查 WiFi 是否有互聯網。已嘗試瀏覽器直連亦失敗。";
        container.appendChild(p);
      }
      return;
    }
    const base = mirrors[mirrorIdx++];
    const url = base + "/json/stations/search?name=" + encodeURIComponent(q) + "&order=random&reverse=true&hidebroken=true&limit=50";
    // Outbound trail (same shape as the backend NetLog): the event log shows which mirror the browser called directly.
    if (typeof appendLog === "function" && typeof nowTimeStr === "function") {
      appendLog({ type: "net_connect", time: nowTimeStr(), data: { purpose: "radio-search", url: base } });
    }
    fetch(url).then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    }).then(function (arr) {
      // Filter out hls, prefer non-https (same rule as the backend)
      const filtered = (arr || []).filter(function (s) { return s.hls !== 1; });
      const list = filtered.length > 0 ? filtered : (arr || []);
      radioStations = list.map(function (s) {
        return {
          name: s.name || "",
          country: s.country || "",
          url_resolved: s.url_resolved || s.url || "",
          url: s.url || "",
          stationuuid: s.stationuuid || "",
          hls: s.hls || 0
        };
      });
      if (statusEl) {
        statusEl.textContent = radioStations.length === 0
          ? t("radio_search_no_result")
          : t("radio_search_found_prefix") + radioStations.length + t("radio_search_found_suffix") + (uiLang === "en" ? " (direct browser connection)" : "（經瀏覽器直連）");
      }
      radioRenderList();
    }).catch(function (err) {
      // Try the next mirror
      tryNextMirror();
    });
  }
  tryNextMirror();
}

function radioQuickSearch(term) {
  const input = document.getElementById("radioSearchInput");
  if (input) input.value = term;
  radioSearch();
}

function radioRenderList() {
  const container = document.getElementById("radioListContainer");
  if (!container) return;
  container.innerHTML = "";
  if (radioStations.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("radio_list_empty_hint");
    container.appendChild(p);
    return;
  }
  radioStations.forEach(function (st) {
    const row = document.createElement("div");
    row.className = "radio-row" + (st.name === radioCurrentName ? " active" : "");
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = st.name;
    nameSpan.title = st.name;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = st.country || "";
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = st.name === radioCurrentName ? "🔊" : "▶";
    const favBtn = document.createElement("button");
    const isFav = radioIsFavorite(st);
    favBtn.className = "radio-fav-btn" + (isFav ? " active" : "");
    favBtn.textContent = isFav ? "★" : "☆";
    favBtn.title = isFav ? t("radio_fav_remove_btn") : t("radio_fav_add_btn");
    favBtn.onclick = function (ev) { radioToggleFavorite(st, ev); };
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.appendChild(favBtn);
    row.onclick = function () { radioPlay(st.name); };
    container.appendChild(row);
  });
}

function radioPlay(name) {
  const targetName = name || (document.getElementById("radioSearchInput") || {}).value;
  if (!targetName || !targetName.trim()) return;
  const cleanName = targetName.trim();
  clearError();
  const statusEl = document.getElementById("radioSearchStatus");
  if (statusEl) statusEl.textContent = t("radio_playing_prefix") + cleanName + "…";
  if (typeof sharedActiveSource !== "undefined") sharedActiveSource = "radio";
  if (typeof musicCurrentName !== "undefined") { musicCurrentName = null; }
  if (typeof musicHasLoadedTrack !== "undefined") musicHasLoadedTrack = false;
  if (typeof musicRenderList === "function") musicRenderList();
  if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(cleanName, true);
  if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();

  let frontendStation = null;
  for (let i = 0; i < radioStations.length; i++) {
    if (radioStations[i].name === cleanName && radioStations[i].url_resolved) {
      frontendStation = radioStations[i];
      break;
    }
  }
  if (frontendStation && frontendStation.url_resolved) {
    Alpha2Api.audioRadioPlayUrl( { url: frontendStation.url_resolved, name: frontendStation.name }).then(function (res) {
      if (res.ok) {
        radioCurrentName = res.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
        return;
      }
      Alpha2Api.audioRadioPlay( { name: cleanName }).then(function (res2) {
        if (!res2.ok) return;
        radioCurrentName = res2.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res2.playing || cleanName);
      });
    });
    return;
  }

  Alpha2Api.audioRadioPlay( { name: cleanName }).then(function (res) {
    if (!res.ok) return;
    radioCurrentName = res.playing || cleanName;
    radioRefreshStatus();
    if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
  });
}

function radioStop() {
  clearError();
  if (typeof sharedActiveSource !== "undefined" && sharedActiveSource === "radio") sharedActiveSource = null;
  Alpha2Api.audioRadioStop().then(function (res) {
    if (!res.ok) return;
    radioCurrentName = null;
    radioCurrentId = null;
    radioRefreshStatus();
    radioRenderList();
    if (typeof radioRenderFavorites === "function") radioRenderFavorites();
    if (typeof musicRenderList === "function") musicRenderList();
    const statusEl = document.getElementById("radioSearchStatus");
    if (statusEl) statusEl.textContent = t("radio_stopped");
    if (typeof updateSharedNowPlaying === "function" && !musicCurrentName) {
      updateSharedNowPlaying(t("music_now_playing_none"), false);
    }
    // When local music is not playing either, stop the spectrum
    Alpha2Api.audioLocalMusicStatus().then(function (r) {
      if (!r.ok || !r.hasTrack) {
        if (typeof musicStopSpectrumLoop === "function") musicStopSpectrumLoop();
      }
    });
  });
}

function radioRefreshStatus() {
  const nowPlaying = document.getElementById("radioNowPlaying");
  const sharedNow = document.getElementById("sharedNowPlaying");
  const playPauseBtn = document.getElementById("musicPlayPauseBtn");
  if (!nowPlaying) return;
  return Alpha2Api.audioRadioStatus().then(function (res) {
    if (!res.ok) return;
    if (!res.playing) {
      radioCurrentName = null;
      radioCurrentId = null;
      nowPlaying.textContent = t("radio_now_playing_none");
      nowPlaying.classList.remove("radio-playing");
      if (sharedNow && !musicCurrentName) {
        if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(t("music_now_playing_none"), false);
      }
      if (playPauseBtn && !musicHasLoadedTrack) playPauseBtn.textContent = "▶";
    } else {
      radioCurrentName = res.name || radioCurrentName;
      radioCurrentId = res.id || null;
      nowPlaying.textContent = "🔊 " + (res.name || radioCurrentName || "");
      nowPlaying.classList.add("radio-playing");
      if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(res.name || radioCurrentName || "", true);
      if (playPauseBtn) playPauseBtn.textContent = "⏸";
      if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();
    }
    radioRenderList();
    if (typeof radioRenderFavorites === "function") radioRenderFavorites();
  });
}

function radioCurrentIndex() {
  if (!radioCurrentName) return -1;
  for (let i = 0; i < radioStations.length; i++) {
    if (radioStations[i].name === radioCurrentName) return i;
  }
  return -1;
}
function radioPlayPrev() {
  if (radioStations.length === 0) return;
  const idx = radioCurrentIndex();
  const target = idx <= 0 ? radioStations.length - 1 : idx - 1;
  radioPlay(radioStations[target].name);
}
function radioPlayNext() {
  if (radioStations.length === 0) return;
  const idx = radioCurrentIndex();
  const target = idx < 0 || idx >= radioStations.length - 1 ? 0 : idx + 1;
  radioPlay(radioStations[target].name);
}
function radioPlayRandom() {
  if (radioStations.length === 0) return;
  let idx = Math.floor(Math.random() * radioStations.length);
  if (radioStations.length > 1) {
    while (radioStations[idx].name === radioCurrentName) {
      idx = Math.floor(Math.random() * radioStations.length);
    }
  }
  radioPlay(radioStations[idx].name);
}

function radioOnSearchKeydown(e) {
  if (e.key === "Enter") radioSearch();
}

