package com.open.alpha2;

/** Host test for the SemanticFunctionTexts table (no JUnit).
 *  scripts/test-apivalidator.py runs it (alpha2-brain#95). */
public final class SemanticFunctionTextsTest {
    private static int passed = 0;
    private static int failed = 0;

    private SemanticFunctionTextsTest() {}

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    /** True when the text has a Han character (the scan range of alpha2-brain#95). */
    private static boolean hasHan(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    public static void main(String[] args) {
        // The English dialogue language speaks English: no Han character.
        for (String key : SemanticFunctionTexts.KEYS) {
            String t = SemanticFunctionTexts.text("en", key, "Song");
            if (t == null || hasHan(t)) {
                failed++;
                System.out.println("FAIL en/" + key + ": missing or has Han: " + t);
            } else {
                passed++;
            }
        }
        // The Chinese dialogue language keeps the Chinese text.
        for (String key : SemanticFunctionTexts.KEYS) {
            String t = SemanticFunctionTexts.text("zh", key, "歌");
            if (t == null || !hasHan(t)) {
                failed++;
                System.out.println("FAIL zh/" + key + ": missing or no Han: " + t);
            } else {
                passed++;
            }
        }
        // The spoken text is the old text (the English row is also the fallback).
        eq("Sorry, I couldn't turn on Bluetooth.", SemanticFunctionTexts.text("en", "btOnFail", null), "en btOnFail");
        eq("Sorry, the camera isn't ready.", SemanticFunctionTexts.text("en", "camNotReady", null), "en camNotReady");
        eq("Okay, now playing Song.", SemanticFunctionTexts.text("en", "musicNow", "Song"), "en musicNow");
        eq("好的，正在播放Song。", SemanticFunctionTexts.text("zh", "musicNow", "Song"), "zh musicNow");
        // A dialogue language that is not in the table speaks English.
        eq("Sorry, I couldn't turn on Bluetooth.", SemanticFunctionTexts.text("xx", "btOnFail", null),
                "unknown lang falls back to en");
        // An unknown key has no text.
        eq(null, SemanticFunctionTexts.text("en", "bogus", null), "unknown key");
        eq(null, SemanticFunctionTexts.text("zh", "bogus", null), "unknown key zh");

        System.out.println("SemanticFunctionTextsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
