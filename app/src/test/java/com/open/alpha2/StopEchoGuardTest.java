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

        // 3. The real story: it holds "stopped", so the sentence is guarded. It is a
        // long sentence, so its window ends at its cap (the audio lag plus the max
        // sentence length cap), not at the next sentence_start.
        g = new StopEchoGuard();
        g.onSentence(STORY, stop, t0);
        check(g.windowCount() == 1, "story: one window for the sentence");
        check(g.blocks(t0 + 5000), "story: guarded in the sentence");
        check(g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS),
                "story: guarded until its cap");
        check(!g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS + 1),
                "story: free after the cap");
        // The next sentence cuts the old window: the audio ends soon after it starts.
        g = new StopEchoGuard();
        g.onSentence("stop", stop, t0);
        g.onSentence("The end of the story was a happy one.", stop, t0 + 1000);
        check(g.blocks(t0 + 1000), "story cut: still guarded at the next start");
        check(!g.blocks(t0 + 1000 + StopEchoGuard.AUDIO_LAG_MS + 1), "story cut: free after the lag");

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
        // guarded until its cap (the robot may say the word anywhere, and the TTS
        // audio can lag). It is far longer than the max cap, so the window ends at
        // the max cap.
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
        check(g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS),
                "long: guarded until the max cap");
        check(!g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS + 1),
                "long: free after the max cap");

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

        // 9. alpha2-brain#92, review round 1: a lost tts stop. No next sentence_start
        // and no reset come (the tts stop message is lost). The window still ends at
        // its cap: the audio lag plus the play time of the sentence (the per-word
        // estimate, below the max cap). A stop of the owner works again after it.
        g = new StopEchoGuard();
        g.onSentence("It cannot be stopped or overcome.", stop, t0); // 6 words
        check(g.windowCount() == 1, "92 lost stop: one window");
        check(g.blocks(t0 + 4000), "92 lost stop: guarded while it plays");
        long lostEnd = t0 + StopEchoGuard.AUDIO_LAG_MS + 6L * StopEchoGuard.PER_WORD_MS;
        check(g.blocks(lostEnd), "92 lost stop: guarded until its end");
        check(!g.blocks(lostEnd + 1), "92 lost stop: free after the cap");

        // 10. alpha2-brain#92, review round 1: a disconnect or a reconnect clears the
        // guard. The guard is reset when the xiaozhi link changes (a disconnect, or a
        // reconnect). A stop of the owner works at once, not after the first tts start.
        g = new StopEchoGuard();
        g.onSentence("It cannot be stopped or overcome.", stop, t0);
        check(g.blocks(t0 + 1000), "92 link: guarded before the link change");
        g.reset(); // the disconnect or the reconnect
        check(g.windowCount() == 0, "92 link: the link change clears the window");
        check(!g.blocks(t0 + 1000), "92 link: free after the link change");

        // 11. alpha2-brain#92, review round 1: a long sentence keeps the guard until
        // its cap. The sentence is far longer than the max cap, so the window holds
        // until the max cap (the audio lag plus MAX_CAP_MS), and no longer. This is
        // the last-sentence case: no following sentence_start, no tts stop.
        String longCap =
                "This is a very long answer sentence that goes on for many and many "
                + "words so that its play time is far longer than the max cap of the "
                + "guard, and it says the word stop near the end of the sentence here.";
        g = new StopEchoGuard();
        g.onSentence(longCap, stop, t0);
        check(g.windowCount() == 1, "92 long cap: one window");
        check(g.blocks(t0 + 10000), "92 long cap: guarded deep in the sentence");
        check(g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS),
                "92 long cap: guarded until the max cap");
        check(!g.blocks(t0 + StopEchoGuard.AUDIO_LAG_MS + StopEchoGuard.MAX_CAP_MS + 1),
                "92 long cap: free after the max cap");

        System.out.println("StopEchoGuardTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
