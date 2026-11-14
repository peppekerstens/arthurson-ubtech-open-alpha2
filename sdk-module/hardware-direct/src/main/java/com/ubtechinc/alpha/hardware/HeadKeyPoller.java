package com.ubtechinc.alpha.hardware;

import android.os.SystemClock;
import android.util.Log;

import com.ubtechinc.alpha.jni.headkey.HeadKeyMgr;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Head top +/- pad: the single entry point to the {@code libhead_key_mgr.so} native thread.
 *
 * <p>The native thread reads {@code /dev/input/event*} (rk29-keypad) and
 * calls back into this class via {@code HeadKeyMgr.onNativeCallback(int)}, with codes shaped like
 * {@code 0x5A01/0x5B01/0x5E01…} (i.e. the old broadcast format
 * {@code (eventCode<<8)|0x01}). {@link #onNativeKey(int)} composes the
 * 0x5a..0x5f gestures from them. The callback comes from the native callback thread, not the main thread.</p>
 *
 * <p>See the locked methods below for the converging state machine (swallowing duplicates / strays, double-key composition, dedup).
 * Note: this class has no Java fallback direct read — if the native side cannot start (missing .so / Init failure),
 * it simply cannot start, the pad is fully dead, and it will not silently fall to a second set of semantics (decision of 2026-09-30: when there is a problem, look at the log and fix it. Do not keep two behaviors).</p>
 *
 * <p>All raw native callback codes are additionally reported via {@link Listener#onHeadKeyNative(int)}
 * (the caller can forward them to the WebSocket log for reference. The native side itself also writes
 * {@code /sdcard/keyjnilog.txt}, which can be compared).</p>
 */
public class HeadKeyPoller extends HeadKeyMgr {
    private static final String TAG = "HeadKeyPoller";

    /** Reporting interface (implemented by the caller). */
    public interface Listener {
        /** Composed gesture code (0x5a..0x5f, see AIDL_REFERENCE chapter 7). */
        void onGesture(int eventCode);
        /** Raw native callback code. */
        void onHeadKeyNative(int code);
    }

    // Old gesture codes (see AIDL_REFERENCE chapter 7 + GestureCenter.onGestureCode)
    private static final int KEY_MINUS = 0x5a;
    private static final int KEY_MINUS_UP = 0x5b;
    private static final int KEY_PLUS = 0x5c;
    private static final int KEY_PLUS_UP = 0x5d;
    private static final int KEY_BOTH = 0x5e;
    private static final int KEY_BOTH_UP = 0x5f;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    // 2026-09-10 removed: startNativeWatchdog (6s without a callback = dead) — keyjnilog.txt proved
    // it killed healthy native sessions by mistake. If it starts, trust that it stays alive. No periodic check needed.

    private boolean minusDown = false;
    private boolean plusDown = false;
    private boolean bothReported = false;
    // Source of the both-keys flag: true = last set directly by a synthetic 0x5e01 (not pressed out raw).
    // The driver does not resend down on a long press (a 3.4s hold proved one pair only), so when a raw down
    // arrives while the flag is already up, it must be a real hold (swallow it, in case of a flood) or a synthetic
    // leftover (treat it as a new press, to stop a stuck flag from eating single presses). Clear it when the raw arrives.
    private boolean bothSynthetic = false;
    private boolean nativeActive = false;
    /**
     * The dedup window (ms, uptime) for one double press. The driver reports
     * the same double press several times (raw overlap plus a late synthetic 0x5e01,
     * with the raw release in between) — within the window everything counts as
     * the same single press, and there is no second report (it is not two global stops).
     * The cost: a real second double press within 1 second is swallowed (a person
     * cannot press that fast, so it is accepted).
     */
    private static final long DOUBLE_DEDUP_MS = 1000;
    private long lastBothFireMs = -1;
    /** The time of the last click code (uptime ms, -1: none), for the click log line. */
    private long lastClickMs = -1;

    public void setListener(Listener l) { listener = l; }

    public synchronized void start() {
        if (running.get()) return;
        // Mutual exclusion with a Java direct read is hard-coded here: if the native side starts, it is the only reader. No second path is opened.
        // Note: disassembly proved the native Init() returns 0 for both success and failure (it ends with moveq r0,#0),
        // so the return value cannot decide the outcome. Call in the stock order. Judge success or failure from the callback / the "nativeRun" log.
        if (HeadKeyMgr.isLibLoaded()) {
            try {
                boolean initRet = Init();
                Log.i(TAG, "HeadKeyMgr.Init() returned " + initRet + " (ignored, always false)");
                nativeInit();
                nativeThreadStart();
                nativeActive = true;
                running.set(true);
                Log.i(TAG, "native head_key thread active (3.002 libhead_key_mgr.so)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "native head_key failed, no fallback: " + t.getMessage());
            }
        } else {
            Log.w(TAG, "head_key_mgr.so not loaded, head keys unavailable (no fallback)");
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (nativeActive) {
            nativeActive = false;
            try {
                nativeThreadStop();
            } catch (Throwable t) {
                Log.w(TAG, "nativeThreadStop failed: " + t.getMessage());
            }
        }
    }

    /**
     * Native key callback. Log the raw code first (to compare with /sdcard/keyjnilog.txt).
     * Reporting and gestures both go through the converging state machine of onNativeKey below. Do not report directly here,
     * or chatter / duplicates would be reported twice.
     */
    @Override
    public void onNativeCallback(int code) {
        if (!running.get()) return; // leftover callbacks after stop are not delivered
        Log.i(TAG, "native key 0x" + Integer.toHexString(code));
        onNativeKey(code);
    }

    /**
     * Decode of the native callback: the measured codes are shaped like {@code 0x5A01/0x5B01/0x5E01…},
     * i.e. the old broadcast format {@code (eventCode<<8)|0x01}.
     * 0x5a..0x5d run the converging state machine (against double dispatch). 0x5e01/0x5f01 are trusted from the native side, but not
     * reported again (if the raw overlap already reported, swallow the synthetic one. Swallow 0x5f01 only when all state is clear).
     */
    private void onNativeKey(int code) {
        int eventCode = (code >> 8) & 0xFF;
        boolean publish = false;
        java.util.List<Integer> tmp = new java.util.ArrayList<Integer>(1);
        synchronized (this) {
            switch (eventCode) {
                case KEY_MINUS:
                    publish = pressLocked(true, tmp);
                    break;
                case KEY_MINUS_UP:
                    publish = releaseLocked(true, tmp);
                    break;
                case KEY_PLUS:
                    publish = pressLocked(false, tmp);
                    break;
                case KEY_PLUS_UP:
                    publish = releaseLocked(false, tmp);
                    break;
                case KEY_BOTH:
                    // Trust the native side, but within the same double-press window do not fire a second time (the raw overlap already reported,
                    // or it was reported earlier and this one is a late echo — do not touch the flag at all, and treat it as if
                    // it never came. This is not two global stops plus a leftover false flag eating the next order).
                    // When it fires, set the flag first (so the following raw release can pair with it), and record that it came
                    // synthetically (clear it when the raw arrives, see pressLocked/releaseLocked).
                    if (tryFireBothLocked(tmp)) {
                        minusDown = true;
                        plusDown = true;
                        bothSynthetic = true;
                    }
                    publish = true;
                    break;
                case KEY_BOTH_UP:
                    // Same reasoning: when everything is already clear, do not report 0x5f once more (it would add one extra global light-off burst).
                    if (bothReported || minusDown || plusDown) {
                        minusDown = false;
                        plusDown = false;
                        bothReported = false;
                        bothSynthetic = false;
                        tmp.add(KEY_BOTH_UP);
                    }
                    publish = true;
                    break;
                case HeadKeyClick.CLICK_MINUS:
                case HeadKeyClick.CLICK_PLUS:
                    // Click codes (robot session 2026-11-01): one code for each press, no
                    // release code. A press and a release gesture give one volume step.
                    // The state machine flags of the 0x5a..0x5f codes stay as they are.
                    // alpha2-brain#45: log each click with the time since the last click
                    // (the source of 2 extra codes is not known, see HeadKeyClick).
                    long nowMs = SystemClock.uptimeMillis();
                    Log.i(TAG, HeadKeyClick.logLine(code, lastClickMs < 0 ? -1 : nowMs - lastClickMs));
                    lastClickMs = nowMs;
                    for (int g : HeadKeyClick.gestures(code)) tmp.add(g);
                    publish = true;
                    break;
                default:
                    Log.d(TAG, "native key unmapped 0x" + Integer.toHexString(code));
                    break;
            }
        }
        if (publish) {
            Listener l = listener;
            if (l != null) {
                try {
                    l.onHeadKeyNative(code);
                } catch (Throwable t) {
                    Log.w(TAG, "onHeadKeyNative failed", t);
                }
            }
        }
        for (int i = 0; i < tmp.size(); i++) emitGesture(tmp.get(i));
    }

    /** Report the double press (0x5e). Within the same double-press window it is reported only once (see DOUBLE_DEDUP_MS).
     *  Returns true = fired (the caller sets the flag afterwards). false = echo, and nothing is touched. */
    private boolean tryFireBothLocked(java.util.List<Integer> gestures) {
        long now = SystemClock.uptimeMillis();
        if (lastBothFireMs >= 0 && now - lastBothFireMs <= DOUBLE_DEDUP_MS) return false;
        lastBothFireMs = now;
        bothReported = true;
        gestures.add(KEY_BOTH);
        return true;
    }

    /** Raw press inside the lock. The driver does not resend down on a long press (a 3.4s hold proved one pair only), so
     *  when a down arrives while the flag is already up there are only two possibilities: a duplicate of a real hold (swallow it, in case of a flood) vs
     *  a synthetic leftover flag (treat it as a new press, clear the other false flag together, to stop a stuck flag from eating orders). */
    private boolean pressLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if ((isMinus ? minusDown : plusDown) && !(bothReported && bothSynthetic)) {
            return false;
        }
        // New edge (or leftover cleanup): this press is real. Flags that came synthetically (including the other key)
        // have no raw evidence to support them, so invalidate them all.
        if (bothSynthetic) {
            minusDown = false;
            plusDown = false;
            bothReported = false;
            bothSynthetic = false;
        }
        if (isMinus) minusDown = true; else plusDown = true;
        if (minusDown && plusDown) {
            tryFireBothLocked(gestures); // do not report the single key during the overlap. Let the dedup decide
        } else {
            // With no overlap there can be no live double press: clear a leftover bothReported at once and report the single key as normal.
            bothReported = false;
            gestures.add(isMinus ? KEY_MINUS : KEY_PLUS);
        }
        return true;
    }

    /** Raw release inside the lock: a stray up for a key that was never pressed is swallowed. A synthetic leftover is cut here
     *  (the other false flag is cleared together), but this key itself is reported through the normal path — reaching this point means the driver really sent
     *  an up (no up, no release). */
    private boolean releaseLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if (bothSynthetic) {
            bothSynthetic = false;
            bothReported = false;
            if (isMinus) plusDown = false; else minusDown = false;
        }
        if (isMinus ? !minusDown : !plusDown) return false;
        if (isMinus) minusDown = false; else plusDown = false;
        if (bothReported && !minusDown && !plusDown) {
            bothReported = false;
            gestures.add(KEY_BOTH_UP);
        } else if (!bothReported) {
            gestures.add(isMinus ? KEY_MINUS_UP : KEY_PLUS_UP);
        } else if (!minusDown || !plusDown) {
            // Release one key first during a double press: report the double-key release first. The pressed state of the remaining key is handled when it resends
            bothReported = false;
            gestures.add(KEY_BOTH_UP);
        }
        return true;
    }

    /** Report the composed gesture (the caller restores the old broadcast direction=(eventCode<<8)|0x01 semantics as needed). */
    private void emitGesture(int eventCode) {
        Log.i(TAG, "gesture 0x" + Integer.toHexString(eventCode));
        Listener l = listener;
        if (l != null) {
            try {
                l.onGesture(eventCode);
            } catch (Throwable t) {
                Log.w(TAG, "onGesture failed", t);
            }
        }
    }
}
