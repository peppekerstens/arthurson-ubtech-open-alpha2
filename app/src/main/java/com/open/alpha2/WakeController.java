package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Recognizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Wake word, local commands and the follow-up window (alpha2-brain#16).
 *
 * One AudioRecord: the Xiaozhi capture runs all the time in wake mode. Each 60 ms PCM
 * frame goes to a local Vosk grammar recognizer (wake words, command phrases, [unk]).
 * The capture sends frames to the server only during a turn (uplink on).
 *
 * Flow: wake word -> listen start, uplink on -> the server answers (tts start: uplink
 * off, half duplex) -> tts stop: listen start again, follow-up window (window_ms). The
 * window stays open while the microphone hears speech. After the window: listen stop,
 * back to wake mode.
 *
 * Local commands work without the server (never lock out): stop, stand up, squat,
 * rest, sleep, volume up and down. "stop" also works during an answer.
 *
 * The setting "enabled" is false by default. wake/set?enabled=false stops all of it.
 */
public final class WakeController {
    private static final String TAG = "WakeController";
    private static final String PREFS = "alpha2_wake";

    private static final String DEFAULT_WORDS = "alpha,robot";
    // One language per list: the Vosk model and its phrases go together (laptop test,
    // docs/speech-options.md "Dutch command list"). "hurk" is 4/10 in noise: use "ga hurken".
    private static final String DEFAULT_COMMANDS_EN = "{"
            + "\"stop\":\"stop\",\"stand up\":\"stand_up\",\"sit down\":\"squat\",\"squat\":\"squat\","
            + "\"rest\":\"rest\",\"sleep\":\"sleep\","
            + "\"volume up\":\"volume_up\",\"louder\":\"volume_up\","
            + "\"volume down\":\"volume_down\",\"quieter\":\"volume_down\""
            + "}";
    private static final String DEFAULT_COMMANDS_NL = "{"
            + "\"stop\":\"stop\",\"sta op\":\"stand_up\",\"ga zitten\":\"squat\",\"ga hurken\":\"squat\","
            + "\"rust\":\"rest\",\"slaap\":\"sleep\","
            + "\"volume hoger\":\"volume_up\",\"harder\":\"volume_up\","
            + "\"volume lager\":\"volume_down\",\"zachter\":\"volume_down\""
            + "}";
    private static final String MODEL_EN = "vosk-model-small-en-us-0.15";
    private static final String MODEL_NL = "vosk-model-small-nl-0.22";
    private static final String SQUAT_FILE = "Squat-myrobot.ubx";
    private static final String STAND_FILE = "StandUp-myrobot.ubx";

    enum Mode { OFF, WAKE, LISTEN, THINKING, SPEAKING }

    private static volatile WakeController instance;

    private final Context appContext;
    private final XiaozhiBridge bridge;
    private final VoskController vosk;
    private final ApiDispatcher api;

    private final LinkedBlockingQueue<short[]> frames = new LinkedBlockingQueue<>(64);
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private volatile Mode mode = Mode.OFF;
    private volatile String lastError;
    private volatile String lastHeard = "";
    private volatile boolean sleeping = false;
    private volatile long windowDeadline;
    private volatile long lastSpeechMs;
    private volatile long thinkingSince;
    private volatile long lastCaptureCheck;
    private volatile long commandUntil;
    private volatile boolean commandRunning;
    /** Goes up at each new turn and each "stop": a queued motion of an older generation is dropped. */
    private volatile int motionGeneration;
    private static final long MOTION_MAX_DELAY_MS = 2000;
    private Thread worker;
    private Recognizer recognizer; // worker thread only

    private WakeController(Context ctx, XiaozhiBridge bridge, VoskController vosk, ApiDispatcher api) {
        this.appContext = ctx.getApplicationContext();
        this.bridge = bridge;
        this.vosk = vosk;
        this.api = api;
    }

    // -- static entry points -----------------------------------------------------

    /** MainActivity: after the ApiDispatcher exists. Starts wake mode when it is enabled. */
    public static void init(Context ctx, XiaozhiBridge bridge, VoskController vosk, ApiDispatcher api) {
        if (vosk == null || bridge == null || api == null) return; // API < 21: no Vosk
        final WakeController w = new WakeController(ctx, bridge, vosk, api);
        instance = w;
        if (w.prefs().getBoolean("enabled", false)) {
            // The Vosk model loads in the background at boot: wait for it.
            new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < 120 && w.mode == Mode.OFF; i++) {
                        if (w.prefs().getBoolean("enabled", false) && w.start() == null) return;
                        try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    }
                }
            }, "WakeBoot").start();
        }
    }

    public static boolean isActive() {
        WakeController w = instance;
        return w != null && w.mode != Mode.OFF;
    }

    public static void onXiaozhiTts(String state) {
        WakeController w = instance;
        if (w != null) w.handleTts(state);
    }

    public static void onXiaozhiStt(String text) {
        WakeController w = instance;
        if (w != null) w.handleStt(text);
    }

    public static HttpServer.ApiResponse api(String path, Map<String, String> query) {
        WakeController w = instance;
        if (w == null) return HttpServer.ApiResponse.error("wake word needs Vosk (Android 5.0+)");
        if ("wake/set".equals(path)) {
            String err = w.applySettings(query);
            if (err != null) return HttpServer.ApiResponse.error(err);
        }
        return HttpServer.ApiResponse.ok(w.statusJson());
    }

    // -- settings ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private List<String> wakeWords() {
        List<String> out = new ArrayList<>();
        for (String w : prefs().getString("words", DEFAULT_WORDS).split(",")) {
            String t = w.trim().toLowerCase(Locale.US);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** en (default, owner decision 2026-10-31: Vosk only for the wake word and the fixed
     *  English list) or nl. Selects the Vosk model and its list. */
    private String language() { return "nl".equals(prefs().getString("language", "en")) ? "nl" : "en"; }

    private String modelId() { return "en".equals(language()) ? MODEL_EN : MODEL_NL; }

    private JSONObject commandMap() {
        String lang = language();
        String def = "en".equals(lang) ? DEFAULT_COMMANDS_EN : DEFAULT_COMMANDS_NL;
        try {
            return new JSONObject(prefs().getString("commands_" + lang, def));
        } catch (Exception e) {
            try { return new JSONObject(def); } catch (Exception e2) { return new JSONObject(); }
        }
    }

    /** A local command counts only this long after the wake word. */
    private int commandWindowMs() { return prefs().getInt("command_window_ms", 4000); }

    private int windowMs() { return prefs().getInt("window_ms", 10000); }

    private boolean halfDuplex() { return prefs().getBoolean("half_duplex", true); }

    /** release = servos limp only. servo_power = also cmd 0x19 off. power_save = also cmd 0x40. */
    private String sleepMode() { return prefs().getString("sleep_mode", "release"); }

    private int speechRms() { return prefs().getInt("speech_rms", 400); }

    private String applySettings(Map<String, String> q) {
        SharedPreferences.Editor ed = prefs().edit();
        try {
            if (q.containsKey("words")) ed.putString("words", q.get("words"));
            if (q.containsKey("language")) {
                String l = q.get("language");
                if (!"nl".equals(l) && !"en".equals(l)) return "language must be nl or en";
                ed.putString("language", l);
            }
            if (q.containsKey("command_window_ms")) {
                int v = Integer.parseInt(q.get("command_window_ms").trim());
                if (v < 1000 || v > 15000) return "command_window_ms must be 1000-15000";
                ed.putInt("command_window_ms", v);
            }
            if (q.containsKey("window_ms")) {
                int v = Integer.parseInt(q.get("window_ms").trim());
                if (v < 2000 || v > 60000) return "window_ms must be 2000-60000";
                ed.putInt("window_ms", v);
            }
            if (q.containsKey("half_duplex")) ed.putBoolean("half_duplex", Boolean.parseBoolean(q.get("half_duplex")));
            if (q.containsKey("cue")) ed.putBoolean("cue", Boolean.parseBoolean(q.get("cue")));
            if (q.containsKey("speech_rms")) ed.putInt("speech_rms", Integer.parseInt(q.get("speech_rms").trim()));
            if (q.containsKey("sleep_mode")) {
                String s = q.get("sleep_mode");
                if (!"release".equals(s) && !"servo_power".equals(s) && !"power_save".equals(s)) {
                    return "sleep_mode must be release, servo_power or power_save";
                }
                ed.putString("sleep_mode", s);
            }
            if (q.containsKey("commands")) {
                String c = q.get("commands");
                // The list of the language in the same request, else of the stored language.
                String l = q.containsKey("language") ? q.get("language") : language();
                if (c == null || c.trim().isEmpty() || "default".equals(c.trim())) {
                    ed.remove("commands_" + l);
                } else {
                    new JSONObject(c); // must parse
                    ed.putString("commands_" + l, c);
                }
            }
        } catch (Exception e) {
            return "bad value: " + e.getMessage();
        }
        boolean wasOn = mode != Mode.OFF;
        Boolean enable = q.containsKey("enabled") ? Boolean.valueOf(q.get("enabled")) : null;
        if (enable != null) ed.putBoolean("enabled", enable);
        ed.apply();
        if (enable != null && !enable) {
            stop();
            return null;
        }
        if ((enable != null && enable) || wasOn) {
            stop(); // new grammar or settings: start again
            return start();
        }
        return null;
    }

    private String grammarJson() {
        JSONArray g = new JSONArray();
        for (String w : wakeWords()) g.put(w);
        Iterator<String> it = commandMap().keys();
        while (it.hasNext()) g.put(it.next().toLowerCase(Locale.US));
        g.put("[unk]");
        return g.toString();
    }

    private String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("enabled", prefs().getBoolean("enabled", false));
            o.put("mode", mode.name().toLowerCase(Locale.US));
            o.put("language", language());
            o.put("model", modelId());
            o.put("command_window_ms", commandWindowMs());
            o.put("words", new JSONArray(wakeWords()));
            o.put("window_ms", windowMs());
            o.put("half_duplex", halfDuplex());
            o.put("cue", prefs().getBoolean("cue", true));
            o.put("sleep_mode", sleepMode());
            o.put("speech_rms", speechRms());
            o.put("commands", commandMap());
            o.put("sleeping", sleeping);
            o.put("last_heard", lastHeard);
            o.put("error", lastError == null ? JSONObject.NULL : lastError);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    // -- start and stop -------------------------------------------------------------

    private synchronized String start() {
        if (mode != Mode.OFF) return null;
        String model = modelId();
        if (!model.equals(vosk.getModelId())) {
            String err = vosk.loadModel(model);
            if (err != null) return fail("model " + model + ": " + err);
            for (int i = 0; i < 120 && !model.equals(vosk.getModelId()); i++) {
                if (vosk.getState() == VoskController.State.ERROR) break;
                sleepMs(500);
            }
            if (!model.equals(vosk.getModelId())) return fail("model " + model + " did not load");
        }
        Recognizer r;
        try {
            r = vosk.newGrammarRecognizer(grammarJson());
        } catch (Throwable t) {
            return fail("grammar recognizer failed: " + t.getMessage());
        }
        if (r == null) return fail("load a Vosk model first");
        recognizer = r;
        frames.clear();
        String err = bridge.wakeCaptureStart(tapInstance);
        if (err != null) {
            closeRecognizer();
            return fail("microphone: " + err);
        }
        lastError = null;
        mode = Mode.WAKE;
        worker = new Thread(new Runnable() {
            @Override public void run() { workLoop(); }
        }, "WakeWorker");
        worker.start();
        Log.i(TAG, "wake mode on, model " + model + ", grammar " + grammarJson());
        publish();
        return null;
    }

    private synchronized void stop() {
        if (mode == Mode.OFF) return;
        if (mode != Mode.WAKE) bridge.wakeTurnEnd();
        mode = Mode.OFF;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException ignore) { }
        }
        bridge.wakeCaptureStop();
        closeRecognizer();
        frames.clear();
        Log.i(TAG, "wake mode off");
        publish();
    }

    private String fail(String msg) {
        lastError = msg;
        Log.w(TAG, msg);
        publish();
        return msg;
    }

    private void closeRecognizer() {
        Recognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.close(); } catch (Throwable ignore) { }
        }
    }

    private final XiaozhiAudioController.PcmTap tapInstance = new XiaozhiAudioController.PcmTap() {
        @Override public void onPcm(short[] pcm) {
            // Copy and return at once. A full queue drops the oldest frame.
            short[] copy = pcm.clone();
            if (!frames.offer(copy)) {
                frames.poll();
                frames.offer(copy);
            }
        }
    };

    private void publish() {
        EventBus.get().publish("wake_state", "{\"mode\":\"" + mode.name().toLowerCase(Locale.US)
                + "\",\"sleeping\":" + sleeping + "}");
    }

    // -- worker: recognizer, window, capture watchdog --------------------------------

    private void workLoop() {
        while (mode != Mode.OFF && !Thread.currentThread().isInterrupted()) {
            short[] f;
            try {
                f = frames.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();
            if (f != null) {
                if (rms(f) >= speechRms()) lastSpeechMs = now;
                Recognizer r = recognizer;
                if (r != null) {
                    try {
                        if (r.acceptWaveForm(f, f.length)) {
                            onFinal(r.getResult());
                        } else {
                            String partial = new JSONObject(r.getPartialResult()).optString("partial", "");
                            if (!clean(partial).isEmpty()) lastSpeechMs = now;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "recognizer: " + t.getMessage());
                    }
                }
            }
            checkTimers(now);
        }
    }

    private static double rms(short[] f) {
        long sum = 0;
        for (short s : f) sum += (long) s * s;
        return Math.sqrt(sum / (double) f.length);
    }

    /** Removes [unk] and extra spaces. */
    private static String clean(String text) {
        if (text == null) return "";
        return text.replace("[unk]", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.US);
    }

    private void checkTimers(long now) {
        Mode m = mode;
        if (m == Mode.LISTEN && now > windowDeadline) {
            if (now - lastSpeechMs < 1200) {
                windowDeadline = now + 1000; // the user still speaks: keep the window open
            } else {
                Log.i(TAG, "follow-up window closed");
                endTurn();
            }
        } else if (m == Mode.THINKING && now - thinkingSince > 20000) {
            // No answer came: open the window again.
            mode = Mode.LISTEN;
            windowDeadline = now + windowMs();
        }
        if (now - lastCaptureCheck > 3000) {
            lastCaptureCheck = now;
            if (mode != Mode.OFF && !bridge.wakeCaptureRunning()) {
                Log.w(TAG, "capture stopped, start again");
                String err = bridge.wakeCaptureStart(tapInstance);
                if (err != null) lastError = "microphone: " + err;
                if (mode != Mode.WAKE) bridge.wakeListenAgain();
            }
        }
    }

    private void onFinal(String json) {
        String text;
        String raw;
        double conf;
        try {
            JSONObject o = new JSONObject(json);
            raw = o.optString("text", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.US);
            text = clean(raw);
            conf = avgConf(o.optJSONArray("result"));
        } catch (Exception e) {
            return;
        }
        if (text.isEmpty()) return;
        lastHeard = text;
        Log.i(TAG, "heard: " + text + " conf=" + conf + " mode=" + mode);
        if (conf >= 0 && conf < 0.5) return;
        JSONObject cmds = commandMap();
        // A command must be the whole utterance, with or without a wake word in front
        // ("alpha sta op" or "sta op"), not a word inside a sentence. The raw result
        // keeps the [unk] tokens: "[unk] slaap" is a sentence, not a command.
        String rest = raw;
        for (String w : wakeWords()) {
            if (rest.startsWith(w + " ")) rest = rest.substring(w.length() + 1).trim();
        }
        String command = null;
        Iterator<String> it = cmds.keys();
        while (it.hasNext()) {
            String phrase = it.next();
            if (rest.equals(phrase.toLowerCase(Locale.US))) {
                command = cmds.optString(phrase);
                break;
            }
        }
        boolean wake = false;
        for (String w : wakeWords()) {
            if ((" " + text + " ").contains(" " + w + " ")) wake = true;
        }
        long now = System.currentTimeMillis();
        // "stop" also inside a sentence (stopping is safe), but without the wake word
        // only while an action or speech runs.
        if (command == null && (" " + text + " ").contains(" stop ") && cmds.has("stop")) {
            command = "stop";
        }
        if ("stop".equals(command) && (wake || busy())) {
            runCommand("stop");
            return;
        }
        // Other local commands: with the wake word in the same phrase, or a few
        // seconds after it. This removes the false alarms of normal talk.
        if (command != null && !"stop".equals(command) && (wake || now < commandUntil)) {
            commandUntil = 0;
            if (mode != Mode.WAKE) endTurn();
            runCommand(command);
            return;
        }
        if (wake && mode == Mode.WAKE) {
            commandUntil = now + commandWindowMs();
            motionGeneration++; // a new turn drops queued motion
            beginTurn();
        }
    }

    /** True while the robot speaks or moves (an answer, Android TTS, an action, a command). */
    private boolean busy() {
        if (mode == Mode.SPEAKING || commandRunning || LedCenter.isMouthTtsActive()) return true;
        HttpServer.ApiResponse s = direct("ubx/status", null);
        return s != null && s.body != null && s.body.contains("\"playing\":true");
    }

    private static double avgConf(JSONArray arr) {
        if (arr == null || arr.length() == 0) return -1;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            double c = w.optDouble("conf", -1);
            if (c >= 0) { sum += c; n++; }
        }
        return n == 0 ? -1 : sum / n;
    }

    // -- turn ------------------------------------------------------------------------

    private void beginTurn() {
        commands.execute(new Runnable() {
            @Override public void run() {
                // The wake word never moves the robot (robot test 2026-10-31: it stood up
                // after "sleep"). Only "sta op" / "stand up" stands up.
                if (!bridge.isConnected()) {
                    bridge.wakeConnect();
                    for (int i = 0; i < 30 && !bridge.isConnected(); i++) {
                        try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    }
                }
                if (!bridge.isConnected()) {
                    clip("noconn", "No connection.");
                    return;
                }
                // Cue: the robot says "Yes?" before the uplink opens (not sent to the server).
                if (prefs().getBoolean("cue", true)) {
                    clip("cue", "Yes?");
                    sleepMs(200);
                }
                String err = bridge.wakeTurnBegin();
                if (err != null) {
                    lastError = "turn: " + err;
                    clip("noconn", "No connection.");
                    return;
                }
                lastSpeechMs = System.currentTimeMillis();
                windowDeadline = System.currentTimeMillis() + windowMs();
                mode = Mode.LISTEN;
                Log.i(TAG, "turn open");
                publish();
            }
        });
    }

    private void endTurn() {
        bridge.wakeTurnEnd();
        if (mode != Mode.OFF) mode = Mode.WAKE;
        publish();
    }

    private void handleStt(String text) {
        if (mode == Mode.LISTEN) {
            mode = Mode.THINKING;
            thinkingSince = System.currentTimeMillis();
        }
    }

    private void handleTts(String state) {
        Mode m = mode;
        if (m == Mode.OFF || m == Mode.WAKE) return;
        if ("start".equals(state)) {
            mode = Mode.SPEAKING;
            if (halfDuplex()) bridge.wakeUplink(false);
        } else if ("stop".equals(state)) {
            bridge.wakeListenAgain();
            lastSpeechMs = 0;
            windowDeadline = System.currentTimeMillis() + windowMs();
            mode = Mode.LISTEN;
            publish();
        }
    }

    // -- local commands --------------------------------------------------------------

    private static boolean isMotion(String command) {
        return "stand_up".equals(command) || "squat".equals(command)
                || "rest".equals(command) || "sleep".equals(command);
    }

    private void runCommand(final String command) {
        Log.i(TAG, "local command: " + command);
        final long heardAt = System.currentTimeMillis();
        final int generation = motionGeneration;
        if ("stop".equals(command)) {
            motionGeneration++; // drop queued motion
            // At once, not behind a queued command.
            if (mode != Mode.WAKE && mode != Mode.OFF) {
                bridge.wakeAbort();
                bridge.wakeTurnEnd();
                mode = Mode.WAKE;
                publish();
            }
            call("speech/stop", null);
            direct("ubx/stop", null);
            return;
        }
        commands.execute(new Runnable() {
            @Override public void run() {
                if (isMotion(command)) {
                    long late = System.currentTimeMillis() - heardAt;
                    if (late > MOTION_MAX_DELAY_MS || generation != motionGeneration) {
                        Log.w(TAG, "motion dropped: " + command + " (" + late + " ms late, generation "
                                + generation + "/" + motionGeneration + ")");
                        return;
                    }
                }
                commandRunning = true;
                try {
                    switch (command) {
                        case "stand_up":
                            if (sleeping) wakeBody(false);
                            playAndWait(STAND_FILE);
                            break;
                        case "squat":
                            if (sleeping) wakeBody(false);
                            playAndWait(SQUAT_FILE);
                            break;
                        case "rest":
                            rest(false);
                            break;
                        case "sleep":
                            rest(true);
                            break;
                        case "volume_up":
                            volumeStep(2);
                            break;
                        case "volume_down":
                            volumeStep(-2);
                            break;
                        default:
                            Log.w(TAG, "unknown command " + command);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "command " + command + " failed", t);
                } finally {
                    commandRunning = false;
                }
            }
        });
    }

    /** Squat first, then release servos 1-20 (owner rule: squat before a servo release). */
    private void rest(boolean sleep) {
        if (!playAndWait(SQUAT_FILE)) {
            Log.w(TAG, "squat did not start: the servos stay powered");
            clip("nosquat", "I cannot squat.");
            return;
        }
        sleepMs(1000);
        for (int id = 1; id <= 20; id++) {
            Map<String, String> q = new HashMap<>();
            q.put("id", String.valueOf(id));
            call("servo/angle", q); // reading the angle releases the servo
            sleepMs(200);
        }
        direct("led/off", null);
        if (!sleep) return;
        String m = sleepMode();
        if ("servo_power".equals(m) || "power_save".equals(m)) {
            direct("chest/servo_power", one("on", "0"));
        }
        if ("power_save".equals(m)) {
            Map<String, String> q = one("enter", "1");
            q.put("servo_off", "1");
            direct("chest/power_save", q);
        }
        sleeping = true;
        publish();
    }

    /** Leaves chest sleep and turns the servo power on. Then stands up when asked. */
    private void wakeBody(boolean standUp) {
        String m = sleepMode();
        if ("power_save".equals(m)) {
            direct("chest/power_save", one("enter", "0"));
            sleepMs(500);
        }
        if ("servo_power".equals(m) || "power_save".equals(m)) {
            direct("chest/servo_power", one("on", "1"));
            sleepMs(500);
        }
        sleeping = false;
        publish();
        if (standUp) playAndWait(STAND_FILE);
    }

    private boolean playAndWait(String file) {
        HttpServer.ApiResponse r = direct("ubx/play", one("name", file));
        if (r == null || r.body == null || !r.body.contains("\"ok\":true")) return false;
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            sleepMs(500);
            HttpServer.ApiResponse s = direct("ubx/status", null);
            if (s != null && s.body != null && s.body.contains("\"playing\":false")) return true;
        }
        return true;
    }

    private void volumeStep(int delta) {
        HttpServer.ApiResponse r = call("audio/volume/get", null);
        int cur = 8, max = 15;
        try {
            JSONObject o = new JSONObject(r.body);
            cur = o.optInt("volume", cur);
            max = o.optInt("max", max);
        } catch (Exception ignore) { }
        int target = Math.max(2, Math.min(Math.min(15, max), cur + delta));
        call("audio/volume/set", one("level", String.valueOf(target)));
        clip("volume-" + target, "Volume " + target + ".");
    }

    private void say(String text) {
        say(text, "en-US");
    }

    /** Plays /sdcard/alpha2/voice/<language>/<name>.wav (made with the same Piper voice as
     *  the answers, nl_NL-ronnie-medium or en_US-lessac-medium) and waits until it ends.
     *  Without the file: Android TTS in English (the robot has no Dutch Android voice). */
    private void clip(String name, String fallback) {
        java.io.File f = new java.io.File("/sdcard/alpha2/voice/" + language() + "/" + name + ".wav");
        if (!f.isFile()) {
            say(fallback);
            return;
        }
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        try {
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setDataSource(f.getPath());
            mp.prepare();
            int ms = Math.max(200, Math.min(5000, mp.getDuration()));
            mp.start();
            sleepMs(ms + 100);
        } catch (Exception e) {
            Log.w(TAG, "clip " + f + ": " + e.getMessage());
            say(fallback);
        } finally {
            try { mp.release(); } catch (Throwable ignore) { }
        }
    }

    /** Android TTS. The robot has no Dutch voice (de, en, es, fr, it only). */
    private void say(String text, String lang) {
        Map<String, String> q = one("text", text);
        q.put("lang", lang);
        call("speech/tts", q);
    }

    private static Map<String, String> one(String k, String v) {
        Map<String, String> q = new HashMap<>();
        q.put(k, v);
        return q;
    }

    private HttpServer.ApiResponse call(String path, Map<String, String> q) {
        try {
            return api.handleApi(path, q == null ? new HashMap<String, String>() : q, "GET", null);
        } catch (Throwable t) {
            Log.w(TAG, path + " failed: " + t.getMessage());
            return null;
        }
    }

    private HttpServer.ApiResponse direct(String path, Map<String, String> q) {
        try {
            return api.handleDirectApi(path, q == null ? new HashMap<String, String>() : q, "GET", null);
        } catch (Throwable t) {
            Log.w(TAG, "direct/" + path + " failed: " + t.getMessage());
            return null;
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
