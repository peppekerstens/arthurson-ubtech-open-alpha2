package com.open.alpha2;

/**
 * Single download channel gate (used by the "Resource Download" card on the
 * experiments tab).
 *
 * <p>A Vosk model and the action pack share the same download channel. Only one
 * download may run at a time. The backend enforces mutual exclusion here: a
 * failed acquire returns an error directly. The frontend button state is
 * decoration only; it does not block.
 * A reentrant acquire by the same owner is allowed (each controller blocks an
 * "already downloading" state internally before reaching this point). Each
 * controller releases on completion, failure or cancel.
 * All methods are static synchronized; any thread may call them.
 */
public final class DownloadGate {
    private DownloadGate() {}

    /** Download that holds the channel: "vosk" or "actions". null = idle. */
    private static String owner = null;
    /** Current download detail (a model id for vosk, actions.zip for actions). Used in error messages. */
    private static String detail = null;

    /** Acquire the gate: allowed when idle or already held by the same owner; false when another owner holds it. */
    public static synchronized boolean tryAcquire(String o, String d) {
        if (owner != null && !owner.equals(o)) return false;
        owner = o;
        detail = d;
        return true;
    }

    /** Release the gate: only the current owner may release it. A release by anyone else is a no-op. */
    public static synchronized void release(String o) {
        if (o != null && o.equals(owner)) {
            owner = null;
            detail = null;
        }
    }

    /** Short description of the current state ("idle", "vosk: xxx", "actions: actions.zip"). Used in error messages. */
    public static synchronized String describe() {
        if (owner == null) return "idle";
        return detail != null ? owner + ": " + detail : owner;
    }
}
