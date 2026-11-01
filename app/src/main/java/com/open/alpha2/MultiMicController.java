package com.open.alpha2;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <p>Robustness (alpha2-brain#27, #28): the native read waits at most
 * {@link #READ_TIMEOUT_MS} (poll). After {@link #MAX_TIMEOUTS} timeouts in a row the capture
 * stops, closes the device and sets the error. A stream client that does not read for
 * {@link #WRITE_STALL_MS} loses its socket. Only one record runs at a time. The slot lock and
 * the de-interleave are in the pure Java class {@link MicSlots}.
 */
public final class MultiMicController {
    private static final String TAG = "MultiMic";
    static final String DEVICE = "/dev/snd/pcmC0D0c";
    static final int RAW_RATE = MicSlots.RAW_RATE;
    static final int RAW_CHANNELS = 2;
    static final int SLOT_RATE = MicSlots.SLOT_RATE;
    private static final int PERIOD_FRAMES = 1024;  // 16 ms at 64 kHz
    private static final int PERIODS = 8;
    /** One native read waits at most this long for data (poll). */
    static final int READ_TIMEOUT_MS = 200;
    /** The capture stops after this many read timeouts in a row (2 s without data). */
    static final int MAX_TIMEOUTS = 10;
    /** A stream or record client that takes no data for this long loses its socket. */
    static final long WRITE_STALL_MS = 5000;
    private static final int ETIMEDOUT = 110;
    private static final int EAGAIN = 11;
    private static final int EPIPE = 32;

    /** Canonical order: names (see MicSlots). */
    public static final String[] NAMES = MicSlots.NAMES;

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
    private volatile boolean staleThread;
    private volatile MicSlots slots = new MicSlots();
    private volatile int overruns;
    private volatile int timeouts;
    private volatile long framesRead;
    private final float[] levels = new float[8];
    /** One /stream/mic/record at a time (alpha2-brain#28). */
    private final AtomicBoolean recording = new AtomicBoolean(false);

    private MultiMicController() {}

    // -- start / stop ----------------------------------------------------------------

    /** Adds a listener and starts the capture if needed. Returns null or an error. */
    public String subscribe(Listener l, long timeoutMs) {
        String err = MultiMicNative.ensureLoaded();
        if (err != null) return err;
        synchronized (stateLock) {
            if (thread != null && thread.isAlive() && !running) {
                // The old capture thread is on its way out (one read waits at most 200 ms).
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive()) {
                    staleThread = true;
                    lastError = "the old capture thread did not stop: the 5-mic card is still open";
                    Log.w(TAG, lastError);
                    return lastError;
                }
            }
            staleThread = false;
            listeners.add(l);
            if (thread == null || !thread.isAlive()) {
                lastError = null;
                slots = new MicSlots();
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
            if (slots.rotation >= 0) return null;
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
        final MicSlots ms = slots;
        short[] raw = new short[PERIOD_FRAMES * RAW_CHANNELS];
        short[][] out = new short[8][PERIOD_FRAMES / 4 + 4];
        double[] lvlAcc = new double[8];
        int lvlCount = 0;
        int timeoutsInRow = 0;
        framesRead = 0;
        try {
            while (running) {
                int n = MultiMicNative.read(fd, raw, PERIOD_FRAMES, RAW_CHANNELS, READ_TIMEOUT_MS);
                if (n == -EPIPE) { // overrun, the slot phase can change
                    overruns++;
                    ms.overrun();
                    continue;
                }
                if (n == -ETIMEDOUT || n == -EAGAIN) {
                    timeouts++;
                    if (++timeoutsInRow >= MAX_TIMEOUTS) {
                        lastError = "no data from the 5-mic card for "
                                + (MAX_TIMEOUTS * READ_TIMEOUT_MS) + " ms";
                        Log.w(TAG, lastError);
                        break;
                    }
                    continue;
                }
                if (n < 0) {
                    lastError = "read failed: errno " + (-n);
                    Log.w(TAG, lastError);
                    break;
                }
                timeoutsInRow = 0;
                framesRead += n;
                int before = ms.rotation;
                int outN = ms.process(raw, n, out);
                if (ms.rotation != before && ms.lastLockEvent != null) Log.i(TAG, ms.lastLockEvent);
                for (int i = 0; i < outN; i++) {
                    for (int k = 0; k < 8; k++) lvlAcc[k] += (double) out[k][i] * out[k][i];
                }
                lvlCount += outN;
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
            ms.rotation = -1;
            Log.i(TAG, "capture stop, frames=" + framesRead + " overruns=" + overruns
                    + " timeouts=" + timeouts + " relocks=" + ms.relocks);
        }
    }

    // -- status -------------------------------------------------------------------------

    public String statusJson() {
        MicSlots ms = slots;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"running\":").append(running && thread != null && thread.isAlive())
                .append(",\"listeners\":").append(listeners.size())
                .append(",\"device\":\"").append(DEVICE).append('"')
                .append(",\"raw_rate\":").append(RAW_RATE)
                .append(",\"slot_rate\":").append(SLOT_RATE)
                .append(",\"locked\":").append(ms.rotation >= 0)
                .append(",\"rotation\":").append(ms.rotation)
                .append(",\"confidence\":").append(String.format(java.util.Locale.US, "%.2f", ms.confidence))
                .append(",\"cross_check\":").append(ms.crossCheckOk)
                .append(",\"relocks\":").append(ms.relocks)
                .append(",\"overruns\":").append(overruns)
                .append(",\"timeouts\":").append(timeouts)
                .append(",\"stale_thread\":").append(staleThread)
                .append(",\"recording\":").append(recording.get())
                .append(",\"frames\":").append(framesRead)
                .append(",\"channels\":[");
        for (int k = 0; k < 8; k++) {
            if (k > 0) sb.append(',');
            sb.append("{\"name\":\"").append(NAMES[k]).append("\",\"raw\":\"c").append(MicSlots.CH[k])
                    .append('s').append(MicSlots.SLOT[k]).append("\",\"rms\":");
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

    /**
     * Closes the socket when one write does not end in {@link #WRITE_STALL_MS}: a client that
     * stops to read but keeps the TCP connection open (alpha2-brain#27). The blocked write then
     * fails, and the caller removes its listener.
     */
    static final class StallGuard {
        private static final int CHUNK = 32 * 1024;
        private final Socket socket;
        private final OutputStream out;
        private final long limitMs;
        private volatile long busySince;
        private volatile boolean done;
        private volatile boolean stalled;
        private final Thread watcher;

        StallGuard(Socket socket, OutputStream out, long limitMs) {
            this.socket = socket;
            this.out = out;
            this.limitMs = limitMs;
            watcher = new Thread(new Runnable() {
                @Override public void run() { watch(); }
            }, "MicWriteGuard");
            watcher.setDaemon(true);
            watcher.start();
        }

        private void watch() {
            while (!done) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
                long b = busySince;
                if (b != 0 && System.currentTimeMillis() - b > limitMs) {
                    stalled = true;
                    Log.w(TAG, "client takes no data for " + limitMs + " ms: closing the socket");
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
            }
        }

        void write(byte[] b) throws IOException {
            for (int off = 0; off < b.length; off += CHUNK) {
                busySince = System.currentTimeMillis();
                try {
                    out.write(b, off, Math.min(CHUNK, b.length - off));
                } finally {
                    busySince = 0;
                }
            }
        }

        void flush() throws IOException {
            busySince = System.currentTimeMillis();
            try {
                out.flush();
            } finally {
                busySince = 0;
            }
        }

        boolean stalled() { return stalled; }

        void close() {
            done = true;
            watcher.interrupt();
        }
    }

    /** Multipart stream of WAV chunks (500 ms each), same framing as /stream/mic. */
    public void handleStream(Socket socket, Map<String, String> query) throws IOException {
        final int channels = MicSlots.parseChannels(query.get("channels"), 5, 5, 8);
        OutputStream out = socket.getOutputStream();
        if (channels < 0) {
            writeError(out, 400, "channels must be 1, 5 or 8");
            return;
        }
        final int chunkSamples = SLOT_RATE / 2;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        Listener l = new Listener() {
            final short[][] buf = new short[channels][chunkSamples];
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
                        byte[] wav = MicSlots.wav(buf, channels, chunkSamples);
                        if (!queue.offer(wav)) {
                            queue.poll();
                            queue.offer(wav);
                        }
                        fill = 0;
                    }
                }
            }
        };
        String err = subscribe(l, 3000);
        if (err != null) {
            writeError(out, err);
            return;
        }
        StallGuard g = new StallGuard(socket, out, WRITE_STALL_MS);
        try {
            g.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/mixed; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "X-Mic-Channels: " + MicSlots.channelNames(channels) + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            g.flush();
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
                g.write(("--" + BOUNDARY + "\r\nContent-Type: audio/wav\r\nContent-Length: "
                        + wav.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                g.write(wav);
                g.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                g.flush();
            }
        } catch (IOException e) {
            if (!g.stalled()) throw e;
        } finally {
            g.close();
            unsubscribe(l);
        }
    }

    /** One WAV file of `seconds` (1 to 60, default 5) with 5 or 8 channels at 16 kHz.
     *  One record at a time: a second request gives 503 (alpha2-brain#28). */
    public void handleRecord(Socket socket, Map<String, String> query) throws IOException {
        final int channels = MicSlots.parseChannels(query.get("channels"), 5, 5, 8);
        int seconds = 5;
        try {
            if (query.get("seconds") != null) seconds = Integer.parseInt(query.get("seconds").trim());
        } catch (NumberFormatException ignored) {
            seconds = -1;
        }
        OutputStream out = socket.getOutputStream();
        if (channels < 0) {
            writeError(out, 400, "channels must be 5 or 8");
            return;
        }
        if (seconds < 1 || seconds > 60) {
            writeError(out, 400, "seconds must be 1 to 60");
            return;
        }
        if (!recording.compareAndSet(false, true)) {
            writeError(out, 503, "another 5-mic record runs: one record at a time");
            return;
        }
        try {
            record(socket, out, channels, seconds);
        } finally {
            recording.set(false);
        }
    }

    private void record(Socket socket, OutputStream out, final int channels, int seconds) throws IOException {
        final int total = seconds * SLOT_RATE;
        // Only the channels that the client wants (at most 60 s x 8 x 2 bytes = 15.4 MB).
        final short[][] buf = new short[channels][total];
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
            String why = lastError;
            writeError(out, "capture stalled: " + got + " of " + total + " samples"
                    + (why != null ? " (" + why + ")" : ""));
            return;
        }
        byte[] header = MicSlots.wavHeader(channels, total);
        StallGuard g = new StallGuard(socket, out, WRITE_STALL_MS);
        try {
            g.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: "
                    + (header.length + total * channels * 2)
                    + "\r\nContent-Disposition: attachment; filename=\"mic" + channels + "ch.wav\""
                    + "\r\nX-Mic-Channels: " + MicSlots.channelNames(channels)
                    + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            g.write(header);
            // The WAV data in parts: no second copy of the full record in memory.
            int part = SLOT_RATE / 4;
            for (int i = 0; i < total; i += part) {
                g.write(MicSlots.pcm(buf, channels, i, Math.min(part, total - i)));
            }
            g.flush();
        } catch (IOException e) {
            if (!g.stalled()) throw e;
        } finally {
            g.close();
        }
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
}
