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
}
