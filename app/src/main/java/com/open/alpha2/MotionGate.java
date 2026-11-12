package com.open.alpha2;

import com.ubtechinc.alpha.hardware.ChestSafety;

import java.util.Map;

/**
 * Motion gate for the direct servo routes (alpha2-brain#113, hub D-82).
 *
 * Hard rule of the owner: motion on the robot stays off until the owner approves it.
 * This gate makes the app refuse a servo move that has no approval.
 *
 * There are two switches:
 *  - "motion_enabled": all servo moves are allowed.
 *  - "head_only_enabled": only servo 19 (the head) can move. Other servos stay refused.
 *
 * Both switches are off by default. Both live in memory only: this class never writes
 * to shared preferences or to a file. A restart of the app gives a new instance, and a
 * new instance has both switches off. Do not add a store here.
 *
 * A refused call gives HTTP 403 and a text that names the setting.
 * This class has no Android dependency, so a plain Java test can run it.
 */
public final class MotionGate {
    /** The head servo (hub D-82). */
    public static final int HEAD_SERVO_ID = 19;

    /** The name of the full switch in the API. */
    public static final String SETTING_MOTION = "motion_enabled";
    /** The name of the head-only switch in the API. */
    public static final String SETTING_HEAD_ONLY = "head_only_enabled";

    private volatile boolean motionEnabled = false;
    private volatile boolean headOnlyEnabled = false;

    /** The code that runs when the gate lets the call pass. */
    public interface Allowed<T> {
        T run();
    }

    /** The code that builds the answer when the gate refuses the call. */
    public interface Refused<T> {
        T refuse(int status, String text);
    }

    public boolean isMotionEnabled() {
        return motionEnabled;
    }

    public boolean isHeadOnlyEnabled() {
        return headOnlyEnabled;
    }

    public void setMotionEnabled(boolean on) {
        motionEnabled = on;
    }

    public void setHeadOnlyEnabled(boolean on) {
        headOnlyEnabled = on;
    }

    /** The refusal text for one servo, or null when the move can go on. */
    public String refusalForServo(int servoId) {
        if (motionEnabled) return null;
        if (headOnlyEnabled && servoId == HEAD_SERVO_ID) return null;
        if (headOnlyEnabled) {
            return "Motion is refused: only servo " + HEAD_SERVO_ID + " (the head) can move now, because the setting "
                    + SETTING_HEAD_ONLY + " is on and the setting " + SETTING_MOTION + " is off. Servo "
                    + servoId + " needs " + SETTING_MOTION + "=true.";
        }
        return "Motion is refused: the setting " + SETTING_MOTION + " is off. Servo " + servoId
                + " cannot move. Both motion settings are off by default and after each restart.";
    }

    /** The refusal text for a move of all servos, or null when the move can go on.
     *  The head-only switch does not allow this call: it moves more than the head. */
    public String refusalForAll() {
        if (motionEnabled) return null;
        return "Motion is refused: the setting " + SETTING_MOTION
                + " is off. A move of all servos needs " + SETTING_MOTION + "=true. The setting "
                + SETTING_HEAD_ONLY + " allows servo " + HEAD_SERVO_ID + " only.";
    }

    /**
     * The refusal text for a call that plays a whole action, writes servo angles back or runs
     * a local voice command that moves the body (alpha2-brain#121). The head-only switch does
     * not allow these calls: they move more than the head. Or null when the call can go on.
     *
     * @param what a short name of the call for the text, for example "ubx/play"
     */
    public String refusalForWholeBody(String what) {
        if (motionEnabled) return null;
        return "Motion is refused: " + what + " moves more than the head, and the setting "
                + SETTING_MOTION + " is off. The setting " + SETTING_HEAD_ONLY
                + " does not allow it. Both motion settings are off by default and after each restart.";
    }

    /**
     * The refusal text for a chest move command of debug/serial/send (alpha2-brain#130):
     * cmd 0x03 (move one), 0x05 (move all) or 0x06 (read with release). The chest servos
     * are body servos, so the head-only switch does not allow the call. Or null when the
     * call can go on.
     */
    public String refusalForChestMove() {
        if (motionEnabled) return null;
        return "Motion is refused: the chest move command in debug/serial/send moves the body, and the setting "
                + SETTING_MOTION + " is off. The setting " + SETTING_HEAD_ONLY
                + " does not allow it. Both motion settings are off by default and after each restart.";
    }

    /**
     * The refusal text for the debug/serial/send route (alpha2-brain#130): on port=chest,
     * a chest move command (cmd 0x03 move one, 0x05 move all, 0x06 read with release)
     * needs the gate to allow a body move. Read commands (for example 0x17, 0x39, 0x0E)
     * are not gated, and frames for port=head are not checked at all. Or null when the
     * call can go on.
     */
    public String refusalForSerialSend(String port, byte[] data) {
        if (!"chest".equals(port)) return null;
        if (ChestSafety.findChestMoveCommand(data) < 0) return null;
        return refusalForChestMove();
    }

    /** Run a call that moves the whole body only when the gate allows it. A refusal gives status 403. */
    public <T> T guardWholeBody(String what, Allowed<T> allowed, Refused<T> refused) {
        String why = refusalForWholeBody(what);
        if (why != null) return refused.refuse(403, why);
        return allowed.run();
    }

    /** Run a move of one servo only when the gate allows it. A refusal gives status 403. */
    public <T> T guardServo(int servoId, Allowed<T> allowed, Refused<T> refused) {
        String why = refusalForServo(servoId);
        if (why != null) return refused.refuse(403, why);
        return allowed.run();
    }

    /** Run a move of all servos only when the gate allows it. A refusal gives status 403. */
    public <T> T guardAll(Allowed<T> allowed, Refused<T> refused) {
        String why = refusalForAll();
        if (why != null) return refused.refuse(403, why);
        return allowed.run();
    }

    /** JSON with the state of both switches. */
    public String statusJson() {
        return "{\"ok\":true,\"" + SETTING_MOTION + "\":" + motionEnabled
                + ",\"" + SETTING_HEAD_ONLY + "\":" + headOnlyEnabled + "}";
    }

    /**
     * Change the switches from query parameters. Each parameter is optional, and the
     * value must be true or false. The call changes nothing when a value is wrong.
     *
     * @throws IllegalArgumentException for a value that is not true or false
     */
    public void applyQuery(Map<String, String> query) {
        Boolean motion = parseBool(query, SETTING_MOTION);
        Boolean head = parseBool(query, SETTING_HEAD_ONLY);
        if (motion != null) motionEnabled = motion.booleanValue();
        if (head != null) headOnlyEnabled = head.booleanValue();
    }

    private static Boolean parseBool(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) return null;
        if (v.equals("true")) return Boolean.TRUE;
        if (v.equals("false")) return Boolean.FALSE;
        throw new IllegalArgumentException("parameter '" + name + "' must be true or false, got: " + v);
    }
}
