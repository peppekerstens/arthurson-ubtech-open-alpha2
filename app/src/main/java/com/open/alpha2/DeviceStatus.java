package com.open.alpha2;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.Log;

import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Direct device-status reads: WiFi/Bluetooth (standard Android framework, not the body service),
 * battery receiver (including low-battery auto-squat) + battery/status, accelerometer set/get +
 * SensorEventListener, and the handleApi status health aggregate (statusResponse).
 * The low-battery squat goes through the same passed-in ActionDirect; the synchronized lock is on this class
 * (all callers share one instance, so mutual exclusion is equivalent; see AudioCenter for the same pattern).
 * No reboot (the App has no REBOOT permission; verified on device, always SecurityException);
 * TtsCenter readiness is read through the same passed-in instance.
 */
public final class DeviceStatus implements SensorEventListener {
    private static final String TAG = "DeviceStatus";

    private final Context appContext;
    private final Handler mainHandler;
    private final ActionDirect actionDirect;
    private final UbxPlayer ubxPlayer;
    private final TtsCenter ttsCenter;

    public DeviceStatus(Context context, Handler mainHandler, ActionDirect actionDirect,
            UbxPlayer ubxPlayer, TtsCenter ttsCenter) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.actionDirect = actionDirect;
        this.ubxPlayer = ubxPlayer;
        this.ttsCenter = ttsCenter;
        // Moved in from the original registerGestureController(): the sensorManager obtained via
        // appContext is the same service as the Activity's (audioManager is unchanged, stays on the gesture side).
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        accelerometerSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
    }

    public HttpServer.ApiResponse wifiStatus() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            boolean enabled = wm.isWifiEnabled();
            String ssid = "";
            int ipInt = 0;
            if (wm.getConnectionInfo() != null) {
                ssid = wm.getConnectionInfo().getSSID();
                ipInt = wm.getConnectionInfo().getIpAddress();
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled
                    + ",\"ssid\":\"" + MainActivity.jsonSafe(ssid) + "\",\"ip\":\""
                    + Formatter.formatIpAddress(ipInt) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }

    public HttpServer.ApiResponse btStatus() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                // Same shape as with an adapter (enabled/name always present), so callers do not need per-key null checks.
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":false,\"enabled\":false,\"name\":\"\"}");
            }
            boolean enabled = adapter.isEnabled();
            String name = adapter.getName();
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":true,\"enabled\":" + enabled
                    + ",\"name\":\"" + MainActivity.jsonSafe(name) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }

    /** Shared by voice FUNCTION: turn Bluetooth on/off (on API22, BluetoothAdapter.enable()/disable()
     *  still work; requires BLUETOOTH_ADMIN, see Manifest). Returns true=target state reached,
     *  false=no adapter or the system refused. Safe to call from any thread. */
    public boolean setBluetoothEnabled(boolean on) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            if (on == adapter.isEnabled()) return true;
            return on ? adapter.enable() : adapter.disable();
        } catch (SecurityException se) {
            Log.w(TAG, "setBluetoothEnabled permission denied", se);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "setBluetoothEnabled failed", e);
            return false;
        }
    }

    /** Shared by voice FUNCTION: turn WiFi on/off (via WifiManager.setWifiEnabled;
     *  requires CHANGE_WIFI_STATE, see Manifest). Returns true=target state reached or the call succeeded,
     *  false=no service or the system refused. Safe to call from any thread. */
    public boolean setWifiEnabled(boolean on) {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            if (on == wm.isWifiEnabled()) return true;
            return wm.setWifiEnabled(on);
        } catch (SecurityException se) {
            Log.w(TAG, "setWifiEnabled permission denied", se);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "setWifiEnabled failed", e);
            return false;
        }
    }

    private BroadcastReceiver batteryReceiver;
    /** Low-battery squat latch: after squatting once at 10%, do not repeat until the battery has charged or recovered above 12%. */
    private boolean batteryLowSquatDone = false;

    private volatile int lastBatteryLevel = -1;
    private volatile int lastBatteryScale = -1;
    private volatile boolean lastBatteryCharging = false;
    private volatile String lastBatteryStatus = "unknown";

    /**
     * Battery/charging is NOT available through Alpha2RobotApi (see capabilities.md
     * "Battery and charging") - the chest board does stream it on the serial link
     * (CHEST_SEND_POWER), but the SDK never surfaces a getter for it. The documented,
     * reliable path for an on-robot app is the standard Android battery intent instead.
     * ACTION_BATTERY_CHANGED is a sticky broadcast, so this also fires immediately with
     * the current state upon registration.
     */
    public void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                lastBatteryLevel = level;
                lastBatteryScale = scale;
                lastBatteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING) || plugged != 0;
                lastBatteryStatus = batteryStatusName(status);
                EventBus.get().publish("battery", "{\"level\":" + level + ",\"scale\":" + scale
                        + ",\"charging\":" + lastBatteryCharging + ",\"status\":\"" + lastBatteryStatus + "\"}");
                // User requirement: when the battery drops to 10% (and is not charging), squat once automatically.
                // ACTION_BATTERY_CHANGED is a sticky broadcast, so registration fires once immediately; the latch prevents repeats;
                // charging or recovery above 12% resets it.
                int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
                if (lastBatteryCharging || pct > 12) {
                    batteryLowSquatDone = false;
                } else if (pct >= 0 && pct <= 10 && !batteryLowSquatDone) {
                    batteryLowSquatDone = true;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            if (actionDirect.playActionDirect(ActionDirect.STOP_RECOVERY_ACTION_ID)
                                    != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                                Log.w(TAG, "low-battery squat not started: " + ubxPlayer.lastError());
                            }
                        }
                    }, "LowBatterySquat").start();
                }
            }
        };
        appContext.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** Shared by onDestroy: same guard as the old MainActivity style (null + IllegalArgumentException). */
    public void unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                appContext.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static String batteryStatusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            default: return "unknown";
        }
    }

    // -- Battery/charging: NOT in Alpha2RobotApi (see capabilities.md); read via
    // the standard Android BatteryManager broadcast this Activity already listens
    // for and caches. ------------------------------------------------------------
    public HttpServer.ApiResponse batteryStatus() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"level\":" + lastBatteryLevel + ","
                + "\"scale\":" + lastBatteryScale + ","
                + "\"charging\":" + lastBatteryCharging + ","
                + "\"status\":\"" + lastBatteryStatus + "\"}");
    }

    /** Used by the system/status power section (originally read the MainActivity field directly). */
    public int getBatteryLevel() { return lastBatteryLevel; }
    /** Used by the system/status power section (originally read the MainActivity field directly). */
    public int getBatteryScale() { return lastBatteryScale; }
    /** Used by the system/status power section (originally read the MainActivity field directly). */
    public boolean isBatteryCharging() { return lastBatteryCharging; }
    /** Used by the system/status power section (originally read the MainActivity field directly). */
    public String getBatteryStatus() { return lastBatteryStatus; }

    // -- Accelerometer (IMU): standard Android SensorManager, NOT the UBTECH AIDL SDK -
    // see docs/capabilities.md "IMU / accelerometer" in the Alpha2OpenSdk repo and the
    // HelloAlpha example (examples/HelloAlpha), which reads it the same way. The robot's
    // only real motion sensor; readings are gravity-relative (tilt), not true dynamic
    // acceleration. Off by default - only registered while at least one browser tab has
    // it toggled on via the "accelerator/set" endpoint below, so idle sessions don't pay
    // for sensor callbacks/WebSocket traffic nobody is watching.
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private volatile boolean accelerometerEnabled = false;

    /**
     * Turns the accelerometer feed on/off. Safe to call repeatedly - a no-op if already
     * in the requested state. registerListener()/unregisterListener() must run on a
     * thread with a Looper (per SensorManager's contract) - both are called here on the
     * main thread, matching how the sensorManager used to be set up in
     * MainActivity.registerGestureController() in onCreate().
     */
    public synchronized void setAccelerometerEnabled(boolean enabled) {
        if (sensorManager == null || accelerometerSensor == null) {
            accelerometerEnabled = false;
            return;
        }
        if (enabled == accelerometerEnabled) {
            return;
        }
        if (enabled) {
            // SENSOR_DELAY_NORMAL, not _UI: verified on hardware in the Alpha2OpenSdk
            // HelloAlpha example (see docs/capabilities.md "IMU / accelerometer") - the
            // RK3288's gsensor driver reliably delivers events at this rate. _UI was
            // observed to register successfully but never actually deliver events.
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(this, accelerometerSensor);
        }
        accelerometerEnabled = enabled;
        if (!enabled) sLatestAccel = null;
    }

    // -- SensorEventListener (accelerometer only - see setAccelerometerEnabled()) -------
    private long lastAccelLogMs = 0;
    // WS throttle window (measured: this gsensor fires at ~100Hz; publishing per frame = main-thread JSON + WS
    // direct write + browser DOM all jammed, button events queue for several seconds, ANR death (see on-device InputDispatcher
    // spent 2000ms + ANR Killing logcat). 10Hz is enough for the chart; the window is measured with a monotonic clock.
    private long lastAccelPublishMs = 0;
    private static volatile float[] sLatestAccel = null;
    private static volatile long sLatestAccelAtMs = 0;

    /** The newest accelerometer sample (m/s^2, x y z), or null while the accelerometer is off. */
    static float[] latestAccel() {
        return sLatestAccel;
    }

    /** SystemClock.elapsedRealtime() of {@link #latestAccel()}. */
    static long latestAccelAtMs() {
        return sLatestAccelAtMs;
    }
    private static final long ACCEL_PUBLISH_MIN_INTERVAL_MS = 100;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        // Rate-limited (every ~2s) rather than per-sample: confirms whether the sensor
        // itself is actually delivering events at all, without flooding logcat - a
        // normal accelerometer at SENSOR_DELAY_NORMAL fires far more often than that.
        long now = System.currentTimeMillis();
        if (now - lastAccelLogMs > 2000) {
            lastAccelLogMs = now;
            Log.i(TAG, "onSensorChanged firing: x=" + event.values[0]
                    + " y=" + event.values[1] + " z=" + event.values[2]);
        }
        // Published as-is (m/s^2, gravity-relative - see docs/capabilities.md). The
        // browser-side chart/UI is responsible for any smoothing/scaling it wants.
        // Push only after throttling (see the ANR note above; uses elapsedRealtime because wall clock can jump).
        long tick = android.os.SystemClock.elapsedRealtime();
        // Newest raw sample for the servo read stream (ServoReadStream, alpha2-rl#3), before the
        // 10 Hz throttle of the "accel" event.
        sLatestAccel = new float[]{event.values[0], event.values[1], event.values[2]};
        sLatestAccelAtMs = tick;
        if (tick - lastAccelPublishMs < ACCEL_PUBLISH_MIN_INTERVAL_MS) return;
        lastAccelPublishMs = tick;
        EventBus.get().publish("accel", "{\"x\":" + event.values[0]
                + ",\"y\":" + event.values[1]
                + ",\"z\":" + event.values[2] + "}");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action needed - the Alpha2's accelerometer accuracy is not meaningfully
        // actionable here (see docs/capabilities.md).
    }

    // -- Accelerometer (IMU): standard Android SensorManager, not SDK-gated -
    // see setAccelerometerEnabled()/onSensorChanged() above. Readings stream out
    // as "accel" WebSocket events while enabled, not through this JSON response. -
    public HttpServer.ApiResponse accelerometerSet(Map<String, String> query) {
        final boolean on = ApiValidator.requireBoolean(query, "on");
        // registerListener()/unregisterListener() must run on the thread that
        // owns sensorManager's Looper (the main thread here) - this handler
        // itself runs on an HttpServer worker thread, so hop over via mainHandler
        // and wait for it to actually apply before answering.
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                setAccelerometerEnabled(on);
                latch.countDown();
            }
        });
        boolean done;
        try {
            done = latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        // If the wait times out, the main thread is stuck: return ok:false so the panel can reset the button; do not claim it is on.
        if (!done) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"main thread busy, try again\"}");
        }
        if (on && accelerometerSensor == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"no accelerometer sensor available on this device\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled + "}");
    }

    public HttpServer.ApiResponse accelerometerGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled
                + ",\"available\":" + (accelerometerSensor != null) + "}");
    }

    /** Is the caller on the main thread (the mapped physical button is, the HTTP worker is not). */
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shared by mapped events/panel: read the actual accelerometer on/off state (self-maintained). */
    public boolean isAccelerometerEnabled() {
        return accelerometerEnabled;
    }

    /** Shared by mapping: accelerometer single-button toggle (same main-thread + latch style as accelerometerSet).
     *  Main thread via the physical button: return true right after post, do not wait on the latch
     *  (waiting on oneself deadlocks for 2 seconds,
     *  taken stays false and button events freeze, see traces; the state still flips).
     *  The HTTP worker does wait (the return value is honest). Returns true = the hardware exists and the state flipped (both on/off count as done). */
    public boolean toggleAccelerometer() {
        if (onMainThread()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    setAccelerometerEnabled(!accelerometerEnabled);
                }
            });
            return accelerometerSensor != null;
        }
        final boolean[] flipped = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                setAccelerometerEnabled(!accelerometerEnabled);
                flipped[0] = (accelerometerSensor != null);
                latch.countDown();
            }
        });
        boolean done;
        try {
            done = latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        // If the wait times out, the main thread is stuck: return the honest value of taken; do not claim success.
        if (!done) return false;
        return flipped[0];
    }

    // -- Health-status aggregate --
    // Thin delegates for chest/header readiness (implementation in DirectProbes).
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    private boolean directHeaderReady() {
        return DirectProbes.isHeadReady(appContext);
    }

    public HttpServer.ApiResponse statusResponse() {
        String appVer = "?";
        try {
            appVer = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        // pure-direct: chest/header availability is now reported by the direct serial port, no longer via binder.
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"appVersion\":\"" + appVer + "\","
                + "\"apiLevel\":" + android.os.Build.VERSION.SDK_INT + ","
                + "\"chestAvailable\":" + directChestReady() + ","
                + "\"headerAvailable\":" + directHeaderReady() + ","
                + "\"androidTtsReady\":" + ttsCenter.isReady() + "}");
    }

    // No reboot: the App is a third-party sideload with no REBOOT permission (signature|system); on device it returns
    // "Neither user 10020 nor current process has android.permission.REBOOT", and su
    // is also unreachable from the app context (Permission denied) - the UUID card now shows a manual-reboot hint.

    // Local IP for the panel URL/display (updatePanelUrlDisplay/copy-link read it here).
    public String getWifiIp() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            int ipInt = wm.getConnectionInfo().getIpAddress();
            String wifiIp = Formatter.formatIpAddress(ipInt);
            if (wifiIp != null && !wifiIp.equals("0.0.0.0") && !wifiIp.isEmpty()) {
                return wifiIp;
            }
            // In hotspot AP mode or when not connected as STA, WifiManager returns 0.0.0.0; enumerate the network interfaces to find a site-local one
            try {
                java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    java.net.NetworkInterface intf = en.nextElement();
                    java.util.Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String host = addr.getHostAddress();
                            // Only the private ranges 192.168/10./172.16/12 count; see also the isSiteLocal note: fd00::/8
                            // IPv6 ULA is not used by this device, so it is not checked).
                            if (host != null && (host.startsWith("192.168.") || host.startsWith("10.")
                                    || isPrivate172(host))) {
                                return host;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return wifiIp != null ? wifiIp : "<device-ip>";
        } catch (Exception e) {
            return "<device-ip>";
        }
    }

    /** 172.16.0.0–172.31.255.255 private range (RFC1918 172.16/12). */
    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) return false;
        int dot = host.indexOf('.', 4);
        if (dot < 0) return false;
        try {
            int second = Integer.parseInt(host.substring(4, dot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

