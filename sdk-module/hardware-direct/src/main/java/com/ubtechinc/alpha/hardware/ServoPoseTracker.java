package com.ubtechinc.alpha.hardware;

/**
 * Command pose tracking (dead reckoning): records the last 20-axis command value
 * sent by this App.
 *
 * <p>2026-09-06 corrected an old belief: the cmd 13 reply is <b>not</b> constant -
 * the official PC tuner measured a varying signed real value (and this App now
 * reads live); cmd 5 (05 00 header) <b>can</b> drive the servo on this machine
 * (official tuner + user visual confirmation). The old conclusion "only cmd 3 can
 * move, cmd13 is constant" is suspected to come from the old 00 00 send header,
 * and is now void.</p>
 *
 * <p>The tracked value only means "the last command", not the real position. It
 * goes stale after dancing / reboot / the official tuner moves it - so it is
 * <b>forbidden</b> to use it to fill in a full-frame send (it once caused a
 * "madness" incident where all 19 axes pulled to the wrong position). Single-servo
 * always goes through cmd05 and moves only the target one. From boot until the
 * first full send, the pose is unknown ({@link #snapshot()} returns null); the
 * caller must report unknown as-is and must not fabricate.</p>
 */
public final class ServoPoseTracker {
    private final int[] pose = new int[22];
    private volatile boolean known = false;
    // 2026-09-08 added: per-axis known flags (for the servoAngleAllResponse command
    // pose board).
    // Background: full known has to wait for one servo/all full send, and stays
    // unknown for a long time after boot; but a single-axis servo/one success means
    // that axis is definitely at the command position (within ±1° error), so record
    // it per axis to get something on the board as fast as possible - still zero
    // wire, still no guarantee for the other axes (stale after dancing / reboot).
    private final boolean[] knownIdx = new boolean[22];

    /** Record a successful cmd3 send (20-axis old full or 22-axis full; 20 updates only the first 20 axes, 21/22 keep their value). */
    public synchronized void update(int[] angles) {
        if (angles == null || (angles.length != 20 && angles.length != 22)) return;
        System.arraycopy(angles, 0, pose, 0, angles.length);
        for (int i = 0; i < angles.length; i++) knownIdx[i] = true;
        known = true;
    }

    /** Record a successful cmd05 single send (only mark that one axis known, the rest are not touched). */
    public synchronized void updateOne(int id, int angle) {
        if (id < 1 || id > 22) return;
        pose[id - 1] = angle;
        knownIdx[id - 1] = true;
    }

    /** Copy of the current command pose; returns null when unknown. */
    public synchronized int[] snapshot() {
        return known ? pose.clone() : null;
    }

    /** Per-axis snapshot: an unknown axis returns null (servoAngleAllResponse uses it directly to fill the board). */
    public synchronized Integer[] snapshotBoxed() {
        Integer[] out = new Integer[22];
        for (int i = 0; i < 22; i++) {
            out[i] = knownIdx[i] ? Integer.valueOf(pose[i]) : null;
        }
        return out;
    }

    public synchronized boolean isKnown() {
        return known;
    }
}
