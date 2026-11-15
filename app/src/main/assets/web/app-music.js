// Open Alpha2 — client logic (app-music.js)
// Local Music tab: hooks into the existing "audio/local_music/*" endpoint set in
// MainActivity.java (built for XiaoZhi voice/AI tool calls; now it gets a browser UI again). The real playback
// (STREAM_MUSIC MediaPlayer) and the random moves all run server side. This file
// is purely UI + a periodic status poll that updates the progress bar. No audio bytes ever pass through the browser (completely
// different from the live streaming in app-mic.js).
// All functions share the window/global scope (no ES modules). See index.html for the load
// order - the <script src="..."> arrangement puts this after app-core.js (the api()/t() helpers).

// ---------------- state ----------------

let musicTracks = [];            // the list fetched by the last musicRefreshList()
let musicCurrentName = null;     // file name of the track currently selected/playing
let musicStatusPollTimer = null;
let musicSeekDragging = false;   // true while the user drags the progress bar; do not let the poll overwrite the position
let musicPlayAllMode = false;    // "▶ Play all" mode - after one track ends, auto-continue to the next (see
                                 // the hasTrack=false branch of musicPollStatusLoop())
let musicRandomMode = false;     // "🔀 Shuffle" mode - after one track ends, auto-pick another at random.
                                 // Mutually exclusive with "▶ Play all" (pressing the other side switches the mode)
let musicModeStopSeq = null;     // the server stopSeq snapshot when the mode started (null = adopt it on the
                                 // next poll). Later, if a poll sees a different value, someone outside explicitly
                                 // pressed stop (pad 94 / stop API / MCP), so turn the mode off and
                                 // do not queue the next track. A natural finish does not move the generation counter, so the queue keeps going.
let musicLastPlayedName = null;  // the last played track name - stop clears musicCurrentName,
                                 // but pressing "▶" afterwards should replay that track, not
                                 // do nothing
let musicHasLoadedTrack = false; // does the server-side currentMusicPlayer still hold a loaded track? -
                                 // true even while paused; only stop-all/track-finished sets it false.
                                 // musicTogglePlayPause() uses it to pick "resume" vs
                                 // "play from the start"
let musicSpectrumTimer = null;   // spectrum poll timer (setTimeout chain)
let musicSpectrumAnimTimer = null; // smooth animation timer (~33ms redraw)
let musicSpectrumTargets = [];   // target values last fetched from the server
let musicSpectrumSmooth = [];    // smoothed values actually drawn
let sharedActiveSource = null;   // "local" or "radio" - the last playback source, used to route the shared previous/next/shuffle actions

// ---------------- disco LED ----------------
// 🪩 Rhythm LED: while music plays, the eyes act as left/right beat machines. Runs on the backend:
// Visualizer callback beat detection + DirectLedController pushing the LEDs directly, no HTTP.
// This switch only flips the backend toggle (persisted on the backend, like the random-move switch); turning it off kills head + eye LEDs.
let musicDiscoEnabled = false;

// ---------------- audio spectrum ----------------
// Server-side Visualizer FFT -> audio/local_music/spectrum gives each
// band a 0-255 value. The poll updates target values every 50ms; a separate ~33ms animation timer
// interpolates "fast up, slow down" (instant attack, exponential release) so the bars move smoothly instead of jumping.

function musicStartSpectrumLoop() {
  if (!musicSpectrumTimer) {
    musicSpectrumLoop();
  }
  if (!musicSpectrumAnimTimer) {
    musicSpectrumAnimTimer = setInterval(musicRenderSpectrumFrame, 33);
  }
}

function musicStopSpectrumLoop() {
  if (musicSpectrumTimer) {
    clearTimeout(musicSpectrumTimer);
    musicSpectrumTimer = null;
  }
  if (musicSpectrumAnimTimer) {
    clearInterval(musicSpectrumAnimTimer);
    musicSpectrumAnimTimer = null;
  }
  musicSpectrumTargets = [];
  musicSpectrumSmooth = [];
  musicDrawSpectrum(null); // finished: redraw flat
}

function musicSpectrumLoop() {
  Alpha2Api.audioLocalMusicSpectrum().then(function (res) {
    if (!res.ok) return;
    musicSpectrumTargets = res.bands || [];
    musicSpectrumTimer = setTimeout(musicSpectrumLoop, 50);
  }).catch(function () {
    musicSpectrumTimer = setTimeout(musicSpectrumLoop, 300); // offline: retry slower
  });
}

/** Animation frame: each band chases its target "up instantly, down slowly", then draws. */
function musicRenderSpectrumFrame() {
  const n = musicSpectrumTargets.length || 24;
  if (musicSpectrumSmooth.length !== n) {
    musicSpectrumSmooth = new Array(n).fill(0);
  }
  for (let i = 0; i < n; i++) {
    const target = musicSpectrumTargets[i] || 0;
    const prev = musicSpectrumSmooth[i];
    // attack: when the target is above the current value, catch up at once (no lag). release: decay 18% per frame,
    // so the fall is smooth and natural, not bouncing.
    musicSpectrumSmooth[i] = target >= prev ? target : Math.max(target, prev * 0.82);
  }
  musicDrawSpectrum(musicSpectrumSmooth);
}

/** Combined draw frame: spectrum bars as the base (log-frequency x-axis, shared with the curve) + the EQ curve on top + handles.
 *  bands=null makes the bars flat (stopped state); the curve stays (the EQ state does not follow play/stop).
 *  Shared by the 33ms animation loop, point dragging, and preset switching. */
function musicDrawSpectrum(bands) {
  const canvas = document.getElementById("musicEqSpectrum");
  if (!canvas || !canvas.getContext) return;
  // If the EQ box has not appeared (unsupported/not loaded), do not draw - the old standalone spectrum canvas is retired; this is the only view.
  const box = document.getElementById("musicEqBands");
  if (!box || !box.classList.contains("on")) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  // The backing store follows the CSS width (x2 for crisp retina; hit-testing runs in CSS px, so both sides need the same coordinate system).
  const w = canvas.clientWidth || 300;
  const h = 150;
  if (canvas.width !== w * 2) { canvas.width = w * 2; canvas.height = h * 2; }
  ctx.setTransform(2, 0, 0, 2, 0, 0);
  ctx.clearRect(0, 0, w, h);
  // Base: 24 bars of even width across the full canvas (x follows bin order, values follow the backend bins; color follows the x position, aligned with the curve points).
  const n = 24;
  for (let i = 0; i < n; i++) {
    const x0 = Math.floor(i / n * w), x1 = Math.ceil((i + 1) / n * w);
    const v = bands ? Math.max(0, Math.min(255, bands[i] | 0)) : 0;
    const barH = Math.max(2, Math.round(v / 255 * (h - 6)));
    const y = h - 3 - barH;
    const hue = Math.round(120 * (1 - (i + 0.5) / n));
    const grad = ctx.createLinearGradient(0, y, 0, h - 3);
    grad.addColorStop(0, "hsl(" + hue + ",95%,65%)");
    grad.addColorStop(1, "hsl(" + hue + ",85%,38%)");
    ctx.globalAlpha = 0.45; // base layer: do not outshine the curve
    ctx.fillStyle = v > 0 ? grad : "#e5e7eb";
    ctx.fillRect(x0, y, Math.max(2, x1 - x0 - 1), barH);
    ctx.globalAlpha = 1;
  }
  // Top: EQ curve (bars only when there is no EQ state; the X scale is taken from geom inside the paint call, not passed through here).
  if (musicEqState && musicEqState.bands && musicEqState.bands.length > 0
      && musicEqCurveDb.length === musicEqState.bands.length) {
    musicEqPaintCurve(ctx, w, h);
  }
}

function musicFormatTime(ms) {
  const totalSec = Math.max(0, Math.floor((ms || 0) / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return min + ":" + (sec < 10 ? "0" : "") + sec;
}

function musicFormatSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KB";
  return bytes + " B";
}

function musicInit() {
  musicRefreshList();
  musicRefreshStatus();
  musicRefreshFillerToggle();
  musicRefreshDiscoToggle();
  musicRefreshEq();
  refreshSharedVolume();
  // Spectrum shared: local or radio, one canvas and one poll for both.
  // It starts when local playback is running; otherwise radioInit triggers it.
}

// ---------------- track list ----------------

function musicRefreshList() {
  const container = document.getElementById("musicListContainer");
  if (!container) return Promise.resolve();
  container.textContent = t("music_list_loading");
  return Alpha2Api.audioLocalMusicList().then(function (res) {
    if (!res.ok) return;
    // The list comes straight from the server-side listLocalMusicFiles() over the single LOCAL_MUSIC_DIR. The extra name-based dedup here is only insurance and should never actually hit.
    const seen = new Set();
    musicTracks = (res.files || []).filter(function (f) {
      if (seen.has(f.name)) return false;
      seen.add(f.name);
      return true;
    });
    musicRenderList();
  });
}

function musicRenderList() {
  const container = document.getElementById("musicListContainer");
  if (!container) return;
  container.innerHTML = "";
  if (musicTracks.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("music_list_empty");
    container.appendChild(p);
    return;
  }
  musicTracks.forEach(function (track) {
    const row = document.createElement("div");
    row.className = "music-track-row" + (track.name === musicCurrentName ? " active" : "");

    const nameSpan = document.createElement("span");
    nameSpan.className = "music-track-name";
    nameSpan.textContent = track.name;

    const sizeSpan = document.createElement("span");
    sizeSpan.className = "music-track-size";
    sizeSpan.textContent = musicFormatSize(track.sizeBytes || 0);

    // Clicking anywhere on the row plays it (user-select:none + cursor:pointer live in style.css).
    row.onclick = function () { musicPlay(track.name); };

    row.appendChild(nameSpan);
    row.appendChild(sizeSpan);
    container.appendChild(row);
  });
}

// ---------------- playback controls ----------------

function musicPlay(name) {
  musicCurrentName = name;
  musicLastPlayedName = name;
  sharedActiveSource = "local";
  if (typeof radioCurrentName !== "undefined") radioCurrentName = null;
  if (typeof radioCurrentId !== "undefined") radioCurrentId = null;
  musicRenderList();
  if (typeof radioRenderList === "function") radioRenderList();
  if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(name, false);
  Alpha2Api.audioLocalMusicPlay( { name: name }).then(function (res) {
    if (!res.ok) return;
    musicRefreshStatus();
    musicStartStatusPolling();
  });
}

// ---------------- prev / next / random / play-all ----------------
// All sequencing is client-side. The server audio/local_music/* endpoints have no playlist concept; they only "play this file". These functions compute the next track inside musicTracks, then call musicPlay().

/** Index of the currently playing track inside musicTracks; returns -1 when not found (list changed / nothing playing). */
function musicCurrentIndex() {
  if (!musicCurrentName) return -1;
  for (let i = 0; i < musicTracks.length; i++) {
    if (musicTracks[i].name === musicCurrentName) return i;
  }
  return -1;
}

function isRadioActive() {
  if (sharedActiveSource === "radio") return true;
  if (sharedActiveSource === "local") return false;
  // With no explicit source yet, decide by whether a radio station is currently playing
  return typeof radioCurrentName !== "undefined" && radioCurrentName && typeof radioStations !== "undefined" && radioStations.length > 0;
}
function musicPlayPrev() {
  if (isRadioActive() && typeof radioPlayPrev === "function") { radioPlayPrev(); return; }
  if (musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const target = idx <= 0 ? musicTracks.length - 1 : idx - 1;
  musicPlay(musicTracks[target].name);
}
function musicPlayNext() {
  if (isRadioActive() && typeof radioPlayNext === "function") { radioPlayNext(); return; }
  if (musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const target = idx < 0 || idx >= musicTracks.length - 1 ? 0 : idx + 1;
  musicPlay(musicTracks[target].name);
}
function musicPlayRandom() {
  if (isRadioActive() && typeof radioPlayRandom === "function") { radioPlayRandom(); return; }
  if (musicTracks.length === 0) return;
  // Shuffle mode: after a track ends, pick another at random (see the poll loop). Mutually exclusive with "▶ Play all"
  musicRandomMode = true;
  musicPlayAllMode = false;
  musicModeStopSeq = null; // entering mode: the next poll adopts the newest generation counter as baseline
  musicAdvanceRandom();
}

/** Pick the next track under "🔀 Shuffle" mode - avoid the current one (impossible to avoid when only one track exists). */
function musicAdvanceRandom() {
  if (!musicRandomMode || musicTracks.length === 0) return;
  let idx = Math.floor(Math.random() * musicTracks.length);
  if (musicTracks.length > 1) {
    let guard = 0;
    while (musicTracks[idx].name === musicCurrentName && guard++ < 10) {
      idx = Math.floor(Math.random() * musicTracks.length);
    }
  }
  musicPlay(musicTracks[idx].name);
}
function musicPlayAll() {
  if (isRadioActive()) return; // Radio is live; the "play all" concept does not apply
  if (musicTracks.length === 0) return;
  musicPlayAllMode = true;
  musicRandomMode = false;
  musicModeStopSeq = null; // entering mode: the next poll adopts the newest generation counter as baseline
  const idx = musicCurrentIndex();
  if (idx < 0) {
    musicPlay(musicTracks[0].name);
  } else {
    musicStartStatusPolling();
  }
}

/** Find the next track under "▶ Play all" mode - start from the current one and go back; wrap to the front at the end. */
function musicAdvancePlayAll() {
  if (!musicPlayAllMode || musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const next = idx < 0 ? 0 : (idx + 1) % musicTracks.length;
  musicPlay(musicTracks[next].name);
}

function musicTogglePlayPause() {
  if (isRadioActive()) {
    // Radio has no pause: the ▶/⏸ toggle is treated as stop/replay of the current station
    const btn = document.getElementById("musicPlayPauseBtn");
    const isPlaying = btn && btn.textContent.trim() === "⏸";
    if (isPlaying && typeof radioStop === "function") { radioStop(); }
    else if (radioCurrentName) { radioPlay(radioCurrentName); }
    else if (typeof radioPlayRandom === "function") { radioPlayRandom(); }
    return;
  }
  const btn = document.getElementById("musicPlayPauseBtn");
  const isPlaying = btn && btn.textContent.trim() === "⏸";
  if (isPlaying) {
    Alpha2Api.audioLocalMusicPause().then(musicRefreshStatus);
  } else if (musicCurrentName || musicLastPlayedName) {
    // A track is loaded (even after stopping it is not released, e.g. paused/finished) -> resume;
    // truly none -> fall back and replay the last track from the start.
    const hasLoadedTrack = musicHasLoadedTrack;
    const name = musicCurrentName || musicLastPlayedName;
    if (hasLoadedTrack) {
      Alpha2Api.audioLocalMusicResume().then(function (res) {
        if (res.ok && res.hasTrack !== false) {
          musicRefreshStatus();
          return;
        }
        musicPlay(name); // resume failed (for example already cleared by stop) - play from the start
      });
    } else {
      musicPlay(name);
    }
  } else if (musicTracks.length > 0) {
    musicPlay(musicTracks[0].name);
  }
}

/**
 * Music tab "⏹ Stop all" - matches the Speech tab stop-all key
 * (xiaozhiStopAll(), see app-xiaozhi.js): action/stop + speech/stop +
 * audio/local_music/stop + audio/radio/stop, all four at once, plus this tab's own
 * "▶ Play all" auto-continue mode. Reuse xiaozhiStopAll() directly instead of writing a second copy, so both
 * sides always behave the same; its Promise.all already keeps one failed endpoint from affecting the rest.
 */
function musicStopAll() {
  musicPlayAllMode = false;
  musicRandomMode = false;
  musicHasLoadedTrack = false;
  sharedActiveSource = null;
  xiaozhiResetTtsQueue();
  Promise.all([
    Alpha2Api.actionStop(),
    Alpha2Api.speechStop(),
    Alpha2Api.audioLocalMusicStop(),
    Alpha2Api.audioRadioStop(),
    Alpha2Api.ledHeadSet({ preset: "stop" }), // stop-all also stops the head LED (the mouth LED follows the TTS stop).
    Alpha2Api.ledEyeSet({ preset: "stop" }), // Eye LED: same as above.
  ]).then(function () {
    musicStopStatusPolling();
    musicCurrentName = null;
    musicLastPlayedName = null;
    if (typeof radioCurrentName !== "undefined") radioCurrentName = null;
    if (typeof radioCurrentId !== "undefined") radioCurrentId = null;
    musicRenderList();
    if (typeof radioRenderList === "function") radioRenderList();
    musicApplyStatus({ ok: true, hasTrack: false, playing: false,
      positionMs: 0, durationMs: 0, name: null });
    if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(t("music_now_playing_none"), false);
    musicStopSpectrumLoop();
  });
}

function musicOnSeekInput(value) {
  musicSeekDragging = true;
  document.getElementById("musicPositionLabel").textContent = musicFormatTime(Number(value));
}

function musicSeekTo(value) {
  Alpha2Api.audioLocalMusicSeek( { ms: String(Math.round(Number(value))) }).then(function () {
    musicSeekDragging = false;
    musicRefreshStatus();
  });
}

// Shared volume (system STREAM_MUSIC, affects local playback and radio alike) - one shared front-end slider.
// Push instantly while dragging (throttled at 150ms, no need to wait for release); the release-time onchange sends the final exact value.
let musicVolLastPushAt = 0;
function onSharedVolumeInput(value) {
  const valEl = document.getElementById("sharedVolumeVal");
  if (valEl) valEl.textContent = value;
  // Reverse-sync guard (see onVolumeChangedEvent in app-servo.js): during a local drag, do not let the WS echo push the slider back within 400ms.
  // markVolLocalInput lives in app-servo.js; the typeof
  // guard protects against load-order problems.
  if (typeof markVolLocalInput === "function") markVolLocalInput();
  const now = Date.now();
  if (now - musicVolLastPushAt < 150) return;
  musicVolLastPushAt = now;
  const v = Math.max(0, Math.min(15, parseInt(value, 10) || 0));
  Alpha2Api.audioVolumeSet( { level: String(v) }).then(function (res) {
    if (res.ok) {
      const statusSlider = document.getElementById("volumeSlider");
      const statusVal = document.getElementById("volumeVal");
      const rv = String(res.volume != null ? res.volume : v);
      if (statusSlider) statusSlider.value = rv;
      if (statusVal) statusVal.textContent = rv;
    }
  });
}
function setSharedVolume(value) {
  if (typeof markVolLocalInput === "function") markVolLocalInput(); // reverse-sync guard
  const v = Math.max(0, Math.min(15, parseInt(value, 10) || 0));
  Alpha2Api.audioVolumeSet( { level: String(v) }).then(function (res) {
    if (res.ok) {
      const valEl = document.getElementById("sharedVolumeVal");
      if (valEl) valEl.textContent = String(res.volume != null ? res.volume : v);
      const slider = document.getElementById("sharedVolumeSlider");
      if (slider) slider.value = String(res.volume != null ? res.volume : v);
      // Sync the slider on the status page
      const statusSlider = document.getElementById("volumeSlider");
      const statusVal = document.getElementById("volumeVal");
      if (statusSlider) statusSlider.value = String(res.volume != null ? res.volume : v);
      if (statusVal) statusVal.textContent = String(res.volume != null ? res.volume : v);
    }
  });
  // Also set the local per-track volume to 100%, so the two volume stages do not stack and make output quiet.
  // Only call this when a track is really loaded - calling it with no track eats a "no track loaded" error,
  // and a fresh player already defaults to 100%, so skipping it changes nothing.
  if (typeof musicHasLoadedTrack !== "undefined" && musicHasLoadedTrack) {
    Alpha2Api.audioLocalMusicVolume( { percent: "100" });
  }
}
function refreshSharedVolume() {
  Alpha2Api.audioVolumeGet().then(function (res) {
    if (!res.ok) return;
    const slider = document.getElementById("sharedVolumeSlider");
    const valEl = document.getElementById("sharedVolumeVal");
    if (slider) { slider.max = String(res.max || 15); slider.value = String(res.volume); }
    if (valEl) valEl.textContent = String(res.volume);
  });
}

// ---------------- status polling ----------------
//
// Uses a setTimeout chain (same as xiaozhiPollActivationStatus()), so the next request is only scheduled
// after the previous one returns; a slow network cannot pile up requests. Keep polling only while something
// is actually playing (hasTrack=true).
function musicStartStatusPolling() {
  musicStopStatusPolling();
  musicPollStatusLoop();
}

function musicStopStatusPolling() {
  if (musicStatusPollTimer) {
    clearTimeout(musicStatusPollTimer);
    musicStatusPollTimer = null;
  }
  musicStopSpectrumLoop();
}

function musicPollStatusLoop() {
  Alpha2Api.audioLocalMusicStatus().then(function (res) {
    if (!res.ok) return;
    if (res.stopSeq != null && musicModeStopSeq == null) {
      musicModeStopSeq = res.stopSeq; // first sighting: adopt as baseline (no comparison)
    }
    if (!res.hasTrack && (musicPlayAllMode || musicRandomMode)) {
      // Someone outside explicitly pressed stop (pad 94 stop-all key / stop API / MCP stop / panel stop-all):
      // the generation counter moved - turn the mode off, do not queue the next track, and fall through to the
      // shared tail cleanup (stop spectrum, stop polling). A natural finish does not move the counter, and only then advance.
      // (If a stop lands between entering the mode and the first poll, we miss it once; the window is a few hundred ms, accepted.)
      if (res.stopSeq != null && musicModeStopSeq != null && res.stopSeq !== musicModeStopSeq) {
        musicPlayAllMode = false;
        musicRandomMode = false;
        musicCurrentName = null;
        musicLastPlayedName = null;
        musicRenderList();
        if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(t("music_now_playing_none"), false);
      } else {
        if (musicRandomMode) musicAdvanceRandom();
        else musicAdvancePlayAll();
        return;
      }
    }
    musicApplyStatus(res);
    if (res.hasTrack) {
      musicStartSpectrumLoop();
      musicStatusPollTimer = setTimeout(musicPollStatusLoop, 1000);
    } else {
      musicPlayAllMode = false;
      musicRandomMode = false;
      // Shared spectrum: local stopped but radio still playing - keep the spectrum
      if (!radioCurrentName) {
        musicStopSpectrumLoop();
      }
    }
  });
}

function musicRefreshStatus() {
  return Alpha2Api.audioLocalMusicStatus().then(function (res) {
    if (!res.ok) return;
    musicApplyStatus(res);
    if (res.hasTrack) musicStartStatusPolling();
  });
}

function updateSharedNowPlaying(text, isRadio) {
  const shared = document.getElementById("sharedNowPlaying");
  const progressRow = document.getElementById("sharedProgressRow");
  if (shared) {
    shared.textContent = text || t("music_now_playing_none");
    shared.classList.toggle("radio-playing", !!isRadio);
  }
  if (progressRow) {
    // Radio is live and has no progress, so hide the progress bar; show it when local playback has progress
    progressRow.style.display = isRadio ? "none" : "flex";
  }
}

function musicApplyStatus(res) {
  const nowPlaying = document.getElementById("musicNowPlaying");
  const sharedNow = document.getElementById("sharedNowPlaying");
  const playPauseBtn = document.getElementById("musicPlayPauseBtn");
  const seekBar = document.getElementById("musicSeekBar");
  const posLabel = document.getElementById("musicPositionLabel");
  const durLabel = document.getElementById("musicDurationLabel");
  if (!nowPlaying || !playPauseBtn || !seekBar) return;

  if (!res.hasTrack) {
    musicHasLoadedTrack = false;
    nowPlaying.textContent = t("music_now_playing_none");
    if (sharedNow && !radioCurrentName) updateSharedNowPlaying(t("music_now_playing_none"), false);
    playPauseBtn.textContent = "▶";
    seekBar.max = "0";
    seekBar.value = "0";
    posLabel.textContent = "0:00";
    durLabel.textContent = "0:00";
    return;
  }

  musicHasLoadedTrack = true;
  musicCurrentName = res.name;
  if (res.name) musicLastPlayedName = res.name;
  nowPlaying.textContent = res.name || "";
  if (sharedNow) updateSharedNowPlaying(res.name || "", false);
  playPauseBtn.textContent = res.playing ? "⏸" : "▶";
  playPauseBtn.title = res.playing ? t("music_pause_btn_title") : t("music_play_btn_title");

  if (res.durationMs > 0) {
    seekBar.max = String(res.durationMs);
    durLabel.textContent = musicFormatTime(res.durationMs);
  }
  if (!musicSeekDragging) {
    seekBar.value = String(res.positionMs);
    posLabel.textContent = musicFormatTime(res.positionMs);
  }
  musicRenderList();
}

// ---------------- random filler action toggle ----------------

function musicRefreshFillerToggle() {
  Alpha2Api.audioLocalMusicFillerActionGet().then(function (res) {
    if (!res.ok) return;
    musicApplyFillerToggleUi(res.enabled);
  });
}

function musicSetFillerActionEnabled(enabled) {
  Alpha2Api.audioLocalMusicFillerActionSet( { enabled: enabled ? "true" : "false" }).then(function (res) {
    if (!res.ok) return;
    musicApplyFillerToggleUi(res.enabled);
  });
}

function musicApplyFillerToggleUi(enabled) {
  const checkbox = document.getElementById("musicFillerToggle");
  const label = document.getElementById("musicFillerStateLabel");
  if (checkbox) checkbox.checked = !!enabled;
  if (label) label.textContent = enabled ? t("music_filler_on") : t("music_filler_off");
}

// ---------------- disco LED toggle ----------------
// Like the random-move switch: persisted in backend prefs, remembered even without the browser; the LED pushing itself runs on the backend.

function musicRefreshDiscoToggle() {
  Alpha2Api.audioLocalMusicDiscoGet().then(function (res) {
    if (!res.ok) return;
    musicDiscoEnabled = !!res.enabled;
    musicApplyDiscoToggleUi(musicDiscoEnabled);
  });
}

function musicSetDiscoEnabled(enabled) {
  Alpha2Api.audioLocalMusicDiscoSet({ enabled: enabled ? "true" : "false" }).then(function (res) {
    if (!res.ok) return;
    musicDiscoEnabled = !!res.enabled;
    musicApplyDiscoToggleUi(musicDiscoEnabled);
  });
}

function musicApplyDiscoToggleUi(enabled) {
  const checkbox = document.getElementById("musicDiscoToggle");
  const label = document.getElementById("musicDiscoStateLabel");
  if (checkbox) checkbox.checked = !!enabled;
  if (label) label.textContent = enabled ? t("music_disco_on") : t("music_disco_off");
}

// ---------------- equalizer ----------------
// Persisted in backend prefs (remembered without the browser); sliders work in dB (backend mB÷100).
// The preset table lives in the backend MusicEq; band count/frequency/range are all built dynamically from eq/get.
let musicEqState = null; // the last eq/get|cfg response
let musicEqPushTimer = null;

const MUSIC_EQ_PRESETS = ["normal", "classical", "dance", "rock", "jazz", "pop", "bass", "treble", "custom"];

function musicEqPresetLabel(name) {
  const s = t("music_eq_preset_" + name);
  return (s && s.indexOf("music_eq_preset_") !== 0) ? s : name;
}

function musicRefreshEq() {
  Alpha2Api.audioLocalMusicEqGet().then(function (res) {
    if (!res || !res.ok) return;
    // No on/off switch: on by default. If an old backend pref is still off (or the first-version default off remained), push it back on.
    // After that, playback automatically uses the standard flat preset (unless the user picked another preset before; if so, follow it).
    if (!res.enabled) {
      Alpha2Api.audioLocalMusicEqSet({ enabled: "true" }).then(function (res2) {
        if (!res2 || !res2.ok) return;
        musicEqState = res2;
        musicRenderEq();
      });
      return;
    }
    musicEqState = res;
    musicRenderEq();
  });
}

function musicSetEqPreset(preset) {
  Alpha2Api.audioLocalMusicEqSet({ preset: preset }).then(function (res) {
    if (!res || !res.ok) return;
    musicEqState = res;
    musicRenderEq();
  });
}

function musicRenderEq() {
  const row = document.getElementById("musicEqRow");
  const bandsBox = document.getElementById("musicEqBands");
  const unsupported = document.getElementById("musicEqUnsupported");
  if (!row || !musicEqState) return;
  if (!musicEqState.supported) {
    // The backend latch confirmed there is really no EQ: collapse the whole row so it does not block space.
    row.style.display = "none";
    if (bandsBox) bandsBox.classList.remove("on");
    return;
  }
  row.style.display = "";
  const presetBox = document.getElementById("musicEqPreset");
  if (presetBox) {
    presetBox.innerHTML = "";
    MUSIC_EQ_PRESETS.forEach(function (p) {
      const btn = document.createElement("button");
      btn.type = "button";
      // Same square button as the radio quick-search (secondary); selected adds blue.
      btn.className = "secondary eq-preset-sq" + (p === musicEqState.preset ? " selected" : "");
      btn.dataset.preset = p;
      btn.textContent = musicEqPresetLabel(p);
      btn.onclick = function () { musicSetEqPreset(p); };
      presetBox.appendChild(btn);
    });
  }
  if (unsupported) unsupported.style.display = musicEqState.bandsKnown ? "none" : "";
  // Curve direct drag: disabled when the band count is unknown; the preset buttons still work. The slider column was removed (final decision: curve only).
  if (!bandsBox) return;
  bandsBox.classList.remove("on");
  const bands = musicEqState.bands || [];
  if (bands.length === 0) return;
  bandsBox.classList.add("on");
  try {
    musicEqCurveMinDb = Math.round(bands[0].minMb / 100);
    musicEqCurveMaxDb = Math.round(bands[0].maxMb / 100);
    if (!(musicEqCurveMaxDb > musicEqCurveMinDb)) { musicEqCurveMinDb = -15; musicEqCurveMaxDb = 15; }
  } catch (e) { musicEqCurveMinDb = -15; musicEqCurveMaxDb = 15; }
  musicEqCurveDb = [];
  for (let i = 0; i < bands.length; i++) {
    let v = 0;
    try { v = Math.round(bands[i].levelMb / 100); } catch (e) {}
    musicEqCurveDb.push(v);
  }
  musicEqHookCurvePointer();
  musicEqDrawCurve();
}

// Curve direct-drag live state (not via the backend; it changes with each drag; sends to the backend go through a debounce / send on release).
let musicEqCurveDb = [];
let musicEqCurveMinDb = -15;
let musicEqCurveMaxDb = 15;
let musicEqDragBand = -1;

// Combined x axis: backend bin order (0-24), not true log frequency.
// Why: the backend's 24 bins follow a (b/24)^2 sparse distribution. Placing them at true log positions crushes the
// lows into slivers and clumps the highs (measured: 7 bars packed into 8% of the width, invisible). Use bin order across
// the whole width: bars get even width, and each curve point sits at the bin position matching its own
// frequency - x positions always align, never drift again.
// Frequency -> bin position (same rule as the backend buildMusicSpectrumBinMap:
// true log even split 40Hz-16kHz, b = 24*log(f/40)/log(400), clamped to 0-24).
function musicFreqBin(fHz) {
  const fr = Math.log(Math.max(40.0001, Math.min(15999.999, fHz)) / 40) / Math.log(400);
  return Math.max(0, Math.min(24, 24 * fr));
}

// Color follows the x position (bin order): same x, same color, so bars and points always line up.
function musicFreqHue(fHz) {
  return Math.round(120 * (1 - musicFreqBin(fHz) / 24));
}

function musicEqBandHex(bi, n) {
  const bands = (musicEqState && musicEqState.bands) || [];
  const f = (bands[bi] && bands[bi].freqHz > 0) ? bands[bi].freqHz : 20;
  return "hsl(" + musicFreqHue(f) + ",90%,40%)";
}

// Curve coordinates (shared by draw + hit test; do not compute two separate sets).
function musicEqCurveGeom(w, h) {
  const lo = musicEqCurveMinDb, hi = musicEqCurveMaxDb;
  return {
    // The curve x shares the bin order with the bars (not log frequency): X(f) = which bin position that frequency maps to.
    X: function (f) { return musicFreqBin(f) / 24 * w; },
    Y: function (db) { return h - 4 - (db - lo) / (hi - lo) * (h - 8); }
  };
}

function musicEqHookCurvePointer() {
  const cv = document.getElementById("musicEqSpectrum");
  if (!cv || cv.dataset.hooked) return;
  cv.dataset.hooked = "1";
  if (!musicEqResizeHooked) {
    musicEqResizeHooked = true;
    try {
      window.addEventListener("resize", function () {
        try { musicEqDrawCurve(); } catch (e) {}
      });
    } catch (e) {}
  }
  function posOf(evt) {
    const r = cv.getBoundingClientRect();
    return { x: evt.clientX - r.left, y: evt.clientY - r.top, w: r.width, h: r.height };
  }
  cv.addEventListener("pointerdown", function (evt) {
    try {
      const bands = (musicEqState && musicEqState.bands) || [];
      if (bands.length === 0 || musicEqCurveDb.length !== bands.length) return;
      const p = posOf(evt);
      if (!(p.w > 0 && p.h > 0)) return;
      const g = musicEqCurveGeom(p.w, p.h);
      let best = -1, bestD = 26 * 26;
      for (let i = 0; i < bands.length; i++) {
        const f = bands[i].freqHz > 0 ? bands[i].freqHz : 40;
        const dx = g.X(f) - p.x, dy = g.Y(musicEqCurveDb[i]) - p.y;
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = i; }
      }
      if (best < 0) return;
      musicEqDragBand = best;
      try { cv.setPointerCapture(evt.pointerId); } catch (e) {}
      cv.style.cursor = "grabbing";
      musicEqDragTo(p);
      if (evt.preventDefault) evt.preventDefault();
    } catch (e) {}
  });
  cv.addEventListener("pointermove", function (evt) {
    if (musicEqDragBand < 0) return;
    try { musicEqDragTo(posOf(evt)); } catch (e) {}
  });
  function endDrag() {
    if (musicEqDragBand < 0) return;
    musicEqDragBand = -1;
    cv.style.cursor = "grab";
    // Send on release (do not wait another 400ms; flush any remaining debounce too, so nothing is sent twice).
    if (musicEqPushTimer) { clearTimeout(musicEqPushTimer); musicEqPushTimer = null; }
    musicEqPushLevels();
  }
  cv.addEventListener("pointerup", endDrag);
  cv.addEventListener("pointercancel", endDrag);
}

function musicEqDragTo(p) {
  const bands = (musicEqState && musicEqState.bands) || [];
  const i = musicEqDragBand;
  if (i < 0 || i >= bands.length || i >= musicEqCurveDb.length) return;
  if (!(p.h > 8)) return;
  const lo = musicEqCurveMinDb, hi = musicEqCurveMaxDb;
  let db = lo + (p.h - 4 - p.y) / (p.h - 8) * (hi - lo); // inverse of Y
  db = Math.max(lo, Math.min(hi, Math.round(db)));
  if (db === musicEqCurveDb[i]) return;
  musicEqCurveDb[i] = db;
  musicEqDrawCurve();
  musicEqSchedulePush();
}

function musicEqSchedulePush() {
  if (musicEqPushTimer) clearTimeout(musicEqPushTimer);
  musicEqPushTimer = setTimeout(musicEqPushLevels, 400);
}

let musicEqResizeHooked = false;

// Redrawing the frequency-response curve = redraw the whole combined view (bars use the current smooth values, or flat when none; the curve uses the live drag values).
function musicEqDrawCurve() {
  try {
    const n = musicSpectrumSmooth.length || 0;
    musicDrawSpectrum(n > 0 ? musicSpectrumSmooth : null);
  } catch (e) {}
}

/** The top layer of the combined view: grid + curve + fill + handles (called by musicDrawSpectrum). */
function musicEqPaintCurve(ctx, w, h) {
  try {
    const bands = (musicEqState && musicEqState.bands) || [];
    if (bands.length === 0 || musicEqCurveDb.length !== bands.length) return;
    const X = musicEqCurveGeom(w, h).X;
    const minDb = musicEqCurveMinDb, maxDb = musicEqCurveMaxDb;
    function Y(db) {
      return h - 4 - (db - minDb) / (maxDb - minDb) * (h - 8);
    }
    // Base grid: solid 0dB line + dashed band-position lines.
    let accent = "#2563eb";
    try {
      const a = getComputedStyle(document.documentElement).getPropertyValue("--accent");
      if (a && a.trim()) accent = a.trim();
    } catch (e) {}
    ctx.lineWidth = 1;
    if (0 >= minDb && 0 <= maxDb) {
      ctx.strokeStyle = "rgba(0,0,0,0.35)";
      ctx.beginPath(); ctx.moveTo(0, Y(0)); ctx.lineTo(w, Y(0)); ctx.stroke();
    }
    ctx.strokeStyle = "rgba(0,0,0,0.12)";
    ctx.setLineDash([3, 3]);
    for (let i = 0; i < bands.length; i++) {
      const fx = X(bands[i].freqHz > 0 ? bands[i].freqHz : 40);
      ctx.beginPath(); ctx.moveTo(fx, 0); ctx.lineTo(fx, h); ctx.stroke();
    }
    ctx.setLineDash([]);
    // Control points: each band (live dB at its bin-order x) + the two ends flattened out to the canvas edges.
    const n = bands.length;
    const px = [], py = [];
    const db0 = musicEqCurveDb[0], dbN = musicEqCurveDb[n - 1];
    px.push(0); py.push(db0);
    for (let i = 0; i < n; i++) {
      const f = bands[i].freqHz > 0 ? bands[i].freqHz : 40;
      px.push(X(f)); py.push(musicEqCurveDb[i]);
    }
    px.push(w); py.push(dbN);
    // Catmull-Rom (index parameter; log-x is already inside X).
    ctx.beginPath();
    const SEG = 64;
    for (let k = 0; k <= SEG; k++) {
      const t = k / SEG * (px.length - 1);
      const i0 = Math.min(px.length - 2, Math.floor(t)), fr = t - i0;
      const q0x = px[Math.max(0, i0 - 1)], q1x = px[i0], q2x = px[i0 + 1], q3x = px[Math.min(px.length - 1, i0 + 2)];
      const q0y = py[Math.max(0, i0 - 1)], q1y = py[i0], q2y = py[i0 + 1], q3y = py[Math.min(py.length - 1, i0 + 2)];
      const fr2 = fr * fr, fr3 = fr2 * fr;
      const x = 0.5 * ((2 * q1x) + (-q0x + q2x) * fr + (2 * q0x - 5 * q1x + 4 * q2x - q3x) * fr2 + (-q0x + 3 * q1x - 3 * q2x + q3x) * fr3);
      const yv = 0.5 * ((2 * q1y) + (-q0y + q2y) * fr + (2 * q0y - 5 * q1y + 4 * q2y - q3y) * fr2 + (-q0y + 3 * q1y - 3 * q2y + q3y) * fr3);
      if (k === 0) ctx.moveTo(x, Y(yv)); else ctx.lineTo(x, Y(yv));
    }
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2;
    ctx.stroke();
    // Light fill below 0dB (color appears only where the EQ moved).
    ctx.lineTo(w, Y(0)); ctx.lineTo(0, Y(0)); ctx.closePath();
    let fill = accent;
    try {
      const m = accent.match(/^#([0-9a-f]{6})$/i);
      if (m) {
        const r = parseInt(m[1].slice(0, 2), 16), g = parseInt(m[1].slice(2, 4), 16), b = parseInt(m[1].slice(4, 6), 16);
        fill = "rgba(" + r + "," + g + "," + b + ",0.15)";
      }
    } catch (e) {}
    ctx.fillStyle = fill;
    ctx.fill();
    // 5 handles: band-colored dot + dB number (for direct dragging; the 26px hit radius is in the hook).
    try {
      ctx.font = "11px sans-serif";
      ctx.textAlign = "center";
      for (let i = 0; i < n; i++) {
        const f = bands[i].freqHz > 0 ? bands[i].freqHz : 40;
        const cx = X(f), cy = Y(musicEqCurveDb[i]);
        const hex = musicEqBandHex(i, n);
        ctx.beginPath();
        ctx.arc(cx, cy, 9, 0, Math.PI * 2);
        ctx.fillStyle = hex;
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = "#222";
        ctx.stroke();
        const label = (musicEqCurveDb[i] > 0 ? "+" : "") + musicEqCurveDb[i];
        ctx.fillStyle = "#333";
        const ly = cy - 14 < 10 ? cy + 22 : cy - 14;
        ctx.fillText(label, cx, ly);
        const fl = bands[i].freqHz >= 1000 ? (bands[i].freqHz / 1000) + "k" : "" + bands[i].freqHz;
        ctx.fillStyle = "#888";
        ctx.fillText(fl, cx, h - 4);
      }
    } catch (e) {}
  } catch (e) {}
}

function musicEqPushLevels() {
  musicEqPushTimer = null;
  const bands = (musicEqState && musicEqState.bands) || [];
  if (bands.length === 0 || musicEqCurveDb.length !== bands.length) return;
  const mbs = [];
  for (let i = 0; i < bands.length; i++) mbs.push(musicEqCurveDb[i] * 100);
  Alpha2Api.audioLocalMusicEqSet({ levels: mbs.join(",") }).then(function (res) {
    if (!res || !res.ok) return;
    musicEqState = res;
    // Do not rebuild while a slider is being dragged (it would interrupt the drag); only sync the preset blue buttons (already switched to custom).
    musicEqMarkPresetSelected(res.preset);
  });
}

function musicEqMarkPresetSelected(preset) {
  const presetBox = document.getElementById("musicEqPreset");
  if (!presetBox) return;
  const btns = presetBox.querySelectorAll(".eq-preset-sq");
  for (let i = 0; i < btns.length; i++) {
    if (btns[i].dataset.preset === preset) btns[i].classList.add("selected");
    else btns[i].classList.remove("selected");
  }
}

// zh/en switch: relabel each dynamically built preset blue button one by one (no rebuild; slider positions stay).
// Also convert the random-move / rhythm-LED state words (on) that moved into the "Playing" row
// (before, those two only called t() at press time, so a language switch left stale words).
function musicEqApplyUiLanguage() {
  try {
    const presetBox = document.getElementById("musicEqPreset");
    if (presetBox) {
      const btns = presetBox.querySelectorAll(".eq-preset-sq");
      for (let i = 0; i < btns.length; i++) {
        if (btns[i].dataset.preset) btns[i].textContent = musicEqPresetLabel(btns[i].dataset.preset);
      }
    }
    const fillerToggle = document.getElementById("musicFillerToggle");
    const fillerLabel = document.getElementById("musicFillerStateLabel");
    if (fillerToggle && fillerLabel) {
      fillerLabel.textContent = fillerToggle.checked ? t("music_filler_on") : t("music_filler_off");
    }
    const discoToggle = document.getElementById("musicDiscoToggle");
    const discoLabel = document.getElementById("musicDiscoStateLabel");
    if (discoToggle && discoLabel) {
      discoLabel.textContent = discoToggle.checked ? t("music_disco_on") : t("music_disco_off");
    }
  } catch (e) {}
}

// ---------------- drag & drop import ----------------

function musicOnDragOver(evt) {
  evt.preventDefault();
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.add("dragover");
}

function musicOnDragLeave(evt) {
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.remove("dragover");
}

function musicOnDrop(evt) {
  evt.preventDefault();
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.remove("dragover");
  const files = evt.dataTransfer && evt.dataTransfer.files;
  if (files && files.length > 0) musicUploadFiles(files);
}

function musicOnFileInputChange(files) {
  if (files && files.length > 0) musicUploadFiles(files);
  // Clear the input value, so picking the same file name again next time still fires the change event
  document.getElementById("musicFileInput").value = "";
}

/** Upload one by one (not in parallel) - every upload is a full HTTP POST body (the whole file content).
 *  Pushing several large uploads in parallel puts needless memory/network pressure on this device
 *  (RK3288, ARMv7, 1.1.7.3.20). One by one is simple and enough. */
function musicUploadFiles(fileList) {
  const files = Array.prototype.slice.call(fileList);
  const statusEl = document.getElementById("musicUploadStatus");
  let index = 0;

  function uploadNext() {
    if (index >= files.length) {
      if (statusEl) statusEl.textContent = t("music_upload_done");
      musicRefreshList();
      return;
    }
    const file = files[index];
    index++;
    if (statusEl) {
      statusEl.textContent = t("music_upload_uploading") + " (" + index + "/" + files.length + ") " + file.name;
    }
    clearError();
    // Panel token: /upload/music needs verification while auth is on (Bearer header is primary, see PanelAuth; like the api() family it
    // reads from localStorage; panelAuthHeaders is global, see app-core.js).
    const hdrs = (typeof panelAuthHeaders === "function") ? panelAuthHeaders() : {};
    fetch("/upload/music?" + new URLSearchParams({ name: file.name }).toString(), {
      method: "POST",
      body: file,
      headers: hdrs,
    }).then(function (res) {
      return res.json().catch(function () {
        return { ok: false, error: "invalid response (status " + res.status + ")" };
      });
    }).then(function (json) {
      if (!json.ok) {
        showError("Upload " + file.name, new Error(json.error || "upload failed"));
      }
      uploadNext();
    }).catch(function (networkErr) {
      showError("Network error uploading " + file.name, networkErr);
      if (statusEl) statusEl.textContent = t("music_upload_failed") + ": " + file.name;
      uploadNext();
    });
  }

  uploadNext();
}

