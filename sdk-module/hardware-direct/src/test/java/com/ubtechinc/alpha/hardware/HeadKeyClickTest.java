package com.ubtechinc.alpha.hardware;

import java.util.Arrays;

/** HeadKeyClick unit test (no JUnit). scripts/test-apivalidator.py runs it. */
public final class HeadKeyClickTest {
    private static int passed = 0;
    private static int failed = 0;

    private HeadKeyClickTest() {}

    private static void eq(String want, String got, String name) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    private static String g(int code) {
        return Arrays.toString(HeadKeyClick.gestures(code));
    }

    public static void main(String[] args) {
        // Robot session 2026-11-01: + gave 0x3d01, - gave 0x3c01.
        eq("[92, 93]", g(0x3d01), "plus: press 0x5c, release 0x5d (volume up)");
        eq("[90, 91]", g(0x3c01), "minus: press 0x5a, release 0x5b (volume down)");
        eq("true", String.valueOf(HeadKeyClick.isClick(0x3d01)), "plus is a click");
        eq("true", String.valueOf(HeadKeyClick.isClick(0x3c01)), "minus is a click");
        // The old press/release codes stay with the state machine of HeadKeyPoller.
        for (int ev = 0x5a; ev <= 0x5f; ev++) {
            int code = (ev << 8) | 0x01;
            eq("[]", g(code), "old code 0x" + Integer.toHexString(code));
            eq("false", String.valueOf(HeadKeyClick.isClick(code)), "old code not a click 0x"
                    + Integer.toHexString(code));
        }
        eq("[]", g(0x3e01), "other code 0x3e01");
        eq("[]", g(0), "zero");
        eq("[]", g(0x3d), "low byte only");
        // alpha2-brain#45: each click gets a log line with the time since the last click.
        eq("click + 0x3d01 gap_ms=4692 (one volume step; a click with no push: alpha2-brain#45)",
                HeadKeyClick.logLine(0x3d01, 4692), "log line plus");
        eq("click - 0x3c01 (first click) (one volume step; a click with no push: alpha2-brain#45)",
                HeadKeyClick.logLine(0x3c01, -1), "log line minus first");
        System.out.println("HeadKeyClickTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
