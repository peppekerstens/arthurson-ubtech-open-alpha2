package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vosk offline ASR (voice tab). The robot no longer has iFlytek/Nuance, so
 * Vosk (alphacephei/vosk-android AAR, armeabi-v7a native, API 21+) takes over.
 *
 * <p>Models do not ship with the app (each is about 65MB): they live on
 * sdcard and are scanned here for auto-detection (user request).
 * Scan roots: top-level directories of
 * {@link Environment#getExternalStorageDirectory()} + {@code /mnt/internal_sd};
 * a directory with {@code am/final.mdl} counts as one usable model (id =
 * directory name).
 *
 * <p>Flow: {@link #scanModels()} lists -> {@link #loadModel(String)}
 * (background thread, a few seconds + ~200MB RAM, only one resident at a
 * time) -> {@link #startListening()} (SpeechService opens its own
 * AudioRecord, 16kHz) -> partials show in real time via the {@code asr_partial}
 * event, finals via the reused {@code asr_result} event (the same pipeline as
 * the frontend's typed-input simulation: user bubble + semantic pairing +
 * Android TTS).
 *
 * <p>Open recognition (open vocab): no constrained grammar, a plain
 * unconstrained Recognizer.
 *  Hallucinations are only controlled by three hard-coded things: the conf
 * threshold fixed at 0.01 / pause decode during TTS / the frontend does not
 * bubble a single word.
 *
 * <p>All public methods are thread-safe; heavy work (load) opens its own
 * thread, and callbacks come back via listeners (the SpeechService ones run
 * on the main thread and must stay light).
 */
public final class VoskController {
    private static final String TAG = "VoskController";
    public static final float SAMPLE_RATE = 16000.0f;
    private static final String PREFS_NAME = "robotpanel";
    private static final String PREF_MODEL_ID = "vosk_model_id";
    /** Stored grammar (JSON array of phrases, "[unk]" for other words). Empty = open vocabulary. */
    private static final String PREF_GRAMMAR = "vosk_grammar";
    /** Dialogue language (zh/en/es/fr/ja/de/it/pt/ko/ru): recorded when loadModel succeeds; used for boot restore + status display. */
    private static final String PREF_DIALOGUE_LANG = "vosk_dialogue_lang";

    /** One usable model found by the scan (langHint = Chinese name, langEn = English name; the frontend picks the display by UI language). */
    public static final class VoskModelInfo {
        public final String id;
        public final String path;
        public final String langHint;
        public final String langEn;
        public final long sizeBytes;
        VoskModelInfo(String id, String path, String langHint, String langEn, long sizeBytes) {
            this.id = id;
            this.path = path;
            this.langHint = langHint;
            this.langEn = langEn;
            this.sizeBytes = sizeBytes;
        }
    }

    public enum State { IDLE, LOADING, READY, LISTENING, ERROR }

    private final Context appContext;
    private final SemanticMatcherZh matcherZh;

    private final SemanticMatcherEn matcherEn;

    private final SemanticMatcherEs matcherEs;

    private final SemanticMatcherFr matcherFr;

    private final SemanticMatcherJa matcherJa;

    private final SemanticMatcherDe matcherDe;

    private final SemanticMatcherIt matcherIt;

    private final SemanticMatcherPt matcherPt;

    private final SemanticMatcherKo matcherKo;

    private final SemanticMatcherRu matcherRu;
    private volatile State state = State.IDLE;
    private volatile String modelId;
    private volatile String lastError;
    /** Which pack is loading (recorded when loadModel starts; cleared on steering change / unload / completion; used for the pre-job check). */
    private volatile String loadingId;

    // -- Model download (experiments tab download card; the backend downloads the zip + unzips directly) --
    // Official small model catalog (cross-checked against alphacephei.com/vosk/models):
    // {modelId, url, sizeMb}: sizeMb is the official page label (approximate, for the user to judge traffic).
    // Large sizes (1G class) are not accepted: the robot's RAM cannot hold them. Fixed allowlist, no arbitrary URLs (anti-SSRF).
    private static final String DL_BASE = "https://alphacephei.com/vosk/models/";
    private static final String[][] DL_CATALOG = {
        {"vosk-model-small-cn-0.22", "42"},
        {"vosk-model-small-en-us-0.15", "40"},
        {"vosk-model-small-en-in-0.4", "36"},
        {"vosk-model-small-ru-0.22", "45"},
        {"vosk-model-small-fr-0.22", "41"},
        {"vosk-model-small-de-0.15", "45"},
        {"vosk-model-small-es-0.42", "39"},
        {"vosk-model-small-pt-0.3", "31"},
        {"vosk-model-small-tr-0.3", "35"},
        {"vosk-model-small-vn-0.4", "32"},
        {"vosk-model-small-it-0.22", "48"},
        {"vosk-model-small-nl-0.22", "39"},
        {"vosk-model-small-ca-0.4", "42"},
        {"vosk-model-small-fa-0.42", "53"},
        {"vosk-model-small-ja-0.22", "48"},
        {"vosk-model-small-eo-0.42", "42"},
        {"vosk-model-small-hi-0.22", "42"},
        {"vosk-model-small-cs-0.4-rhasspy", "44"},
        {"vosk-model-small-pl-0.22", "50"},
        {"vosk-model-small-uz-0.22", "49"},
        {"vosk-model-small-ko-0.22", "82"},
        {"vosk-model-br-0.8", "70"},
        {"vosk-model-small-gu-0.42", "100"},
        {"vosk-model-small-tg-0.22", "50"},
        {"vosk-model-small-te-0.42", "58"},
        {"vosk-model-small-ky-0.42", "49"},
        {"vosk-model-small-ka-0.42", "45"},
        {"vosk-model-small-kz-0.42", "58"},
        {"vosk-model-small-uk-v3-nano", "73"},
    };
    // Download state machine (independent from the load/listen State above: the main State stays IDLE during a download, undisturbed).
    // idle = not started / before the reset after completion; downloading / unzipping = in progress; done / error / cancelled = terminal states.
    private volatile String dlState = "idle";
    private volatile String dlLang; // "cn"/"en"
    private volatile String dlModel; // full directory name
    private volatile int dlProgress = -1; // 0-100, -1 = unknown (unzipping / length unknown)
    private volatile long dlBytes = 0;
    private volatile long dlTotal = -1;
    private volatile String dlError;
    private volatile boolean dlCancel = false;
    private Thread dlThread;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private String grammarJson; // always null (open-vocabulary recognition; the field is kept to avoid changing the start flow)
    private boolean stripSpaces; // always false (previously used by the old Chinese char-grammar first)

    /** When the average word conf of a final is below this, treat it as
     *  bottom-noise hallucination and drop it (do not publish, do not pair;
     *  logcat keeps "low-conf final dropped" as before).
     *  -1 = unknown (old lib / no result array): still let it pass, no
     *  mistaken kills.
     *  Fixed value 0.01: almost everything passes; only extremely low-score
     *  garbage is blocked. */
    private static final double MIN_FINAL_CONF = 0.01;

    public VoskController(Context context, SemanticMatcherZh zh,
            SemanticMatcherEn en, SemanticMatcherEs es, SemanticMatcherFr fr, SemanticMatcherJa ja,
            SemanticMatcherDe de, SemanticMatcherIt it, SemanticMatcherPt pt,
            SemanticMatcherKo ko, SemanticMatcherRu ru) {
        this.appContext = context.getApplicationContext();
        this.matcherZh = zh;
        this.matcherEn = en;
        this.matcherEs = es;
        this.matcherFr = fr;
        this.matcherJa = ja;
        this.matcherDe = de;
        this.matcherIt = it;
        this.matcherPt = pt;
        this.matcherKo = ko;
        this.matcherRu = ru;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable ignore) {
        }
        // The previously selected model (if still present) auto-loads in the background, so the page opens ready.
        final String saved = prefs().getString(PREF_MODEL_ID, null);
        if (saved != null && findModelDir(saved) != null) {
            loadModel(saved);
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -- Scan ---------------------------------------------------------------

    /** Scan the top level of sdcard for model directories (standard
     *  am/final.mdl, or the top-level final.mdl of an official flat pack --
     *  e.g. pt-0.3 ships flat, libvosk reads it natively; the old acceptance
     *  check was too strict and wrongly failed that download). Fast; any
     *  thread may call it. Pure file IO, does not touch org.vosk, works on
     *  API 19 (static is deliberate, so the vosk/models endpoint lists the
     *  table even when the controller is not up (19 device)). */
    public static List<VoskModelInfo> scanModels() {
        Set<String> roots = new LinkedHashSet<>();
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) roots.add(ext.getCanonicalPath());
        } catch (Exception ignore) {
        }
        roots.add("/mnt/internal_sd");
        List<VoskModelInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String root : roots) {
            File[] dirs;
            try {
                dirs = new File(root).listFiles();
            } catch (Exception e) {
                continue;
            }
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                if (!isModelDir(d)) continue;
                String canon;
                try {
                    canon = d.getCanonicalPath();
                } catch (Exception e) {
                    canon = d.getAbsolutePath();
                }
                if (!seen.add(canon)) continue;
                out.add(new VoskModelInfo(d.getName(), canon, guessLang(d.getName()),
                        guessLangEn(d.getName()), dirSize(d)));
            }
        }
        return out;
    }

    /** Whether a directory counts as a usable model: standard (am/final.mdl) or official flat (top-level final.mdl). */
    private static boolean isModelDir(File d) {
        if (new File(new File(d, "am"), "final.mdl").isFile()) return true;
        return new File(d, "final.mdl").isFile();
    }

    /** Parse the language segment from the model id (vosk-model[-small]-xx-..., xx is the language code)
     *  and look it up in the full table. The parse part is shared by both language versions (previously guessLang / guessLangEn each copied one). */
    private static String[] parseLangSub(String id) {
        String lang = null;
        String sub = null;
        String[] parts = id.toLowerCase(java.util.Locale.US).split("-");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("model") || parts[i].equals("small")) {
                // Skip the fixed prefixes "model" / "small" (e.g. vosk-model-small-cn-0.22:
                // the language segment is the cell after "small", not after "model").
                int j = i + 1;
                while (j < parts.length
                        && (parts[j].equals("model") || parts[j].equals("small"))) {
                    j++;
                }
                if (j < parts.length) lang = parts[j];
                if (j + 1 < parts.length && parts[j + 1].matches("[a-z]{2}")) {
                    sub = parts[j + 1];
                }
                break;
            }
        }
        return new String[]{lang, sub};
    }

    private static String guessLang(String id) {
        String[] ls = parseLangSub(id);
        String lang = ls[0];
        String sub = ls[1];
        if (lang == null) return "未知";
        if (lang.equals("en") && "in".equals(sub)) return "印度英文";
        if (lang.equals("ar") && "tn".equals(sub)) return "突尼西亞阿拉伯文";
        String name = LANG_NAMES.get(lang);
        return name != null ? name : "未知 (" + lang + ")";
    }

    private static final java.util.Map<String, String> LANG_NAMES = buildLangNames(false);
    private static final java.util.Map<String, String> LANG_NAMES_EN = buildLangNames(true);

    /** Build the Chinese and English tables at once from the same code table (the two old builders each copied the same pairs). */
    private static java.util.Map<String, String> buildLangNames(boolean english) {
        // Full coverage of the 35 small models on the official Vosk models page (code -> Chinese name / English name).
        String[][] pairs = {
            {"en", "英文", "English"}, {"cn", "中文", "Chinese"}, {"zh", "中文", "Chinese"},
            {"ja", "日文", "Japanese"}, {"ru", "俄文", "Russian"},
            {"fr", "法文", "French"}, {"de", "德文", "German"},
            {"es", "西班牙文", "Spanish"}, {"pt", "葡萄牙文", "Portuguese"},
            {"tr", "土耳其文", "Turkish"},
            {"vn", "越南文", "Vietnamese"}, {"vi", "越南文", "Vietnamese"},
            {"it", "意大利文", "Italian"},
            {"nl", "荷蘭文", "Dutch"}, {"ca", "加泰隆尼亞文", "Catalan"},
            {"ar", "阿拉伯文", "Arabic"},
            {"fa", "波斯文", "Persian"}, {"tl", "菲律賓文", "Filipino"},
            {"uk", "烏克蘭文", "Ukrainian"},
            {"kz", "哈薩克文", "Kazakh"}, {"sv", "瑞典文", "Swedish"},
            {"eo", "世界語", "Esperanto"},
            {"hi", "印地文", "Hindi"}, {"cs", "捷克文", "Czech"},
            {"pl", "波蘭文", "Polish"},
            {"uz", "烏茲別克文", "Uzbek"}, {"ko", "韓文", "Korean"},
            {"br", "布列塔尼文", "Breton"},
            {"gu", "古吉拉特文", "Gujarati"}, {"tg", "塔吉克文", "Tajik"},
            {"te", "泰盧固文", "Telugu"},
            {"ky", "吉爾吉斯文", "Kyrgyz"}, {"ka", "格魯吉亞文", "Georgian"},
        };
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String[] p : pairs) m.put(p[0], english ? p[2] : p[1]);
        return m;
    }

    /** The English version of guessLang (the same parse; shown when the frontend uiLang = en;
     *  catalog / models carry langEn, so old clients that only read lang are unaffected). */
    private static String guessLangEn(String id) {
        String[] ls = parseLangSub(id);
        String lang = ls[0];
        String sub = ls[1];
        if (lang == null) return "unknown";
        if (lang.equals("en") && "in".equals(sub)) return "Indian English";
        if (lang.equals("ar") && "tn".equals(sub)) return "Tunisian Arabic";
        String name = LANG_NAMES_EN.get(lang);
        return name != null ? name : "unknown (" + lang + ")";
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception e) {
            return 0;
        }
        if (files == null) return 0;
        for (File f : files) {
            try {
                total += f.isDirectory() ? dirSize(f) : f.length();
            } catch (Exception ignore) {
            }
        }
        return total;
    }

    private static String findModelDir(String id) {
        for (VoskModelInfo m : scanModels()) {
            if (m.id.equals(id)) return m.path;
        }
        return null;
    }

    // -- Load -----------------------------------------------------------------

    public synchronized State getState() {
        return state;
    }

    public synchronized String getModelId() {
        return modelId;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized boolean isListening() {
        return state == State.LISTENING;
    }

    private void setState(State s, String model, String err) {
        synchronized (this) {
            state = s;
            if (model != null) modelId = model;
            lastError = err;
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"" + s.name().toLowerCase(java.util.Locale.US) + "\""
                        + ",\"model\":" + (modelId == null ? "null"
                                : "\"" + modelId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        + (err == null ? "" : ",\"message\":\""
                                + err.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        + "}");
    }

    /** Load a model in the background (a few seconds). Return null = loading started, otherwise an immediate error.
     *  Progress / result via the vosk_state event + status query. */
    public String loadModel(final String id) {
        final String path = findModelDir(id);
        if (path == null) {
            setState(State.ERROR, null, "model not found on sdcard: " + id);
            return "model not found on sdcard: " + id;
        }
        // OOM circuit breaker: the RK3288 has only 1GB of RAM, and a big pack (e.g. Korean 252MB) gets silently killed by LMK while loading,
        // with nothing in logcat (not a Java crash). Verify there is enough room before loading; if not, return a clear error
        // instead of letting the whole App die suddenly. The old model is already released first (stopLocked + closeModelLocked below);
        // this counts the model body + 150MB of working headroom.
        long needBytes = 150L * 1024 * 1024;
        try {
            for (VoskModelInfo m : scanModels()) {
                if (id.equals(m.id)) {
                    needBytes = m.sizeBytes + 150L * 1024 * 1024;
                    break;
                }
            }
            android.app.ActivityManager am = (android.app.ActivityManager)
                    appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.availMem < needBytes) {
                    String msg = "low memory, refuse to load " + id + " (need "
                            + (needBytes / 1048576) + "MB, avail "
                            + (mi.availMem / 1048576) + "MB)";
                    setState(State.ERROR, null, msg);
                    return msg;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadModel memory check failed", t);
        }
        synchronized (this) {
            if (state == State.LOADING) return "already loading";
            stopLocked();
            closeModelLocked();
            state = State.LOADING;
            loadingId = id;
            lastError = null;
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"loading\",\"model\":\"" + id.replace("\"", "\\\"") + "\"}");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Verify once more before starting work: if a steering change / unload happened, do not waste mmap-ing a few hundred MB
                // (several big packs loading at once, who wins and who loses is luck, see real-device tests).
                synchronized (VoskController.this) {
                    if (state != State.LOADING || !id.equals(loadingId)) return;
                }
                try {
                    Model m = new Model(path);
                    String grammar = buildGrammar(id);
                    synchronized (VoskController.this) {
                        // If it was unloaded / reloaded during the load, discard this one (also check the id: a slow one that finishes later must not beat the new pack).
                        if (state != State.LOADING || !id.equals(loadingId)) {
                            try {
                                m.close();
                            } catch (Throwable ignore) {
                            }
                            return;
                        }
                        model = m;
                        grammarJson = grammar;
                    }
                    android.content.SharedPreferences.Editor ed = prefs().edit();
                    ed.putString(PREF_MODEL_ID, id);
                    String mappedLang = langOfModelId(id);
                    if (mappedLang != null) {
                        // Model is language: when switching models, also record the dialogue language, so status / the frontend do not have to guess.
                        ed.putString(PREF_DIALOGUE_LANG, mappedLang);
                    }
                    ed.apply();
                    Log.i(TAG, "model loaded: " + id
                            + (grammar != null ? " (grammar constrained)" : " (open vocab)"));
                    setState(State.READY, id, null);
                    loadingId = null;
                } catch (Throwable e) {
                    Log.w(TAG, "loadModel failed: " + id, e);
                    setState(State.ERROR, null, "load failed: " + e.getMessage());
                    loadingId = null;
                }
            }
        }, "VoskLoad").start();
        return null;
    }

    /** Model id -> dialogue language (zh/en/es/fr/ja/de/it/pt/ko/ru; return null when unrecognized). Identical, word for word, to the
     *  inline contains chain that buildGrammar used to have; extracted so that the synchronous pairing in vosk/load uses the same set.
     *  Note that "es" itself is too easy to collide (test/best both contain es), so it is only recognized with a boundary
     *  (spanish / español / -es- / an es- start / an -es end / the whole thing being es).
     *  "fr" is equally easy to collide (from contains fr), so it also needs a boundary to be recognized.
     *  "ja" also needs a boundary to be recognized (major/january both contain ja).
     *  The new five languages are the same: de collides easily (model/made both contain de), it collides easily (with/city both contain it),
     *  pt collides easily (empty contains pt), ko/ru both need a boundary; recognize the full name first, then the bounded code. */
    static String langOfModelId(String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(java.util.Locale.US);
        if (lower.contains("cn") || lower.contains("zh") || lower.contains("mandarin")) return "zh";
        if (lower.contains("spanish") || lower.contains("espa")) return "es";
        if (lower.contains("-es-") || lower.startsWith("es-") || lower.endsWith("-es")
                || lower.endsWith("_es") || lower.equals("es")) return "es";
        if (lower.contains("french") || lower.contains("fran")) return "fr";
        if (lower.contains("-fr-") || lower.startsWith("fr-") || lower.endsWith("-fr")
                || lower.endsWith("_fr") || lower.equals("fr")) return "fr";
        if (lower.contains("japanese") || lower.contains("japan")) return "ja";
        if (lower.contains("-ja-") || lower.startsWith("ja-") || lower.endsWith("-ja")
                || lower.endsWith("_ja") || lower.equals("ja")) return "ja";
        if (lower.contains("german") || lower.contains("deutsch")) return "de";
        if (lower.contains("-de-") || lower.startsWith("de-") || lower.endsWith("-de")
                || lower.endsWith("_de") || lower.equals("de")) return "de";
        if (lower.contains("italian") || lower.contains("italiano")) return "it";
        if (lower.contains("-it-") || lower.startsWith("it-") || lower.endsWith("-it")
                || lower.endsWith("_it") || lower.equals("it")) return "it";
        if (lower.contains("portuguese") || lower.contains("portugues")) return "pt";
        if (lower.contains("-pt-") || lower.startsWith("pt-") || lower.endsWith("-pt")
                || lower.endsWith("_pt") || lower.equals("pt")) return "pt";
        if (lower.contains("korean") || lower.contains("korea")) return "ko";
        if (lower.contains("-ko-") || lower.startsWith("ko-") || lower.endsWith("-ko")
                || lower.endsWith("_ko") || lower.equals("ko")) return "ko";
        if (lower.contains("russian")) return "ru";
        if (lower.contains("-ru-") || lower.startsWith("ru-") || lower.endsWith("-ru")
                || lower.endsWith("_ru") || lower.equals("ru")) return "ru";
        if (lower.contains("en")) return "en";
        return null;
    }

    /** Current dialogue language (zh/en/es/fr/ja/de/it/pt/ko/ru): what the last load recorded; when never recorded, infer it from the current model (fall back to zh when there is none).
     *  The language follows the model -- the model key is the language key, so the user does not select it separately. */
    public String getDialogueLang() {
        try {
            String v = prefs().getString(PREF_DIALOGUE_LANG, null);
            if ("zh".equals(v) || "en".equals(v) || "es".equals(v) || "fr".equals(v) || "ja".equals(v)
                    || "de".equals(v) || "it".equals(v) || "pt".equals(v) || "ko".equals(v) || "ru".equals(v)) return v;
            String m = langOfModelId(modelId);
            if (m != null) return m;
        } catch (Throwable ignore) {}
        return "zh";
    }

    /** The stored grammar (vosk/grammar), or null for open vocabulary. Never throws. */
    private String buildGrammar(String id) {
        stripSpaces = false;
        try {
            String g = prefs().getString(PREF_GRAMMAR, "");
            return g == null || g.trim().isEmpty() ? null : g;
        } catch (Throwable t) {
            return null;
        }
    }

    public String getGrammar() {
        return prefs().getString(PREF_GRAMMAR, "");
    }

    /** Stores the grammar. It is used at the next vosk/start. Empty = open vocabulary. */
    public synchronized void setGrammar(String json) {
        String g = json == null ? "" : json.trim();
        prefs().edit().putString(PREF_GRAMMAR, g).apply();
        grammarJson = g.isEmpty() ? null : g;
    }

    /** A new grammar recognizer on the loaded model (WakeController feeds it the PCM of
     *  the Xiaozhi capture). Returns null when no model is loaded. The caller closes it. */
    public synchronized Recognizer newGrammarRecognizer(String grammar) throws java.io.IOException {
        if (model == null) return null;
        Recognizer r = new Recognizer(model, SAMPLE_RATE, grammar);
        try {
            r.setWords(true);
        } catch (Throwable ignore) {
        }
        return r;
    }

    /** Drop the model (do not clear the choice stored in prefs). */
    public synchronized void unload() {
        stopLocked();
        closeModelLocked();
        state = State.IDLE;
        modelId = null;
        loadingId = null;
        lastError = null;
        EventBus.get().publish("vosk_state", "{\"state\":\"idle\",\"model\":null}");
    }

    private void closeModelLocked() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
        if (model != null) {
            try {
                model.close();
            } catch (Throwable ignore) {
            }
            model = null;
        }
        grammarJson = null;
    }

    // -- Listening -----------------------------------------------------------------

    /** In-progress flag while start is running (prevents a double-start while the construction is outside the lock; kept separate from
     *  state so the status API shape does not move; stopLocked clears it, and all reads/writes go through the lock). */
    private boolean starting = false;

    /** Mutual exclusion for heavy native work (Recognizer / Service construction + Model.close must not happen together;
     *  kept separate from the main monitor so it is not held while waiting tens of seconds. Lock order is only main -> audio, one direction, no deadlock.) */
    private final Object audioInitLock = new Object();

    /**
     * Start listening. Return null = started, otherwise an error string (model not loaded / already listening / mic cannot be opened).
     * Results via asr_partial (real time) / asr_result (full sentence, reusing the old pipeline: bubble + semantic pairing + TTS).
     */
    public synchronized String startListening() {
        if (state == State.LISTENING) return null;
        return startListeningLocked();
    }

    /** Internal implementation of start listening (full question grammar). The slow construction (mic check + Recognizer
     *  native + Service + AudioRecord open, tens of seconds when the mic is busy) runs outside the lock, using only
     *  audioInitLock for mutual exclusion, and does not hold the main monitor (see logcat AudioRecord.native_setup 60s + waiters piling up);
     *  only after it lands is the lock taken to check the flag + assign. Pressing again while starting returns "starting, try later". */
    private String startListeningLocked() {
        final Model snapModel;
        final String snapGrammar;
        synchronized (this) {
            if (state == State.LISTENING) return null;
            if (state != State.READY || model == null) {
                return state == State.LOADING ? "model loading, try later" : "load a model first";
            }
            if (starting) return "starting, try later";
            starting = true;
            snapModel = model;
            snapGrammar = grammarJson;
        }
        // Mic pre-check: if the robot does not support 16kHz capture, do not open it (opening it yields only garbage / [unk] anyway).
        // Same parameters as inside SpeechService (MONO / PCM16).
        try {
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                endStarting();
                return "mic does not support 16kHz mono PCM (minBuf=" + minBuf + ")";
            }
            Log.i(TAG, "mic 16kHz check ok, minBuf=" + minBuf);
        } catch (Throwable e) {
            Log.w(TAG, "mic check failed", e);
            endStarting();
            return "mic check failed: " + e.getMessage();
        }
        Recognizer rec = null;
        SpeechService service = null;
        try {
            synchronized (audioInitLock) {
                if (snapGrammar != null) {
                    Recognizer g;
                    try {
                        g = new Recognizer(snapModel, SAMPLE_RATE, snapGrammar);
                    } catch (Throwable e) {
                        Log.w(TAG, "grammar recognizer failed, fallback open vocab", e);
                        g = new Recognizer(snapModel, SAMPLE_RATE);
                    }
                    rec = g;
                } else {
                    rec = new Recognizer(snapModel, SAMPLE_RATE);
                }
                // Word confidence: the final JSON has a result/conf array, which makes the noise gate possible
                // (see onVoskFinal; bottom-noise hallucinations usually score low, real human speech scores high). If the old lib
                // does not have this method, treat it as nothing (fail-open; avgWordConf below returns -1 and lets it pass).
                try {
                    rec.setWords(true);
                } catch (Throwable ignore) {
                }
                // Endpointer tuning removed: the Recognizer always uses the library defaults (it previously followed a persisted preference).
                service = new SpeechService(rec, SAMPLE_RATE);
                service.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    String text = transcript(hypothesis, "partial");
                    if (text != null && !text.isEmpty()) {
                        EventBus.get().publish("asr_partial",
                                "{\"text\":\"" + escape(text) + "\"}");
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    onVoskFinal(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    onVoskFinal(hypothesis);
                }

                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "vosk error", e);
                    synchronized (VoskController.this) {
                        if (state == State.LISTENING) {
                            state = State.READY;
                        }
                    }
                    setState(getState(), null,
                            e == null ? "unknown mic error" : String.valueOf(e.getMessage()));
                }

                @Override
                public void onTimeout() {
                    stopListening();
                }
            });
            }
            // Landed: take the lock, check the flag + assign (if someone stopped / changed state in the middle, clean up after itself; do not assign a zombie).
            synchronized (this) {
                if (!starting || state != State.READY) {
                    starting = false;
                    closeStartParts(service, rec);
                    return "stopped";
                }
                recognizer = rec;
                speechService = service;
                state = State.LISTENING;
                starting = false;
                lastError = null;
            }
        } catch (Throwable e) {
            Log.w(TAG, "startListening failed", e);
            closeStartParts(service, rec);
            endStarting();
            return "mic start failed: " + e.getMessage();
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"listening\",\"model\":\"" + escape(modelId == null ? "" : modelId) + "\"}");
        return null;
    }

    /** Full-sentence result: reuse the old pipeline (bubble + semantic pairing + TTS).
     *  Runs on the SpeechService listener thread (main). Never throws.
     *  Confidence gate fixed at 0.01 -- an average word conf below it is treated as bottom noise and dropped
     *  (not published, not paired; an unknown conf returns -1 and still passes). */
    private void onVoskFinal(String hypothesis) {
        try {
            String text = transcript(hypothesis, "text");
            if (text == null || text.isEmpty()) return;
            double conf = avgWordConf(hypothesis);
            if (conf >= 0 && conf < MIN_FINAL_CONF) {
                Log.i(TAG, "low-conf final dropped (" + conf + "): " + text);
                return;
            }
            // For debugging: log what was recognized + its score (the EventBus only carries it to the frontend; logcat cannot see the content).
            Log.i(TAG, "final: " + text + " conf="
                    + (conf < 0 ? "?" : String.format("%.2f", conf)));
            EventBus.get().publish("asr_result",
                    "{\"text\":\"" + escape(text) + "\"}");
        } catch (Throwable e) {
            Log.w(TAG, "onVoskFinal failed", e);
        }
    }

    /** Average word conf of the final JSON result array; when there is no result array / parsing fails,
     *  return -1 (unknown; the caller still lets it pass). */
    private static double avgWordConf(String json) {
        if (json == null) return -1;
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("result");
            if (arr == null || arr.length() == 0) return -1;
            double sum = 0;
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                double c = w.optDouble("conf", -1);
                if (c >= 0) {
                    sum += c;
                    n++;
                }
            }
            return n == 0 ? -1 : sum / n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Pause and come back during TTS playback (do not release the mic, just do not decode); resume when playback ends.
     *  A no-op when not listening. */
    public synchronized void setPaused(boolean paused) {
        if (speechService != null && state == State.LISTENING) {
            try {
                speechService.setPause(paused);
            } catch (Throwable ignore) {
            }
        }
    }

    /** TTS starts playing: pause decode immediately (same as setPaused(true)). */
    public synchronized void onTtsStarted() {
        setPaused(true);
    }

    /** TTS finished playing: resume decode immediately. */
    public void onTtsFinished() {
        synchronized (VoskController.this) {
            setPaused(false);
        }
    }

    public synchronized String stopListening() {
        stopLocked();
        if (state == State.LISTENING || model != null) {
            state = model != null ? State.READY : State.IDLE;
        }
        // A server-side stop (e.g. yielding the mic to Xiaozhi) must also push the event, or the frontend will not turn the lamp off.
        EventBus.get().publish("vosk_state",
                "{\"state\":\"" + state.name().toLowerCase(java.util.Locale.US) + "\""
                        + ",\"model\":" + (modelId == null ? "null" : "\"" + escape(modelId) + "\"")
                        + "}");
        return null;
    }

    private void stopLocked() {
        starting = false;
        if (speechService != null) {
            try {
                speechService.stop();
            } catch (Throwable ignore) {
            }
            try {
                speechService.shutdown();
            } catch (Throwable ignore) {
            }
            speechService = null;
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
    }

    public synchronized void shutdown() {
        if ("downloading".equals(dlState) || "unzipping".equals(dlState)) dlCancel = true;
        stopLocked();
        closeModelLocked();
        state = State.IDLE;
        loadingId = null;
    }

    /** Clear the starting flag when the start fails / is stopped midway (via the lock; the stopLocked side clears it too). */
    private void endStarting() {
        try {
            synchronized (this) {
                starting = false;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Clean up the half-finished start (stop the service first, otherwise close the recognizer; the same path as the old catch). */
    private static void closeStartParts(SpeechService service, Recognizer rec) {
        if (service != null) {
            try {
                service.shutdown();
            } catch (Throwable ignored) {
            }
        } else if (rec != null) {
            try {
                rec.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** optText + trim. Empty returns null and is not published. Shared by partial and final. */
    private String transcript(String json, String key) {
        String s = optText(json, key);
        if (s != null) {
            s = s.trim();
        }
        if (s == null || s.isEmpty()) return null;
        return s;
    }

    private static String optText(String json, String key) {
        if (json == null) return null;
        try {
            String s = new JSONObject(json).optString(key, "");
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // -- Model download + auto unzip -------------------------------------------------
    // Frontend flow: experiments tab download card -> vosk/catalog lists everything downloadable
    // (already-downloaded ones are not shown) -> vosk/download?model=<id> starts a background
    // thread -> the frontend polls vosk/download_status (or listens for the vosk_download event)
    // to show progress -> after done, auto refresh + load (this side also auto-loads, saving
    // one round trip).

    /** modelId -> {modelId, url}. Returns null when not in the catalog (the upper layer turns it into a 400). */
    public static String[] downloadTarget(String modelId) {
        if (modelId == null) return null;
        for (String[] e : DL_CATALOG) {
            if (e[0].equals(modelId)) return new String[]{e[0], DL_BASE + e[0] + ".zip"};
        }
        return null;
    }

    public static boolean isDownloadable(String modelId) {
        return downloadTarget(modelId) != null;
    }

    /** For the experiments tab download card: everything downloadable + the downloaded flag + the dialogue flag.
     *  dialogue = has a recognized matcher, can converse (ten languages; consistent with the SemanticCenter rules,
     *  languages without a matcher are always false). Pure file IO + constant checks; any thread may call it. */
    public static String catalogJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"catalog\":[");
        boolean first = true;
        for (String[] e : DL_CATALOG) {
            if (!first) sb.append(',');
            first = false;
            String id = e[0];
            String ml = langOfModelId(id);
            sb.append("{\"id\":\"").append(escape(id)).append('"');
            sb.append(",\"lang\":\"").append(escape(guessLang(id))).append('"');
            sb.append(",\"langEn\":\"").append(escape(guessLangEn(id))).append('"');
            sb.append(",\"sizeMb\":").append(e[1]);
            sb.append(",\"downloaded\":").append(findModelDir(id) != null);
            sb.append(",\"dialogue\":").append("zh".equals(ml) || "en".equals(ml) || "es".equals(ml) || "fr".equals(ml) || "ja".equals(ml)
                    || "de".equals(ml) || "it".equals(ml) || "pt".equals(ml) || "ko".equals(ml) || "ru".equals(ml));
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    /** Download root = the first writable root from scanModels (consistent with the scan; once unzipped, the model directory is visible at the top level). */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                // If it exists but writing is uncertain, still try (canWrite mis-reports on old devices); if the write fails, it will error again downstream.
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** Start download + unzip. modelId must be in the catalog (see downloadTarget).
     *  Return null = started, otherwise an immediate error string. */
    public synchronized String startDownload(String modelId) {
        String[] target = downloadTarget(modelId);
        if (target == null) return "unknown downloadable model: " + modelId;
        if ("downloading".equals(dlState) || "unzipping".equals(dlState)) {
            return "already downloading (" + dlModel + " " + dlProgress + "%)";
        }
        // Single channel: if the action pack is downloading, this cannot start (see DownloadGate, the resource download card on the experiments tab).
        if (!DownloadGate.tryAcquire("vosk", modelId)) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        final String url = target[1];
        // If it already exists, do not download it again (scan and loadModel share the same check: see isModelDir).
        // Set the state to done as well -- a leftover error message from a previous failure does not need to stay.
        if (findModelDir(modelId) != null) {
            dlModel = modelId;
            dlLang = guessLang(modelId);
            dlBytes = 0;
            dlTotal = 0;
            setDl("done", 100, null);
            publishDl();
            return "already exists: " + modelId;
        }
        dlState = "downloading";
        dlLang = guessLang(modelId);
        dlModel = modelId;
        dlProgress = 0;
        dlBytes = 0;
        dlTotal = -1;
        dlError = null;
        dlCancel = false;
        publishDl();
        dlThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload(url, modelId);
            }
        }, "VoskDownload");
        dlThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(dlState) && !"unzipping".equals(dlState)) {
            return "not downloading";
        }
        dlCancel = true;
        return null;
    }

    /** For the download_status endpoint (pass-through JSON, not wrapped in another ApiResponse layer). */
    public String downloadStatusJson() {
        String st;
        String lang;
        String mid;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = dlState;
            lang = dlLang;
            mid = dlModel;
            prog = dlProgress;
            bytes = dlBytes;
            total = dlTotal;
            err = dlError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"lang\":").append(lang == null ? "null" : "\"" + escape(lang) + "\"");
        sb.append(",\"model\":").append(mid == null ? "null" : "\"" + escape(mid) + "\"");
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishDl() {
        String st;
        String lang;
        String mid;
        int prog;
        String err;
        synchronized (this) {
            st = dlState;
            lang = dlLang;
            mid = dlModel;
            prog = dlProgress;
            err = dlError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"lang\":").append(lang == null ? "null" : "\"" + escape(lang) + "\"");
        sb.append(",\"model\":").append(mid == null ? "null" : "\"" + escape(mid) + "\"");
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("vosk_download", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setDl(String st, int prog, String err) {
        synchronized (this) {
            dlState = st;
            dlProgress = prog;
            dlError = err;
        }
        publishDl();
    }

    private void runDownload(String urlStr, String modelId) {
        File root = downloadRoot();
        File zipTmp = new File(root, modelId + ".zip.tmp");
        File zipDone = new File(root, modelId + ".zip");
        java.util.Set<String> beforeDl = new java.util.HashSet<>();
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            // Snapshot: top-level directories added from now on were all dropped by this unzip; on failure / cancel, clear them,
            // so that no retry leaves another piece of garbage on sdcard.
            beforeDl = listDirNames(root);
            // --- download ---
            java.net.HttpURLConnection conn = null;
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                java.net.URL url = new java.net.URL(urlStr);
                conn = (java.net.HttpURLConnection) url.openConnection();
                try {
                    XiaozhiTrustAllSsl.applyTrustAll(conn);
                } catch (Throwable ignore) {
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "OpenAlpha2");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                // Use the int version of getContentLength (present since API 1): the model zip is ~50MB, far below 2GB;
                // getContentLengthLong needs API 24+, and this APK must run on API 21/22 (see manifest);
                // calling it directly throws NoSuchMethodError (the same class as the vosk circuit breaker that keeps API 19).
                long total = -1;
                try {
                    total = conn.getContentLength();
                } catch (Throwable ignore) {
                }
                synchronized (this) {
                    dlTotal = total;
                }
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(zipTmp);
                byte[] buf = new byte[32768];
                long got = 0;
                int n;
                long lastPub = 0;
                while ((n = in.read(buf)) != -1) {
                    synchronized (this) {
                        if (dlCancel) throw new java.io.IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (this) {
                        dlBytes = got;
                        if (total > 0) dlProgress = (int) Math.min(100, got * 100 / total);
                    }
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastPub > 500) {
                        lastPub = now;
                        publishDl();
                    }
                }
                try {
                    out.flush();
                } catch (Throwable ignore) {
                }
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignore) {
                }
                try {
                    if (out != null) out.close();
                } catch (Throwable ignore) {
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
            synchronized (this) {
                if (dlCancel) throw new java.io.IOException("cancelled");
            }
            // --- unzip (unzip it ourselves, no dependence on the system unzip binary) ---
            setDl("unzipping", 100, null);
            try {
                zipTmp.renameTo(zipDone);
            } catch (Throwable ignore) {
            }
            File src = zipDone.exists() ? zipDone : zipTmp;
            unzipToRoot(src, root);
            try {
                src.delete();
            } catch (Throwable ignore) {
            }
            try {
                if (zipTmp.exists()) zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // Acceptance: only count it as a success if a model directory exists after unzipping (standard
            // am/final.mdl or the top-level final.mdl of an official flat pack, see isModelDir). If it is not accepted, clear the
            // zip together with the directories added this time -- old model directories (already present before the snapshot) are never touched.
            if (findModelDir(modelId) == null) {
                cleanupNewDirs(root, beforeDl);
                try {
                    src.delete();
                } catch (Throwable ignore) {
                }
                throw new java.io.IOException("unzip ok but model not found: " + modelId
                        + " (need am/final.mdl or top-level final.mdl)");
            }
            setDl("done", 100, null);
            DownloadGate.release("vosk");
            Log.i(TAG, "model downloaded+unzipped: " + modelId);
            // Auto-load as a side step (saves the frontend one round trip; a failure is not a download failure, the state stays done).
            // If the user is listening, yield the way -- loadModel stops the mic and cuts the session, so wait for the user to switch over themselves.
            boolean listeningNow;
            synchronized (this) {
                listeningNow = state == State.LISTENING;
            }
            if (listeningNow) {
                Log.i(TAG, "skip auto-load after download (user listening): " + modelId);
            } else {
                try {
                    String err = loadModel(modelId);
                    if (err != null) Log.w(TAG, "auto-load after download failed: " + err);
                } catch (Throwable e) {
                    Log.w(TAG, "auto-load after download threw", e);
                }
            }
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = dlCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "download failed: " + modelId + ": " + msg, e);
            try {
                zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // Clean up on both failure / cancel: delete the directories added by this unzip (the
            // half pack left by a corrupted zip / a mid-way cancel); directories already present before the snapshot are never touched.
            cleanupNewDirs(root, beforeDl);
            DownloadGate.release("vosk");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setDl("cancelled", dlProgress, "cancelled");
            } else {
                setDl("error", dlProgress, msg);
            }
        }
    }

    /** List the top-level directories under root (a set of canonical paths, for the pre-download snapshot). */
    private static java.util.Set<String> listDirNames(File root) {
        java.util.Set<String> s = new java.util.HashSet<>();
        File[] fs;
        try {
            fs = root.listFiles();
        } catch (Throwable t) {
            return s;
        }
        if (fs == null) return s;
        for (File f : fs) {
            try {
                if (f.isDirectory()) s.add(f.getCanonicalPath());
            } catch (Throwable ignore) {
            }
        }
        return s;
    }

    /** Delete top-level directories that are not in the snapshot (the ones dropped by this unzip; the user's old files are never touched). */
    private static void cleanupNewDirs(File root, java.util.Set<String> before) {
        File[] fs;
        try {
            fs = root.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            try {
                if (f.isDirectory() && !before.contains(f.getCanonicalPath())) {
                    deleteRecursive(f);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Throwable t) {
            fs = null;
        }
        if (fs != null) {
            for (File c : fs) {
                try {
                    if (c.isDirectory()) deleteRecursive(c);
                    else c.delete();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    /** Zip-slip safe: an entry must unpack inside root, otherwise it is skipped. */
    private void unzipToRoot(File zip, File root) throws java.io.IOException {
        String rootCanon = root.getCanonicalPath();
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zis.getNextEntry()) != null) {
                synchronized (this) {
                    if (dlCancel) throw new java.io.IOException("cancelled");
                }
                String name = e.getName();
                // Block absolute paths / .. escapes (official packs never have them, but do not trust an outside zip).
                File f = new File(root, name);
                String canon = f.getCanonicalPath();
                if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                    try {
                        zis.closeEntry();
                    } catch (Throwable ignore) {
                    }
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.OutputStream o = null;
                    try {
                        o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            synchronized (this) {
                                if (dlCancel) throw new java.io.IOException("cancelled");
                            }
                            o.write(buf, 0, n);
                        }
                        o.flush();
                    } finally {
                        try {
                            if (o != null) o.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
                try {
                    zis.closeEntry();
                } catch (Throwable ignore) {
                }
            }
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Throwable ignore) {
            }
        }
    }
}



