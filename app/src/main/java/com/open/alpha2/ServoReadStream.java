package com.open.alpha2;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.Arrays;
import java.util.Map;

/**
 * Servo read stream (alpha2-rl#3): the app reads a set of servos with chest cmd 0x17 at a fixed
 * rate in its own thread and sends one {@code servo_reads} event on {@code /ws} for each cycle.
 *
 * <p>Why: one read over HTTP costs about 45 ms (request, chest reply, {@code chest_rcv} on
 * {@code /ws}), so a 20 Hz policy loop on a PC cannot read and move in 50 ms. With this stream
 * the PC uses the newest pushed reads and does not wait for a reply.
 *
 * <p>Safety:
 * <ul>
 *   <li>The stream sends only cmd 0x17 read frames ({@code F8 8F 08 00 00 17 id sum ED}), the
 *       same bytes as {@code servo/back-angle}. It never moves a servo and never writes.</li>
 *   <li>It stops by itself when no client renews it: {@code servo/stream/start} sets a deadline
 *       ({@code ttl_ms}, default 5000). A client calls start again to renew.</li>
 *   <li>It pauses while a chest or servo firmware upgrade runs, and while the chest port is not
 *       open.</li>
 *   <li>It writes one frame batch for each cycle through {@code DirectSerialPort.sendRaw}, which is
 *       synchronized. Thus other chest frames (app commands, heartbeat handling) go out between
 *       two batches and never inside a frame.</li>
 * </ul>
 *
 * <p>The replies of the stream are not sent as {@code chest_rcv} events while {@code raw=false}
 * (the default): at 20 Hz and 8 servos that would be 160 events each second for each WebSocket
 * client. ChestQuery still sees each reply.
 *
 * <p>Event data: {@code {"seq":N,"t_ms":<elapsedRealtime ms>,"read_ms":<first send to last reply>,
 * "ids":[...],"angles":[... or null],"accel":[x,y,z] or null,"accel_age_ms":N or null}}.
 */
public final class ServoReadStream {
    private static final String TAG = "ServoReadStream";

    public static final int HZ_MIN = 1;
    public static final int HZ_MAX = 25;
    public static final int HZ_DEFAULT = 20;
    public static final int TTL_MIN_MS = 500;
    public static final int TTL_MAX_MS = 10000;
    public static final int TTL_DEFAULT_MS = 5000;
    public static final int MAX_IDS = 22;
    /** At most this many reads each second (servo bus and chest link load). */
    public static final int MAX_READS_PER_S = 200;
    /** The 8 kg servos of the Alpha 2 that answer cmd 0x17 (alpha2_rl READABLE_SERVOS). */
    public static final int[] DEFAULT_IDS = {2, 3, 5, 6, 17, 18, 19, 20};
    private static final byte CMD_READ_BACK = 0x17;

    /** True while a chest or servo firmware upgrade runs: the stream sends nothing then. */
    public interface BusyCheck {
        boolean busy();
    }

    private static volatile ServoReadStream sInstance;

    public static void init(Context context, BusyCheck busy) {
        if (sInstance == null) {
            sInstance = new ServoReadStream(context.getApplicationContext(), busy);
        }
    }

    public static ServoReadStream get() {
        return sInstance;
    }

    /** The serial reader thread calls this for each chest frame (payload[0] = cmd).
     *  Returns true when the frame is a reply to the stream and must not go out as chest_rcv. */
    public static boolean onChestPayload(byte[] payload) {
        ServoReadStream s = sInstance;
        return s != null && s.onPayload(payload);
    }

    public static void shutdown() {
        ServoReadStream s = sInstance;
        if (s != null) s.stop("app shutdown");
    }

    // -- state -------------------------------------------------------------------------------

    private final Context appContext;
    private final BusyCheck busyCheck;
    private final Object lock = new Object();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile long deadlineMs;
    private volatile int[] ids = DEFAULT_IDS;
    private volatile int hz = HZ_DEFAULT;
    private volatile int ttlMs = TTL_DEFAULT_MS;
    private volatile boolean raw = false;

    // One cycle: the reply slots. Guarded by "lock".
    private int[] cycleIds = new int[0];
    private Integer[] cycleAngles = new Integer[0];
    private int cycleGot = 0;
    private long cycleLastReplyNs = 0;

    // Counters for servo/stream/status.
    private volatile long seq = 0;
    private volatile long cycles = 0;
    private volatile long completeCycles = 0;
    private volatile long missedReads = 0;
    private volatile long pausedCycles = 0;
    private volatile long lateCycles = 0;
    private volatile int lastReadMs = -1;
    private volatile int maxReadMs = -1;
    private volatile String stopReason = "";

    private ServoReadStream(Context appContext, BusyCheck busyCheck) {
        this.appContext = appContext;
        this.busyCheck = busyCheck;
    }

    // -- API ---------------------------------------------------------------------------------

    /** servo/stream/start, servo/stream/stop, servo/stream/status. */
    public static HttpServer.ApiResponse api(String path, Map<String, String> query) {
        ServoReadStream s = sInstance;
        if (s == null) {
            return HttpServer.ApiResponse.error("servo stream not ready");
        }
        switch (path) {
            case "servo/stream/start":
                return s.startResponse(query);
            case "servo/stream/stop":
                s.stop("stop request");
                return HttpServer.ApiResponse.ok(s.statusJson());
            default:
                return HttpServer.ApiResponse.ok(s.statusJson());
        }
    }

    private HttpServer.ApiResponse startResponse(Map<String, String> query) {
        int newHz = ApiValidator.optionalIntRange(query, "hz", HZ_MIN, HZ_MAX, HZ_DEFAULT);
        int newTtl = ApiValidator.optionalIntRange(query, "ttl_ms", TTL_MIN_MS, TTL_MAX_MS, TTL_DEFAULT_MS);
        boolean newRaw = ApiValidator.optionalBoolean(query, "raw", false);
        int[] newIds = ApiValidator.optionalServoIdList(query, "ids", DEFAULT_IDS, MAX_IDS);
        if (newIds.length * newHz > MAX_READS_PER_S) {
            throw new IllegalArgumentException("ids x hz must be at most " + MAX_READS_PER_S
                    + " reads per second, got " + newIds.length + " x " + newHz);
        }
        synchronized (lock) {
            boolean changed = !Arrays.equals(newIds, ids) || newHz != hz;
            ids = newIds;
            hz = newHz;
            ttlMs = newTtl;
            raw = newRaw;
            deadlineMs = SystemClock.elapsedRealtime() + newTtl;
            if (!running) {
                running = true;
                stopReason = "";
                thread = new Thread(new Runnable() {
                    @Override public void run() { loop(); }
                }, "ServoReadStream");
                thread.setDaemon(true);
                thread.start();
                Log.i(TAG, "start ids=" + Arrays.toString(newIds) + " hz=" + newHz + " ttl_ms=" + newTtl);
            } else if (changed) {
                Log.i(TAG, "change ids=" + Arrays.toString(newIds) + " hz=" + newHz);
            }
        }
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public void stop(String why) {
        Thread t;
        synchronized (lock) {
            if (!running) return;
            running = false;
            stopReason = why;
            t = thread;
            thread = null;
            cycleIds = new int[0];
        }
        Log.i(TAG, "stop: " + why);
        if (t != null) t.interrupt();
        EventBus.get().publish("servo_stream", "{\"running\":false,\"reason\":" + JsonUtil.quote(why) + "}");
    }

    String statusJson() {
        StringBuilder sb = new StringBuilder(256);
        long left = running ? Math.max(0, deadlineMs - SystemClock.elapsedRealtime()) : 0;
        sb.append("{\"ok\":true,\"running\":").append(running)
                .append(",\"ids\":").append(Arrays.toString(ids).replace(" ", ""))
                .append(",\"hz\":").append(hz)
                .append(",\"ttl_ms\":").append(ttlMs)
                .append(",\"ttl_left_ms\":").append(left)
                .append(",\"raw\":").append(raw)
                .append(",\"seq\":").append(seq)
                .append(",\"cycles\":").append(cycles)
                .append(",\"complete_cycles\":").append(completeCycles)
                .append(",\"missed_reads\":").append(missedReads)
                .append(",\"paused_cycles\":").append(pausedCycles)
                .append(",\"late_cycles\":").append(lateCycles)
                .append(",\"last_read_ms\":").append(lastReadMs)
                .append(",\"max_read_ms\":").append(maxReadMs)
                .append(",\"stop_reason\":").append(JsonUtil.quote(stopReason))
                .append('}');
        return sb.toString();
    }

    // -- the reply side (serial reader thread) -------------------------------------------------

    private boolean onPayload(byte[] payload) {
        // Reply: [0x17, status, id, hi, lo]. status 0 = OK.
        if (!running || payload == null || payload.length < 5 || payload[0] != CMD_READ_BACK) return false;
        int id = payload[2] & 0xFF;
        synchronized (lock) {
            for (int i = 0; i < cycleIds.length; i++) {
                if (cycleIds[i] != id) continue;
                if (payload[1] == 0 && cycleAngles[i] == null) {
                    cycleAngles[i] = ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF);
                    cycleGot++;
                    cycleLastReplyNs = System.nanoTime();
                    if (cycleGot == cycleIds.length) lock.notifyAll();
                }
                return !raw;
            }
        }
        return false;
    }

    // -- the read loop (own thread) ------------------------------------------------------------

    private void loop() {
        long next = SystemClock.elapsedRealtime();
        try {
            while (alive()) {
                long now = SystemClock.elapsedRealtime();
                if (now > deadlineMs) {
                    stop("ttl: no renew in " + ttlMs + " ms");
                    return;
                }
                int period = 1000 / hz;
                if (busyCheck != null && busyCheck.busy() || !chestAvailable()) {
                    pausedCycles++;
                } else {
                    cycle(period);
                }
                next += period;
                long wait = next - SystemClock.elapsedRealtime();
                if (wait < 0) {
                    // Late: do not run the next cycles faster to catch up.
                    lateCycles++;
                    next = SystemClock.elapsedRealtime();
                    wait = 0;
                }
                if (wait > 0) Thread.sleep(wait);
            }
        } catch (InterruptedException e) {
            // stop() interrupts the thread.
        } catch (Throwable t) {
            Log.w(TAG, "loop failed", t);
            stop("error: " + t.getClass().getSimpleName());
        }
    }

    /** True while this thread is the live stream thread (a stop and a quick start make a new one). */
    private boolean alive() {
        return running && thread == Thread.currentThread();
    }

    private boolean chestAvailable() {
        try {
            return HardwareDirectManager.get(appContext).chest().isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private void cycle(int periodMs) throws InterruptedException {
        int[] want = ids;
        byte[] batch = new byte[9 * want.length];
        for (int i = 0; i < want.length; i++) {
            int id = want[i];
            int o = 9 * i;
            batch[o] = (byte) 0xF8;
            batch[o + 1] = (byte) 0x8F;
            batch[o + 2] = 0x08;
            batch[o + 3] = 0x00;
            batch[o + 4] = 0x00;
            batch[o + 5] = CMD_READ_BACK;
            batch[o + 6] = (byte) id;
            batch[o + 7] = (byte) ((8 + CMD_READ_BACK + id) & 0xFF);
            batch[o + 8] = (byte) 0xED;
        }
        Integer[] got;
        long t0;
        long lastNs;
        synchronized (lock) {
            cycleIds = want;
            cycleAngles = new Integer[want.length];
            cycleGot = 0;
            cycleLastReplyNs = 0;
        }
        t0 = System.nanoTime();
        boolean sent = HardwareDirectManager.get(appContext).chest().sendRaw(batch);
        // Wait for all replies, at most the period minus 10 ms (the rest is for the event).
        long waitNs = Math.max(5, periodMs - 10) * 1_000_000L;
        long endNs = t0 + waitNs;
        synchronized (lock) {
            if (sent) {
                while (cycleGot < want.length && alive()) {
                    long leftNs = endNs - System.nanoTime();
                    if (leftNs <= 0) break;
                    lock.wait(Math.max(1, leftNs / 1_000_000L));
                }
            }
            got = cycleAngles;
            lastNs = cycleLastReplyNs;
            // Replies after this point do not count for this cycle (no request id in the protocol:
            // a late reply can count for the next cycle, at most one period old).
            cycleIds = new int[0];
        }
        if (!alive()) return;
        cycles++;
        int missing = 0;
        for (Integer v : got) if (v == null) missing++;
        missedReads += missing;
        if (missing == 0) completeCycles++;
        int readMs = lastNs == 0 ? -1 : (int) ((lastNs - t0) / 1_000_000L);
        if (missing == 0) {
            lastReadMs = readMs;
            if (readMs > maxReadMs) maxReadMs = readMs;
        }
        long s = ++seq;
        StringBuilder sb = new StringBuilder(200);
        sb.append("{\"seq\":").append(s)
                .append(",\"t_ms\":").append(SystemClock.elapsedRealtime())
                .append(",\"read_ms\":").append(readMs)
                .append(",\"ids\":").append(Arrays.toString(want).replace(" ", ""))
                .append(",\"angles\":[");
        for (int i = 0; i < got.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(got[i] == null ? "null" : String.valueOf(got[i]));
        }
        sb.append(']');
        float[] acc = DeviceStatus.latestAccel();
        long accAt = DeviceStatus.latestAccelAtMs();
        if (acc != null) {
            sb.append(",\"accel\":[").append(acc[0]).append(',').append(acc[1]).append(',').append(acc[2])
                    .append("],\"accel_age_ms\":").append(SystemClock.elapsedRealtime() - accAt);
        } else {
            sb.append(",\"accel\":null,\"accel_age_ms\":null");
        }
        sb.append('}');
        EventBus.get().publish("servo_reads", sb.toString());
    }
}
