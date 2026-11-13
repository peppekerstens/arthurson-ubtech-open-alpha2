package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * Unified entry point exposed to the upper layer (MainActivity/HttpServer). pure-direct mode:
 * all chest/head hardware goes only over /dev/ttyS1 + /dev/ttyS3 + libhead_led.so JNI,
 * and no longer binds com.ubtechinc.alpha2services (the APK is no longer on the device, no fallback).
 *
 * <p>Measured local ttyS1/ttyS3 as 777, so a normal app can also open them directly. If open fails,
 * isDirectAvailable()=false, the caller reports the error directly, and no binder fallback is tried at all.</p>
 */
public final class HardwareDirectManager {
    private static final String TAG = "HardwareDirectManager";
    private static HardwareDirectManager sInstance;

    private final DirectChestController chest;
    private final DirectHeadController head;
    private boolean directReady = false;

    private HardwareDirectManager(Context ctx) {
        DirectSerialPort chestPort = DirectSerialPort.forChest();
        DirectSerialPort headPort = DirectSerialPort.forHead();
        this.chest = new DirectChestController(chestPort);
        this.head  = new DirectHeadController(headPort);
    }

    public static synchronized HardwareDirectManager get(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx is null");
        if (sInstance == null) sInstance = new HardwareDirectManager(ctx.getApplicationContext());
        return sInstance;
    }

    /** Try to open the direct serial ports and return whether it succeeded. Call it on a background thread in MainActivity.onCreate. */
    public synchronized boolean tryEnableDirect() {
        boolean c = chest.open();
        boolean h = head.open();
        // A head board failure does not affect the chest board servos. If either succeeds, it counts as partially available
        directReady = c || h;
        Log.i(TAG, "tryEnableDirect chest=" + c + " head=" + h + " directReady=" + directReady);
        if (!directReady) {
            chest.close();
            head.close();
        }
        return directReady;
    }

    public boolean isDirectAvailable() { return directReady && (chest.isAvailable() || head.isAvailable()); }

    public DirectChestController chest() { return chest; }
    public DirectHeadController head() { return head; }

    public void release() {
        synchronized (this) {
            chest.close();
            head.close();
            directReady = false;
        }
    }

    /** Convenience: servo direct send (pure-direct, no binder fallback, failure returns false directly).
     *  2026-09-09: check the chest board itself, not "any board" (when only the head board is alive, a chest send is always false.
     *  Returning false early beats dying inside send). */
    public boolean chestSendSingle(byte id, int angle, short time) {
        if (!chest.isAvailable()) return false;
        return chest.setSingleServo(id, angle, time);
    }
}
