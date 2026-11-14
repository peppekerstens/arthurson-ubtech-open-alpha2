package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 1.1.7.3 Minimal local replacement for this generation of alpha2services (pure-direct).
 * Does not copy the 9991 classes of 3.002. Implements only the chest/head direct drive
 * that open-alpha2 truly needs:
 *   - DirectSerialPort (chest /dev/ttyS1, head /dev/ttyS3)
 *   - 5-mic LED JNI branch (DirectLedController)
 *
 * <p>The robot no longer has com.ubtechinc.alpha2services. No binder fallback:
 * if start() fails, the caller reports the error directly.</p>
 */
public final class LocalAlpha2Services {
    private static final String TAG = "LocalAlpha2Services";
    private final HardwareDirectManager direct;

    public LocalAlpha2Services(Context ctx) {
        this.direct = HardwareDirectManager.get(ctx);
    }

    /** Call on the background thread of MainActivity.onCreate */
    public boolean start() {
        boolean ok = direct.tryEnableDirect();
        Log.i(TAG, "LocalAlpha2Services start direct=" + ok);
        return ok;
    }

    public void stop() { direct.release(); }

    public boolean isDirectActive() { return direct.isDirectAvailable(); }

    // LED uses JNI. Does not depend on whether the serial port is available.
    public boolean ledHead(int color) { return DirectLedController.headSolid(color); }
    public boolean ledMouthBreathe(int speed) { return DirectLedController.setMouth(Integer.MAX_VALUE, speed, 0, Integer.MAX_VALUE, 1); }
    public boolean ledOff() { return DirectLedController.clear(); }
}
