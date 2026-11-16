package com.open.alpha2;

/**
 * The turn end of a local command in a turn (alpha2-brain#153). Pure Java, no
 * Android classes, so a plain Java test can run it (WakeTurnEndTest).
 *
 * <p>A local command in a turn (volume up, stand up, ...) ends the turn with an
 * abort: the server gets no listen stop, drops the buffered audio, and gives no
 * answer. "stop" keeps its own path (the stop branch of
 * WakeController.runCommand). A turn end with no command (the follow-up window
 * closes) stays a plain turn end: the server transcribes the speech and answers.
 */
final class WakeTurnEnd {
    private WakeTurnEnd() {}

    /** True when the turn end of this command is an abort (no listen stop).
     *  "stop" and a turn end with no command are plain turn ends. */
    static boolean abortFor(String command) {
        return command != null && !"stop".equals(command);
    }
}
