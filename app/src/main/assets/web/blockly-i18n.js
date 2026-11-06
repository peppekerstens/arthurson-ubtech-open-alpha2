// Open Alpha2 — language switch (Chinese/English) for the Blockly page.
//
// This Blockly page (blockly.html) is designed to open in its own tab, so it
// does not require index.html (app-*.js) to be running (see the head note in
// blockly-page.js). It therefore ships its own full language switch,
// independent of app-core.js. Both sides share the same localStorage key
// ("ui_lang"), so a switch in the main control panel is picked up here too.
//
// Two layers are switched here, and both follow the language:
//
//  1. The Blockly official strings (standard blocks such as controls_if/
//     logic_compare/math_arithmetic, plus the workspace context menu entry
//     "Delete 3 blocks" and the undo/redo tooltips). This layer comes from
//     blockly_msg_zh-hant.js / blockly_msg_en.js, both preloaded (see
//     blockly.html). Each one is saved as a snapshot at load time
//     (window.__ALPHA_BLOCKLY_MSG_ZH / __ALPHA_BLOCKLY_MSG_EN), and a
//     language switch writes the chosen snapshot's keys back into Blockly.Msg.
//
//  2. The custom blocks this app defines (alpha_action_play/alpha_speech_tts/
//     alpha_servo_one_head and more, in blockly-blocks.js) plus the toolbox
//     category names (blockly-toolbox.js). This layer uses the
//     window.ALPHA_BLOCK_I18N dictionary and window.t(key) from
//     blockly-blocks-i18n-data.js, called directly in each block's init().
//     But t() evaluates at call time, with no reactive binding, so just
//     flipping uiLang does not change built blocks or the built toolbox object.
//     rebuildWorkspaceForLocale() below forces every block to re-run init(),
//     and window.buildAlphaToolbox() is re-called for a new-language toolbox.
//
//  Exceptions (kept fixed on purpose — see the notes in blockly-blocks.js):
//   - the FieldLabelSerializable variable names of alpha_event_accel_threshold /
//     alpha_event_sonar_triggered (their Chinese label strings). Each value is
//     also the real variable key inside saved XML programs, so translating it
//     would make old programs lose their variables.
//   - the SUBCATEGORY values of the action/ringtone blocks — these are keys of
//     the real data categories in blockly-actions-data.js / blockly-ringtone-data.js
//     (firmware names measured on the unit), not this page's display text.
//   - TTS voice names (xiaofeng / xiaoyan and more): names and IDs, not UI text.

(function () {
  let uiLang = localStorage.getItem('ui_lang') || 'zh';

  function msgTableFor(lang) {
    return lang === 'en' ? window.__ALPHA_BLOCKLY_MSG_EN : window.__ALPHA_BLOCKLY_MSG_ZH;
  }

  function applyBlocklyMsgTable(lang) {
    const table = msgTableFor(lang);
    if (!table) return;
    for (const key in table) {
      if (Object.prototype.hasOwnProperty.call(table, key)) {
        Blockly.Msg[key] = table[key];
      }
    }
  }

  // Blocks already on the canvas render their field text (including the
  // Blockly.Msg strings that built-in blocks use) as SVG text nodes when the
  // block is created, so updating Blockly.Msg alone does not repaint them.
  // Serializing the workspace to XML and parsing it back forces every block
  // to rebuild (safer than hunting for the fields that need new text per
  // block). clearWorkspace() wipes the undo history, an acceptable cost here.
  function rebuildWorkspaceForLocale() {
    const workspace = window.__alphaBlocklyWorkspace;
    if (!workspace) return;
    let xml;
    try {
      xml = Blockly.Xml.workspaceToDom(workspace);
    } catch (e) {
      console.warn('workspaceToDom failed, skip rebuild', e);
      return;
    }
    workspace.clear();
    try {
      Blockly.Xml.domToWorkspace(xml, workspace);
    } catch (e) {
      console.warn('domToWorkspace failed after locale switch', e);
    }
  }

  function updateToggleButtons() {
    document.querySelectorAll('[data-ui-lang-btn]').forEach(function (btn) {
      btn.classList.toggle('active', btn.dataset.uiLangBtn === uiLang);
    });
  }

  // Apply the static UI text of the page frame (header/toolbar/side panel).
  // Same [data-i18n] pattern as applyUiLanguage() in app-core.js, kept
  // identical on purpose. The only difference is the dictionary: this page
  // uses window.ALPHA_BLOCK_I18N/window.t() (it exists independently of
  // index.html and must not depend on the app-core.js I18N object).
  function applyUiTextLocale() {
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      const key = el.dataset.i18n;
      if (!window.ALPHA_BLOCK_I18N || !window.ALPHA_BLOCK_I18N[key]) return;
      const text = window.t(key);
      const attr = el.dataset.i18nAttr;
      if (attr) {
        el.setAttribute(attr, text);
      } else {
        el.textContent = text;
      }
    });
    if (window.ALPHA_BLOCK_I18N && window.ALPHA_BLOCK_I18N.page_title) {
      document.title = window.t('page_title');
    }
  }

  // The toolbox category names and example texts are fixed by t() inside
  // the object at the moment window.buildAlphaToolbox() is called, so call
  // it again to get the new language, then updateToolbox() re-seeds the
  // workspace (official categories such as Logic/Loops rebuild too).
  function refreshToolbox() {
    const workspace = window.__alphaBlocklyWorkspace;
    if (!workspace || !window.buildAlphaToolbox) return;
    try {
      window.ALPHA_TOOLBOX = window.buildAlphaToolbox();
      workspace.updateToolbox(window.ALPHA_TOOLBOX);
    } catch (e) {
      console.warn('updateToolbox failed after locale switch', e);
    }
  }

  window.setUiLanguage = function (lang) {
    if (lang !== 'zh' && lang !== 'en') return;
    uiLang = lang;
    localStorage.setItem('ui_lang', lang);
    applyBlocklyMsgTable(lang);
    updateToggleButtons();
    applyUiTextLocale();
  // ⚠️ Order matters: the action category/option data tables must be updated
  // *before* the workspace/toolbox rebuild, because rebuildWorkspaceForLocale()
  // (the XML roundtrip re-runs block init()) and refreshToolbox() both read
  // window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY and friends to build their
  // dropdowns on the spot. A reversed order rebuilds blocks and the toolbox
  // with the old language, and a second language switch is needed to fix it.
    if (window.rebuildAlphaActionCategories) {
      window.rebuildAlphaActionCategories();
    }
    if (window.rebuildAlphaActionOptionTables) {
      window.rebuildAlphaActionOptionTables();
    }
    rebuildWorkspaceForLocale();
    refreshToolbox();
    // The "-- Saved programs --" dropdown placeholder is outside the
    // [data-i18n] scan (blockly-run.js builds the <option> HTML at run time),
    // so it must be re-rendered explicitly for the new language.
    if (window.AlphaBlockly && window.AlphaBlockly.refreshSavedProgramDropdown) {
      window.AlphaBlockly.refreshSavedProgramDropdown();
    }
    // The undo/cut/zoom button rows are SVG UI components now (see
    // EditFabControls/ZoomFabControls in blockly-run.js), not plain HTML
    // <button> elements, so the [data-i18n] scan in applyUiTextLocale() does
    // not find them (it only queries document.querySelectorAll('[data-i18n]')
    // DOM elements). Call their update explicitly so the <title> tooltips
    // switch too. (The side trigger is plain HTML and follows data-i18n-attr.)
    if (window.AlphaBlockly && window.AlphaBlockly.refreshEditControlsI18n) {
      window.AlphaBlockly.refreshEditControlsI18n();
    }
  };

  window.getUiLanguage = function () { return uiLang; };

  // Choose the language before the workspace starts: Blockly.inject already
  // reads Blockly.Msg when it builds the toolbox flyout. So re-apply the
  // language stored in localStorage early on DOMContentLoaded, no user press needed.
  // The page frame text (data-i18n) can only be applied after
  // DOMContentLoaded (the queried DOM elements must be parsed first), but the
  // Blockly.Msg layer has no such limit: it applies at once (it is read later).
  applyBlocklyMsgTable(uiLang);
  document.addEventListener('DOMContentLoaded', function () {
    updateToggleButtons();
    applyUiTextLocale();
  });
})();
