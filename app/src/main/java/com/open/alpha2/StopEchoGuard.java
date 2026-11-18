package com.open.alpha2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Echo guard for a heard "stop" while the robot speaks (alpha2-brain#52, #92).
 *
 * The second Vosk recognizer hears the robot's own voice. A sentence that holds a
 * stop word form ("stop", "stops", "stopped", "unstoppable", ...) is guarded for its
 * whole play time: from its sentence_start until the next sentence_start or the tts
 * stop. The robot may say the word anywhere in the sentence (a long sentence, a late
 * word), and the TTS audio can lag the sentence_start message, so a fixed short
 * window (the old 1.5 s) let the echo through and stopped the robot. Robot test
 * 2026-11-14: the answer to "What does unstoppable mean?" holds "stopped", and the
 * echo of that word stopped the robot 4 s into the answer.
 *
 * Each window is [from, until] in milliseconds. A stop word sentence adds one window
 * that opens at its sentence_start and stays open for the sentence: until the next
 * sentence_start cuts it (the audio of the earlier sentence ends soon after the next
 * sentence starts), or a reset clears it (tts start, tts stop, a disconnect, a
 * reconnect), or its hard end passes.
 *
 * The hard end is the sentence_start time plus the audio lag plus an estimate of the
 * play time of the sentence (PER_WORD_MS for each word, capped at MAX_CAP_MS). It is
 * a fallback that keeps a stop of the owner working when no reset comes: a lost tts
 * stop, a disconnect or a reconnect, or a long last sentence with no following
 * sentence_start. Without it, a window could stay open with no end and ignore a stop
 * of the owner forever (review round 1 of fork PR 56).
 *
 * The caller gives the time (nowMs). This class never calls
 * System.currentTimeMillis(), so the tests run without waiting. Pure Java
 * (java.* only), no Android and no org.json: scripts/test-apivalidator.py runs it
 * with plain javac.
 */
final class StopEchoGuard {
    /** The audio of a sentence can lag the next sentence_start by up to 2.5 s. */
    static final long AUDIO_LAG_MS = 2500;
    /** The estimate of the play time of one word of an answer sentence. */
    static final long PER_WORD_MS = 400;
    /** The cap of the sentence length estimate: at most this much play time. */
    static final long MAX_CAP_MS = 15000;

    /** The open windows. Each entry is {from, until} in ms. */
    private final List<long[]> windows = new ArrayList<>();

    /** Remove all windows. The caller runs it at tts start, at tts stop, on a
     *  disconnect and on a reconnect. */
    synchronized void reset() {
        windows.clear();
    }

    /**
     * Register the stop word forms of one answer sentence. nowMs is the time of the
     * sentence_start message, about the time the audio of the sentence starts. A
     * sentence that holds a stop word form adds one window that stays open for the
     * whole sentence: until the next sentence_start cuts it, a reset clears it, or
     * its hard end (nowMs plus the audio lag plus the sentence length estimate)
     * passes. Returns the start index of each matched stop word form in the text, an
     * empty list when the sentence holds no match. No Java 8 library calls: the
     * robot runs API 22, where List.removeIf throws NoSuchMethodError.
     */
    synchronized List<Integer> onSentence(String text, Collection<String> phrases, long nowMs) {
        // The audio of an earlier sentence ends soon after this sentence starts:
        // cut the old open windows at the audio lag.
        for (long[] w : windows) {
            w[1] = Math.min(w[1], nowMs + AUDIO_LAG_MS);
        }
        Iterator<long[]> it = windows.iterator();
        while (it.hasNext()) {
            if (it.next()[1] <= nowMs) {
                it.remove();
            }
        }
        List<Integer> at = StopWordMatcher.stopWordOffsets(text, phrases);
        if (!at.isEmpty()) {
            // The sentence holds a stop word form: guard the whole play time, with
            // a hard end so a stop of the owner always works.
            windows.add(new long[] {nowMs, hardEnd(text, nowMs)});
        }
        return at;
    }

    /** The hard end of a window for a sentence: its sentence_start time (nowMs) plus
     *  the audio lag plus the play time of the sentence (PER_WORD_MS for each word,
     *  capped at MAX_CAP_MS). */
    private static long hardEnd(String text, long nowMs) {
        long cap = wordCount(text) * PER_WORD_MS;
        if (cap > MAX_CAP_MS) cap = MAX_CAP_MS;
        return nowMs + AUDIO_LAG_MS + cap;
    }

    /** The number of words of a sentence, for the play time estimate. */
    private static int wordCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(text.charAt(i))) i++;
            if (i < len) {
                n++;
                while (i < len && !Character.isWhitespace(text.charAt(i))) i++;
            }
        }
        return n;
    }

    /** true when nowMs is inside one window: the robot may say "stop" now. */
    synchronized boolean blocks(long nowMs) {
        for (long[] w : windows) {
            if (w[0] <= nowMs && nowMs <= w[1]) return true;
        }
        return false;
    }

    /** The number of open windows. For tests. */
    synchronized int windowCount() {
        return windows.size();
    }
}
