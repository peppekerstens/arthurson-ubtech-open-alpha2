package com.open.alpha2;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for VolumeStepSequence (alpha2-brain#53). No Android, no JUnit.
 *
 * Run (no Gradle needed):
 *   mkdir -p /tmp/vss-test/com/open/alpha2
 *   cp app/src/main/java/com/open/alpha2/VolumeStepSequence.java /tmp/vss-test/com/open/alpha2/
 *   cp host-test/com/open/alpha2/VolumeStepSequenceTest.java /tmp/vss-test/com/open/alpha2/
 *   cd /tmp/vss-test && javac com/open/alpha2/*.java && java com.open.alpha2.VolumeStepSequenceTest
 */
public final class VolumeStepSequenceTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static final class Recorder implements VolumeStepSequence.Actions {
        final List<String> log = new ArrayList<String>();
        @Override public void tick() { log.add("tick"); }
        @Override public void adjustVolume(boolean playSoundFlag) {
            log.add("adjust(" + playSoundFlag + ")");
        }
        @Override public void meter() { log.add("meter"); }
    }

    private static String run(boolean early, boolean tickReady) {
        Recorder r = new Recorder();
        VolumeStepSequence.run(early, tickReady, r);
        return r.log.toString();
    }

    public static void main(String[] args) {
        check("off, tick ready: old order",
                run(false, true).equals("[adjust(false), tick, meter]"));
        check("off, tick not ready: old order, system click flag",
                run(false, false).equals("[adjust(true), tick, meter]"));
        check("on, tick ready: tick first",
                run(true, true).equals("[tick, adjust(false), meter]"));
        check("on, tick not ready: tick first, system click flag kept",
                run(true, false).equals("[tick, adjust(true), meter]"));

        // Each action runs once on every path.
        for (int i = 0; i < 4; i++) {
            Recorder r = new Recorder();
            VolumeStepSequence.run((i & 1) != 0, (i & 2) != 0, r);
            check("3 actions once, case " + i, r.log.size() == 3);
        }

        // Early path: the tick comes before the binder call even when the binder throws.
        Recorder r = new Recorder();
        VolumeStepSequence.Actions thrower = new VolumeStepSequence.Actions() {
            @Override public void tick() { r.log.add("tick"); }
            @Override public void adjustVolume(boolean f) { throw new IllegalStateException("binder"); }
            @Override public void meter() { r.log.add("meter"); }
        };
        boolean threw = false;
        try {
            VolumeStepSequence.run(true, true, thrower);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("early: tick done before a failing binder call", threw && r.log.toString().equals("[tick]"));

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed != 0) {
            System.exit(1);
        }
    }
}
