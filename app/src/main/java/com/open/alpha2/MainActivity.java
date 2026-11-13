package com.open.alpha2;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ubtechinc.alpha.hardware.RobotWire;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.LocalAlpha2Services;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * Single-activity host for the Open Alpha2 robot panel.
 *
 * Owns the one {@link RobotStub} instance for the process (decoupled from
 * Alpha2OpenSdk: the body has no alpha2services, so all the old binder calls
 * fail honestly;
 * the real hardware path goes through HardwareDirectManager /
 * DirectLedController / Android native APIs),
 * initialises every sub-system and answers every "/api/..." HTTP call from
 * {@link HttpServer}. All asynchronous hardware callbacks are pushed to
 * {@link EventBus} so the browser panel's WebSocket log updates live.
 *
 * The activity itself shows minimal on-device status (IP:port, init state) since the
 * robot has no practical on-screen use for this tool - the HTML control panel at
 * http://<robot-ip>:8888/ is the actual UI.
 */
public class MainActivity extends Activity implements XiaozhiBridge.HostState, GestureCenter.Host {
    private static final String TAG = "MainActivity";

    static final String PREFS_NAME = "robotpanel";

    // (squat down and stand up; the stop semantics are unchanged, see
    // actionDirect.stopActionWithRecovery()).

    private RobotStub robot;
    private LocalAlpha2Services localServices;
    // Action music is handled by the voice line inside UbxPlayer (official
    // a/j/a/o semantics: parallel with the clock, starts at the slot,
    // b*timeBase auto-stop, frame switch interrupts). It is independent of
    // currentMusicPlayer / currentRadioPlayer and does not go through the
    // filler loop / spectrum.
    private final UbxPlayer ubxPlayer = new UbxPlayer();
    // The three share the same ubxPlayer instance above (servo read/write still
    // use it directly here).
    private ActionDirect actionDirect;
    private ActionsPackController actionsPackController;
    private ApkDownloadController apkDownloadController;
    private UbxApi ubxApi;
    private HttpServer httpServer;
    private RobotEventReceiver dynamicReceiver;

    // -- WiFi indicator light -----------------------------------------------
    private BroadcastReceiver panelUrlReceiver;
    private TextView panelLinkView;
    private String currentPanelUrl;
    private final CameraController cameraController = new CameraController();
    private final AudioController audioController = new AudioController();
    private final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();
    private MicCenter micCenter;
    private GestureCenter gestureCenter;
    private final MusicController musicController = new MusicController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Try to play the boot voice 15 s after boot. A field so onDestroy can removeCallbacks easily. */
    private final Runnable bootVoiceRunnable = new Runnable() {
        @Override public void run() {
            if (sInstance == null || xiaozhiBridge == null) return;
            xiaozhiBridge.maybeBootVoice("startup");
        }
    };

    static final long MIC_HOLD_ENFORCER_INTERVAL_MS = 2000;

    /** Chinese semantic matching engine instance that fully replaces the 悠聊
     *  APK (com.ubtech.iflytekmix). Created once in onCreate() (it only holds a
     *  Context, does not touch AIDL, so there is no initialization order
     *  problem). The real 1000 entries are read from assets only when
     *  handleSemanticMatch() is called for the first time - see the lazy-load
     *  design of SemanticMatcherZh itself. */
    private SemanticMatcherZh semanticMatcherZh;

    /** English semantic matching engine instance that fully replaces the
     *  AlphaEnglishChat APK (com.ubtechinc.alphaenglishchat). Same mechanism as
     *  semanticMatcherZh, with independent data (1000 English phrasings, see
     *  SemanticMatcherEn). SemanticCenter decides which sentence uses which
     *  matcher from the conversation language / input text (see
     *  handleSemanticMatch); content-based judgment is more reliable. */
    private SemanticMatcherEn semanticMatcherEn;

    /** Spanish semantic matching engine instance. Same mechanism and independent
     *  data as the Chinese and English versions (semantic_es.json, see
     *  SemanticMatcherEs). SemanticCenter decides which sentence uses which
     *  matcher from the conversation language / input text (see
     *  handleSemanticMatch); content-based judgment is more reliable. */
    private SemanticMatcherEs semanticMatcherEs;

    /** French semantic matching engine instance. Same mechanism and independent
     *  data as the other language versions (semantic_fr.json, see
     *  SemanticMatcherFr; the first version is an empty library skeleton).
     *  SemanticCenter decides which sentence uses which matcher from the
     *  conversation language / input text (see handleSemanticMatch);
     *  content-based judgment is more reliable. */
    private SemanticMatcherFr semanticMatcherFr;

    /** Japanese semantic matching engine instance. Same mechanism and
     *  independent data as the other language versions (semantic_ja.json, see
     *  SemanticMatcherJa: 85 ACTION + 200 daily CHAT). SemanticCenter decides
     *  which sentence uses which matcher from the conversation language / input
     *  text (see handleSemanticMatch); content-based judgment is more reliable. */
    private SemanticMatcherJa semanticMatcherJa;

    /** German / Italian / Portuguese / Korean / Russian semantic matching
     *  engine instances. Same mechanism and independent data as the other
     *  language versions (semantic_de/it/pt/ko/ru.json, see the matching
     *  Matchers: currently empty library skeletons, content to be added later).
     *  Wiring is the same as the other five languages, so no rewiring is needed
     *  when the content arrives. */
    private SemanticMatcherDe semanticMatcherDe;
    private SemanticMatcherIt semanticMatcherIt;
    private SemanticMatcherPt semanticMatcherPt;
    private SemanticMatcherKo semanticMatcherKo;
    private SemanticMatcherRu semanticMatcherRu;

    /** Vosk offline ASR controller (voice tab). Singleton, started in onCreate,
     *  stopped in onDestroy. The model is placed on sdcard and auto-detected;
     *  see VoskController. */
    private VoskController vosk;

    private RingtoneCenter ringtoneCenter;

    private ChestQuery chestQuery;
    private ChestUpgrade chestUpgrade;
    private ServoUpgrade servoUpgrade;

    /** Static seam for PIR events (entry from RobotEventReceiver /
     *  onDirectChestFrame, signature unchanged): delegates to SonarCenter. If
     *  sInstance / sonarCenter is null, no-op - they are constructed in sequence
     *  on the same onCreate thread (sonarCenter comes after
     *  registerDynamicReceiver). PIR edges within the first few ms of startup
     *  are lost. Behaviour matches the null-guards of the other centers. See
     *  SonarCenter.onPirStateReceived for the non-blocking constraint. */
    static void onPirStateReceived(final boolean triggered) {
        final MainActivity m = sInstance;
        if (m == null || m.sonarCenter == null) {
            return;
        }
        m.sonarCenter.onPirStateReceived(triggered);
    }

    private TtsCenter ttsCenter;

    private LedCenter ledCenter;
    private SemanticCenter semanticCenter;
    private DeviceStatus deviceStatus;
    private XiaozhiBridge xiaozhiBridge;
    private ApiDispatcher apiDispatcher;
    private GrammarCenter grammarCenter;
    private SpeechCenter speechCenter;
    private SonarCenter sonarCenter;
    private GamepadCenter gamepadCenter;

    // -- XiaozhiBridge.HostState (host seam): the two TTS methods delegate to
    // SpeechCenter, the four sonar methods
    // delegate to SonarCenter (delegate + null-guard; the 4 MCP tools still read
    // through here). --
    @Override public boolean isRobotTtsSpeaking() { return speechCenter != null && speechCenter.isRobotTtsSpeaking(); }
    @Override public long getLastSpeechStopAtMs() { return speechCenter != null ? speechCenter.getLastSpeechStopAtMs() : 0L; }
    @Override public int getSonarDistanceCm() { return sonarCenter != null ? sonarCenter.getSonarDistanceCm() : -1; }
    @Override public int getSonarThreshold() { return sonarCenter != null ? sonarCenter.getSonarThreshold() : 30; }
    @Override public int getPirTriggeredState() { return sonarCenter != null ? sonarCenter.getPirTriggeredState() : -1; }
    @Override public void applySonarThreshold(int distanceCm) {
        if (sonarCenter != null) sonarCenter.applySonarThreshold(distanceCm);
    }
    private CameraApi cameraApi;
    private VideoCenter videoCenter;
    private VoskApi voskApi;
    private FaceTrackCenter faceTrackCenter;
    private ColorTrackCenter colorTrackCenter;

    // RobotEventReceiver has no constructor/field to reach the outer
    // MainActivity instance (it has only ever sent events via the static EventBus
    // methods and does not know
    // MainActivity itself). But the LED indicator logic for sonar_obstacle
    // (applyObstacleIndicator, sonarThresholdCm) is all instance-level, relying
    // on the robot AIDL connection. Add a static
    // instance reference, set/clear in onCreate/onDestroy, so
    // RobotEventReceiver can reach back to the
    // instance logic through the two static bridge methods
    // MainActivity.getSonarThresholdCm() /
    // MainActivity.onSonarDistanceReceived() (static signature unchanged,
    // delegating),
    // without widening the RobotEventReceiver constructor signature (that would
    // affect
    // every new RobotEventReceiver() call site in registerDynamicReceiver()).
    private static volatile MainActivity sInstance;

    /** Until the broadcast triggered by SONAR_DISTANCE_ACTION arrives,
     *  RobotEventReceiver must know the current threshold to compute
     *  "triggered". If there is no instance / sonarCenter (for example the
     *  window while the Activity is not yet fully up, or already destroyed),
     *  treat it as no threshold, so triggered is not misjudged. */
    static int getSonarThresholdCm() {
        MainActivity m = sInstance;
        return (m != null && m.sonarCenter != null) ? m.sonarCenter.getSonarThreshold() : 30;
    }

    /** Entry after RobotEventReceiver receives SONAR_DISTANCE_ACTION (the real
     *  body is in SonarCenter#onSonarDistanceReceived): delegates;
     *  if there is no instance / sonarCenter, quietly do nothing. */
    static void onSonarDistanceReceived(int distanceCm, boolean triggered) {
        MainActivity m = sInstance;
        if (m == null || m.sonarCenter == null) {
            return;
        }
        m.sonarCenter.onSonarDistanceReceived(distanceCm, triggered);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        installCrashRestartHandler();

        // LedCenter/RingtoneCenter are created here (without waiting for
        // initRobot), because registerWifiLedReceiver() below needs them
        // immediately. Both only need Context / mainHandler and have no other
        // dependencies, so constructing them earlier does not change behaviour.
        ringtoneCenter = new RingtoneCenter(this);
        ledCenter = new LedCenter(this, mainHandler, ringtoneCenter);
        registerDynamicReceiver();
        ledCenter.registerWifiLedReceiver();
        initRobot();
        // pure-direct: chest /dev/ttyS1 + head /dev/ttyS3 + libhead_led.so JNI.
        // The body has no alpha2services, no binder fallback; failures report
        // directly.
        localServices = new LocalAlpha2Services(this);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean direct = localServices.start();
                Log.i(TAG, "LocalAlpha2Services direct=" + direct + " (pure-direct, no alpha2services fallback)");
                // User request: on app start, automatically do one squat-and-stand
                // (stretch). Play only when the direct drive is ready.
                if (direct) {
                    if (actionDirect.playActionDirect(ActionDirect.STOP_RECOVERY_ACTION_ID)
                            != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                        Log.w(TAG, "startup squat not started: " + ubxPlayer.lastError());
                    }
                }
            }
        }, "LocalServicesInit").start();
        mainHandler.postDelayed(bootVoiceRunnable, 15000);
        semanticMatcherZh = new SemanticMatcherZh(this);
        semanticMatcherEn = new SemanticMatcherEn(this);
        semanticMatcherEs = new SemanticMatcherEs(this);
        semanticMatcherFr = new SemanticMatcherFr(this);
        semanticMatcherJa = new SemanticMatcherJa(this);
        semanticMatcherDe = new SemanticMatcherDe(this);
        semanticMatcherIt = new SemanticMatcherIt(this);
        semanticMatcherPt = new SemanticMatcherPt(this);
        semanticMatcherKo = new SemanticMatcherKo(this);
        semanticMatcherRu = new SemanticMatcherRu(this);
        // Vosk circuit breaker - vosk-android minSdk 21. API 19 machines (this
        // APK must install on 4.4) must never touch org.vosk.* (native/JNA
        // crashes at once). On API 19, vosk stays null, and every vosk/*
        // endpoint returns a clear error via VoskApi.voskOrError().
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                vosk = new VoskController(this, semanticMatcherZh, semanticMatcherEn, semanticMatcherEs, semanticMatcherFr, semanticMatcherJa,
                        semanticMatcherDe, semanticMatcherIt, semanticMatcherPt, semanticMatcherKo, semanticMatcherRu);
            } catch (Throwable e) {
                Log.w(TAG, "VoskController init failed", e);
            }
        } else {
            Log.i(TAG, "Vosk disabled: need API 21+, this device is API "
                    + android.os.Build.VERSION.SDK_INT);
        }
        // The Android TTS layer is constructed in TtsCenter (started after vosk,
        // because the listener uses vosk pause/resume later). null = use the
        // body's current default engine.
        ttsCenter = new TtsCenter(this, vosk);
        ttsCenter.initAndroidTts(null);
        semanticCenter = new SemanticCenter(semanticMatcherZh, semanticMatcherEn, semanticMatcherEs, semanticMatcherFr, semanticMatcherJa,
                semanticMatcherDe, semanticMatcherIt, semanticMatcherPt, semanticMatcherKo, semanticMatcherRu, actionDirect, ttsCenter);
        deviceStatus = new DeviceStatus(this, mainHandler, actionDirect, ubxPlayer, ttsCenter);
        // Sticky broadcast registration is not timing-sensitive.
        deviceStatus.registerBatteryReceiver();
        cameraApi = new CameraApi(this, cameraController, ringtoneCenter);
        // On-device mp4 recording (MediaRecorder + CameraController handoff;
        // exposes camera/video/* through a thin CameraApi delegate, placed after
        // cameraApi and before the dispatcher).
        videoCenter = new VideoCenter(this, cameraController);
        cameraApi.setVideoCenter(videoCenter);
        // Android built-in face tracking (framework FaceDetector, zero
        // dependencies; exposes face/track/* through a thin CameraApi delegate,
        // see FaceTrackCenter javadoc). Needs ubxApi (servo cmd05) +
        // cameraController (preview frame), placed after cameraApi and before
        // the dispatcher.
        faceTrackCenter = new FaceTrackCenter(cameraController, ubxApi);
        cameraApi.setFaceTrackCenter(faceTrackCenter);
        // Pure Java color tracking (HSV + largest blob, zero dependencies;
        // mutually exclusive with face tracking, see CameraApi).
        colorTrackCenter = new ColorTrackCenter(cameraController, ubxApi);
        cameraApi.setColorTrackCenter(colorTrackCenter);
        // Color walk-follow needs ActionDirect: initRobot() (line 224) runs
        // earlier than here, when the Center is still null and cannot be
        // injected. Here, fill in the gap after both sides are ready (it used to
        // sit inside initRobot and was always skipped - that is how the
        // walk-follow got a head that would not move).
        if (actionDirect != null) {
            try { colorTrackCenter.setActionDirect(actionDirect); } catch (Throwable ignored) {}
        }
        // Sonar + PIR sensors package: only needs ledCenter (purple light
        // indicator), started before xiaozhiBridge -
        // the 4 MCP sensors tools get it through the ctor (breaks the cycle: the
        // PIR push uplink is filled in later via setUplink below, see the
        // SonarCenter javadoc seam design).
        sonarCenter = new SonarCenter(this, ledCenter);
        // Xiaozhi package (HTTP API/mic/activation/vision/MCP/mute key switch):
        // all collaborators assemble and start here.
        // It owns xiaozhiClient/xiaozhiAudioController/xiaozhiConfig.
        // Started before voskApi - taking the mic back after voskStart() goes
        // through it.
        // (sonarCenter is passed in at the end; it is only usable once built
        // here.)
        xiaozhiBridge = new XiaozhiBridge(this, mainHandler, actionDirect, audioCenter,
                robot, ttsCenter, vosk, cameraController, ledCenter, this, sonarCenter);
        // PIR push uplink filled in later (same onCreate thread; it is bound to
        // arrive before httpServer starts.
        // PIR edges before it is filled in still store the state, the push is
        // skipped).
        sonarCenter.setUplink(xiaozhiBridge);
        micCenter = new MicCenter(robot, ledCenter, audioController, audioPlaybackController, this);
        // (passed in together with apiDispatcher; only usable once built here.)
        // Gesture package (head pad + volume burst + two-key total stop):
        // actionDirect/audioCenter assemble and start here (after initRobot).
        gestureCenter = new GestureCenter(this, mainHandler, ledCenter, ringtoneCenter,
                actionDirect, audioCenter, this);
        gestureCenter.start();
        grammarCenter = new GrammarCenter(this, robot, xiaozhiBridge);
        // Sticky broadcast: the current state is available right after
        // registration (callable only once started).
        grammarCenter.registerConnectivityReceiver();
        voskApi = new VoskApi(vosk, xiaozhiBridge, semanticCenter);
        // TTS orchestration package (speech/tts + stop + total stop): needs
        // xiaozhiBridge (stops the Xiaozhi pipeline via stopSpeechPlayback),
        // placed after voskApi and before the dispatcher.
        speechCenter = new SpeechCenter(ttsCenter, vosk, xiaozhiBridge);
        // The real FUNCTION execution is filled in later (avoids a
        // construction-order cycle: semanticCenter starts earlier than
        // deviceStatus/cameraApi/speechCenter; only here are all the parts
        // present).
        semanticCenter.setAudioCenter(audioCenter);
        semanticCenter.setDeviceStatus(deviceStatus);
        semanticCenter.setCameraApi(cameraApi);
        semanticCenter.setSpeechStopHost(this);
        // (sonarCenter was already built above, before xiaozhiBridge;
        // the dispatcher goes at the very end.)
        // BT gamepad / keyboard package (needs actionDirect + ubxApi +
        // deviceStatus. The A-side physical keys and the B-side
        // HTTP share the same mapping, see GamepadCenter. Placed before the
        // dispatcher).
        gamepadCenter = new GamepadCenter(this, actionDirect, ubxApi, ubxPlayer, deviceStatus,
                audioCenter, ringtoneCenter, ledCenter, speechCenter, cameraApi, sonarCenter,
                xiaozhiBridge, voskApi);
        // The dispatcher wraps all the controllers above (+ speechCenter as
        // Host. sensorState still goes through this - TTS / sonar all delegate
        // to the corresponding center. servo/sonar call
        // sonarCenter directly).
        // Placed at the very end - it must wait for every collaborator
        // (speechCenter above is the latest).
        apiDispatcher = new ApiDispatcher(this, speechCenter, this, actionDirect, ubxApi, chestQuery,
                chestUpgrade, servoUpgrade, ttsCenter, voskApi, ledCenter, semanticCenter, deviceStatus,
                cameraApi, audioCenter, ringtoneCenter, micCenter, robot, grammarCenter,
                ubxPlayer, musicController, localServices, sonarCenter, actionsPackController,
                apkDownloadController, gamepadCenter);
        // Wake word and local commands (alpha2-brain#16). Off until wake/set?enabled=true.
        WakeController.init(this, xiaozhiBridge, vosk, apiDispatcher);

        // The music-playing indicator light (playing music while disco is on)
        // shares the same chest mute light as the "Xiaozhi connected" light.
        // XiaozhiBridge does the OR. Started after xiaozhiBridge.
        ledCenter.setMuteLedSink(new LedCenter.MuteLedSink() {
            @Override
            public void applyMuteLed(boolean litByMusic) {
                xiaozhiBridge.applyMuteLedByMusic(litByMusic);
            }
        });
        // LED tab mute button mirror: follows the effective state of the chest
        // mute light (Xiaozhi OR music playing). When music playing opens
        // disco, the button turns red at once, without waiting for the user to
        // press it.
        ledCenter.setMuteStateQuery(new LedCenter.MuteStateQuery() {
            @Override
            public boolean isMuteLit() {
                try {
                    return xiaozhiBridge.isMuteLedLit();
                } catch (Throwable t) {
                    return false;
                }
            }
        });

        // Plain HTTP only. TLS/HTTPS was tried (self-signed cert) to make getUserMedia()
        // available for the walkie-talkie mic feature, but browsers on this device
        // repeatedly rejected new TLS connections after the very first page load with
        // "SSLHandshakeException: Handshake failed / certificate unknown" (see logcat
        // from 2017-01-01 session) - each new WebSocket/keep-alive connection re-runs
        // the TLS handshake and the self-signed cert's trust exception did not reliably
        // carry over, so the WebSocket feed (accel, uuid, wakeup, etc.) dropped
        // intermittently even though the HTTP API calls themselves succeeded. Rather
        // than fight browser cert-trust behavior, TLS support was removed outright
        // - walkie-talkie (which needs a secure context)
        // stays permanently disabled in the UI (see app-mic.js) and everything else works
        // reliably over plain HTTP/WS.
        String ip = deviceStatus.getWifiIp();

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body,
                    Map<String, String> headers) {
                // Central gate for the experiment tab panel token (see PanelAuth:
                // opt-in, off by default = fully open.
                // Once enabled, the whole panel locks: every /api/* needs a token
                // (Bearer header primary, panel_token query as the img / ws
                // fallback). Only system/auth/* (the unlock entry) stays open.
                // Static pages do not go through here and remain open. ws /
                // stream have their own gates).
                if (!PanelAuth.isOpenApi(path)) {
                    HttpServer.ApiResponse gate = PanelAuth.requireAuth(MainActivity.this, query, headers);
                    if (gate != null) return gate;
                }
                // "/api/alpha2/..." goes to the original Alpha2RobotApi dispatch
                // (handleApi, unchanged below). "/api/system/..." is a small namespace
                // for things not tied to the robot SDK itself.
                if (path.startsWith("direct/")) {
                    return apiDispatcher.handleDirectApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("alpha2/")) {
                    return apiDispatcher.handleApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return apiDispatcher.handleSystemApi(path.substring(7), query, method, body, headers);
                }
                if (path.startsWith("xiaozhi/")) {
                    return xiaozhiBridge.handleXiaozhiApi(path.substring(8), query, method, body);
                }
                // Back-compat: requests with no backend prefix (older cached browser
                // tab) fall through to the Alpha2 dispatch.
                return apiDispatcher.handleApi(path, query, method, body);
            }
        }, new HttpServer.StreamHandler() {
            @Override
            public void handle(String path, Map<String, String> query, java.net.Socket socket,
                    Map<String, String> headers) throws java.io.IOException {
                handleStream(path, query, socket, headers);
            }
        }, new HttpServer.RawUploadHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, byte[] body,
                    Map<String, String> headers) {
                return handleUpload(path, query, body, headers);
            }
        });
        // Gate before the /ws upgrade (browser WebSockets cannot set headers, so
        // check Bearer or the panel_token query. Not enabled means pass through.
        // Static pages do not go through here).
        httpServer.setWsAuthGate(new HttpServer.WsAuthGate() {
            @Override
            public HttpServer.ApiResponse check(String path, Map<String, String> query,
                    Map<String, String> headers) {
                return PanelAuth.requireAuth(MainActivity.this, query, headers);
            }
        });
        httpServer.start();
        String scheme = "http";

        // The on-device screen does NOT mirror the HTML control panel via WebView -
        // that path had unreliable CSS/JS rendering on this device's WebView build (blank/
        // broken layout, buttons stuck disabled). Per this class's original design intent,
        // the HTML panel at http://<robot-ip>:8888/ is the actual UI; the on-device
        // screen is just a native status readout telling the user where to point a browser.
        // panelUrl/linkView are member variables and update automatically as the
        // network changes
        currentPanelUrl = scheme + "://" + ip + ":" + HttpServer.PORT + "/";
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setTextSize(16);
        titleView.setText("Open Alpha2\n\nOpen in a browser on the same network:");
        root.addView(titleView);

        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        int topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        linkRow.setPadding(0, topMargin, 0, topMargin);

        panelLinkView = new TextView(this);
        panelLinkView.setText(currentPanelUrl);
        panelLinkView.setTextSize(16);
        panelLinkView.setTextColor(Color.parseColor("#3b7dff"));
        panelLinkView.setPaintFlags(panelLinkView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panelLinkView.setLayoutParams(linkParams);

        Button copyBtn = new Button(this);
        copyBtn.setText("Copy");
        View.OnClickListener copyAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String urlToCopy = currentPanelUrl != null ? currentPanelUrl : ("http://" + deviceStatus.getWifiIp() + ":" + HttpServer.PORT + "/");
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Alpha2 panel URL", urlToCopy));
                    Toast.makeText(MainActivity.this, "Copied: " + urlToCopy, Toast.LENGTH_SHORT).show();
                }
            }
        };
        panelLinkView.setOnClickListener(copyAction);
        copyBtn.setOnClickListener(copyAction);

        linkRow.addView(panelLinkView);
        linkRow.addView(copyBtn);
        root.addView(linkRow);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);

        Log.i(TAG, "Open Alpha2 - reachable at " + scheme + "://" + ip
                + ":" + HttpServer.PORT + "/ from any browser on the same network");
        registerPanelUrlReceiver();

        // pure-direct: "head noise reduction" is on by default and sent directly
        // to /dev/ttyS3 via DirectHeadController,
        // no longer through robot.waitHeaderReady() / the alpha2services binder.
        // Still uses a separate background thread (localServices.start() is
        // itself async).
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Wait for the direct serial port to be ready (up to 5 s, one
                    // poll every 100 ms)
                    for (int i = 0; i < 50; i++) {
                        try {
                            if (HardwareDirectManager.get(MainActivity.this).head().isAvailable()) break;
                        } catch (Exception ignore) {}
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                    boolean ok = HardwareDirectManager.get(MainActivity.this).head().setNoiseReduction(true);
                    if (!ok) Log.w(TAG, "HeadNoiseDefaultInit: direct send failed (head not ready?)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to enable default head noise reduction", e);
                }
            }
        }, "HeadNoiseDefaultInit").start();
    }

    private void registerDynamicReceiver() {
        dynamicReceiver = new RobotEventReceiver();
        IntentFilter filter = new IntentFilter();
        // Keep every string below exactly as-is (see git history + each case in
        // RobotEventReceiver for the archaeology details):
        // the key dead code stays for old firmware compatibility. CHEST_ACTION is
        // pure debug aid; the real sonar is SONAR_DISTANCE_ACTION.
        // pirStatus is not verified on real hardware. The trailing mic-family
        // broadcast semantics are undetermined, collected via mic_broadcast_debug.
        String[] actions = {
                "com.ubtechinc.key",
                "com.ubtechinc.robot.tts_hint_wakeup",
                "come.ubt.alpha2.gesture",
                "com.ubtechinc.robot_uuid.info",
                RobotWire.ALPHA_QR_CODE,
                RobotWire.ALPHA_WIFI_RESULT,
                RobotWire.ALPHA_BT_CONNECTION,
                "com.ubtechinc.services.Action.ACTION_STOP",
                "com.ubtechinc.services.Action.ROBOT_INTERRUPTED",
                RobotWire.CHEST_ACTION,
                "com.ubtech.securityCamera.pirStatus",
                RobotWire.SONAR_DISTANCE_ACTION,
                "com.ubtechinc.services.ABOUT_TTS",
                "com.ubtechinc.services.ALPHA_SOCKET_ASR_OK",
                "com.ubtechinc.services.SPEECH_ANGLE_5MIC",
                "com.ubtechinc.services.LED_ACTION",
                "com.ubtechinc.services.POWER_SAVE",
                "com.ubtechinc.services.ALPHA_NOTIFY_POWER",
        };
        for (String a : actions) filter.addAction(a);
        registerReceiver(dynamicReceiver, filter);
    }

    /**
     * Installs a default uncaught-exception handler so any crash anywhere in this
     * process schedules a restart instead of leaving the robot's control panel dead
     * until someone physically walks over and re-launches the app.
     *
     * Approach: on an uncaught exception, use AlarmManager.setExact() (not just posting
     * a delayed Handler task - a crashing/dying process won't reliably run that) to fire
     * a fresh MainActivity launch ~1.5s from now, chain to whatever the previous default
     * handler was (so ADB/Play-style crash logging still sees the exception), then kill
     * this process outright. Restarting a *process* that's already in a broken state via
     * in-place recovery is unreliable; a full relaunch is the robust option here.
     *
     * (No SCHEDULE_EXACT_ALARM permission is needed for setExact() here: that's only
     * required starting targetSdkVersion 31, and this app targets 22.)
     */
    private void installCrashRestartHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final Context appContext = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "Uncaught exception - scheduling restart", throwable);
                Intent restartIntent = new Intent(appContext, MainActivity.class);
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        appContext, 0, restartIntent,
                        // IMMUTABLE: not needed at target 22 for now, but a move
                        // to 31+ crashes without this flag. A static final int is
                        // inlined into the dex, so old machine runtimes are
                        // unaffected.
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                            android.os.SystemClock.elapsedRealtime() + 1500, pendingIntent);
                }
            } catch (Exception schedulingFailure) {
                // If even scheduling the restart fails, fall through to the previous
                // handler / process death below rather than losing the crash entirely.
                Log.e(TAG, "Failed to schedule crash restart", schedulingFailure);
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private AudioCenter audioCenter;

    /**
     * WiFi state -> wifi indicator light (three states: wifi off = light off,
     * wifi on but not connected = red 13, connected to an AP = blue 12).
     * Checks the current state once immediately on registration, covering the
     * "already connected / disconnected before the app opened" case.
     */

    private void initRobot() {
        // Decoupled from Alpha2OpenSdk - robot is the RobotStub pure-local no-op
        // facade (the body has no alpha2services, so all old binder calls fail
        // honestly, see RobotStub).
        // chest/head reply frames only go through wireDirectFrameListeners()
        // below.
        robot = new RobotStub(this);
        chestQuery = new ChestQuery(this, robot);
        actionDirect = new ActionDirect(this, ubxPlayer);
        actionsPackController = new ActionsPackController(this, actionDirect);
        apkDownloadController = new ApkDownloadController(this);
        ubxApi = new UbxApi(this, ubxPlayer, actionDirect, chestQuery);
        chestUpgrade = new ChestUpgrade(this, chestQuery);
        servoUpgrade = new ServoUpgrade(this, chestQuery);
        // Servo read stream (alpha2-rl#3): reads only, pauses during a firmware upgrade.
        ServoReadStream.init(this, new ServoReadStream.BusyCheck() {
            @Override public boolean busy() {
                return (chestUpgrade != null && chestUpgrade.isBusy())
                        || (servoUpgrade != null && servoUpgrade.isBusy());
            }
        });
        // (ringtoneCenter/ledCenter were already built at the top of onCreate,
        // see above.)
        audioCenter = new AudioCenter(this, ubxPlayer, actionDirect, mainHandler, ledCenter);
        EventBus.get().publish("authorize", "{\"code\":1,\"info\":\"have offline authority\"}");
        Log.i(TAG, "Authorize result: 1 have offline authority");

        // pure-direct: the body no longer has com.ubtechinc.alpha2services, so
        // no bindService at all.
        // Chest/head serial frames are pushed directly by HardwareDirectManager
        // via DirectSerialPort, see wireDirectFrameListeners().
        wireDirectFrameListeners();

        // The chest_mute_key event goes up to the WebSocket as usual via the
        // EventBus.
        ledCenter.registerAlpha2PirAlertListener();
    }

    // -- pure-direct frame wiring -----------------------------------------------
    //
    // Chest/head MCU reply frames are received directly via
    // HardwareDirectManager: what DirectSerialPort sends is a complete
    // wire frame (F8 8F ... ED, the official 05 00 header or the MCU event
    // 00 00 header). First stripSerialFrame()
    // strips it down to the payload layer (bytes[0] is cmd) before the
    // latch/EventBus logic. The outward-published
    // chest_rcv/head_rcv events use the full frame hex (more information; the
    // front-end Event Log displays them as usual).
    private void wireDirectFrameListeners() {
        try {
            HardwareDirectManager dm = HardwareDirectManager.get(this);
            dm.chest().setFrameListener(new com.ubtechinc.alpha.hardware.DirectSerialPort.OnFrameListener() {
                @Override public void onFrame(byte[] frame) { onDirectChestFrame(frame); }
            });
            dm.head().setFrameListener(new com.ubtechinc.alpha.hardware.DirectSerialPort.OnFrameListener() {
                @Override public void onFrame(byte[] frame) { onDirectHeadFrame(frame); }
            });
            Log.i(TAG, "wireDirectFrameListeners: direct chest/head listeners attached (pure-direct)");
        } catch (Throwable t) {
            Log.w(TAG, "wireDirectFrameListeners failed", t);
        }
    }

    /**
     * Strip the complete wire frame down to the payload layer (bytes[0] is cmd,
     * matching the old AIDL callback format).
     * Compatible with the long form (F8 8F LEN SRC DST CMD PAYLOAD SUM ED;
     * SRC DST = the official 05 00 form
     * or the 00 00 event form, cmd at i+5) and the short form (F8 8F LEN CMD
     * PAYLOAD SUM ED,
     * old compatibility). Returns null if no frame header is found.
     * Note: cmd 13 servo read returns a short error frame on a bad servo
     * (such as servos 5/6 on this machine)
     * (payload [0x0d, 0x01, id], no angle value). The caller must use plen /
     * the first-byte status to
     * distinguish the normal [00 id hi lo] reply; do not parse it as an angle.
     */
    static byte[] stripSerialFrame(byte[] frame) {
        if (frame == null) return null;
        int n = frame.length;
        for (int i = 0; i + 5 < n; i++) {
            if ((frame[i] & 0xFF) == 0xF8 && (frame[i + 1] & 0xFF) == 0x8F) {
                int lenByte = frame[i + 2] & 0xFF;
                // Long form: [i+3] is 05 (official) or 00 (event), [i+4] is 00,
                // cmd at i+5
                if (frame[i + 4] == 0 && (frame[i + 3] == 0x05 || frame[i + 3] == 0)) {
                    int pl = lenByte - 7;
                    if (pl < 0) pl = 0;
                    if (i + 6 + pl > n) pl = Math.max(0, n - (i + 6));
                    byte[] out = new byte[1 + pl];
                    out[0] = frame[i + 5];
                    if (pl > 0) System.arraycopy(frame, i + 6, out, 1, pl);
                    return out;
                }
                // Short form: cmd at i+3
                int pl = lenByte - 1;
                if (pl < 0) pl = 0;
                if (i + 4 + pl > n) pl = Math.max(0, n - (i + 4));
                byte[] out = new byte[1 + pl];
                out[0] = frame[i + 3];
                if (pl > 0) System.arraycopy(frame, i + 4, out, 1, pl);
                return out;
            }
        }
        return null;
    }

    private void onDirectHeadFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        EventBus.get().publish("head_rcv", "{\"hex\":\"" + toHex(frame, frame.length) + "\"}");
        // Head frames are only published; no latch at all anymore.
    }

    private void onDirectChestFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        byte[] payload = stripSerialFrame(frame);
        if (payload == null) payload = frame;
        // Heartbeat silence - cmd 0x8B (-117, ~1Hz telemetry) and 0x8D (-115,
        // 5s heartbeat) no longer publish chest_rcv to the WebSocket (the Event
        // Log floods; see the logcat measurement: 361 frames in 5 minutes,
        // almost all of these two). Other cmds (UUID reply 0x37,
        // PIR 0x93, etc.) publish as before; the -109 PIR capture/forward logic
        // below is completely untouched.
        // The DirectSerialPort RX hex in logcat is kept as-is; look there for
        // the raw frames.
        boolean noisyHeartbeat = payload.length >= 1
                && (payload[0] == (byte) 0x8B || payload[0] == (byte) 0x8D);
        // Replies to the servo read stream go out as one servo_reads event per cycle instead
        // (ServoReadStream, alpha2-rl#3). The parsers below still see them.
        boolean streamReply = !noisyHeartbeat && ServoReadStream.onChestPayload(payload);
        if (!noisyHeartbeat && !streamReply) {
            EventBus.get().publish("chest_rcv", "{\"hex\":\"" + toHex(frame, frame.length) + "\"}");
        }
        int plen = payload.length;
        handleChestObstacleFrame(payload, plen);
        // pure-direct: the chest mute key (-111/0x91) and PIR (-109/0x93) come
        // straight from the serial frames.
        // The old path (the CHEST_ACTION broadcast forwarded by alpha2services)
        // disappeared with the APK removal.
        // Push here with the same semantics as the old RobotEventReceiver:
        // sub-value 1 = pressed/entered, 0 = released/exited.
        if (plen >= 1) {
            if (payload[0] == (byte) -111) {
                boolean pressed = plen < 2 || payload[1] == 1;
                EventBus.get().publish("chest_mute_key", "{\"pressed\":" + pressed + "}");
                try { onMuteKeyEvent(pressed); } catch (Throwable t) { Log.w(TAG, "onMuteKeyEvent failed", t); }
            } else if (payload[0] == (byte) -109) {
                boolean pirTriggered = plen < 2 || payload[1] == 1;
                EventBus.get().publish("alpha2_pir_state", "{\"triggered\":" + pirTriggered + "}");
                try { onPirStateReceived(pirTriggered); } catch (Throwable t) { Log.w(TAG, "onPirStateReceived failed", t); }
            }
        }
        // Handle upgrade ACKs (48/49/50) first and let ChestUpgrade claim them
        // (pass the raw frame too to get the ack index; outside this line the
        // parse area does not change a single character).
        if (chestUpgrade.onAckFrame(frame, payload, plen)) return;
        // Servo upgrade 16/17 ACK + 134 result go to ServoUpgrade to claim
        // (before the ChestQuery version fallback).
        if (servoUpgrade != null && servoUpgrade.onFrame(frame, payload, plen)) return;
        // The version/UUID reply latch goes to ChestQuery to claim (upgrade
        // ACKs were already handled first above).
        if (chestQuery.onFrame(frame, payload, plen)) return;
    }

    // -- pure-direct shared reply helpers (directCode / codeResponse /
    // codeResponseReady / jsonSafe / toHex / readFully stay here; each center
    // uses them directly through MainActivity.) --
    static UbxErrorCode.API_ERROR_CODE directCode(boolean ok) {
        return ok ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
                : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    // -- Local semantic matching: fully replaces the 悠聊 APK (com.ubtech.iflytekmix)
    // -------------------
    //
    // The complete pipeline restored by decompiling the 悠聊 APK (see the
    // conversation history) is:
    //   the body ASR finishes recognizing a sentence -> JsonResultParse parses
    // it into operation+slots
    //     -> RobotActionBusiness.startBusiness(): TTS (200ms sleep) Action
    // OpenAlpha2 already has its own robot.speech_startTTS() /
    // robot.action_PlayActionName(), so it does not need the 悠聊 RobotHandle
    // wrapper layer. It only needs the "text -> operation/answer/action"
    // semantic matching layer (SemanticMatcherZh, restored from the 850
    // phrasings in 悠聊 assets/local_semantic) and this timing.
    //
    // Where it hooks in: the front end triggers everything through
    // speech/semantic_simulate, so every input method (real speech / typed
    // simulation) takes the same path, avoiding duplicate TTS. Chinese/English
    // is decided by SemanticCenter from the conversation language / input text,
    // looking only at the input text content, not at which language the ASR
    // engine is currently set to.

    // The static seam stays here (the frozen onDirectChestFrame /
    // RobotEventReceiver enter through here, signature unchanged).
    /** Called directly when RobotEventReceiver / onDirectChestFrame receives
     * the chest mute key (-111) (signature unchanged): delegates to
     * XiaozhiBridge. If sInstance / xiaozhiBridge is null, no-op. */
    public static void onMuteKeyEvent(final boolean pressed) {
        final MainActivity m = sInstance;
        if (m == null || m.xiaozhiBridge == null) {
            return;
        }
        m.xiaozhiBridge.onMuteKeyEvent(pressed);
    }

    /** Catches the result of the ACTION_CHECK_TTS_DATA sent by
     *  TtsCenter.checkTtsDataSyncLegacy(). Handles only the requestCodes this
     *  app itself recognizes; hands everything else back to super (this app
     *  currently uses startActivityForResult() nowhere else, but that is basic
     *  etiquette; it should not swallow every requestCode). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TtsCenter.TTS_DATA_CHECK_REQUEST_CODE) {
            ttsCenter.onTtsDataResult(data);
        }
    }

    // -- A side: robot directly connected to BT / USB gamepad / keyboard ----------------
    // HID enters through these three callbacks (see the separate notes for the two
    // sides in GamepadCenter).
    // - Sides execute separately: keyboard-family sources (have KEYBOARD, no
    //   GAMEPAD / JOYSTICK) take the kb mapping, the rest take the pad mapping;
    //   a recognized key is intercepted and executed at once (press and move;
    //   both sides default on).
    // - If that side's switch (HTML "direct gamepad / direct keyboard",
    //   gamepad/input/*) is off, only record, do not execute.
    // - Unrecognized keys: record as unmapped (with code), then pass through or
    //   swallow (keyboard swallows, mouse swallows, gamepad passes through).
    // - Mouse family (MOUSE source, integrated touchpad): swallow all, never
    //   into the system.
    // Repeated downs (long-press repeat, getRepeatCount>0) are always silently
    // swallowed: not recorded, not played.
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        try {
            if (gamepadCenter != null && event != null
                    && event.getAction() != android.view.KeyEvent.ACTION_MULTIPLE) {
                int kc = event.getKeyCode();
                int src = 0;
                try {
                    src = event.getSource();
                } catch (Throwable ignored) {
                }
                boolean isGame = (src & android.view.InputDevice.SOURCE_GAMEPAD)
                        == android.view.InputDevice.SOURCE_GAMEPAD
                        || (src & android.view.InputDevice.SOURCE_JOYSTICK)
                        == android.view.InputDevice.SOURCE_JOYSTICK;
                boolean isKb = (src & android.view.InputDevice.SOURCE_KEYBOARD)
                        == android.view.InputDevice.SOURCE_KEYBOARD;
                boolean isMouse = (src & android.view.InputDevice.SOURCE_MOUSE)
                        == android.view.InputDevice.SOURCE_MOUSE;
                boolean pressed = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                // Keyboard family (no gamepad bit) takes the kb side, the rest
                // always the pad side (including the DPAD-only cap).
                String side = (isKb && !isGame) ? GamepadCenter.SIDE_KB : GamepadCenter.SIDE_PAD;
                boolean enabled = gamepadCenter.isInputEnabled(side);
                // Mouse family: the whole feature is shut - silently swallow,
                // no event recorded, nothing into the system.
                if (isMouse) {
                    return true;
                }
                String logical = GamepadCenter.keyCodeToLogical(kc);
                if (logical != null && GamepadCenter.isKnownKey(side, logical)) {
                    if (event.getRepeatCount() > 0) return true;
                    if (enabled) {
                        gamepadCenter.handleRobotKey(kc, pressed, src, side);
                    } else {
                        gamepadCenter.recordOnlyRobotKey(kc, pressed, side);
                    }
                    return true;
                }
                // Unmapped: external keyboard swallows (not into the system),
                // gamepad passes through; always record first.
                if (isKb && !isGame) {
                    if (event.getRepeatCount() == 0) gamepadCenter.handleRobotUnknown(kc, pressed, src);
                    return true;
                }
                if (isGame) {
                    gamepadCenter.handleRobotUnknown(kc, pressed, src);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "gamepad dispatchKeyEvent failed", t);
        }
        return super.dispatchKeyEvent(event);
    }

    // External input black hole (the mouse / touchpad feature is shut as a
    // whole):
    // - TOOL_TYPE_MOUSE: swallow all (left / middle / right buttons + movement).
    // - Touchscreen-family touch: only swallow external devices (isExternal;
    //   some Bluetooth touchpads run in absolute mode and impersonate a
    //   touchscreen. If not intercepted here, they would press the body views).
    //   The body's own touchscreen (if it has one) passes through -
    //   on this machine, dumpsys input confirmed there is no touchscreen
    //   hardware, so for now everything external can be swallowed.
    // Fingers / stylus through the body screen work as before; the body buttons
    // still press as before.
    /**
     * External device detection (isExternal needs API 26; this project's
     * compileSdk 25 has no such symbol, so use reflection plus a name
     * fallback; if it cannot run, treat it as a body device = pass through, no
     * accidental swallowing).
     */
    private static boolean isExternalInputDevice(int deviceId) {
        android.view.InputDevice dev = null;
        try {
            dev = android.view.InputDevice.getDevice(deviceId);
        } catch (Throwable t) {
            return false;
        }
        if (dev == null) return false;
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("isExternal");
            Object r = m.invoke(dev);
            if (r instanceof Boolean) return ((Boolean) r).booleanValue();
        } catch (Throwable ignored) {
        }
        try {
            String nm = dev.getName() != null ? dev.getName() : "";
            if (nm.contains("rk29") || nm.startsWith("C-Media")
                    || nm.equals("Virtual") || nm.isEmpty()) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Touchpad detection (the name contains touchpad / trackpad; ThinkPad-class
     * names do not contain the full string, so they cannot false-hit; null /
     * unknown names are always treated as a real mouse = swallow).
     */
    private static boolean isTouchpadDevice(int deviceId) {
        try {
            android.view.InputDevice dev = android.view.InputDevice.getDevice(deviceId);
            if (dev == null || dev.getName() == null) return false;
            String nl = dev.getName().toLowerCase(java.util.Locale.US);
            return nl.contains("touchpad") || nl.contains("trackpad");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Touchpad / mouse routing (shared by touch + motion; returning true =
     * consumed).
     * - Touchpad (judged by name): if the kb side is on, turn the head 19/20
     *   through handleRobotTouchpad throttling; if off, silently swallow. Both
     *   relative and absolute modes work (delta accumulation).
     * - Real mouse: swallow all (buttons / movement / touch, not even the Event
     *   Log comes out).
     * - External touchscreen (not a touchpad name): swallow conservatively; the
     *   body screen passes through.
     * - Gamepad / joystick / others: always false, hand back to the normal
     *   chain.
     */
    private boolean routeTouchpadMotion(android.view.MotionEvent event) {
        if (gamepadCenter == null || event == null) return false;
        int src = 0;
        try {
            src = event.getSource();
        } catch (Throwable ignored) {
        }
        boolean mouseBit = (src & android.view.InputDevice.SOURCE_MOUSE)
                == android.view.InputDevice.SOURCE_MOUSE;
        boolean touchBit = (src & android.view.InputDevice.SOURCE_TOUCHSCREEN)
                == android.view.InputDevice.SOURCE_TOUCHSCREEN;
        if (!mouseBit && !touchBit) return false;
        int deviceId = 0;
        try {
            deviceId = event.getDeviceId();
        } catch (Throwable ignored) {
        }
        if (isTouchpadDevice(deviceId)) {
            if (!gamepadCenter.isInputEnabled(GamepadCenter.SIDE_KB)) return true;
            int ai = 0;
            try {
                ai = event.getActionIndex();
                if (ai < 0 || ai >= event.getPointerCount()) ai = 0;
            } catch (Throwable ignored) {
            }
            float x = 0f;
            float y = 0f;
            try {
                x = event.getAxisValue(android.view.MotionEvent.AXIS_X, ai);
                y = event.getAxisValue(android.view.MotionEvent.AXIS_Y, ai);
            } catch (Throwable t) {
                return true;
            }
            int masked = android.view.MotionEvent.ACTION_MASK;
            try {
                masked = event.getActionMasked();
            } catch (Throwable ignored) {
            }
            boolean down = masked == android.view.MotionEvent.ACTION_DOWN
                    || masked == android.view.MotionEvent.ACTION_POINTER_DOWN
                    || masked == android.view.MotionEvent.ACTION_HOVER_ENTER
                    || masked == android.view.MotionEvent.ACTION_UP
                    || masked == android.view.MotionEvent.ACTION_POINTER_UP
                    || masked == android.view.MotionEvent.ACTION_HOVER_EXIT
                    || masked == android.view.MotionEvent.ACTION_CANCEL;
            try {
                gamepadCenter.handleRobotTouchpad(deviceId, x, y, down);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "handleRobotTouchpad failed", t);
            }
            return true;
        }
        int tool = -1;
        try {
            int ai = event.getActionIndex();
            if (ai < 0 || ai >= event.getPointerCount()) ai = 0;
            tool = event.getToolType(ai);
        } catch (Throwable ignored) {
        }
        if (tool == android.view.MotionEvent.TOOL_TYPE_MOUSE) return true;
        if (touchBit) {
            try {
                return isExternalInputDevice(deviceId);
            } catch (Throwable t) {
                return true;
            }
        }
        return mouseBit;
    }

    // 觸控板轉頭＋滑鼠掐死點：dispatchTouchEvent（最前，view 食之前）。
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        try {
            if (routeTouchpadMotion(event)) return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "gamepad dispatchTouchEvent failed", t);
        }
        return super.dispatchTouchEvent(event);
    }

    // 滑鼠類位移掐死點：dispatchGenericMotionEvent（最前，view 食之前）。
    // 背景：onGenericMotionEvent 太遲——hover／滾輪／觸控板點擊會先畀 view 食咗
    // （如機身 Copy 掣畀滑鼠撳到），activity override 攔唔到。觸控屏
    // （TOUCHSCREEN，0x1002，同 MOUSE 0x2002 位唔重疊）唔受影響，機身 Copy 掣
    // 手指照撳得。手柄搖桿（JOYSTICK／GAMEPAD 位）放行，交下面處理。
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        try {
            // 觸控板轉頭／滑鼠吞（見上；手柄搖桿源唔經呢度，落下面）。
            if (routeTouchpadMotion(event)) return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "gamepad dispatchGenericMotionEvent failed", t);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    // 滑鼠已喺上面 dispatchGenericMotionEvent 掐死，呢度只剩手柄搖桿。
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        try {
            if (gamepadCenter != null && event != null
                    && ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK)
                    == android.view.InputDevice.SOURCE_JOYSTICK
                    || (event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD)
                    == android.view.InputDevice.SOURCE_GAMEPAD)) {
                float lx = 0f;
                float ly = 0f;
                float rx = 0f;
                float ry = 0f;
                try {
                    lx = event.getAxisValue(android.view.MotionEvent.AXIS_X);
                    ly = event.getAxisValue(android.view.MotionEvent.AXIS_Y);
                    rx = event.getAxisValue(android.view.MotionEvent.AXIS_Z);
                    ry = event.getAxisValue(android.view.MotionEvent.AXIS_RZ);
                } catch (Throwable ignored) {
                }
                if (lx != 0f || ly != 0f || rx != 0f || ry != 0f) {
                    // pad 邊熄咗：靜默吞（唔記，免 150ms 洗版）。
                    if (gamepadCenter.isInputEnabled(GamepadCenter.SIDE_PAD)) {
                        gamepadCenter.handleRobotAxis(lx, ly, rx, ry, "robot");
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "gamepad onGenericMotionEvent failed", t);
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        // 逐個 null-guard（onCreate 中途炸/早退再 destroy）。
        mainHandler.removeCallbacks(bootVoiceRunnable);
        if (gestureCenter != null) gestureCenter.shutdown();
        if (gamepadCenter != null) gamepadCenter.shutdown();
        ServoReadStream.shutdown();
        if (ubxPlayer != null) ubxPlayer.stopVoice();
        if (micCenter != null) micCenter.shutdownMicHold();
        if (deviceStatus != null) deviceStatus.setAccelerometerEnabled(false);
        if (ttsCenter != null) ttsCenter.shutdown();
        if (localServices != null) {
            try { localServices.stop(); } catch (Throwable ignored) {}
        }
        if (xiaozhiBridge != null) xiaozhiBridge.shutdown();
        if (httpServer != null) {
            httpServer.stop();
        }
        if (robot != null) {
            robot.releaseApi();
        }
        if (vosk != null) {
            try {
                vosk.shutdown();
            } catch (Throwable ignore) {
            }
        }
        if (actionsPackController != null) {
            try {
                actionsPackController.shutdown();
            } catch (Throwable ignore) {
            }
        }
        if (apkDownloadController != null) {
            try {
                apkDownloadController.shutdown();
            } catch (Throwable ignore) {
            }
        }
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (deviceStatus != null) deviceStatus.unregisterBatteryReceiver();
        if (ledCenter != null) ledCenter.unregisterWifiLedReceiver();
        if (panelUrlReceiver != null) {
            try {
                unregisterReceiver(panelUrlReceiver);
                panelUrlReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (grammarCenter != null) grammarCenter.unregisterConnectivityReceiver();
        if (faceTrackCenter != null) {
            try { faceTrackCenter.shutdown(); } catch (Throwable ignored) {}
        }
        if (colorTrackCenter != null) {
            try { colorTrackCenter.shutdown(); } catch (Throwable ignored) {}
        }
        if (videoCenter != null) {
            try { videoCenter.stopIfRecording(); } catch (Throwable ignored) {}
        }
        if (cameraController != null) cameraController.shutdown();
        if (audioController != null) audioController.shutdown();
        MultiMicController.get().shutdown();
        if (audioPlaybackController != null) audioPlaybackController.shutdown();
        if (ledCenter != null) ledCenter.shutdown();
        if (ringtoneCenter != null) ringtoneCenter.stopRingtonePlayback();
        // 之前這裡沒有呼叫 stopLocalMusicPlayback()/stopRadioPlayback() -
        // onDestroy() 就算執行了也不會釋放正在播放的 currentMusicPlayer/currentRadioPlayer,
        // 一直以來都是個 leak (MediaPlayer native resource 沒有 release())。沿用
        // stopRingtonePlayback() 一樣的做法, 在這裡一併全部停止。
        if (audioCenter != null) {
            audioCenter.stopLocalMusicPlayback();
            audioCenter.stopRadioPlayback();
            audioCenter.shutdown();
        }
    }

    private void updatePanelUrlDisplay() {
        final String newIp = deviceStatus.getWifiIp();
        final String newUrl = "http://" + newIp + ":" + HttpServer.PORT + "/";
        currentPanelUrl = newUrl;
        if (panelLinkView != null) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    panelLinkView.setText(newUrl);
                }
            });
        }
        Log.i(TAG, "Panel URL updated to " + newUrl + " (ip=" + newIp + ")");
    }

    private void registerPanelUrlReceiver() {
        panelUrlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)
                        || WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)
                        || android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                        || "android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {
                    // 延時 500ms 等 DHCP 完成取到新 IP
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() { updatePanelUrlDisplay(); }
                    }, 700);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        registerReceiver(panelUrlReceiver, filter);
    }

    // -- API dispatch ----------------------------------------------------------------

    // ---------------- 小智 (XiaoZhi) AI 對話 ----------------
    //
    // "/api/xiaozhi/..." namespace - AI對話 doesn't belong to the robot's own AIDL
    // surface. See XiaozhiClient's class javadoc for the overall protocol/phase-1-scope
    // explanation.

    /** Radio Browser (radio-browser.info) 的其中一個 API 主機 - 官方文件建議客戶端
     *  對 "all.api.radio-browser.info" 做 DNS 解析再從多個鏡像之間挑選, 但這台機器沒有
     *  DNS SRV/多鏡像 failover 的需求 (一台家用機器人, 不是高流量服務), 直接用
     *  官方文件範例裡出現的 de1 這個固定主機就已經足夠, 保持程式碼簡單。 */
    /** Reads an InputStream fully into a UTF-8 string (共用實現見 {@link IOUtil})。 */
    static String readFully(java.io.InputStream in) throws java.io.IOException {
        return IOUtil.readFully(in);
    }

    // -- handleApi 缺口 (mic/grammar core 未搬)：dispatcher 經 Host 調回來，
    // core 搬完跟著走。 --

    // -- GestureCenter.Host (0x5e 總停鍵)：轉交 SpeechCenter (TTS orchestration)。 --
    @Override public void stopAllSpeech() { if (speechCenter != null) speechCenter.stopAllSpeech(); }

    /** Handles POST /upload/audio: raw PCM bytes (16kHz mono 16-bit, matching
     *  AudioPlaybackController's format - see AudioPlaybackController.SAMPLE_RATE_HZ
     *  and app-mic.js's TALK_TARGET_SAMPLE_RATE) from the
     *  browser's mic, queued for playback.
     *  Playback must already be running (audio/play/start) - this does not implicitly
     *  start it, so a stray upload after the user has stopped talking doesn't
     *  re-open the speaker session on its own. */
    private HttpServer.ApiResponse handleUpload(String path, Map<String, String> query, byte[] body,
            Map<String, String> headers) {
        // 整個面板上鎖：啟用中全部上載（chest／music／audio）都要 token
        //（Bearer header 主，panel_token query 為 fallback；見 PanelAuth，未啟用即放行）。
        HttpServer.ApiResponse gate = PanelAuth.requireAuth(this, query, headers);
        if (gate != null) return gate;
        if ("audio".equals(path)) {
            audioPlaybackController.enqueuePcm(body);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bytes\":" + body.length + "}");
        }
        if ("music".equals(path)) {
            return audioCenter.handleMusicUpload(query, body);
        }
        // 胸固件上載閘口在上面 handleUpload 入口統一做（整個面板上鎖）；
        // 保留 "chest".equals(path) 字面比對，不經 helper——check-openapi-drift.py
        // 靠這個字面抽 upload 路由，一改即誤報 missing）。
        if ("chest".equals(path)) {
            return chestUpgrade.handleChestUpload(query, body);
        }
        if ("servo-fw".equals(path)) {
            return servoUpgrade.handleServoFwUpload(query, body);
        }
        return HttpServer.ApiResponse.error("Unknown upload path: " + path);
    }

    private void handleStream(String path, Map<String, String> query, java.net.Socket socket,
            Map<String, String> headers) throws java.io.IOException {
        // 鎖機時串流都要驗（之前全開會漏相機／咪；<img>／fetch 帶 query fallback，見 PanelAuth）。
        HttpServer.ApiResponse gate = PanelAuth.requireAuth(this, query, headers);
        if (gate != null) {
            HttpServer.writeResponse(socket.getOutputStream(), gate, false);
            return;
        }
        if ("camera".equals(path)) {
            cameraApi.handleCameraStream(socket);
        } else if ("mic".equals(path) && query.containsKey("channels") && !"1".equals(query.get("channels"))) {
            // 5-mic FPGA card (card 0): channels=5 or 8, see MultiMicController.
            MultiMicController.get().handleStream(socket, query);
        } else if ("mic/record".equals(path)) {
            MultiMicController.get().handleRecord(socket, query);
        } else if ("mic".equals(path)) {
            micCenter.handleMicStream(socket);
        } else {
            byte[] msg = ("Not found: /stream/" + path).getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream out = socket.getOutputStream();
            // 補 Content-Type＋CORS，同其他回應看齊。
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + msg.length
                    + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
        }
    }

    // 這裡留薄 shim 不直接改 call site，因為 onDirectChestFrame 區間凍結（有人同時改動 serial frame 解析，那區一字不動）。
    private void handleChestObstacleFrame(byte[] bytes, int len) {
        if (sonarCenter != null) sonarCenter.handleChestObstacleFrame(bytes, len);
    }

    // action/list 一律行 ActionDirect.actionListDirect() (讀 actionInfo.txt + UbxPlayer)。
    // 以下僅剩 ubx/servo 共用實現。
    // -- Ubx 直播共用实现（/api/direct/ubx/* 与 /api/alpha2/ubx/* 同调；
    // 抢占式：播新动作自动停旧动作，与原厂 playActionName 打断语义一致）--
    // 动作配乐已并入 UbxPlayer 内 voice 线（a/j/a/o 官方语义），此处不再另起 MediaPlayer。
    // 配乐寻址规则见 UbxPlayer.resolveVoiceFile：ubx去扩展名/music名，缺省退回目录首首 mp3。
    // (Map.getOrDefault 在 API 22 會 NoSuchMethodError, 一律經 ApiValidator.optional()
    // 取代; 空字串同缺席一樣回 default, 非法值拋 IllegalArgumentException → 400)。
    static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    /**
     * Same as codeResponse but also reports whether the underlying chest/header
     * direct serial port was actually open (caller 經自己內聯的
     * directChestReady()/directHeaderReady() 傳入 ready，見各 center)
     * at the time of the call. pure-direct: no AIDL bind exists any more;
     * API_ERROR_SUCCEED means the frame was written to /dev/ttyS1/S3.
     */
    static HttpServer.ApiResponse codeResponseReady(UbxErrorCode.API_ERROR_CODE code, boolean ready) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code
                + "\",\"bindReady\":" + ready + "}");
    }

    /** 直發＋就緒二合一（之前 8 處 codeResponseReady(directCode(sent), ready) 三層嵌套，
     *  僅 sent 變量／ready 來源不同）。 */
    static HttpServer.ApiResponse sentReadyResponse(boolean sent, boolean ready) {
        return codeResponseReady(directCode(sent), ready);
    }

    // 轉義單一實現見 JsonUtil。保留這個 shim：~60 個 call site 經 MainActivity. 直用，
    // 兼顧 XiaozhiOtaClient activationMessage 帶 literal "\n" 必須 escape 先可以
    // JSON.parse() 的實機背景（詳見 git history）。
    static String jsonSafe(String s) {
        return JsonUtil.esc(s);
    }

    /** RobotEventReceiver tts_hint_wakeup 交給 GrammarCenter (wakeup probe)。 */
    public static void triggerWakeupProbe() {
        MainActivity m = sInstance;
        if (m == null || m.grammarCenter == null) {
            return;
        }
        m.grammarCenter.triggerWakeupProbe();
    }

    // 胸板 queryChestFirmwareVersion() 保留。

    // -- 胸口升級實作 (48/49/50，鏡像 alpha2services h.a.a$b) ---------------------------
    /** Formats raw serial bytes as space-separated uppercase hex, matching the format
     *  used by the upstream SDK's HelloAlpha example for the same callbacks.
     *  手寫 hex 表（chest 幀路徑高頻，慳 GC）。 */
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    static String toHex(byte[] bytes, int len) {
        if (bytes == null || len <= 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(len * 3);
        int n = Math.min(len, bytes.length);
        for (int i = 0; i < n; i++) {
            int v = bytes[i] & 0xFF;
            sb.append(HEX_UPPER[v >>> 4]).append(HEX_UPPER[v & 0x0F]);
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}



