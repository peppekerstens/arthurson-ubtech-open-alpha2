package com.ubtechinc.alpha.hardware;

/**
 * Serial device paths for the hardware of this 1.1.7.3 generation.
 * The 3.002 / 2.004 APK hardcodes /dev/ttySAC0/1 (Samsung Exynos),
 * while the RK3288 device (Android 5.1.1, 1.1.7.3.20) uses /dev/ttyS0/1.
 * Using the 3.002 libserial_port.so as-is fails to open, so a mapping fix is done here.
 *
 * <p>Source: androguard scan of the 1.1.7.3.20 AIDL_REFERENCE + device logcat_2026-07-02
 * + comparison of the 3.002/2.004 string tables.</p>
 */
public final class DirectSerialConfig {
    private DirectSerialConfig() {}

    // RK3288 (1.1.7.3.20 device) - dmesg verified: ff180000=ttyS0 (Bluetooth), ff190000=ttyS1 (chest front), ff1b0000=ttyS3 (head board)
    // ro.front.serial=/dev/ttyS1 (getprop on 3GKL2EJRDH)
    public static final String CHEST_TTY = "/dev/ttyS1";
    public static final String HEAD_TTY  = "/dev/ttyS3";

    // Compatibility fallback - the Exynos paths used by 3.002 + the old RK ttyS0
    public static final String CHEST_TTY_ALT = "/dev/ttySAC0";
    public static final String HEAD_TTY_ALT  = "/dev/ttySAC1";
    public static final String CHEST_TTY_ALT2 = "/dev/ttyS0";
    public static final String HEAD_TTY_ALT2  = "/dev/ttyS0";

    public static final int BAUDRATE = 115200;

    // 2026-09: the wire command words now live in RobotWire of this package. The old SDK constant tables are no longer referenced.
}
