// Open Alpha2 — Blockly page initialization.
// Provides an api()/WebSocket flow exactly like index.html (app-core.js), as
// an independent copy (so this page can open in its own tab without index.html
// running), then inits the Blockly workspace, binds the toolbar, wires the blockly-run.js events.

const API = '/api/';

function showError(context, err) {
  const banner = document.getElementById('errorBanner');
  let msg = (err && err.message) ? err.message : String(err);
  banner.textContent = '⚠ ' + context + ': ' + msg;
  banner.style.display = 'block';
  console.error(context, err);
}
function clearError() {
  const banner = document.getElementById('errorBanner');
  banner.style.display = 'none';
  banner.textContent = '';
}
window.addEventListener('error', function (e) { showError('JavaScript error', e.error || e.message); });
window.addEventListener('unhandledrejection', function (e) { showError('Unhandled promise rejection', e.reason); });

// Panel token (see PanelAuth.java + app-core.js): when set, the whole panel locks.
// /api uses the Authorization: Bearer header, never the URL; <img> and WebSocket
// use a ?panel_token= query fallback. The key shares index.html's localStorage
// "panel_token" (one browser + one panel origin), so one unlock covers both.
function panelTokenGet() {
  try { return localStorage.getItem('panel_token') || ''; } catch (e) { return ''; }
}
function panelAuthHeaders(params) {
  const tok = (params && params.panel_token) ? params.panel_token : panelTokenGet();
  if (!tok) return {};
  return { 'Authorization': 'Bearer ' + tok };
}
function stripPanelToken(params) {
  if (!params) return params;
  if (params.panel_token == null) return params;
  const out = {};
  for (const k in params) { if (k !== 'panel_token') out[k] = params[k]; }
  return out;
}
function apiQuery(params) {
  const clean = stripPanelToken(params);
  return clean ? '?' + new URLSearchParams(clean).toString() : '';
}
function withPanelToken(params) {
  const tok = panelTokenGet();
  if (!tok) return params;
  const out = {};
  if (params) { for (const k in params) { out[k] = params[k]; } }
  if (out.panel_token == null) out.panel_token = tok;
  return out;
}

// Shared api() helper — same behaviour as the app-core.js version (Bearer header + query string, parsed JSON).
window.api = function (path, params) {
  clearError();
  const qs = apiQuery(params);
  return fetch(API + 'alpha2/' + path + qs, { headers: panelAuthHeaders(params) }).then(function (res) {
    return res.json().catch(function () {
      return { ok: false, error: 'invalid response (status ' + res.status + ')' };
    }).then(function (json) {
      if (!json.ok) {
        showError('API /' + path, new Error(json.error || json.code || 'request failed'));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError('Network error calling /' + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
};

// Namespace helpers — match the three separate MainActivity route groups,
// with the same shape as app-core.js / app-xiaozhi.js. api-client.js
// (Alpha2Api.*) picks one automatically: sysApi() -> /api/system/*,
// directApi() -> /api/direct/*, xiaozhiApi() -> /api/xiaozhi/*. hwApi is an
// alias of api() for the legacy hw() in api-client.js, same as index.html.
function namespacedApi(prefix, label) {
  return function (path, params) {
    clearError();
    const qs = apiQuery(params);
    return fetch(API + prefix + path + qs, { headers: panelAuthHeaders(params) }).then(function (res) {
      return res.json().catch(function () {
        return { ok: false, error: 'invalid response (status ' + res.status + ')' };
      }).then(function (json) {
        if (!json.ok) {
          showError('API /' + label + path, new Error(json.error || json.code || 'request failed'));
        }
        return json;
      });
    }).catch(function (networkErr) {
      showError('Network error calling /' + label + path, networkErr);
      return { ok: false, error: String(networkErr) };
    });
  };
}
window.sysApi = namespacedApi('system/', 'system/');
window.directApi = namespacedApi('direct/', 'direct/');
window.xiaozhiApi = namespacedApi('xiaozhi/', 'xiaozhi/');
window.hwApi = window.api;

// ---------------- WebSocket: wiring for event-driven blocks ----------------
let ws = null;
let wsReconnectTimer = null;
let wsBootId = null; // server bootId (auto-reload of a stale page, see app-log.js).

function setWsStatus(connected) {
  document.getElementById('wsStatusDot').className = 'run-status-dot ' + (connected ? 'running' : 'idle');
  document.getElementById('wsStatusText').textContent = 'WebSocket: ' + (connected ? t('page_ws_connected') : t('page_ws_disconnected_word'));
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  // Panel token: WebSocket cannot set headers, so a locked panel uses the ?panel_token= query fallback (see PanelAuth).
  let wsUrl = proto + '//' + location.host + '/ws';
  try {
    const pt = panelTokenGet();
    if (pt) wsUrl += '?panel_token=' + encodeURIComponent(pt);
  } catch (e) {}
  try {
    ws = new WebSocket(wsUrl);
  } catch (e) {
    showError(t('page_ws_connect_failed'), e);
    scheduleReconnect();
    return;
  }
  ws.onopen = function () { setWsStatus(true); };
  ws.onclose = function () { setWsStatus(false); scheduleReconnect(); };
  // 2026-09-09: an error now closes the socket, so one onclose path handles
  // reconnect. Before, an error without a close event never reconnected (as in app-log.js).
  ws.onerror = function () { setWsStatus(false); try { ws.close(); } catch (e) {} };
  ws.onmessage = function (evt) {
    let parsed;
    try {
      parsed = JSON.parse(evt.data);
    } catch (e) {
      return;
    }
    // As on the main panel: if bootId changed, this stale page reloads (see app-log.js).
    if (parsed && parsed.type === "connected" && parsed.bootId) {
      if (wsBootId && wsBootId !== parsed.bootId) {
        location.reload();
        return;
      }
      wsBootId = parsed.bootId;
      return;
    }
    if (parsed && parsed.type && parsed.type !== 'connected' && window.AlphaBlockly) {
      window.AlphaBlockly.onWsEvent(parsed);
    }
  };
}
function scheduleReconnect() {
  clearTimeout(wsReconnectTimer);
  wsReconnectTimer = setTimeout(connectWs, 2000);
}

// ---------------- Blockly workspace initialization ----------------
let workspace = null;

// Show the Blockly version actually loaded, in the page header. Read it
// straight from Blockly.VERSION (a constant baked into blockly_compressed.js)
// instead of typing a number into the HTML, so an upgrade or replacement of
// that file updates the display automatically and can never go stale.
function showBlocklyVersionBadge() {
  const badge = document.getElementById('blocklyVersionBadge');
  if (!badge) return;
  const ver = (typeof Blockly !== 'undefined' && Blockly.VERSION) ? Blockly.VERSION : t('page_blockly_version_unknown');
  badge.textContent = 'Blockly v' + ver;
}

function initWorkspace() {
  showBlocklyVersionBadge();
  // On a phone the canvas keeps only ~240px of width, while Chinese blocks
  // are ~300-400px wide (see the screenshot where "Play local music" is cut
  // off), so phones start at 0.6 to fit whole blocks; desktop keeps 0.9.
  const isMobileStart = (typeof window !== 'undefined' && window.matchMedia &&
    window.matchMedia('(max-width: 900px)').matches);
  workspace = Blockly.inject('blocklyDiv', {
    toolbox: window.ALPHA_TOOLBOX,
    // This Blockly version's default pathToMedia is
    // "https://static.blockly.com/media/" (an external CDN), unreachable in this
    // app's WebView. It now uses the local media/ folder (the official npm
    // package's media files were copied in), so everything works offline.
    // (2026-09: sprite buttons are gone — self-drawn zoom, vector trash, media/ is a backup.)
    media: 'media/',
    grid: { spacing: 24, length: 2, colour: '#c3cad6', snap: true },
    // 2026-09: zoom.controls is off — the three built-in sprite buttons
    // (zoom in/out/reset) do not match the Code Lab look, so blockly-run.js
    // ZoomFabControls draws a vertical group (same style as the edit row). Wheel zoom stays.
    zoom: { controls: false, wheel: true, startScale: (isMobileStart ? 0.6 : 0.9), maxScale: 3, minScale: 0.3, scaleSpeed: 1.1 },
    trashcan: true,
    move: { scrollbars: true, drag: true, wheel: false },
    theme: buildAlphaTheme(),
    sounds: false,
  });
  window.__alphaBlocklyWorkspace = workspace; // re-fetched by blockly-i18n.js on a language switch
  // AlphaBlockly.init() now also starts the two button rows (undo/cut and zoom),
  // which are Blockly IPositionable components (see the
  // EditFabControls/ZoomFabControls notes in blockly-run.js). They reuse the
  // exact official positioning pipeline of the trash can, so they can start
  // only after workspace injection (the side trigger is HTML #bkSideTrigger).
  window.AlphaBlockly.init(workspace);

  // Recompute the Blockly canvas size when the window resizes.
  window.addEventListener('resize', resizeBlockly);
  resizeBlockly();

  // Copied from Code Lab (see the chat screenshot): the side panel (run log)
  // collapse state is stored in localStorage, so the next time this tab opens
  // it keeps the last choice. Use setSidePanelCollapsedInitial(), not
  // toggleSidePanel(): the page must load already collapsed, with no 0.18s animation.
  //
  // 2026-09: desktop and phone both start with the side panel collapsed, so
  // the canvas has the full width. The user opens the log on demand (the state is kept).
  try {
    const savedCollapsed = localStorage.getItem('blocklySideCollapsed');
    if (savedCollapsed === '1' || savedCollapsed == null) {
      window.AlphaBlockly.setSidePanelCollapsedInitial(true);
      resizeBlockly();
    }
  } catch (e) { /* localStorage may be unavailable in some WebViews; default to open when there is no record, which is fine */ }

  // Phone rotation and the address bar show/hide fire resize; the first layout
  // frame may not have settled, so a delayed second svgResize guarantees the size.
  window.addEventListener('orientationchange', function () {
    setTimeout(resizeBlockly, 200);
    setTimeout(resizeBlockly, 500);
  });
  setTimeout(resizeBlockly, 300);
}

// Recompute the Blockly canvas size after a window resize or a side-panel
// collapse/expand. One Blockly.svgResize() call also re-positions the
// ComponentManager POSITIONABLE components (the trash can, our own
// EditFabControls/ZoomFabControls), so nothing else needs touching. It is a
// separate function for the resize listener and toggleSidePanel() to share.
function resizeBlockly() {
  if (workspace) Blockly.svgResize(workspace);
}

function toggleSidePanel() {
  if (window.AlphaBlockly && window.AlphaBlockly.toggleSidePanel) {
    window.AlphaBlockly.toggleSidePanel();
  }
}
window.toggleSidePanel = toggleSidePanel;

function buildAlphaTheme() {
  // Reuse the light blue/white palette from style.css (--bg #f5f7fa /
  // --accent #3b7dff) so the whole page looks like one product, not a bolted-on third-party style.
  try {
    return Blockly.Theme.defineTheme('alphaTheme', {
      base: Blockly.Themes.Classic,
      componentStyles: {
        workspaceBackgroundColour: '#f5f7fa',
        toolboxBackgroundColour: '#ffffff',
        toolboxForegroundColour: '#1c2126',
        flyoutBackgroundColour: '#eef1f6',
        flyoutForegroundColour: '#1c2126',
        flyoutOpacity: 1,
        scrollbarColour: '#c6ccd6',
        insertionMarkerColour: '#3b7dff',
        insertionMarkerOpacity: 0.4,
        markerColour: '#3b7dff',
        cursorColour: '#3b7dff',
      },
    });
  } catch (e) {
    console.warn('theme build failed, fallback to classic', e);
    return Blockly.Themes.Classic;
  }
}

// ---------------- toolbar button wiring ----------------
function doSaveNamed() {
  const input = document.getElementById('saveNameInput');
  const name = input.value.trim();
  if (!name) {
    showError(t('page_save_program_ctx'), new Error(t('page_save_program_need_name')));
    return;
  }
  AlphaBlockly.saveNamed(name);
}
function doLoadNamed() {
  const sel = document.getElementById('savedProgramSelect');
  if (!sel.value) return;
  AlphaBlockly.loadNamed(sel.value);
  document.getElementById('saveNameInput').value = sel.value;
}
function doDeleteNamed() {
  const sel = document.getElementById('savedProgramSelect');
  if (!sel.value) return;
  if (confirm(t('page_confirm_delete', { name: sel.value }))) {
    AlphaBlockly.deleteNamed(sel.value);
  }
}
function onImportFile(input) {
  const file = input.files && input.files[0];
  if (file) AlphaBlockly.importXmlFile(file);
  input.value = '';
}
function onClearWorkspace() {
  if (confirm(t('page_confirm_clear_workspace'))) {
    AlphaBlockly.clearWorkspace();
  }
}

// ---------------- startup ----------------
document.addEventListener('DOMContentLoaded', function () {
  initWorkspace();
  connectWs();
  // 2026-09: the page auto-fetches the action list (actioninfo.txt) and the
  // local music list, fire-and-forget (never blocks the workspace). On failure
  // an error banner + dropdown fallback appear (the old manual buttons are gone, see blockly.html).
  try {
    if (window.AlphaBlockly) {
      window.AlphaBlockly.refreshActionDropdown();
      window.AlphaBlockly.refreshMusicDropdown();
    }
  } catch (e) { /* showError / the fallback handles it, no problem */ }
});

