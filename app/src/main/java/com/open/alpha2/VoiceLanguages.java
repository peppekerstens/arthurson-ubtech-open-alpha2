package com.open.alpha2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The voice languages of the robot (alpha2-brain#88, owner decision D-62). Pure Java.
 *
 * One setting, "languages" of the WakeController: a comma list of "en" and "nl". The default
 * is "en": the robot understands English only, because a Dutch talk in the room must not get
 * an answer or start a command. The Dutch Vosk model and the Dutch command words stay in
 * the code. They are off until "nl" is in the list.
 */
public final class VoiceLanguages {
    public static final String DEFAULT = "en";

    private VoiceLanguages() {}

    /** The language codes of a list such as "en,nl". An empty or bad list gives English only. */
    public static List<String> allowed(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String c : csv.trim().toLowerCase(Locale.US).split("[,\\s]+")) {
                if (("en".equals(c) || "nl".equals(c)) && !out.contains(c)) out.add(c);
            }
        }
        if (out.isEmpty()) out.add(DEFAULT);
        return out;
    }

    /** null when the list is valid, else the error text. */
    public static String check(String csv) {
        if (csv == null || csv.trim().isEmpty()) return "languages must be a list of en and nl";
        for (String c : csv.trim().toLowerCase(Locale.US).split("[,\\s]+")) {
            if (!c.isEmpty() && !"en".equals(c) && !"nl".equals(c)) return "languages must be a list of en and nl";
        }
        return null;
    }

    /** The language to use: the requested one when it is on, else the first language that is on. */
    public static String effective(String requested, String csv) {
        List<String> on = allowed(csv);
        String r = requested == null ? "" : requested.trim().toLowerCase(Locale.US);
        return on.contains(r) ? r : on.get(0);
    }

    /** null when the language is on, else the reason that it is off. */
    public static String refuse(String requested, String csv) {
        List<String> on = allowed(csv);
        String r = requested == null ? "" : requested.trim().toLowerCase(Locale.US);
        if (on.contains(r)) return null;
        return "language " + r + " is off (languages: " + android.text.TextUtils.join(",", on) + ")";
    }
}
