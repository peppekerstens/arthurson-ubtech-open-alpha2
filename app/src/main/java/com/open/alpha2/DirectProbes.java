package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

/**
 * Direct serial port readiness probes.
 *
 * ApiDispatcher, UbxApi, LedCenter, DeviceStatus, SonarCenter, ChestQuery and
 * XiaozhiBridge each inlined the same try/catch around
 * {@code HardwareDirectManager.get(ctx).chest()/head().isAvailable()} in
 * {@code directChestReady()}/{@code directHeaderReady()}/{@code headerReady()}/
 * {@code chestReady()}. This class holds that code in one place. Behavior is
 * unchanged (an exception returns false).
 */
public final class DirectProbes {
    private DirectProbes() {}

    public static boolean isChestReady(Context ctx) {
        try {
            return HardwareDirectManager.get(ctx.getApplicationContext()).chest().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHeadReady(Context ctx) {
        try {
            return HardwareDirectManager.get(ctx.getApplicationContext()).head().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}

