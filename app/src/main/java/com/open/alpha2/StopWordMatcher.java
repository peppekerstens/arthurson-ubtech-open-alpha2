package com.open.alpha2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "stop" while the robot speaks (alpha2-brain#21). Pure Java, no Android and no
 * org.json: scripts/test-apivalidator.py tests it with plain javac.
 *
 * The WakeController feeds a second Vosk recognizer with the grammar
 * ["stop", "[unk]"] while the robot speaks. The robot hears its own voice (echo).
 * Thus a match needs the stop phrase in the result and a word confidence of at
 * least the limit. A partial result has no confidence: it only tells the caller to
 * get the final result now (Recognizer.getFinalResult()), and not to wait for
 * silence. During an answer there is no silence.
 */
public final class StopWordMatcher {
    private static final Pattern PARTIAL = Pattern.compile("\"partial\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern WORD_OBJ = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern CONF = Pattern.compile("\"conf\"\\s*:\\s*([-+0-9.eE]+)");
    private static final Pattern WORD = Pattern.compile("\"word\"\\s*:\\s*\"([^\"]*)\"");
    /** One word of an answer sentence: a run of letter and digit characters. */
    private static final Pattern WORD_TEXT = Pattern.compile("[\\p{L}\\p{N}]+");

    private StopWordMatcher() {}

    /** Splits a phrase into lower-case words. "[unk]" stays a word. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.trim().toLowerCase(Locale.US).split("\\s+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** true when the word list holds the phrase as whole, adjacent words. */
    static boolean containsPhrase(List<String> said, List<String> phrase) {
        if (phrase.isEmpty() || said.size() < phrase.size()) return false;
        for (int i = 0; i + phrase.size() <= said.size(); i++) {
            if (said.subList(i, i + phrase.size()).equals(phrase)) return true;
        }
        return false;
    }

    /** true when a partial result (JSON of getPartialResult()) holds a stop phrase. */
    public static boolean partialHasStop(String partialJson, Collection<String> phrases) {
        if (partialJson == null) return false;
        Matcher m = PARTIAL.matcher(partialJson);
        if (!m.find()) return false;
        List<String> said = words(m.group(1));
        for (String p : phrases) {
            if (containsPhrase(said, words(p))) return true;
        }
        return false;
    }

    /**
     * The best confidence of a stop phrase in a final result (JSON of getResult() or
     * getFinalResult(), with setWords(true)). For a phrase of more words, the lowest
     * word confidence counts. Returns -1 when no stop phrase is in the result.
     * Returns 2 when the result has the phrase but no word confidence: the caller then
     * accepts it only when the full text is the phrase (see {@link #accept}).
     */
    public static double stopConfidence(String resultJson, Collection<String> phrases) {
        if (resultJson == null) return -1;
        List<String> ws = new ArrayList<>();
        List<Double> cs = new ArrayList<>();
        Matcher o = WORD_OBJ.matcher(resultJson);
        while (o.find()) {
            String obj = o.group();
            Matcher w = WORD.matcher(obj);
            if (!w.find()) continue;
            Matcher c = CONF.matcher(obj);
            double conf = -1;
            if (c.find()) {
                try {
                    conf = Double.parseDouble(c.group(1));
                } catch (NumberFormatException e) {
                    conf = -1;
                }
            }
            ws.add(w.group(1).toLowerCase(Locale.US));
            cs.add(conf);
        }
        if (ws.isEmpty()) {
            // No word list: only the text.
            Matcher t = TEXT.matcher(resultJson);
            if (!t.find()) return -1;
            List<String> said = words(t.group(1));
            for (String p : phrases) {
                if (containsPhrase(said, words(p))) return 2;
            }
            return -1;
        }
        double best = -1;
        for (String p : phrases) {
            List<String> ph = words(p);
            if (ph.isEmpty() || ws.size() < ph.size()) continue;
            for (int i = 0; i + ph.size() <= ws.size(); i++) {
                if (!ws.subList(i, i + ph.size()).equals(ph)) continue;
                double min = Double.MAX_VALUE;
                for (int k = i; k < i + ph.size(); k++) min = Math.min(min, cs.get(k));
                if (min > best) best = min;
            }
        }
        return best;
    }

    /**
     * The start index of each stop word in an answer sentence, as a whole word only
     * (alpha2-brain#52). A one-word phrase matches a text word equal to it, or equal
     * to it plus "s" ("stops", because the laptop test of 2026-10-31 found that "the
     * bus stops" sounds like stop). A phrase of more words matches only as whole
     * adjacent words. "stopped", "stopping", "nonstop" and "stopwatch" do not match.
     * Returns an empty list for null or empty text. The index is a character offset
     * in the original sentence.
     */
    static List<Integer> stopWordOffsets(String sentence, Collection<String> phrases) {
        List<Integer> out = new ArrayList<>();
        if (sentence == null || sentence.isEmpty()) return out;
        // Whole words of the text, with their start index in the original string.
        List<String> said = new ArrayList<>();
        List<Integer> start = new ArrayList<>();
        Matcher t = WORD_TEXT.matcher(sentence);
        while (t.find()) {
            said.add(t.group().toLowerCase(Locale.US));
            start.add(t.start());
        }
        for (String p : phrases) {
            List<String> ph = words(p);
            if (ph.isEmpty()) continue;
            if (ph.size() == 1) {
                String first = ph.get(0);
                for (int i = 0; i < said.size(); i++) {
                    String w = said.get(i);
                    if (w.equals(first) || w.equals(first + "s")) out.add(start.get(i));
                }
            } else {
                // More words: whole adjacent words only. Same rule as containsPhrase.
                for (int i = 0; i + ph.size() <= said.size(); i++) {
                    if (said.subList(i, i + ph.size()).equals(ph)) out.add(start.get(i));
                }
            }
        }
        return out;
    }

    /**
     * true when an answer sentence (text of the tts sentence_start message) holds a
     * stop phrase as a whole word ("stop", "stops", "Stop."). Then the robot says the
     * word itself, and the microphone hears it (laptop test 2026-10-31: all false
     * stops in 55 s of Piper speech were words like "the bus stops"). alpha2-brain#52:
     * a word that only starts with "stop" ("stopped") is no match.
     */
    public static boolean textHasStopWord(String sentence, Collection<String> phrases) {
        return !stopWordOffsets(sentence, phrases).isEmpty();
    }

    /** The full text of a final result, lower case, or "". */
    static String text(String resultJson) {
        if (resultJson == null) return "";
        Matcher t = TEXT.matcher(resultJson);
        return t.find() ? t.group(1).trim().toLowerCase(Locale.US) : "";
    }

    /**
     * The decision: true = stop the answer. A stop phrase with a word confidence of at
     * least minConf. Without word confidences, the full text must be the phrase only.
     */
    public static boolean accept(String resultJson, Collection<String> phrases, double minConf) {
        double c = stopConfidence(resultJson, phrases);
        if (c < 0) return false;
        if (c > 1.0) {
            List<String> said = words(text(resultJson));
            for (String p : phrases) {
                if (said.equals(words(p))) return true;
            }
            return false;
        }
        return c >= minConf;
    }
}
