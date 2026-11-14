package com.ubtechinc.alpha.jni;

import android.util.Log;

/**
 * JNI bridge to libhead_led.so v3.002 (/dev/led_eye). The ioctl ABI matches
 * v1.1.7.3: On=0x4c00 / OFF=0x4c01 / Eye=0x4c02 / Head=0x4c03 /
 * Mouth=0x4c04. All are _IOW('L'). Disassembly verified each value.
 *
 * <p>The package name and method signatures must match the .so export symbols
 * exactly (extracted from classes2.dex of the 3.002 APK, confirmed with a
 * custom DexSig parser):
 * {@code Java_com_ubtechinc_alpha_jni_LedControl_open/close/ledSetOFF/ledSetOn/
 * ledSetEye/ledSetHead/ledSetMouth}. All are {@code public static native}.</p>
 *
 * <p>The old {@code com.ubtechinc.mic5.LedControl} differs in two points:
 * the package name, and {@code ledSetOFF(I)} has one extra int (disassembly
 * confirms this argument goes to the log and never to the hardware; pass 0).
 * The other signatures match exactly.</p>
 *
 * <p>Return values: only ledSet* invert the sense (native returns 0/false on
 * success and 0xF2/true on failure; disassembly confirms ioctl success
 * returns 0, see MouthLedData:95-103). open()/close() do not invert; read
 * them literally (the {@code if(!open)fail} in
 * DirectLedController.callLedReflect means that).</p>
 */
public class LedControl {
    private static final String TAG = "LedControl3002";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("head_led");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary head_led failed: " + t.getMessage());
        }
    }

    public static native boolean open();

    public static native boolean close();

    /** arg goes to the native log only (disassembly: after mov r3,r4 it is passed only to __android_log_print). Pass 0. */
    public static native boolean ledSetOFF(int dummy);

    public static native boolean ledSetOn(int i);

    public static native boolean ledSetEye(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetHead(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetMouth(int runTime, int breatheSpeedMs, int offDurationMs, int playDurationMs, int effectMode);
}
