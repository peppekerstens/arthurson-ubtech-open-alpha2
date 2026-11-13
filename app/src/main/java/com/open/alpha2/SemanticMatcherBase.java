package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared foundation of the semantic-pairing engine for Chinese/English/Spanish/
 * French/Japanese/German/Italian/Portuguese/Korean/Russian. SemanticMatcherZh
 * (Chinese), SemanticMatcherEn (English), SemanticMatcherEs (Spanish),
 * SemanticMatcherFr (French), SemanticMatcherJa (Japanese), SemanticMatcherDe
 * (German), SemanticMatcherIt (Italian), SemanticMatcherPt (Portuguese),
 * SemanticMatcherKo (Korean), and SemanticMatcherRu (Russian) differ only in TAG,
 * asset filename, and the fallback question/action groups. The load/compare/
 * category-random layers are completely identical, so 2026-09 extracted this
 * shared base class to avoid two word-for-word copies that must be changed in
 * sync. A subclass only needs to provide three things in its constructor: the log
 * tag, the asset path of the question json, and the "cannot understand" fallback
 * group (question lines + matching action ids, equal length).
 *
 * MatchResult/Entry were both moved here as shared types. Before, the Chinese and
 * English each had a nested MatchResult with the same structure but belonging to
 * different classes, and SemanticCenter had to convert manually with toZhResult().
 * Now both subclasses share the same SemanticMatcherBase.MatchResult, so callers
 * no longer convert.
 *
 * For the background on the data source / category-random mechanism, see the
 * SemanticMatcherZh class javadoc; not repeated here.
 *
 *  Naming note: these classes (this base +
 * SemanticMatcherZh/En/Es/Fr/Ja/De/It/Pt/Ko/Ru) were called
 * IflytekSemanticMatcher(Base/En) before 2026-09. The name was purely historical
 * (the question data was first restored by decompiling the iFlytek APK) and has
 * nothing to do with the Nuance/iFlytek binder TTS/ASR engines on the same robot
 * body, which are permanently retired. Only the name was misleading (this feature
 * is pure local JSON pairing and goes through no external engine). 2026-09
 * renamed them to SemanticMatcher* to remove that misleading impression.
 */
public abstract class SemanticMatcherBase {
    private static final String ASSET_PATH_CATEGORIES = "semantic/action_category_pools.json";
    private static final String RANDOM_CATEGORY_PREFIX = "__RANDOM_CATEGORY__";

    /** "Cannot understand" fallback action group shared by all ten languages (5
     *  actions verified to have no sound effect; each subclass previously copied
     *  the same one on its own). */
    protected static final String[] DEFAULT_FALLBACK_ACTION_IDS = {
            "1464835936013", // Shake head
            "1464835936026", // Thinking
            "1464835936043", // Wink
            "1509000313549", // Cute
            "1464835936087", // Nod
    };

    /** Pairing result. type is aligned with the existing asr_result event format
     *  in MainActivity. answer/actionId may be null (for example the CHAT type has
     *  no actionId, and some FUNCTION have no answer). matched = a real hit on the
     *  question library / false = a fallback random answer (when the primary
     *  matcher cannot understand, use the primary's own fallback).
     *  matchLayer = which layer hit (0 exact / 1 contains / 2 reverse-contains /
     *  3 fuzzy / 4 fallback). */
    public static final class MatchResult {
        public final String question;   // the original question form that hit (for debug)
        public final String type;       // "ACTION" | "FUNCTION" | "CHAT"
        public final String operation;  // YouChat operation code; null for the CHAT type
        public final String slot;       // direction/mood slot; null when absent
        public final String answer;     // the reply line chosen at random; null when there is no answer line
        public final String actionId;   // the action id from the 202-action list; null when there is no action
        public final boolean matched;   // a real hit / fallback
        public final int matchLayer;    // 0 exact / 1 contains / 2 reverse-contains / 3 fuzzy / 4 fallback

        public MatchResult(String question, String type, String operation, String slot,
                    String answer, String actionId, boolean matched, int matchLayer) {
            this.question = question;
            this.type = type;
            this.operation = operation;
            this.slot = slot;
            this.answer = answer;
            this.actionId = actionId;
            this.matched = matched;
            this.matchLayer = matchLayer;
        }
    }

    /** Internal record, one per line of the question json. normQ is the
     *  normalized question form used for matching (see normalizeForMatch;
     *  computed once at load time so match() does not recompute it every time). */
    private static final class Entry {
        String q;
        String normQ;
        String[] answers;
        String type;
        String op;
        String slot;
        String actionId;
    }

    private final String tag;
    private final String assetPath;
    private final String[] fallbackQuestions;
    private final String[] fallbackActionIds;

    protected final Context appContext;
    protected final Random random = new Random();

    /** Lazily loaded on the first match() call, cached afterwards. Loading it all
     *  into memory is only a few tens of KB in parsed form, easily affordable;
     *  there is no need to re-read the assets on every match. Consistent with the
     *  cache style of loadXiaozhiActions(). */
    private List<Entry> cache;

    /** Lazily loaded cache of action_category_pools.json: category key (for
     *  example "DANCE_KIDS") -&gt; all action ids of that category. Same
     *  lazy-load style as cache; read only when __RANDOM_CATEGORY__ hits, so it
     *  does not slow down the normal match() flow. */
    private java.util.Map<String, List<String>> categoryPoolsCache;

    /** @param tag the subclass-specific Log tag
     *  @param assetPath the assets path of the question json (for example
     *         "semantic/semantic_zh.json")
     *  @param fallbackQuestions candidate reply lines for "cannot understand"
     *  @param fallbackActionIds the action id for each fallbackQuestions line
     *         (lengths must be equal, indices correspond one to one) */
    protected SemanticMatcherBase(Context context, String tag, String assetPath,
            String[] fallbackQuestions, String[] fallbackActionIds) {
        this.appContext = context.getApplicationContext();
        this.tag = tag;
        this.assetPath = assetPath;
        this.fallbackQuestions = fallbackQuestions;
        this.fallbackActionIds = fallbackActionIds;
    }

    /** Read in and parse the question records from assets. On read/parse failure,
     *  return an empty list (does not throw), following the same "do not crash,
     *  log once" philosophy as loadXiaozhiActions().
     *
     *  Supports two formats (from 2026-09 v2 on):
     *   v1 legacy - top-level JSONArray, each object is one question form:
     *     [{"q":"跳舞","a":[...],"type":"ACTION","op":"DANCE",...}, ...]
     *   v2 grouped - top-level JSONObject, intents array, each intent carries
     *     multiple question forms (the same intent shares the same
     *     a/type/op/slot/actionId, to avoid repetition):
     *     {"version":2,"lang":"zh","intents":[
     *       {"id":"action.dance","type":"ACTION","op":"DANCE",
     *        "actionId":"...","a":[...],"qs":["跳舞","跳個舞",...]}, ...]}
     *  The v2 question key accepts "qs" (array) / "q" (array or string) /
     *  "patterns" (array, an alias for when multiple languages share one file in
     *  the future). If none of the three is present, skip that intent. v1 files
     *  need no change; new languages always use v2. */
    private synchronized List<Entry> load() {
        if (cache != null) return cache;
        List<Entry> result = new ArrayList<>();
        try {
            String raw = readAssetString(assetPath).trim();
            if (raw.startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Entry e = parseEntry(o, o.optString("q"));
                    if (e != null) result.add(e);
                }
            } else {
                JSONObject root = new JSONObject(raw);
                JSONArray intents = root.optJSONArray("intents");
                if (intents == null) intents = new JSONArray();
                for (int i = 0; i < intents.length(); i++) {
                    JSONObject o = intents.getJSONObject(i);
                    for (String q : parseQuestions(o)) {
                        Entry e = parseEntry(o, q);
                        if (e != null) result.add(e);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(tag, "load: failed to load assets/" + assetPath + ": " + e);
        }
        cache = result;
        return result;
    }

    /** Extract all question forms from a v2 intent object: the "qs" array takes
     *  priority, then "q" (array or string), then the "patterns" array. When none
     *  is present or all are empty, return an empty list (the caller skips it). */
    private static List<String> parseQuestions(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray qs = o.optJSONArray("qs");
        if (qs == null) qs = o.optJSONArray("patterns");
        if (qs != null) {
            for (int i = 0; i < qs.length(); i++) {
                String q = qs.optString(i, null);
                if (q != null && !q.isEmpty()) out.add(q);
            }
            return out;
        }
        JSONArray qArr = o.optJSONArray("q");
        if (qArr != null) {
            for (int i = 0; i < qArr.length(); i++) {
                String q = qArr.optString(i, null);
                if (q != null && !q.isEmpty()) out.add(q);
            }
            return out;
        }
        String single = o.optString("q", null);
        if (single != null && !single.isEmpty()) out.add(single);
        return out;
    }

    /** Build an Entry from one question object plus one question string. Return
     *  null (skip) when q is empty. */
    private static Entry parseEntry(JSONObject o, String q) {
        if (q == null || q.isEmpty()) return null;
        Entry e = new Entry();
        e.q = q;
        e.normQ = normalizeForMatch(q);
        e.type = o.optString("type");
        e.op = o.has("op") ? o.optString("op") : null;
        e.slot = o.has("slot") ? o.optString("slot") : null;
        e.actionId = o.has("actionId") ? o.optString("actionId") : null;
        JSONArray aArr = o.optJSONArray("a");
        if (aArr != null && aArr.length() > 0) {
            e.answers = new String[aArr.length()];
            for (int j = 0; j < aArr.length(); j++) {
                e.answers[j] = aArr.optString(j, null);
            }
        } else {
            e.answers = null;
        }
        return e;
    }

    /** Take a line of text recognized by ASR, check it against the question
     *  library, and find the closest one. A "strict to loose" four-layer approach;
     *  use the layer that hits as soon as one hits:
     *   1. Exact equality (after normalization)
     *   2. The hit question form is fully contained in the input (the input mixes
     *      in other characters, for example "阿爾法你好嗎" contains the question
     *      form "你好嗎") - pick the longest question form among them, to reduce a
     *      short question form falsely matching inside a long sentence
     *   3. The input is fully contained in the hit question form (ASR dropped the
     *      tail, for example input "你好", question form "你好嗎") - again pick the
     *      longest question form
     *   4. Fuzzy matching (added 2026-09, for ASR homophone typos / one character
     *      added or dropped, for example "跳武吧" against "跳舞吧") - OSA edit
     *      distance, requiring d*2 &lt; maxlen (strictly more than half similar);
     *      too short (maxlen&lt;3) is not fuzzy, to avoid false hits (for example
     *      "跳樓" will not hit "跳舞"). When distances are equal, take the first in
     *      file order (ACTION intents come first, see semantic_zh.json).
     *  When all four layers miss, no longer return null - randomly pick one
     *  fallbackQuestions line as the "cannot understand" response, so whatever the
     *  user says still gets a reaction even when it is not in the question library
     *  (no silence). Blank input (text is null or only whitespace) really has
     *  nothing to answer, so still return null.
     *
     *  The input text is first normalized to Traditional via
     *  SimplifiedToTraditional.toTraditional(), then passed through
     *  normalizeForMatch() to strip punctuation/whitespace/Latin case (so that
     *  "TFBOYS!", "tfboys", "你好嗎?" from ASR/Vosk/typed input do not fail to
     *  match because of punctuation or case) before matching. The two conversions
     *  only affect the q "used for matching"; they do not change
     *  MatchResult.question (still the original e.q). answer/actionId always come
     *  from the database itself. */
    public MatchResult match(String text) {
        List<Entry> entries = load();
        String raw = text == null ? "" : SimplifiedToTraditional.toTraditional(text.trim());
        if (raw.isEmpty()) return null;
        if (entries.isEmpty()) return fallback();
        String q = normalizeForMatch(raw);
        if (q.isEmpty()) return fallback(); // all punctuation/whitespace - treat as cannot understand, do not stay silent

        // 1) exact equality
        for (Entry e : entries) {
            if (q.equals(e.normQ)) return toResult(e, 0);
        }

        // 2) the question form is fully contained in the input (pick the longest, to reduce short-form false hits)
        Entry best = null;
        for (Entry e : entries) {
            if (!e.normQ.isEmpty() && q.contains(e.normQ)) {
                if (best == null || e.normQ.length() > best.normQ.length()) best = e;
            }
        }
        if (best != null) return toResult(best, 1);

        // 3) the input is fully contained in the question form (ASR dropped characters / shortened, pick the longest question form)
        for (Entry e : entries) {
            if (!e.normQ.isEmpty() && e.normQ.contains(q)) {
                if (best == null || e.normQ.length() > best.normQ.length()) best = e;
            }
        }
        if (best != null) return toResult(best, 2);

        // 4) fuzzy matching: ASR homophone typos / one character added or dropped / adjacent transposition. The smallest distance wins.
        best = null;
        int bestD = Integer.MAX_VALUE;
        for (Entry e : entries) {
            String nq = e.normQ;
            if (nq == null || nq.isEmpty()) continue;
            int ml = Math.max(q.length(), nq.length());
            if (ml < 3) continue;
            if (Math.abs(q.length() - nq.length()) > (ml - 1) / 2) continue;
            int d = osaDistance(q, nq);
            if (d * 2 < ml && d < bestD) {
                best = e;
                bestD = d;
            }
        }
        if (best != null) return toResult(best, 3);

        return fallback();
    }

    /** Normalization for matching: trim leading/trailing whitespace, lowercase
     *  Latin, strip Latin accents (NFD drops Mn, for example í→i, ñ→n - Vosk
     *  Spanish output and typing often do not carry accents, so stripping both
     *  sides is what makes them line up; the Chinese/English files themselves have
     *  no accented characters, so this has zero effect on them), and remove all
     *  punctuation/symbols/whitespace (Unicode P/S classes + whitespace, including
     *  full-width ？。！，、；：「」『』《》…—). Chinese/English digits are not
     *  affected (TFBOYS/OK/0.01公分 still recognized). null returns "". */
    static String normalizeForMatch(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        // NFD strips accents (e + ́), the Mn class is dropped - only affects Latin letters, CJK passes through.
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}", "");
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            int type = Character.getType(c);
            if (type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL
                    || type == Character.SURROGATE
                    || type == Character.CONTROL
                    || type == Character.FORMAT) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Restricted OSA edit distance (an adjacent transposition counts as 1, the
     *  other insert/delete/modify each count as 1). The question forms are at
     *  most a dozen or so characters, so a full DP is easily affordable (only
     *  called when the first three layers miss). */
    static int osaDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        int[] prev2 = null;
        int[] prev = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            int[] cur = new int[lb + 1];
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(prev[j - 1] + cost,
                        Math.min(cur[j - 1] + 1, prev[j] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
            }
            prev2 = prev;
            prev = cur;
        }
        return prev[lb];
    }

    /** Randomly pick one line from fallbackQuestions/fallbackActionIds and wrap it
     *  into a MatchResult. type uses "CHAT" (a pure reply, belonging to no
     *  operation); operation/slot are null. An empty pool (a skeleton language
     *  with no content filled in) returns null - the caller treats it as silence,
     *  no crash (otherwise random.nextInt(0) immediately throws
     *  IllegalArgumentException). */
    private MatchResult fallback() {
        if (fallbackQuestions.length == 0 || fallbackActionIds.length == 0) return null;
        int i = random.nextInt(fallbackQuestions.length);
        return new MatchResult(fallbackQuestions[i], "CHAT", null, null,
                fallbackQuestions[i], fallbackActionIds[i], false, 4);
    }

    private MatchResult toResult(Entry e, int layer) {
        String answer = null;
        if (e.answers != null && e.answers.length > 0) {
            answer = e.answers[random.nextInt(e.answers.length)];
        }
        String actionId = e.actionId;
        if ("__RANDOM__".equals(actionId)) {
            // This kind of operation has no corresponding fixed action in the
            // question library; it follows the user's decision: randomly pick one
            // action from the 202-action list. The actual random logic is left to
            // the caller (MainActivity already has loadXiaozhiActions(); this class
            // does not read the 202-action list a second time). Here we return the
            // "__RANDOM__" marker for the caller to recognize.
        }
        return new MatchResult(e.q, e.type, e.op, e.slot, answer, actionId, true, layer);
    }

    /** For debug/metrics: how many records have been loaded. */
    public int size() {
        return load().size();
    }

    /** For the Vosk restricted grammar: the original text of all question forms
     *  (deduplicated, empties removed). */
    public synchronized java.util.List<String> questions() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Entry e : load()) {
            if (e.q != null && !e.q.isEmpty()) set.add(e.q);
        }
        return new java.util.ArrayList<>(set);
    }

    /** Read in from assets/semantic/action_category_pools.json the cross-reference
     *  of 17 categories -&gt; action id pools (Chinese/English share the same one).
     *  On read/parse failure, return an empty map (does not throw), following the
     *  same "do not crash, log once" philosophy as load(). */
    private synchronized java.util.Map<String, List<String>> loadCategoryPools() {
        if (categoryPoolsCache != null) return categoryPoolsCache;
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        try {
            JSONObject root = new JSONObject(readAssetString(ASSET_PATH_CATEGORIES));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray idsArr = root.getJSONArray(key);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < idsArr.length(); i++) {
                    ids.add(idsArr.getString(i));
                }
                result.put(key, ids);
            }
        } catch (Exception e) {
            Log.w(tag, "loadCategoryPools: failed to load assets/" + ASSET_PATH_CATEGORIES + ": " + e);
        }
        categoryPoolsCache = result;
        return result;
    }

    /** Asset reading shared by the two load methods (via IOUtil, no longer each
     *  writing its own 4k loop). */
    private String readAssetString(String path) throws java.io.IOException {
        InputStream in = null;
        try {
            in = appContext.getAssets().open(path);
            return IOUtil.readFully(in);
        } finally {
            if (in != null) {
                try { in.close(); } catch (java.io.IOException ignored) {}
            }
        }
    }

    /** If actionId is in the "__RANDOM_CATEGORY__&lt;key&gt;" format, parse out
     *  &lt;key&gt; and randomly pick one from the action id pool of the
     *  corresponding category, returning the real action id. If it is not in this
     *  format, return it as is. If the corresponding category cannot be found, or
     *  the category is an empty pool, return null (the caller must fall back on
     *  its own, for example to the resolveRandomActionId() random action pool).
     *
     *  The caller (SemanticCenter.handleSemanticMatch()) should use this method,
     *  after getting the MatchResult and before actually calling
     *  robot.action_PlayActionName(), to resolve actionId into a real playable id.
     *  This is the same "defer the choice until execution time" design as the
     *  "__RANDOM__" marker, but it is category-limited random, not picking any of
     *  all 202. */
    public String resolveCategoryRandomActionId(String actionId) {
        if (actionId == null || !actionId.startsWith(RANDOM_CATEGORY_PREFIX)) {
            return actionId;
        }
        String key = actionId.substring(RANDOM_CATEGORY_PREFIX.length());
        List<String> pool = loadCategoryPools().get(key);
        if (pool == null || pool.isEmpty()) {
            Log.w(tag, "resolveCategoryRandomActionId: empty/missing pool for category " + key);
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}


