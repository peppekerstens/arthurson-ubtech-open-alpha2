package com.open.alpha2;

import java.util.Arrays;

/** VoiceLanguages unit test (no JUnit). scripts/test-apivalidator.py runs it. */
public final class VoiceLanguagesTest {
    private static int passed = 0;
    private static int failed = 0;

    private VoiceLanguagesTest() {}

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    public static void main(String[] args) {
        // Default: English only. A stored Dutch choice gives the English model and list.
        eq(Arrays.asList("en"), VoiceLanguages.allowed(VoiceLanguages.DEFAULT), "default list");
        eq(Arrays.asList("en"), VoiceLanguages.allowed(null), "null list");
        eq(Arrays.asList("en"), VoiceLanguages.allowed("de,fr"), "bad list");
        eq("en", VoiceLanguages.effective("nl", "en"), "Dutch is off: English");
        eq("en", VoiceLanguages.effective("en", "en"), "English is on");
        eq("en", VoiceLanguages.effective(null, "en"), "no choice");
        // Dutch input is refused, English input is the normal path.
        eq("language nl is off (languages: en)", VoiceLanguages.refuse("nl", "en"), "refuse nl");
        eq(null, VoiceLanguages.refuse("en", "en"), "accept en");
        // One setting switches Dutch on again.
        eq(Arrays.asList("en", "nl"), VoiceLanguages.allowed("en, NL"), "both on");
        eq("nl", VoiceLanguages.effective("nl", "en,nl"), "Dutch is on");
        eq(null, VoiceLanguages.refuse("nl", "en,nl"), "accept nl");
        eq("nl", VoiceLanguages.effective("nl", "nl"), "Dutch only");
        // The setting value is checked.
        eq(null, VoiceLanguages.check("en,nl"), "valid list");
        eq("languages must be a list of en and nl", VoiceLanguages.check("en,fr"), "unknown code");
        eq("languages must be a list of en and nl", VoiceLanguages.check(" "), "empty list");

        System.out.println("VoiceLanguagesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
