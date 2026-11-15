package com.open.alpha2;

/**
 * Host test for PttTurnRunner (alpha2-brain#16, review round 1, F1).
 * No Android, no JUnit: plain checks in main().
 *
 * Run (no Gradle needed):
 *   mkdir -p /tmp/ptt-test/com/open/alpha2
 *   cp app/src/main/java/com/open/alpha2/PttTurnRunner.java /tmp/ptt-test/com/open/alpha2/
 *   cp host-test/com/open/alpha2/PttTurnRunnerTest.java /tmp/ptt-test/com/open/alpha2/
 *   cd /tmp/ptt-test && javac com/open/alpha2/*.java && java com.open.alpha2.PttTurnRunnerTest
 */
public final class PttTurnRunnerTest {

    private int passed = 0;
    private int failed = 0;

    private void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    /** Waits until the flag is set or the deadline passes. */
    private static boolean await(long ms,
            java.util.concurrent.atomic.AtomicBoolean flag) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (flag.get()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return flag.get();
    }

    public static void main(String[] args) {
        new PttTurnRunnerTest().run();
    }

    private void run() {
        final PttTurnRunner runner = new PttTurnRunner();
        final java.util.concurrent.atomic.AtomicBoolean ran =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean otherThread =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // 1. The action runs, on a thread that is not the caller's.
        final Thread caller = Thread.currentThread();
        runner.submit("first", new Runnable() {
            @Override
            public void run() {
                otherThread.set(Thread.currentThread() != caller);
                ran.set(true);
            }
        });
        check("1 action ran", await(2000, ran));
        check("1 not on the caller thread", otherThread.get());

        // 2. The submitter is not blocked while the action runs.
        final long start = System.currentTimeMillis();
        runner.submit("sleep", new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final long submitMs = System.currentTimeMillis() - start;
        check("2 submit did not block (" + submitMs + " ms)", submitMs < 50);

        // 3. The worker keeps the queue: the next task runs in order,
        //    after the sleeping task.
        final java.util.concurrent.atomic.AtomicBoolean afterRan =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        runner.submit("after", new Runnable() {
            @Override
            public void run() {
                afterRan.set(true);
            }
        });
        check("3 worker kept the queue", await(2500, afterRan));

        // 4. A failing action is reported, and the worker survives it.
        final java.util.concurrent.atomic.AtomicBoolean failureReported =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean nextRan =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        runner.setFailureListener(new PttTurnRunner.FailureListener() {
            @Override
            public void onActionFailure(String name, Throwable error) {
                failureReported.set("boom".equals(name)
                        && error instanceof IllegalStateException);
            }
        });
        runner.submit("boom", new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }
        });
        runner.submit("next", new Runnable() {
            @Override
            public void run() {
                nextRan.set(true);
            }
        });
        check("4 failure reported", await(2000, failureReported));
        check("4 worker survived the failure", await(2000, nextRan));

        System.out.println("PttTurnRunnerTest: " + passed + " passed, "
                + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
