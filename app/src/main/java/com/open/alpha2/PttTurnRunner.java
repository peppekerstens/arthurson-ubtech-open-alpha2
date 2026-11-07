package com.open.alpha2;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Runs the push-to-talk turn actions on one background thread
 * (alpha2-brain#16).
 *
 * The turn send ends in a socket write (sendListenStop). On SDK 22 a
 * socket write on the main thread throws NetworkOnMainThreadException.
 * The actions run on the worker thread, in the order submitted. A
 * failing action is reported to the failure listener and does not stop
 * the worker.
 *
 * Pure logic: no Android imports, no I/O. The host test covers the
 * worker thread, the submission order and the failure report.
 */
public final class PttTurnRunner {

    /** Called on the worker thread when a turn action fails. */
    public interface FailureListener {
        void onActionFailure(String name, Throwable error);
    }

    private static final class Task {
        final String name;
        final Runnable action;

        Task(String name, Runnable action) {
            this.name = name;
            this.action = action;
        }
    }

    private final LinkedBlockingQueue<Task> queue =
            new LinkedBlockingQueue<Task>();
    private final Thread worker;
    private volatile FailureListener failureListener;

    public PttTurnRunner() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    final Task task;
                    try {
                        task = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        task.action.run();
                    } catch (Throwable t) {
                        reportFailure(task.name, t);
                    }
                }
            }
        }, "xiaozhi-ptt-turns");
        worker.setDaemon(true);
        worker.start();
    }

    public void setFailureListener(FailureListener listener) {
        this.failureListener = listener;
    }

    /** Submits one turn action. It runs on the worker thread, never on
     *  the calling thread, and does not block the caller. */
    public void submit(String name, Runnable action) {
        queue.add(new Task(name, action));
    }

    private void reportFailure(String name, Throwable error) {
        final FailureListener listener = failureListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onActionFailure(name, error);
        } catch (Throwable ignore) {
        }
    }
}
