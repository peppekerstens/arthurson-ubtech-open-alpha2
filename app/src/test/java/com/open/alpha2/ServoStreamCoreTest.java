package com.open.alpha2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit test for ServoStreamCore with a fake chest port (alpha2-brain#26, #29, #30).
 * Pure java.*, no JUnit (same pattern as ApiValidatorTest). Run: scripts/test-apivalidator.py.
 */
public class ServoStreamCoreTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A fake chest. It answers each read of the batch at once (from the send call). */
    static final class FakeEnv implements ServoStreamCore.Env {
        ServoStreamCore core;
        final AtomicBoolean busy = new AtomicBoolean(false);
        final AtomicBoolean upgradeFramesStarted = new AtomicBoolean(false);
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger sendsDuringUpgrade = new AtomicInteger();
        final AtomicInteger hiddenReplies = new AtomicInteger();
        final AtomicInteger shownReplies = new AtomicInteger();
        volatile boolean answer = true;
        volatile long sendDelayMs = 0;
        final List<String> events = new ArrayList<>();
        volatile byte[] lastBatch;
        /** More than 0: the replies come from another thread, this many ms after the send. */
        volatile long replyDelayMs = 0;

        @Override public long nowMs() { return System.nanoTime() / 1_000_000L; }

        @Override public boolean portAvailable() { return true; }

        @Override public boolean send(byte[] batch) {
            if (upgradeFramesStarted.get()) sendsDuringUpgrade.incrementAndGet();
            sends.incrementAndGet();
            lastBatch = batch;
            if (sendDelayMs > 0) sleep(sendDelayMs);
            if (answer && replyDelayMs > 0) {
                final byte[] b = batch;
                final long d = replyDelayMs;
                new Thread(new Runnable() {
                    @Override public void run() {
                        sleep(d);
                        for (int o = 0; o + 9 <= b.length; o += 9) {
                            int id = b[o + 6] & 0xFF;
                            core.onPayload(new byte[]{0x17, 0, (byte) id, 0x01, (byte) id});
                        }
                    }
                }).start();
            } else if (answer) {
                for (int o = 0; o + 9 <= batch.length; o += 9) {
                    int id = batch[o + 6] & 0xFF;
                    byte[] reply = {0x17, 0, (byte) id, 0x01, (byte) id};
                    if (core.onPayload(reply)) hiddenReplies.incrementAndGet();
                    else shownReplies.incrementAndGet();
                }
            }
            return true;
        }

        @Override public boolean busy() { return busy.get(); }

        @Override public String accelJson() { return ",\"accel\":null,\"accel_age_ms\":null"; }

        @Override public void publish(String type, String json) {
            synchronized (events) {
                events.add(type + " " + json);
            }
        }

        @Override public void log(String msg) { }

        int count(String prefix) {
            int n = 0;
            synchronized (events) {
                for (String e : events) if (e.startsWith(prefix)) n++;
            }
            return n;
        }
    }

    /**
     * A fake clock with a fixed schedule (alpha2-brain#46). No real sleep: a wait moves the
     * clock to the next scheduled reply or to the end of the wait. The read loop runs on the
     * test thread ({@link #runLoop}), so the result is the same on each host.
     */
    static final class SimClock implements ServoStreamCore.Clock {
        long ns = 1_000_000_000_000L;
        private final java.util.PriorityQueue<long[]> due =
                new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]) != 0
                        ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        private final List<Runnable> jobs = new ArrayList<>();
        private Runnable loop;

        void at(long atNs, Runnable r) {
            jobs.add(r);
            due.add(new long[]{atNs, jobs.size() - 1});
        }

        /** Runs each job due up to t in time order, then sets the clock to t. */
        void advanceTo(long t) {
            while (!due.isEmpty() && due.peek()[0] <= t) {
                long[] d = due.poll();
                ns = Math.max(ns, d[0]);
                jobs.get((int) d[1]).run();
            }
            ns = Math.max(ns, t);
        }

        @Override public long nanoTime() { return ns; }

        @Override public void sleepMs(long ms) { advanceTo(ns + ms * 1_000_000L); }

        @Override public void await(Object lock, long ms) {
            long end = ns + ms * 1_000_000L;
            if (!due.isEmpty() && due.peek()[0] <= end) advanceTo(due.peek()[0]);
            else ns = end;
        }

        @Override public Thread startThread(Runnable r) {
            loop = r;
            return null;
        }

        /** Runs the read loop on this thread until the TTL stops it. */
        void runLoop() {
            Runnable r = loop;
            loop = null;
            r.run();
        }
    }

    /** A fake chest on the fake clock: the send takes sendDelayMs, the replies come
     *  replyDelayMs after the end of the send. */
    static final class SimEnv implements ServoStreamCore.Env {
        final SimClock clock = new SimClock();
        ServoStreamCore core;
        long sendDelayMs = 0;
        long replyDelayMs = 0;
        boolean answer = true;
        final List<String> events = new ArrayList<>();

        @Override public long nowMs() { return clock.ns / 1_000_000L; }

        @Override public boolean portAvailable() { return true; }

        @Override public boolean send(final byte[] batch) {
            clock.advanceTo(clock.ns + sendDelayMs * 1_000_000L);
            if (!answer) return true;
            long at = clock.ns + replyDelayMs * 1_000_000L;
            for (int o = 0; o + 9 <= batch.length; o += 9) {
                final int id = batch[o + 6] & 0xFF;
                final int angle = 0x0100 + (int) (clock.ns / 1_000_000L % 200);
                clock.at(at, new Runnable() {
                    @Override public void run() {
                        core.onPayload(new byte[]{0x17, 0, (byte) id, (byte) (angle >> 8), (byte) angle});
                    }
                });
            }
            return true;
        }

        @Override public boolean busy() { return false; }

        @Override public String accelJson() { return ",\"accel\":null,\"accel_age_ms\":null"; }

        @Override public void publish(String type, String json) { events.add(type + " " + json); }

        @Override public void log(String msg) { }

        int readEventsWithAngle() {
            int n = 0;
            for (String e : events) {
                if (e.startsWith("servo_reads") && !e.contains("\"angles\":[null,null]")) n++;
            }
            return n;
        }
    }

    /** Starts a stream on the fake clock and runs it until the TTL stops it. */
    private static SimEnv sim(long sendDelayMs, long replyDelayMs, boolean answer, int[] ids, int ttlMs) {
        SimEnv s = new SimEnv();
        s.sendDelayMs = sendDelayMs;
        s.replyDelayMs = replyDelayMs;
        s.answer = answer;
        s.core = new ServoStreamCore(s, s.clock, new int[]{2, 3}, 20, 5000);
        s.core.start(ids, 20, ttlMs, false);
        s.clock.runLoop();
        return s;
    }

    private static FakeEnv env() {
        FakeEnv e = new FakeEnv();
        e.core = new ServoStreamCore(e, new int[]{2, 3}, 20, 5000);
        return e;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // -- frames and ids --
        eq("f8 8f 08 00 00 17 02 21 ed f8 8f 08 00 00 17 11 30 ed",
                hex(ServoStreamCore.batch(new int[]{2, 17})), "batch.bytes");
        eq(null, com.ubtechinc.alpha.hardware.ChestSafety.checkChestFrames(
                ServoStreamCore.batch(new int[]{2, 3, 5, 6, 17, 18, 19, 20, 21, 22}), false), "batch.passesGuard");
        eq(null, ServoStreamCore.checkReadableIds(new int[]{2, 3, 5, 6, 17, 18, 19, 20, 21, 22}), "ids.readable");
        int[] heavy = {1, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        int refused = 0;
        for (int id : heavy) if (ServoStreamCore.checkReadableIds(new int[]{2, id}) != null) refused++;
        eq(heavy.length, refused, "ids.heavyRefused");
        eq("[2, 17]", java.util.Arrays.toString(ServoStreamCore.readIdsInFrames(
                ServoStreamCore.batch(new int[]{2, 17}))), "ids.inFrames");

        // -- reply match uses the id --
        ServoStreamCore.Cycle c = new ServoStreamCore.Cycle(new int[]{2, 3});
        eq(false, c.accept(5, 0, 100, 1), "cycle.otherId");
        eq(true, c.accept(3, 0, 300, 1), "cycle.id3");
        eq(Integer.valueOf(300), c.angles[1], "cycle.slot");
        eq(true, c.accept(3, 0, 999, 2), "cycle.dupe");
        eq(Integer.valueOf(300), c.angles[1], "cycle.firstWins");
        eq(true, c.accept(2, 1, 5, 3), "cycle.badStatus");
        eq(null, c.angles[0], "cycle.badStatusEmpty");
        eq(false, c.complete(), "cycle.notComplete");

        // -- reply wait: robot session 2026-11-01, 8 reads take 33 to 40 ms --
        eq(48, ServoStreamCore.replyWaitMs(50), "wait.20hz");
        eq(38, ServoStreamCore.replyWaitMs(40), "wait.25hz");
        eq(5, ServoStreamCore.replyWaitMs(5), "wait.min");
        // The timing tests below use the fake clock (alpha2-brain#46): no real sleep.
        // Replies 43 ms after the send: later than the old 40 ms limit, sooner than 48 ms.
        SimEnv slow = sim(0, 43, true, new int[]{2, 3, 5, 6, 17, 18, 19, 20}, 400);
        eq(true, slow.core.cycles() >= 7, "slow.cycles");
        eq(true, slow.core.stopReason().startsWith("ttl"), "slow.ttlStop");
        // The wait counts from the start of the cycle (alpha2-brain#45): each cycle reads all 8.
        eq(0L, slow.core.missedReads(), "slow.noMissed");

        // -- alpha2-brain#45: a late reply of the last cycle does not fill the next cycle --
        eq(true, ServoStreamCore.lateReply(true, 2_000_000L, 0L), "late.missedEarly");
        eq(false, ServoStreamCore.lateReply(true, 3_000_000L, 0L), "late.missedAtGuard");
        eq(false, ServoStreamCore.lateReply(false, 1_000_000L, 0L), "late.notMissed");
        eq(true, ServoStreamCore.lateReply(true, -1_000_000L, 0L), "late.beforeSend");
        // A fake chest that answers 51 ms after the send, at 20 Hz (period 50 ms): the
        // replies of cycle N come about 1 ms after the send of cycle N+1.
        SimEnv late = sim(0, 51, true, new int[]{2, 3}, 600);
        // Each reply comes 1 ms after the next send. Only the replies of the last cycle
        // come after the stop. Thus 2 late replies for each cycle but the last.
        eq(2 * (late.core.cycles() - 1), late.core.lateReplies(), "late.counted");
        eq(0, late.readEventsWithAngle(), "late.noOldAngle");
        eq(true, late.core.statusJson().contains("\"late_replies\":"), "late.inStatus");
        // A send that waits for the send lock does not make the cycle late: the reply wait
        // counts from the start of the cycle (period 50 ms, send wait 20 ms, no replies).
        SimEnv lock = sim(20, 0, false, new int[]{2, 3}, 600);
        eq(true, lock.core.cycles() >= 11, "sendWait.cycles");
        eq(0L, lock.core.lateCycles(), "sendWait.notLate");
        // alpha2-brain#46: a send that waits 10 ms for the send lock, replies 45 ms after
        // the send. The replies of cycle N come 5 ms into the send of cycle N+1. The guard
        // counts from the end of the send, so these replies are late and fill no slot.
        SimEnv both = sim(10, 45, true, new int[]{2, 3}, 600);
        eq(true, both.core.cycles() >= 11, "sendAndReply.cycles");
        eq(2 * (both.core.cycles() - 1), both.core.lateReplies(), "sendAndReply.late");
        eq(0, both.readEventsWithAngle(), "sendAndReply.noOldAngle");

        // -- run, hide replies, TTL --
        FakeEnv e = env();
        eq(true, e.core.start(new int[]{2, 3}, 25, 300, false), "run.start");
        sleep(150);
        eq(true, e.core.isRunning(), "run.running");
        eq(true, e.count("servo_reads") >= 2, "run.events");
        eq(0, e.shownReplies.get(), "run.repliesHidden");
        eq(0L, e.core.missedReads(), "run.noMissed");
        eq("f8 8f 08 00 00 17 02 21 ed f8 8f 08 00 00 17 03 22 ed", hex(e.lastBatch), "run.batchBytes");
        sleep(400);
        eq(false, e.core.isRunning(), "ttl.stopped");
        eq(true, e.core.stopReason().startsWith("ttl"), "ttl.reason");
        int sendsAfterTtl = e.sends.get();
        sleep(100);
        eq(sendsAfterTtl, e.sends.get(), "ttl.noSendAfterStop");

        // -- raw=true shows the replies --
        e = env();
        e.core.start(new int[]{2}, 25, 2000, true);
        sleep(120);
        e.core.stop("test");
        eq(0, e.hiddenReplies.get(), "raw.noneHidden");

        // -- external read: the next reply for that id goes out as chest_rcv --
        e = env();
        e.answer = false;
        e.core.start(new int[]{2, 3}, 5, 2000, false);
        sleep(30);
        e.core.noteExternalRead(2);
        boolean hidden2 = true, hidden3 = false, hidden2b = false;
        for (int i = 0; i < 50; i++) {
            // wait for a cycle with its slots open, then answer by hand
            if (e.sends.get() > 0) break;
            sleep(5);
        }
        hidden2 = e.core.onPayload(new byte[]{0x17, 0, 2, 0, 9});
        hidden3 = e.core.onPayload(new byte[]{0x17, 0, 3, 0, 9});
        hidden2b = e.core.onPayload(new byte[]{0x17, 0, 2, 0, 9});
        e.core.stop("test");
        eq(false, hidden2, "external.shown");
        eq(true, hidden3, "external.otherHidden");
        eq(true, hidden2b, "external.once");
        eq(false, e.core.onPayload(new byte[]{0x17, 0, 2, 0, 9}), "stopped.notHidden");

        // -- busy: pause, and start gives BusyException --
        e = env();
        e.core.start(new int[]{2, 3}, 25, 2000, false);
        sleep(60);
        e.busy.set(true);
        sleep(20);
        int sendsBusy = e.sends.get();
        sleep(150);
        eq(sendsBusy, e.sends.get(), "busy.noSend");
        eq(true, e.core.pausedCycles() > 0, "busy.paused");
        e.core.stop("test");
        boolean threw = false;
        try {
            e.core.start(new int[]{2}, 20, 1000, false);
        } catch (ServoStreamCore.BusyException ex) {
            threw = true;
        }
        eq(true, threw, "busy.start409");
        eq(false, e.core.isRunning(), "busy.notStarted");

        // -- upgrade: halt waits for the send that runs, no send after it --
        for (int round = 0; round < 20; round++) {
            final FakeEnv u = env();
            u.sendDelayMs = 7; // a slow write, the upgrade comes in the middle
            u.core.start(new int[]{2, 3, 5, 6}, 25, 2000, false);
            sleep(10 + round * 3);
            u.busy.set(true);
            boolean ok = u.core.haltForUpgrade("upgrade test", 2000);
            u.upgradeFramesStarted.set(true);
            sleep(80);
            u.busy.set(false);
            if (!ok || u.sendsDuringUpgrade.get() != 0 || u.core.isRunning()) {
                eq("halt ok, 0 sends, stopped", "ok=" + ok + " sends=" + u.sendsDuringUpgrade.get()
                        + " running=" + u.core.isRunning(), "upgrade.round" + round);
            } else {
                passed++;
            }
        }

        // -- stop and quick start: the old thread does not mix with the new cycles --
        e = env();
        e.sendDelayMs = 1;
        for (int i = 0; i < 40; i++) {
            e.core.start(new int[]{2, 3, 5}, 25, 2000, false);
            sleep(i % 3);
            e.core.stop("restart " + i);
        }
        e.core.start(new int[]{2, 3, 5}, 25, 2000, false);
        sleep(150);
        e.core.stop("end");
        eq(0L, e.core.missedReads(), "restart.noMissed");
        int sendsEnd = e.sends.get();
        sleep(100);
        eq(sendsEnd, e.sends.get(), "restart.noSendAfterStop");

        // -- status JSON --
        String st = e.core.statusJson();
        eq(true, st.contains("\"running\":false") && st.contains("\"busy\":false")
                && st.contains("\"stop_reason\":\"end\""), "status.json");

        System.out.println("ServoStreamCoreTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
