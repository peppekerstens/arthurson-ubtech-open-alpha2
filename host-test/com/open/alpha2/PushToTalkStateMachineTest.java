package com.open.alpha2;

/**
 * Host test for PushToTalkStateMachine (alpha2-brain#16).
 * No Android, no JUnit: plain checks in main().
 *
 * Run (no Gradle needed):
 *   mkdir -p /tmp/ptt-test/com/open/alpha2
 *   cp app/src/main/java/com/open/alpha2/PushToTalkStateMachine.java /tmp/ptt-test/com/open/alpha2/
 *   cp host-test/com/open/alpha2/PushToTalkStateMachineTest.java /tmp/ptt-test/com/open/alpha2/
 *   cd /tmp/ptt-test && javac com/open/alpha2/*.java && java com.open.alpha2.PushToTalkStateMachineTest
 */
public final class PushToTalkStateMachineTest {

    private int passed = 0;
    private int failed = 0;
    private final Recorder recorder = new Recorder();

    /** Records the listener actions in order. */
    private static final class Recorder implements PushToTalkStateMachine.Listener {
        final java.util.List<String> actions = new java.util.ArrayList<String>();
        @Override public void onOpenTurn() { actions.add("OPEN"); }
        @Override public void onSendTurn() { actions.add("SEND"); }
        @Override public void onShortPress() { actions.add("SHORT"); }
        boolean has(int i, String a) { return i < actions.size() && a.equals(actions.get(i)); }
        int size() { return actions.size(); }
    }

    private PushToTalkStateMachine machine() {
        return new PushToTalkStateMachine(recorder);
    }

    private void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) {
        new PushToTalkStateMachineTest().run();
    }

    private void run() {
        final PushToTalkStateMachine.State IDLE = PushToTalkStateMachine.State.IDLE;
        final PushToTalkStateMachine.State PENDING = PushToTalkStateMachine.State.PENDING;
        final PushToTalkStateMachine.State RECORDING = PushToTalkStateMachine.State.RECORDING;
        final PushToTalkStateMachine.State WAIT_REPLY = PushToTalkStateMachine.State.WAIT_REPLY;

        // 1. Idle press: PENDING, no action.
        PushToTalkStateMachine m = machine();
        m.press();
        check("1 idle press -> PENDING", m.state() == PENDING);
        check("1 no action on press", recorder.size() == 0);

        // 2. Arm opens the turn.
        m.arm();
        check("2 arm -> RECORDING", m.state() == RECORDING);
        check("2 OPEN action", recorder.size() == 1 && recorder.has(0, "OPEN"));

        // 3. Release sends the turn.
        m.release();
        check("3 release -> WAIT_REPLY", m.state() == WAIT_REPLY);
        check("3 SEND action", recorder.size() == 2 && recorder.has(1, "SEND"));

        // 4. TTS stop closes the turn.
        check("4 ttsStop returns true", m.ttsStop());
        check("4 state IDLE", m.state() == IDLE);

        // 5. Short press: release before arm, SHORT only.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.release();
        check("5 short press -> IDLE", m.state() == IDLE);
        check("5 SHORT action only", recorder.size() == 1 && recorder.has(0, "SHORT"));

        // 6. Turn timeout sends the turn.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.turnTimeout();
        check("6 timeout -> WAIT_REPLY", m.state() == WAIT_REPLY);
        check("6 OPEN then SEND", recorder.size() == 2 && recorder.has(0, "OPEN") && recorder.has(1, "SEND"));

        // 7. Reply timeout closes the turn, no extra action.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.release();
        check("7 replyTimeout returns true", m.replyTimeout());
        check("7 state IDLE", m.state() == IDLE);
        check("7 no extra action", recorder.size() == 2);

        // 8. Disconnect from any state: IDLE, no action.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.disconnect();
        check("8 disconnect from RECORDING -> IDLE", m.state() == IDLE);
        check("8 no SEND on disconnect", recorder.size() == 1);
        recorder.actions.clear();
        m.press();
        m.arm();
        m.release();
        m.disconnect();
        check("8 disconnect from WAIT_REPLY -> IDLE", m.state() == IDLE);
        check("8 no action on disconnect", recorder.size() == 2);

        // 9. Abort: back to IDLE, no extra action.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.abort();
        check("9 abort -> IDLE", m.state() == IDLE);
        check("9 OPEN sent, no SEND", recorder.size() == 1 && recorder.has(0, "OPEN"));

        // 10. Extra presses in PENDING and RECORDING: one OPEN only.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.press();
        m.press();
        m.arm();
        m.press();
        m.release();
        check("10 one OPEN one SEND", recorder.size() == 2 && recorder.has(0, "OPEN") && recorder.has(1, "SEND"));

        // 11. A new long press during WAIT_REPLY starts a new turn.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.release();
        m.press();
        m.arm();
        check("11 second turn open", m.state() == RECORDING);
        check("11 two OPEN actions", recorder.size() == 3 && recorder.has(0, "OPEN") && recorder.has(2, "OPEN"));

        // 12. TTS stop outside WAIT_REPLY: no close, no action.
        recorder.actions.clear();
        m = machine();
        check("12 ttsStop in IDLE returns false", !m.ttsStop());
        check("12 no action", recorder.size() == 0);

        // 13. Press during RECORDING: no state change.
        recorder.actions.clear();
        m = machine();
        m.press();
        m.arm();
        m.press();
        check("13 press in RECORDING stays", m.state() == RECORDING);

        // 14. Reply timeout outside WAIT_REPLY: no close.
        recorder.actions.clear();
        m = machine();
        check("14 replyTimeout in IDLE returns false", !m.replyTimeout());
        check("14 no action", recorder.size() == 0);

        System.out.println("PushToTalkStateMachineTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
