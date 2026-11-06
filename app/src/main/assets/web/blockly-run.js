// Open Alpha2 — Blockly interpreter and execution engine.
//
// Design principles:
//  1. Do not use the Blockly built-in code generator to build JS and eval it. Use
//     tree-walking interpretation instead (interpretBlock). This highlights each
//     block during wait/repeat, and `running = false` stops the program safely.
//  2. Every action block (play action, TTS, LED, servo, ...) uses a verified /api/*
//     endpoint through the typed Alpha2Api.* wrappers (api-client.js, built-in assertEnum/assertRange,
//     same rules as the backend ApiValidator). Behaviour matches the Panel page exactly.
//  3. Event blocks (alpha_event_accel_threshold / alpha_event_sonar_triggered /
//     alpha_event_pir_triggered) do not run in the main program flow. They register
//     as WebSocket listeners when the workspace loads, and stay active as listeners.
//     This is the event-driven model. It is separate from the linear program that
//     runs when the user presses ▶ Run. Both can exist at the same time.

(function () {

  // ------------------------------------------------------------------
  // Execution state
  // ------------------------------------------------------------------
  let running = false;
  let stopRequested = false;
  let workspace = null;
  const variables = new Map(); // Variable name -> value (the interpreter's own scope, not the Blockly built-in code-gen variable system)
  const accelHandlers = []; // { axis, cmp, threshold, varName, bodyBlock, hatBlock }
  const sonarHandlers = []; // { varName, bodyBlock, hatBlock, wasTriggered }
  const pirHandlers = []; // { state: 'detected'|'cleared', varName, bodyBlock, hatBlock, wasTriggered }

  function logLine(text, cls) {
    const out = document.getElementById('runLog');
    if (!out) return;
    const div = document.createElement('div');
    div.className = 'run-log-line' + (cls ? ' ' + cls : '');
    const time = new Date().toLocaleTimeString('zh-HK', { hour12: false });
    div.textContent = '[' + time + '] ' + text;
    out.appendChild(div);
    if (document.getElementById('runAutoScroll').checked) {
      out.scrollTop = out.scrollHeight;
    }
  }

  function setRunningUI(isRunning) {
    document.getElementById('runBtn').disabled = isRunning;
    document.getElementById('stopBtn').disabled = !isRunning;
    document.getElementById('runStatusDot').className = 'run-status-dot ' + (isRunning ? 'running' : 'idle');
    document.getElementById('runStatusText').textContent = isRunning ? t('run_status_running') : t('run_status_idle');
  }

  function sleep(ms) {
    return new Promise(function (resolve) {
      // Poll stopRequested on a short interval, so the Stop button takes effect immediately
      // in the middle of a wait, instead of only after the whole setTimeout finishes.
      const step = 50;
      let elapsed = 0;
      const timer = setInterval(function () {
        elapsed += step;
        if (stopRequested || elapsed >= ms) {
          clearInterval(timer);
          resolve();
        }
      }, step);
    });
  }

  function highlight(blockId) {
    if (workspace && blockId) {
      workspace.highlightBlock(blockId);
    }
  }

  // ---- Action playback ----

  // ---- Servo ----
  // Alpha2: servo/one { id, angle, time }, servo/all { angles: "a,b,c,..", time }
  async function servoOneAdapter(id, angle, time) {
    await Alpha2Api.servoOne({ id: id, angle: angle, time: time });
  }
  async function servoAllAdapter(angles, time) {
    // angles: a number array of length 20, index 0 maps to servo id 1.
    await Alpha2Api.servoAll({ angles: angles.join(','), time: time });
  }

  // ---- LED ----
  // led/head/set and led/eye/set use one endpoint with a preset (long/flash/breathe/chase/dual/stop)
  // plus color and brightness. This covers all effects.
  async function ledHeadAdapter(preset, color, brightness) {
    const params = { preset: preset };
    if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
    await Alpha2Api.ledHeadSet(params);
  }
  async function ledEyeAdapter(preset, color, brightness) {
    const params = { preset: preset };
    if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
    await Alpha2Api.ledEyeSet(params);
  }
  // Mouth LED: only two states, 'off' and 'breathe(speed)' (no solid-on).
  // An old save may carry a speed in the 0-5000 range (for example the default 1500). Clamp it into 100-1000.
  // Do not wait for the server to answer 400 and stop the whole program.
  async function ledMouthAdapter(mode, speed) {
    if (mode === 'off') await Alpha2Api.ledMouthSet({ preset: 'off' });
    else {
      const sp = Math.min(1000, Math.max(100, Number(speed) || 150));
      await Alpha2Api.ledMouthSet({ speed: sp });
    }
  }

  // ---- Voice TTS ----
  // speech/tts only makes sound on android: nuance/iflytek go through the body-side speech_startTTS
  // and always return NOT_INIT (there is no alpha2services, see RobotStub). An old save may still carry
  // those two values. Convert both to android here and warn, instead of swallowing it silently.
  // The VOICE value still goes to the backend, and the android branch ignores it.
  async function speechTtsAdapter(text, engine, voice) {
    if (engine !== 'android') {
      console.warn('[blockly] TTS engine "' + engine + '" is dead. Using android instead.');
      engine = 'android';
    }
    const params = { text: text, engine: engine };
    if (voice) params.voice = voice;
    await Alpha2Api.speechTts(params);
  }

  // ------------------------------------------------------------------
  // Value evaluation: get the result of a value-input block (number, text, boolean, or object).
  // ------------------------------------------------------------------
  async function evalValue(block) {
    if (!block) return null;
    switch (block.type) {
      case 'math_number': return Number(block.getFieldValue('NUM'));
      case 'text': return block.getFieldValue('TEXT');
      case 'logic_boolean': return block.getFieldValue('BOOL') === 'TRUE';
      case 'logic_null': return null;
      case 'logic_negate': return !(await evalValue(block.getInputTargetBlock('BOOL')));
      case 'logic_compare': {
        const a = await evalValue(block.getInputTargetBlock('A'));
        const b = await evalValue(block.getInputTargetBlock('B'));
        const op = block.getFieldValue('OP');
        switch (op) {
          case 'EQ': return a == b;
          case 'NEQ': return a != b;
          case 'LT': return a < b;
          case 'LTE': return a <= b;
          case 'GT': return a > b;
          case 'GTE': return a >= b;
        }
        return false;
      }
      case 'logic_operation': {
        const a = await evalValue(block.getInputTargetBlock('A'));
        const op = block.getFieldValue('OP');
        if (op === 'AND') return a && (await evalValue(block.getInputTargetBlock('B')));
        return a || (await evalValue(block.getInputTargetBlock('B')));
      }
      case 'logic_ternary': {
        const cond = await evalValue(block.getInputTargetBlock('IF'));
        return cond ? await evalValue(block.getInputTargetBlock('THEN')) : await evalValue(block.getInputTargetBlock('ELSE'));
      }
      case 'math_arithmetic': {
        const a = Number(await evalValue(block.getInputTargetBlock('A')));
        const b = Number(await evalValue(block.getInputTargetBlock('B')));
        switch (block.getFieldValue('OP')) {
          case 'ADD': return a + b;
          case 'MINUS': return a - b;
          case 'MULTIPLY': return a * b;
          case 'DIVIDE': return a / b;
          case 'POWER': return Math.pow(a, b);
        }
        return NaN;
      }
      case 'math_single': {
        const a = Number(await evalValue(block.getInputTargetBlock('NUM')));
        switch (block.getFieldValue('OP')) {
          case 'ROOT': return Math.sqrt(a);
          case 'ABS': return Math.abs(a);
          case 'NEG': return -a;
          case 'LN': return Math.log(a);
          case 'LOG10': return Math.log10(a);
          case 'EXP': return Math.exp(a);
          case 'POW10': return Math.pow(10, a);
        }
        return NaN;
      }
      case 'math_round': {
        const a = Number(await evalValue(block.getInputTargetBlock('NUM')));
        switch (block.getFieldValue('OP')) {
          case 'ROUND': return Math.round(a);
          case 'ROUNDUP': return Math.ceil(a);
          case 'ROUNDDOWN': return Math.floor(a);
        }
        return a;
      }
      case 'math_modulo': {
        const a = Number(await evalValue(block.getInputTargetBlock('DIVIDEND')));
        const b = Number(await evalValue(block.getInputTargetBlock('DIVISOR')));
        return a % b;
      }
      case 'math_random_int': {
        const from = Math.round(Number(await evalValue(block.getInputTargetBlock('FROM'))));
        const to = Math.round(Number(await evalValue(block.getInputTargetBlock('TO'))));
        const lo = Math.min(from, to), hi = Math.max(from, to);
        return lo + Math.floor(Math.random() * (hi - lo + 1));
      }
      case 'text_join': {
        let result = '';
        const count = block.itemCount_ !== undefined ? block.itemCount_ : 2;
        for (let i = 0; i < count; i++) {
          const v = await evalValue(block.getInputTargetBlock('ADD' + i));
          result += (v === null || v === undefined) ? '' : String(v);
        }
        return result;
      }
      case 'text_length': {
        const v = await evalValue(block.getInputTargetBlock('VALUE'));
        return v ? String(v).length : 0;
      }
      case 'text_isEmpty': {
        const v = await evalValue(block.getInputTargetBlock('VALUE'));
        return !v || String(v).length === 0;
      }
      case 'text_indexOf': {
        const haystack = String(await evalValue(block.getInputTargetBlock('VALUE')) || '');
        const needle = String(await evalValue(block.getInputTargetBlock('FIND')) || '');
        const idx = block.getFieldValue('END') === 'FIRST' ? haystack.indexOf(needle) : haystack.lastIndexOf(needle);
        return idx + 1; // Blockly text blocks use a 1-based index
      }
      case 'text_charAt': {
        const v = String(await evalValue(block.getInputTargetBlock('VALUE')) || '');
        const where = block.getFieldValue('WHERE') || 'FROM_START';
        if (where === 'FIRST') return v.charAt(0);
        if (where === 'LAST') return v.charAt(v.length - 1);
        if (where === 'RANDOM') return v.charAt(Math.floor(Math.random() * v.length));
        const at = Math.round(Number(await evalValue(block.getInputTargetBlock('AT')) || 1));
        return where === 'FROM_END' ? v.charAt(v.length - at) : v.charAt(at - 1);
      }
      case 'variables_get':
        return variables.has(block.getFieldValue('VAR')) ? variables.get(block.getFieldValue('VAR')) : null;
      case 'alpha_servo_all_helper': {
        const vals = [];
        for (let i = 1; i <= 20; i++) {
          const v = await evalValue(block.getInputTargetBlock('A' + i));
          vals.push(v === null || v === undefined || v === '' ? 120 : Math.round(Number(v)));
        }
        return vals.join(',');
      }
      default:
        // procedures_callreturn and other unsupported value blocks: try the variable getter behaviour.
        if (block.type === 'variables_get_reporter') {
          return variables.has(block.getFieldValue('VAR')) ? variables.get(block.getFieldValue('VAR')) : null;
        }
        logLine(t('run_unsupported_value_block', { type: block.type }), 'warn');
        return null;
    }
  }

  // ------------------------------------------------------------------
  // Play an action and optionally wait for completion.
  //
  // Critical order: attach the action_stop listener first, then send /api/action/play.
  // If you reverse this, a very fast action (faster than the HTTP round-trip) can send its
  // action_stop event to the WebSocket before the listener attaches, so the event is lost
  // and the block waits until the timeout before it releases. This is the classic setup-before-fire race condition.
  //
  // Also match evt.data.name against the name that was sent. Do not treat any action_stop
  // as your own completion. If a second concurrent event-driven block triggered a different action
  // during this time, a blind match would report "done" too early.
  //
  // 2026-08 bugfix (user report: "playing a whole action crashes"): the branch where "wait for completion"
  // was set to the no-wait choice had no serialization at all. Every alpha_action_play block in a
  // sequence (or every block in a loop set to no-wait) sent its request and moved on at once, so several
  // action/play requests could reach MainActivity#handleApi() within a few ms. The AIDL call inside
  // AlphaActionServiceUtil.playActionName() is a Binder call that really blocks and waits for the
  // UBTECH body-side IActionService response. That service drives the physical motors, so it
  // cannot run more than one action at the same time. logcat traced one real case (see the conversation record):
  // 6 action/play requests arrived within 15ms, and after that the app HttpServer logged
  // no response at all, until the user force-killed the app.
  // One AIDL call hung and never returned, and it stuck the shared Binder thread.
  // That blocked every later AIDL call (not only action; servo and LED stopped too), so the user
  // saw what looked like "the whole app is dead".
  //
  // Fix: whether WAIT is set to wait or no-wait, always serialize through this
  // module-level actionBusyPromise gate. It guarantees only one action/play is in flight at a time.
  //
  // 2026-08 second bugfix (logcat in the conversation record: two action/play calls hit
  // the server "API_ERROR_BUSY" back to back): the gate originally released as soon as
  // the HTTP round-trip returned (a no-wait block should not wait for the whole action to finish,
  // and that idea was not wrong). But the server-side meaning of "busy" changed to "this action is really playing,
  // and it unlocks only at onStopActionResult()", which no longer matched this client's
  // "not busy once the HTTP round-trip ends". The second action/play HTTP request passed
  // the first round-trip early while the body still played the first action, so the server
  // rejected it.
  //
  // Now the client-side "busy" matches the server: wait until this action truly finishes
  // (the action_stop event arrives). No matter what the block picked for no-wait, the gate
  // quietly waits for action_stop (or a conservative safety timeout, see below) before it
  // releases the next queued action/play. This does not change the user-visible behaviour of the no-wait block:
  // the !wait branch of playActionAndMaybeWait still returns right after the send, so the user
  // never waits longer. It only stops several action/play calls from fighting each other.
  let actionBusyPromise = Promise.resolve();

  // A conservative safety timeout. If the action_stop event is missed for some reason (for example
  // the WebSocket dropped, or this action's callback has a bug and never fires), the gate still must not
  // hang forever, or every later action/play in the whole program stops. 20 seconds is already longer
  // than the 15 second timeout inside playActionAndMaybeWait(), so any real wait is always released from
  // there first. This timeout is only the last failsafe.
  const ACTION_GATE_SAFETY_TIMEOUT_MS = 20000;

  function waitForActionStopOrTimeout() {
    return new Promise(function (resolve) {
      const onEvt = function (evt) {
        if (evt.type === 'action_stop') { cleanup(); resolve(); }
      };
      const timer = setTimeout(function () { cleanup(); resolve(); }, ACTION_GATE_SAFETY_TIMEOUT_MS);
      function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
      window.__alphaOnEvent(onEvt);
    });
  }

  function sendActionPlay(name) {
    const gated = actionBusyPromise.then(function () {
      return Alpha2Api.actionPlay({ name: name });
    });
    // The next queued action/play must wait until this action really finishes playing, not
    // only the HTTP round-trip (see the long comment above). Whether this API call succeeds, fails, receives
    // action_stop, or times out, the gate must always release afterwards (the .catch swallows the error),
    // or one failure would deadlock the whole queue forever.
    actionBusyPromise = gated
      .then(function (r) {
        // If the API call itself never went out (network failure, or the server said busy), there is no reason
        // to wait for action_stop — it will never come. Release at once, so the next block can try to send
        // (the earlier "busy" may have been a misunderstanding, for example a short network drop).
        if (!r || !r.ok) return;
        return waitForActionStopOrTimeout();
      })
      .catch(function () { /* see the comment above: swallow the error, use it only to release the gate */ });
    return gated;
  }

  async function playActionAndMaybeWait(name, wait, timeoutSeconds) {
    if (!wait) {
      logLine(t('run_action_play_nowait', { name: name }));
      await sendActionPlay(name);
      return;
    }
    logLine(t('run_action_play_wait', { name: name, timeout: timeoutSeconds }));
    const donePromise = new Promise(function (resolve) {
      const onEvt = function (evt) {
        if (evt.type === 'action_stop' && (!evt.data || evt.data.name === name || !evt.data.name)) {
          cleanup(); resolve('done');
        }
      };
      const timer = setTimeout(function () { cleanup(); resolve('timeout'); }, timeoutSeconds * 1000);
      function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
      window.__alphaOnEvent(onEvt);
      // After the listener is attached, send the real API request.
      // Do not drop the .catch(...). window.api() should already handle every fetch network error,
      // but do not depend on that assumption: keep a local .catch here. Otherwise an unexpected throw
      // becomes an unhandled promise rejection with no .then() to catch it, and the page shows a red
      // error banner while the playback state (donePromise) hangs and never resolves.
      sendActionPlay(name).then(function (r) {
        if (!r || !r.ok) { cleanup(); resolve('api_failed'); }
      }).catch(function (err) {
        cleanup();
        logLine(t('run_action_play_exception', { err: (err && err.message ? err.message : String(err)) }), 'err');
        resolve('api_failed');
      });
    });
    const result = await donePromise;
    if (result === 'timeout') logLine(t('run_action_wait_timeout', { name: name }), 'warn');
    else if (result === 'api_failed') logLine(t('run_action_api_failed', { name: name }), 'err');
    else logLine(t('run_action_play_done', { name: name }));
  }

  // ------------------------------------------------------------------
  // Statement execution: run one statement block and return the next block. The caller (runSequence) handles it.
  // ------------------------------------------------------------------
  async function execStatement(block) {
    highlight(block.id);
    const blockType = block.type;

    switch (blockType) {
      // ---------------- Actions ----------------
      case 'alpha_action_play': {
        const name = block.getFieldValue('NAME');
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      // 2026-08 update: alpha_action_play_builtin split into separate blocks (basic/dance
      // /story/yoga/other), but every block keeps the same NAME/WAIT/TIMEOUT field structure,
      // so one case covers all types. No need to write separate cases.
      case 'alpha_action_play_basic':
      case 'alpha_action_play_dance':
      case 'alpha_action_play_story':
      case 'alpha_action_play_yoga':
      case 'alpha_action_play_others': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine(t('run_no_action_selected'), 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_play_dropdown': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine(t('run_no_action_selected_live'), 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_stop':
        logLine(t('run_action_stop'));
        await Alpha2Api.actionStop();
        return;
      case 'alpha_action_wait_done': {
        const timeout = Number(block.getFieldValue('TIMEOUT')) * 1000;
        logLine(t('run_action_wait_extra', { timeout: block.getFieldValue('TIMEOUT') }));
        await new Promise(function (resolve) {
          const onEvt = function (evt) {
            if (evt.type === 'action_stop') { cleanup(); resolve(); }
          };
          const timer = setTimeout(function () { cleanup(); resolve(); }, timeout);
          function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
          window.__alphaOnEvent(onEvt);
        });
        if (stopRequested) return;
        return;
      }

      // ---------------- Sound (TTS / ringtone / local music) ----------------
      case 'alpha_speech_tts': {
        const text = await evalValue(block.getInputTargetBlock('TEXT'));
        const engine = block.getFieldValue('ENGINE');
        const voice = block.getFieldValue('VOICE');
        logLine(t('run_tts', { engine: engine, text: text }));
        await speechTtsAdapter(String(text == null ? '' : text), engine, voice);
        return;
      }
      case 'alpha_speech_stop':
        logLine(t('run_tts_stop'));
        await Alpha2Api.speechStop();
        return;
      case 'alpha_media_stop': {
        // Three-in-one stop: the toolbox shows this unified block. An old .xml with alpha_speech_stop or
        // alpha_music_stop still runs through the two separate cases below (in their own sections), while
        // alpha_speech_ringtone_stop has no block definition left and is unified through TYPE=ringtone.
        const stopType = block.getFieldValue('TYPE');
        if (stopType === 'ringtone') {
          logLine(t('media_stop__run_ringtone'));
          await Alpha2Api.audioRingtonesStop();
        } else if (stopType === 'music') {
          logLine(t('media_stop__run_music'));
          await Alpha2Api.audioLocalMusicStop();
        } else {
          logLine(t('media_stop__run_tts'));
          await Alpha2Api.speechStop();
        }
        return;
      }
      case 'alpha_speech_set_mic':
        logLine(t('run_mic_ownership', { owner: (block.getFieldValue('WAKE') === 'true' ? t('run_mic_owner_robot') : t('run_mic_owner_app')) }));
        await Alpha2Api.speechSetMic({ wake: block.getFieldValue('WAKE') });
        return;
      // Removed 2026-09: alpha_speech_start_asr / alpha_speech_set_voice /
      // alpha_speech_set_language / alpha_speech_self_interrupt — the matching backend
      // endpoints no longer exist (404), so the block definitions, toolbox, and i18n entries were removed together.
      // 2026-08 update: phone ringtone and notification ringtone are now two separate blocks (previously one
      // alpha_speech_ringtone block with a TYPE dropdown). The type is now hard-coded in these two cases.
      // Playback now sends title (no longer index) and calls the new /api/audio/ringtones/play_by_title
      // (see the makeRingtoneBlock() comment in blockly-blocks.js: title lookup is as reliable as the
      // camera shutter sound mechanism, so RingtoneManager cursor index ordering does not matter).
      //
      // 2026-08 update 2: added the DURATION value input. Before this, the block played the whole
      // system ringtone before returning. There was no control over play time, and this was one source of
      // the old "it repeats and never stops" problem. Now, after playback, if duration > 0 the block
      // sleeps for that time and then calls /api/audio/ringtones/stop to stop it (MainActivity.playRingtoneUri()
      // now tracks the playing ringtone in its currentRingtonePlayer field, so stop can interrupt it safely at any time).
      // Set 0 for no auto-stop: play until the sound file ends naturally (the old behaviour).
      // Toolbox defaults: phone ringtone 10 seconds, notification ringtone 5 seconds.
      case 'alpha_speech_ringtone_phone':
      case 'alpha_speech_ringtone_notification': {
        const type = (block.type === 'alpha_speech_ringtone_notification') ? 'notification' : 'ringtone';
        const title = block.getFieldValue('TITLE');
        if (!title) {
          logLine(t('run_no_ringtone_selected'), 'warn');
          return;
        }
        const duration = Number(await evalValue(block.getInputTargetBlock('DURATION'))) || 0;
        logLine(t('run_ringtone_play', {
          type: (type === 'notification' ? t('run_ringtone_type_notification') : t('run_ringtone_type_phone')),
          title: title,
          durationNote: (duration > 0 ? t('run_ringtone_duration_note', { duration: duration }) : t('run_ringtone_duration_full')),
        }));
        await Alpha2Api.audioRingtonesPlayByTitle({ type: type, title: title });
        if (duration > 0) {
          await sleep(duration * 1000);
          // Even when stopRequested is set (the user pressed "Stop the program"), the ringtone must stop.
          // Otherwise it keeps playing on the robot: the Java MediaPlayer does not stop just because
          // this web page loop stopped.
          await Alpha2Api.audioRingtonesStop();
        }
        return;
      }

      // Removed 2026-09: the alpha_speech_ringtone_stop case — the block definition is deleted.
      // Stopping a ringtone now goes through alpha_media_stop (TYPE=ringtone).

      // ---------------- Local music (same /api/audio/local_music/* endpoints as the Music page) ----------------
      case 'alpha_music_play': {
        const name = block.getFieldValue('NAME');
        if (!name) {
          logLine(t('run_no_music_selected'), 'warn');
          return;
        }
        const filler = block.getFieldValue('FILLER') === 'true';
        const disco = block.getFieldValue('DISCO') === 'true';
        logLine(t('run_music_play', {
          name: name,
          fillerNote: (filler ? t('run_music_filler_note') : ''),
          discoNote: (disco ? t('run_music_disco_note') : ''),
        }));
        // Set the two persistent switches first (write both explicitly for every play; this is the most reliable way), then play.
        // Dance-with-music = filler_action (random actions); beat lights = disco — two separate APIs.
        // Playing is fire-and-forget (the server answers at once and plays in the background). Do not wait for the end.
        await Alpha2Api.audioLocalMusicFillerActionSet({ enabled: filler ? 'true' : 'false' });
        await Alpha2Api.audioLocalMusicDiscoSet({ enabled: disco ? 'true' : 'false' });
        await Alpha2Api.audioLocalMusicPlay({ name: name });
        return;
      }
      case 'alpha_music_stop':
        logLine(t('run_music_stop'));
        await Alpha2Api.audioLocalMusicStop();
        return;

      // Removed 2026-09: the alpha_music_disco / alpha_music_pause /
      // alpha_music_resume cases — those blocks are deleted. The play block's FILLER and
      // DISCO fields now control dance-with-music (filler_action) and beat lights (disco) respectively.

      // ---------------- Servo ----------------
      // 2026-08 update: alpha_servo_one split into 5 separate blocks (head/right arm/left arm/right leg
      // /left leg), but all five keep the same ID/ANGLE/TIME field structure, so no separate cases.
      // One case covers all 5 types.
      case 'alpha_servo_one_head':
      case 'alpha_servo_one_right_arm':
      case 'alpha_servo_one_left_arm':
      case 'alpha_servo_one_right_leg':
      case 'alpha_servo_one_left_leg': {
        const id = block.getFieldValue('ID');
        let angle = Number(block.getFieldValue('ANGLE'));
        // Clamp once more in the execution layer as a failsafe. The field validator already blocks most
        // cases in the UI, but a program loaded through "import .xml" can bypass the UI (the XML can
        // hold any out-of-range value), so clamp again before the API request goes out.
        if (window.ALPHA_SERVO_CLAMP) {
          const clamped = window.ALPHA_SERVO_CLAMP(id, angle);
          if (clamped !== angle) {
            logLine(t('run_servo_clamped', { id: id, angle: angle, clamped: clamped }), 'warn');
            angle = clamped;
          }
        }
        const time = block.getFieldValue('TIME');
        logLine(t('run_servo_one', { id: id, angle: angle, time: time }));
        await servoOneAdapter(id, angle, time);
        return;
      }
      case 'alpha_servo_all': {
        const anglesVal = await evalValue(block.getInputTargetBlock('ANGLES'));
        const time = block.getFieldValue('TIME');
        // Clamp each of the 20 values in the CSV string against the per-servo #1~#20 calibration table. No matter
        // where the CSV came from (a helper, direct typing, or a variable), the values must be safe before sending.
        let angles = String(anglesVal == null ? '' : anglesVal).split(',').map(function (s) { return Number(s.trim()); });
        if (window.ALPHA_SERVO_CLAMP) {
          angles = angles.map(function (v, idx) { return window.ALPHA_SERVO_CLAMP(idx + 1, v); });
        }
        logLine(t('run_servo_all', { csv: angles.join(','), time: time }));
        await servoAllAdapter(angles, time);
        return;
      }
      case 'alpha_servo_home': {
        // Home values always come from the shared calibration table (window.ALPHA_SERVO_CALIBRATION, used by the
        // servo-part block and the Servo page too — all three read one data source, see blockly-servo-data.js),
        // so this file no longer keeps its own hard-coded copy that could drift and be wrong.
        const cal = window.ALPHA_SERVO_CALIBRATION;
        const home = [];
        for (let i = 1; i <= 20; i++) home.push(cal && cal[i] ? cal[i].home : 120);
        const time = block.getFieldValue('TIME');
        logLine(t('run_servo_home', { time: time }));
        await servoAllAdapter(home, time);
        return;
      }
      case 'alpha_servo_sonar':
        logLine(t('run_sonar_distance', { dist: block.getFieldValue('DIST') }));
        await Alpha2Api.servoSonar({ distance: block.getFieldValue('DIST') });
        return;

      // ---------------- LED ----------------
      case 'alpha_led_head': {
        const preset = block.getFieldValue('PRESET');
        logLine(t('run_led_head', { preset: preset }));
        const color = block.getFieldValue('COLOR');
        const brightness = block.getFieldValue('BRIGHT');
        await ledHeadAdapter(preset, color, brightness);
        return;
      }
      case 'alpha_led_eye': {
        const preset = block.getFieldValue('PRESET');
        logLine(t('run_led_eye', { preset: preset }));
        const color = block.getFieldValue('COLOR');
        const brightness = block.getFieldValue('BRIGHT');
        await ledEyeAdapter(preset, color, brightness);
        return;
      }
      case 'alpha_led_mouth': {
        const mode = block.getFieldValue('MODE');
        if (mode === 'off') {
          logLine(t('run_led_mouth_off'));
        } else {
          logLine(t('run_led_mouth_breathe', { speed: block.getFieldValue('SPEED') }));
        }
        await ledMouthAdapter(mode, block.getFieldValue('SPEED'));
        return;
      }

      // ---------------- Sensor / device ----------------
      case 'alpha_sensor_accel_toggle':
        logLine(t('run_accel_toggle', { on: block.getFieldValue('ON') }));
        await Alpha2Api.accelerometerSet({ on: block.getFieldValue('ON') });
        return;
      case 'alpha_sensor_sonar_toggle': {
        const on = block.getFieldValue('ON') === 'true';
        const dist = on ? block.getFieldValue('DIST') : '0';
        logLine(t('run_sonar_toggle', { on: block.getFieldValue('ON'), thresholdNote: (on ? t('run_sonar_toggle_threshold', { dist: dist }) : '') }));
        await Alpha2Api.servoSonar({ distance: dist });
        return;
      }
      case 'alpha_sensor_pir_toggle': {
        const on = block.getFieldValue('ON');
        logLine(t('run_pir_toggle', { on: on }));
        await Alpha2Api.pirSet({ on: on });
        return;
      }

      // ---------------- Flow control ----------------
      case 'alpha_wait_seconds': {
        const secs = Number(await evalValue(block.getInputTargetBlock('SECONDS'))) || 0;
        logLine(t('run_wait_seconds', { secs: secs }));
        await sleep(secs * 1000);
        return;
      }
      case 'alpha_log': {
        const msg = await evalValue(block.getInputTargetBlock('MSG'));
        logLine('📝 ' + (msg === null || msg === undefined ? '' : String(msg)), 'user');
        return;
      }
      case 'alpha_stop_program':
        logLine(t('run_stop_program'), 'warn');
        stopRequested = true;
        return;

      // ---------------- Blockly standard: if / repeat / while / for ----------------
      case 'controls_if': {
        let i = 0;
        while (block.getInput('IF' + i)) {
          const cond = await evalValue(block.getInputTargetBlock('IF' + i));
          if (cond) {
            await runSequence(block.getInputTargetBlock('DO' + i));
            return;
          }
          i++;
        }
        if (block.getInput('ELSE')) {
          await runSequence(block.getInputTargetBlock('ELSE'));
        }
        return;
      }
      case 'controls_repeat_ext': {
        const times = Math.round(Number(await evalValue(block.getInputTargetBlock('TIMES'))) || 0);
        for (let i = 0; i < times && !stopRequested; i++) {
          const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
          if (flow === 'BREAK') break;
          // flow === 'CONTINUE' or null: go straight to the next round, nothing special to do
        }
        return;
      }
      case 'controls_whileUntil': {
        const mode = block.getFieldValue('MODE');
        let guard = 0;
        while (!stopRequested && guard++ < 100000) {
          const cond = await evalValue(block.getInputTargetBlock('BOOL'));
          const shouldRun = mode === 'WHILE' ? cond : !cond;
          if (!shouldRun) break;
          const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
          if (flow === 'BREAK') break;
        }
        return;
      }
      case 'controls_for': {
        const varName = block.getFieldValue('VAR');
        const from = Number(await evalValue(block.getInputTargetBlock('FROM'))) || 0;
        const to = Number(await evalValue(block.getInputTargetBlock('TO'))) || 0;
        const by = Number(await evalValue(block.getInputTargetBlock('BY'))) || 1;
        if (by > 0) {
          for (let i = from; i <= to && !stopRequested; i += by) {
            variables.set(varName, i);
            const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
            if (flow === 'BREAK') break;
          }
        } else if (by < 0) {
          for (let i = from; i >= to && !stopRequested; i += by) {
            variables.set(varName, i);
            const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
            if (flow === 'BREAK') break;
          }
        }
        return;
      }
      case 'controls_flow_statements':
        // break/continue: simplified model — throw a special signal and let runSequence/loop catch it.
        throw { __alphaFlow: block.getFieldValue('FLOW') };

      case 'variables_set': {
        const val = await evalValue(block.getInputTargetBlock('VALUE'));
        variables.set(block.getFieldValue('VAR'), val);
        return;
      }
      case 'math_change': {
        const varName = block.getFieldValue('VAR');
        const delta = Number(await evalValue(block.getInputTargetBlock('DELTA'))) || 0;
        const cur = Number(variables.get(varName)) || 0;
        variables.set(varName, cur + delta);
        return;
      }
      case 'text_print': {
        const v = await evalValue(block.getInputTargetBlock('TEXT'));
        logLine('🖨 ' + (v === null || v === undefined ? '' : String(v)), 'user');
        return;
      }

      default:
        logLine(t('run_unsupported_block', { type: blockType }), 'warn');
        return;
    }
  }

  async function runSequence(startBlock) {
    let block = startBlock;
    while (block && !stopRequested) {
      try {
        await execStatement(block);
      } catch (e) {
        if (e && e.__alphaFlow) {
          // break/continue bubble out to the nearest loop. The simplest way: throw upward.
          // If runProgram() at the top level cannot catch it, treat that as a stop. Every loop case already uses
          // the runSequence call stack, so bubbling one level up means leaving that loop body.
          throw e;
        }
        logLine(t('run_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
      }
      block = block.getNextBlock();
    }
  }

  // controls_repeat_ext / whileUntil / for need runSequence to catch break/continue, so
  // wrap one try/catch layer that breaks out of that loop instead of crashing the whole program.
  const _rawRunSequence = runSequence;
  async function runSequenceGuarded(startBlock) {
    try {
      await _rawRunSequence(startBlock);
      return null;
    } catch (e) {
      if (e && e.__alphaFlow) return e.__alphaFlow;
      throw e;
    }
  }

  async function runProgram() {
    if (running) return;
    // 2026-08 bugfix: rewireEventHandlers() normally runs through the workspace change listener
    // with a 300ms debounce (see AlphaBlockly.init), to avoid too many scans after a burst of drags or
    // undo/redo. But if the user just dropped a new accel/sonar event block and then
    // quickly presses "Run", the 300ms debounce has not fired yet, so accelHandlers/
    // sonarHandlers still hold the old state (without the new block). The hat block
    // is never registered at all, the run log shows "registered 0", and it stays like that until the next
    // workspace change (for example the user nudges the block) triggers another rewire.
    // Now, before running the program, force one synchronous rewireEventHandlers() call, so the
    // accel/sonar handlers always reflect the newest canvas state. Do not rely on luck.
    rewireEventHandlers();
    const EVENT_HAT_TYPES = ['alpha_event_accel_threshold', 'alpha_event_sonar_triggered', 'alpha_event_pir_triggered'];
    const topBlocks = workspace.getTopBlocks(true).filter(function (b) {
      return EVENT_HAT_TYPES.indexOf(b.type) === -1 && !b.isCollapsed() && !b.disabled;
    });
    if (!topBlocks.length) {
      logLine(t('run_no_executable_blocks'), 'warn');
      return;
    }
    running = true;
    stopRequested = false;
    setRunningUI(true);
    logLine(t('run_program_start', { count: topBlocks.length }), 'sys');
    try {
      for (const top of topBlocks) {
        if (stopRequested) break;
        await runSequence(top);
      }
    } catch (e) {
      if (!(e && e.__alphaFlow)) {
        logLine(t('run_runtime_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
      }
    }
    running = false;
    const wasStopped = stopRequested;
    stopRequested = false;
    setRunningUI(false);
    workspace.highlightBlock(null);
    logLine(wasStopped ? t('run_program_stopped') : t('run_program_finished'), 'sys');
  }

  function stopProgram() {
    stopRequested = true;
    logLine(t('run_user_pressed_stop'), 'sys');
  }

  // ------------------------------------------------------------------
  // Event-driven: scan the workspace for every alpha_event_accel_threshold /
  // alpha_event_sonar_triggered / alpha_event_pir_triggered block and register WS listeners.
  // ------------------------------------------------------------------
  function rewireEventHandlers() {
    accelHandlers.length = 0;
    sonarHandlers.length = 0;
    pirHandlers.length = 0;
    if (!workspace) return;
    workspace.getTopBlocks(true).forEach(function (b) {
      if (b.type === 'alpha_event_accel_threshold' && !b.disabled) {
        accelHandlers.push({
          axis: b.getFieldValue('AXIS'),
          cmp: b.getFieldValue('CMP'),
          threshold: Number(b.getFieldValue('THRESHOLD')),
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          running: false, // re-entrancy guard: is this handler's DO sequence running now
        });
      } else if (b.type === 'alpha_event_sonar_triggered' && !b.disabled) {
        sonarHandlers.push({
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: false, // for edge detection: the triggered state of the last event
          running: false, // re-entrancy guard: is this handler's DO sequence running now
        });
      } else if (b.type === 'alpha_event_pir_triggered' && !b.disabled) {
        pirHandlers.push({
          state: b.getFieldValue('STATE'), // 'detected' or 'cleared' — which direction the user picked
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: null, // for edge detection: the last triggered state; null = no PIR event yet, so no edge
          running: false, // re-entrancy guard: is this handler's DO sequence running now
        });
      }
    });
    logLine(t('run_handlers_registered', { accel: accelHandlers.length, sonar: sonarHandlers.length, pir: pirHandlers.length }), 'sys');
  }

  async function onWsEvent(evt) {
    window.__alphaFireEvent(evt); // lets ad-hoc listeners such as alpha_action_wait_done receive the event
    // Accelerometer: for every accel event, check each registered threshold block. The reading
    // (includes the gravity component, see the MainActivity onSensorChanged comment) triggers when its
    // absolute value passes the threshold.
    //
    // 2026-08 bugfix: there was no debounce or re-entrancy guard here before. Accel events are
    // frequent (~150-250ms apart), and one servo action inside DO already takes over 1 second.
    // So while one body was still awaiting runSequence(), the next accel events arrived, and the
    // for-loop did not block, so it called runSequence() again several times.
    // The same hatBlock then ran several sequences stacked at once, which looked like "the action keeps
    // repeating". This is not about missing edge detection (accel means "re-trigger while the threshold is
    // exceeded", unlike sonar with its clear one-shot "just detected now" meaning). The real problem
    // was that overlapping executions were not blocked. Follow the standard Scratch runtime practice:
    // one hat block runs at most one sequence at a time. While the previous round is unfinished, a new
    // trigger event is skipped. Nothing is queued or stacked.
    if (evt.type === 'accel' && evt.data) {
      for (const h of accelHandlers) {
        if (!h.bodyBlock) continue;
        if (h.running) continue; // the previous DO round is still running, so skip this trigger
        const v = Math.abs(Number(evt.data[h.axis]));
        const hit = h.cmp === 'gt' ? v > h.threshold : v < h.threshold;
        if (!hit) continue;
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_accel_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
    // Sonar: MainActivity already debounces sonar_obstacle events (frames with the same
    // state do not re-publish the LED switch, but events may still arrive every frame). Here
    // wasTriggered does edge detection for "not triggered -> triggered", so "obstacle detected" means
    // "detected just now" and does not repeat while the object stays inside the threshold range.
    //
    // 2026-08 bugfix: edge detection alone is not enough. If an object moves in and out of the threshold
    // distance (for example it lingers at the edge), wasTriggered flips false -> true again and again, each one a
    // new "edge", so several triggers can still land before the previous DO round ends and stack
    // several sequences on the same hatBlock. Add the same running guard as accel.
    if (evt.type === 'sonar_obstacle' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of sonarHandlers) {
        if (!h.bodyBlock) continue;
        const edge = triggeredNow && !h.wasTriggered;
        h.wasTriggered = triggeredNow;
        if (!edge) continue;
        if (h.running) continue; // the previous DO round is still running, so skip this trigger
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_sonar_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
    // PIR human sensor: the alpha2_pir_state event payload is only {triggered: true/false}
    // (true = person detected, false = no person). See the comment near
    // registerAlpha2PirAlertListener in RobotEventReceiver. Like sonar, this uses edge detection,
    // but the user asked for both "detected" and "cleared" to be selectable separately (the STATE field),
    // unlike sonar with only the "far -> near" direction. So check the detected edge
    // (false -> true) or the cleared edge (true -> false), to match the direction that hat block
    // picked for itself.
    //
    // Initialize wasTriggered to null (not false). It separates "this handler was just
    // registered and has not received any PIR event" from "the last event said no person". If we
    // used false, a first event with triggered=false would look like an undefined -> false cleared edge,
    // and the "no person detected" block would fire for no visible reason right at program start.
    // The user would find that confusing.
    if (evt.type === 'alpha2_pir_state' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of pirHandlers) {
        if (!h.bodyBlock) continue;
        const hadPrior = h.wasTriggered !== null;
        const edgeDetected = h.state === 'detected' && triggeredNow && hadPrior && !h.wasTriggered;
        const edgeCleared = h.state === 'cleared' && !triggeredNow && hadPrior && h.wasTriggered;
        h.wasTriggered = triggeredNow;
        if (!edgeDetected && !edgeCleared) continue;
        if (h.running) continue; // the previous DO round is still running, so skip this trigger
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_pir_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
  }

  // Simple pub/sub for one-shot ad-hoc listeners such as wait_done. Do not stuff them all into accelHandlers/sonarHandlers.
  const adHocListeners = new Set();
  window.__alphaOnEvent = function (fn) { adHocListeners.add(fn); };
  window.__alphaOffEvent = function (fn) { adHocListeners.delete(fn); };
  window.__alphaFireEvent = function (evt) { adHocListeners.forEach(function (fn) { fn(evt); }); };

  // ------------------------------------------------------------------
  // Save / Load — store the workspace as XML, with localStorage quick-save plus .xml file export/import
  // ------------------------------------------------------------------
  const STORAGE_KEY = 'alpha_blockly_workspace_v1';
  const STORAGE_KEY_LIST = 'alpha_blockly_saved_programs_v1';

  function workspaceToXmlText() {
    const xmlDom = Blockly.Xml.workspaceToDom(workspace);
    return Blockly.Xml.domToPrettyText(xmlDom);
  }

  function loadXmlText(xmlText) {
    workspace.clear();
    const xmlDom = Blockly.utils.xml.textToDom(xmlText);
    Blockly.Xml.domToWorkspace(xmlDom, workspace);
    rewireEventHandlers();
  }

  function autoSaveToLocalStorage() {
    try {
      localStorage.setItem(STORAGE_KEY, workspaceToXmlText());
    } catch (e) {
      console.error('autosave failed', e);
    }
  }

  function restoreFromLocalStorage() {
    try {
      const xml = localStorage.getItem(STORAGE_KEY);
      if (xml) {
        loadXmlText(xml);
        logLine(t('run_restored_autosave'), 'sys');
        return true;
      }
    } catch (e) {
      console.error('restore failed', e);
    }
    return false;
  }

  function getSavedProgramList() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY_LIST) || '{}');
    } catch (e) {
      return {};
    }
  }

  function saveNamedProgram(name) {
    const list = getSavedProgramList();
    list[name] = { xml: workspaceToXmlText(), savedAt: new Date().toISOString() };
    localStorage.setItem(STORAGE_KEY_LIST, JSON.stringify(list));
    refreshSavedProgramDropdown();
    logLine(t('run_saved_as', { name: name }), 'sys');
  }

  function loadNamedProgram(name) {
    const list = getSavedProgramList();
    if (list[name]) {
      loadXmlText(list[name].xml);
      logLine(t('run_loaded', { name: name }), 'sys');
    }
  }

  function deleteNamedProgram(name) {
    const list = getSavedProgramList();
    delete list[name];
    localStorage.setItem(STORAGE_KEY_LIST, JSON.stringify(list));
    refreshSavedProgramDropdown();
    logLine(t('run_deleted', { name: name }), 'sys');
  }

  function refreshSavedProgramDropdown() {
    const sel = document.getElementById('savedProgramSelect');
    if (!sel) return;
    const list = getSavedProgramList();
    const names = Object.keys(list).sort();
    // 2026-09-09: build each row with createElement + textContent. Before this, string concatenation
    // wrote into innerHTML, and the program name came from localStorage or an imported file (stored XSS).
    while (sel.firstChild) sel.removeChild(sel.firstChild);
    const ph = document.createElement('option');
    ph.value = "";
    ph.textContent = t('run_saved_program_placeholder');
    sel.appendChild(ph);
    names.forEach(function (n) {
      const opt = document.createElement('option');
      opt.value = n;
      opt.textContent = n;
      sel.appendChild(opt);
    });
  }

  function exportXmlFile() {
    const xml = workspaceToXmlText();
    const blob = new Blob([xml], { type: 'application/xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'alpha2-program-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.xml';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    logLine(t('run_exported'), 'sys');
  }

  function importXmlFile(file) {
    const reader = new FileReader();
    reader.onload = function () {
      try {
        loadXmlText(String(reader.result));
        logLine(t('run_imported', { file: file.name }), 'sys');
      } catch (e) {
        logLine(t('run_import_failed', { err: e.message }), 'err');
      }
    };
    reader.readAsText(file);
  }

  // ------------------------------------------------------------------
  // Action list dropdown: fetch /api/action/list and fill it into alpha_action_play_dropdown
  //
  // The category prefix is looked up by action id in window.ALPHA_ACTION_CATEGORY_OF (defined in
  // blockly-actions-data.js, built from the id -> main table in action_classified.txt).
  //
  // Note: always use a.id here, never a.type — the "type" returned by the robot /api/action/list is the
  // body firmware's own internal action type number (it matches the id/type/nameCn/nameEn columns of
  // IAlphaActionListListener). It is completely unrelated to the 4 categories we classified by hand
  // in action_classified.txt (basic/dance/story/yoga). The two numbering systems only overlap by
  // coincidence. The old version inferred the category from the type number with a whitelist; the parts
  // that looked "right" matched only because two number ranges overlapped. That is unreliable.
  // ------------------------------------------------------------------
  async function refreshActionDropdown() {
    logLine(t('run_fetching_action_list'), 'sys');
    const r = await Alpha2Api.actionList();
    if (r && r.ok && Array.isArray(r.actions)) {
      window.__alphaActionOptions = r.actions.map(function (a) {
        const catKey = window.ALPHA_ACTION_CATEGORY_OF ? window.ALPHA_ACTION_CATEGORY_OF(a.id) : '';
        const catInfo = (window.ALPHA_ACTION_CATEGORIES || []).filter(function (c) { return c.key === catKey; })[0];
        const prefix = catInfo ? ('[' + catInfo.label + '] ') : '';
        const label = prefix + (a.nameCn || a.nameEn || a.id) + '  [' + a.id + ']';
        return [label, a.id];
      });
      if (!window.__alphaActionOptions.length) {
        window.__alphaActionOptions = [[t('run_action_list_empty'), '']];
      }
      logLine(t('run_action_list_loaded', { count: r.actions.length }), 'sys');
    } else {
      logLine(t('run_action_list_failed'), 'err');
      window.__alphaActionOptions = [[t('run_action_list_load_failed_option'), '']];
    }
    // Force every existing dropdown block to re-render and show the new options.
    //
    // Warning: run setValue together with this step. getOptions() + forceRerender() alone is not enough:
    // before the list arrives, the value stored in the field may be the fallback option's '' (the value of
    // the not-loaded placeholder), and the new list no longer contains that '' value. That leaves the field
    // holding a value its own menuGenerator does not return — a value/options mismatch. In that state
    // the Blockly dropdown stops responding to clicks entirely (a bug seen in real use), and it looks
    // like the dropdown is broken. So after the new list arrives, if the stored field value is missing from
    // the new options, call setValue to move it to the first entry. This keeps the field in the
    // consistent state "current value is one of the current options"
    // at all times.
    if (workspace) {
      workspace.getBlocksByType('alpha_action_play_dropdown', false).forEach(function (b) {
        const field = b.getField('NAME');
        if (field && field.getOptions) {
          field.getOptions(false);
          const opts = field.getOptions(false);
          const curVal = field.getValue();
          const stillValid = opts.some(function (pair) { return pair[1] === curVal; });
          if (!stillValid && opts.length) field.setValue(opts[0][1]);
          field.forceRerender();
        }
      });
    }
  }

  // 2026-08 update: the system ringtone list is now embedded statically in blockly-ringtone-data.js (captured
  // once from a real device over adb, see that file's header comment). This code no longer queries /api/audio/ringtones/list live.
  // The refreshRingtoneDropdown() function and the toolbox "get ringtone list" button were removed
  // together. See blockly.html.

  // ------------------------------------------------------------------
  // Local music track list (live dropdown, same approach as refreshActionDropdown above):
  // blockly-page.js fetches it once when the page opens and stores it in
  // window.__alphaMusicOptions for the alpha_music_play NAME dropdown to read.
  // Response format, see app-music.js: { files: [{ name, sizeBytes }] }.
  // ------------------------------------------------------------------
  async function refreshMusicDropdown() {
    logLine(t('run_fetching_music_list'), 'sys');
    try {
      const r = await Alpha2Api.audioLocalMusicList();
      if (r && r.ok && Array.isArray(r.files)) {
        window.__alphaMusicOptions = r.files
          .filter(function (f) { return f && f.name; })
          .map(function (f) { return [f.name, f.name]; });
        if (!window.__alphaMusicOptions.length) {
          window.__alphaMusicOptions = [[t('run_music_list_empty'), '']];
        }
        logLine(t('run_music_list_loaded', { count: r.files.length }), 'sys');
      } else {
        throw new Error('bad response');
      }
    } catch (e) {
      logLine(t('run_music_list_failed'), 'err');
      window.__alphaMusicOptions = [[t('run_music_list_load_failed_option'), '']];
    }
    // Same as refreshActionDropdown: after the new list returns, an existing block's field value
    // may no longer be in the new options (for example the old fallback ''), so actively reset it to
    // the first entry. Otherwise the dropdown never responds to clicks.
    if (workspace) {
      workspace.getBlocksByType('alpha_music_play', false).forEach(function (b) {
        const field = b.getField('NAME');
        if (field && field.getOptions) {
          const opts = field.getOptions(false);
          const curVal = field.getValue();
          const stillValid = opts.some(function (pair) { return pair[1] === curVal; });
          if (!stillValid && opts.length) field.setValue(opts[0][1]);
          field.forceRerender();
        }
      });
    }
  }

  // ------------------------------------------------------------------
  // The cut/copy/paste and sidebar-collapse button groups — ported from NuwaRobotics Code Lab, rewritten with
  // Blockly's official Blockly.ComponentManager + Blockly.uiPosition (the IPositionable interface),
  // the same placement pipeline as the Trashcan and ZoomControls:
  // On every resize, WorkspaceSvg collects all registered POSITIONABLE components and calls
  // .position(uiMetrics, alreadyPositionedRects) on each one in ascending weight order,
  // and after each call adds its .getBoundingRectangle() to alreadyPositionedRects, so the next
  // (heavier-weight) component avoids it while positioning. That is why the trashcan and the
  // zoom buttons never overlap. Our own buttons now use the same pipeline, so they always follow
  // those components and never land in the wrong place again.
  //
  // Two earlier attempts used a separate HTML <div> + CSS position:absolute + JS
  // getBoundingClientRect() measurements to track where the Blockly trashcan was — but Blockly's internal
  // placement formulas (uiPosition.getStartPositionRect/bumpPositionRect) depend on many factors
  // (scrollbars, toolbox position, RTL), so external tracking was always a step late or wrong. Going back
  // to the official Blockly mechanism is the real root fix.
  //
  // Blockly.utils.dom.createSvgElement(tag, attrs, parent) is the same helper Blockly's own
  // Trashcan/ZoomControls use to build their DOM (confirmed in the minified source;
  // it maps to Blockly.utils.dom.createSvgElement).

  // 2026-09: aligned with the Code Lab solid-fill icons — the path data was extracted from
  // codelab.nuwarobotics.com (the SVG sprite embedded in the index bundle + chunk-843ccf1e):
  // undo/redo use a 16x16 viewBox; cut/copy/paste/center/zoom use a 25x25 viewBox.
  // Each icon records its original viewBox size (vb). When drawn, every icon scales to
  // FAB_ICON_BOX (25px, same as Code Lab drawing a 25px icon inside a 42px circle) and centers,
  // so icons from different viewBoxes look the same size. paste/center/zoom-in/zoom-out originally had
  // two subpaths (body + detail/ring). They are joined into one d string and drawn with fill-rule:evenodd
  // so ring shapes stay hollow (nonzero would fill them into solid discs).
  const FAB_ICON_BOX = 25;
  const EDIT_FAB_ICONS = {
    undo: { vb: 16, d: 'M11.758 5.345H2.484l3.121-3.11a.721.721 0 0 0 0-1.023.729.729 0 0 0-1.028 0L.214 5.557a.722.722 0 0 0 .03 1.053l4.364 3.863a.73.73 0 0 0 1.027-.061.722.722 0 0 0-.061-1.023L2.641 6.793h9.117c1.537 0 2.787 1.516 2.787 3.38 0 1.863-1.25 3.379-2.787 3.379H8.263a.726.726 0 0 0-.727.724c0 .4.325.724.727.724h3.495C14.098 15 16 12.834 16 10.172c0-2.662-1.903-4.827-4.242-4.827' },
    redo: { vb: 16, d: 'M15.787 5.557l-4.364-4.345a.73.73 0 0 0-1.028 0 .722.722 0 0 0 0 1.025l3.122 3.108H4.243C1.903 5.345 0 7.51 0 10.172S1.903 15 4.243 15h3.494a.726.726 0 0 0 .728-.724c0-.4-.326-.724-.728-.724H4.243c-1.538 0-2.788-1.516-2.788-3.38 0-1.862 1.25-3.379 2.788-3.379h9.117L10.427 9.39a.72.72 0 0 0-.062 1.022.726.726 0 0 0 1.027.06l4.364-3.862a.72.72 0 0 0 .031-1.053' },
    cut: { vb: 25, d: 'M12.695 20.404c-.211.8-.668 1.484-1.19 1.788-.234.133-.45.174-.64.124-.591-.162-.996-1.308-.655-2.604.212-.8.667-1.485 1.191-1.787a.945.945 0 0 1 .474-.145c.057 0 .112.006.165.02.59.162.996 1.31.655 2.604zm-6.63-7.814c-.302.522-.987.979-1.788 1.19-1.295.339-2.442-.064-2.602-.652-.052-.193-.01-.408.125-.64.301-.525.987-.981 1.788-1.192.327-.086.645-.125.94-.125.871 0 1.542.337 1.661.778.052.192.011.408-.124.64zm16.03-1.829l1.894-4.203-8.79 2.234L17.433 0l-4.204 1.896-1.344 7.74-4.477 1.136c-.78-1.072-2.46-1.545-4.242-1.075C1.937 10.022.89 10.756.37 11.66c-.36.62-.458 1.277-.288 1.901.338 1.242 1.653 2 3.24 2A5.42 5.42 0 0 0 4.7 15.38c1.23-.325 2.275-1.059 2.798-1.963.198-.342.307-.695.347-1.048l4.512-1.147.263.152.15.262-1.146 4.512a2.666 2.666 0 0 0-1.048.346c-.905.523-1.639 1.568-1.963 2.799-.57 2.157.23 4.186 1.819 4.618.197.053.398.08.6.08a2.59 2.59 0 0 0 1.3-.368c.905-.523 1.638-1.567 1.963-2.797.47-1.783-.005-3.464-1.077-4.242l1.139-4.476 7.739-1.346z' },
    copy: { vb: 25, d: 'M20.5 18H10c-1.654 0-3-1.346-3-3V4.5c0-1.654 1.346-3 3-3h7.5v5.25c0 .414.336.75.75.75h5.25V15c0 1.654-1.346 3-3 3m-6.185 5.5H5.967A4.472 4.472 0 0 1 1.5 19.032v-8.347c0-2.304 1.758-4.183 4-4.42V15c0 2.481 2.019 4.5 4.5 4.5h8.735c-.237 2.241-2.117 4-4.42 4M24.993 6.751a.742.742 0 0 0-.213-.531l-.596-.596L19.108.236A.746.746 0 0 0 18.563 0H10a4.505 4.505 0 0 0-4.5 4.5v.241C2.428 4.98 0 7.552 0 10.685v8.347A5.974 5.974 0 0 0 5.967 25h8.348c3.133 0 5.704-2.428 5.943-5.5h.242c2.481 0 4.5-2.019 4.5-4.5V6.787c0-.013-.006-.024-.007-.036' },
    paste: { vb: 25, d: 'M21.765 14.139a3.633 3.633 0 0 1-3.63 3.63h-6.441a.812.812 0 0 0 0 1.623h4.955c-.189 1.934-1.805 3.456-3.786 3.456H5.446a3.828 3.828 0 0 1-3.822-3.823V11.61c0-1.983 1.521-3.599 3.455-3.787v4.422a.812.812 0 0 0 1.624 0V6.336a3.634 3.634 0 0 1 3.63-3.63h7.803a3.633 3.633 0 0 1 3.63 3.63v7.803zm-3.63-13.056h-7.801c-2.85 0-5.165 2.283-5.24 5.114C2.257 6.384 0 8.726 0 11.61v7.416a5.452 5.452 0 0 0 5.446 5.446h7.417c2.882 0 5.225-2.257 5.41-5.092 2.832-.076 5.116-2.39 5.116-5.24V6.336a5.26 5.26 0 0 0-5.253-5.253z M15.026 13.254v-3.81h-3.808l1.143 1.144-6.656 6.656 1.524 1.523 6.654-6.656z' },
    center: { vb: 25, d: 'M16.5498,12.5286538 C16.5498,14.6526923 14.7588,16.3748077 12.5498,16.3748077 C10.3408,16.3748077 8.5498,14.6526923 8.5498,12.5286538 C8.5498,10.4046154 10.3408,8.6825 12.5498,8.6825 C14.7588,8.6825 16.5498,10.4046154 16.5498,12.5286538 M13.5498,20.1542308 L13.5498,19.2311538 C13.5498,18.6994231 13.1018,18.2696154 12.5498,18.2696154 C11.9978,18.2696154 11.5498,18.6994231 11.5498,19.2311538 L11.5498,20.1542308 C7.9378,19.7176923 5.0728,16.9628846 4.6188,13.4898077 L5.9998,13.4898077 C6.5518,13.4898077 6.9998,13.06 6.9998,12.5282692 C6.9998,11.9975 6.5518,11.5667308 5.9998,11.5667308 L4.6188,11.5667308 C5.0728,8.09461538 7.9378,5.33884615 11.5498,4.90326923 L11.5498,5.76961538 C11.5498,6.30038462 11.9978,6.73115385 12.5498,6.73115385 C13.1018,6.73115385 13.5498,6.30038462 13.5498,5.76961538 L13.5498,4.90326923 C17.1618,5.33884615 20.0268,8.09461538 20.4808,11.5667308 L18.9998,11.5667308 C18.4478,11.5667308 17.9998,11.9975 17.9998,12.5282692 C17.9998,13.06 18.4478,13.4898077 18.9998,13.4898077 L20.4808,13.4898077 C20.0268,16.9628846 17.1618,19.7176923 13.5498,20.1542308 M23.9998,11.5667308 L22.4988,11.5667308 C22.0288,7.03115385 18.2668,3.41480769 13.5498,2.96192308 L13.5498,0.961923077 C13.5498,0.430192308 13.1018,0.000384615385 12.5498,0.000384615385 C11.9978,0.000384615385 11.5498,0.430192308 11.5498,0.961923077 L11.5498,2.96192308 C6.8328,3.41480769 3.0708,7.03115385 2.6008,11.5667308 L0.9998,11.5667308 C0.4478,11.5667308 -0.0002,11.9975 -0.0002,12.5282692 C-0.0002,13.06 0.4478,13.4898077 0.9998,13.4898077 L2.6008,13.4898077 C3.0708,18.0263462 6.8328,21.6426923 11.5498,22.0955769 L11.5498,24.0388462 C11.5498,24.5696154 11.9978,25.0003846 12.5498,25.0003846 C13.1018,25.0003846 13.5498,24.5696154 13.5498,24.0388462 L13.5498,22.0955769 C18.2668,21.6426923 22.0288,18.0263462 22.4988,13.4898077 L23.9998,13.4898077 C24.5518,13.4898077 24.9998,13.06 24.9998,12.5282692 C24.9998,11.9975 24.5518,11.5667308 23.9998,11.5667308' },
    zoom_in: { vb: 25, d: 'M19,11.5 L13.5,11.5 L13.5,6 C13.5,5.448 13.052,5 12.5,5 C11.948,5 11.5,5.448 11.5,6 L11.5,11.5 L6,11.5 C5.448,11.5 5,11.948 5,12.5 C5,13.052 5.448,13.5 6,13.5 L11.5,13.5 L11.5,19 C11.5,19.552 11.948,20 12.5,20 C13.052,20 13.5,19.552 13.5,19 L13.5,13.5 L19,13.5 C19.552,13.5 20,13.052 20,12.5 C20,11.948 19.552,11.5 19,11.5 M12.5,2 C6.71,2 2,6.71 2,12.5 C2,18.29 6.71,23 12.5,23 C18.29,23 23,18.29 23,12.5 C23,6.71 18.29,2 12.5,2 M12.5,25 C5.607,25 0,19.393 0,12.5 C0,5.607 5.607,0 12.5,0 C19.393,0 25,5.607 25,12.5 C25,19.393 19.393,25 12.5,25' },
    zoom_out: { vb: 25, d: 'M19 13.5H6a1 1 0 0 1 0-2h13a1 1 0 0 1 0 2 M12.5 2C6.71 2 2 6.71 2 12.5S6.71 23 12.5 23 23 18.29 23 12.5 18.29 2 12.5 2m0 23C5.607 25 0 19.393 0 12.5S5.607 0 12.5 0 25 5.607 25 12.5 19.393 25 12.5 25' },
    // The delete button has no separate rendering (the trashcan handles deletion); this path stays only as a spare.
    delete: { vb: 20, d: 'M5 6 H15 M8 6 V4 H12 V6 M7 6 L7.7 17 H12.3 L13 6 M9 9 V14 M11 9 V14' },
  };

  // Shared button-building helper for EditFabControls and ZoomFabControls: one dark circle +
  // a centered solid icon + a <title> tooltip. Returns { group, titleEl }.
  function appendFabButton(parentG, iconDef, x, y, size, titleText, onClick) {
    const group = Blockly.utils.dom.createSvgElement('g', {
      class: 'bk-svg-fab-group', transform: `translate(${x}, ${y})`,
    }, parentG);
    Blockly.utils.dom.createSvgElement('circle', {
      class: 'bk-svg-fab-circle',
      cx: size / 2, cy: size / 2, r: size / 2 - 1,
    }, group);
    const scale = FAB_ICON_BOX / iconDef.vb;
    const off = (size - FAB_ICON_BOX) / 2;
    const iconG = Blockly.utils.dom.createSvgElement('g', {
      transform: `translate(${off}, ${off}) scale(${scale})`,
    }, group);
    Blockly.utils.dom.createSvgElement('path', {
      class: 'bk-svg-fab-icon',
      d: iconDef.d,
      'fill-rule': 'evenodd',
    }, iconG);
    const titleEl = Blockly.utils.dom.createSvgElement('title', {}, group);
    titleEl.textContent = titleText;
    group.addEventListener('pointerdown', (evt) => {
      evt.preventDefault();
      evt.stopPropagation();
    });
    group.addEventListener('click', (evt) => {
      evt.preventDefault();
      evt.stopPropagation();
      onClick();
    });
    return { group, titleEl };
  }

  // ------------------------------------------------------------------
  // Trashcan restyle — aligned with the Code Lab dark round trashcan.
  //
  // Code Lab (webpack module a073 in chunk-843ccf1e) replaces the whole
  // Trashcan.prototype.createDom: a 60px dark #333 circle base + self-drawn vector
  // lid/body paths (fill #A8A8AC, #0ED3FF when open, lid has a 700ms open/close animation).
  //
  // This code does not replace the whole thing. It only reskins: call the original Blockly 13.2.0 createDom
  // first (this keeps a11y role/label, focus ring, sprite clippath, pointerdown/up bindings,
  // flyout restore, and drag-to-delete hit-test), then:
  //   1. insert a dark circle base (as the first child);
  //   2. remove the two sprite <image> elements and add the Code Lab-style vector lid/body paths
  //      (extracted from codelab.nuwarobotics.com, original ~24-unit space, scale 1.4
  //      like Code Lab).
  // The visual footprint stays 47x60 (matching the assumptions in getBoundingRectangle/position/getClientRect),
  // so the avoidance math in EditFabControls/ZoomFabControls needs no change.
  // The open/close color change relies on the .blocklyTrash.blocklyTrashOpen rules in blockly.css (Blockly 13
  // setLidOpen() already toggles that class).
  //
  // Timing: this IIFE runs as soon as the script loads. blockly-page.js runs
  // initWorkspace()/Blockly.inject() on DOMContentLoaded, so the patch always lands
  // before addTrashcan()->createDom().
  // ------------------------------------------------------------------
  (function patchAlphaTrashcan() {
    if (typeof Blockly === 'undefined' || !Blockly.Trashcan || !Blockly.Trashcan.prototype) return;
    const proto = Blockly.Trashcan.prototype;
    if (proto.__alphaTrashPatched) return;
    proto.__alphaTrashPatched = true;
    const origCreateDom = proto.createDom;
    // Code Lab trashcan lid (lid bar + handle) and body (body + two grooves).
    const TRASH_LID_PATHS = [
      'M21.192 2.265H1a1 1 0 0 1 0-2h20.192a1 1 0 0 1 0 2',
      'M15.17 2H8.848a1 1 0 0 1 0-2h6.324a1 1 0 0 1 0 2',
    ];
    const TRASH_BODY_PATHS = [
      'M18.358 24.62H5.534c-1.556 0-2.823-1.371-2.823-3.058V7.175a1 1 0 0 1 2 0v14.387c0 .574.377 1.059.823 1.059h12.824c.446 0 .823-.485.823-1.06V7.176a1 1 0 0 1 2 0v14.387c0 1.687-1.266 3.059-2.823 3.059',
      'M8.847 20.034a1 1 0 0 1-1-1V8.76a1 1 0 1 1 2 0v10.274a1 1 0 0 1-1 1M15.17 20.034a1 1 0 0 1-1-1V8.578a1 1 0 0 1 2 0v10.456a1 1 0 0 1-1 1',
    ];
    proto.createDom = function () {
      const g = origCreateDom.call(this);
      const NS = 'http://www.w3.org/2000/svg';
      // 1. Dark circle base: the largest circle inside the 47x60 rect (center 23.5,30, r=23).
      const disc = document.createElementNS(NS, 'circle');
      disc.setAttribute('cx', '23.5');
      disc.setAttribute('cy', '30');
      disc.setAttribute('r', '23');
      disc.setAttribute('class', 'trash-bg');
      g.insertBefore(disc, g.firstChild);
      // 2. Remove the sprite <image> elements (two: one body, one lid).
      const imgs = g.querySelectorAll('image');
      for (let i = 0; i < imgs.length; i++) {
        const im = imgs[i];
        if (im.parentNode) im.parentNode.removeChild(im);
      }
      // 3. Code Lab icon art (about 22x24.6, scale 1.4 -> 30.8x34.4).
      // Keep the body in the main group as before. The lid must go inside the native g.blocklyTrashLid
      // wrapper: the Blockly 13 open/close animation is a CSS rotate on that wrapper
      // (closed 5deg / open 45deg). Putting the lid in our own group sent the animation to
      // an empty shell (that is why "the animation is gone"). Both parts use the same
      // translate(8.1 12.8) scale(1.4) so lid and body line up (lid x 9.5..37.8,
      // y about 13.2..16, exactly as in the previous layout).
      const ICON_TRANSFORM = 'translate(8.1 12.8) scale(1.4)';
      const iconG = Blockly.utils.dom.createSvgElement('g', {
        class: 'trashcan-icon', transform: ICON_TRANSFORM,
      }, g);
      const bodyG = Blockly.utils.dom.createSvgElement('g', { class: 'trashcan-body' }, iconG);
      TRASH_BODY_PATHS.forEach(function (d) {
        Blockly.utils.dom.createSvgElement('path', { d: d }, bodyG);
      });
      const lidWrap = g.querySelector('.blocklyTrashLid');
      if (lidWrap) {
        // Remove the now-empty inner svg viewport (the images are gone, so it would render nothing).
        const oldSvg = lidWrap.querySelector('svg');
        if (oldSvg && oldSvg.parentNode) oldSvg.parentNode.removeChild(oldSvg);
      }
      const lidParent = lidWrap || iconG; // if a future Blockly changes the DOM, fall back to the main group
      const lidPos = Blockly.utils.dom.createSvgElement('g', { transform: ICON_TRANSFORM }, lidParent);
      const capG = Blockly.utils.dom.createSvgElement('g', { class: 'trashcan-cap' }, lidPos);
      TRASH_LID_PATHS.forEach(function (d) {
        Blockly.utils.dom.createSvgElement('path', { d: d }, capG);
      });
      return g;
    };
  })();

  // ------------------------------------------------------------------
  // EditFabControls — one IPositionable component that wraps five small buttons
  // (undo/redo/cut/copy/paste) and positions them as a group, behaving like the Blockly
  // ZoomControls: several buttons inside, but ComponentManager sees one
  // component, and one getBoundingRectangle() covers the whole group.
  // Note: editAction() below keeps a 'delete' case (checkAndDelete()) that no
  // button here triggers — deletion is deliberately button-free, the Blockly trashcan does it (see
  // the trashcan restyle patch below). The case stays so a future independent delete button
  // can use it directly. It is not dead code left by mistake.
  //
  // 2026-09: aligned with the Code Lab quick-edit row — 5 evenly spaced buttons in one line, no dividers,
  // 42px dark circles, solid icons (see EDIT_FAB_ICONS above), and no disabled state (always clickable;
  // with no selection, cut/copy/paste are silent no-ops, same as Code Lab).
  // ------------------------------------------------------------------
  class EditFabControls {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaEditFabControls';
      this.top = 0;
      this.left = 0;
      // Layout: 5 separate dark round buttons (42px diameter desktop / 34px mobile, Code Lab style), each with its own circle
      // background. Buttons are separated only by an even gap (GAP=15 desktop / 8 mobile), in one horizontal
      // row. No groups and no divider lines (the old { sep:true } + GROUP_GAP was
      // removed, because Code Lab spaces all 5 evenly, with no special divider between undo/redo and
      // cut/copy/paste.
      // Mobile needs smaller sizes: in portrait, the toolbox takes ~110px and leaves ~240px of canvas,
      // so the 42px group (270px wide) would stick out past the toolbox and get clipped (mobile screenshots showed
      // only two buttons in the bottom bar). The 34px group is 202px wide and fits.
      const isMobile = (typeof window !== 'undefined' && window.matchMedia &&
        window.matchMedia('(max-width: 900px)').matches);
      this.BUTTON_SIZE = isMobile ? 34 : 42;
      this.GAP = isMobile ? 8 : 15;
      this.MARGIN_HORIZONTAL = isMobile ? 8 : 12;
      this.MARGIN_VERTICAL = isMobile ? 8 : 12;
      this.buttons = [
        { action: 'undo', icon: 'undo', titleKey: 'page_edit_undo_title' },
        { action: 'redo', icon: 'redo', titleKey: 'page_edit_redo_title' },
        { action: 'cut', icon: 'cut', titleKey: 'page_edit_cut_title' },
        { action: 'copy', icon: 'copy', titleKey: 'page_edit_copy_title' },
        { action: 'paste', icon: 'paste', titleKey: 'page_edit_paste_title' },
      ];
      this.buttonEls = {}; // action -> { group, circle, titleEl }
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 3, // the Trashcan is usually weight 2 and ZoomControls weight 1 — place
                   // ours after them (a larger number positions later), so the two Blockly-native
                   // components take their usual corner spots first. Our group then gets pushed
                   // slightly higher automatically by bumpPositionRect() and never overlaps them.
      });
      this.workspace.resizeContents();
    }

    getGroupWidth() {
      // 5 buttons in one even row: n circles + (n-1) gaps.
      const n = this.buttons.length;
      return n * this.BUTTON_SIZE + (n - 1) * this.GAP;
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', { class: 'bk-svg-fab-bar' }, null);
      // There is no shared background pill — each button's own circle is its background (separate dark
      // round buttons, Code Lab style), with even white space and no groups or divider lines.
      let x = 0;
      for (const b of this.buttons) {
        const made = appendFabButton(this.svgGroup, EDIT_FAB_ICONS[b.icon], x, 0,
          this.BUTTON_SIZE, t(b.titleKey), () => editAction(b.action));
        this.buttonEls[b.action] = made;
        x += this.BUTTON_SIZE + this.GAP;
      }
      svg.appendChild(this.svgGroup);
    }

    // 2026-09: aligned with Code Lab — the floating buttons have no disabled state; all 5 are always clickable.
    // With no selected block, pressing cut/copy/paste is a silent no-op (see the editAction guards),
    // so this method became a no-op. The name stays so old call sites need no change (nothing below
    // calls it anymore; it is kept only as documentation).
    updateButtonStates() { /* no-op: aligned with Code Lab, every button stays clickable */ }

    // After a language switch, the title (the SVG <title> tooltip) must switch too — this mirrors
    // the HTML approach in blockly-i18n.js applyUiTextLocale(); this method exists for it to call.
    updateI18n() {
      for (const b of this.buttons) {
        const el = this.buttonEls[b.action];
        if (el) el.titleEl.textContent = t(b.titleKey);
      }
    }

    getBoundingRectangle() {
      const width = this.getGroupWidth();
      const height = this.BUTTON_SIZE;
      return new Blockly.utils.Rect(this.top, this.top + height, this.left, this.left + width);
    }

    position(uiMetrics, savedPositions) {
      const width = this.getGroupWidth();
      const size = new Blockly.utils.Size(width, this.BUTTON_SIZE);
      const trashcan = this.workspace.trashcan;
      if (trashcan && typeof trashcan.getBoundingRectangle === 'function') {
        // Place the group right against the left side of the trashcan, vertically centered on it — this is the Code Lab
        // layout (the button group and the trashcan sit side by side in one row, not in opposite canvas corners). The old
        // getCornerOppositeToolbox + bumpPositionRect "auto-avoid" logic computed a wrong position in this
        // layout (the toolbox is not in a corner), so the whole group ran off the canvas and
        // showed nothing at all. So use the most direct, reliable method instead: read the trashcan's own
        // getBoundingRectangle() and compute from it.
        const tRect = trashcan.getBoundingRectangle();
        const tHeight = tRect.bottom - tRect.top;
        this.left = tRect.left - this.MARGIN_HORIZONTAL - width;
        // On a narrow mobile canvas: if the group is wider than the free space, do not reach into the toolbox or out of bounds.
        // Clamp it to the canvas left edge (outside the toolbox) — better than clipping the group to two buttons.
        if (this.left < this.MARGIN_HORIZONTAL) this.left = this.MARGIN_HORIZONTAL;
        this.top = tRect.top + (tHeight - this.BUTTON_SIZE) / 2;
      } else {
        // Fallback: the trashcan is not built yet (this should never happen — addTrashcan() always runs before
        // this component registers), so keep the original corner placement logic as a failsafe.
        const corner = Blockly.uiPosition.getCornerOppositeToolbox(this.workspace, uiMetrics);
        let rect = Blockly.uiPosition.getStartPositionRect(
          corner, size, this.MARGIN_HORIZONTAL, this.MARGIN_VERTICAL, uiMetrics, this.workspace);
        const bumpDir = corner.vertical === Blockly.uiPosition.verticalPosition.TOP
          ? Blockly.uiPosition.bumpDirection.DOWN
          : Blockly.uiPosition.bumpDirection.UP;
        rect = Blockly.uiPosition.bumpPositionRect(rect, this.MARGIN_VERTICAL, bumpDir, savedPositions);
        this.top = rect.top;
        this.left = rect.left;
      }
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  // ------------------------------------------------------------------
  // ZoomFabControls — aligned with the Code Lab editor-tool: a vertical group of 3 matching dark circles at bottom right
  // (center / zoom in / zoom out), stacked directly above the trashcan. Like EditFabControls this is an
  // IPositionable component, and it shares appendFabButton() to build buttons, so it visually matches the edit
  // row. The Blockly built-in zoom controls are switched off
  // (see zoom.controls:false in blockly-page.js); this group replaces them.
  // ------------------------------------------------------------------
  class ZoomFabControls {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaZoomFabControls';
      this.top = 0;
      this.left = 0;
      // Same size and same family as EditFabControls (desktop 42 / mobile 34),
      // see the mobile screenshot above: landscape height is tight, and a vertical 42px group (156px + the trashcan) would collide.
      const isMobileZoom = (typeof window !== 'undefined' && window.matchMedia &&
        window.matchMedia('(max-width: 900px)').matches);
      this.BUTTON_SIZE = isMobileZoom ? 34 : 42; // same size as EditFabControls, same family
      this.GAP = isMobileZoom ? 8 : 15;
      this.MARGIN_ABOVE_TRASH = isMobileZoom ? 10 : 16; // gap to the trashcan top (Code Lab editor-tool keeps about 18px above the trashcan)
      this.buttons = [
        { action: 'center', icon: 'center', titleKey: 'page_edit_center_title' },
        { action: 'zoom_in', icon: 'zoom_in', titleKey: 'page_edit_zoom_in_title' },
        { action: 'zoom_out', icon: 'zoom_out', titleKey: 'page_edit_zoom_out_title' },
      ];
      this.buttonEls = {}; // action -> { group, titleEl }
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 4, // position after EditFabControls (3), so the two groups never collide
      });
      this.workspace.resizeContents();
    }

    getGroupHeight() {
      const n = this.buttons.length;
      return n * this.BUTTON_SIZE + (n - 1) * this.GAP;
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', { class: 'bk-svg-fab-bar' }, null);
      let y = 0;
      for (const b of this.buttons) {
        const made = appendFabButton(this.svgGroup, EDIT_FAB_ICONS[b.icon], 0, y,
          this.BUTTON_SIZE, t(b.titleKey), () => editAction(b.action));
        this.buttonEls[b.action] = made;
        y += this.BUTTON_SIZE + this.GAP;
      }
      svg.appendChild(this.svgGroup);
    }

    updateI18n() {
      for (const b of this.buttons) {
        const el = this.buttonEls[b.action];
        if (el) el.titleEl.textContent = t(b.titleKey);
      }
    }

    getBoundingRectangle() {
      const height = this.getGroupHeight();
      return new Blockly.utils.Rect(this.top, this.top + height, this.left, this.left + this.BUTTON_SIZE);
    }

    position(uiMetrics, savedPositions) {
      const height = this.getGroupHeight();
      const trashcan = this.workspace.trashcan;
      if (trashcan && typeof trashcan.getBoundingRectangle === 'function') {
        // Place the group directly above the trashcan, centered horizontally on it — the Code Lab editor-tool spot
        // (bottom:110 right:29, i.e. above the trashcan). Read the trashcan's own rect and compute, like
        // EditFabControls: direct and reliable, not through the uiPosition avoidance pipeline.
        const tRect = trashcan.getBoundingRectangle();
        const tWidth = tRect.right - tRect.left;
        this.left = tRect.left + (tWidth - this.BUTTON_SIZE) / 2;
        this.top = tRect.top - this.MARGIN_ABOVE_TRASH - height;
        // Short landscape mobile canvas: do not let the group run past the top edge and get clipped; clamp
        // it inside the top edge (slight overlap is still better than losing whole buttons).
        if (this.top < 8) this.top = 8;
      } else {
        // Fallback: the trashcan is not built yet, so use corner placement as a failsafe.
        const size = new Blockly.utils.Size(this.BUTTON_SIZE, height);
        const corner = Blockly.uiPosition.getCornerOppositeToolbox(this.workspace, uiMetrics);
        let rect = Blockly.uiPosition.getStartPositionRect(
          corner, size, 12, 12, uiMetrics, this.workspace);
        const bumpDir = corner.vertical === Blockly.uiPosition.verticalPosition.TOP
          ? Blockly.uiPosition.bumpDirection.DOWN
          : Blockly.uiPosition.bumpDirection.UP;
        rect = Blockly.uiPosition.bumpPositionRect(rect, 12, bumpDir, savedPositions);
        this.top = rect.top;
        this.left = rect.left;
      }
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  // 2026-09: the old SidePanelToggleControl (a floating SVG ›/‹ button, 18x36, too small to press)
  // was deleted — aligned with the Code Lab simulator-area approach: when the sidebar collapses, keep a 32px wide
  // edge strip with a dark ribbon trigger button (see #bkSideTrigger in blockly.html and
  // .bk-side-trigger in blockly.css). It is always clickable, so it no longer depends on Blockly canvas placement.

  let editFabControls = null;
  let zoomFabControls = null;

  function currentSelectedBlock() {
    return (Blockly.common && Blockly.common.getSelected) ? Blockly.common.getSelected() : null;
  }

  function editAction(action) {
    if (!workspace) return;
    switch (action) {
      case 'undo':
        workspace.undo(false);
        break;
      case 'redo':
        workspace.redo();
        break;
      case 'copy': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable() && Blockly.clipboard && Blockly.clipboard.copy) {
          Blockly.clipboard.copy(b);
        }
        break;
      }
      case 'cut': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable() && b.isMovable() && Blockly.clipboard && Blockly.clipboard.copy) {
          Blockly.clipboard.copy(b);
          b.checkAndDelete();
        }
        break;
      }
      case 'paste':
        if (Blockly.clipboard && Blockly.clipboard.paste) {
          Blockly.clipboard.paste(workspace);
        }
        break;
      // 2026-09: ZoomFabControls vertical group (aligned with the Code Lab editor-tool).
      case 'center': {
        // Back to the default view: reset the zoom to the startScale used at inject time, then center the canvas.
        // (Code Lab calls setScale(1) because its startScale is 1; here read this
        // workspace's own startScale instead, which means the same thing.)
        const zs = workspace.options && workspace.options.zoomOptions;
        workspace.setScale(zs && zs.startScale ? zs.startScale : 1);
        workspace.scrollCenter();
        break;
      }
      case 'zoom_in':
        workspace.zoomCenter(1);
        break;
      case 'zoom_out':
        workspace.zoomCenter(-1);
        break;
      case 'delete': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable()) {
          b.checkAndDelete();
        }
        break;
      }
      default:
        return;
    }
    // 2026-09: aligned with Code Lab — the floating buttons have no disabled state, so after an action there is no
    // need to refresh button states (this used to call editFabControls.updateButtonStates(),
    // and that method is now a no-op, so the call was deleted).
  }

  // ------------------------------------------------------------------
  // Public exports
  // ------------------------------------------------------------------
  window.AlphaBlockly = {
    init: function (ws) {
      workspace = ws;
      restoreFromLocalStorage();
      rewireEventHandlers();
      refreshSavedProgramDropdown();
      // Both rewire and autosave use debounce: a burst of block changes (a drag, a programmatic build,
      // undo/redo) fires many non-UI change events in a short time, and handling each one immediately wastes work
      // and floods the log. Debounce until this change burst stops, then do one pass.
      let rewireTimer = null;
      let saveTimer = null;
      workspace.addChangeListener(function (e) {
        if (e.isUiEvent) return;
        clearTimeout(rewireTimer);
        rewireTimer = setTimeout(rewireEventHandlers, 300);
        clearTimeout(saveTimer);
        saveTimer = setTimeout(autoSaveToLocalStorage, 800);
      });
      // Selecting or deselecting a block is a UI event — after the 2026-09 Code Lab alignment, the floating buttons
      // have no disabled state, so no separate listener needs to track SELECTED and toggle
      // cut/copy. That whole section was deleted.
      // Build the two IPositionable component groups (see the long comments on the EditFabControls/
      // ZoomFabControls classes above). This can only happen after the workspace is injected and has
      // a real SVG root, so it runs here inside init(),
      // not at module load time.
      editFabControls = new EditFabControls(workspace);
      zoomFabControls = new ZoomFabControls(workspace);
      // 2026-09: the action list + music list are now fetched once, automatically, when blockly-page.js opens the page
      // (fire-and-forget). Neither dropdown fetches on its own here anymore, and the top-bar manual buttons were removed.
    },
    run: runProgram,
    stop: stopProgram,
    onWsEvent: onWsEvent,
    saveNamed: saveNamedProgram,
    loadNamed: loadNamedProgram,
    deleteNamed: deleteNamedProgram,
    exportXmlFile: exportXmlFile,
    importXmlFile: importXmlFile,
    refreshActionDropdown: refreshActionDropdown,
    refreshMusicDropdown: refreshMusicDropdown,
    refreshSavedProgramDropdown: refreshSavedProgramDropdown, // lets blockly-i18n.js re-fetch it on a language switch, so the saved-program placeholder re-renders in the new language
    editAction: editAction,
    // After a language switch (blockly-i18n.js setUiLanguage()), the <title> tooltip text of these two SVG
    // component groups must switch too. The sidebar collapse button (#bkSideTrigger) is plain
    // HTML with data-i18n-attr="title", so it follows the language automatically; nothing is needed here.
    refreshEditControlsI18n: function () {
      if (editFabControls) editFabControls.updateI18n();
      if (zoomFabControls) zoomFabControls.updateI18n();
    },
    toggleSidePanel: function () {
      const main = document.querySelector('.bk-main');
      if (!main) return;
      const collapsed = !main.classList.contains('bk-side-collapsed');
      main.classList.toggle('bk-side-collapsed', collapsed);
      try { localStorage.setItem('blocklySideCollapsed', collapsed ? '1' : '0'); } catch (e) { /* ignore: if nothing is saved, the next load defaults to expanded */ }
      // The .bk-side flex-basis has a CSS transition (0.18s), so the canvas width must change along with the
      // animation, and several resize calls cover the whole transition (Blockly.svgResize makes
      // ComponentManager reposition everything, so here we only call it at the right time points). The sidebar
      // collapse button (#bkSideTrigger) is HTML, and the arrow direction is pure CSS
      // (rotate) that turns with .bk-side-collapsed; no JS set is needed.
      if (workspace) {
        Blockly.svgResize(workspace);
        setTimeout(function () { Blockly.svgResize(workspace); }, 100);
        setTimeout(function () { Blockly.svgResize(workspace); }, 200);
      }
    },
    setSidePanelCollapsedInitial: function (collapsed) {
      // Right after the page loads, apply the last collapsed/expanded state stored in localStorage. Do not
      // use toggleSidePanel() (it carries a setTimeout cascade for the 0.18s transition);
      // just set the initial state directly, with no animation.
      const main = document.querySelector('.bk-main');
      if (!main) return;
      main.classList.toggle('bk-side-collapsed', collapsed);
    },
    clearWorkspace: function () {
      if (workspace) workspace.clear();
    }
  };
})();


