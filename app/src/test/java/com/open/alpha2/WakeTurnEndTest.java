package com.open.alpha2;

/** WakeTurnEnd unit test (no JUnit). scripts/test-apivalidator.py runs it. */
public final class WakeTurnEndTest {
    private static int passed = 0;
    private static int failed = 0;

    private WakeTurnEndTest() {}

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    public static void main(String[] args) {
        // alpha2-brain#153: a local command in a turn ends it with an abort, so
        // the server drops the buffered audio and gives no answer.
        eq(true, WakeTurnEnd.abortFor("volume_up"), "volume up: abort");
        eq(true, WakeTurnEnd.abortFor("volume_down"), "volume down: abort");
        eq(true, WakeTurnEnd.abortFor("stand_up"), "stand up: abort");
        eq(true, WakeTurnEnd.abortFor("squat"), "squat: abort");
        eq(true, WakeTurnEnd.abortFor("rest"), "rest: abort");
        eq(true, WakeTurnEnd.abortFor("sleep"), "sleep: abort");
        // "stop" keeps its own path (the stop branch of WakeController.runCommand).
        eq(false, WakeTurnEnd.abortFor("stop"), "stop: no abort here");
        // A turn end with no command (the follow-up window closes) is a plain turn end.
        eq(false, WakeTurnEnd.abortFor(null), "no command: plain turn end");

        System.out.println("WakeTurnEndTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
