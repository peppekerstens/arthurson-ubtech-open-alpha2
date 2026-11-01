package com.ubtechinc.alpha.hardware;

/**
 * Click codes of the head + and - keys (pure Java, unit test HeadKeyClickTest).
 *
 * <p>Robot session 2026-11-01: libhead_key_mgr.so gave {@code 0x3d01} for the + key and
 * {@code 0x3c01} for the - key, one code for each press (6 of 6 presses) and no release
 * code. Before, the app knew only the press/release codes 0x5a01 to 0x5f01 and logged
 * "native key unmapped".
 *
 * <p>A click gives a press and a release gesture: + is volume up (0x5c, 0x5d), - is
 * volume down (0x5a, 0x5b). GestureCenter then does one volume step, the same as a short
 * press of the old codes. A click never moves the robot.
 */
public final class HeadKeyClick {
    /** The event code (high byte) of a - key click. */
    public static final int CLICK_MINUS = 0x3c;
    /** The event code (high byte) of a + key click. */
    public static final int CLICK_PLUS = 0x3d;

    private static final int[] MINUS = {0x5a, 0x5b};
    private static final int[] PLUS = {0x5c, 0x5d};
    private static final int[] NONE = {};

    private HeadKeyClick() {}

    /** True when the native code is a click code. */
    public static boolean isClick(int nativeCode) {
        int ev = (nativeCode >> 8) & 0xFF;
        return ev == CLICK_MINUS || ev == CLICK_PLUS;
    }

    /** The gesture codes for a native click code, in order. Empty for each other code. */
    public static int[] gestures(int nativeCode) {
        int ev = (nativeCode >> 8) & 0xFF;
        if (ev == CLICK_MINUS) return MINUS.clone();
        if (ev == CLICK_PLUS) return PLUS.clone();
        return NONE;
    }
}
