// Open Alpha2 — client logic (app-actions.js)
// Content: Alpha2 action list / categories / playback, plus the raw AIDL passthrough
// of the Advanced tab (low-level methods, not fully verified).
// All files share the window/global scope (no ES modules). The load order follows
// the <script src="..."> order in index.html. See the comment block at its head.

// ---------------- Actions ----------------

// Categories as returned by the robot's own action list (Alpha2RobotApi row[1] = type).
// The robot's static action-info file uses numeric types (1/2/3/4); some runtime builds
// report the same categories as text instead. Either way each of basic/dance/story/yoga
// gets its own sub-tab with a distinct theme colour; anything not in this whitelist
// (regardless of what the raw type string actually says) falls back to a shared
// "others" sub-tab - the whitelist approach means no unknown category value ever needs
// to be spelled out literally here.
const ACTION_CATEGORIES = [
  { key: "basic",  label: "基本", labelEn: "Basic", color: "#3b7dff" },
  { key: "dance",  label: "跳舞", labelEn: "Dance", color: "#db2777" },
  { key: "story",  label: "故事", labelEn: "Story", color: "#d97706" },
  { key: "yoga",   label: "瑜伽", labelEn: "Yoga",  color: "#16a34a" },
  { key: "others", label: "其他", labelEn: "Other", color: "#6b7280" },
];
// Whitelist: maps both numeric types (static actionInfo.txt format) and text types (runtime format on some units).
const ACTION_CATEGORY_MAP = {
  "1": "basic", "2": "dance", "3": "story", "4": "yoga",
  basic: "basic", dance: "dance", story: "story", yoga: "yoga",
};
let allActions = [];
let activeActionCategory = "basic";

// Action ID -> {main, sub} sub-category table, read from action_classification.json
// (compiled from action_classified.txt). Only actions present in the asset file get a
// sub category. Others still follow their categoryOf() main category but no sub tab
// applies: they mix into the main list, effectively "Other" inside that category. Keep
// the table out of the code: to re-classify, change or swap the json, not app-actions.js.
let actionClassification = {}; // id -> {main, sub}
let activeActionSubCategory = null; // null = show all actions of the main category (no sub category selected)
// Sub category -> color: same palette and same assignment as the sub-category tabs.
// The key carries the main category, so name clashes stay apart. Chip and tab always share a color.
let actionSubColors = {};

function actionSubColorKey(mainCategory, sub) { return mainCategory + "|" + sub; }

/** Main category color (the tab colour; chips without a sub-category mapping use this). */
function actionMainColor(main) {
  for (let i = 0; i < ACTION_CATEGORIES.length; i++) {
    if (ACTION_CATEGORIES[i].key === main) return ACTION_CATEGORIES[i].color;
  }
  return "#6b7280";
}

/** Action chip color: with a sub category follow it (same as the tab); without one, follow the main category. */
function actionChipColor(a) {
  const main = categoryOf(a.type);
  const sub = subCategoryOf(a);
  if (sub) {
    const c = actionSubColors[actionSubColorKey(main, sub)];
    if (c) return c;
  }
  return actionMainColor(main);
}

function loadActionClassification() {
  return fetch("action_classification.json").then(function (r) {
    if (!r.ok) throw new Error("http " + r.status);
    return r.json();
  }).then(function (json) {
    actionClassification = json || {};
  }).catch(function (e) {
    // A missing file or a read failure must not break the whole actions tab. Only the
    // sub-category tabs disappear. The main categories (Basic/Dance/Story/Yoga/Other) still work.
    console.warn("action_classification.json read failed, sub-category tabs will not appear:", e);
    actionClassification = {};
  });
}

function categoryOf(rawType) {
  return ACTION_CATEGORY_MAP[rawType] || "others";
}

/** Get an action's sub-category name (for example "移動類"). null when there is no mapping. */
function subCategoryOf(action) {
  const entry = actionClassification[action.id];
  return entry ? entry.sub : null;
}

/** Get an action's display name: follow the main UI language (uiLang). When that
 *  language has no data (equal names, or one side empty), fall back to the other. No blank chip. */
function displayNameOf(action) {
  if (uiLang === "en") {
    return action.nameEn || action.nameCn;
  }
  return action.nameCn || action.nameEn;
}

function loadActionList() {
  const listEl = document.getElementById("actionList");
  listEl.textContent = uiLang === "en" ? "Loading…" : "載入中…";
  return Promise.all([Alpha2Api.actionList(), loadActionClassification()]).then(function (results) {
    const data = results[0];
    if (!data.ok) {
      listEl.textContent = (uiLang === "en" ? "Error: " : "錯誤: ") + (data.error || data.code);
      return;
    }
    allActions = data.actions || [];
    buildActionSubTabs();
    renderActionList();
  });
}

/** Builds the 5 category sub-tabs (basic/dance/story/yoga/others), each themed with
 *  its own accent colour via a CSS custom property set inline on the button. */
function buildActionSubTabs() {
  const bar = document.getElementById("actionSubTabBar");
  bar.innerHTML = "";
  ACTION_CATEGORIES.forEach(function (c) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (c.key === activeActionCategory ? " active" : "");
    btn.style.setProperty("--sub-tab-color", c.color);
    const count = allActions.filter(function (a) { return categoryOf(a.type) === c.key; }).length;
    btn.textContent = (uiLang === "en" ? c.labelEn : c.label) + " (" + count + ")";
    btn.onclick = function () {
      activeActionCategory = c.key;
      activeActionSubCategory = null; // switching the main category resets the sub-category filter to "All"
      buildActionSubTabs();
      buildActionSubSubTabs();
      renderActionList();
    };
    bar.appendChild(btn);
  });
  buildActionSubSubTabs();
}

// Fixed palette for sub-category tabs (Locomotion/Gestures/...), cycled by index so each
// sub-category gets a distinct, stable colour regardless of which main category it's
// under - deliberately a separate palette from ACTION_CATEGORIES' colours so the two
// tab levels stay visually distinguishable from each other.
const SUB_CATEGORY_COLORS = [
  "#0891b2", "#ca8a04", "#9333ea", "#059669", "#e11d48",
  "#2563eb", "#c2410c", "#4d7c0f", "#be185d", "#0d9488",
];

// English labels for the sub-categories that come from action_classification.json
// (see loadActionClassification() above). The Chinese string is still the filter key
// used everywhere else (subCategoryOf() / activeActionSubCategory) - this table is
// purely for what's shown on the tab when uiLang === "en", so it never needs to touch
// action_classification.json or action_classified.txt (source of truth for the actual
// classification). Add an entry here whenever a new sub value shows up in that file.
const SUB_CATEGORY_LABELS_EN = {
  "移動類":            "Locomotion",
  "手勢類":            "Gestures",
  "頭部類":            "Head",
  "表情 / 互動類":      "Expression / Interaction",
  "全身 / 其他動作":    "Full Body / Other",
  "伸展式":            "Stretch",
  "站立式 / 平衡式":    "Standing / Balance",
  "騎馬式":            "Horse Stance",
  "踢腿 / 動態式":      "Kick / Dynamic",
  "流行 / 節奏舞蹈":    "Pop / Rhythm Dance",
  "兒童歌曲 / 卡通舞蹈": "Kids Songs / Cartoon Dance",
  "品牌 / 客製舞蹈":    "Brand / Custom Dance",
  "中國寓言":          "Chinese Fables",
  "西方寓言 / 故事":    "Western Fables / Stories",
};

/** Get a sub-category display name: follow the main UI language (uiLang). The internal
 *  filter still uses the `sub` key (Chinese). This function only touches the display
 *  layer. No mapping means show the original text, never a blank tab. */
function subCategoryDisplayName(sub) {
  if (uiLang === "en") {
    return SUB_CATEGORY_LABELS_EN[sub] || sub;
  }
  return sub;
}

/** Extract the logic that builds the second-level sub-category tabs (for example the
 *  Locomotion/Gestures/Head tabs under Basic) into a standalone function. A future
 *  second action list can reuse it instead of copying near-identical code. The caller
 *  decides: which bar element, which action array, which main category, which sub
 *  category is selected (+ how to write it back). Tabs appear only for sub-categories
 *  that action_classification.json lists for this main category. Without data, skip
 *  this tab bar and show the flat list. An always-present "All" tab clears the filter.
 *  @param barElId       id of the sub-category tab bar container
 *  @param actions       full action array (allActions)
 *  @param mainCategory  selected main category key (activeActionCategory)
 *  @param getActiveSub  () => selected sub-category (null = all)
 *  @param setActiveSub  (sub) => void, writes back the caller's own activeXxxSubCategory variable
 *  @param onChange      what to do after a new sub-category pick (redraw tab bar + redraw action list)
 */
function buildActionSubSubTabsShared(barElId, actions, mainCategory, getActiveSub, setActiveSub, onChange) {
  const bar = document.getElementById(barElId);
  if (!bar) return;
  bar.innerHTML = "";

  // Sub-category labels, in the order first encountered in the classification file,
  // restricted to actions that belong to the currently active main category.
  const subsInOrder = [];
  actions.forEach(function (a) {
    if (categoryOf(a.type) !== mainCategory) return;
    const sub = subCategoryOf(a);
    if (sub && subsInOrder.indexOf(sub) === -1) subsInOrder.push(sub);
  });

  if (subsInOrder.length === 0) {
    setActiveSub(null);
    return; // this main category has no sub-category data - do not show this tab bar level
  }

  const allBtn = document.createElement("button");
  allBtn.className = "sub-tab-btn" + (getActiveSub() === null ? " active" : "");
  allBtn.textContent = uiLang === "en" ? "All" : "全部";
  allBtn.onclick = function () {
    setActiveSub(null);
    onChange();
  };
  bar.appendChild(allBtn);

  subsInOrder.forEach(function (sub, i) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (sub === getActiveSub() ? " active" : "");
    const subColor = SUB_CATEGORY_COLORS[i % SUB_CATEGORY_COLORS.length];
    actionSubColors[actionSubColorKey(mainCategory, sub)] = subColor;
    btn.style.setProperty("--sub-tab-color", subColor);
    const count = actions.filter(function (a) {
      return categoryOf(a.type) === mainCategory && subCategoryOf(a) === sub;
    }).length;
    btn.textContent = subCategoryDisplayName(sub) + " (" + count + ")";
    btn.onclick = function () {
      setActiveSub(sub);
      onChange();
    };
    bar.appendChild(btn);
  });
}

/** Builds the second-level sub-category tabs (e.g. Locomotion/Gestures/Head... within
 *  Basic) for whichever main category is currently active. Only sub-categories that
 *  actually appear in action_classification.json for this main category show up as
 *  tabs - if the classification file doesn't cover a main category at all (or none
 *  of its actions matched), no sub-tab bar appears and the flat action list shows,
 *  same as before this feature existed. An always-present "All" tab clears the
 *  sub-category filter back to showing everything in the main category. */
function buildActionSubSubTabs() {
  buildActionSubSubTabsShared(
    "actionSubSubTabBar",
    allActions,
    activeActionCategory,
    function () { return activeActionSubCategory; },
    function (sub) { activeActionSubCategory = sub; },
    function () { buildActionSubSubTabs(); renderActionList(); }
  );
}

/** Extract the action chip list build logic into a standalone function for reuse.
 *  @param listElId    id of the action list container
 *  @param filtered    the already filtered action array
 *  @param nameFn      (action) => display name
 *  @param onPick      (action) => void, what a chip click does (fill input + play)
 *  @param emptyText   text to show when there are no actions
 *  @param colorFn     (action) => color code (optional. When set, the chip follows its category via --chip-color)
 */
function renderActionChips(listElId, filtered, nameFn, onPick, emptyText, colorFn) {
  const listEl = document.getElementById(listElId);
  if (!listEl) return;
  if (filtered.length === 0) {
    listEl.textContent = emptyText;
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = nameFn(a);
    if (typeof colorFn === "function") {
      try {
        chip.style.setProperty("--chip-color", colorFn(a));
      } catch (e) {}
    }
    chip.onclick = function () { onPick(a); };
    listEl.appendChild(chip);
  });
}

function renderActionList() {
  const filtered = allActions.filter(function (a) {
    if (categoryOf(a.type) !== activeActionCategory) return false;
    if (activeActionSubCategory !== null && subCategoryOf(a) !== activeActionSubCategory) return false;
    return true;
  });
  renderActionChips("actionList", filtered, displayNameOf, function (a) {
    document.getElementById("actionName").value = a.nameEn || a.nameCn;
    playAction();
  }, (uiLang === "en" ? "(No actions / service not initialized)" : "(沒有動作 / 服務未初始化)"), actionChipColor);
}

function playAction() {
  const name = document.getElementById("actionName").value.trim();
  if (!name) { showError(uiLang === "en" ? "Play action" : "播放動作", uiLang === "en" ? "Enter an action name" : "請輸入動作名稱"); return; }
  return Alpha2Api.actionPlay( { name: name });
}

function stopAction() {
  return Alpha2Api.actionStop();
}

function setActionSpeed() {
  const el = document.getElementById("actionSpeed");
  const v = parseFloat(el.value);
  return Alpha2Api.ubxSpeed({ value: v });
}


