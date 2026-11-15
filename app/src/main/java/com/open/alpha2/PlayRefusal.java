package com.open.alpha2;

/**
 * The refusal choice for a call that plays an action (alpha2-brain#144). Pure Java, no
 * Android classes, so a plain Java test can run it (PlayRefusalTest).
 *
 * <p>ActionDirect is an Android class. Its MCP play tools (self.robot.play_action,
 * self.robot.play_random_action) put their whole choice into PlayRefusal.route: the gate
 * refusal first, and only when it is null the play part. Before this change the tools
 * answered API_ERROR_FAILED from playActionDirect, and the LLM did not hear that the
 * motion is refused.
 */
final class PlayRefusal {
    private PlayRefusal() {}

    /** The refusal text for a call that plays a whole action, or null when the gate
     *  allows it. Without a gate the call is refused: no gate means no approval
     *  (the same choice as the gate-off case, alpha2-brain#121).
     *
     * @param gate the motion gate of the app, or null when it is not set
     * @param what a short name of the call for the text, for example "play_action"
     */
    static String refusalForPlay(MotionGate gate, String what) {
        if (gate == null) return "Motion is refused: " + what + " has no motion gate.";
        return gate.refusalForWholeBody(what);
    }

    /** The name of the boot stand-up in the refusal text. */
    static final String BOOT_STANDUP = "official boot standup";

    /** The refusal text for the stand-up pose that the app sends about 30 s after each start
     *  (ActionDirect.officialBootStandup), or null when the gate allows it. The pose moves all
     *  servos, so the head-only switch does not allow it (alpha2-brain#148). */
    static String refusalForBootStandup(MotionGate gate) {
        return refusalForPlay(gate, BOOT_STANDUP);
    }

    /** The refusal text for a move of one servo, or null. Without a gate the move is refused. */
    static String refusalForServoMove(MotionGate gate, int servoId) {
        if (gate == null) return "Motion is refused: servo " + servoId + " has no motion gate.";
        return gate.refusalForServo(servoId);
    }

    /** The refusal text for a move of all servos, or null. Without a gate the move is refused. */
    static String refusalForAllServosMove(MotionGate gate) {
        if (gate == null) return "Motion is refused: a move of all servos has no motion gate.";
        return gate.refusalForAll();
    }

    /** The code that builds the result when the gate refuses the call, from the
     *  refusal text. */
    interface Refused<T> {
        T apply(String why);
    }

    /** The play part; it runs only when the gate allows the call. */
    interface Play<T> {
        T get();
    }

    /**
     * The whole choice of the MCP play tools (alpha2-brain#144): ask the gate for the
     * refusal text of the call. When the gate refuses, the refused part builds the
     * result from the text, and the play part never runs. When the gate allows the
     * call, the play part runs, and its result comes back unchanged.
     *
     * <p>Only java.* types: the build targets API 22 (minSdk 19), and
     * java.util.function is API 24 or newer.
     *
     * @param gate the motion gate of the app, or null when it is not set
     * @param what a short name of the call for the text, for example "play_action"
     * @param refused builds the refusal result from the refusal text
     * @param play the play part; runs only when the gate allows the call
     */
    static <T> T route(MotionGate gate, String what, Refused<T> refused, Play<T> play) {
        String why = refusalForPlay(gate, what);
        if (why != null) return refused.apply(why);
        return play.get();
    }
}
