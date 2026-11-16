package com.open.alpha2;

/**
 * Tests of the argument check and the level to step conversion of the MCP tool
 * self.robot.volume_set (alpha2-brain#123). A plain Java runner, with no JUnit,
 * as the other tests. Run from the repository root: scripts/test-apivalidator.py
 */
public final class VolumeToolArgsTest {
    private static int passed = 0;
    private static int failed = 0;

    private VolumeToolArgsTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static boolean refused(String direction, Object level) {
        VolumeToolArgs a = VolumeToolArgs.parse(direction, level);
        return !a.ok && a.error != null && !a.error.isEmpty();
    }

    public static void main(String[] args) {
        // Direction.
        VolumeToolArgs up = VolumeToolArgs.parse("up", null);
        check(up.ok && up.byDirection && up.up, "direction up");
        VolumeToolArgs down = VolumeToolArgs.parse("down", null);
        check(down.ok && down.byDirection && !down.up, "direction down");
        VolumeToolArgs mixed = VolumeToolArgs.parse("  Down ", null);
        check(mixed.ok && mixed.byDirection && !mixed.up, "direction: case and spaces");
        check(refused("louder", null), "direction: unknown word");
        check(VolumeToolArgs.parse("sideways", null).error.contains("\"up\" or \"down\""),
                "direction: error text names the two words");

        // Level: good values.
        VolumeToolArgs l0 = VolumeToolArgs.parse(null, Integer.valueOf(0));
        check(l0.ok && !l0.byDirection && l0.level == 0, "level 0");
        VolumeToolArgs l100 = VolumeToolArgs.parse(null, Integer.valueOf(100));
        check(l100.ok && l100.level == 100, "level 100");
        VolumeToolArgs l50 = VolumeToolArgs.parse(null, Long.valueOf(50L));
        check(l50.ok && l50.level == 50, "level 50 as Long");
        VolumeToolArgs l50d = VolumeToolArgs.parse(null, Double.valueOf(50.0));
        check(l50d.ok && l50d.level == 50, "level 50.0 as Double");
        VolumeToolArgs l40s = VolumeToolArgs.parse(null, " 40 ");
        check(l40s.ok && l40s.level == 40, "level as text");

        // Level: refused values, each with an error text.
        check(refused(null, Integer.valueOf(-1)), "level -1");
        check(refused(null, Integer.valueOf(101)), "level 101");
        check(refused(null, Long.valueOf(Long.MAX_VALUE)), "level very large");
        check(refused(null, Double.valueOf(50.5)), "level 50.5");
        check(refused(null, Double.valueOf(Double.NaN)), "level NaN");
        check(refused(null, Double.valueOf(Double.POSITIVE_INFINITY)), "level infinity");
        check(refused(null, "loud"), "level text that is no number");
        check(refused(null, Boolean.TRUE), "level of a wrong type");
        check(VolumeToolArgs.parse(null, Integer.valueOf(150)).error.contains("0 to 100"),
                "level: error text names the range");
        check(VolumeToolArgs.parse(null, Integer.valueOf(150)).error.contains("150"),
                "level: error text names the value");

        // Both or none.
        check(refused("up", Integer.valueOf(50)), "both direction and level");
        check(refused(null, null), "none");
        check(refused("", null), "empty direction and no level");
        check(VolumeToolArgs.parse("", Integer.valueOf(30)).ok, "empty direction is not given");

        // Level to step. The stream of the robot has 15 steps.
        check(VolumeToolArgs.levelToStep(0, 15) == 0, "step of 0 percent");
        check(VolumeToolArgs.levelToStep(100, 15) == 15, "step of 100 percent");
        check(VolumeToolArgs.levelToStep(50, 15) == 8, "step of 50 percent (7.5 rounds up)");
        check(VolumeToolArgs.levelToStep(40, 15) == 6, "step of 40 percent");
        check(VolumeToolArgs.levelToStep(1, 15) == 0, "step of 1 percent");
        check(VolumeToolArgs.levelToStep(100, 7) == 7, "step of 100 percent, max 7");
        check(VolumeToolArgs.levelToStep(50, 100) == 50, "step of 50 percent, max 100");
        check(VolumeToolArgs.levelToStep(150, 15) == 15, "step clamped at the top");
        check(VolumeToolArgs.levelToStep(-20, 15) == 0, "step clamped at the bottom");
        check(VolumeToolArgs.levelToStep(50, 0) == 0, "step with max 0");
        boolean never = true;
        int last = -1;
        for (int p = 0; p <= 100; p++) {
            int s = VolumeToolArgs.levelToStep(p, 15);
            if (s < 0 || s > 15 || s < last) {
                never = false;
            }
            last = s;
        }
        check(never, "step stays in range and never goes down when the level goes up");

        // Step to level (the number in the result text).
        check(VolumeToolArgs.stepToLevel(0, 15) == 0, "level of step 0");
        check(VolumeToolArgs.stepToLevel(15, 15) == 100, "level of the top step");
        check(VolumeToolArgs.stepToLevel(6, 15) == 40, "level of step 6 is 40");
        check(VolumeToolArgs.stepToLevel(8, 15) == 53, "level of step 8");
        check(VolumeToolArgs.stepToLevel(20, 15) == 100, "level clamped at the top");
        check(VolumeToolArgs.stepToLevel(5, 0) == 0, "level with max 0");
        boolean back = true;
        for (int s = 0; s <= 15; s++) {
            if (VolumeToolArgs.levelToStep(VolumeToolArgs.stepToLevel(s, 15), 15) != s) {
                back = false;
            }
        }
        check(back, "step to level to step gives the same step");

        // Direction step: the rule of the local commands volume_up and volume_down.
        check(VolumeToolArgs.directionTarget(8, 15, true) == 10, "up from 8");
        check(VolumeToolArgs.directionTarget(8, 15, false) == 6, "down from 8");
        check(VolumeToolArgs.directionTarget(14, 15, true) == 15, "up stops at 15");
        check(VolumeToolArgs.directionTarget(15, 15, true) == 15, "up at the top");
        check(VolumeToolArgs.directionTarget(3, 15, false) == 2, "down stops at 2");
        check(VolumeToolArgs.directionTarget(0, 15, true) == 2, "up from 0 gives 2");
        check(VolumeToolArgs.directionTarget(10, 12, true) == 12, "up stops at a lower max");

        // Result text.
        check("The volume is 40 %.".equals(VolumeToolArgs.resultText(40)), "result text");

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed != 0) {
            System.exit(1);
        }
    }
}
