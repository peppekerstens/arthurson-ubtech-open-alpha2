package com.ubtechinc.alpha.hardware;

import android.util.Log;

/**
 * JNI direct drive for the 5-mic head/eye/mouth LEDs.
 * Bypasses the AIDL of alpha2services entirely and goes straight to the
 * ioctl of libhead_led.so.
 * 3.002 version (/dev/led_eye, ioctl numbers identical to 1.1.7.3 one by one:
 * On=0x4c00/OFF=0x4c01/Eye=0x4c02/Head=0x4c03/Mouth=0x4c04, checked by
 * disassembly).
 * Already verified in open-alpha2's MouthLedData.
 *
 * <p>To avoid a circular dependency between the sdk-module hardware layer and
 * the app layer, this calls {@code com.ubtechinc.alpha.jni.LedControl} via
 * reflection (the 3.002 stock package name; the old
 * {@code com.ubtechinc.mic5.LedControl} file is kept but no longer referenced).
 * This way hardware-direct has no compile-time dependency on the app, and at
 * runtime it finds the class through its own dex.</p>
 */
public final class DirectLedController {
    private static final String TAG = "DirectLedController";
    private static final String LED_CTRL = "com.ubtechinc.alpha.jni.LedControl";

    private DirectLedController() {}

    // Reuse the verified parameter table from AIDL_REFERENCE 3.1
    // p1 color 1 red 2 green 3 blue 4 yellow 5 purple 6 cyan 7 white, p2 brightness 1-9, p5/p6 timing, p7 runTime, p8 mode
    // p3/p4 use the full mask (31/31, matching the original binder behavior noted above. An earlier
    // version passed the color there, which made the same color a notch darker than the LedCenter preset).
    public static boolean setHead5Mic(int color, int bright, int p5, int p6, int runTime, int mode) {
        boolean ok = callLedReflect("ledSetHead",
                new Object[]{color, bright, 31, 31, p5, p6, runTime, mode}, "head color=" + color + " mode=" + mode);
        if (ok) recordHead(color, bright, 31, 31);
        return ok;
    }

    public static boolean setMouth(int runTime, int breathe, int off, int play, int mode) {
        boolean ok = callLedReflect("ledSetMouth",
                new Object[]{runTime, breathe, off, play, mode},
                "mouth mode=" + mode + " breathe=" + breathe + "ms off=" + off + "ms");
        if (ok) recordMouth(runTime, breathe, off, play, mode);
        return ok;
    }

    public static boolean setOff() {
        // 3.002 signature ledSetOFF(I): disassembly shows the int only goes to the log (mov r3,r4 → __android_log_print), not to the hardware, so pass 0.
        // Note: this ioctl number also clears wifi 12/13. On the hardware the head and eye go off together,
        // so on success both mirrors record off. The mouth channel dies too, so restore it right away (it was restored only while breathing).
        boolean ok = callLedReflect("ledSetOFF", new Object[]{0}, "ledSetOFF");
        if (ok) {
            recordHeadOff();
            recordEyeOff();
            restoreMouthAfterOff();
        }
        return ok;
    }

    // Reflection cache: every light call used to run the full Class.forName + getMethod sequence (under disco high frequency,
    // more than ten times per second, on the Visualizer callback thread). Now resolve once and keep it.
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean sInitDone = false;
    private static volatile boolean sInitOk = false;
    private static Class<?> sCls = null;
    private static java.lang.reflect.Method sOpen = null;
    private static java.lang.reflect.Method sClose = null;
    private static java.lang.reflect.Method sHead = null;
    private static java.lang.reflect.Method sEye = null;
    private static java.lang.reflect.Method sMouth = null;
    private static java.lang.reflect.Method sOn = null;
    private static java.lang.reflect.Method sOff = null;

    private static boolean ensureInit() {
        if (sInitDone) return sInitOk;
        synchronized (INIT_LOCK) {
            if (sInitDone) return sInitOk;
            boolean ok = false;
            try {
                sCls = Class.forName(LED_CTRL);
                sOpen = sCls.getMethod("open");
                try {
                    sClose = sCls.getMethod("close");
                } catch (NoSuchMethodException e) {
                    // close missing: refuse below. Leaking one fd per open would
                    // stall the whole machine under high-frequency lighting. Log loudly so logcat shows it.
                    Log.e(TAG, "LedControl.close() NOT FOUND - refusing LED calls to avoid leaking an fd per open()");
                    sClose = null;
                }
                Class<?>[] i8 = new Class[]{int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class};
                Class<?>[] i5 = new Class[]{int.class, int.class, int.class, int.class, int.class};
                Class<?>[] i1 = new Class[]{int.class};
                sHead = sCls.getMethod("ledSetHead", i8);
                sEye = sCls.getMethod("ledSetEye", i8);
                sMouth = sCls.getMethod("ledSetMouth", i5);
                sOn = sCls.getMethod("ledSetOn", i1);
                sOff = sCls.getMethod("ledSetOFF", i1);
                // open/head/eye plus close must all be present to count. mouth/on/off missing is not fatal
                // (the matching function returns false). Do not drag down head/eye.
                ok = sOpen != null && sHead != null && sEye != null && sClose != null;
            } catch (Throwable t) {
                Log.w(TAG, "LED reflect init failed: " + t.getMessage());
            }
            // On failure, do not latch — a late-loading .so is retried next time (the old latch stayed dead until reboot).
            sInitOk = ok;
            sInitDone = ok;
            return ok;
        }
    }

    private static java.lang.reflect.Method methodFor(String method) {
        if ("ledSetHead".equals(method)) return sHead;
        if ("ledSetEye".equals(method)) return sEye;
        if ("ledSetMouth".equals(method)) return sMouth;
        if ("ledSetOn".equals(method)) return sOn;
        if ("ledSetOFF".equals(method)) return sOff;
        return null;
    }

    // Global driver lock: the disco thread / pad light thread / HTTP thread / wifi light / mouth light all light
    // /dev/led_eye through here. The old driver is suspected of failing on concurrent open (several hard hangs
    // with no ANR, adb itself dead, all during the disco high-frequency period). Queue them up, one at a time. A few ms slower beats a dead machine.
    // Note: any path that touches LedControl directly (open→ioctl→close) must go through this lock.
    // Do not build your own, or it collides with the open of a queued light call and can wedge the whole machine.
    private static final Object DRIVER_LOCK = new Object();

    /** Wrap a piece of code that touches LedControl directly (open→ioctl→close) in the global driver lock.
     *  It joins the same queue as all light calls through callLedReflect. The lock wraps only the ioctl itself.
     *  The retry sleep stays outside, so other lights are not blocked. The Throwable thrown out by r still propagates. */
    public static void runExclusive(Runnable r) {
        synchronized (DRIVER_LOCK) {
            r.run();
        }
    }

    private static boolean callLedReflect(String method, Object[] args, String desc) {
        synchronized (DRIVER_LOCK) {
            return callLedReflectLocked(method, args, desc);
        }
    }

    // -- Batch ioctl ---------------------------------------------------------
    // One open → N ioctls → one close (inside the same DRIVER_LOCK).
    // Saves fd open/close plus reflection. The whole set of lights swaps at once, no black gap in the middle — used for the pad independent off (OFF plus restore),
    // and for the disco per-turn multi-light push, which also lightens the driver load. Only return true when all succeed.
    // On failure, stop at once (skip the rest, do not hammer the wedged driver). Return false.
    // The state mirror is still recorded one by one (the last one wins, same as lighting one by one).
    public static final class LedOp {
        final String method;
        final Object[] args;
        final String desc;
        private LedOp(String method, Object[] args, String desc) {
            this.method = method;
            this.args = args;
            this.desc = desc;
        }
        public static LedOp off() {
            return new LedOp("ledSetOFF", new Object[]{0}, "ledSetOFF");
        }
        public static LedOp head(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
            return new LedOp("ledSetHead",
                    new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
        }
        public static LedOp eye(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
            return new LedOp("ledSetEye",
                    new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
        }
        public static LedOp mouth(int runTime, int breathe, int off, int play, int mode) {
            return new LedOp("ledSetMouth",
                    new Object[]{runTime, breathe, off, play, mode},
                    "mouth mode=" + mode + " breathe=" + breathe + "ms");
        }
        public static LedOp on(int index) {
            return new LedOp("ledSetOn", new Object[]{index}, "ledSetOn " + index);
        }
    }

    public static boolean runBatch(java.util.List<LedOp> ops) {
        synchronized (DRIVER_LOCK) {
            try {
                if (!ensureInit()) return false;
                Object openRes = sOpen.invoke(null);
                if (openRes instanceof Boolean && !(Boolean) openRes) {
                    Log.w(TAG, "LedControl.open() failed for batch x" + ops.size());
                    return false;
                }
                boolean allOk = true;
                try {
                    for (int oi = 0; oi < ops.size(); oi++) {
                        LedOp op = ops.get(oi);
                        java.lang.reflect.Method m = methodFor(op.method);
                        if (m == null) {
                            Log.w(TAG, "LED method not found: " + op.method);
                            allOk = false;
                            break;
                        }
                        Object r = m.invoke(null, op.args);
                        boolean raw = r instanceof Boolean ? (Boolean) r : false;
                        // Same as callLedReflectLocked: the return value is inverted (success = 0/false).
                        boolean ok = !raw;
                        Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + op.desc + " raw=" + raw);
                        if (!ok) {
                            allOk = false;
                            break;
                        }
                        recordOp(op);
                        // OFF clears the mouth channel too — restore it right away inside the same open
                        // (it was restored only while breathing. Skip it when an explicit mouth op follows, since that one overwrites).
                        // Without the restore the mouth stays black forever (disco pushes first, TTS all at once, it does not come back on its own).
                        if ("ledSetOFF".equals(op.method) && !hasMouthOpLater(ops, oi)) {
                            restoreMouthLocked();
                        }
                    }
                } finally {
                    if (sClose != null) {
                        try { sClose.invoke(null); } catch (Exception ignore) {}
                    }
                }
                return allOk;
            } catch (Throwable t) {
                Log.w(TAG, "LED batch exception: " + t.getMessage());
                return false;
            }
        }
    }

    /** Is there an explicit mouth op after an OFF in the batch (if yes, no auto-restore needed). */
    private static boolean hasMouthOpLater(java.util.List<LedOp> ops, int idx) {
        for (int i = idx + 1; i < ops.size(); i++) {
            if ("ledSetMouth".equals(ops.get(i).method)) return true;
        }
        return false;
    }

    /** Restore the mouth inside the same open (previously only done while breathing. The record is untouched).
     *  The outside callers (burst / setOff) open and close on their own. They use the public version below. */
    private static void restoreMouthLocked() {
        if (sMouth == null || sMouthMode != 1) return;
        try {
            Object r = sMouth.invoke(null,
                    (Object[]) new Object[]{sMouthRun, sMouthBreathe, sMouthOffDur, sMouthPlay, sMouthMode});
            boolean raw = r instanceof Boolean ? (Boolean) r : false;
            Log.d(TAG, (!raw ? "LED OK: " : "LED FAIL: ") + "mouth restore mode=1 raw=" + raw);
        } catch (Throwable t) {
            Log.w(TAG, "LED mouth restore exception: " + t.getMessage());
        }
    }

    /** For outside use: restore the mouth after a global OFF (previously only done while breathing). Opens and closes once on its own. */
    public static boolean restoreMouthAfterOff() {
        synchronized (DRIVER_LOCK) {
            try {
                if (!ensureInit()) return false;
                Object openRes = sOpen.invoke(null);
                if (openRes instanceof Boolean && !(Boolean) openRes) return false;
                try {
                    restoreMouthLocked();
                    return true;
                } finally {
                    if (sClose != null) {
                        try { sClose.invoke(null); } catch (Exception ignore) {}
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "LED mouth restore exception: " + t.getMessage());
                return false;
            }
        }
    }

    /** When the batch succeeds, record the mirror one by one (same as lighting one by one. OFF clears head+eye and does not touch the mouth record). */
    private static void recordOp(LedOp op) {
        if ("ledSetOFF".equals(op.method)) {
            recordHeadOff();
            recordEyeOff();
        } else if ("ledSetHead".equals(op.method)) {
            recordHead((Integer) op.args[0], (Integer) op.args[1],
                    (Integer) op.args[2], (Integer) op.args[3]);
        } else if ("ledSetEye".equals(op.method)) {
            recordEye((Integer) op.args[0], (Integer) op.args[1],
                    (Integer) op.args[2], (Integer) op.args[3]);
        } else if ("ledSetMouth".equals(op.method)) {
            recordMouth((Integer) op.args[0], (Integer) op.args[1], (Integer) op.args[2],
                    (Integer) op.args[3], (Integer) op.args[4]);
        }
    }

    private static boolean callLedReflectLocked(String method, Object[] args, String desc) {
        try {
            if (!ensureInit()) return false;
            java.lang.reflect.Method m = methodFor(method);
            if (m == null) {
                Log.w(TAG, "LED method not found: " + method);
                return false;
            }
            Object openRes = sOpen.invoke(null);
            if (openRes instanceof Boolean && !(Boolean) openRes) {
                Log.w(TAG, "LedControl.open() failed for " + desc);
                return false;
            }
            boolean raw = false;
            try {
                Object r = m.invoke(null, args);
                raw = r instanceof Boolean ? (Boolean) r : false;
            } finally {
                if (sClose != null) {
                    try { sClose.invoke(null); } catch (Exception ignore) {}
                }
            }
            // The return value of libhead_led.so is inverted: ioctl success returns 0(false), failure returns 0xF2(true), see MouthLedData:100
            boolean ok = !raw;
            Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + desc + " raw=" + raw);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "LED exception " + desc + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Full-parameter direct drive (p1..p8 passed through as-is). MainActivity used to call
     * header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, MAX, p8) /
     * header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, MAX, p8) over binder.
     * Under pure-direct, p3/p4 keep their original values. The behavior does not change.
     */
    public static boolean setHead5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        boolean ok = callLedReflect("ledSetHead",
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
        if (ok) recordHead(p1, p2, p3, p4);
        return ok;
    }

    public static boolean setEye5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        boolean ok = callLedReflect("ledSetEye",
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
        if (ok) recordEye(p1, p2, p3, p4);
        return ok;
    }

    // Default: equivalent to MainActivity's translateColor
    public static boolean headSolid(int color) {
        return setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }
    public static boolean clear() { return setOff(); }

    /**
     * Under pure-direct, replaces the AIDL stop5MicEarLED / stop5MicEyeLED.
     * Independent stop: an empty mask (p3/p4=0) written back to the same zone — it only turns itself off.
     * eye / head / wifi / pad do not affect each other. The old path ran ledSetOFF (cleared everything in one shot).
     * To turn off the whole set now, use clear()/setOff() first.
     */
    public static boolean stopHead5Mic() {
        boolean ok = setHead5MicRaw(7, 9, 0, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        if (ok) recordHeadOff();
        return ok;
    }
    public static boolean stopEye5Mic() {
        boolean ok = setEye5MicRaw(7, 9, 0, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        if (ok) recordEyeOff();
        return ok;
    }

    // -- Last light-state mirror --------------------------------------------------
    // The hardware is write-only and cannot be read back — record here the last round of parameters sent through this class,
    // so /api/led/state/get can return them to the front end for the mirror (used for pressing each light to test). Only recorded on success.
    // -1 = never lit since boot. Note: debugJniLed passes raw straight through and does not go through here, so during that time
    // the mirror lags (it usually sends a stop at the end, and that syncs it back).
    private static volatile int sHeadP1 = -1;
    private static volatile int sHeadP2 = -1;
    private static volatile int sHeadP3 = -1;
    private static volatile int sHeadP4 = -1;
    private static volatile int sEyeP1 = -1;
    private static volatile int sEyeP2 = -1;
    private static volatile int sEyeP3 = -1;
    private static volatile int sEyeP4 = -1;
    private static volatile int sMouthRun = -1;
    private static volatile int sMouthBreathe = -1;
    private static volatile int sMouthOffDur = -1;
    private static volatile int sMouthPlay = -1;
    private static volatile int sMouthMode = -1;

    private static void recordHead(int p1, int p2, int p3, int p4) {
        sHeadP1 = p1; sHeadP2 = p2; sHeadP3 = p3; sHeadP4 = p4;
    }
    private static void recordEye(int p1, int p2, int p3, int p4) {
        sEyeP1 = p1; sEyeP2 = p2; sEyeP3 = p3; sEyeP4 = p4;
    }
    private static void recordMouth(int runTime, int breathe, int off, int play, int mode) {
        sMouthRun = runTime; sMouthBreathe = breathe; sMouthOffDur = off;
        sMouthPlay = play; sMouthMode = mode;
    }
    private static void recordHeadOff() {
        sHeadP1 = 0; sHeadP2 = 0; sHeadP3 = 0; sHeadP4 = 0;
    }
    private static void recordEyeOff() {
        sEyeP1 = 0; sEyeP2 = 0; sEyeP3 = 0; sEyeP4 = 0;
    }

    /** Last head/eye parameters (p1-p4) — used to restore the light after ledPadSet turns it off independently.
     *  Returns null when never lit (-1) / turned off (0). Do not restore. */
    public static int[] lastHeadParams() {
        if (sHeadP1 <= 0) return null;
        return new int[]{sHeadP1, sHeadP2, sHeadP3, sHeadP4};
    }
    public static int[] lastEyeParams() {
        if (sEyeP1 <= 0) return null;
        return new int[]{sEyeP1, sEyeP2, sEyeP3, sEyeP4};
    }
    /** Last light-state JSON (all int, no escaping needed): never lit = -1, turned off = 0. */
    public static String lastStateJson() {
        return "{\"ok\":true,\"head\":{\"color\":" + sHeadP1
                + ",\"brightness\":" + sHeadP2
                + ",\"p3\":" + sHeadP3 + ",\"p4\":" + sHeadP4 + "}"
                + ",\"eye\":{\"color\":" + sEyeP1
                + ",\"brightness\":" + sEyeP2
                + ",\"p3\":" + sEyeP3 + ",\"p4\":" + sEyeP4 + "}"
                + ",\"mouth\":{\"runTime\":" + sMouthRun
                + ",\"breatheMs\":" + sMouthBreathe
                + ",\"offMs\":" + sMouthOffDur
                + ",\"playMs\":" + sMouthPlay
                + ",\"mode\":" + sMouthMode + "}}";
    }
}
