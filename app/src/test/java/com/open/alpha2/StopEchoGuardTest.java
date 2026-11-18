package com.open.alpha2;

import java.util.Arrays;
import java.util.List;

/**
 * Unit test for StopEchoGuard (alpha2-brain#52, #92). No JUnit:
 * scripts/test-apivalidator.py runs it with plain javac and java. The guard takes
 * the time from the caller, so the test needs no waiting.
 *
 * A sentence that holds a stop word form is guarded for its whole play time, until
 * the next sentence_start or a reset. The robot decision in the worker (feedStop)
 * is: stop when the recognizer accepts "stop" and the guard does not block.
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

    /** The recognizer result for an echoed "stop" at full confidence (the robot's own voice). */
    private static final String ECHO =
            "{\"result\":[{\"conf\":1.0,\"end\":1.0,\"start\":0.0,\"word\":\"stop\"}],\"text\":\"stop\"}";

    /** The worker decision: the robot stops when the echo is accepted and the guard is off. */
    private static boolean robotStops(StopEchoGuard g, long nowMs, List<String> stop) {
        return StopWordMatcher.accept(ECHO, stop, 0.7) && !g.blocks(nowMs);
    }

    public static void main(String[] args) {
        final long t0 = 1_000_000L;
        List<String> stop = Arrays.asList("stop");

        // 1. alpha2-brain#92: the real robot failure. The answer to "What does
        // unstoppable mean?" has the sentence "It cannot be stopped or overcome."
        // sentence_start at t=0; the recognizer hears the echo "stop" at t=4 s,
        // while the sentence still plays. The guard must hold for the whole
        // sentence, so the robot does not stop itself. This check fails on the
        // old code (the old guard did not match "stopped" and had no window here).
        StopEchoGuard g = new StopEchoGuard();
        g.onSentence("It cannot be stopped or overcome.", stop, t0);
        check(g.windowCount() == 1, "92: the sentence has a window");
        check(g.blocks(t0 + 4000), "92: guarded at t+4s while the sentence plays");
        check(!robotStops(g, t0 + 4000, stop), "92: the robot does not stop itself");

        // 2. alpha2-brain#92: a "stop" of the owner while a sentence with no stop
        // word plays must still stop the robot.
        g = new StopEchoGuard();
        g.onSentence("The sky is very blue today.", stop, t0);
        check(g.windowCount() == 0, "92 owner: no stop word: no window");
        check(!g.blocks(t0 + 2000), "92 owner: the guard is off");
        check(robotStops(g, t0 + 2000, stop), "92 owner: the robot stops");

        // 3. The real story: it holds "stopped", so the whole sentence is guarded.
        g = new StopEchoGuard();
        g.onSentence(STORY, stop, t0);
        check(g.windowCount() == 1, "story: one window for the sentence");
        check(g.blocks(t0 + 5000), "story: guarded in the sentence");
        check(g.blocks(t0 + 28000), "story: guarded late in the sentence");
        // The next sentence cuts the old window: the audio ends soon after it starts.
        g.onSentence("The end of the story was a happy one.", stop, t0 + 31000);
        check(g.blocks(t0 + 31000), "story cut: still guarded at the next start");
        check(!g.blocks(t0 + 31000 + StopEchoGuard.AUDIO_LAG_MS + 1), "story cut: free after the lag");

        // 4. Word forms that hold "stop": one window for the whole sentence.
        g = new StopEchoGuard();
        g.onSentence("It stopped, it was nonstop and unstoppable.", stop, t0);
        check(g.windowCount() == 1, "stopped/nonstop/unstoppable: one window");
        check(g.blocks(t0 + 1000), "word forms: guarded");

        // 5. A short sentence with "Stop.": guarded until the next sentence cuts it.
        g = new StopEchoGuard();
        List<Integer> ret = g.onSentence("Stop.", stop, t0);
        check(Arrays.asList(0).equals(ret), "Stop.: onSentence returns [0]");
        check(g.windowCount() == 1, "Stop.: one window");
        check(g.blocks(t0), "Stop.: blocks at t0");
        check(g.blocks(t0 + 2000), "Stop.: blocks at +2000");
        g.onSentence("Now the sky is blue.", stop, t0 + 2000);
        check(g.blocks(t0 + 2000), "Stop. cut: still guarded at the next start");
        check(!g.blocks(t0 + 2000 + StopEchoGuard.AUDIO_LAG_MS + 1), "Stop. cut: free after the lag");

        // 6. A long sentence with "stop" only at the end: the whole sentence is
        // guarded, so it is blocked early and late (the robot may say the word
        // anywhere, and the TTS audio can lag).
        String filler = "Once upon a time there was a small robot who liked very long stories "
                + "about trains and rivers and green hills. ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 560) sb.append(filler);
        sb.append("And at the very end of the story Alpha said stop.");
        String longText = sb.toString();
        g = new StopEchoGuard();
        g.onSentence(longText, stop, t0);
        check(g.windowCount() == 1, "long: one window for the sentence");
        check(g.blocks(t0 + 3000), "long: guarded early in the sentence");
        check(g.blocks(t0 + 30000), "long: guarded late in the sentence");

        // 7. The next sentence cuts the old window: audio of the old sentence ends soon.
        g = new StopEchoGuard();
        g.onSentence("stop", stop, t0);
        g.onSentence("The sky is blue.", stop, t0 + 1000);
        check(g.blocks(t0 + 1000), "cut: still guarded at the next start");
        check(!g.blocks(t0 + 1000 + StopEchoGuard.AUDIO_LAG_MS + 1), "cut: free after the lag");

        // 8. reset() removes the windows (tts start and tts stop). null and "" add no window.
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
