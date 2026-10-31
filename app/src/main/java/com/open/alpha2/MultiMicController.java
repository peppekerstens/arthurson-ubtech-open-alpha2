package com.open.alpha2;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

/**
 * 5-mic capture from the FPGA sound card (card 0, "RK-FPGA-I2S"), next to the mono
 * AudioRecord path (AudioController, which reads the USB CM108B card 3).
 *
 * <h3>How the stock system reads the mics</h3>
 * alpha2services (IflyteckEngine) uses the iFlytek {@code AlsaRecorder.createInstance(0,
 * 64000, 1536)}: libalsa-jni.so opens /dev/snd/pcmC0D0c directly and feeds the raw
 * stream to the iFlytek CAE engine (wake word, beam, angle). The Android HAL does not
 * see this card. ueventd gives the node mode 0777, so this app opens it without root.
 *
 * <h3>Stream layout (measured 2026-11-01)</h3>
 * 2 channels, S16_LE, 64000 Hz. Each channel carries 4 TDM slots in turn, so each slot
 * is a 16 kHz signal (two CX20810 4-channel ADCs behind the FPGA). The slot phase is
 * NOT stable: each open starts at a random slot. The lock below finds slot 0 again:
 * channel 1 has exactly one slot with a smooth (low-pass) signal, the "aux" slot. Its
 * mean |diff| is 4 to 250 times lower than the mic slots, in silence and in sound.
 * That slot defines canonical slot 0 of channel 1. Channel 0 must then show its two
 * smooth slots (the speaker reference) at slots 1 and 3; this is the cross-check.
 *
 * <h3>Canonical channel order</h3>
 * m1 = c0s0, m2 = c0s2, m3 = c1s1, m4 = c1s2, m5 = c1s3 (the 5 mics), ref1 = c0s1,
 * ref2 = c0s3 (speaker reference, both carry the speaker signal, verified with a 440 Hz
 * test tone), aux = c1s0 (no speaker signal, no mic signal: unknown).
 *
 * <p>Wake mode, Xiaozhi and /stream/mic keep the mono AudioRecord path. They use
 * another card, so this capture runs at the same time and never pauses them.
 */
public final class MultiMicController {
    private static final String TAG = "MultiMic";
    static final String DEVICE = "/dev/snd/pcmC0D0c";
    static final int RAW_RATE = 64000;
    static final int RAW_CHANNELS = 2;
    static final int SLOT_RATE = 16000;
    private static final int PERIOD_FRAMES = 1024;  // 16 ms at 64 kHz
    private static final int PERIODS = 8;
    private static final int WARMUP_FRAMES = 6400;  // 100 ms: the first samples are junk
    private static final int LOCK_FRAMES = 8192;    // 128 ms of data for the first lock
    private static final int CHECK_FRAMES = RAW_RATE; // check the lock again each second

    /** Canonical order: names and (raw channel, canonical slot). */
    public static final String[] NAMES = {"m1", "m2", "m3", "m4", "m5", "ref1", "ref2", "aux"};
    private static final int[] CH = {0, 0, 1, 1, 1, 0, 0, 1};
    private static final int[] SLOT = {0, 2, 1, 2, 3, 1, 3, 0};

    /** Called on the capture thread with n samples of each of the 8 canonical channels.
     *  Must not block. The arrays are reused after the call returns. */
    public interface Listener {
        void onBlock(short[][] ch8, int n);
    }

    private static final MultiMicController INSTANCE = new MultiMicController();

    public static MultiMicController get() { return INSTANCE; }

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object stateLock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile String lastError;
    // lock state (written by the capture thread, read by status())
    private volatile int rotation = -1;
    private volatile float confidence;
    private volatile boolean crossCheckOk;
    private volatile int relocks;
    private volatile int overruns;
    private volatile long framesRead;
    private final float[] levels = new float[8];

    private MultiMicController() {}

    // -- start / stop ----------------------------------------------------------------

    /** Adds a listener and starts the capture if needed. Returns null or an error. */
    public String subscribe(Listener l, long timeoutMs) {
        String err = MultiMicNative.ensureLoaded();
        if (err != null) return err;
        synchronized (stateLock) {
            listeners.add(l);
            if (thread != null && thread.isAlive() && !running) {
                // The old capture thread is on its way out (one read is at most 16 ms).
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (thread == null || !thread.isAlive()) {
                lastError = null;
                rotation = -1;
                running = true;
                thread = new Thread(new Runnable() {
                    @Override
                    public void run() { captureLoop(); }
                }, "MultiMicCapture");
                thread.start();
            }
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (lastError != null) {
                unsubscribe(l);
                return lastError;
            }
            if (rotation >= 0) return null;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        unsubscribe(l);
        return lastError != null ? lastError : "timeout: no slot lock in " + timeoutMs + " ms";
    }

    /** Removes a listener. The capture stops when no listener is left. */
    public void unsubscribe(Listener l) {
        synchronized (stateLock) {
            listeners.remove(l);
            if (listeners.isEmpty()) running = false;
        }
    }

    public void shutdown() {
        synchronized (stateLock) {
            listeners.clear();
            running = false;
        }
    }

    // -- capture thread ----------------------------------------------------------------

    private void captureLoop() {
        int fd = MultiMicNative.open(DEVICE, RAW_CHANNELS, RAW_RATE, PERIOD_FRAMES, PERIODS);
        if (fd < 0) {
            lastError = "open " + DEVICE + " failed: errno " + (-fd)
                    + (fd == -16 ? " (busy: another process reads the 5-mic card)" : "");
            Log.w(TAG, lastError);
            running = false;
            return;
        }
        Log.i(TAG, "capture start");
        short[] raw = new short[PERIOD_FRAMES * RAW_CHANNELS];
        short[][] out = new short[8][PERIOD_FRAMES / 4 + 4];
        short[] sf = new short[8];       // one super frame: 4 frames x 2 channels
        double[] lvlAcc = new double[8];
        int lvlCount = 0;
        long pos = 0;                    // frame counter since open or since the last overrun
        long[] d0 = new long[4], d1 = new long[4];
        short[] prev = new short[8];     // previous sample of each raw (channel, slot)
        long accFrames = 0;
        int pendingRot = -1, pendingCount = 0;
        framesRead = 0;
        try {
            while (running) {
                int n = MultiMicNative.read(fd, raw, PERIOD_FRAMES);
                if (n == -32) { // EPIPE: overrun, the slot phase can change
                    overruns++;
                    pos = 0;
                    rotation = -1;
                    accFrames = 0;
                    java.util.Arrays.fill(d0, 0);
                    java.util.Arrays.fill(d1, 0);
                    continue;
                }
                if (n < 0) {
                    lastError = "read failed: errno " + (-n);
                    Log.w(TAG, lastError);
                    break;
                }
                framesRead += n;
                int outN = 0;
                for (int f = 0; f < n; f++, pos++) {
                    short a = raw[2 * f], b = raw[2 * f + 1];
                    if (pos < WARMUP_FRAMES) continue;
                    int q = (int) (pos & 3);
                    // smoothness statistics per raw slot (for lock and re-check)
                    d0[q] += Math.abs(a - prev[q]);
                    d1[q] += Math.abs(b - prev[4 + q]);
                    prev[q] = a;
                    prev[4 + q] = b;
                    accFrames++;
                    int r = rotation;
                    if (r < 0) {
                        if (accFrames >= LOCK_FRAMES) {
                            int best = lockFrom(d0, d1);
                            rotation = best;
                            Log.i(TAG, "slot lock rotation=" + best + " conf=" + confidence
                                    + " crossCheck=" + crossCheckOk);
                            accFrames = 0;
                            java.util.Arrays.fill(d0, 0);
                            java.util.Arrays.fill(d1, 0);
                        }
                        continue;
                    }
                    if (accFrames >= CHECK_FRAMES) {
                        int best = bestRotation(d1);
                        if (best != r && smoothRatio(d1, best) < 0.5f) {
                            if (best == pendingRot) pendingCount++; else { pendingRot = best; pendingCount = 1; }
                            if (pendingCount >= 2) {
                                Log.w(TAG, "slot phase moved: rotation " + r + " -> " + best);
                                lockFrom(d0, d1);
                                rotation = best;
                                relocks++;
                                pendingRot = -1;
                                pendingCount = 0;
                            }
                        } else {
                            pendingRot = -1;
                            pendingCount = 0;
                        }
                        accFrames = 0;
                        java.util.Arrays.fill(d0, 0);
                        java.util.Arrays.fill(d1, 0);
                    }
                    int s = (q - rotation) & 3;  // canonical slot
                    sf[s] = a;
                    sf[4 + s] = b;
                    if (s == 3) {
                        if (outN < out[0].length) {
                            for (int k = 0; k < 8; k++) {
                                short v = sf[CH[k] * 4 + SLOT[k]];
                                out[k][outN] = v;
                                lvlAcc[k] += (double) v * v;
                            }
                            outN++;
                            lvlCount++;
                        }
                    }
                }
                if (lvlCount >= SLOT_RATE / 2) {
                    synchronized (levels) {
                        for (int k = 0; k < 8; k++) {
                            levels[k] = (float) Math.sqrt(lvlAcc[k] / lvlCount);
                            lvlAcc[k] = 0;
                        }
                    }
                    lvlCount = 0;
                }
                if (outN > 0) {
                    for (Listener l : listeners) {
                        try {
                            l.onBlock(out, outN);
                        } catch (Throwable t) {
                            Log.w(TAG, "listener failed", t);
                        }
                    }
                }
            }
        } finally {
            MultiMicNative.close(fd);
            running = false;
            rotation = -1;
            Log.i(TAG, "capture stop, frames=" + framesRead + " overruns=" + overruns
                    + " relocks=" + relocks);
        }
    }

    private static int bestRotation(long[] d1) {
        int best = 0;
        for (int i = 1; i < 4; i++) if (d1[i] < d1[best]) best = i;
        return best;
    }

    /** d1[r] / smallest other d1: low = clear lock. */
    private static float smoothRatio(long[] d1, int r) {
        long other = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) if (i != r) other = Math.min(other, d1[i]);
        return other <= 0 ? 1f : (float) d1[r] / other;
    }

    /** Sets confidence and crossCheckOk from the statistics, returns the rotation. */
    private int lockFrom(long[] d0, long[] d1) {
        int r = bestRotation(d1);
        confidence = 1f - Math.min(1f, smoothRatio(d1, r));
        // channel 0: the reference slots (canonical 1 and 3) must be smoother than the
        // mic slots (canonical 0 and 2).
        long refs = d0[(r + 1) & 3] + d0[(r + 3) & 3];
        long mics = d0[r] + d0[(r + 2) & 3];
        crossCheckOk = refs < mics;
        return r;
    }

    // -- status -------------------------------------------------------------------------

    public String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"running\":").append(running && thread != null && thread.isAlive())
                .append(",\"listeners\":").append(listeners.size())
                .append(",\"device\":\"").append(DEVICE).append('"')
                .append(",\"raw_rate\":").append(RAW_RATE)
                .append(",\"slot_rate\":").append(SLOT_RATE)
                .append(",\"locked\":").append(rotation >= 0)
                .append(",\"rotation\":").append(rotation)
                .append(",\"confidence\":").append(String.format(java.util.Locale.US, "%.2f", confidence))
                .append(",\"cross_check\":").append(crossCheckOk)
                .append(",\"relocks\":").append(relocks)
                .append(",\"overruns\":").append(overruns)
                .append(",\"frames\":").append(framesRead)
                .append(",\"channels\":[");
        for (int k = 0; k < 8; k++) {
            if (k > 0) sb.append(',');
            sb.append("{\"name\":\"").append(NAMES[k]).append("\",\"raw\":\"c").append(CH[k])
                    .append('s').append(SLOT[k]).append("\",\"rms\":");
            synchronized (levels) {
                sb.append(Math.round(levels[k]));
            }
            sb.append('}');
        }
        sb.append("],\"error\":");
        String e = lastError;
        sb.append(e == null ? "null" : "\"" + MainActivity.jsonSafe(e) + "\"");
        sb.append('}');
        return sb.toString();
    }

    // -- HTTP: /stream/mic?channels=5|8 and /stream/mic/record ---------------------------

    private static final String BOUNDARY = "opensdktestpanelaudio";

    static int parseChannels(Map<String, String> query) {
        String c = query.get("channels");
        return "8".equals(c) ? 8 : 5;
    }

    /** Multipart stream of WAV chunks (500 ms each), same framing as /stream/mic. */
    public void handleStream(Socket socket, Map<String, String> query) throws IOException {
        final int channels = parseChannels(query);
        final int chunkSamples = SLOT_RATE / 2;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        Listener l = new Listener() {
            final short[][] buf = new short[8][chunkSamples];
            int fill = 0;

            @Override
            public void onBlock(short[][] ch8, int n) {
                int i = 0;
                while (i < n) {
                    int take = Math.min(n - i, chunkSamples - fill);
                    for (int k = 0; k < channels; k++) System.arraycopy(ch8[k], i, buf[k], fill, take);
                    fill += take;
                    i += take;
                    if (fill == chunkSamples) {
                        byte[] wav = wav(buf, channels, chunkSamples);
                        if (!queue.offer(wav)) {
                            queue.poll();
                            queue.offer(wav);
                        }
                        fill = 0;
                    }
                }
            }
        };
        OutputStream out = socket.getOutputStream();
        String err = subscribe(l, 3000);
        if (err != null) {
            writeError(out, err);
            return;
        }
        try {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/mixed; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "X-Mic-Channels: " + channelNames(channels) + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            while (true) {
                byte[] wav;
                try {
                    wav = queue.poll(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (wav == null) {
                    Log.w(TAG, "stream: no data for 3 s, closing");
                    break;
                }
                out.write(("--" + BOUNDARY + "\r\nContent-Type: audio/wav\r\nContent-Length: "
                        + wav.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(wav);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            unsubscribe(l);
        }
    }

    /** One WAV file of `seconds` (1 to 60, default 5) with 5 or 8 channels at 16 kHz. */
    public void handleRecord(Socket socket, Map<String, String> query) throws IOException {
        final int channels = parseChannels(query);
        int seconds = 5;
        try {
            if (query.get("seconds") != null) seconds = Integer.parseInt(query.get("seconds").trim());
        } catch (NumberFormatException ignored) {
            seconds = -1;
        }
        OutputStream out = socket.getOutputStream();
        if (seconds < 1 || seconds > 60) {
            writeError(out, 400, "seconds must be 1 to 60");
            return;
        }
        final int total = seconds * SLOT_RATE;
        final short[][] buf = new short[8][total];
        final int[] fill = {0};
        final Object done = new Object();
        Listener l = new Listener() {
            @Override
            public void onBlock(short[][] ch8, int n) {
                synchronized (done) {
                    int take = Math.min(n, total - fill[0]);
                    if (take <= 0) return;
                    for (int k = 0; k < channels; k++) System.arraycopy(ch8[k], 0, buf[k], fill[0], take);
                    fill[0] += take;
                    if (fill[0] >= total) done.notifyAll();
                }
            }
        };
        String err = subscribe(l, 3000);
        if (err != null) {
            writeError(out, err);
            return;
        }
        int got;
        try {
            long deadline = System.currentTimeMillis() + seconds * 1000L + 3000;
            synchronized (done) {
                while (fill[0] < total) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    try {
                        done.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                got = fill[0];
            }
        } finally {
            unsubscribe(l);
        }
        if (got < total) {
            writeError(out, "capture stalled: " + got + " of " + total + " samples");
            return;
        }
        byte[] wav = wav(buf, channels, total);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wav.length
                + "\r\nContent-Disposition: attachment; filename=\"mic" + channels + "ch.wav\""
                + "\r\nX-Mic-Channels: " + channelNames(channels)
                + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(wav);
        out.flush();
    }

    static String channelNames(int channels) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < channels; k++) {
            if (k > 0) sb.append(',');
            sb.append(NAMES[k]);
        }
        return sb.toString();
    }

    private static void writeError(OutputStream out, String msg) throws IOException {
        writeError(out, 503, msg);
    }

    private static void writeError(OutputStream out, int status, String msg) throws IOException {
        byte[] body = ("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(msg) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String reason = status == 400 ? "Bad Request" : "Service Unavailable";
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + body.length + "\r\nAccess-Control-Allow-Origin: *"
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Interleaved 16-bit PCM WAV (format 1) with `channels` channels at 16 kHz. */
    static byte[] wav(short[][] ch, int channels, int n) {
        int dataLen = n * channels * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(SLOT_RATE)
                .putInt(SLOT_RATE * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < channels; k++) bb.putShort(ch[k][i]);
        }
        return bb.array();
    }
}
