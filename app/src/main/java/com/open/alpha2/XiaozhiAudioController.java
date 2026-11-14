package com.open.alpha2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.theeasiestway.opus.Constants;
import com.theeasiestway.opus.Opus;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PHASE 2: mic-capture-and-encode + decode-and-playback halves of the XiaoZhi
 * audio path, using theeasiestway/android-opus-codec (opus.aar in app/libs/) as the
 * Opus JNI layer. Deliberately a separate controller from AudioController/
 * AudioPlaybackController (the walkie-talkie feature's mic/speaker classes) rather than
 * reusing them - three concrete reasons:
 *
 *  1. Different sample rate: the XiaoZhi WebSocket protocol's hello handshake commits
 *     to 16kHz mono, 60ms frames (see XiaozhiClient.buildHelloMessage()); the
 *     walkie-talkie pair runs at 8kHz to keep decodeAudioData() load down in the
 *     browser (see AudioController.java's SAMPLE_RATE_HZ comment) - these can't share
 *     one AudioRecord/AudioTrack instance without one side silently mis-resampling.
 *  2. Different wire format: walkie-talkie sends/receives raw/WAV-wrapped PCM over
 *     HTTP; this sends/receives Opus-encoded frames over the already-open XiaozhiClient
 *     WebSocket.
 *  3. Independent lifecycle: a XiaoZhi voice session and a walkie-talkie session are
 *     conceptually unrelated features that happen to both want the mic - see
 *     micGuard()-style exclusivity note on start() below for how a clash is prevented,
 *     rather than silently allowing two AudioRecord opens to fight over the hardware.
 *
 * Runtime-gated: every entry point that touches this class must first check
 * XiaozhiClient.isAudioSupported() (API 21+) - see MainActivity#handleXiaozhiApi's
 * "mic/start" endpoint. This class itself does not re-check that gate internally, to
 * keep the check in exactly one place.
 *
 * Same open-once-per-session, dedicated-HandlerThread pattern as AudioController/
 * AudioPlaybackController, for the same reasons (AudioRecord/AudioTrack/Opus JNI calls
 * should never run on whatever thread happens to call start()/stop(), and start()/
 * stop() must block synchronously so callers get a definite ready/failed result).
 */
public class XiaozhiAudioController {
    private static final String TAG = "XiaozhiAudioController";

    // Must match the audio_params sent in XiaozhiClient's hello message exactly - the
    // server negotiates its own encoder/decoder against whatever this device declared,
    // so drifting these two locations apart would silently corrupt audio in both
    // directions.
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_DURATION_MS = 60;
    // 16000 samples/sec * 0.060 sec = 960 samples/frame - a supported Opus frame size
    // at 16kHz (Opus frame sizes are 2.5/5/10/20/40/60ms; 60ms is the largest standard
    // size and keeps per-frame protocol/JNI overhead low for a voice-assistant use case
    // where latency in the tens-of-ms range is acceptable). Kept as a plain int for PCM
    // buffer sizing (pcmBuf/pmcQueue math below) even though Opus.encode()/decode()
    // themselves take a Constants.FrameSize object, not this int directly - see
    // FRAME_SIZE below. (Correction from an earlier revision of this file: the
    // (short[], int) overloads of encode()/decode() turned out to be *private* in the
    // compiled opus.aar - see FRAME_SIZE's javadoc.)
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000;
    // The only public encode()/decode() overloads that take a frame-size parameter
    // require a Constants.FrameSize object (via its Companion, since Kotlin companion
    // members aren't static from Java's perspective) - confirmed against the compiled
    // opus.aar's method access flags (javap/constant-pool inspection showed the
    // (short[], int) overloads are marked private, likely an internal helper the
    // library's own public (short[], FrameSize) overload delegates to). _960()
    // matches SAMPLES_PER_FRAME's value (960) exactly.
    private static final Constants.FrameSize FRAME_SIZE = Constants.FrameSize.Companion._960();

    private static final long MAX_SESSION_MS = 5 * 60 * 1000; // same safety cap
                                                                 // rationale as
                                                                 // AudioController/
                                                                 // AudioPlaybackController

    // Jitter buffer for incoming (server->device) decoded PCM, same rationale as
    // AudioPlaybackController.JITTER_BUFFER_CAP_BYTES: bound how much backlog can build
    // up during a network hiccup so playback lag stays small instead of growing
    // unbounded. 16000 bytes/sec (16kHz mono 16-bit) * 0.6s = 9600 bytes.
    private static final int JITTER_BUFFER_CAP_BYTES = SAMPLE_RATE_HZ * 2 * 600 / 1000;
    private static final int PREBUFFER_FRAMES = 3; // same startup-underrun rationale as
                                                      // AudioPlaybackController.PREBUFFER_CHUNKS
    private static final int IDLE_POLL_MS = 10;
    private static final int IDLE_PAUSE_MS = 300;

    /** Implemented by XiaozhiClient - decouples this controller from knowing anything
     *  about the WebSocket connection itself. Called on this controller's own capture
     *  thread; must not block. */
    /** Gets each raw 60 ms PCM frame of the capture before the Opus encoder
     *  (WakeController: local Vosk grammar on the same microphone). Runs on the
     *  capture thread: copy the frame and return at once. */
    public interface PcmTap {
        void onPcm(short[] pcm);
    }

    private volatile PcmTap pcmTap;
    /** false = capture keeps the microphone open but sends no frame to the server
     *  (wake-word mode and half duplex while the robot speaks). */
    private volatile boolean uplink = true;

    public void setPcmTap(PcmTap tap) { pcmTap = tap; }

    public void setUplink(boolean on) { uplink = on; }

    public boolean isUplink() { return uplink; }

    public interface EncodedFrameSink {
        void onEncodedFrame(byte[] opusData) throws IOException;
    }

    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile AudioRecord audioRecord;
    private volatile boolean capturing = false;
    private volatile long captureSessionStart = 0;
    private volatile Opus encoder;
    private volatile EncodedFrameSink frameSink;

    private HandlerThread playbackThread;
    private Handler playbackHandler;
    private volatile AudioTrack audioTrack;
    private volatile boolean playing = false;
    private volatile long playbackSessionStart = 0;
    private volatile Opus decoder;
    private final ConcurrentLinkedQueue<byte[]> pcmQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedBytes = new AtomicInteger(0);

    public static final class StartResult {
        public final String error;
        private StartResult(String error) { this.error = error; }
        static StartResult ok() { return new StartResult(null); }
        static StartResult fail(String error) { return new StartResult(error); }
    }

    // ================= Capture (mic -> Opus -> sink) =================

    private void startCaptureThreadIfNeeded() {
        if (captureThread == null) {
            captureThread = new HandlerThread("XiaozhiAudioCaptureThread");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
        }
    }

    /** Opens the mic, starts a dedicated Opus encoder instance, and begins streaming
     *  60ms Opus frames to {@code sink} until stop() is called. Safe to call repeatedly
     *  - a no-op if already capturing. Blocks the calling thread (must NOT be the main
     *  thread) until capture has either started or failed - mirrors AudioController's
     *  start(long) contract. */
    public StartResult startCapture(EncodedFrameSink sink, long timeoutMs) {
        if (audioRecord != null) {
            return StartResult.ok();
        }
        frameSink = sink;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startCaptureThreadIfNeeded();
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                if (audioRecord != null) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioRecord.getMinBufferSize() returned " + minBufBytes
                            + " - 16kHz mono not supported on this hardware");
                    latch.countDown();
                    return;
                }
                int bufBytes = Math.max(minBufBytes * 4, SAMPLES_PER_FRAME * 2 * 4);
                Opus opus;
                try {
                    opus = new Opus();
                    int initResult = opus.encoderInit(Constants.SampleRate.Companion._16000(),
                            Constants.Channels.Companion.mono(), Constants.Application.Companion.voip());
                    if (initResult < 0) {
                        error.set("Opus encoderInit failed, code=" + initResult);
                        latch.countDown();
                        return;
                    }
                } catch (Throwable t) {
                    // Throwable (not Exception) deliberately: a missing/incompatible
                    // native .so surfaces as UnsatisfiedLinkError, which is an Error,
                    // not an Exception - this is exactly the failure mode
                    // XiaozhiClient.isAudioSupported()'s API-level gate exists to avoid,
                    // but this catch is the last line of defense if that gate is ever
                    // bypassed or wrong for a given device.
                    error.set("Opus encoder init threw: " + t.getMessage());
                    latch.countDown();
                    return;
                }
                try {
                    AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG, AUDIO_FORMAT, bufBytes);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        rec.release();
                        opus.encoderRelease();
                        error.set("AudioRecord failed to initialize (state="
                                + rec.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    rec.startRecording();
                    if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        rec.release();
                        opus.encoderRelease();
                        error.set("AudioRecord.startRecording() did not enter RECORDING state");
                        latch.countDown();
                        return;
                    }
                    audioRecord = rec;
                    encoder = opus;
                    capturing = true;
                    captureSessionStart = System.currentTimeMillis();
                    Log.i(TAG, "XiaoZhi mic capture started: " + SAMPLE_RATE_HZ
                            + "Hz mono 16-bit, " + FRAME_DURATION_MS + "ms frames, Opus VOIP mode");
                } catch (Exception e) {
                    opus.encoderRelease();
                    error.set("AudioRecord open failed: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                latch.countDown();
                captureLoop();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out starting XiaoZhi mic capture");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting XiaoZhi mic capture");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Runs on the capture thread: reads exactly one frame's worth of PCM at a time
     *  (blocking - AudioRecord.read() waits for enough samples), encodes it, and hands
     *  the Opus payload to the sink. One encode call per read, matching the protocol's
     *  fixed 60ms frame_duration - unlike AudioController's WAV-chunk streaming (which
     *  tolerates any chunk size), Opus frame boundaries are fixed by SAMPLES_PER_FRAME and
     *  must not be split/merged. */
    private void captureLoop() {
        short[] pcmBuf = new short[SAMPLES_PER_FRAME];
        // 2026-08 diagnostics: used to find out whether "speaking gets no reaction
        // at all" is caused by the mic actually recording silence/garbage data
        // (for example, the stream opened by the adev_open_input_stream HAL-layer
        // fallback is not really connected to the hardware), or the sound itself
        // is fine and it is only a protocol/server-side issue. Roughly every 2
        // seconds (about 33 frames of 60ms) print this frame's RMS and peak
        // amplitude once - it does not flood the log, but at the next recording
        // you can see directly from the log whether sound was really captured -
        // if it stays at 0 or near 0 all the time, that proves an HAL/mic-layer
        // problem; if the values clearly rise while speaking, that proves the
        // recording is normal and the problem is in the server/protocol layer.
        int frameCounter = 0;
        final int LOG_EVERY_N_FRAMES = 33;

        while (capturing && audioRecord != null) {
            // The wake-word capture (PCM tap set) runs for hours: no session cap.
            if (pcmTap == null && System.currentTimeMillis() - captureSessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "XiaoZhi capture session hit MAX_SESSION_MS - stopping");
                capturing = false;
                break;
            }
            int totalRead = 0;
            while (totalRead < pcmBuf.length && capturing) {
                int n = audioRecord.read(pcmBuf, totalRead, pcmBuf.length - totalRead);
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read() returned error code " + n);
                    capturing = false;
                    break;
                }
                totalRead += n;
            }
            if (!capturing || totalRead < pcmBuf.length) {
                break; // partial frame at shutdown - not worth encoding/sending
            }
            PcmTap tap = pcmTap;
            if (tap != null) {
                try {
                    tap.onPcm(pcmBuf);
                } catch (Throwable t) {
                    Log.w(TAG, "PCM tap failed: " + t.getMessage());
                }
            }
            if (!uplink) {
                continue; // microphone open, nothing to the server
            }
            frameCounter++;
            if (frameCounter % LOG_EVERY_N_FRAMES == 0) {
                long sumSq = 0;
                short peak = 0;
                for (short s : pcmBuf) {
                    sumSq += (long) s * (long) s;
                    short abs = (short) Math.abs((int) s);
                    if (abs > peak) peak = abs;
                }
                double rms = Math.sqrt(sumSq / (double) pcmBuf.length);
                Log.i(TAG, "XiaoZhi capture level check: rms=" + String.format(java.util.Locale.US, "%.1f", rms)
                        + " peak=" + peak + " (silence ~= rms<50)");
            }
            try {
                short[] encoded = encoder.encode(pcmBuf, FRAME_SIZE);
                if (encoded != null && encoded.length > 0) {
                    byte[] opusBytes = shortsToBytes(encoded);
                    EncodedFrameSink sink = frameSink;
                    if (sink != null) {
                        sink.onEncodedFrame(opusBytes);
                    }
                }
            } catch (IOException e) {
                // Sink (XiaozhiClient) couldn't send - most likely the WebSocket
                // dropped mid-session. Stop capturing rather than continuing to
                // encode audio nobody can receive; MainActivity's "mic/stop" endpoint
                // or a fresh "mic/start" after reconnecting will resume it.
                Log.w(TAG, "Encoded frame sink failed, stopping capture: " + e.getMessage());
                capturing = false;
                break;
            } catch (Exception e) {
                Log.w(TAG, "Opus encode failed for one frame, skipping: " + e.getMessage());
                // Skip this frame rather than aborting the whole session - a single
                // bad encode (e.g. a transient JNI hiccup) shouldn't kill an otherwise
                // healthy capture session.
            }
        }

        AudioRecord rec = audioRecord;
        audioRecord = null;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
        }
        Opus enc = encoder;
        encoder = null;
        if (enc != null) {
            try { enc.encoderRelease(); } catch (Exception ignored) { }
        }
    }

    /** Stops capture and releases the mic + encoder. Blocks until fully released,
     *  matching AudioController.shutdown()'s synchronous-teardown rationale (a caller
     *  that immediately does something else mic-related afterwards - e.g. hands the mic
     *  back to alpha2services' wake-word engine - must not race an in-flight release). */
    public void stopCapture() {
        capturing = false;
        frameSink = null;
        if (captureHandler == null) return;
        HandlerDrain.awaitQueueDrain(captureHandler, HandlerDrain.DEFAULT_TIMEOUT_MS);
    }

    public boolean isCapturing() {
        // A short pause of the capture in reopenPlayback() counts as capturing:
        // the wake-word watchdog must not start a second capture in that gap.
        return capturing || captureReopening;
    }

    /** true while reopenPlayback() stops and starts the capture again. */
    private volatile boolean captureReopening = false;

    // ================= Playback (Opus <- server -> AudioTrack) =================

    private void startPlaybackThreadIfNeeded() {
        if (playbackThread == null) {
            playbackThread = new HandlerThread("XiaozhiAudioPlaybackThread");
            playbackThread.start();
            playbackHandler = new Handler(playbackThread.getLooper());
        }
    }

    /** Opens the AudioTrack + a dedicated Opus decoder instance and starts the write
     *  loop. Safe to call repeatedly - a no-op if already playing. Blocks the calling
     *  thread (must NOT be the main thread) until playback has either started or
     *  failed. */
    public StartResult startPlayback(long timeoutMs) {
        if (audioTrack != null && playing) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startPlaybackThreadIfNeeded();
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                if (audioTrack != null) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_OUT_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioTrack.getMinBufferSize() returned " + minBufBytes
                            + " - 16kHz mono not supported on this hardware");
                    latch.countDown();
                    return;
                }
                int bufBytes = minBufBytes * 8; // same headroom rationale as
                                                  // AudioPlaybackController's bufBytes
                Opus opus;
                try {
                    opus = new Opus();
                    int initResult = opus.decoderInit(Constants.SampleRate.Companion._16000(),
                            Constants.Channels.Companion.mono());
                    if (initResult < 0) {
                        error.set("Opus decoderInit failed, code=" + initResult);
                        latch.countDown();
                        return;
                    }
                } catch (Throwable t) {
                    error.set("Opus decoder init threw: " + t.getMessage());
                    latch.countDown();
                    return;
                }
                try {
                    // Legacy constructor, matching AudioPlaybackController's own
                    // documented finding that the newer AudioAttributes/AudioFormat
                    // .Builder constructor failed to initialize on this hardware.
                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE_HZ,
                            CHANNEL_OUT_CONFIG,
                            AUDIO_FORMAT,
                            bufBytes,
                            AudioTrack.MODE_STREAM);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        track.release();
                        opus.decoderRelease();
                        error.set("AudioTrack failed to initialize (state="
                                + track.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    // play() deliberately not called yet - see writeLoop()/
                    // prebufferThenPlay(), same underrun-avoidance rationale as
                    // AudioPlaybackController.
                    audioTrack = track;
                    decoder = opus;
                    playing = true;
                    playbackSessionStart = System.currentTimeMillis();
                    pcmQueue.clear();
                    queuedBytes.set(0);
                    Log.i(TAG, "XiaoZhi playback constructed (not yet playing): "
                            + SAMPLE_RATE_HZ + "Hz mono 16-bit, buffer=" + bufBytes + " bytes");
                } catch (Exception e) {
                    opus.decoderRelease();
                    error.set("AudioTrack open failed: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                latch.countDown();
                writeLoop();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out starting XiaoZhi playback");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting XiaoZhi playback");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Called from XiaozhiClient's read loop (a different thread) whenever a binary
     *  (Opus) frame arrives from the server. Decodes immediately (JNI decode is cheap
     *  relative to network I/O) and enqueues the resulting PCM for writeLoop() to drain
     *  - matches AudioPlaybackController's enqueuePcm() pattern of decoding/preparing
     *  audio on the producer thread but only ever writing to the AudioTrack from its
     *  own dedicated thread. Silently drops the frame if playback hasn't been started
     *  (startPlayback() not called yet, or already stopped) rather than buffering
     *  audio nobody will play.
     *
     *  2026-08 fix (a bug confirmed on the real device, manifesting as a large
     *  number of "E/CodecOpus [decode] error: corrupted stream", 307 times in a
     *  single session): this previously used bytesToShorts(opusData) to convert
     *  the received compressed Opus bytes into a short[] before passing them to
     *  the decode(short[], FrameSize) overload. The problem is that
     *  bytesToShorts() used "bytes.length / 2" to determine the length of the
     *  output short[] - that assumes opusData's length is always even, but Opus
     *  is a variable-length codec: the actual byte count of each encoded frame
     *  floats with the audio content/bitrate and can well be odd. With an odd
     *  length, integer division silently truncates the last byte, so the
     *  decoder receives an incomplete compressed bitstream that is one byte
     *  short, triggering corrupted stream (Opus uses a range coder, which is
     *  very sensitive to bitstream integrity; one missing byte is enough to
     *  fail the whole frame's decode) - this also explains why not every frame
     *  hits it (it depends on whether that frame happens to have an odd length,
     *  which is close to half the time by probability). The Opus class (see
     *  com.theeasiestway.opus.Opus) actually already has a decode(byte[],
     *  FrameSize) overload that truly accepts/returns byte[], so this layer of
     *  byte[]<->short[] conversion is not needed at all, and there is no length
     *  parity problem either - this now uses that, and even the bytesToShorts()
     *  helper is no longer needed (shortsToBytes() is still used on the encode
     *  side, and does not have this bug: the conversion direction is
     *  short[]->byte[], the length is determined by short[].length*2, guaranteed
     *  even, no truncation risk). */
    public void onIncomingOpusFrame(byte[] opusData) {
        Opus dec = decoder;
        if (dec == null || !playing) return;
        byte[] pcmBytes;
        try {
            pcmBytes = dec.decode(opusData, FRAME_SIZE);
        } catch (Exception e) {
            Log.w(TAG, "Opus decode failed for one frame, dropping: " + e.getMessage());
            return;
        }
        if (pcmBytes == null || pcmBytes.length == 0) return;
        pcmQueue.add(pcmBytes);
        int total = queuedBytes.addAndGet(pcmBytes.length);
        while (total > JITTER_BUFFER_CAP_BYTES) {
            byte[] dropped = pcmQueue.poll();
            if (dropped == null) break;
            total = queuedBytes.addAndGet(-dropped.length);
        }
    }

    /** Same prebuffer-then-play()/proactive-idle-pause structure as
     *  AudioPlaybackController.writeLoop() - see that class's javadoc for the full
     *  underrun-avoidance rationale, which applies identically here. */
    private void writeLoop() {
        if (!prebufferThenPlay()) {
            finishAndReleasePlayback();
            return;
        }

        int idleMs = 0;
        while (playing && audioTrack != null) {
            // The wake-word mode (PCM tap set) keeps the playback open for hours: no
            // session cap (alpha2-brain#20: after 5 min the answers had no sound).
            if (pcmTap == null && System.currentTimeMillis() - playbackSessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "XiaoZhi playback session hit MAX_SESSION_MS - stopping");
                playing = false;
                break;
            }
            byte[] pcm = pcmQueue.poll();
            if (pcm == null) {
                idleMs += IDLE_POLL_MS;
                if (idleMs >= IDLE_PAUSE_MS) {
                    try {
                        audioTrack.pause();
                    } catch (Exception ignored) {
                    }
                    VoiceTiming.get().idle();
                    if (!prebufferThenPlay()) {
                        finishAndReleasePlayback();
                        return;
                    }
                    idleMs = 0;
                }
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            idleMs = 0;
            queuedBytes.addAndGet(-pcm.length);
            try {
                audioTrack.write(pcm, 0, pcm.length);
                VoiceTiming.get().written();
            } catch (Exception e) {
                Log.w(TAG, "AudioTrack.write() failed, stopping playback: " + e.getMessage());
                playing = false;
                break;
            }
        }
        finishAndReleasePlayback();
    }

    /** Waits (bounded by a short poll loop, not indefinitely - unlike
     *  AudioPlaybackController's stream, a XiaoZhi voice reply might simply never
     *  arrive if the server has nothing to say, e.g. silence between turns) for
     *  PREBUFFER_FRAMES to accumulate, writes them, then calls play(). Returns false if
     *  playback was stopped while waiting. */
    private boolean prebufferThenPlay() {
        int waited = 0;
        final int MAX_PREBUFFER_WAIT_MS = 3000;
        while (playing && audioTrack != null && pcmQueue.size() < PREBUFFER_FRAMES) {
            if (waited >= MAX_PREBUFFER_WAIT_MS) {
                break; // give up prebuffering, play whatever's queued (possibly zero)
            }
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += IDLE_POLL_MS;
        }
        if (!playing || audioTrack == null) return false;
        byte[] pcm;
        while ((pcm = pcmQueue.poll()) != null) {
            queuedBytes.addAndGet(-pcm.length);
            try {
                audioTrack.write(pcm, 0, pcm.length);
                VoiceTiming.get().written();
            } catch (Exception e) {
                Log.w(TAG, "AudioTrack.write() failed during prebuffer: " + e.getMessage());
                return false;
            }
        }
        try {
            audioTrack.play();
            VoiceTiming.get().play();
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack.play() failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void finishAndReleasePlayback() {
        // isPlaybackOpen() must say false after each end of the write loop, also after
        // a write error (then "playing" was still true and nothing opened it again).
        playing = false;
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
        }
        Opus dec = decoder;
        decoder = null;
        if (dec != null) {
            try { dec.decoderRelease(); } catch (Exception ignored) { }
        }
        pcmQueue.clear();
        queuedBytes.set(0);
    }

    /** Stops playback and releases the AudioTrack + decoder. Blocks until fully
     *  released, same rationale as stopCapture(). */
    public void stopPlayback() {
        playing = false;
        if (playbackHandler == null) return;
        HandlerDrain.awaitQueueDrain(playbackHandler, HandlerDrain.DEFAULT_TIMEOUT_MS);
    }

    public boolean isPlaying() {
        return playing;
    }

    /** true when the AudioTrack and the decoder are open and take frames. */
    public boolean isPlaybackOpen() {
        return playing && audioTrack != null && decoder != null;
    }

    /** Opens the playback when it is closed (alpha2-brain#20). When the capture runs,
     *  it uses the safe order of this HAL: capture stop, playback start, 250 ms,
     *  capture start with the same sink. The PCM tap and the uplink flag stay as they
     *  are. An AudioTrack that opens while the AudioRecord runs spoils the input. */
    public synchronized StartResult reopenPlayback(long timeoutMs) {
        if (isPlaybackOpen()) return StartResult.ok();
        if (!capturing || audioRecord == null) return startPlayback(timeoutMs);
        EncodedFrameSink sink = frameSink;
        captureReopening = true;
        try {
            stopCapture();
            StartResult p = startPlayback(timeoutMs);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            StartResult c = startCapture(sink, timeoutMs);
            if (p.error != null) return p;
            if (c.error != null) return StartResult.fail("capture after playback: " + c.error);
            return StartResult.ok();
        } finally {
            captureReopening = false;
        }
    }

    // ================= Shutdown (both halves) =================

    /** Full teardown of both capture and playback - called from
     *  MainActivity#onDestroy(). Unlike stopCapture()/stopPlayback(), this also quits
     *  the HandlerThreads, matching AudioController.shutdown()'s two-stage pattern
     *  (block for in-flight release, then quitSafely()). */
    public void shutdown() {
        stopCapture();
        stopPlayback();
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        if (playbackThread != null) {
            playbackThread.quitSafely();
        }
    }

    // ================= PCM/Opus <-> byte[] conversion =================

    /** The short[] overload of encode() is used to encode PCM samples (short[]
     *  is natively the representation of 16-bit audio samples, consistent with
     *  AudioRecord/AudioTrack, no extra byte-order conversion needed), but the
     *  output of encode() (the compressed Opus payload) has no 16-bit sample
     *  semantics at all; it merely borrows this library's generic "array of
     *  16-bit units" return type - so this helper must convert it into a flat
     *  byte[] to fit into a WebSocket binary frame. Little-endian, consistent
     *  with the ByteOrder.LITTLE_ENDIAN that AudioController.wrapPcmAsWav uses.
     *
     *  The conversion direction here (short[] -> byte[]) always has length
     *  shorts.length*2, guaranteed even, no truncation risk - the symmetric
     *  reverse direction (byte[] -> short[], formerly called bytesToShorts())
     *  is not like this, because Opus is a variable-length codec, the length of
     *  the received compressed byte[] can be odd, and the "/2" integer division
     *  silently truncates the last byte, causing the real-device-confirmed
     *  "corrupted stream" decode error (307 times in a single session) - since
     *  2026-08, onIncomingOpusFrame() has switched to the decode(byte[],
     *  FrameSize) overload that the Opus class itself provides, which truly
     *  accepts/returns byte[], completely bypassing this conversion, so the
     *  bytesToShorts() helper has been deleted, leaving behind no method that
     *  "looks right but actually has a bug" to lure later people into reusing
     *  it. */
    private static byte[] shortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}

