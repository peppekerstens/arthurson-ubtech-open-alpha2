// Open Alpha2 — Toolbox definition (Blockly JSON format).
// It has 12 categories: control flow first (most used), then the API groups,
// and last the Blockly standards: logic/loops/math/text/variables/functions.
//
// Wrapping this in a function (not a one-shot object literal) is for the
// language switch: category names and example texts read
// window.ALPHA_BLOCK_I18N through t(), and t() fixes its result the moment
// this file loads (JS has no lazy evaluation). Assigning a static object once
// would leave even updateToolbox() with an old-language snapshot after a
// switch. window.buildAlphaToolbox() lets blockly-i18n.js re-call it during a
// switch to get the new language. window.ALPHA_TOOLBOX keeps "the last built
// version" so initWorkspace() (blockly-page.js) can read it on first injection.
window.buildAlphaToolbox = function () {
  return {
  kind: 'categoryToolbox',
  contents: [
    {
      kind: 'category', name: t('toolbox_cat_flow'), colour: '120',
      contents: [
        { kind: 'block', type: 'alpha_wait_seconds', inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1 } } } } },
        { kind: 'block', type: 'alpha_log', inputs: { MSG: { shadow: { type: 'text', fields: { TEXT: t('toolbox_default_msg') } } } } },
        { kind: 'block', type: 'alpha_stop_program' },
        { kind: 'sep' },
        { kind: 'block', type: 'controls_if' },
        { kind: 'block', type: 'controls_repeat_ext', inputs: { TIMES: { shadow: { type: 'math_number', fields: { NUM: 5 } } } } },
        { kind: 'block', type: 'controls_whileUntil' },
        { kind: 'block', type: 'controls_for', inputs: {
          FROM: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
          TO: { shadow: { type: 'math_number', fields: { NUM: 10 } } },
          BY: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
        } },
        { kind: 'block', type: 'controls_flow_statements' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_events'), colour: '0',
      contents: [
        { kind: 'block', type: 'alpha_sensor_accel_toggle', fields: { ON: 'true' } },
        { kind: 'block', type: 'alpha_sensor_sonar_toggle', fields: { ON: 'true' } },
        { kind: 'block', type: 'alpha_sensor_pir_toggle', fields: { ON: 'true' } },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_event_accel_threshold' },
        { kind: 'block', type: 'alpha_event_sonar_triggered' },
        { kind: 'block', type: 'alpha_event_pir_triggered' },
      ]
    },
    {
      // 2026-08 update: the one alpha_action_play_builtin block (with a
      // CATEGORY dropdown) was split into separate blocks (basic/dance/story/yoga/others),
      // following the phone/notification ringtone pattern (see
      // makeActionCategoryBlock() in blockly-blocks.js). The category keeps one
      // colour (colour: '20', same as clr.action), so one click shows every action block.
      kind: 'category', name: t('toolbox_cat_action'), colour: '20',
      contents: [
        { kind: 'block', type: 'alpha_action_play_basic' },
        { kind: 'block', type: 'alpha_action_play_dance' },
        { kind: 'block', type: 'alpha_action_play_story' },
        { kind: 'block', type: 'alpha_action_play_yoga' },
        { kind: 'block', type: 'alpha_action_play_others' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_action_play' },
        { kind: 'block', type: 'alpha_action_play_dropdown' },
        { kind: 'block', type: 'alpha_action_stop' },
        { kind: 'block', type: 'alpha_action_wait_done' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_speech'), colour: '160',
      contents: [
        { kind: 'block', type: 'alpha_speech_tts', inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_tts_default_shadow') } } } } },
        // 2026-09: one three-in-one stop (TTS/ringtone/music) replaces the three old stop blocks.
        // (blockly-blocks.js alpha_media_stop)。alpha_speech_stop /
        // The alpha_music_stop definition and case are kept for old .xml files.
        { kind: 'block', type: 'alpha_media_stop' },
        { kind: 'block', type: 'alpha_speech_set_mic' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_speech_ringtone_phone', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 10 } } } } },
        { kind: 'block', type: 'alpha_speech_ringtone_notification', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 5 } } } } },
        { kind: 'sep' },
        // Added 2026-09: local music (same /api/audio/local_music/* set as Music tab).
        // The track dropdown is a live list, fetched on page open (see
        // refreshMusicDropdown in blockly-page.js + blockly-run.js). Two switches:
        // dance-along FILLER (filler_action random actions) + rhythm light DISCO.
        // Stop goes through alpha_media_stop (TYPE=music). Pause/resume were removed.
        { kind: 'block', type: 'alpha_music_play' },
      ]
    },
    {
      // 2026-08 update: the one alpha_servo_one block (with a GROUP dropdown) was
      // split into 5 blocks (head/right arm/left arm/right leg/left leg), following
      // the phone/notification ringtone and action-category pattern (see
      // makeServoGroupBlock() in blockly-blocks.js). The category keeps one colour
      // (colour: '230', same as clr.servo), so one click shows every servo block.
      kind: 'category', name: t('toolbox_cat_servo'), colour: '230',
      contents: [
        { kind: 'block', type: 'alpha_servo_one_head' },
        { kind: 'block', type: 'alpha_servo_one_right_arm' },
        { kind: 'block', type: 'alpha_servo_one_left_arm' },
        { kind: 'block', type: 'alpha_servo_one_right_leg' },
        { kind: 'block', type: 'alpha_servo_one_left_leg' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_servo_home' },
        { kind: 'block', type: 'alpha_servo_all', inputs: { ANGLES: { shadow: { type: 'text', fields: { TEXT: '120,120,120,120,120,120,120,65,145,140,120,120,175,95,100,120,120,120,120,120' } } } } },
        { kind: 'block', type: 'alpha_servo_all_helper' },
        { kind: 'block', type: 'alpha_servo_sonar' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_led'), colour: '290',
      contents: [
        { kind: 'block', type: 'alpha_led_head' },
        { kind: 'block', type: 'alpha_led_eye' },
        { kind: 'block', type: 'alpha_led_mouth' },
      ]
    },
    // 2026-08 update: the camera/sound and device-info categories were removed
    // entirely, and their block definitions and interpreter cases too (see
    // blockly-blocks.js / blockly-run.js). The sonar/accel sensor switches and
    // their events stay in the "🔔 Events" category above.
    {
      kind: 'category', name: t('toolbox_cat_logic'), colour: '%{BKY_LOGIC_HUE}',
      contents: [
        { kind: 'block', type: 'logic_compare' },
        { kind: 'block', type: 'logic_operation' },
        { kind: 'block', type: 'logic_negate' },
        { kind: 'block', type: 'logic_boolean' },
        { kind: 'block', type: 'logic_null' },
        { kind: 'block', type: 'logic_ternary' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_math'), colour: '%{BKY_MATH_HUE}',
      contents: [
        { kind: 'block', type: 'math_number', fields: { NUM: 0 } },
        { kind: 'block', type: 'math_arithmetic' },
        { kind: 'block', type: 'math_single' },
        { kind: 'block', type: 'math_random_int', inputs: {
          FROM: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
          TO: { shadow: { type: 'math_number', fields: { NUM: 100 } } },
        } },
        { kind: 'block', type: 'math_modulo' },
        { kind: 'block', type: 'math_round' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_text'), colour: '%{BKY_TEXTS_HUE}',
      contents: [
        { kind: 'block', type: 'text', fields: { TEXT: '' } },
        { kind: 'block', type: 'text_join' },
        { kind: 'block', type: 'text_length' },
        { kind: 'block', type: 'text_isEmpty' },
        { kind: 'block', type: 'text_indexOf' },
        { kind: 'block', type: 'text_charAt' },
        { kind: 'block', type: 'text_print' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_variables'), colour: '%{BKY_VARIABLES_HUE}', custom: 'VARIABLE'
    },
    {
      kind: 'category', name: t('toolbox_cat_procedures'), colour: '%{BKY_PROCEDURES_HUE}', custom: 'PROCEDURE'
    },
    {
      // Added 2026-08: the "Examples" category holds pre-assembled block chains,
      // so a drag from the toolbox gives a finished next-chain — run it at once
      // or edit it as a starting point. The toolbox JSON nests blocks: under the
      // first block, "next": { "block": {...} } chains down, matching Blockly's
      // internal next-connection statement chain (officially supported, no extra XML).
      //
      // 2026-08 update: this held 5 hand-written short demos before. They were
      // replaced by 2 full user-supplied programs tested on real hardware
      // (alpha2-program-2026-08-04-04-02-26.xml / alpha2-program-2026-08-04-05-43-58.xml,
      // exported from a workspace), converted to toolbox JSON and stored here
      // directly: <value><shadow>...</shadow></value> maps to inputs.{NAME}.shadow,
      // <next><block>...</block></next> maps to a nested next.block, and the
      // XML-only id/x/y position attributes were dropped (the flyout reassigns them on drag-out).
      //
      // Note: every fields.SUBCATEGORY value must stay Chinese (for example the
      // expression/interaction one). It is a key of the real subcategory lists in
      // blockly-actions-data.js (matching the firmware names measured on the
      // unit), not display text here, so do not switch it with uiLang — the
      // dropdown would lose that category. The same holds for fields.TITLE
      // ('World'/'Antares'/'On The Hunt'): real ringtone titles, data values, keep verbatim.
      kind: 'category', name: t('toolbox_cat_examples'), colour: '15',
      contents: (function () {
        // Example 1 TTS engine: 2026-09 onward always android — the unit no longer
        // has alpha2services, and nuance/iflytek through speech_startTTS always
        // returns NOT_INIT (total silence), so the old pairing "iflytek for
        // Chinese, nuance for English" is dead. android ignores VOICE (leave it empty).
        const ttsEngine = 'android';
        return [
        // Example 1: incoming-call effect — phone ringtone → greeting → play an
        // action (no wait) → speak → wait 7 seconds → stop the ringtone by hand.
        // It shows how sound, speech and action mix (wait_done=false overlaps them).
        {
          kind: 'block', type: 'alpha_speech_ringtone_phone',
          fields: { TITLE: 'World' },
          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
          next: { block: {
            kind: 'block', type: 'alpha_speech_tts',
            fields: { ENGINE: ttsEngine, VOICE: '' },
            inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_example1_hello') } } } },
            next: { block: {
              kind: 'block', type: 'alpha_action_play_basic',
              fields: { SUBCATEGORY: '表情 / 互動類', NAME: '1464835936026', WAIT: 'false', TIMEOUT: 15 },
              next: { block: {
                kind: 'block', type: 'alpha_speech_tts',
                fields: { ENGINE: ttsEngine, VOICE: '' },
                inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_example1_happy') } } } },
                next: { block: {
                  kind: 'block', type: 'alpha_wait_seconds',
                  inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 7 } } } },
                  next: { block: {
                    kind: 'block', type: 'alpha_media_stop', fields: { TYPE: 'ringtone' }
                  } }
                } }
              } }
            } }
          } }
        },
        // Example 2: notification sound + servo pose chain — head turns →
        // notification sound → 3 right-arm poses → sound → 3 left-arm poses →
        // sound → closing action. It shows how servo blocks and ringtone blocks
        // combine into one rhythmic swaying set, with "wait" + a sound as the beat.
        {
          kind: 'block', type: 'alpha_speech_ringtone_notification',
          fields: { TITLE: 'Antares' },
          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
          next: { block: {
            kind: 'block', type: 'alpha_servo_one_head',
            fields: { ID: 19, ANGLE: 90, TIME: 1000 },
            next: { block: {
              kind: 'block', type: 'alpha_servo_one_head',
              fields: { ID: 20, ANGLE: 105, TIME: 1000 },
              next: { block: {
                kind: 'block', type: 'alpha_wait_seconds',
                inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 0.5 } } } },
                next: { block: {
                  kind: 'block', type: 'alpha_speech_ringtone_notification',
                  fields: { TITLE: 'On The Hunt' },
                  inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                  next: { block: {
                    kind: 'block', type: 'alpha_servo_one_right_arm',
                    fields: { ID: 1, ANGLE: 90, TIME: 1000 },
                    next: { block: {
                      kind: 'block', type: 'alpha_servo_one_right_arm',
                      fields: { ID: 2, ANGLE: 90, TIME: 1000 },
                      next: { block: {
                        kind: 'block', type: 'alpha_servo_one_right_arm',
                        fields: { ID: 3, ANGLE: 90, TIME: 1000 },
                        next: { block: {
                          kind: 'block', type: 'alpha_servo_one_right_arm',
                          fields: { ID: 17, ANGLE: 95, TIME: 1000 },
                          next: { block: {
                            kind: 'block', type: 'alpha_wait_seconds',
                            inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1 } } } },
                            next: { block: {
                              kind: 'block', type: 'alpha_speech_ringtone_notification',
                              fields: { TITLE: 'Polaris' },
                              inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                              next: { block: {
                                kind: 'block', type: 'alpha_servo_one_left_arm',
                                fields: { ID: 4, ANGLE: 90, TIME: 1000 },
                                next: { block: {
                                  kind: 'block', type: 'alpha_servo_one_left_arm',
                                  fields: { ID: 5, ANGLE: 90, TIME: 1000 },
                                  next: { block: {
                                    kind: 'block', type: 'alpha_servo_one_left_arm',
                                    fields: { ID: 6, ANGLE: 90, TIME: 1000 },
                                    next: { block: {
                                      kind: 'block', type: 'alpha_servo_one_left_arm',
                                      fields: { ID: 18, ANGLE: 95, TIME: 1000 },
                                      next: { block: {
                                        kind: 'block', type: 'alpha_wait_seconds',
                                        inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1.5 } } } },
                                        next: { block: {
                                          kind: 'block', type: 'alpha_speech_ringtone_notification',
                                          fields: { TITLE: 'Tinkerbell' },
                                          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                                          next: { block: {
                                            kind: 'block', type: 'alpha_action_play_basic',
                                            fields: { SUBCATEGORY: '全身 / 其他動作', NAME: '1464835936001', WAIT: 'true', TIMEOUT: 15 }
                                          } }
                                        } }
                                      } }
                                    } }
                                  } }
                                } }
                              } }
                            } }
                          } }
                        } }
                      } }
                    } }
                  } }
                } }
              } }
            } }
          } }
        },
      ];
      })()
    },
  ]
  };
};

// Build once at first load so initWorkspace() in blockly-page.js can keep
// reading window.ALPHA_TOOLBOX directly (no change to that call). A language
// switch makes blockly-i18n.js re-call window.buildAlphaToolbox() and refresh this.
// reference。
window.ALPHA_TOOLBOX = window.buildAlphaToolbox();

