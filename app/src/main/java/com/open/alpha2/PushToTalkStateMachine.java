package com.open.alpha2;

/**
 * Push-to-talk turn state machine (alpha2-brain#16).
 *
 * Pure logic: no Android imports, no I/O. The bridge (XiaozhiBridge) posts the
 * timers and calls the events; the listener receives the actions to execute.
 *
 * States:
 *   IDLE       - no turn. The microphone is closed.
 *   PENDING    - a press is registered. No I/O yet.
 *   RECORDING  - the manual turn is open. Frames flow to the server.
 *   WAIT_REPLY - the turn is sent (listen stop). The microphone is closed.
 *                The playback runs until TTS stop.
 *
 * Events (thread-safe):
 *   press()        - IDLE or WAIT_REPLY to PENDING.
 *   arm()          - PENDING to RECORDING, action OPEN_TURN.
 *   release()      - PENDING to IDLE, action SHORT_PRESS (the existing toggle).
 *                    RECORDING to WAIT_REPLY, action SEND_TURN.
 *   turnTimeout()  - RECORDING to WAIT_REPLY, action SEND_TURN (the 60 s cap).
 *   ttsStop()      - WAIT_REPLY to IDLE. Returns true when it closed a turn.
 *   replyTimeout() - WAIT_REPLY to IDLE. Returns true when it closed a turn.
 *   disconnect()   - any state to IDLE.
 *   abort()        - I/O failure before the turn is established: to IDLE, no action.
 */
public final class PushToTalkStateMachine {

    public enum State { IDLE, PENDING, RECORDING, WAIT_REPLY }

    /** The actions the bridge executes on the state changes. */
    public interface Listener {
        /** Open the manual turn: listen start (mode=manual), uplink on, mic held. */
        void onOpenTurn();

        /** Send the turn: listen stop, uplink off, start the reply timeout. */
        void onSendTurn();

        /** Run the existing short-press connect/disconnect toggle. */
        void onShortPress();
    }

    private final Listener listener;
    private State state = State.IDLE;

    public PushToTalkStateMachine(Listener listener) {
        this.listener = listener;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void press() {
        if (state == State.IDLE || state == State.WAIT_REPLY) {
            state = State.PENDING;
        }
    }

    public synchronized void arm() {
        if (state == State.PENDING) {
            state = State.RECORDING;
            listener.onOpenTurn();
        }
    }

    public synchronized void release() {
        if (state == State.PENDING) {
            state = State.IDLE;
            listener.onShortPress();
        } else if (state == State.RECORDING) {
            state = State.WAIT_REPLY;
            listener.onSendTurn();
        }
    }

    public synchronized void turnTimeout() {
        if (state == State.RECORDING) {
            state = State.WAIT_REPLY;
            listener.onSendTurn();
        }
    }

    /** True when this event closed a push-to-talk turn (WAIT_REPLY to IDLE). */
    public synchronized boolean ttsStop() {
        if (state == State.WAIT_REPLY) {
            state = State.IDLE;
            return true;
        }
        return false;
    }

    /** True when this event closed a push-to-talk turn (WAIT_REPLY to IDLE). */
    public synchronized boolean replyTimeout() {
        if (state == State.WAIT_REPLY) {
            state = State.IDLE;
            return true;
        }
        return false;
    }

    public synchronized void disconnect() {
        state = State.IDLE;
    }

    /** I/O failure before the turn is established: back to IDLE, no action. */
    public synchronized void abort() {
        if (state == State.PENDING || state == State.RECORDING) {
            state = State.IDLE;
        }
    }
}
