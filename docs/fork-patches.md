# Fork patches (alpha2-brain#146)

This file lists the patches of this fork on top of upstream `ubtech-open-alpha2`.
Base of this list: the merge of upstream `b6fe8be` (2026-11-10) into the branch `146-sync`
(alpha2-brain#148, slice 1 of #146).

State values:
- **fork only**: the patch stays in this fork.
- **upstream candidate**: the patch can go upstream as a pull request.

The themes come from the fork commits since the common ancestor `9ee0d28` (76 commits).
The text of #146 itself was not available to the author of this file. Check the rows against #146 step 3.

## Patch themes

| # | Theme | Main files | State | Source |
|---|-------|-----------|-------|--------|
| 1 | Motion gate: servo, ubx play, voice and debug serial routes refuse motion by default (in memory only) | `MotionGate`, `PlayRefusal`, `ApiDispatcher`, `ActionDirect`, `UbxApi` | fork only (owner safety rule) | alpha2-brain#113, #121, #130, #144 |
| 2 | Chest safety guard: sit-down trap cmd 0x19 and 0x40, whole-frame check, short-form LEN below 7, `allow_sit_down` | `ChestSafety`, `DirectChestController`, `ApiValidator` | upstream candidate | alpha2-brain#24, #25, D-22 |
| 3 | Servo read stream at 20 Hz on `/ws`, and its move/read timing (pause, wait for the cycle in flight) | `ServoReadStream`, `ServoStreamCore` | upstream candidate | alpha2-rl#3, #217, alpha2-brain#26, #29 |
| 4 | Wake word, local voice commands, follow-up window, half-duplex microphone, push-to-talk key | `WakeController`, `WakeCommandMatch`, `VoskController`, `XiaozhiBridge` | fork only | alpha2-brain#16, #39 |
| 5 | Voice languages: English by default, one setting for Dutch (`VoiceLanguages`) | `VoiceLanguages` | fork only | alpha2-brain#88 |
| 6 | Stop word handling: stop is ignored when the answer text itself has a stop word, stop echo guard | `StopWordMatcher`, `StopEchoGuard` | upstream candidate | alpha2-brain#52 |
| 7 | 5-mic capture from the FPGA sound card. No endless block. `mic5_tdoa` takes argparse. No LAN address in the repo | `miccap.c`, `MicSlots`, `scripts/mic5_tdoa.py`, `docs/mic-5ch.md` | upstream candidate | alpha2-brain#23, #27, #28, #80, #85 |
| 8 | Head keys: click codes 0x3c01 and 0x3d01, early tick feedback, click log line | `HeadKeyClick`, `HeadKeyPoller` | fork only (upstream has its own 60/61/66 handling) | alpha2-brain#45, #53 |
| 9 | Chest firmware: `chest/version` takes only a reply with command byte 0x33; servo firmware writes refused in debug serial | `ChestQuery`, `ChestReplyMatch` | upstream candidate | alpha2-brain#130 |
| 10 | Servo power and power save as two methods (`setServoPower`, `setPowerSave(boolean, boolean)`) | `DirectChestController`, `SleepModePolicy` | fork only | alpha2-brain#24, #30 |
| 11 | LED: default brightness for a color-only call, head LEDs off when the mic stream ends | `LedCenter` | upstream candidate | alpha2-brain#93, #105 |
| 12 | Sensors: the motion sensor answers clear after the app start, not unknown | PIR status code (file not checked) | upstream candidate | fork PR #20 |
| 13 | MCP and Xiaozhi: play an action by name, filler gesture setting, reply playback for typed text | `XiaozhiBridge`, MCP tools | upstream candidate | |
| 14 | S-23 English texts in the web panel, the spec, the scripts, and the shown and spoken texts. Strings and dictionary stay English. Comments follow upstream (D-216) | `app/src/main/assets/web`, `openapi/`, `scripts/`, `semantic_*.json` | fork only | alpha2-brain#95, #96, S-23 |
| 15 | Tests and CI: chest guard vector test, `check-openapi` runs on `LedCenter.java` and `app/src/test`, YAML 1.2 booleans in the generator | `scripts/test-*.py`, `.github/workflows/check-openapi.yml`, `scripts/yaml12.py` | upstream candidate | alpha2-brain#29, #30, #61 |

## Conflict choices of the merge (alpha2-brain#148)

Rules: functional changes keep upstream and fork. S-23 strings and dictionary keep the fork English.
A conflict in a comment only takes the upstream comment (D-216).

### Java

| File | Choice |
|------|--------|
| `DirectChestController.java` | Keep `setPowerSave(boolean, boolean)` (with the 3-argument form) and both `setServoPower` methods. Drop the 1-argument `setPowerSave(boolean)` (upstream removed it). `DirectChestControllerTest` calls the 2-argument form now. |
| `VoskController.java` | Keep the fork `buildGrammar` body, `getGrammar`, `setGrammar`, `newGrammarRecognizer`. No `stripSpaces = false;` line (upstream has no such field). |
| `MainActivity.java` | Upstream `walkCenter` argument of the `ApiDispatcher` constructor and the fork `WakeController.init` call. |
| `ApiDispatcher.java` | `motionGate` and `motionRefused` plus the upstream `walkCenter` field. `servo/all` stays behind the gate. Upstream `servo/calibrate` route added. The `servo/all` move keeps `ServoReadStream.moveBetweenCycles`. |
| `UbxApi.java` | The `servoAll` move keeps `moveBetweenCycles` (fork) with the upstream comment about the 2-mic cmd52 and 5-mic cmd03 split. |
| `ActionDirect.java` | Upstream `officialBootStandup` and its constants. Upstream comments. The fork motion gate stays on the play paths. |
| `HeadKeyPoller.java` | Upstream cases for the codes 60, 61 and 66 (the same codes as `HeadKeyClick.CLICK_MINUS` and `CLICK_PLUS`: two case labels do not compile). The fork click log line stays through `logClick()`. `HeadKeyClick` and its test stay. |
| `HttpServer.java` | Upstream comment. The unused `esc` wrapper and the unused 5-argument `writeResponse` are dropped (upstream removed them, no caller). |
| `ApiValidator.java` | Upstream `parseUbxSpeedValue` range 0.5 to 2.0 and `optionalTrims20or22`. Upstream comments. |
| `ChestQuery.java` | Upstream battery version latch (cmd 57) and upstream comments. |
| `scripts/test-apivalidator.py` | Both lists: `ChestSafety.java` (fork) and `IkPose.java` with `IkTransition.java` (upstream). `HardwareVersion.java` added to the `DirectChestControllerTest` file list, because upstream `DirectChestController` needs it. |

Known risk: upstream `officialBootStandup` calls `setAllServos` directly. This call does not pass the fork motion gate
and not `moveBetweenCycles`. The owner decision D-217 resolves this risk with the stored setting
`boot_standup_enabled`. The app sends the boot stand-up pose only when this setting is true. The default is false.

### OpenAPI, web panel, spec copies

| Area | Choice |
|------|--------|
| `openapi/open-alpha2-openapi.yml` | Fork English descriptions with the upstream content: tag `walk` (English text), 22 axes, `ubx/speed` range 0.5 to 2.0, gamepad action list with the `ik:` actions, `'on'` quoted. |
| `app/src/main/assets/web/openapi.yml`, `.well-known/openapi.yml`, `well-known/openapi.yml` | Copy of the source file (same as `check-spec-copies.py --fix`). |
| `.well-known/openapi.json`, `well-known/openapi.json` | Same choices by hand: English text, version `beta 8.1`. The check script was not run (see the test notes). |
| `api-client.js` | `assertRange` for `ubx/speed` (upstream). English summary comments (the generator takes them from the spec). |
| `app-*.js` with `t("key")` (actions, camera, chest, log, mic, radio, speech, status) | Upstream `t()` call. The dictionary in `app-core.js` has an English text for each key. |
| `app-gamepad.js` | Upstream multi-action mapping, IK and walk kinds, fullscreen and campad code. Fork `uiLang` ternaries that upstream replaced are gone. |
| `app-servo.js`, `app-xiaozhi.js`, `app-core.js`, `app-status.js` | Upstream code. Upstream removed the WiFi row and `SERVO_RESET_ACTION_ID`, the activation modal stubs and the EventBus block of `app-chest.js`. English Bluetooth text kept. |
| `blockly-*.js`, `blockly.css`, `blockly.html`, `index.html`, `style.css` | Upstream code and comments. The English `title` of the Xiaozhi filler switch is kept. |
| `semantic_*.json` | Upstream `_comment` (86 ACTION entries). |

### Scripts, CI, README

| File | Choice |
|------|--------|
| `scripts/ubx_gate.py`, `ubx_to_traj.py`, `ubx_corpus_order.py` | Upstream file as a whole (`git checkout --theirs`). The fork change was a translation of 3 docstrings and 1 print text. Upstream rewrote the logic. |
| `scripts/ubx_walk_humanize.py` | Upstream code and docstrings in all 8 conflicts. |
| `scripts/generate-api-client.py` | Upstream comment text. |
| `.github/workflows/check-openapi.yml` | Both test steps: `test-chestsafety.py` and `test-headkeypoller.py`. |
| `README.md` | Both: `test-headkeypoller.py` text and the note about `VolumeStepSequenceTest`. |
