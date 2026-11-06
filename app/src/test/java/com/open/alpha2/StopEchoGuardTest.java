package com.open.alpha2;

import java.util.Arrays;
import java.util.List;

/**
 * Unit test for StopEchoGuard (alpha2-brain#52). No JUnit:
 * scripts/test-apivalidator.py runs it with plain javac and java. The guard takes
 * the time from the caller, so the test needs no waiting.
 */
public final class StopEchoGuardTest {
    private static int passed = 0;
    private static int failed = 0;

    private StopEchoGuardTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    /** The real story sentence: robot session log 2026-11-06, block F, 07:56. */
    private static final String STORY =
            "in a quiet village, there was a little robot named Alpha who loved to explore. "
            + "Every morning, Alpha would walk through the fields, watching the sun rise and the "
            + "birds fly. One day, while gathering flowers, Alpha found a golden key hidden "
            + "beneath a bush. With curiosity, Alpha used the key to open a door that led to a "
            + "magical forest. In the forest, animals spoke in riddles and the trees sang "
            + "lullabies. Alpha learned that courage and kindness were the true magic. And from "
            + "that day on, Alpha never stopped exploring, because every journey holds a new story";

    public static void main(String[] args) {
        final long t0 = 1_000_000L;
        List<String> stop = Arrays.asList("stop");

        // 1. The real story: "stopped" must not guard the whole 620 characters.
        StopEchoGuard g = new StopEchoGuard();
        g.onSentence(STORY, stop, t0);
        check(g.windowCount() == 0, "story: no window");
        check(!g.blocks(t0 + 5000), "story: free at +5000");
        check(!g.blocks(t0 + 28000), "story: free at +28000");
        check(!g.blocks(t0 + 31000), "story: free at +31000");

        // 2. Word forms that only start with "stop": no window.
        g = new StopEchoGuard();
        g.onSentence("It stopped, it was nonstop and unstoppable.", stop, t0);
        check(g.windowCount() == 0, "stopped/nonstop/unstoppable: no window");

        // 3. A short sentence with "Stop.": guard now, not 10 s later.
        g = new StopEchoGuard();
        List<Integer> ret = g.onSentence("Stop.", stop, t0);
        check(Arrays.asList(0).equals(ret), "Stop.: onSentence returns [0]");
        check(g.windowCount() == 1, "Stop.: one window");
        check(g.blocks(t0), "Stop.: blocks at t0");
        check(g.blocks(t0 + 2000), "Stop.: blocks at +2000");
        check(!g.blocks(t0 + 10000), "Stop.: free at +10000");

        // 4. A long story with "stop" only at the end: free early, guarded late.
        String filler = "Once upon a time there was a small robot who liked very long stories "
                + "about trains and rivers and green hills. ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 560) sb.append(filler);
        sb.append("And at the very end of the story Alpha said stop.");
        String longText = sb.toString();
        List<Integer> at = StopWordMatcher.stopWordOffsets(longText, stop);
        check(at.size() == 1, "long: one match");
        int o = at.get(0);
        g = new StopEchoGuard();
        g.onSentence(longText, stop, t0);
        check(g.windowCount() == 1, "long: one window");
        check(!g.blocks(t0 + 3000), "long: free early in the sentence");
        long when = t0 + (long) (o / 16.0 * 1000);
        check(g.blocks(when), "long: blocks near the word time");

        // 5. The next sentence cuts the old window: audio of the old sentence ends soon.
        g = new StopEchoGuard();
        g.onSentence("stop", stop, t0);
        g.onSentence("The sky is blue.", stop, t0 + 1000);
        check(g.blocks(t0 + 1000), "cut: still guarded at the next start");
        check(!g.blocks(t0 + 1000 + StopEchoGuard.AUDIO_LAG_MS + 1), "cut: free after the lag");

        // 6. reset() removes the windows. null and "" add no window.
        g = new StopEchoGuard();
        g.onSentence("Stop.", stop, t0);
        g.reset();
        check(g.windowCount() == 0, "reset: no window");
        check(!g.blocks(t0), "reset: free");
        g.onSentence(null, stop, t0);
        check(g.windowCount() == 0, "null text: no window");
        g.onSentence("", stop, t0);
        check(g.windowCount() == 0, "empty text: no window");

        System.out.println("StopEchoGuardTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
