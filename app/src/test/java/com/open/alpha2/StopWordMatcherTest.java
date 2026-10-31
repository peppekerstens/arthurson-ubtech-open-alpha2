package com.open.alpha2;

import java.util.Arrays;
import java.util.List;

/**
 * StopWordMatcher unit test (no JUnit). scripts/test-apivalidator.py runs it with
 * plain javac and java. The JSON samples have the format of Vosk 0.3.x with
 * setWords(true).
 */
public final class StopWordMatcherTest {
    private static int passed = 0;
    private static int failed = 0;

    private StopWordMatcherTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) {
        List<String> stop = Arrays.asList("stop");

        // Partial results: a whole word only.
        check(StopWordMatcher.partialHasStop("{\n  \"partial\" : \"stop\"\n}", stop), "partial stop");
        check(StopWordMatcher.partialHasStop("{\"partial\" : \"[unk] [unk] stop\"}", stop), "partial unk stop");
        check(!StopWordMatcher.partialHasStop("{\"partial\" : \"[unk] [unk]\"}", stop), "partial unk only");
        check(!StopWordMatcher.partialHasStop("{\"partial\" : \"\"}", stop), "partial empty");
        check(!StopWordMatcher.partialHasStop("{\"partial\" : \"stopping\"}", stop), "partial no part word");
        check(!StopWordMatcher.partialHasStop(null, stop), "partial null");
        check(!StopWordMatcher.partialHasStop("garbage", stop), "partial garbage");

        String strong = "{\n  \"result\" : [{\n      \"conf\" : 0.93,\n      \"end\" : 1.02,\n"
                + "      \"start\" : 0.6,\n      \"word\" : \"stop\"\n    }],\n  \"text\" : \"stop\"\n}";
        String weak = "{\"result\" : [{\"conf\" : 0.41, \"end\" : 1.0, \"start\" : 0.7, \"word\" : \"stop\"}],"
                + " \"text\" : \"stop\"}";
        String mixed = "{\"result\" : [{\"conf\" : 0.30, \"end\" : 0.5, \"start\" : 0.1, \"word\" : \"[unk]\"},"
                + " {\"conf\" : 0.88, \"end\" : 1.0, \"start\" : 0.6, \"word\" : \"stop\"},"
                + " {\"conf\" : 0.20, \"end\" : 1.5, \"start\" : 1.1, \"word\" : \"[unk]\"}],"
                + " \"text\" : \"[unk] stop [unk]\"}";
        String unkOnly = "{\"result\" : [{\"conf\" : 1.0, \"end\" : 0.5, \"start\" : 0.1, \"word\" : \"[unk]\"}],"
                + " \"text\" : \"[unk]\"}";
        String empty = "{\"text\" : \"\"}";
        String noConfStop = "{\"text\" : \"stop\"}";
        String noConfLong = "{\"text\" : \"[unk] stop\"}";

        check(Math.abs(StopWordMatcher.stopConfidence(strong, stop) - 0.93) < 1e-9, "conf strong");
        check(Math.abs(StopWordMatcher.stopConfidence(mixed, stop) - 0.88) < 1e-9, "conf mixed: the stop word only");
        check(StopWordMatcher.stopConfidence(unkOnly, stop) < 0, "conf unk only");
        check(StopWordMatcher.stopConfidence(empty, stop) < 0, "conf empty");

        check(StopWordMatcher.accept(strong, stop, 0.7), "accept strong");
        check(!StopWordMatcher.accept(weak, stop, 0.7), "reject weak (echo guard)");
        check(StopWordMatcher.accept(weak, stop, 0.4), "accept weak with a low limit");
        check(StopWordMatcher.accept(mixed, stop, 0.7), "accept stop inside echo");
        check(!StopWordMatcher.accept(unkOnly, stop, 0.7), "reject unk only");
        check(!StopWordMatcher.accept(empty, stop, 0.7), "reject empty");
        check(StopWordMatcher.accept(noConfStop, stop, 0.7), "no conf: whole text stop");
        check(!StopWordMatcher.accept(noConfLong, stop, 0.7), "no conf: stop inside a sentence");
        check(!StopWordMatcher.accept(null, stop, 0.7), "reject null");

        // A phrase of two words: the lowest word confidence counts.
        List<String> two = Arrays.asList("stop now");
        String twoWords = "{\"result\" : [{\"conf\" : 0.95, \"end\" : 0.5, \"start\" : 0.1, \"word\" : \"stop\"},"
                + " {\"conf\" : 0.6, \"end\" : 1.0, \"start\" : 0.6, \"word\" : \"now\"}], \"text\" : \"stop now\"}";
        check(Math.abs(StopWordMatcher.stopConfidence(twoWords, two) - 0.6) < 1e-9, "two words: min conf");
        check(!StopWordMatcher.accept(twoWords, two, 0.7), "two words: reject");
        check(StopWordMatcher.partialHasStop("{\"partial\" : \"[unk] stop now\"}", two), "two words partial");
        check(!StopWordMatcher.partialHasStop("{\"partial\" : \"now stop\"}", two), "two words order");

        // The answer itself says stop: the guard text check.
        check(StopWordMatcher.textHasStopWord("A bus stops at the corner.", stop), "text: stops");
        check(StopWordMatcher.textHasStopWord("Stop.", stop), "text: Stop.");
        check(StopWordMatcher.textHasStopWord("The boats could not stop rocking", stop), "text: stop inside");
        check(StopWordMatcher.textHasStopWord("It stopped, then it went on.", stop), "text: stopped");
        check(!StopWordMatcher.textHasStopWord("The weather is sunny today.", stop), "text: no stop");
        check(!StopWordMatcher.textHasStopWord("unstoppable", stop), "text: word start only");
        check(!StopWordMatcher.textHasStopWord(null, stop), "text: null");
        check(!StopWordMatcher.textHasStopWord("", stop), "text: empty");

        System.out.println("StopWordMatcherTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
