package com.open.alpha2;

/**
 * Time log of one voice turn (alpha2-brain, queue item 6). Pure java.*: no Android
 * class, so scripts/test-apivalidator.py tests it with plain javac.
 *
 * One line per turn, tag "VoiceTiming", stable key=value format:
 *
 *   v=1 turn=3 kind=wake wake=0 listen=812 speech_end=2300 stt=2410 tts_start=2900
 *   audio_first=3010 play=3105 tts_stop=5000 play_end=6100
 *
 * All values are milliseconds from t0 on a monotonic clock (System.nanoTime). A value
 * that did not occur is "-". t0 is the wake word (kind=wake) or the listen start of
 * a follow-up turn (kind=followup, wake=-).
 *
 *   wake        Vosk gave the final result with the wake word.
 *   listen      listen start went to the server, uplink on (after the "Yes?" cue).
 *   speech_end  the last microphone frame with speech before the server STT result.
 *   stt         the server STT message came in (server VAD end + ASR done).
 *   tts_start   the server tts start message came in.
 *   audio_first the first Opus frame of the answer came in.
 *   play        AudioTrack.play() after the first frame (playback start).
 *   tts_stop    the server tts stop message came in (all audio is sent).
 *   play_end    the last PCM write to the AudioTrack. The sound ends about one
 *               buffer later.
 *
 * The line comes when the playback is idle after tts stop, or at the next wake word,
 * or when the turn ends. A follow-up turn without speech gives no line.
 * The class only records times. It changes no behaviour.
 */
public final class VoiceTiming {

    public interface Sink {
        void line(String text);
    }

    public interface Clock {
        long nanos();
    }

    public static final String TAG = "VoiceTiming";

    static final String[] KEYS = {
        "wake", "listen", "speech_end", "stt", "tts_start",
        "audio_first", "play", "tts_stop", "play_end",
    };
    private static final int WAKE = 0, LISTEN = 1, SPEECH_END = 2, STT = 3, TTS_START = 4,
            AUDIO_FIRST = 5, PLAY = 6, TTS_STOP = 7, PLAY_END = 8;

    private static final class Turn {
        final int id;
        final String kind;
        final long t0;
        final long[] at = new long[KEYS.length];
        final boolean[] has = new boolean[KEYS.length];

        Turn(int id, String kind, long t0) {
            this.id = id;
            this.kind = kind;
            this.t0 = t0;
        }

        void first(int k, long now) {
            if (!has[k]) {
                has[k] = true;
                at[k] = now;
            }
        }

        void last(int k, long now) {
            has[k] = true;
            at[k] = now;
        }

        boolean worthALine() {
            return "wake".equals(kind) || has[STT] || has[TTS_START] || has[AUDIO_FIRST];
        }
    }

    private static final VoiceTiming INSTANCE = new VoiceTiming(new Clock() {
        @Override public long nanos() {
            return System.nanoTime();
        }
    });

    public static VoiceTiming get() {
        return INSTANCE;
    }

    private final Clock clock;
    private volatile Sink sink;
    private int counter;
    /** The turn that listens or waits for the answer. */
    private Turn current;
    /** The turn after tts stop: its audio still plays. */
    private Turn closing;

    VoiceTiming(Clock clock) {
        this.clock = clock;
    }

    public void setSink(Sink s) {
        sink = s;
    }

    /** The wake word: a new turn. Open turns give their line first. */
    public void wake() {
        String out;
        synchronized (this) {
            long now = clock.nanos();
            out = join(take(closing), take(current));
            closing = null;
            current = new Turn(++counter, "wake", now);
            current.first(WAKE, now);
        }
        emit(out);
    }

    /** listen start went to the server for the wake turn. */
    public synchronized void listen() {
        if (current != null) current.first(LISTEN, clock.nanos());
    }

    /** Follow-up window after an answer: a new turn with t0 = listen start. */
    public void followUp() {
        String out;
        synchronized (this) {
            long now = clock.nanos();
            out = take(current);
            current = new Turn(++counter, "followup", now);
            current.first(LISTEN, now);
        }
        emit(out);
    }

    /** One microphone frame with speech while the turn listens. */
    public synchronized void speech() {
        Turn t = current;
        if (t != null && !t.has[STT]) t.last(SPEECH_END, clock.nanos());
    }

    public synchronized void stt() {
        if (current != null) current.first(STT, clock.nanos());
    }

    public synchronized void ttsStart() {
        if (current != null) current.first(TTS_START, clock.nanos());
    }

    /**
     * One Opus frame of the answer came in. The first frame of the current turn also
     * closes the closing turn: from now on play() and written() belong to the current
     * turn. Without this, a missed idle pause before tts stop keeps the closing turn
     * open up to the prebuffer wait, and a follow-up answer then shows play=-.
     */
    public void audioFrame() {
        String out = null;
        synchronized (this) {
            Turn t = current;
            if (t == null || t.has[AUDIO_FIRST]) return; // no allocation per frame
            t.first(AUDIO_FIRST, clock.nanos());
            if (closing != null) {
                out = take(closing);
                closing = null;
            }
        }
        emit(out);
    }

    /** AudioTrack.play(): counts only after the first frame of the turn. */
    public synchronized void play() {
        Turn t = playTarget();
        if (t == null || !t.has[AUDIO_FIRST]) return;
        long now = clock.nanos();
        t.first(PLAY, now);
        t.first(PLAY_END, now); // a short answer can fit in the prebuffer
    }

    /** One PCM write to the AudioTrack. */
    public synchronized void written() {
        Turn t = playTarget();
        if (t != null && t.has[PLAY]) t.last(PLAY_END, clock.nanos());
    }

    /** tts stop: the turn waits for the end of its audio. */
    public void ttsStop() {
        String out;
        synchronized (this) {
            out = take(closing);
            closing = current;
            current = null;
            if (closing != null) closing.first(TTS_STOP, clock.nanos());
        }
        emit(out);
    }

    /** The playback queue is empty (idle pause): the closing turn is done. */
    public void idle() {
        String out;
        synchronized (this) {
            out = take(closing);
            closing = null;
        }
        emit(out);
    }

    /** The turn ends (window closed, stop, wake mode off). */
    public void end() {
        String out;
        synchronized (this) {
            out = join(take(closing), take(current));
            closing = null;
            current = null;
        }
        emit(out);
    }

    private Turn playTarget() {
        return closing != null ? closing : current;
    }

    private static String take(Turn t) {
        if (t == null || !t.worthALine()) return null;
        return format(t);
    }

    static String format(Turn t) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("v=1 turn=").append(t.id).append(" kind=").append(t.kind);
        for (int k = 0; k < KEYS.length; k++) {
            sb.append(' ').append(KEYS[k]).append('=');
            if (t.has[k]) sb.append((t.at[k] - t.t0) / 1000000L);
            else sb.append('-');
        }
        return sb.toString();
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "\n" + b;
    }

    private void emit(String out) {
        Sink s = sink;
        if (out == null || s == null) return;
        for (String line : out.split("\n")) s.line(line);
    }
}
