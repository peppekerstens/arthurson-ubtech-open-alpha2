// Open Alpha2 — Blockly custom block definitions.
// Every block maps to one verified /api/* endpoint in index.html (app-*.js) / MainActivity.handleApi(),
// or it is a flow-control block (repeat/if/wait/variable/event).
//
// This file only defines what each block looks like and which inputs it has. What running a block does
// lives in the blockly-run.js interpreter, not in the Blockly built-in code generator.
// Tree-walking interpretation is used instead of "generate JS then eval" so the highlight of
// "which block is running now" stays live during wait/repeat, and stopping is always safe.

(function () {
  const clr = {
    action: 20,     // orange - actions
    speech: 160,     // green - sound (TTS/ringtone/local music share it)
    servo: 230,     // blue - servo
    led: 290,     // purple - LED
    sensor: 0,       // red - sensors/events
    camera: 65,      // yellow-green - camera
    audio: 200,     // indigo - sound effects
    device: 330,     // pink - device info
    flow: 120,     // grass green - flow control (Blockly convention usually uses brown, but this project keeps one custom color scheme)
    logic: 210,
    loop: 120,
    math: 230,
    text: 160,
    variable: 330,
    procedure: 290,
  };

  // ---------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------
  //
  // Design trade-off: the play-action block waits by default after it sends the API request. It waits until the robot
  // reports, with the action_stop event, that the action truly finished, then the next block runs. This is a deliberate
  // safe default. Action length varies (a few hundred ms to several seconds), and starting the next block immediately
  // easily makes two actions interrupt each other. That looks abrupt and can stress the servo motors. For a
  // fast fire-and-forget chain, set "wait for completion" below to the no-wait choice.

  Blockly.Blocks['alpha_action_play'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('action_play__label'))
        .appendField(new Blockly.FieldTextInput('ACT0'), 'NAME');
      this.appendDummyInput()
        .appendField(t('wait_done__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('wait_done__yes'), 'true'],
          [t('wait_done__no'), 'false'],
        ]), 'WAIT')
        .appendField(t('timeout_seconds__label'))
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_play__tooltip'));
      this.setHelpUrl('');
    }
  };

  // 2026-08 update: the actions toolbox changed from a nested tree ("top category -> 4 sub-categories, each with its own color")
  // to a flat list. Opening "Actions" now shows every action block immediately, with no extra
  // expansion step. With this change, action blocks no longer change color by CATEGORY. The single
  // clr.action color is enough: it is easy to see at a glance "this is an action block", and color no longer
  // needs to separate basic/dance/story/yoga. The CATEGORY dropdown is shown in the block again
  // so the user can pick it. The old trick (the toolbox sub-category presetting CATEGORY) no longer works, because
  // those sub-categories are gone from the toolbox.
  const ACTION_CATEGORY_COLOUR = null; // keep the const name as a no-op; nothing below uses it to look up colors

  // Sub-category list: kept in sync with ALPHA_ACTION_SUBCATEGORIES in blockly-actions-data.js
  // (the sub-categories under each main category, for example basic has movement/gesture/head/
  // expression/interaction/full-body/other actions). With no data, fall back to one "(all)" category,
  // so the block still works even before the data loads.
  function subcategoriesForCategory(catKey) {
    const subs = (window.ALPHA_ACTION_SUBCATEGORIES && window.ALPHA_ACTION_SUBCATEGORIES[catKey]) || [];
    return subs.length ? subs : [t('action_all_subcat')];
  }

  function actionOptionsForSubcategory(catKey, subKey) {
    const bySub = window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY;
    const list = (bySub && bySub[catKey] && bySub[catKey][subKey]) || [];
    if (list.length) return list;
    // Fallback: when the data is not loaded yet (no ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY), use the
    // old flat list without sub-categories, so the block never shows an empty dropdown.
    const flat = (window.ALPHA_ACTION_OPTIONS_BY_CATEGORY && window.ALPHA_ACTION_OPTIONS_BY_CATEGORY[catKey]) || [];
    return flat.length ? flat : [[t('action_no_items'), '']];
  }

  // 2026-08 update: previously one block (alpha_action_play_builtin) + a CATEGORY dropdown picked
  // "basic/dance/story/yoga". Now, following the phone/notification ringtone pattern, split into
  // separate blocks — "Play Basic Action" / "Play Dance Action" / "Play Story Action" / "Play
  // Yoga Action" / "Play Other Action", one block each. The CATEGORY field is gone; only
  // SUBCATEGORY + NAME remain as the two dropdowns. All blocks use the single clr.action
  // color (decided when the toolbox was flattened). CATEGORY is implied by the block type itself,
  // hard-coded in the interpreter (blockly-run.js), and no longer read from a field.
  function makeActionCategoryBlock(blockType, catKey, catI18nKey) {
    Blockly.Blocks[blockType] = {
      init: function () {
        const self = this;
        const defaultSubs = subcategoriesForCategory(catKey);
        const defaultSub = defaultSubs[0];
        const catLabel = t(catI18nKey);

        this.appendDummyInput()
          .appendField(t('action_category__prefix') + catLabel)
          .appendField(t('action_category__subcat'))
          .appendField(new Blockly.FieldDropdown(function () {
            return subcategoriesForCategory(catKey).map(function (s) {
              return [(window.ALPHA_SUB_LABEL ? window.ALPHA_SUB_LABEL(s) : s), s];
            });
          }, function (newSub) {
            // When the sub-category changes, re-filter the NAME dropdown options and select the
            // first action of that sub-category.
            setTimeout(function () {
              const nameField = self.getField('NAME');
              if (nameField) {
                const opts = actionOptionsForSubcategory(catKey, newSub);
                nameField.menuGenerator_ = opts;
                nameField.setValue(opts[0][1]);
              }
            }, 0);
            return newSub;
          }), 'SUBCATEGORY')
          .appendField(t('action_category__name'))
          .appendField(new Blockly.FieldDropdown(function () {
            const block = this.getSourceBlock();
            const sub = block ? block.getFieldValue('SUBCATEGORY') : defaultSub;
            return actionOptionsForSubcategory(catKey, sub || defaultSub);
          }), 'NAME');
        this.appendDummyInput()
          .appendField(t('wait_done__label'))
          .appendField(new Blockly.FieldDropdown([
            [t('wait_done__yes'), 'true'],
            [t('wait_done__no'), 'false'],
          ]), 'WAIT')
          .appendField(t('timeout_seconds__label'))
          .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.action);
        this.setTooltip(t('action_category_tooltip', { cat: catLabel }));
      },
      // When a workspace loads back (XML -> block), check that SUBCATEGORY/NAME are still valid values
      // under this catKey. If not, auto-correct to the first valid sub-category/action, to avoid
      // showing a mismatched pair (same rule as the old alpha_action_play_builtin onchange, only
      // CATEGORY no longer matters here because catKey is fixed).
      onchange: function (e) {
        if (this.workspace && this.workspace.isDragging && this.workspace.isDragging()) return;
        if (this.__alphaFixingConsistency) return;

        const validSubs = subcategoriesForCategory(catKey);
        const curSub = this.getFieldValue('SUBCATEGORY');
        const subOk = validSubs.indexOf(curSub) !== -1;
        const curName = this.getFieldValue('NAME');
        const wantSub = subOk ? curSub : validSubs[0];
        const validNames = actionOptionsForSubcategory(catKey, wantSub);
        const nameOk = validNames.some(function (pair) { return pair[1] === curName; });

        if (!subOk || !nameOk) {
          this.__alphaFixingConsistency = true;
          try {
            const subField = this.getField('SUBCATEGORY');
            if (subField && !subOk) {
              subField.menuGenerator_ = validSubs.map(function (s) { return [s, s]; });
              subField.setValue(wantSub);
            }
            const nameField = this.getField('NAME');
            if (nameField && !nameOk) {
              nameField.menuGenerator_ = validNames;
              nameField.setValue(validNames.length ? validNames[0][1] : '');
            }
          } finally {
            this.__alphaFixingConsistency = false;
          }
        }
      }
    };
  }
  makeActionCategoryBlock('alpha_action_play_basic', 'basic', 'action_cat_basic');
  makeActionCategoryBlock('alpha_action_play_dance', 'dance', 'action_cat_dance');
  makeActionCategoryBlock('alpha_action_play_story', 'story', 'action_cat_story');
  makeActionCategoryBlock('alpha_action_play_yoga', 'yoga', 'action_cat_yoga');
  makeActionCategoryBlock('alpha_action_play_others', 'others', 'action_cat_others');

  Blockly.Blocks['alpha_action_play_dropdown'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('action_play_live__label'))
        .appendField(new Blockly.FieldDropdown(function () {
          return (window.__alphaActionOptions && window.__alphaActionOptions.length)
            ? window.__alphaActionOptions
            : [[t('action_play_live__not_loaded'), '']];
        }), 'NAME');
      this.appendDummyInput()
        .appendField(t('wait_done__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('wait_done__yes'), 'true'],
          [t('wait_done__no'), 'false'],
        ]), 'WAIT')
        .appendField(t('timeout_seconds__label'))
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_play_live__tooltip'));
    }
  };

  Blockly.Blocks['alpha_action_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('action_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_stop__tooltip'));
    }
  };

  Blockly.Blocks['alpha_action_wait_done'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('action_wait_done__prefix'))
        .appendField(new Blockly.FieldNumber(15, 0, 120), 'TIMEOUT')
        .appendField(t('action_wait_done__suffix'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_wait_done__tooltip'));
    }
  };



  // ---------------------------------------------------------------------
  // Sound: TTS / system ringtone / local music
  // (2026-09: the toolbox category changed its name from "voice" to "sound", but the block type names stay
  // alpha_speech_* unchanged — old .xml saves use these type names, and renaming would break loading.)
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_speech_tts'] = {
    init: function () {
      this.appendValueInput('TEXT')
        .setCheck('String')
        .appendField(t('speech_tts__label'))
        .appendField(new Blockly.FieldDropdown([
          // 2026-09: keep only android — the body no longer has alpha2services, and nuance/iflytek
          // return NOT_INIT through speech_startTTS and stay silent all the time (see RobotStub).
          // An old save with ENGINE=nuance/iflytek falls back to the first entry, android, and still speaks.
          [t('speech_tts__engine_android'), 'android'],
        ]), 'ENGINE');
      this.appendDummyInput()
        .appendField(t('speech_tts__voice_label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_tts__voice_default'), ''],
          ['catherine', 'catherine'],
          ['john', 'john'],
          ['小峯 xiaofeng', 'xiaofeng'],
          ['小欣 xiaoyan', 'xiaoyan'],
        ]), 'VOICE');
      this.setInputsInline(false);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_tts__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('speech_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('/api/speech/stop');
    }
  };

  // 2026-09: the three-in-one "stop playback" block — pick one: TTS / system ringtone / local music.
  // It replaces the three separate stop blocks alpha_speech_stop / alpha_speech_ringtone_stop / alpha_music_stop.
  // The toolbox shows only this unified block; the alpha_speech_stop and
  // alpha_music_stop definitions and interpreter cases stay for old .xml files,
  // while alpha_speech_ringtone_stop is fully removed (covered by TYPE=ringtone).
  Blockly.Blocks['alpha_media_stop'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('media_stop__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('media_stop__type_tts'), 'tts'],
          [t('media_stop__type_ringtone'), 'ringtone'],
          [t('media_stop__type_music'), 'music'],
        ]), 'TYPE');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('media_stop__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_set_mic'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('speech_set_mic__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_set_mic__release'), 'true'],
          [t('speech_set_mic__take'), 'false'],
        ]), 'WAKE');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_set_mic__tooltip'));
    }
  };

  // Removed 2026-09: alpha_speech_start_asr / alpha_speech_set_voice /
  // alpha_speech_set_language / alpha_speech_self_interrupt — the matching backend
  // endpoints no longer exist (a call only gets 404), so the definitions, toolbox, i18n, and run cases were removed together.

  // Built-in Android phone ringtones / notification sounds — the list now lives in blockly-ringtone-data.js
  // (captured once from a real device over adb and made static; no more live /api/audio/ringtones/list queries).
  //
  // 2026-08 update: previously one block (alpha_speech_ringtone) + a TYPE dropdown picked
  // "phone/notification". Now split into two separate blocks — "Play Phone Ringtone" and "Play Notification
  // Ringtone" — one each, with no TYPE field anymore. Playback now uses title (not index) and sends it to
  // /api/audio/ringtones/play_by_title; the app resolves it with findRingtoneByTitle() and returns a
  // Uri (the same reliable mechanism as the camera shutter/stop sounds — see the
  // findRingtoneByTitle() javadoc in MainActivity.java). Nothing depends on whether RingtoneManager cursor index
  // ordering matches across devices.
  function ringtoneDropdownOptions(fixedType) {
    const titles = (window.ALPHA_RINGTONE_TITLES && window.ALPHA_RINGTONE_TITLES[fixedType]) || [];
    if (!titles.length) return [[t('ringtone__not_loaded'), '']];
    return titles.map(function (title) { return [title, title]; });
  }
  function makeRingtoneBlock(blockType, fixedType, labelKey) {
    Blockly.Blocks[blockType] = {
      init: function () {
        this.appendDummyInput()
          .appendField(t(labelKey))
          .appendField(t('ringtone__sound_label'))
          .appendField(new Blockly.FieldDropdown(function () {
            return ringtoneDropdownOptions(fixedType);
          }), 'TITLE');
        this.appendValueInput('DURATION').setCheck('Number')
          .appendField(t('ringtone__play_label'));
        this.appendDummyInput().appendField(t('ringtone__seconds_suffix'));
        this.setInputsInline(true);
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.speech);
        this.setTooltip(t('ringtone__tooltip', { type: t(fixedType === 'notification' ? 'ringtone__type_notification' : 'ringtone__type_phone') }));
      }
    };
  }
  makeRingtoneBlock('alpha_speech_ringtone_phone', 'ringtone', 'ringtone_phone__label');
  makeRingtoneBlock('alpha_speech_ringtone_notification', 'notification', 'ringtone_notification__label');

  // Removed 2026-09: alpha_speech_ringtone_stop — replaced by the three-in-one
  // alpha_media_stop (TYPE=ringtone), see the alpha_media_stop definition above.

  // ---------------------------------------------------------------------
  // Local music (same /api/audio/local_music/* endpoints as the Music page).
  //
  // The track dropdown is a live list: refreshMusicDropdown() in blockly-run.js fetches it once when the page
  // opens (the top-bar "🔄 Get music list" button re-fetches it), and stores it in
  // window.__alphaMusicOptions, the same way as alpha_action_play_dropdown
  // (window.__alphaActionOptions). Before the fetch completes, a fallback option is shown.
  // ---------------------------------------------------------------------
  Blockly.Blocks['alpha_music_play'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('music__label'))
        .appendField(t('music__track_label'))
        .appendField(new Blockly.FieldDropdown(function () {
          return (window.__alphaMusicOptions && window.__alphaMusicOptions.length)
            ? window.__alphaMusicOptions
            : [[t('music__not_loaded'), '']];
        }), 'NAME');
      // Two independent switches, matching the two different checkboxes on the Music page:
      //   Dance-with-music FILLER → /api/audio/local_music/filler_action/set (random actions)
      //   Beat lights      DISCO → /api/audio/local_music/disco/set (the eyes act as a metronome)
      this.appendDummyInput()
        .appendField(t('music__filler_label'))
        .appendField(new Blockly.FieldDropdown([
          [t('toggle_off'), 'false'],
          [t('toggle_on'), 'true'],
        ]), 'FILLER');
      this.appendDummyInput()
        .appendField(t('music__disco_label'))
        .appendField(new Blockly.FieldDropdown([
          [t('toggle_off'), 'false'],
          [t('toggle_on'), 'true'],
        ]), 'DISCO');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('music__tooltip'));
    }
  };

  Blockly.Blocks['alpha_music_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('music_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('music_stop__tooltip'));
    }
  };

  // Removed 2026-09: alpha_music_disco / alpha_music_pause /
  // alpha_music_resume — the user asked for a smaller toolbox. The play block's FILLER and
  // DISCO fields now control dance-with-music and beat lights. The definitions and interpreter cases
  // were removed together; an old .xml with these types shows an unknown block (rare, accepted).

  // ---------------------------------------------------------------------
  // Servo (20 motors) + Sonar
  // ---------------------------------------------------------------------

  // The real SERVO_NAMES / SERVO_GROUPS / SERVO_CALIBRATION data lives in the separate file
  // blockly-servo-data.js (kept in sync with app-core.js, see that file's header comment). Here we only read
  // window.ALPHA_SERVO_* and never keep a second copy that could drift and be wrong.
  // 2026-08 update: the servo toolbox changed from a nested tree ("top category -> 5 body-part sub-categories, each with its own color")
  // to a flat list. Opening "Servo" now shows every servo block immediately, with no extra expansion step.
  // With this change, servo blocks no longer change color by body part; the fixed clr.servo color is enough.
  // servoGroupColour() stays only as a no-op fallback (always returns clr.servo), so nothing
  // that calls it needs to change.
  function servoGroups() {
    return (window.ALPHA_SERVO_GROUPS && window.ALPHA_SERVO_GROUPS.length) ? window.ALPHA_SERVO_GROUPS : [
      { key: 'head', label: t('servo_group_head') },
      { key: 'right-arm', label: t('servo_group_right_arm') },
      { key: 'left-arm', label: t('servo_group_left_arm') },
      { key: 'right-leg', label: t('servo_group_right_leg') },
      { key: 'left-leg', label: t('servo_group_left_leg') },
    ];
  }
  function servoGroupColour(key) {
    return clr.servo;
  }
  function servoHomeAngle(id) {
    const cal = window.ALPHA_SERVO_CALIBRATION && window.ALPHA_SERVO_CALIBRATION[Number(id)];
    return cal ? cal.home : 120;
  }
  function servoDropdownForGroup(key) {
    if (window.ALPHA_SERVO_DROPDOWN_FOR_GROUP) return window.ALPHA_SERVO_DROPDOWN_FOR_GROUP(key);
    return [[t('servo_data_not_loaded'), '']];
  }

  // 2026-08 update: previously one block (alpha_servo_one) + a GROUP dropdown picked
  // "head/right arm/left arm/right leg/left leg". Now, following the phone/notification ringtone and action-category patterns,
  // split into 5 separate blocks — "Head Servo" / "Right Arm Servo" / "Left Arm Servo" / "Right Leg Servo" / "Left
  // Leg Servo" — one each, with no GROUP field; only ID (motor) + ANGLE + TIME remain.
  // All five use the single clr.servo color. GROUP is implied by the block type itself
  // and is hard-coded in the interpreter (blockly-run.js), no longer read from a field.
  function makeServoGroupBlock(blockType, groupKey, groupLabelKey, groupIcon) {
    Blockly.Blocks[blockType] = {
      init: function () {
        const self = this;
        const defaultIds = servoDropdownForGroup(groupKey);
        const defaultId = defaultIds.length ? defaultIds[0][1] : '19';
        const groupLabel = t(groupLabelKey);

        // 2026-08 bugfix: this flag separates "the block was just created by init(), or XML is loading field
        // values" from "the user really picked a dropdown in the workspace". Without this guard,
        // Blockly.Xml.domToBlock() fired the ID dropdown validator below while an import restored the ID field
        // (even when the value matched the default). That validator's setTimeout(0)
        // callback then overwrote the freshly loaded ANGLE value, which was already correct,
        // with servoHomeAngle(newId) (the homepoint). The XML loader calls setFieldValue() on
        // every field synchronously, but this auto-set-homepoint side effect uses setTimeout(0) and becomes
        // async, so it ran only after the loader finished setting all fields, and reset ANGLE to a homepoint number such as 120.
        // That turned the whole imported program into the standing-still pose.
        // Now the validator performs the auto-homepoint side effect only after the block
        // finished init() (self.servoBlockReady_ = true).
        // Inside domToBlock(), every field set happens fast, during or right after init(), inside the synchronous flow,
        // so it always completes before this setTimeout(0) callback runs. After that, when the user
        // really picks a dropdown in the workspace, the flag is already true,
        // and only then does the auto-set homepoint side effect above fire. This flag keeps
        // "programmatic load" apart from "the user picking a dropdown by hand".
        self.servoBlockReady_ = false;

        this.appendDummyInput()
          .appendField((groupIcon ? groupIcon + ' ' : '') + groupLabel + t('servo_group__motor_suffix'))
          .appendField(new Blockly.FieldDropdown(function () {
            return servoDropdownForGroup(groupKey);
          }, function (newId) {
            // After the user picks a different motor in the same group, calibrate ANGLE right away to
            // that motor's own homepoint — home is not always 120 (for example right leg #8 home=65,
            // #9 home=145, left leg #13 home=175). Do this side effect only when the
            // block is ready (a real user action, not an XML import in progress),
            // or an import would overwrite the freshly restored
            // ANGLE value with the homepoint.
            if (self.servoBlockReady_) {
              setTimeout(function () {
                const angleField = self.getField('ANGLE');
                if (angleField && newId) angleField.setValue(servoHomeAngle(newId));
              }, 0);
            }
            return newId;
          }), 'ID');
        this.appendDummyInput()
          .appendField(t('servo_group__angle_label'))
          .appendField(new Blockly.FieldNumber(servoHomeAngle(defaultId), 0, 255, 1, function (newVal) {
            // Field-level validator: clamp the angle for the selected motor to that motor's calibration min/max
            // range, so no angle can be sent that would hit a mechanical limit (see the same safety design in
            // clampServoAngle() in app-core.js; here the UI blocks most cases early, and blockly-run.js clamps
            // once more at execution time as a failsafe).
            const block = this.getSourceBlock();
            const id = block ? block.getFieldValue('ID') : null;
            if (id && window.ALPHA_SERVO_CLAMP) return window.ALPHA_SERVO_CLAMP(id, Math.round(newVal));
            return Math.round(newVal);
          }), 'ANGLE')
          .appendField(t('servo_group__time_label'))
          .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.servo);
        this.setTooltip(t('servo_group__tooltip', { group: groupLabel }));

        // Use setTimeout(0) to defer to the next event loop tick before the flag becomes
        // true. Blockly.Xml.domToBlock() calls setFieldValue() on fields such as ID / ANGLE / TIME
        // all inside the synchronous flow that returns from this init() call into
        // domToBlock(), so those calls always finish before this setTimeout(0) callback runs. Later, when the user
        // really picks a dropdown in the workspace, the flag is already true,
        // and only then does the auto-set homepoint side effect above
        // fire.
        setTimeout(function () { self.servoBlockReady_ = true; }, 0);
      }
    };
  }
  makeServoGroupBlock('alpha_servo_one_head', 'head', 'servo_group_head', '🧠');
  makeServoGroupBlock('alpha_servo_one_right_arm', 'right-arm', 'servo_group_right_arm', '💪');
  makeServoGroupBlock('alpha_servo_one_left_arm', 'left-arm', 'servo_group_left_arm', '💪');
  makeServoGroupBlock('alpha_servo_one_right_leg', 'right-leg', 'servo_group_right_leg', '🦵');
  makeServoGroupBlock('alpha_servo_one_left_leg', 'left-leg', 'servo_group_left_leg', '🦵');

  Blockly.Blocks['alpha_servo_all'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_all__label'));
      this.appendValueInput('ANGLES').setCheck('String');
      this.appendDummyInput()
        .appendField(t('servo_group__time_label'))
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_all__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_all_helper'] = {
    // Makes it easy to assemble 20 numbers into one CSV string, so the user does not type the commas by hand
    init: function () {
      this.appendDummyInput().appendField(t('servo_helper__label'));
      for (let i = 1; i <= 20; i++) {
        if ((i - 1) % 4 === 0) this.appendDummyInput();
        this.appendValueInput('A' + i).setCheck('Number')
          .appendField('#' + i);
      }
      this.setInputsInline(true);
      this.setOutput(true, 'String');
      this.setColour(clr.servo);
      this.setTooltip(t('servo_helper__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_home'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_home__label'))
        .appendField(t('servo_group__time_label'))
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_home__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_sonar'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_sonar__label'))
        .appendField(new Blockly.FieldNumber(30, 0, 999, 1), 'DIST')
        .appendField(t('servo_sonar__off_hint'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_sonar__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // LED
  // ---------------------------------------------------------------------

  // Warning: these 3 were originally module-level const, evaluated once with t() at first load,
  // and they kept that language forever (the same trap as module-level static arrays such as
  // ACTION_CATEGORIES/LED_PRESETS). After a language switch, even an XML round-trip that re-ran init(),
  // init() still got the same old-language array reference; it never changed. Rewrite them as functions,
  // called fresh inside every init(),
  // so each call returns the current-language version.
  function ledColours() {
    return [
      [t('led_colour_red'), '1'], [t('led_colour_green'), '2'], [t('led_colour_blue'), '3'], [t('led_colour_yellow'), '4'],
      [t('led_colour_purple'), '5'], [t('led_colour_cyan'), '6'], [t('led_colour_white'), '7'],
    ];
  }
  function ledPresetsHead() {
    return [
      [t('led_preset_on'), 'long'], [t('led_preset_flash'), 'flash'], [t('led_preset_breathe'), 'breathe'],
      [t('led_preset_chase'), 'chase'], [t('led_preset_dual'), 'dual'], [t('led_preset_stop'), 'stop'],
    ];
  }
  function ledPresetsEye() {
    return [
      [t('led_preset_on'), 'long'], [t('led_preset_flash'), 'flash'],
      [t('led_preset_chase'), 'chase'], [t('led_preset_dual'), 'dual'], [t('led_preset_stop'), 'stop'],
    ];
  }

  Blockly.Blocks['alpha_led_head'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_head__label'))
        .appendField(new Blockly.FieldDropdown(ledPresetsHead()), 'PRESET')
        .appendField(t('led__colour_label'))
        .appendField(new Blockly.FieldDropdown(ledColours()), 'COLOR')
        .appendField(t('led__brightness_label'))
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_head__tooltip'));
    }
  };

  Blockly.Blocks['alpha_led_eye'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_eye__label'))
        .appendField(new Blockly.FieldDropdown(ledPresetsEye()), 'PRESET')
        .appendField(t('led__colour_label'))
        .appendField(new Blockly.FieldDropdown(ledColours()), 'COLOR')
        .appendField(t('led__brightness_label'))
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_eye__tooltip'));
    }
  };

  Blockly.Blocks['alpha_led_mouth'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_mouth__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('led_preset_breathe_mouth'), 'on'],
          [t('led_preset_off'), 'off'],
        ], function (newMode) {
          const block = this.getSourceBlock();
          if (block) {
            const speedInput = block.getInput('SPEED_ROW');
            if (speedInput) speedInput.setVisible(newMode === 'on');
            if (block.rendered) block.render();
          }
          return newMode;
        }), 'MODE');
      this.appendDummyInput('SPEED_ROW')
        .appendField(t('led__speed_label'))
        .appendField(new Blockly.FieldNumber(150, 100, 1000, 1), 'SPEED');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_mouth__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // 2026-08 update: the device-info category (battery level/charging/WiFi IP/Bluetooth/UUID query/
  // system status) and the camera/audio category (snapshot/resolution/test tone/speaker streaming) were removed completely,
  // including both the block definitions and the interpreter cases (see the matching notes in blockly-run.js).
  // / blockly-toolbox.js.
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // Events (WebSocket) — hat blocks that start an event-driven flow: "when event XXX happens".
  // ---------------------------------------------------------------------

  // Sensor switches — the two accel/sonar "trigger" hat blocks (below,
  // alpha_event_accel_threshold / alpha_event_sonar_triggered) only listen.
  // They do not switch the sensor on by themselves. The sensor must already be on before any WebSocket event arrives.
  // Before this, Blockly had no block to turn accel (/api/alpha2/accelerometer/set)
  // or sonar (/api/alpha2/servo/sonar, distance=0=off / distance>0=on with threshold)
  // on or off, and users had to remember to open the Sensors page on the main control panel first. These two blocks fix that.
  Blockly.Blocks['alpha_sensor_accel_toggle'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('sensor_accel_toggle__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip(t('sensor_accel_toggle__tooltip'));
    }
  };

  Blockly.Blocks['alpha_sensor_sonar_toggle'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('sensor_sonar_toggle__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON')
        .appendField(t('sensor_sonar_toggle__dist'))
        .appendField(new Blockly.FieldNumber(30, 0, 100, 1), 'DIST');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip(t('sensor_sonar_toggle__tooltip'));
    }
  };

  // PIR human sensor switch — maps to /api/alpha2/pir/set?on=true|false (chest_setPirSensorEnabled(),
  // confirmed working on real hardware as cmd=72, see AIDL_REFERENCE_ALPHA2.md). There is no distance threshold to set (the hardware
  // only gives on/off, no sonar-style distance parameter), so this block has one dropdown and is
  // simpler than the sonar toggle. Like sonar, switch this on first, or the "when PIR detects..." hat
  // block below never receives alpha2_pir_state events.
  Blockly.Blocks['alpha_sensor_pir_toggle'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('sensor_pir_toggle__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip(t('sensor_pir_toggle__tooltip'));
    }
  };

  // Accelerometer / sonar "trigger" hat blocks — the user sets the threshold directly, close to the "trigger" meaning
  // (not "run when the event arrives" but "run when the event arrives and the value passes the threshold"),
  // so the logic lives in blockly-run.js with dedicated accelHandlers/sonarHandlers.
  Blockly.Blocks['alpha_event_accel_threshold'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('event_accel__label'))
        .appendField(new Blockly.FieldDropdown([[t('event_accel__axis_x'), 'x'], [t('event_accel__axis_y'), 'y'], [t('event_accel__axis_z'), 'z']]), 'AXIS')
        .appendField(new Blockly.FieldDropdown([[t('event_accel__cmp_gt'), 'gt'], [t('event_accel__cmp_lt'), 'lt']]), 'CMP')
        // 2026-08 fix: min was 0 before, but the comparison logic in blockly-run.js below
        // compares Math.abs(reading) against the threshold, so a user should be able to type a negative
        // threshold directly (for example to mean "tilt in the negative X direction" only, or just to show a
        // negative number on screen). With min=0 the field could never hold a
        // minus sign: FieldNumber clamped it back to 0 at once. Now min is -20, symmetric with
        // max 20, so the user can type negative numbers freely.
        .appendField(new Blockly.FieldNumber(2, -20, 20, 0.1), 'THRESHOLD')
        .appendField(t('event_accel__trigger_suffix'));
      this.appendDummyInput()
        .appendField(t('event_accel__store_prefix'))
        // 2026-08 update: previously this used Blockly.FieldVariable, which attached Blockly's built-in
        // "rename variable / delete variable" context menu. But this variable is only a key in
        // the simple variables Map inside blockly-run.js (see the getFieldValue
        // ('VAR') usage). It never goes through the official Blockly workspace variable model,
        // so "rename/delete" means nothing to the user (a rename only affects this block; no second
        // block shares the name). It only added confusion. Switch to FieldLabelSerializable:
        // display-only text, not editable, but the value is still stored in the XML
        // (field serialization) so getFieldValue('VAR') can read it, and the logic
        // below needs no change at all.
        //
        // Warning: i18n deliberately skips this field. Its display text is also the actual key of the
        // variables Map in blockly-run.js (the value stored in the XML), not only a UI label. If it
        // switched to English with the language, variable names in saved or exported .xml programs would no
        // longer match the blocks made by the new code, and loading an old program could not read the variable.
        // So it stays Chinese and does not follow uiLang, which protects existing programs/XML.
        .appendField(new Blockly.FieldLabelSerializable('加速度計讀數'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip(t('event_accel__tooltip'));
    }
  };

  Blockly.Blocks['alpha_event_sonar_triggered'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('event_sonar__label'))
        .appendField(t('event_sonar__store_prefix'))
        // Same reasoning as alpha_event_accel_threshold: this is only a key in the
        // blockly-run.js variables Map. Do not use the Blockly official variable model's
        // "rename/delete" UI; FieldLabelSerializable is used instead.
        // Warning: i18n deliberately skips it, same reason as above (the value is also the variable
        // key; letting it follow the language would scatter old XML).
        .appendField(new Blockly.FieldLabelSerializable('聲納資料'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip(t('event_sonar__tooltip'));
    }
  };

  // PIR "trigger" hat block — maps to the alpha2_pir_state event (payload is only
  // {triggered: true/false}, see the RobotEventReceiver/registerAlpha2PirAlertListener
  // comment). Unlike sonar, which triggers only in the one "far -> near" direction, for PIR the user asked that
  // both "detected" and "cleared" be selectable (the STATE dropdown). Both are
  // edge-triggered (the state must change from the last one to count once; a constant state does not
  // repeat), matching the pirHandlers implementation in blockly-run.js.
  Blockly.Blocks['alpha_event_pir_triggered'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('event_pir__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('event_pir__state_detected'), 'detected'],
          [t('event_pir__state_cleared'), 'cleared'],
        ]), 'STATE');
      this.appendDummyInput()
        .appendField(t('event_pir__store_prefix'))
        // Same reasoning as event_sonar__var, see the comment block above.
        // Warning: i18n deliberately skips it, same reason as above.
        .appendField(new Blockly.FieldLabelSerializable('PIR資料'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip(t('event_pir__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // Flow control: wait / print message (log) / repeat-count limit protection
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_wait_seconds'] = {
    init: function () {
      this.appendValueInput('SECONDS').setCheck('Number')
        .appendField(t('wait_seconds__label'));
      this.appendDummyInput().appendField(t('wait_seconds__suffix'));
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('wait_seconds__tooltip'));
    }
  };

  Blockly.Blocks['alpha_log'] = {
    init: function () {
      this.appendValueInput('MSG').setCheck(null)
        .appendField(t('log__label'));
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('log__tooltip'));
    }
  };

  Blockly.Blocks['alpha_stop_program'] = {
    init: function () {
      this.appendDummyInput().appendField(t('stop_program__label'));
      this.setPreviousStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('stop_program__tooltip'));
    }
  };

})();

