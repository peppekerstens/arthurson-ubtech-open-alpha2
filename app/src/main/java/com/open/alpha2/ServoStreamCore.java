package com.open.alpha2;

import java.util.Arrays;

/**
 * Pure Java core of the servo read stream (alpha2-rl#3). {@link ServoReadStream} is the
 * Android wrapper: it gives the clock, the chest port, the event bus and the busy check
 * through {@link Env}. The unit test (ServoStreamCoreTest) uses a fake {@link Env}.
 *
 * <p>Rules:
 * <ul>
 *   <li>The stream sends only cmd 0x17 read frames {@code F8 8F 08 00 00 17 id sum ED}.</li>
 *   <li>Only the servos that answer cmd 0x17 are allowed: {@link #READABLE_IDS}. The 20 kg
 *       servos (1, 4, 7 to 16) do not answer (alpha2-brain#29).</li>
 *   <li>The busy check and the send are one step under {@code gate}
 *       (alpha2-brain#26). A firmware upgrade sets its busy flag, then calls
 *       {@link #haltForUpgrade}. That call takes {@code gate}, so a send that runs ends first,
 *       and each later cycle sees the busy flag. Then it stops the stream and waits for the
 *       thread to end. While the flag is set, {@link #start} refuses with
 *       {@link BusyException} (HTTP 409).</li>
 *   <li>Each thread has a generation number. Each cycle has its own {@link Cycle} object.
 *       An old thread after a stop and a quick start cannot clear or fill the cycle of the
 *       new thread, and it sends no more batches (alpha2-brain#29).</li>
 *   <li>A cmd 0x17 reply is hidden from {@code chest_rcv} only when its id is in the running
 *       cycle and no other requester asked for that id in the last
 *       {@link #EXTERNAL_WINDOW_MS} ms ({@link #noteExternalRead}, alpha2-brain#29).</li>
 * </ul>
 */
final class ServoStreamCore {

    /** The environment of the stream. All methods must be thread safe. */
    interface Env {
        long nowMs();
        boolean portAvailable();
        boolean send(byte[] batch);
        /** True while a chest or servo firmware upgrade runs. */
        boolean busy();
        /** The accel part of the event, starting with a comma. */
        String accelJson();
        void publish(String type, String json);
        void log(String msg);
    }

    /**
     * The time source, the waits and the read thread of the stream. {@link #REAL_CLOCK} is the
     * normal clock. The unit test gives a fake clock with a fixed schedule, so the timing
     * tests do not depend on the host (alpha2-brain#46).
     */
    interface Clock {
        long nanoTime();
        void sleepMs(long ms) throws InterruptedException;
        /** Waits on {@code lock} (the caller holds it) for at most {@code ms} ms. */
        void await(Object lock, long ms) throws InterruptedException;
        /** Starts the read thread. Returns the thread, or null when no real thread runs. */
        Thread startThread(Runnable r);
    }

    static final Clock REAL_CLOCK = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }

        @Override public void sleepMs(long ms) throws InterruptedException { Thread.sleep(ms); }

        @Override public void await(Object lock, long ms) throws InterruptedException { lock.wait(ms); }

        @Override public Thread startThread(Runnable r) {
            Thread t = new Thread(r, "ServoReadStream");
            t.setDaemon(true);
            t.start();
            return t;
        }
    };

    /** start() while a firmware upgrade runs (HTTP 409). */
    static final class BusyException extends RuntimeException {
        BusyException(String msg) { super(msg); }
    }

    static final byte CMD_READ_BACK = 0x17;
    /** The 8 kg servos that answer cmd 0x17 (alpha2_rl READABLE_SERVOS plus 21 and 22). */
    static final int[] READABLE_IDS = {2, 3, 5, 6, 17, 18, 19, 20, 21, 22};
    /** A reply for an id that another requester asked for in this time goes out as chest_rcv. */
    static final long EXTERNAL_WINDOW_MS = 1000;

    /**
     * The time of the cycle after the reply wait, for the event (ms). Robot session
     * 2026-11-01: 8 reads take 33 to 40 ms on the chest link. The old wait (period minus
     * 10 ms, thus 40 ms at 20 Hz) cut the last replies (servos 17 to 20) in about 13 % of
     * the cycles: 6 to 7 % missed reads. The publish of the event takes less than 1 ms.
     */
    static final int EVENT_MARGIN_MS = 2;

    /**
     * The longest wait for the replies of one cycle (ms), from the start of the cycle
     * (before the busy check and the send). The send can wait for another chest write
     * (sendRaw is synchronized), so the wait does not start at the send (alpha2-brain#45).
     */
    static int replyWaitMs(int periodMs) {
        return Math.max(5, periodMs - EVENT_MARGIN_MS);
    }

    /**
     * A cmd 0x17 reply has no cycle number. A reply for an id that the last cycle missed,
     * earlier than this time after the send of the new cycle, is a late reply of the last
     * cycle (alpha2-brain#45): the stream drops it and counts it in late_replies. A new
     * reply cannot come so early: 8 reads take 33 to 40 ms, thus about 4 ms or more for one.
     */
    static final long LATE_REPLY_GUARD_MS = 3;

    /** True when a reply is a late reply of the last cycle (see LATE_REPLY_GUARD_MS). */
    static boolean lateReply(boolean missedInLastCycle, long replyNs, long sendNs) {
        return missedInLastCycle && replyNs - sendNs < LATE_REPLY_GUARD_MS * 1_000_000L;
    }

    static boolean readable(int id) {
        for (int r : READABLE_IDS) if (r == id) return true;
        return false;
    }

    /** Null when all ids answer cmd 0x17, else the reason. */
    static String checkReadableIds(int[] ids) {
        for (int id : ids) {
            if (!readable(id)) {
                return "servo id " + id + " does not answer cmd 0x17 (the 20 kg servos 1, 4 and 7 to 16"
                        + " give only missed reads). Use ids from " + Arrays.toString(READABLE_IDS);
            }
        }
        return null;
    }

    /** The read batch: one 9-byte cmd 0x17 frame for each id. */
    static byte[] batch(int[] ids) {
        byte[] b = new byte[9 * ids.length];
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            int o = 9 * i;
            b[o] = (byte) 0xF8;
            b[o + 1] = (byte) 0x8F;
            b[o + 2] = 0x08;
            b[o + 3] = 0x00;
            b[o + 4] = 0x00;
            b[o + 5] = CMD_READ_BACK;
            b[o + 6] = (byte) id;
            b[o + 7] = (byte) ((8 + CMD_READ_BACK + id) & 0xFF);
            b[o + 8] = (byte) 0xED;
        }
        return b;
    }

    /** The servo ids of the long-form cmd 0x17 frames in raw chest data (for noteExternalRead). */
    static int[] readIdsInFrames(byte[] data) {
        if (data == null) return new int[0];
        int[] out = new int[data.length / 9 + 1];
        int n = 0;
        for (int i = 0; i + 6 < data.length; i++) {
            if ((data[i] & 0xFF) == 0xF8 && (data[i + 1] & 0xFF) == 0x8F && data[i + 5] == CMD_READ_BACK) {
                out[n++] = data[i + 6] & 0xFF;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** The reply slots of one cycle. Guarded by the lock of the core. */
    static final class Cycle {
        final int[] ids;
        final Integer[] angles;
        int got;
        long lastReplyNs;
        /** The ids that the last cycle missed (late replies can come for them). */
        int[] missedBefore = new int[0];
        /**
         * The time when the send returned (Clock.nanoTime). It is set after the send, under
         * the lock: a wait for the send lock thus does not open the late reply guard early
         * (alpha2-brain#46).
         */
        long sendNs;
        /** False until the send returned. A reply for a missed id before that time is late. */
        boolean sendDone;

        Cycle(int[] ids) {
            this.ids = ids;
            this.angles = new Integer[ids.length];
        }

        boolean missedBefore(int id) {
            for (int m : missedBefore) if (m == id) return true;
            return false;
        }

        /** Returns true when the id is in this cycle. Fills the slot on a good first reply. */
        boolean accept(int id, int status, int angle, long ns) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != id) continue;
                if (status == 0 && angles[i] == null) {
                    angles[i] = angle;
                    got++;
                    lastReplyNs = ns;
                }
                return true;
            }
            return false;
        }

        boolean complete() { return got == ids.length; }
    }

    // -- state --------------------------------------------------------------------------------

    private final Env env;
    private final Clock clock;
    /** State, the running cycle and the external read marks. */
    private final Object lock = new Object();
    /** The busy check and the send of one batch (alpha2-brain#26). Order: gate, then lock. */
    private final Object gate = new Object();

    private Thread thread;          // guarded by lock
    private volatile boolean running;
    private volatile int generation; // written under lock
    private volatile long deadlineMs;
    private volatile int[] ids;
    private volatile int hz;
    private volatile int ttlMs;
    private volatile boolean raw;
    private Cycle current;          // guarded by lock
    private final long[] externalReadAt = new long[256]; // guarded by lock

    private volatile long seq, cycles, completeCycles, missedReads, pausedCycles, lateCycles, lateReplies;
    /** Moves that waited for a cycle (move_waits) and moves that went out after the limit (move_forced). */
    private volatile long moveWaits, moveForced;
    /** The ids that the last cycle missed. Read loop thread only. */
    private int[] lastMissed = new int[0];
    private volatile int lastReadMs = -1, maxReadMs = -1;
    private volatile String stopReason = "";

    ServoStreamCore(Env env, int[] defaultIds, int defaultHz, int defaultTtlMs) {
        this(env, REAL_CLOCK, defaultIds, defaultHz, defaultTtlMs);
    }

    ServoStreamCore(Env env, Clock clock, int[] defaultIds, int defaultHz, int defaultTtlMs) {
        this.env = env;
        this.clock = clock;
        this.ids = defaultIds.clone();
        this.hz = defaultHz;
        this.ttlMs = defaultTtlMs;
    }

    boolean isRunning() { return running; }

    long missedReads() { return missedReads; }

    long pausedCycles() { return pausedCycles; }

    long cycles() { return cycles; }

    long lateReplies() { return lateReplies; }

    long lateCycles() { return lateCycles; }

    String stopReason() { return stopReason; }

    long moveWaits() { return moveWaits; }

    long moveForced() { return moveForced; }

    /** A chest write of another requester, for example a move frame. */
    interface Write<T> {
        T run();
    }

    /**
     * alpha2-rl#217: writes a move frame between two read cycles. A cmd 3 frame touches the slot of
     * each servo in the chest, and it can replace a cmd 0x17 read that is still on its way. So the
     * write waits until no cycle waits for replies (at most {@code maxWaitMs}). The next cycle waits
     * for the write at the gate. The write never drops: after the limit it goes out anyway
     * (move_forced). When the stream does not run, no cycle waits and the write starts at once.
     * Order of the locks: gate, then lock (the same as the cycle).
     */
    <T> T writeBetweenCycles(long maxWaitMs, Write<T> w) {
        long limitNs = clock.nanoTime() + Math.max(0, maxWaitMs) * 1_000_000L;
        synchronized (gate) {
            boolean waited = false;
            boolean forced = false;
            synchronized (lock) {
                while (current != null) {
                    long leftNs = limitNs - clock.nanoTime();
                    if (leftNs <= 0) {
                        forced = true;
                        break;
                    }
                    waited = true;
                    try {
                        clock.await(lock, Math.max(1, leftNs / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        forced = true;
                        break;
                    }
                }
            }
            if (waited) moveWaits++;
            if (forced) moveForced++;
            return w.run();
        }
    }

    // -- API --------------------------------------------------------------------------------------

    /** Starts or renews the stream. The caller has checked the values.
     *  Throws BusyException while a firmware upgrade runs. Returns true for a new thread. */
    boolean start(int[] newIds, int newHz, int newTtl, boolean newRaw) {
        if (env.busy()) {
            throw new BusyException("a chest or servo firmware upgrade runs: the servo read stream"
                    + " cannot start (alpha2-brain#26)");
        }
        synchronized (lock) {
            boolean changed = !Arrays.equals(newIds, ids) || newHz != hz;
            ids = newIds.clone();
            hz = newHz;
            ttlMs = newTtl;
            raw = newRaw;
            deadlineMs = env.nowMs() + newTtl;
            if (running) {
                if (changed) env.log("change ids=" + Arrays.toString(newIds) + " hz=" + newHz);
                return false;
            }
            running = true;
            stopReason = "";
            final int gen = ++generation;
            current = null;
            thread = clock.startThread(new Runnable() {
                @Override public void run() { loop(gen); }
            });
            env.log("start ids=" + Arrays.toString(newIds) + " hz=" + newHz + " ttl_ms=" + newTtl);
            return true;
        }
    }

    /** Stops the stream that runs now. */
    void stop(String why) {
        stopGeneration(-1, why);
    }

    /** Stops the stream only when generation {@code gen} still runs (-1: each generation). */
    private void stopGeneration(int gen, String why) {
        Thread t;
        synchronized (lock) {
            if (!running || (gen >= 0 && gen != generation)) return;
            running = false;
            stopReason = why;
            t = thread;
            thread = null;
            current = null;
            lock.notifyAll();
        }
        env.log("stop: " + why);
        if (t != null && t != Thread.currentThread()) t.interrupt();
        env.publish("servo_stream", "{\"running\":false,\"reason\":" + JsonUtil.quote(why) + "}");
    }

    /**
     * For a firmware upgrade (alpha2-brain#26). The caller sets its busy flag first. After
     * this call returns, the stream sends no frame: a send that ran has ended, each later
     * cycle sees the busy flag, and the stream is stopped. Returns false when the thread did
     * not end in {@code timeoutMs}. The stream sends nothing more also then.
     */
    boolean haltForUpgrade(String why, long timeoutMs) {
        Thread t;
        synchronized (gate) {
            synchronized (lock) {
                t = thread;
            }
        }
        stop(why);
        if (t == null || t == Thread.currentThread()) return true;
        try {
            t.join(Math.max(1, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /** Another requester (servo/back-angle, debug/serial/send) sends a cmd 0x17 read for id. */
    void noteExternalRead(int id) {
        if (id < 0 || id >= externalReadAt.length) return;
        synchronized (lock) {
            externalReadAt[id] = Math.max(1, env.nowMs());
        }
    }

    /** The serial reader calls this for each chest payload (payload[0] = cmd).
     *  Returns true when the reply must not go out as chest_rcv. */
    boolean onPayload(byte[] payload) {
        // Reply: [0x17, status, id, hi, lo]. status 0 = OK.
        if (payload == null || payload.length < 5 || payload[0] != CMD_READ_BACK) return false;
        int id = payload[2] & 0xFF;
        synchronized (lock) {
            if (!running) return false;
            boolean external = externalReadAt[id] != 0
                    && env.nowMs() - externalReadAt[id] <= EXTERNAL_WINDOW_MS;
            if (external) externalReadAt[id] = 0;
            Cycle c = current;
            long ns = clock.nanoTime();
            boolean mine;
            if (c != null && c.missedBefore(id) && (!c.sendDone || lateReply(true, ns, c.sendNs))) {
                // A late reply of the last cycle: it does not fill the slot of this cycle.
                lateReplies++;
                mine = true;
            } else {
                mine = c != null && c.accept(id, payload[1],
                        ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF), ns);
            }
            if (c != null && c.complete()) lock.notifyAll();
            return mine && !raw && !external;
        }
    }

    String statusJson() {
        StringBuilder sb = new StringBuilder(256);
        long left = running ? Math.max(0, deadlineMs - env.nowMs()) : 0;
        sb.append("{\"ok\":true,\"running\":").append(running)
                .append(",\"ids\":").append(Arrays.toString(ids).replace(" ", ""))
                .append(",\"hz\":").append(hz)
                .append(",\"ttl_ms\":").append(ttlMs)
                .append(",\"ttl_left_ms\":").append(left)
                .append(",\"raw\":").append(raw)
                .append(",\"busy\":").append(env.busy())
                .append(",\"seq\":").append(seq)
                .append(",\"cycles\":").append(cycles)
                .append(",\"complete_cycles\":").append(completeCycles)
                .append(",\"missed_reads\":").append(missedReads)
                .append(",\"paused_cycles\":").append(pausedCycles)
                .append(",\"late_cycles\":").append(lateCycles)
                .append(",\"late_replies\":").append(lateReplies)
                .append(",\"move_waits\":").append(moveWaits)
                .append(",\"move_forced\":").append(moveForced)
                .append(",\"last_read_ms\":").append(lastReadMs)
                .append(",\"max_read_ms\":").append(maxReadMs)
                .append(",\"stop_reason\":").append(JsonUtil.quote(stopReason))
                .append('}');
        return sb.toString();
    }

    // -- the read loop (own thread) -----------------------------------------------------------

    private boolean alive(int gen) {
        return running && generation == gen;
    }

    private void loop(int gen) {
        long next = env.nowMs();
        try {
            while (alive(gen)) {
                long now = env.nowMs();
                if (now > deadlineMs) {
                    stopGeneration(gen, "ttl: no renew in " + ttlMs + " ms");
                    return;
                }
                int period = 1000 / Math.max(1, hz);
                cycle(gen, period);
                next += period;
                long wait = next - env.nowMs();
                if (wait < 0) {
                    // Late: do not run the next cycles faster to catch up.
                    lateCycles++;
                    next = env.nowMs();
                    wait = 0;
                }
                if (wait > 0) clock.sleepMs(wait);
            }
        } catch (InterruptedException e) {
            // stop() interrupts the thread.
        } catch (Throwable t) {
            env.log("loop failed: " + t);
            stopGeneration(gen, "error: " + t.getClass().getSimpleName());
        }
    }

    private void cycle(int gen, int periodMs) throws InterruptedException {
        // alpha2-brain#45: the reply wait counts from here, not from the send.
        long startNs = clock.nanoTime();
        int[] want = ids;
        byte[] batch = batch(want);
        Cycle c = new Cycle(want);
        c.missedBefore = lastMissed;
        boolean sent;
        long t0;
        synchronized (gate) {
            // alpha2-brain#26: check right before the write, in one step with the write.
            if (!alive(gen)) return;
            if (env.busy() || !env.portAvailable()) {
                pausedCycles++;
                return;
            }
            synchronized (lock) {
                if (!alive(gen)) return;
                t0 = clock.nanoTime();
                current = c;
            }
            sent = env.send(batch);
            // alpha2-brain#46: the guard counts from the end of the send, not from before the
            // wait for the send lock. A reply before this time for a missed id is late.
            synchronized (lock) {
                c.sendNs = clock.nanoTime();
                c.sendDone = true;
            }
        }
        // Wait for all replies, at most replyWaitMs(period) from the start of the cycle (the
        // rest is for the event). A wait for the send lock thus does not make the cycle late.
        long endNs = startNs + replyWaitMs(periodMs) * 1_000_000L;
        Integer[] got;
        long lastNs;
        synchronized (lock) {
            if (sent) {
                while (!c.complete() && alive(gen)) {
                    long leftNs = endNs - clock.nanoTime();
                    if (leftNs <= 0) break;
                    clock.await(lock, Math.max(1, leftNs / 1_000_000L));
                }
            }
            got = c.angles.clone();
            lastNs = c.lastReplyNs;
            // Replies after this point do not count for this cycle. Clear only the own cycle.
            if (current == c) {
                current = null;
                lock.notifyAll(); // a move waits for this (writeBetweenCycles)
            }
            if (!alive(gen)) return;
        }
        cycles++;
        int missing = 0;
        for (Integer v : got) if (v == null) missing++;
        int[] missedIds = new int[missing];
        for (int i = 0, k = 0; i < got.length; i++) if (got[i] == null) missedIds[k++] = want[i];
        lastMissed = missedIds;
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
                .append(",\"t_ms\":").append(env.nowMs())
                .append(",\"read_ms\":").append(readMs)
                .append(",\"ids\":").append(Arrays.toString(want).replace(" ", ""))
                .append(",\"angles\":[");
        for (int i = 0; i < got.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(got[i] == null ? "null" : String.valueOf(got[i]));
        }
        sb.append(']').append(env.accelJson()).append('}');
        env.publish("servo_reads", sb.toString());
    }
}
