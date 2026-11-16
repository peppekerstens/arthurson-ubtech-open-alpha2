package com.open.alpha2;

/**
 * The argument check and the number conversions of the MCP tool
 * self.robot.volume_set (alpha2-brain#123).
 *
 * Pure Java, no Android and no org.json types, so a host test can run it.
 * The caller (AudioCenter.mcpVolumeSet) does the real work with AudioManager.
 *
 * The tool takes one of two arguments:
 *   - direction: "up" or "down". One step, the same step as the local
 *     commands volume_up and volume_down of WakeController.
 *   - level: a whole number from 0 to 100 (percent of the maximum).
 */
public final class VolumeToolArgs {

    /** Lowest level that the tool accepts. */
    public static final int MIN_LEVEL = 0;
    /** Highest level that the tool accepts. */
    public static final int MAX_LEVEL = 100;

    /** Size of one direction step. Same as WakeController.volumeStep(2). */
    static final int STEP_DELTA = 2;
    /** A direction step never goes below this step. Same as WakeController.volumeStep. */
    static final int STEP_FLOOR = 2;
    /** A direction step never goes above this step. Same as WakeController.volumeStep. */
    static final int STEP_CAP = 15;

    /** True when the arguments are good. */
    public final boolean ok;
    /** The error text for the voice LLM. Null when ok is true. */
    public final String error;
    /** True for a direction step. False for a level. Only valid when ok is true. */
    public final boolean byDirection;
    /** With byDirection: true is up, false is down. */
    public final boolean up;
    /** With a level: 0 to 100. */
    public final int level;

    private VolumeToolArgs(boolean ok, String error, boolean byDirection, boolean up, int level) {
        this.ok = ok;
        this.error = error;
        this.byDirection = byDirection;
        this.up = up;
        this.level = level;
    }

    private static VolumeToolArgs fail(String text) {
        return new VolumeToolArgs(false, text, false, false, 0);
    }

    /**
     * Checks the two arguments of the tool.
     *
     * @param direction the direction argument, or null when the caller did not give it
     * @param levelRaw  the level argument (a Number or a String), or null when the caller did not give it
     */
    public static VolumeToolArgs parse(String direction, Object levelRaw) {
        boolean hasDirection = direction != null && !direction.trim().isEmpty();
        boolean hasLevel = levelRaw != null;
        if (hasDirection && hasLevel) {
            return fail("give only one of direction and level, not both");
        }
        if (!hasDirection && !hasLevel) {
            return fail("missing argument: give direction (\"up\" or \"down\") or level (0 to 100)");
        }
        if (hasDirection) {
            String d = direction.trim().toLowerCase(java.util.Locale.ROOT);
            if (d.equals("up")) {
                return new VolumeToolArgs(true, null, true, true, 0);
            }
            if (d.equals("down")) {
                return new VolumeToolArgs(true, null, true, false, 0);
            }
            return fail("direction must be \"up\" or \"down\", got \"" + direction.trim() + "\"");
        }
        double value;
        if (levelRaw instanceof Number) {
            value = ((Number) levelRaw).doubleValue();
        } else if (levelRaw instanceof String) {
            try {
                value = Double.parseDouble(((String) levelRaw).trim());
            } catch (NumberFormatException e) {
                return fail("level must be a whole number from 0 to 100, got \"" + levelRaw + "\"");
            }
        } else {
            return fail("level must be a whole number from 0 to 100");
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
            return fail("level must be a whole number from 0 to 100, got " + levelRaw);
        }
        if (value < MIN_LEVEL || value > MAX_LEVEL) {
            return fail("level must be from 0 to 100, got " + (long) value);
        }
        return new VolumeToolArgs(true, null, false, false, (int) value);
    }

    /**
     * Converts a level (0 to 100 percent) to a step of the audio stream (0 to max).
     * The result is rounded to the nearest step and clamped to 0 to max.
     */
    public static int levelToStep(int level, int max) {
        if (max <= 0) {
            return 0;
        }
        int clamped = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        int step = (clamped * max + MAX_LEVEL / 2) / MAX_LEVEL;
        return Math.max(0, Math.min(max, step));
    }

    /** Converts a step of the audio stream (0 to max) to a level (0 to 100 percent), rounded. */
    public static int stepToLevel(int step, int max) {
        if (max <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(max, step));
        return (clamped * MAX_LEVEL + max / 2) / max;
    }

    /**
     * The step after one direction step. Same rule as the local commands volume_up
     * and volume_down: plus or minus 2, never below 2, never above min(15, max).
     */
    public static int directionTarget(int current, int max, boolean up) {
        int delta = up ? STEP_DELTA : -STEP_DELTA;
        return Math.max(STEP_FLOOR, Math.min(Math.min(STEP_CAP, max), current + delta));
    }

    /** The result text for the voice LLM, for example "The volume is 40 %.". */
    public static String resultText(int level) {
        return "The volume is " + level + " %.";
    }
}
