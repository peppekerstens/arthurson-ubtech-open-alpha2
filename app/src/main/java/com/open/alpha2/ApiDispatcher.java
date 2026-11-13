package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.LocalAlpha2Services;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;

/**
 * /api/alpha2/* dispatcher: a thin delegate switch; the body is in each center.
 *
 * Cross-domain cores call back through the Host seam below:
 * - speech/tts, speech/stop are directly implemented by SpeechCenter (MainActivity no longer implements Host)
 * (speech/set_mic, set_mic_keep_held go through micCenter below, direct call.)
 * (speech/offline_auto_switch, get_default_grammar go through grammarCenter below, direct call; the Host is not involved.)
 * (servo/sonar go through sonarCenter below, direct call - the three sensors values of
 * discover continue to be read via sensorState, being handed to the same SonarCenter.)
 * directChestReady/directHeaderReady are inline copies
 * (the same approach as UbxApi/LedCenter/ChestQuery/XiaozhiBridge).
 * walkie testtone/diagnose/play go through micCenter below, direct call.
 * handleSystemApi (discover + music/*, needs UbxPlayer pose + MusicController + deviceStatus.getWifiIp()) and
 * handleDirectApi (servo/led/sonar/ubx direct drive, needs LocalAlpha2Services +
 * UbxPlayer notePose).
 */
public final class ApiDispatcher {

    /**
     * Host seam: speech/tts, speech/stop are directly implemented by SpeechCenter.
     */
    public interface Host {
        HttpServer.ApiResponse handleSpeechTts(Map<String, String> query);
        HttpServer.ApiResponse handleSpeechStop();
    }

    private final Context appContext;
    private final Host host;
    private final XiaozhiBridge.HostState sensorState;
    private final ActionDirect actionDirect;
    private final UbxApi ubxApi;
    private final ChestQuery chestQuery;
    private final ChestUpgrade chestUpgrade;
    private final ServoUpgrade servoUpgrade;
    private final TtsCenter ttsCenter;
    private final VoskApi voskApi;
    private final LedCenter ledCenter;
    private final SemanticCenter semanticCenter;
    private final DeviceStatus deviceStatus;
    private final CameraApi cameraApi;
    private final AudioCenter audioCenter;
    private final RingtoneCenter ringtoneCenter;
    private final MicCenter micCenter;
    private final RobotStub robot;
    private final GrammarCenter grammarCenter;
    private final UbxPlayer ubxPlayer;
    private final MusicController musicController;
    private final LocalAlpha2Services localServices;
    // Direct calls for servo/sonar go here (placed last, by convention).
    private final SonarCenter sonarCenter;
    private final ActionsPackController actionsPackController;
    private final ApkDownloadController apkDownloadController;
    private final GamepadCenter gamepadCenter;
    // Motion gate (alpha2-brain#113): off by default, in memory only. A new dispatcher
    // (a restart of the app) starts with both switches off. Never store it.
    private final MotionGate motionGate = new MotionGate();

    /** 403 for a servo move that the motion gate refuses. */
    private static HttpServer.ApiResponse motionRefused(int status, String text) {
        android.util.Log.w("MotionGate", "refused (" + status + "): " + text);
        return new HttpServer.ApiResponse(status, "application/json; charset=utf-8", JsonUtil.okFalse(text));
    }

    /** The motion gate. WakeController asks it before a voice command moves the body (alpha2-brain#121). */
    public MotionGate motionGate() {
        return motionGate;
    }

    public ApiDispatcher(Context context, Host host, XiaozhiBridge.HostState sensorState,
            ActionDirect actionDirect, UbxApi ubxApi, ChestQuery chestQuery,
            ChestUpgrade chestUpgrade, ServoUpgrade servoUpgrade, TtsCenter ttsCenter, VoskApi voskApi,
            LedCenter ledCenter, SemanticCenter semanticCenter, DeviceStatus deviceStatus,
            CameraApi cameraApi, AudioCenter audioCenter, RingtoneCenter ringtoneCenter,
            MicCenter micCenter, RobotStub robot,
            GrammarCenter grammarCenter, UbxPlayer ubxPlayer, MusicController musicController,
            LocalAlpha2Services localServices, SonarCenter sonarCenter,
            ActionsPackController actionsPackController, ApkDownloadController apkDownloadController,
            GamepadCenter gamepadCenter) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.sensorState = sensorState;
        this.actionDirect = actionDirect;
        this.ubxApi = ubxApi;
        actionDirect.setMotionGate(motionGate); // the same gate for each path that plays an action (alpha2-brain#121)
        ledCenter.setMotionGate(motionGate); // chest move commands in debug/serial/send need the gate (alpha2-brain#130)
        this.chestQuery = chestQuery;
        this.chestUpgrade = chestUpgrade;
        this.servoUpgrade = servoUpgrade;
        this.ttsCenter = ttsCenter;
        this.voskApi = voskApi;
        this.ledCenter = ledCenter;
        this.semanticCenter = semanticCenter;
        this.deviceStatus = deviceStatus;
        this.cameraApi = cameraApi;
        this.audioCenter = audioCenter;
        this.ringtoneCenter = ringtoneCenter;
        this.micCenter = micCenter;
        this.robot = robot;
        this.grammarCenter = grammarCenter;
        this.ubxPlayer = ubxPlayer;
        this.musicController = musicController;
        this.localServices = localServices;
        this.sonarCenter = sonarCenter;
        this.actionsPackController = actionsPackController;
        this.apkDownloadController = apkDownloadController;
        this.gamepadCenter = gamepadCenter;
    }

    // directChestReady() inlined: uses appContext, no Activity (each center carries its own copy).
    private boolean directChestReady() {
        return directReady(true);
    }

    // directHeaderReady() inlined: same as above (used by handleSystemApi discover + handleDirectApi status).
    private boolean directHeaderReady() {
        return directReady(false);
    }

    /** Chest/head serial ready, two in one (implementation in {@link DirectProbes}; a thin delegate only, to keep the call sites unchanged). */
    private boolean directReady(boolean chest) {
        return chest ? DirectProbes.isChestReady(appContext) : DirectProbes.isHeadReady(appContext);
    }

    /** Shared shape for the six music/* operations (err != null means 500, otherwise {"ok":true}). */
    private static HttpServer.ApiResponse musicOp(MusicOp op) {
        String err = op.run();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.okTrue();
    }

    private interface MusicOp { String run(); }

    /** Shared shape for the three 404s (the message prefix is unchanged, to avoid drift / a broken frontend string comparison). */
    private static HttpServer.ApiResponse unknown(String kind, String path) {
        String prefix = kind == null || kind.isEmpty() ? "unknown endpoint: "
                : "unknown " + kind + " endpoint: ";
        return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                "{\"ok\":false,\"error\":\"" + prefix + path + "\"}");
    }

    /**
     * Routes "/api/<name>" calls to the matching Alpha2RobotApi method. Runs on an
     * HttpServer worker thread (not the main thread) - every SDK call used here is safe
     * to invoke off the main thread (the *ServiceUtil classes only marshal Binder calls),
     * matching how the SDK's own AGENTS.md describes bind/call safety.
     */

    public HttpServer.ApiResponse handleApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            // -- Health status aggregation (body in DeviceStatus; thin delegate, do not add logic here) --
            case "status":
                return deviceStatus.statusResponse();

            case "chest/version": {
                // Return only the real firmware version of the chest MCU (sendCommand 51)
                long timeoutMs = ApiValidator.optionalLong(query, "timeout", 1500L);
                String v = chestQuery.queryFirmwareVersion(timeoutMs);
                if (v != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + MainActivity.jsonSafe(v) + "\"}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"version\":\"not found\"}");
                }
            }
            case "chest/upgrade": {
                // Trigger the chest upgrade: read /sdcard/AlphaII_CHEST_kernel.bin, upgrade via the 48/49/50 protocol
                String err = chestUpgrade.startChestUpgrade();
                if (err == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"started\":true}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(err) + "\"}");
                }
            }
            case "chest/upgrade/status": {
                return HttpServer.ApiResponse.ok("{\"ok\":true," + chestUpgrade.getChestUpgradeStatusJson().substring(1));
            }
            case "chest/upgrade/resume": {
                int from = ApiValidator.optionalInt(query, "from", 0);
                String err = chestUpgrade.startChestUpgradeFrom(from);
                if (err == null) return HttpServer.ApiResponse.ok("{\"ok\":true,\"resumed\":true,\"from\":"+from+"}");
                else return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(err) + "\"}");
            }
            case "chest/upgrade/abort": {
                chestUpgrade.abort();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"aborted\":true}");
            }
            // -- Upgrade image read page (body in ChestUpgrade; thin delegate, do not add logic here) --
            case "chest/page":
                return chestUpgrade.chestPageResponse(query);

            // -- Actions (pure-direct: actionInfo.txt + UbxPlayer; the body has no alpha2services,
            // the old AIDL action_* all return NOT_INIT, so this no longer goes through RobotStub) --------------
            case "action/list":
                return actionDirect.actionListDirect();
            case "action/play":
                return motionGate.guardWholeBody("action/play",
                        () -> actionDirect.actionPlayDirect(ApiValidator.require(query, "name")),
                        ApiDispatcher::motionRefused);
            case "action/stop": {
                // A user "stop" must also do the "squat then stand up" recovery action: shared with the gesture stop-all / MCP
                // stopActionWithRecovery(); whether the recovery plays does not affect the stop itself returning true.
                return MainActivity.codeResponse(actionDirect.stopActionWithRecovery());
            }
            // -- Action pack download + replace (experiments tab action pack card; body in ActionsPackController,
            // thin delegate, do not add logic here) --
            // Fixed URL (see ActionsPackController.PACK_URL); the backend drops the zip directly + auto
            // unzip into sdcard; the old /sdcard/actions is renamed to /sdcard/actions-backup.
            case "action/pack/download": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                String err = actionsPackController.startDownload();
                if (err != null) {
                    // While a download is in progress, return the current state so the frontend can follow progress (same as vosk/download).
                    if (err.startsWith("already downloading")) {
                        return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
                    }
                    return HttpServer.ApiResponse.error(err);
                }
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }
            case "action/pack/status": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }
            case "action/pack/cancel": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                String err = actionsPackController.cancelDownload();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }
            // -- APK download (experiments tab resource download card, Google TTS area; body in
            // ApkDownloadController, thin delegate, do not add logic here) --
            // Fixed URL (see ApkDownloadController.APK_URL); the backend drops the apk directly into
            // sdcard, then opens the system installer to wait for manual confirmation.
            case "apk/download": {
                if (apkDownloadController == null) {
                    return HttpServer.ApiResponse.error("apk download not initialised");
                }
                String err = apkDownloadController.startDownload();
                if (err != null) {
                    // While a download is in progress, return the current state so the frontend can follow progress (same as vosk/download).
                    if (err.startsWith("already downloading")) {
                        return HttpServer.ApiResponse.ok(apkDownloadController.statusJson());
                    }
                    return HttpServer.ApiResponse.error(err);
                }
                return HttpServer.ApiResponse.ok(apkDownloadController.statusJson());
            }
            case "apk/status": {
                if (apkDownloadController == null) {
                    return HttpServer.ApiResponse.error("apk download not initialised");
                }
                return HttpServer.ApiResponse.ok(apkDownloadController.statusJson());
            }
            case "apk/cancel": {
                if (apkDownloadController == null) {
                    return HttpServer.ApiResponse.error("apk download not initialised");
                }
                String err = apkDownloadController.cancelDownload();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok(apkDownloadController.statusJson());
            }

            // -- Ubx live (for the frontend action tab: api() only sends /api/alpha2/*, so one copy is hooked here;
            // the /api/direct/ubx/* one calls the same helper, consistent behavior; the /api/ubx/* bare path
            // also reaches here via fallthrough) --------------
            case "ubx/list":
                return ubxApi.ubxListResponse();
            case "ubx/play":
                return motionGate.guardWholeBody("ubx/play", () -> ubxApi.ubxPlayResponse(ApiValidator.optionalNullable(query, "name"),
                        ApiValidator.optionalNullable(query, "path")), ApiDispatcher::motionRefused);
            case "ubx/stop":
                return ubxApi.ubxStopResponse();
            case "ubx/status":
                return ubxApi.ubxStatusResponse();
            case "ubx/speed":
                // Enum validation is done uniformly inside ubxSpeedResponse via ApiValidator.parseUbxSpeedValue;
                // here only the required check (absent means 400), avoiding a double parse.
                return ubxApi.ubxSpeedResponse(ApiValidator.require(query, "value"));

            // -- Speech / TTS -----------------------------------------------------------
            // engine: android only (only engine that speaks). nuance/iflytek
            // are dead (the alpha2services binder does not exist, robot.speech_startTTS always returns NOT_INIT):
            // ApiValidator directly rejects any value other than "android".
            case "speech/tts":
                return host.handleSpeechTts(query);
            case "speech/stop":
                return host.handleSpeechStop();

            // Android TTS language selection - engine is android only now; lang is chosen from the list
            // provided by speech/tts_languages. ui_lang ("zh"/"en") controls which language displayName is shown in.
            case "speech/tts_languages":
                return ttsCenter.ttsLanguages(query);

            // Android TTS engine selection - the body may have more than one system TTS engine installed (e.g. factory
            // built-in + Google TTS + SVOX Pico); these three endpoints let the speech tab choose
            // which voice speech/tts actually uses.
            case "speech/tts_engines":
                return ttsCenter.ttsEngines();

            case "speech/set_tts_engine":
                return ttsCenter.setTtsEngine(query);

            case "speech/cur_tts_engine":
                return ttsCenter.curTtsEngine();

            // Backend pref for the language selection of the TTS card (BCP-47 tag; empty = keep the
            // engine's current language). The frontend setAndroidTtsLang() writes it synchronously; the dialogue
            // pipeline speakAndroidTts() reads it first - a choice takes effect immediately.
            case "speech/set_tts_lang":
                return ttsCenter.setTtsLang(query);

            case "speech/cur_tts_lang":
                return ttsCenter.curTtsLang();

            // Voice selection for the TTS card (Google TTS has multiple voices per language).
            // tts_voices?lang=<BCP-47> lists the voices of that language (name/locale/network/quality);
            // set/cur_tts_voice read and write the selection (empty = the default voice of that language). Switching engine / language clears
            // the old voice. speech/tts with a voice parameter reads in that voice; the dialogue pipeline follows the pref automatically.
            case "speech/tts_voices":
                return ttsCenter.ttsVoices(query);

            case "speech/set_tts_voice":
                return ttsCenter.setTtsVoice(query);

            case "speech/cur_tts_voice":
                return ttsCenter.curTtsVoice();

            // TTS pitch/rate default values (1.0 = normal, range 0.5-2.0). Used when speech/tts has no
            // pitch/rate; the dialogue pipeline also follows. Body in TtsCenter.
            case "speech/tts_prosody/get":
                return ttsCenter.getTtsProsody();

            case "speech/tts_prosody/set":
                return ttsCenter.setTtsProsody(query);

            // TTS-follows-Vosk switch (voice tab manual/auto selection; body in TtsCenter;
            // thin delegate, do not add logic here).
            case "speech/cur_tts_follow_vosk":
                return ttsCenter.curTtsFollowVosk();

            case "speech/set_tts_follow_vosk":
                return ttsCenter.setTtsFollowVosk(query);

            case "speech/set_mic":
                return micCenter.setMic(query);
            case "speech/set_mic_keep_held":
                return micCenter.setMicKeepHeld(query);
            // Removed (dead binder): speech/reset, speech/start_asr, speech/set_voice,
            // speech/set_language, speech/self_interrupt, speech/inject. If an old .xml
            // program used any of these, importing it will fail to load; delete it by hand.
            // -- Semantic simulation (body in SemanticCenter; thin delegate, do not add logic here) --
            case "speech/semantic_simulate":
                return semanticCenter.semanticSimulateResponse(query);
            // Removed (dead binder): speech/stop_inject.
            // Removed (dead binder): the three endpoints speech/init_grammar, speech/start_grammar,
            // speech/stop_grammar (the body has no iFlytek engine,
            // always returns NOT_INIT). The internal doInitGrammar/doStartGrammar/doStopGrammar
            // remain (the offline auto-switch internal flow still uses them); get_default_grammar still reads the local asset.
            case "speech/get_default_grammar":
                return grammarCenter.getDefaultGrammar();
            case "speech/offline_auto_switch":
                return grammarCenter.offlineAutoSwitch(query);
            // -- Vosk offline ASR (body in VoskApi; thin delegate, do not add logic here) --
            case "vosk/models":
                return voskApi.voskModels();
            case "vosk/load":
                return voskApi.voskLoad(query);
            case "vosk/status":
                return voskApi.voskStatus();
            case "vosk/start":
                return voskApi.voskStart();
            case "vosk/stop":
                return voskApi.voskStop();
            case "vosk/unload":
                return voskApi.voskUnload();
            case "vosk/download":
                return voskApi.voskDownload(query);
            case "vosk/download_status":
                return voskApi.voskDownloadStatus();
            case "vosk/download_cancel":
                return voskApi.voskDownloadCancel();
            case "vosk/catalog":
                return voskApi.voskCatalog();
            case "vosk/grammar":
                return voskApi.voskGrammar(query);
            // -- Wake word, local commands, follow-up window (WakeController) --
            case "wake/status":
            case "wake/get":
            case "wake/set":
                return WakeController.api(path, query);
            // -- 5-mic FPGA capture (MultiMicController; audio itself on /stream/mic?channels=) --
            case "mic/multi/status":
                return HttpServer.ApiResponse.ok(MultiMicController.get().statusJson());
            // -- Servos -----------------------------------------------------------------
            // Motion gate (alpha2-brain#113): state and switches. Both are off by default
            // and in memory only. Query: motion_enabled=true|false, head_only_enabled=true|false.
            case "motion/status":
                return HttpServer.ApiResponse.ok(motionGate.statusJson());
            case "motion/set":
                motionGate.applyQuery(query);
                return HttpServer.ApiResponse.ok(motionGate.statusJson());
            case "servo/one": {
                int gid = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
                return motionGate.guardServo(gid, () -> ubxApi.servoOneResponse(query), ApiDispatcher::motionRefused);
            }
            case "servo/all":
                return motionGate.guardAll(() -> ubxApi.servoAllResponse(query), ApiDispatcher::motionRefused);
            // threshold state goes through sonarCenter, direct call.
            case "servo/sonar":
                return sonarCenter.servoSonarResponse(query);
            case "servo/sonar/get":
                return sonarCenter.servoSonarGet();
            case "servo/read":
                return ubxApi.servoReadResponse(query);
            case "servo/read-all":
                return ubxApi.servoReadAllResponse();
            // Absolute angle actual read (cmd 6, same units as servo/one; the read/read-all one is trim/offset).
            case "servo/angle":
                return ubxApi.servoAngleResponse(query);
            case "servo/angle-restore":
                return motionGate.guardWholeBody("servo/angle-restore", () -> ubxApi.servoAngleRestoreResponse(query),
                        ApiDispatcher::motionRefused);
            case "servo/angle-all":
                return motionGate.guardWholeBody("servo/angle-all", () -> ubxApi.servoAngleAllResponse(),
                        ApiDispatcher::motionRefused);
            // Angle read-back actual read (cmd 23 / 0x17, same as the PC tool "read angle 6"; pure read, no write-back, being tested).
            case "servo/back-angle":
                return ubxApi.servoBackAngleResponse(query);
            case "servo/back-angle-all":
                return ubxApi.servoBackAngleAllResponse();
            case "servo/modify-id":
                return ubxApi.servoModifyIdResponse(query);
            case "servo/version":
                return ubxApi.servoVersionResponse(query);
            case "servo/version-all":
                return ubxApi.servoVersionAllResponse();
            case "servo/hardware":
                return ubxApi.servoHardwareResponse(query);
            case "servo/hardware-all":
                return ubxApi.servoHardwareAllResponse();
            // Servo read stream (cmd 0x17 at up to 25 Hz, event servo_reads; body in ServoReadStream).
            case "servo/stream/start":
            case "servo/stream/stop":
            case "servo/stream/status":
                return ServoReadStream.api(path, query);
            // -- Servo firmware upgrade (body in ServoUpgrade; thin delegate, do not add logic here) --
            case "servo/upgrade":
                return servoUpgrade.servoUpgradeResponse(query);
            case "servo/upgrade/status":
                return servoUpgrade.servoUpgradeStatusResponse();
            case "servo/upgrade/abort":
                servoUpgrade.abort();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"aborted\":true}");

            // -- PIR (body in LedCenter; thin delegate, do not add logic here) --
            case "pir/set":
                return ledCenter.pirSetResponse(query);
            case "pir/alert_enabled":
                return ledCenter.pirAlertEnabledResponse(query);
            case "pir/get":
                return ledCenter.pirGetResponse();

            // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
            // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
            //   color: 1=red 2=green 3=blue 4=yellow 5=purple 6=cyan 7=white
            //   brightness: 1 (dimmest) .. 9 (brightest)
            //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
            //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
            case "led/head/set":
                return ledCenter.ledHeadSet(query);
            case "led/eye/set":
                return ledCenter.ledEyeSet(query);
            case "led/head/raw":
                return ledCenter.ledHeadRaw(query);
            case "led/eye/raw":
                return ledCenter.ledEyeRaw(query);
            case "led/state/get":
                return ledCenter.ledStateGet();
            case "led/wifi/set":
                return ledCenter.ledWifiSet(query);
            case "led/pad/set":
                return ledCenter.ledPadSet(query);
            case "led/mute/set":
                return ledCenter.ledMuteSet(query);
            case "led/mouth/set":
                return ledCenter.ledMouthSet(query);
            case "debug/jni/led":
                return ledCenter.debugJniLed(query);
            case "debug/serial/send":
                return ledCenter.debugSerialSend(query);

            // -- Head / misc ---------------------------------------------------------------
            case "head/noise": {
                boolean on = ApiValidator.requireBoolean(query, "on");
                boolean sent = HardwareDirectManager.get(appContext).head().setNoiseReduction(on);
                return MainActivity.codeResponse(MainActivity.directCode(sent));
            }
            // -- UUID (body in ChestQuery; thin delegate, do not add logic here) --
            case "misc/request_uuid":
                return chestQuery.requestUuidResponse();
            case "misc/set_uuid":
                return chestQuery.setUuidResponse(query);

            // -- Camera: standard Android legacy Camera API, not SDK-gated (see
            // CameraController for the front/back index quirk on this hardware). The
            // live feed itself is served at GET /stream/camera (see handleStream()) as
            // MJPEG, not through this JSON api/ path - a continuous multipart response
            // doesn't fit the single-JSON-body ApiResponse shape. This single-frame
            // snapshot endpoint just starts the camera (if it isn't already streaming)
            // and returns whatever the most recent preview frame is, for callers that
            // want one still image rather than opening the stream. -----------------------
            case "camera/snapshot":
                return cameraApi.snapshot();
            case "camera/snapshot_save":
                return cameraApi.snapshotSave(query);
            case "camera/take_photo_save":
                return cameraApi.takePhotoSave(query);
            // Plays the "Sirrah" shutter cue out of the robot's own speaker (see
            // playShutterCue() javadoc) - called by the browser right after a
            // successful camera/snapshot, instead of synthesizing a click sound in
            // the browser itself.
            case "camera/shutter_sound":
                return cameraApi.shutterSound();
            case "camera/info":
                return cameraApi.info();
            case "camera/fps":
                return cameraApi.fps();
            case "camera/supported_sizes":
                return cameraApi.supportedSizes();
            case "camera/resolution":
                return cameraApi.resolution(query);
            case "camera/zoom":
                return cameraApi.zoom(query);
            // -- On-body video recording mp4 (body in VideoCenter, thin delegate via CameraApi;
            // audio first, if the mic cannot be acquired it switches to video only automatically; until the user presses stop, no time limit) --
            case "camera/video/start":
                return cameraApi.videoStart(query);
            case "camera/video/stop":
                return cameraApi.videoStop();
            case "camera/video/status":
                return cameraApi.videoStatus();
            // -- Android built-in face tracking (body in FaceTrackCenter, thin delegate via CameraApi;
            // pure framework android.media.FaceDetector, zero extra dependencies, see that class's javadoc) --
            case "face/track/start":
                return cameraApi.faceTrackStart(query);
            case "face/track/stop":
                return cameraApi.faceTrackStop();
            case "face/track/status":
                return cameraApi.faceTrackStatus();
            case "face/track/config":
                return cameraApi.faceTrackConfig(query);
            case "face/track/probe":
                return cameraApi.faceTrackProbe();
            // -- Pure Java color tracking (body in ColorTrackCenter, thin delegate via CameraApi;
            // HSV threshold + largest blob, zero extra dependencies; mutually exclusive with face tracking, start stops the other) --
            case "color/track/start":
                return cameraApi.colorTrackStart(query);
            case "color/track/stop":
                return cameraApi.colorTrackStop();
            case "color/track/status":
                return cameraApi.colorTrackStatus();
            case "color/track/config":
                return cameraApi.colorTrackConfig(query);
            // -- Walkie-talkie (body in MicCenter; thin delegate, do not add logic here) --
            case "audio/testtone":
                return micCenter.testTone();
            case "audio/diagnose":
                return micCenter.diagnoseAudio();
            case "audio/play/start":
                return micCenter.playStart();
            case "audio/play/stop":
                return micCenter.playStop();

            // -- System ringtones/notification sounds: exposes every ringtone Android
            // knows about (via RingtoneManager, same mechanism findRingtoneByTitle()
            // above already uses to look up "Proxima"/"Sirrah" by name) as a numbered
            // list, so the Blockly page can offer a dropdown without hardcoding titles
            // that vary by OEM/Android version. "list" returns titles+type; "play"
            // takes the numbered index back and plays it through the same STREAM_MUSIC
            // MediaPlayer path as playRingtoneUri() (so it follows the media volume
            // slider, not the separate ringer/notification volume). -------------------
            case "audio/ringtones/list":
                return ringtoneCenter.ringtonesList(query);
            case "audio/ringtones/play":
                return ringtoneCenter.ringtonesPlay(query);

            // Look up the ringtone by title, no longer using the numbered
            // index of audio/ringtones/list (see the javadoc of findRingtoneByTitle() above: the cursor position is not
            // guaranteed consistent across devices, because the internal sort order of RingtoneManager is not necessarily the same as the
            // manual --sort of adb content query). The Blockly page now embeds a static title list
            // (fetched once from the real device's adb content query; see blockly-ringtone-data.js
            // next to blockly-actions-data.js). A chosen title is sent directly to this API, reusing
            // findRingtoneByTitle(), the "title to Uri" mechanism already used by playStopCue()/playShutterCue()
            // and verified stable; the index sort issue is completely irrelevant.
            case "audio/ringtones/play_by_title":
                return ringtoneCenter.ringtonesPlayByTitle(query);

            // Stop the system ringtone/notification sound currently playing (the one played by the two
            // endpoints play / play_by_title), corresponding to the "stop playback" button of Blockly "example 5".
            case "audio/ringtones/stop":
                return ringtoneCenter.ringtonesStop();

            // -- Local music (/mnt/internal_sd/music/): music files the user placed on the body,
            // a different thing from the system ringtones of audio/ringtones/* above; each has its own set of
            // endpoint/MediaPlayer; see listLocalMusicFiles()/playLocalMusicFile()
            // javadoc for details. "list" has no index (the file list changes as the user adds/removes songs,
            // not as stable as the system list of ringtones); "play" selects directly by file name (including extension).
            case "audio/local_music/list":
                return audioCenter.localMusicList();
            case "audio/local_music/play":
                return audioCenter.localMusicPlay(query);
            case "audio/local_music/stop":
                return audioCenter.localMusicStop();
            case "audio/local_music/status":
                return audioCenter.localMusicStatus();
            case "audio/local_music/seek":
                return audioCenter.localMusicSeek(query);
            case "audio/local_music/volume":
                return audioCenter.localMusicVolume(query);
            case "audio/local_music/spectrum":
                return audioCenter.localMusicSpectrum();
            case "audio/local_music/pause":
                return audioCenter.localMusicPause();
            case "audio/local_music/resume":
                return audioCenter.localMusicResume();

            case "audio/local_music/filler_action/get":
                return audioCenter.fillerActionGet();
            case "audio/local_music/filler_action/set":
                return audioCenter.fillerActionSet(query);

            case "audio/local_music/disco/get":
                return audioCenter.discoGet();
            case "audio/local_music/disco/set":
                return audioCenter.discoSet(query);

            // -- Shared player EQ (body in AudioCenter; the Equalizer is bound to the
            // MediaPlayer session, same life and death as the Visualizer, shared by local + radio; thin delegate) --
            case "audio/local_music/eq/get":
                return audioCenter.musicEqGet();
            case "audio/local_music/eq/set":
                return audioCenter.musicEqSet(query);

            // -- FM/online radio (via the Radio Browser API, radio-browser.info, dynamically searching
            // all public radios in the world - see searchRadioStations()/resolveRadioStation()
            // javadoc; this machine no longer has any built-in hardcoded radio list) - "search" corresponds to
            // self.media.search_radio; "play" uses resolveRadioStation() for
            // human-language name matching (first match against lastRadioSearchResults; if not found, treat it directly as a new search term and hit the
            // API). One more "status" is added for the frontend panel to display "which station is currently playing" (a radio does not have
            // a file name that is so intuitive; after the user presses "change station" they may need to know the result). These two endpoints
            // hit the network internally; different from the MCP tool side (that side has an outer try/catch(Exception)
            // wrapping the whole switch); handleApi() does not, so wrap a try/catch here
            // to turn IOException/JSONException into a normal {"ok":false,...} response;
            // do not let the exception fly out of handleApi() directly.
            case "audio/radio/search":
                return audioCenter.radioSearch(query);
            case "audio/radio/play":
                return audioCenter.radioPlay(query);
            case "audio/radio/play_url":
                return audioCenter.radioPlayUrl(query);
            case "audio/radio/stop":
                return audioCenter.radioStop();
            case "audio/radio/status":
                return audioCenter.radioStatus();
            // -- Online radio favorites (persisted in the body's SharedPreferences; see AudioCenter radioFavorites*) --
            case "audio/radio/favorites/list":
                return audioCenter.radioFavoritesList();
            case "audio/radio/favorites/add":
                return audioCenter.radioFavoritesAdd(query);
            case "audio/radio/favorites/remove":
                return audioCenter.radioFavoritesRemove(query);

            // -- Media volume (body in AudioCenter; thin delegate, do not add logic here) --
            case "audio/volume/get":
                return audioCenter.systemVolumeGet();
            case "audio/volume/set":
                return audioCenter.systemVolumeSet(query);

            // -- Battery (body in DeviceStatus; thin delegate, do not add logic here) --
            case "battery/status":
                return deviceStatus.batteryStatus();

            // -- Wi-Fi / Bluetooth: standard Android framework, not SDK-gated. -----------
            case "wifi/status":
                return deviceStatus.wifiStatus();
            case "bt/status":
                return deviceStatus.btStatus();

            // -- BT gamepad/keyboard (body in GamepadCenter; thin delegate, do not add logic here) --
            // A end = the robot directly connected to HID (physical keys intercepted by MainActivity); gamepad/key/axis
            // are injected by direct script calls (the B-end browser remote control has been removed). All go through the same set of edge mappings.
            case "gamepad/status":
                return gamepadCenter.statusResponse();
            case "gamepad/config/get":
                return gamepadCenter.configGetResponse(query);
            case "gamepad/config/set":
                return gamepadCenter.configSetResponse(query);
            case "gamepad/key":
                return gamepadCenter.keyResponse(query);
            case "gamepad/axis":
                return gamepadCenter.axisResponse(query);
            case "gamepad/bt":
                return gamepadCenter.btResponse(query);
            case "gamepad/scan":
                return gamepadCenter.scanResponse();
            case "gamepad/pair":
                return gamepadCenter.pairResponse(query);
            case "gamepad/connect":
                return gamepadCenter.connectResponse(query);
            case "gamepad/unpair":
                return gamepadCenter.unpairResponse(query);
            case "gamepad/input/get":
                return gamepadCenter.inputGetResponse();
            case "gamepad/input/set":
                return gamepadCenter.inputSetResponse(query);

            // -- Robot-service broadcasts with simple boolean extras. --------------------
            // Removed (false alive): misc/power_save (see below) and misc/charge_play -
            // they purely sent a broadcast to alpha2services which no longer exists; they returned ok:true but were
            // actually ineffective.

            // -- Accelerometer (body in DeviceStatus; thin delegate, do not add logic here) --
            case "accelerometer/set":
                return deviceStatus.accelerometerSet(query);
            case "accelerometer/get":
                return deviceStatus.accelerometerGet();

            // Removed: service_config/get|set (config dedicated to alpha2services; the body no longer has
            // this service, useless for the open alpha2). reboot is also
            // removed: the App has no REBOOT permission (uid not granted; verified on the real device, always a
            // SecurityException); changed to a manual reboot prompt.

            default:
                return unknown("", path);
        }
    }

    // -- /api/system/* + /api/direct/* --

    /**
     * Small namespace ("/api/system/...") for things not tied to the robot AIDL
     * surface itself.
     */
    public HttpServer.ApiResponse handleSystemApi(String path, Map<String, String> query, String method, String body,
            Map<String, String> headers) {
        switch (path) {
            // Search all machine data in one shot (the pure-direct version of the seven sys/* of the lynx era; one response is complete,
            // each slow query has a 1.5s cap). Battery version string: the local chest firmware has no such command, absent as-is;
            // battery level/charging go through the Android system broadcast.
            case "discover": {
                String appVer = "?";
                try {
                    appVer = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                String chestFw;
                try {
                    chestFw = chestQuery.queryFirmwareVersion(1500);
                } catch (Exception e) {
                    chestFw = null;
                }
                String chestUuid;
                try {
                    chestUuid = chestQuery.queryRobotUuid(1500);
                } catch (Exception e) {
                    chestUuid = null;
                }
                int[] pose = ubxPlayer.pose();
                StringBuilder sb = new StringBuilder("{\"ok\":true,");
                sb.append("\"app\":{\"package\":\"").append(MainActivity.jsonSafe(appContext.getPackageName())).append("\",")
                        .append("\"version\":\"").append(MainActivity.jsonSafe(appVer)).append("\",")
                        .append("\"panel\":\"http://").append(MainActivity.jsonSafe(deviceStatus.getWifiIp())).append(":")
                        .append(HttpServer.PORT).append("/\"},");
                sb.append("\"robot\":{\"chestFw\":").append(chestFw != null ? "\"" + MainActivity.jsonSafe(chestFw) + "\"" : "null").append(",")
                        .append("\"chestUuid\":").append(chestUuid != null ? "\"" + MainActivity.jsonSafe(chestUuid) + "\"" : "null").append(",")
                        .append("\"chestAvailable\":").append(directChestReady()).append(",")
                        .append("\"headerAvailable\":").append(directHeaderReady()).append("},");
                sb.append("\"power\":{\"level\":").append(deviceStatus.getBatteryLevel()).append(",")
                        .append("\"scale\":").append(deviceStatus.getBatteryScale()).append(",")
                        .append("\"charging\":").append(deviceStatus.isBatteryCharging()).append(",")
                        .append("\"status\":\"").append(MainActivity.jsonSafe(deviceStatus.getBatteryStatus())).append("\"},");
                sb.append("\"sensors\":{\"sonarCm\":").append(sensorState.getSonarDistanceCm()).append(",")
                        .append("\"sonarThresholdCm\":").append(sensorState.getSonarThreshold()).append(",")
                        .append("\"pir\":").append(sensorState.getPirTriggeredState()).append("},");
                sb.append("\"servo\":{\"poseKnown\":").append(pose != null);
                if (pose != null) {
                    sb.append(",\"angles\":[");
                    for (int i = 0; i < pose.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(pose[i]);
                    }
                    sb.append("]");
                }
                sb.append("}}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            // -- Experiments tab panel token authentication (see PanelAuth: opt-in, default off = fully open;
            // status/verify always open; set/clear while enabled requires proof of the old value (current or Bearer)) --
            case "auth/status":
                return PanelAuth.status(appContext);
            case "auth/set":
                return PanelAuth.set(appContext, query, headers);
            case "auth/clear":
                return PanelAuth.clear(appContext, query, headers);
            case "auth/verify":
                return PanelAuth.verify(appContext, query, headers);
            // ---------------- Local music playback ----------------
            // "/api/system/music/..." - plays music files already on the body's SD card (/sdcard/Music etc.)
            // via MusicController (standard android.media.MediaPlayer,
            // STREAM_MUSIC output from the robot's speaker); completely unrelated to the AIDL robot API,
            // so it is placed under the system namespace, aligned with pure hardware functions like camera/audio-testtone.

            case "music/list": {
                java.util.List<MusicController.Track> tracks = musicController.listTracks();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"tracks\":[");
                for (int i = 0; i < tracks.size(); i++) {
                    if (i > 0) sb.append(",");
                    MusicController.Track t = tracks.get(i);
                    sb.append("{\"path\":\"").append(MainActivity.jsonSafe(t.path)).append("\",")
                      .append("\"name\":\"").append(MainActivity.jsonSafe(t.name)).append("\",")
                      .append("\"sizeBytes\":").append(t.sizeBytes).append("}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "music/play": {
                final String p = ApiValidator.require(query, "path");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.play(p); } });
            }

            case "music/pause": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.pause(); } });
            }

            case "music/resume": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.resume(); } });
            }

            case "music/stop": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.stop(); } });
            }

            case "music/seek": {
                final int ms = ApiValidator.requireInt(query, "ms");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.seekTo(ms); } });
            }

            case "music/volume": {
                final int pct = ApiValidator.requireVolumePercent(query, "percent");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.setVolume(pct); } });
            }

            case "music/status": {
                MusicController.Status s = musicController.status();
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"hasTrack\":" + s.hasTrack + ","
                        + "\"playing\":" + s.playing + ","
                        + "\"prepared\":" + s.prepared + ","
                        + "\"path\":" + (s.path != null ? "\"" + MainActivity.jsonSafe(s.path) + "\"" : "null") + ","
                        + "\"positionMs\":" + s.positionMs + ","
                        + "\"durationMs\":" + s.durationMs + "}");
            }

            default:
                return unknown("system", path);
        }
    }

    public HttpServer.ApiResponse handleDirectApi(String path, Map<String, String> query, String method, String body) {
        if (localServices == null) {
            return HttpServer.ApiResponse.error("direct not initialized");
        }
        switch (path) {
            case "status": {
                boolean direct = localServices.isDirectActive();
                boolean chest = false, head = false;
                try { chest = localServices.isDirectActive() && HardwareDirectManager.get(appContext).chest().isAvailable(); } catch (Exception ignore) {}
                try { head = HardwareDirectManager.get(appContext).head().isAvailable(); } catch (Exception ignore) {}
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"direct\":" + direct + ",\"chest\":" + chest + ",\"head\":" + head + "}");
            }
            case "servo/one": {
                int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
                int angle = ApiValidator.requireIntRange(query, "angle", ApiValidator.SERVO_ANGLE_MIN, ApiValidator.SERVO_ANGLE_MAX);
                int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 500);
                // cmd05 single-shot (official tuner, tested to work on this body), see servoSendOneCode.
                return motionGate.guardServo(id, () -> ubxApi.servoSendOne(id, angle, time), ApiDispatcher::motionRefused);
            }
            case "servo/all": {
                int[] arr = ApiValidator.requireAngles20(query);
                int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 500);
                return motionGate.guardAll(() -> {
                    // setAllServos internally already translates to cmd03 (cmd52 has an ACK but no action).
                    boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(arr, (short) time);
                    if (!sent) return HttpServer.ApiResponse.error("direct not ready");
                    ubxPlayer.notePose(arr);
                    return HttpServer.ApiResponse.okTrue();
                }, ApiDispatcher::motionRefused);
            }
            case "sonar/config": {
                int cm = ApiValidator.requireIntRange(query, "distance", 0, 100);
                boolean ok = HardwareDirectManager.get(appContext).chest().configureSonar(cm);
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/head": {
                int color = ApiValidator.optionalInt(query, "color", 3);
                Integer modeOpt = ApiValidator.optionalInteger(query, "mode");
                int mode = modeOpt != null ? modeOpt.intValue() : 0;
                boolean ok = localServices.ledHead(color);
                // also try direct with mode
                if (modeOpt != null) ok = DirectLedController.setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, mode);
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/off": {
                boolean ok = localServices.ledOff();
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/mouth": {
                int sp = ApiValidator.optionalInt(query, "breathe", 500);
                boolean ok = localServices.ledMouthBreathe(sp);
                return HttpServer.ApiResponse.okBool(ok);
            }
            // Chest cmd 0x19 and cmd 0x40 (alpha2-brain#24). cmd 0x19 [00] is NOT a servo
            // power off: it starts the stock "Sit down" move (chest V142, V352). cmd 0x40
            // enter=1 can do the same. Both need allow_sit_down=true, else 400. To release
            // the servos, squat and read each servo angle (cmd 6).
            case "chest/servo_power": {
                boolean on = ApiValidator.requireChestServoPower(query);
                boolean allow = ApiValidator.optionalAllowSitDown(query);
                boolean sent = HardwareDirectManager.get(appContext).chest().setServoPower(on, allow);
                return HttpServer.ApiResponse.okBool(sent);
            }
            case "chest/power_save": {
                boolean enter = ApiValidator.requireChestPowerSaveEnter(query);
                boolean servoOff = ApiValidator.optionalInt(query, "servo_off", 0) != 0;
                boolean allow = ApiValidator.optionalAllowSitDown(query);
                boolean sent = HardwareDirectManager.get(appContext).chest().setPowerSave(enter, servoOff, allow);
                return HttpServer.ApiResponse.okBool(sent);
            }
            // Ubx live (same helper as /api/alpha2/ubx/*; preemptive: playing a new one stops the old one).
            case "ubx/list":
                return ubxApi.ubxListResponse();
            case "ubx/play":
                return motionGate.guardWholeBody("ubx/play", () -> ubxApi.ubxPlayResponse(ApiValidator.optionalNullable(query, "name"),
                        ApiValidator.optionalNullable(query, "path")), ApiDispatcher::motionRefused);
            case "ubx/speed":
                return ubxApi.ubxSpeedResponse(ApiValidator.require(query, "value"));
            case "ubx/stop":
                return ubxApi.ubxStopResponse();
            case "ubx/status":
                return ubxApi.ubxStatusResponse();
            default:
                return unknown("direct", path);
        }
    }

}
