// Open Alpha2 — client logic (app-archive.js)
// Online song request card: free music from Internet Archive (archive.org). No login needed.
// Same design as the radio front-end fallback (see radioSearchFrontendFallback in
// app-radio.js): the browser calls the archive.org API over https (the robot TLS stack
// is too old for https). After it gets a direct http link, the ready-made
// audio/radio/play_url endpoint makes the robot play it. archive.org sends CORS *, so a
// direct browser call works. The download link is http, which the unit MediaPlayer
// accepts (verified: 200 audio/mpeg on both PC and unit).
// Content expectation: archive.org holds live recordings / indie music / old recordings, not commercial chart songs.
// All functions share the window/global scope (no ES modules). See index.html for load order.

let archiveItems = [];           // album list from the last archiveSearch() {identifier, title, creator}
let archiveTracks = [];          // tracks of the album being expanded {name, sizeMb, url}
let archiveOpenItem = null;      // identifier being expanded (none = album list mode)

function arcOnSearchKeydown(event) {
  if (event.key === "Enter") archiveSearch();
}

function archiveSearch() {
  const input = document.getElementById("archiveSearchInput");
  const container = document.getElementById("archiveListContainer");
  const statusEl = document.getElementById("archiveSearchStatus");
  if (!input || !container) return;
  const q = (input.value || "").trim();
  if (!q) {
    if (statusEl) statusEl.textContent = t("radio_search_empty_hint");
    return;
  }
  container.innerHTML = "";
  archiveOpenItem = null;
  archiveTracks = [];
  const loading = document.createElement("p");
  loading.className = "hint";
  loading.textContent = t("radio_search_loading");
  container.appendChild(loading);
  if (statusEl) statusEl.textContent = "";
  clearError();
  const url = "https://archive.org/advancedsearch.php?q="
      + encodeURIComponent("(" + q + ") AND mediatype:audio")
      + "&fl[]=identifier,title,creator&rows=20&output=json";
  if (typeof appendLog === "function" && typeof nowTimeStr === "function") {
    appendLog({ type: "net_connect", time: nowTimeStr(), data: { purpose: "archive-search", url: "https://archive.org" } });
  }
  fetch(url).then(function (res) {
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }).then(function (j) {
    const docs = (j && j.response && j.response.docs) || [];
    archiveItems = docs.map(function (d) {
      return {
        identifier: d.identifier || "",
        title: d.title || d.identifier || "",
        creator: d.creator || ""
      };
    }).filter(function (d) { return !!d.identifier; });
    if (statusEl) {
      statusEl.textContent = archiveItems.length === 0
        ? t("arc_search_no_result")
        : t("radio_search_found_prefix") + archiveItems.length + t("arc_search_found_suffix");
    }
    archiveRenderItems();
  }).catch(function () {
    if (statusEl) statusEl.textContent = t("arc_search_no_result");
    container.innerHTML = "";
  });
}

// Tap an album: fetch metadata, pick MP3 files (skip samples). One track plays at once. Several show a track list to choose.
function archiveOpenItemTracks(identifier, title) {
  const container = document.getElementById("archiveListContainer");
  const statusEl = document.getElementById("archiveSearchStatus");
  if (!identifier || !container) return;
  container.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "hint";
  loading.textContent = t("radio_search_loading");
  container.appendChild(loading);
  clearError();
  fetch("https://archive.org/metadata/" + encodeURIComponent(identifier)).then(function (res) {
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }).then(function (m) {
    const files = (m && m.files) || [];
    archiveTracks = files.filter(function (f) {
      const n = (f.name || "").toLowerCase();
      return n.slice(-4) === ".mp3" && n.indexOf("sample") === -1;
    }).map(function (f) {
      return {
        name: f.name,
        sizeMb: f.size ? Math.round(f.size / 1048576) : 0,
        url: "http://archive.org/download/" + identifier + "/"
            + f.name.split("/").map(encodeURIComponent).join("/")
      };
    });
    archiveOpenItem = identifier;
    if (archiveTracks.length === 0) {
      if (statusEl) statusEl.textContent = t("arc_no_mp3");
      archiveRenderItems();
      return;
    }
    if (archiveTracks.length === 1) {
      archivePlayTrack(archiveTracks[0], title);
      return;
    }
    archiveRenderTracks(title);
  }).catch(function () {
    if (statusEl) statusEl.textContent = t("arc_no_mp3");
    archiveRenderItems();
  });
}

function archiveRenderItems() {
  const container = document.getElementById("archiveListContainer");
  if (!container) return;
  container.innerHTML = "";
  if (archiveItems.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("radio_list_empty_hint");
    container.appendChild(p);
    return;
  }
  archiveItems.forEach(function (it) {
    const row = document.createElement("div");
    row.className = "radio-row";
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = "▶";
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = it.title;
    nameSpan.title = it.title;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = it.creator || "";
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.onclick = function () { archiveOpenItemTracks(it.identifier, it.title); };
    container.appendChild(row);
  });
}

function archiveRenderTracks(albumTitle) {
  const container = document.getElementById("archiveListContainer");
  const statusEl = document.getElementById("archiveSearchStatus");
  if (!container) return;
  container.innerHTML = "";
  const back = document.createElement("button");
  back.className = "secondary";
  back.textContent = "‹ " + albumTitle;
  back.onclick = function () {
    archiveOpenItem = null;
    archiveTracks = [];
    if (statusEl) statusEl.textContent = "";
    archiveRenderItems();
  };
  container.appendChild(back);
  archiveTracks.forEach(function (tr) {
    const row = document.createElement("div");
    row.className = "radio-row";
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = "▶";
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = tr.name;
    nameSpan.title = tr.name;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = tr.sizeMb ? tr.sizeMb + "MB" : "";
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.onclick = function () { archivePlayTrack(tr, albumTitle); };
    container.appendChild(row);
  });
}

function archivePlayTrack(tr, albumTitle) {
  const statusEl = document.getElementById("archiveSearchStatus");
  if (!tr || !tr.url) return;
  clearError();
  const label = tr.name || albumTitle || tr.url;
  if (statusEl) statusEl.textContent = t("radio_playing_prefix") + label + "…";
  // Follow the radio play chain (play_url -> currentRadioPlayer): share the spectrum /
  // status / stop button. Like radioPlay, sync the shared card too.
  if (typeof sharedActiveSource !== "undefined") sharedActiveSource = "radio";
  if (typeof musicCurrentName !== "undefined") { musicCurrentName = null; }
  if (typeof musicHasLoadedTrack !== "undefined") musicHasLoadedTrack = false;
  if (typeof musicRenderList === "function") musicRenderList();
  if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(label, true);
  if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();
  Alpha2Api.audioRadioPlayUrl({ url: tr.url, name: label }).then(function (res) {
    if (!res || !res.ok) {
      if (statusEl) statusEl.textContent = t("arc_play_fail_prefix") + (res && res.error ? res.error : "?");
      return;
    }
    if (typeof radioRefreshStatus === "function") radioRefreshStatus();
    if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || label);
  });
}

