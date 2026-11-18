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
 * that opens at its sentence_start and stays open until the next sentence_start cuts
 * it (the audio of the earlier sentence ends soon after the next sentence starts) or
 * a reset clears it (tts start and tts stop).
 *
 * The caller gives the time (nowMs). This class never calls
 * System.currentTimeMillis(), so the tests run without waiting. Pure Java
 * (java.* only), no Android and no org.json: scripts/test-apivalidator.py runs it
 * with plain javac.
 */
final class StopEchoGuard {
    /** The audio of a sentence can lag the next sentence_start by up to 2.5 s. */
    static final long AUDIO_LAG_MS = 2500;
    /** The far end of a window that is still open (cut by the next sentence_start). */
    private static final long OPEN_END = Long.MAX_VALUE;

    /** The open windows. Each entry is {from, until} in ms. */
    private final List<long[]> windows = new ArrayList<>();

    /** Remove all windows. The caller runs it at tts start and at tts stop. */
    synchronized void reset() {
        windows.clear();
    }

    /**
     * Register the stop word forms of one answer sentence. nowMs is the time of the
     * sentence_start message, about the time the audio of the sentence starts. A
     * sentence that holds a stop word form adds one window that stays open for the
     * whole sentence. Returns the start index of each matched stop word form in the
     * text, an empty list when the sentence holds no match. No Java 8 library calls:
     * the robot runs API 22, where List.removeIf throws NoSuchMethodError.
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
            // The sentence holds a stop word form: guard the whole play time.
            windows.add(new long[] {nowMs, OPEN_END});
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
