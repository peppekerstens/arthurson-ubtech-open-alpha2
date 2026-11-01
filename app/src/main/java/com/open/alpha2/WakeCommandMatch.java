package com.open.alpha2;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The local command match of WakeController (pure Java, unit test WakeCommandMatchTest).
 *
 * <p>Rules (alpha2-brain#16, #21):
 * <ul>
 *   <li>A command must be the whole utterance, with or without a wake word in front
 *       ("alpha stand up" or "stand up"). The raw Vosk text keeps the [unk] tokens:
 *       "[unk] sleep" is a sentence, not a command.</li>
 *   <li>"stop" also counts inside a sentence (stopping is safe). It runs with the wake
 *       word, or while the robot speaks or moves.</li>
 *   <li>Each other command runs with the wake word in the same phrase, or in the command
 *       window after the wake word. Normal talk never gives a motion.</li>
 * </ul>
 */
final class WakeCommandMatch {
    private WakeCommandMatch() {}

    /** Lower case, one space between the tokens. */
    static String norm(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.US);
    }

    /** Removes [unk] and extra spaces. */
    static String clean(String text) {
        if (text == null) return "";
        return norm(text.replace("[unk]", " "));
    }

    /** The command of the whole utterance (raw text with [unk]), or null. */
    static String command(String raw, List<String> wakeWords, Map<String, String> phrases) {
        String rest = norm(raw);
        for (String w : wakeWords) {
            if (rest.startsWith(w + " ")) rest = rest.substring(w.length() + 1).trim();
        }
        for (Map.Entry<String, String> e : phrases.entrySet()) {
            if (rest.equals(norm(e.getKey()))) return e.getValue();
        }
        return null;
    }

    /** True when the clean text holds a wake word as a whole word. */
    static boolean hasWakeWord(String text, List<String> wakeWords) {
        String t = " " + clean(text) + " ";
        for (String w : wakeWords) {
            if (t.contains(" " + w + " ")) return true;
        }
        return false;
    }

    /** True when the clean text holds "stop" as a whole word. */
    static boolean hasStop(String text) {
        return (" " + clean(text) + " ").contains(" stop ");
    }

    /** True when a command other than "stop" runs: with the wake word, or before commandUntil. */
    static boolean runsCommand(String command, boolean wake, long now, long commandUntil) {
        return command != null && !"stop".equals(command) && (wake || now < commandUntil);
    }

    /**
     * The command window (alpha2-brain#45). A command with no wake word runs only in this
     * window. The rules:
     * <ul>
     *   <li>{@link #onWake}: the wake word opens the window.</li>
     *   <li>{@link #onTurnOpen}: the wake turn opens (after the cue). The window starts
     *       again, only when the wake word opened it. A follow-up turn gets no window.</li>
     *   <li>{@link #onTtsStart}: the answer starts. The window closes, so a follow-up turn
     *       after a short answer has no window.</li>
     *   <li>{@link #onTurnEnd} and {@link #onCommand}: the window closes.</li>
     * </ul>
     * Thread safe.
     */
    static final class CommandWindow {
        private long until;
        /** The time of the last close, for the log (0: never open). */
        private long closedAt;

        synchronized void onWake(long now, long windowMs) {
            until = now + windowMs;
        }

        synchronized void onTurnOpen(long now, long windowMs) {
            if (until != 0) until = now + windowMs;
        }

        synchronized void onTtsStart(long now) { close(now); }

        synchronized void onTurnEnd(long now) { close(now); }

        synchronized void onCommand(long now) { close(now); }

        private void close(long now) {
            if (until != 0) closedAt = Math.min(now, until);
            until = 0;
        }

        /** The end of the window (0: closed). */
        synchronized long until() { return until; }

        /** True when a command other than "stop" runs now. */
        synchronized boolean runs(String command, boolean wake, long now) {
            return runsCommand(command, wake, now, until);
        }

        /** The window state for the log line of an ignored command. */
        synchronized String describe(long now) {
            if (until != 0 && now < until) return "open";
            if (until != 0) return "closed " + (now - until) + " ms ago (time out)";
            if (closedAt != 0) return "closed " + (now - closedAt) + " ms ago (turn end, answer or command)";
            return "not open";
        }
    }
}
