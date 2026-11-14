package com.ubtechinc.alpha.jni.headkey;

import android.util.Log;

/**
 * JNI bridge for the 3.002 libhead_key_mgr.so (rk29-keypad, /dev/input/event*).
 *
 * <p>The class name + method signatures must match the .so exported symbols word for
 * word (extracted from the 3.002 APK classes2.dex,
 * confirmed by a home-built DexSig parser):
 * {@code Java_com_ubtechinc_alpha_jni_headkey_HeadKeyMgr_Init/nativeInit/
 * nativeThreadStart/nativeThreadStop} (all public instance native), plus
 * the original same-package private stubs {@code convertHeadKeyEvent(I)[B /
 * newConvertHeadKeyEvent(I)V /
 * publishEvent(B)V} (the native may call GetMethodID; a missing one would silently
 * kill the native thread,
 * so the same-signature stubs are kept; a call logs it for checking).</p>
 *
 * <p>Callback: the native key thread calls {@code onNativeCallback(I)} (a public
 * instance method, the original intent is for
 * a subclass / this class to implement the conversion and then publish). This class
 * has an empty implementation, the app-side HeadKeyPoller extends and overrides it,
 * feeding the code value into the existing gesture synthesis pipeline. The native
 * also writes {@code /sdcard/keyjnilog.txt}
 * debug logs, to compare against the raw code.</p>
 *
 * <p>Timing (experience): {@code new → Init() → nativeInit() → nativeThreadStart()};
 * stop with {@code nativeThreadStop()}. Any step that fails throws; the caller falls
 * back
 * to reading /dev/input/event0 in Java.</p>
 */
public class HeadKeyMgr {
    private static final String TAG = "HeadKeyMgr";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("head_key_mgr");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary head_key_mgr failed: " + t.getMessage());
        }
    }

    /** Whether the .so is loaded; when not loaded, every native call throws UnsatisfiedLinkError. */
    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    public native boolean Init();

    public native void nativeInit();

    public native void nativeThreadStart();

    public native void nativeThreadStop();

    /** Native key thread callback (a subclass overrides it; the default is an empty implementation). */
    public void onNativeCallback(int code) {
    }

    // ---- original same-signature private stubs (guard the native GetMethodID from missing; a call logs it) ----
    @SuppressWarnings("unused")
    private byte[] convertHeadKeyEvent(int code) {
        Log.d(TAG, "convertHeadKeyEvent(" + code + ") stub hit");
        return null;
    }

    @SuppressWarnings("unused")
    private void newConvertHeadKeyEvent(int code) {
        Log.d(TAG, "newConvertHeadKeyEvent(" + code + ") stub hit");
    }

    @SuppressWarnings("unused")
    private void publishEvent(byte b) {
        Log.d(TAG, "publishEvent(" + b + ") stub hit");
    }
}
