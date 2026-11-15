package com.open.alpha2;

/**
 * The refusal choice for a call that plays an action (alpha2-brain#144). Pure Java, no
 * Android classes, so a plain Java test can run it (PlayRefusalTest).
 *
 * <p>ActionDirect is an Android class. Its MCP play tools (self.robot.play_action,
 * self.robot.play_random_action) call this function before the play: a non-null text is
 * the refusal the tool returns. Before this change the tools answered API_ERROR_FAILED
 * from playActionDirect, and the LLM did not hear that the motion is refused.
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
}
