// Open Alpha2 — servo group and calibration data for the Blockly page.
//
// This data deliberately matches SERVO_CALIBRATION / SERVO_NAMES / SERVO_GROUPS
// in app-core.js exactly (see the "Servo calibration" notes there: the min/max/home
// values come from real units measured with the "Gen-2 servo calibration software
// 1.0.0.4", which is hardware calibration, not SDK-native values, so a new unit
// needs a new measurement). It is kept separate instead of including app-core.js
// because blockly.html must not pull in the whole control panel (this tab is designed to stand alone).
//
// ⚠ If the Servo tab (app-servo.js) re-measures calibration, sync this file by hand too.

(function () {
  const SERVO_CALIBRATION = {
    1:  { min: 5,   max: 235, home: 120 },
    2:  { min: 50,  max: 210, home: 120 },
    3:  { min: 55,  max: 185, home: 120 },
    4:  { min: 5,   max: 235, home: 120 },
    5:  { min: 30,  max: 190, home: 120 },
    6:  { min: 55,  max: 185, home: 120 },
    7:  { min: 100, max: 200, home: 120 },
    8:  { min: 20,  max: 220, home: 65  },
    9:  { min: 35,  max: 230, home: 145 },
    10: { min: 35,  max: 215, home: 140 },
    11: { min: 100, max: 190, home: 120 },
    12: { min: 40,  max: 140, home: 120 },
    13: { min: 20,  max: 220, home: 175 },
    14: { min: 10,  max: 205, home: 95  },
    15: { min: 25,  max: 205, home: 100 },
    16: { min: 50,  max: 140, home: 120 },
    17: { min: 95,  max: 125, home: 120 },
    18: { min: 95,  max: 125, home: 120 },
    19: { min: 75,  max: 165, home: 120 },
    20: { min: 105, max: 155, home: 120 },
  };

  // Display names of the 20 servos, copied from SERVO_NAMES in app-core.js (the
  // copy the Servo tab of the main panel uses), so the two sides never drift apart.
  const SERVO_NAMES = {
    1:  { zh: '右肩上下', en: 'R Shoulder Pitch' },
    2:  { zh: '右肩左右', en: 'R Shoulder Roll' },
    3:  { zh: '右肘',     en: 'R Elbow' },
    4:  { zh: '左肩上下', en: 'L Shoulder Pitch' },
    5:  { zh: '左肩左右', en: 'L Shoulder Roll' },
    6:  { zh: '左肘',     en: 'L Elbow' },
    7:  { zh: '右股左右', en: 'R Hip Roll' },
    8:  { zh: '右股上下', en: 'R Hip Pitch' },
    9:  { zh: '右膝',     en: 'R Knee' },
    10: { zh: '右腳掌上下', en: 'R Ankle Pitch' },
    11: { zh: '右腳掌左右', en: 'R Ankle Roll' },
    12: { zh: '左股左右', en: 'L Hip Roll' },
    13: { zh: '左股上下', en: 'L Hip Pitch' },
    14: { zh: '左膝',     en: 'L Knee' },
    15: { zh: '左腳掌上下', en: 'L Ankle Pitch' },
    16: { zh: '左腳掌左右', en: 'L Ankle Roll' },
    17: { zh: '右指',     en: 'R Hand' },
    18: { zh: '左指',     en: 'L Hand' },
    19: { zh: '頭左右',   en: 'Head Yaw' },
    20: { zh: '頭上下',   en: 'Head Pitch' },
  };
  // For display: get the motor name in the current language. t() evaluates at
  // call time, so each call re-reads window.getUiLanguage() (not a fixed
  // module-level value — the same trap that caught LED_COLOURS/LED_PRESETS_HEAD
  // in blockly-blocks.js). This helper caches nothing and reads the dictionary per call.
  function servoDisplayName(id) {
    const entry = SERVO_NAMES[id];
    if (!entry) return (window.t ? window.t('servo_name_fallback', { id: id }) : ('Servo ' + id));
    const lang = (window.getUiLanguage && window.getUiLanguage()) || 'zh';
    return lang === 'zh' ? entry.zh : entry.en;
  }

  // Groups: head / right arm / left arm / right leg / left leg (same as
  // SERVO_GROUPS in app-core.js), one colour per group, so the Blockly category
  // and the block itself follow the grouping. ⚠ The label field is internal
  // reference only: no code reads it for display (verified across all
  // blockly-*.js). Display group names come from the
  // servo_group_head/right_arm/left_arm/right_leg/left_leg keys in blockly-blocks-i18n-data.js via t(). This label only helps a reader match groups.
  const SERVO_GROUPS = [
    { key: 'head',      label: 'Head',  icon: '🧠', ids: [19, 20],             colour: '#7c3aed' },
    { key: 'right-arm', label: 'R Arm', icon: '💪', ids: [1, 2, 3, 17],       colour: '#2563eb' },
    { key: 'left-arm',  label: 'L Arm', icon: '💪', ids: [4, 5, 6, 18],       colour: '#0891b2' },
    { key: 'right-leg', label: 'R Leg', icon: '🦵', ids: [7, 8, 9, 10, 11],   colour: '#ea580c' },
    { key: 'left-leg',  label: 'L Leg', icon: '🦵', ids: [12, 13, 14, 15, 16], colour: '#16a34a' },
  ];

  function groupOfServoId(id) {
    const found = SERVO_GROUPS.filter(function (g) { return g.ids.indexOf(Number(id)) !== -1; })[0];
    return found ? found.key : null;
  }

  function servoDropdownForGroup(groupKey) {
    const g = SERVO_GROUPS.filter(function (x) { return x.key === groupKey; })[0];
    const ids = g ? g.ids : [];
    return ids.map(function (id) {
      return ['#' + id + ' ' + servoDisplayName(id), String(id)];
    });
  }

  /** Clamp a value into [min,max] for the given servo id (string or number).
   *  Mirrors app-core.js clampServoAngle() exactly — this is what guarantees a
   *  Blockly-issued "set angle" can never exceed the calibrated safe range. */
  function clampServoAngle(id, value) {
    const cal = SERVO_CALIBRATION[Number(id)];
    if (!cal) return value;
    return Math.max(cal.min, Math.min(cal.max, value));
  }

  window.ALPHA_SERVO_CALIBRATION = SERVO_CALIBRATION;
  window.ALPHA_SERVO_NAMES = SERVO_NAMES;
  window.ALPHA_SERVO_GROUPS = SERVO_GROUPS;
  window.ALPHA_SERVO_GROUP_OF = groupOfServoId;
  window.ALPHA_SERVO_DROPDOWN_FOR_GROUP = servoDropdownForGroup;
  window.ALPHA_SERVO_CLAMP = clampServoAngle;
})();

