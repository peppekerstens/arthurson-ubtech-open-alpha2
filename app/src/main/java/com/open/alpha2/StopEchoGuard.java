package com.open.alpha2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Echo guard for a heard "stop" while the robot speaks (alpha2-brain#52).
 *
 * The second Vosk recognizer hears the robot's own voice. The old guard blocked
 * every "stop" while the current answer sentence held the word. One "stopped" in
 * a 620 character story then blocked a real "stop" for 31 seconds (robot session
 * log 2026-11-06, block F, 07:56). This class blocks only the time windows where the
 * TTS audio of a matched stop word can play.
 *
 * Each window is [from, until] in milliseconds. The window of a word starts at
 * the estimated play time of the word (fast estimate, minus a margin) and ends at
 * the estimated end of the word (slow estimate, plus the audio lag and a margin).
 * The next sentence_start cuts the old windows: the audio of the earlier sentence
 * ends soon after the next sentence starts.
 *
 * The caller gives the time (nowMs). This class never calls
 * System.currentTimeMillis(), so the tests run without waiting. Pure Java
 * (java.* only), no Android and no org.json: scripts/test-apivalidator.py runs it
 * with plain javac.
 */
final class StopEchoGuard {
    /** Fast speech estimate: 20 characters per second (log: 620 characters in 31 s). */
    static final double FAST_CHARS_PER_SEC = 20.0;
    /** Slow speech estimate for the word end: 12 characters per second. */
    static final double SLOW_CHARS_PER_SEC = 12.0;
    /** Slack at both ends of a window, in ms. */
    static final long MARGIN_MS = 1500;
    /** The audio can lag the sentence_start message by up to 2.5 s. */
    static final long AUDIO_LAG_MS = 2500;

    /** The open windows. Each entry is {from, until} in ms. */
    private final List<long[]> windows = new ArrayList<>();

    /** Remove all windows. The caller runs it at TTS start. */
    synchronized void reset() {
        windows.clear();
    }

    /**
     * Register the stop words of one answer sentence. nowMs is the time of the
     * sentence_start message, about the time the audio of the sentence starts.
     * Returns the start index of each matched stop word in the text, an empty list
     * when the sentence holds no match. No Java 8 library calls: the robot runs
     * API 22, where List.removeIf throws NoSuchMethodError.
     */
    synchronized List<Integer> onSentence(String text, Collection<String> phrases, long nowMs) {
        // The audio of an earlier sentence ends soon after this sentence starts.
        for (long[] w : windows) {
            w[1] = Math.min(w[1], nowMs + AUDIO_LAG_MS);
        }
        Iterator<long[]> it = windows.iterator();
        while (it.hasNext()) {
            if (it.next()[1] < nowMs) {
                it.remove();
            }
        }
        List<Integer> at = StopWordMatcher.stopWordOffsets(text, phrases);
        for (int o : at) {
            // L: the length of the matched word at offset o.
            int len = 1;
            while (o + len < text.length() && Character.isLetterOrDigit(text.charAt(o + len))) {
                len++;
            }
            long from = nowMs + (long) (o / FAST_CHARS_PER_SEC * 1000) - MARGIN_MS;
            long until = nowMs + AUDIO_LAG_MS
                    + (long) ((o + len) / SLOW_CHARS_PER_SEC * 1000) + MARGIN_MS;
            windows.add(new long[] {from, until});
        }
        return at;
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
