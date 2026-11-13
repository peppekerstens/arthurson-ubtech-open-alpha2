package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.MouthLedData;
import com.ubtechinc.alpha.jni.LedControl;

import java.util.Map;

/**
 * LED layer: 5-mic head/eye, mouth, and low-level JNI/serial debug endpoints.
 */
public final class LedCenter {
    private static final String TAG = "LedCenter";

    // alpha2-brain#93: default brightness, the middle of the 1-9 range.
    // Used by the LED MCP tools when a call gives a color but no brightness.
    private static final int LED_DEFAULT_BRIGHTNESS = 5;

    private final Context appContext;
    private final Handler mainHandler;
    private final RingtoneCenter ringtoneCenter;
    // The motion gate of the app (alpha2-brain#130). ApiDispatcher sets it. Without a gate
    // a chest move command is refused: no gate means no approval (as in ActionDirect).
    private volatile MotionGate motionGate;

    public void setMotionGate(MotionGate gate) {
        motionGate = gate;
    }

    public LedCenter(Context context, Handler mainHandler, RingtoneCenter ringtoneCenter) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.ringtoneCenter = ringtoneCenter;
        registerVolumeChangeReceiver();
    }

    /** Shared by onDestroy: stop the pad LED worker, the meter worker, and unregister the volume broadcast. */
    public void shutdown() {
        padLedExecutor.shutdownNow();
        volumeMeterExecutor.shutdownNow();
        try {
            if (volumeChangeReceiver != null) {
                appContext.unregisterReceiver(volumeChangeReceiver);
            }
        } catch (Throwable ignore) {
        }
        volumeChangeReceiver = null;
    }

    // Any path that changes the system volume (physical V+/V-, the HTML volume slider,
    // Android settings) emits this broadcast. Listen here: the green bar follows a
    // volume change from any path, with no call-site changes needed.
    private BroadcastReceiver volumeChangeReceiver = null;

    private void registerVolumeChangeReceiver() {
        try {
            volumeChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null) return;
                        // Write the constant verbatim: this SDK has no AudioManager.EXTRA_VOLUME_STREAM_TYPE
                        int stream = intent.getIntExtra(
                                "android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                        if (stream != android.media.AudioManager.STREAM_MUSIC) return;
                        showVolumeMeter();
                        // Reverse sync: any path that changes the volume (the HTML slider,
                        // the physical +/- keys, voice up/down) emits this broadcast.
                        // Publish volume_changed to WS so both HTML sliders follow at once.
                        // (Forward HTML->robot already syncs through the /api/audio/volume/set
                        // reply; see appendLog in app-log.js and onVolumeChangedEvent in
                        // app-servo.js.)
                        // Read AudioManager and build a string only; no blocking; fits the
                        // onReceive time limit.
                        try {
                            android.media.AudioManager am = (android.media.AudioManager)
                                    appContext.getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) {
                                int vol = am.getStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC);
                                int max = am.getStreamMaxVolume(
                                        android.media.AudioManager.STREAM_MUSIC);
                                EventBus.get().publish("volume_changed",
                                        "{\"volume\":" + vol + ",\"max\":" + max + "}");
                            }
                        } catch (Throwable ignore) {
                        }
                    } catch (Throwable ignore) {
                    }
                }
            };
            appContext.registerReceiver(volumeChangeReceiver,
                    new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (Throwable t) {
            Log.w(TAG, "volume receiver register failed", t);
            volumeChangeReceiver = null;
        }
    }

    // -- Pad (+/-) physical key indicator lights -----------------------------------------------
    // Confirmed on real hardware: ledSetOn(14) = volume- light, ledSetOn(16) = volume+ light.
    // The firmware does not light them on its own. While a key is held, the app keeps
    // sending the lights. On release, the app sends OFF.
    // The wifi light (12/13) does not light on its own either; keep it manual
    // (three states: off/red/blue).
    private static final int PAD_LED_INDEX_MINUS = 14;
    private static final int PAD_LED_INDEX_PLUS = 16;
    private static final long PAD_LED_INTERVAL_MS = 80;
    // alpha2services is removed. No one else races /dev/led_eye. Retry only guards a rare open failure.
    private static final int PAD_LED_OPEN_ATTEMPTS = 3;
    private final java.util.concurrent.ExecutorService padLedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // Dedicated single thread for the volume meter. It must not share padLedExecutor:
    // while a key is held, the pad worker loops without yielding (one turn every 80 ms).
    // If shared, the meter update would queue until release (confirmed on real hardware:
    // a long volume- hold left the green head light stale; it jumped all at once on
    // release). With separate threads, both sides order through the
    // DirectLedController global lock, so they never conflict.
    private final java.util.concurrent.ExecutorService volumeMeterExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Post for meter updates (swallows RejectedExecutionException like postPadLed). */
    public void postVolumeMeter(Runnable r) {
        try {
            volumeMeterExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** onDestroy() calls shutdownNow() on the executor above, but a runnable that
     *  the wifi receiver posted earlier with postDelayed (1200 ms) can still fire
     *  afterwards. Posting at that point hits RejectedExecutionException on the main
     *  thread. Dropping one LED update during shutdown is the correct behaviour, so
     *  swallow it.
     *  Public because the mute key Xiaozhi switch and the mute LED send also borrow
     *  this single thread for background execution. */
    public void postPadLed(Runnable r) {
        try {
            padLedExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private volatile boolean padMinusHeld = false;
    private volatile boolean padPlusHeld = false;
    private volatile boolean padLedWorkerRunning = false;
    // Pad physical key state generation: +1 on each setPad*Held. The 1.5 s failsafe
    // light-off in GestureCenter after the two-key stop clears only if the generation
    // did not move (no new key press in the meantime). Otherwise it would turn off a
    // light the user then pressed (see stopAllViaPads).
    private final java.util.concurrent.atomic.AtomicInteger padHeldEpoch =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The gesture layer sets the physical key state (0x5a-0x5f), then calls padLedUpdate() right away. */
    public void setPadMinusHeld(boolean held) {
        padMinusHeld = held;
        padHeldEpoch.incrementAndGet();
    }

    public void setPadPlusHeld(boolean held) {
        padPlusHeld = held;
        padHeldEpoch.incrementAndGet();
    }

    /** Read the generation (for the failsafe light-off; see above). */
    public int getPadHeldEpoch() {
        return padHeldEpoch.get();
    }

    /**
     * Green volume meter for V+/V- (user request): read the live STREAM_MUSIC volume
     * and show 0-4 green lights on the head (only 1-4 are usable; see the bit map
     * above). Level 0 = all off, full = 4 lights.
     * Update on every key press and do not restore (disco, the LED page, or the next
     * key press overwrites it naturally).
     * Runs on the pad single thread, queues behind the pad light bursts, and never
     * races the JNI.
     */
    private static final int VOLUME_LED_GREEN = 2;
    private static final int VOLUME_LED_BRIGHTNESS = 9;

    public void showVolumeMeter() {
        postVolumeMeter(() -> {
            try {
                android.media.AudioManager am = (android.media.AudioManager)
                        appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                int level;
                int max;
                try {
                    level = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                } catch (Throwable t) {
                    return;
                }
                int count;
                if (level <= 0 || max <= 0) {
                    count = 0;
                } else {
                    count = Math.min(4, Math.max(1, (int) Math.round(level * 4.0 / max)));
                }
                int p4;
                int p3;
                switch (count) {
                    case 1: p4 = 1; p3 = 16; break;
                    case 2: p4 = 3; p3 = 24; break;
                    case 3: p4 = 7; p3 = 28; break;
                    case 4: p4 = 15; p3 = 30; break;
                    default: p4 = 0; p3 = 0; break;
                }
                DirectLedController.setHead5MicRaw(VOLUME_LED_GREEN, VOLUME_LED_BRIGHTNESS,
                        p3, p4, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * The firmware does not light 14/16 while +/- is held, so the app keeps the
     * light on actively during the hold and sends ledSetOFF on release to clean up.
     * Single-thread worker; no re-entry while running.
     */
    public synchronized void padLedUpdate() {
        // check-then-set must be atomic: two threads pressing at once must not start two workers that fight.
        if (padLedWorkerRunning) {
            return;
        }
        padLedWorkerRunning = true;
        postPadLed(() -> {
            int lastCombo = -1; // bit0=minus bit1=plus; resend only when the combo changes (no one else takes the light)
            try {
                for (;;) {
                    while (padMinusHeld || padPlusHeld) {
                        int combo = (padMinusHeld ? 1 : 0) | (padPlusHeld ? 2 : 0);
                        if (combo != lastCombo) {
                            // On a combo change (for example, two held and one released),
                            // turn everything off once.
                            // The hardware has no per-light off. An uncleared released
                            // light stays lit until both keys let go.
                            if (lastCombo != -1 && !assertPadLedsOffBurst()) {
                                Log.w(TAG, "pad combo-change off-burst failed");
                            }
                            assertPadLedsComboBurst();
                            lastCombo = combo;
                        }
                        Thread.sleep(PAD_LED_INTERVAL_MS);
                    }
                    if (!assertPadLedsOffBurst()) {
                        Log.w(TAG, "pad release off-burst failed, pads may stay lit");
                    }
                    if (!padMinusHeld && !padPlusHeld) break;
                    // Pressed again while turning off: back to the loop, do not hand over
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                padLedWorkerRunning = false;
            }
        });
    }

    /** Gap between open() attempts inside one burst (ms). */
    private static final long PAD_LED_RETRY_GAP_MS = 40;

    /** Pad LED open retry interval (the combo / off / wifi burst loops are identical word for word). */
    private static boolean sleepPadRetryGap() {
        try {
            Thread.sleep(PAD_LED_RETRY_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * Light the held combo burst: when open, light 14/16 according to
     * padMinusHeld/padPlusHeld (additive: two held, both light). Retry if the
     * open fails.
     */
    private boolean assertPadLedsComboBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            final boolean[] openOk = {false};
            // Wrap open->ioctl->close in the global driver lock: /dev/led_eye cannot
            // take concurrent opens, and a collision with the high-frequency disco
            // light can hang the hardware hard. Keep the retry sleep outside the lock.
            DirectLedController.runExclusive(() -> {
                try {
                    openOk[0] = LedControl.open();
                } catch (Throwable t) {
                    Log.w(TAG, "pad LED open() threw", t);
                }
                if (openOk[0]) {
                    try {
                        if (padMinusHeld) {
                            LedControl.ledSetOn(PAD_LED_INDEX_MINUS);
                        }
                        if (padPlusHeld) {
                            LedControl.ledSetOn(PAD_LED_INDEX_PLUS);
                        }
                    } finally {
                        try {
                            LedControl.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            if (openOk[0]) {
                if (attempt > 1) {
                    Log.d(TAG, "pad LED device opened on attempt " + attempt);
                }
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "pad LED open() failed " + PAD_LED_OPEN_ATTEMPTS
                + "x in a row (device busy?)");
        return false;
    }

    /**
     * Light-off burst after release: a global OFF (ledSetOFF is "clear all at
     * once"; head/eye/pad/wifi/mouth all die), then immediately restore the lights
     * that should stay on.
     *
     * The original bug, caught in real hardware logcat: this used to send only OFF
     * and then restore the mouth, leaving head/eye/pad/wifi black forever. On real
     * hardware, one V+/V- press-and-release made all the machine's lights flash off
     * and only the mouth remain. A disco dance step got cut off and the whole
     * section vanished. Wifi was the worst: applyWifiLedInternal restored wifi
     * every few hundred ms, so OFF->restore-wifi ping-ponged forever.
     *
     * Now use runBatch (OFF + restore in one open, zero blackout in between), the
     * same pattern as ledPadSet. keepPads=false: a physical key release means that
     * key's light(s) must go out; do not restore them from the mirror.
     */
    private boolean assertPadLedsOffBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(6);
            ops.add(DirectLedController.LedOp.off());
            // keepPads considers the music-playing indicator: while it is lit, a
            // physical key release must not switch it off with the pad.
            appendRestoreOps(ops, false, null);
            if (DirectLedController.runBatch(ops)) {
                // The global off also clears the pad test-light state (the hardware
                // reality: both die together; the mirror must not lie).
                // The flag operations share the same lock as ledPadSet. Do not
                // modify from both sides at once.
                synchronized (padToggleLock) {
                    padMinusLit = false;
                    padPlusLit = false;
                }
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "pad LED off: batch failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    /**
     * Restore the lights after a global OFF (head -> eye -> pad -> wifi), added
     * one by one.
     *
     * @param keepPads  false = a physical volume key released. Do not restore
     *                  either pad light (they must go out).
     * @param wifiOverride null = restore from wifiLedState; "red"/"blue" = restore
     *                     this new color; "off" = do not restore wifi (for
     *                     ledWifiSet(color=off)).
     * Head/eye restore only if they really lit (DirectLedController.lastHeadParams
     * returns null before the first call).
     *
     * Read the pad/wifi mirrors only while holding padToggleLock: ledPadSet and a
     * global OFF restore both work under this same lock. Without the lock, a read
     * can be half-stale and half-new. On real hardware, a rapid wifi color switch
     * (each switch is one global OFF) collided with the pad test light: the pad
     * flag was not updated at restore time, so that v-/v+ light got switched off
     * and never restored, flickering out at intervals.
     * The lock is reentrant. ledPadSetLocked already holds it, so nesting is safe.
     */
    private void appendRestoreOps(java.util.List<DirectLedController.LedOp> ops,
                                  boolean keepPads, String wifiOverride) {
        int[] h = DirectLedController.lastHeadParams();
        if (h != null) {
            ops.add(DirectLedController.LedOp.head(h[0], h[1], h[2], h[3],
                    Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0));
        }
        int[] e = DirectLedController.lastEyeParams();
        if (e != null) {
            ops.add(DirectLedController.LedOp.eye(e[0], e[1], e[2], e[3],
                    Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0));
        }
        synchronized (padToggleLock) {
            if (keepPads) {
                if (padMinusLit) ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_MINUS));
                if (padPlusLit) ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_PLUS));
            }
            String w = wifiOverride != null ? wifiOverride : wifiLedState;
            if ("blue".equals(w)) {
                ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_BLUE));
            } else if ("red".equals(w)) {
                ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_RED));
            }
        }
    }

    /**
     * System lights for the rhythm light (disco): **they light and go out in the
     * same batch as head/eye/mouth, within the same disco job**. Do not start
     * another state machine (user request).
     *
     * This **writes** padMinusLit/padPlusLit (it counts as "a key was pressed"),
     * so a later manual pad change by the user really sticks and does not bounce
     * back. Same as wifi/mute. This fixes the pad API lying before:
     * `led/pad/set?on=false` returned ok, but the light bounced back immediately
     * (measured: right after sending minus=false, it read true). Disco is "set
     * once at the moment the light turns on", not "hold the user's hand for the
     * whole song".
     *
     * @param on true = lit: pad v-(14)/v+(16) + wifi red (13).
     *            false = off: both pad lights out, wifi corrected to the real
     *            connection state.
     */
    public java.util.List<DirectLedController.LedOp> discoSystemLightsOps(boolean on) {
        java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(5);
        if (on) {
            // Add only, no subtract: pad off->on and wifi ->red are both additive. No global OFF needed.
            padMinusLit = true;
            padPlusLit = true;
            ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_MINUS));
            ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_PLUS));
            ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_RED));
            wifiLedState = "red";
        } else {
            // Off: do not restore pad (it goes out with the disco); correct wifi to
            // the real connection state (connected = blue).
            padMinusLit = false;
            padPlusLit = false;
            appendRestoreOps(ops, false, realWifiColor());
            String real = realWifiColor();
            wifiLedState = real;
        }
        return ops;
    }

    /** Query the wifi switch and connection state now. Returns "blue"/"red"/"off". */
    private String realWifiColor() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "off";
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "red";
            android.net.NetworkInfo ni =
                    cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
            return (ni != null && ni.isConnected()) ? "blue" : "red";
        } catch (Throwable t) {
            return "blue";
        }
    }

    // -- WiFi light (12 blue = network up / 13 red = no network) --------------------------------------
    private static final int WIFI_LED_INDEX_BLUE = 12;
    private static final int WIFI_LED_INDEX_RED = 13;
    private BroadcastReceiver wifiLedReceiver;
    private Runnable wifiLedReapply;

    public void registerWifiLedReceiver() {
        // Re-entry guard: do not register twice and lose the first receiver.
        if (wifiLedReceiver != null) return;
        wifiLedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                // Convergence: when wifi closes, the order of the DISABLED and
                // disconnected broadcasts is not fixed. If the first one judges red,
                // the later off overwrites it; and the reverse. After 1.2 s, reset
                // once from the authoritative state to guarantee eventual
                // consistency (single-thread executor preserves order; the debounce
                // prevents buildup).
                if (wifiLedReapply != null) mainHandler.removeCallbacks(wifiLedReapply);
                wifiLedReapply = new Runnable() {
                    @Override public void run() { applyWifiLed(); }
                };
                mainHandler.postDelayed(wifiLedReapply, 1200);
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    // The switch itself: off means light off. For other state
                    // changes, take the fresh query as authoritative.
                    int st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                    if (st == WifiManager.WIFI_STATE_DISABLED
                            || st == WifiManager.WIFI_STATE_DISABLING) {
                        applyWifiLedState(false, false);
                    } else {
                        applyWifiLed();
                    }
                    return;
                }
                // Connection change: the NetworkInfo carried by the intent is the
                // authoritative new state. Use it directly, no re-query (a re-query
                // has a race: the broadcast arrived but ConnectivityManager still
                // holds the old value, freezing on red; seen on real hardware). But
                // the switch needs a fresh query: when wifi closes, the DISABLED
                // and disconnected broadcasts arrive in some order. If the latter
                // treats wifi as still on, it re-lights red and covers the off.
                android.net.NetworkInfo info = intent != null ? intent
                        .getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO) : null;
                if (info != null && info.getType()
                        == android.net.ConnectivityManager.TYPE_WIFI) {
                    boolean onNow = isWifiEnabledNow();
                    applyWifiLedState(onNow, onNow && info.isConnected());
                } else {
                    applyWifiLed();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        appContext.registerReceiver(wifiLedReceiver, filter);

        // On app start, set from the current state immediately.
        applyWifiLed();
    }

    public void unregisterWifiLedReceiver() {
        if (wifiLedReapply != null) {
            mainHandler.removeCallbacks(wifiLedReapply);
            wifiLedReapply = null;
        }
        if (wifiLedReceiver != null) {
            try {
                appContext.unregisterReceiver(wifiLedReceiver);
                wifiLedReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** WiFi light state switch entry (known explicit state version) - queued to the pad LED single-thread executor. */
    private void applyWifiLedState(final boolean wifiOn, final boolean connected) {
        postPadLed(() -> applyWifiLedInternal(wifiOn, connected));
    }

    /** Query the wifi switch now (wrapped in try/catch; if the query fails, treat it as on and let the connection state decide). */
    private boolean isWifiEnabledNow() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            return wm == null || wm.isWifiEnabled();
        } catch (Exception e) {
            Log.w(TAG, "wifi enabled check failed", e);
            return true;
        }
    }

    /** WiFi light state switch entry (fresh-query version: used at boot and on switch changes) - queued to the pad LED single-thread executor. */
    private void applyWifiLed() {
        postPadLed(() -> applyWifiLedSync());
    }

    /** The sync body of the above - when ledPadSet switches off independently, it
     *  must do this step immediately and inline, without going through the executor
     *  queue (otherwise the lock order with the light restore gets scrambled). */
    private void applyWifiLedSync() {
        final boolean wifiOn;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            wifiOn = wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            Log.w(TAG, "wifi enabled check failed", e);
            return;
        }
        boolean conn = false;
        if (wifiOn) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.NetworkInfo ni =
                            cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                    conn = ni != null && ni.isConnected();
                }
            } catch (Exception e) {
                Log.w(TAG, "wifi connected check failed", e);
            }
        }
        applyWifiLedInternal(wifiOn, conn);
    }

    /**
     * Actual switch: first ledSetOFF() to clear the old color, wait 100 ms, then
     * light the target color (do not light when wifi is off). Both steps use
     * burst retry.
     */
    /** Last state (red/blue/off) for the Wi-Fi light test + mirror.
     *  Both applyWifiLedInternal and ledWifiSet update it; the 5th light in the
     *  LED tab head follows this (that is just how the hardware is). */
    private volatile String wifiLedState = "off";

    private void applyWifiLedInternal(boolean wifiOn, boolean connected) {
        String want = !wifiOn ? "off" : (connected ? "blue" : "red");
        // No color change, no ioctl at all. The real-hardware logcat showed the old
        // path ping-ponging "global OFF -> sleep 100 ms -> restore wifi only" every
        // few hundred ms, blacking the whole machine for 100 ms. A disco head/eye
        // dance step got cut off and the whole section vanished. With this gate,
        // it is quiet.
        synchronized (padToggleLock) {
            if (want.equals(wifiLedState)) return;
            if (!wifiOn) {
                // Off = global OFF (head/eye get cleared too). Follow the original
                // semantics.
                if (assertPadLedsOffBurst()) wifiLedState = "off";
                return;
            }
            // Red<->blue only needs ledSetOn(12/13). No OFF first (see the ledWifiSet
            // note).
            if (assertSingleLedBurst(connected ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED)) {
                wifiLedState = want;
            }
        }
    }

    /** One burst: retry open()/dev/led_eye until it opens, light a single LED index.
     *  open->ioctl->close goes under the global driver lock. Do not collide opens
     *  with the disco (see above). */
    private boolean assertSingleLedBurst(int index) {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            final boolean[] openOk = {false};
            DirectLedController.runExclusive(() -> {
                try {
                    openOk[0] = LedControl.open();
                } catch (Throwable t) {
                    Log.w(TAG, "wifi LED open() threw", t);
                }
                if (openOk[0]) {
                    try {
                        LedControl.ledSetOn(index);
                    } finally {
                        try {
                            LedControl.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            if (openOk[0]) {
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "wifi LED open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    // -- Alpha2 PIR warning response (LED + ringtone) ----------------------------------------------
    // Listen for the standalone "alpha2_pir_state" event and use the Alpha2 backend
    // LED API to trigger the LED / ringtone.
    //
    // The PIR raw event (chest cmd=-109, "PIR HUMON DETECT") fires normally -
    // the low-level chest MCU hardware on this machine can do PIR on its own.
    //
    // LED part: keep the eye/head 5-mic LED red solid (setHeadEyeLedLong(1, 9)),
    // color code 1 = red.
    // See the color table in the LEDs section below (1=red 2=green 3=blue 4=yellow
    // 5=purple 6=cyan 7=white).
    //
    // The 5-mic head/eye LED on this machine's head board
    // is effective for the PIR warning response (eye/head light red). The PIR
    // warning
    // goes through this one path only. No mouth LED breathing fallback is added.
    // The mouth does not flash; with the ringtone, only eye/head stay red.
    private volatile boolean alpha2PirAlertActive = false;
    // Independent of the pir/set sensor hardware switch itself - default off. The
    // user has to turn it on to get the LED/sound response.
    // This avoids a red light flashing or a ringtone ringing for no reason right
    // after boot.
    private volatile boolean alpha2PirAlertEnabled = false;
    // Self-remembered state of the PIR hardware switch (the chest cannot read it
    // back; only the last setting is remembered; default on -
    // different from the warning switch: the sensor itself usually ships on, and
    // only a toggle off stops the events).
    private volatile boolean pirEnabled = true;

    /** Called through from the pir/alert_enabled endpoint (pirAlertEnabledResponse). */
    public void setPirAlertEnabled(boolean enabled) {
        alpha2PirAlertEnabled = enabled;
        if (!enabled && alpha2PirAlertActive) {
            // Turning the switch off mid-way must immediately switch off the
            // currently lit light / stop the sound that is playing, not just stop
            // reacting to later events.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    applyAlpha2PirLedAndSound(false);
                }
            }).start();
        }
    }

    public synchronized void applyAlpha2PirLedAndSound(boolean triggered) {
        if (!alpha2PirAlertEnabled && triggered) {
            return; // Switch off - ignore new triggers (but what is already lit can
                     // still be switched off via setPirAlertEnabled(false)).
        }
        if (triggered == alpha2PirAlertActive) {
            return; // Avoid re-sending LED/sound every time the same state event
                     // repeats. Same practice as onSonarDistanceReceived().
        }
        alpha2PirAlertActive = triggered;
        // The PIR warning (controlled by the standalone "PIR test" page switch
        // alpha2PirAlertEnabled) sends a single
        // setHeadEyeLedLong()/stop directly - the later call wins, nothing to cancel.
        try {
            if (triggered) {
                setHeadEyeLedLong(1, 9); // 1 = red, 9 = brightest
            } else {
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        } catch (Throwable t) {
            // Log a warning when the 5-mic head/eye LED path fails (the PIR warning
            // goes through this path only; no mouth fallback is added).
            Log.w(TAG, "applyAlpha2PirLedAndSound: 5-mic head/eye LED path failed", t);
        }
        if (triggered) {
            ringtoneCenter.playPirAlertCue(); // the lazily looked-up "Heaven" ringtone
        } else {
            ringtoneCenter.stopRingtonePlayback();
        }
    }

    // -- PIR event wiring --
    public void registerAlpha2PirAlertListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains("\"type\":\"alpha2_pir_state\"")) {
                    return;
                }
                final Boolean triggered = extractPirTriggered(line);
                if (triggered == null) {
                    return;
                }
                // onEvent() runs on the main thread - move the AIDL/JNI LED call to a
                // background thread. Do not use the main thread. This matches the
                // project's usual practice (see
                // registerPirAlertListener()/registerChestMuteKeyTestListener()).
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        applyAlpha2PirLedAndSound(triggered);
                    }
                }).start();
            }
        });
    }

    /** Pulls the boolean after "triggered":  out of an EventBus-published "pir_state"
     *  JSON line (no-JSON-library style, matching the rest of this project). */
    private static Boolean extractPirTriggered(String line) {
        String key = "\"triggered\":";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        if (line.startsWith("true", start)) return true;
        if (line.startsWith("false", start)) return false;
        return null;
    }

    // Thin delegate for directChestReady() (implementation in DirectProbes, a pair
    // with headerReady() below).
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    // Confirmed on real hardware: the cmd=72 switch takes effect, and PIR triggers normally.
    public HttpServer.ApiResponse pirSetResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        boolean sent = HardwareDirectManager.get(appContext).chest().setPirEnabled(enabled);
        if (sent) pirEnabled = enabled;
        return MainActivity.sentReadyResponse(sent, directChestReady());
    }

    /** Shared by the panel page-entry sync: read the PIR self-remembered state (the hardware cannot read it back; an explicit write from pir/set syncs it at once). */
    public boolean isPirEnabled() {
        return pirEnabled;
    }

    /** Shared by the panel page-entry sync: read the warning response switch. */
    public boolean isPirAlertEnabled() {
        return alpha2PirAlertEnabled;
    }

    /** Body of pir/get: return the hardware switch self-remembered state and the warning response switch in one call, so the panel does not open two. */
    public HttpServer.ApiResponse pirGetResponse() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + isPirEnabled()
                + ",\"alertEnabled\":" + isPirAlertEnabled() + "}");
    }

    /** Shared by the mapping: PIR hardware single-key toggle (reads the self-remembered
     *  state, see pirEnabled).
     *  Returns true = delivered (taken as-is); if the send fails, the state is
     *  unchanged. */
    public synchronized boolean togglePir() {
        boolean next = !pirEnabled;
        boolean sent = false;
        try {
            sent = HardwareDirectManager.get(appContext).chest().setPirEnabled(next);
        } catch (Throwable t) {
            sent = false;
        }
        if (sent) pirEnabled = next;
        return sent;
    }

    /** Independent of the pir/set sensor hardware switch itself. This purely controls
     *  whether the warning response of "flash a red light / ring when a person is
     *  detected" is on. It is confirmed that the PIR event
     *  itself (cmd=-109, "PIR HUMON DETECT") fires normally - this
     *  endpoint lets the front end choose whether to react to that event. */
    public HttpServer.ApiResponse pirAlertEnabledResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        setPirAlertEnabled(enabled);
        return HttpServer.ApiResponse.okTrue();
    }

    /** Same "long" (solid, always-on) LED effect as led/head/set & led/eye/set's
     *  preset=long, but callable directly server-side without an HTTP round-trip.
     *  pure-direct: a single send settles it. */
    public void setHeadEyeLedLong(int color, int brightness) {
        DirectLedController.setHead5MicRaw(color, brightness, 31, 31, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        DirectLedController.setEye5MicRaw(color, brightness, 255, 255, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }

    /** Obstacle LED indicator for this machine (head board / firmware 1.1.1.14):
     *  head/eye go through DirectLedController (pure-direct JNI, the same path as the
     *  LED tab / disco, confirmed working on real hardware) and stay purple, while the
     *  mouth LED flashes at the same time as a second cue. The two paths use
     *  independent try/catch, so one failure does not block the other. */
    public void applyObstacleIndicator(boolean triggered) {
        try {
            if (triggered) {
                setHeadEyeLedLong(5, 9); // 5 = purple, see the led/head/set color-code comment
            } else {
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyObstacleIndicator: 5-mic head/eye LED path failed (known unsupported on this head board, see MouthLedData javadoc)", t);
        }
        try {
            if (triggered) {
                MouthLedData.breathing(150).apply(); // fast breathing = obstacle-near cue
            } else {
                MouthLedData.off().apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyObstacleIndicator: mouth LED fallback failed", t);
        }
    }

    private boolean headerReady() {
        return DirectProbes.isHeadReady(appContext);
    }

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - 0 = fastest breathing
    // (confirmed working on real hardware),
    // unrelated to the LED tab slider (that one is 100-1000, default 150, and only
    // controls the manual led/mouth/set).
    private static final int TTS_MOUTH_LED_SPEED = 0;

    /**
     * Starts the mouth LED breathing effect for the duration of a TTS utterance. Called
     * right after kicking off speech (both robot-side speech_startTTS and Android
     * system TTS), paired with stopMouthLedForTts() called when that speech actually
     * finishes (onServerPlayEnd for robot TTS; UtteranceProgressListener.onDone/onError
     * for Android TTS - see androidTts setup in MainActivity.onCreate).
     *
     * Note this can't be timed to the utterance's real length in advance: neither
     * speech_startTTS nor Android TextToSpeech.speak() reports how long the resulting
     * audio will be before/while it's produced (the robot's TTS engine synthesizes and
     * plays it internally; length depends on synthesis the caller doesn't control), so
     * "flash the mouth for exactly N seconds" is implemented as bracket-and-release
     * around the actual speech rather than a precomputed fixed duration -
     * MouthLedData.breathing() is left running (playDurationMs=MAX) until the
     * corresponding stop call arrives from whichever completion signal fires.
     */
    public static void startMouthLedForTts() {
        mouthTtsActive = true;
        MouthLedData.breathing(TTS_MOUTH_LED_SPEED).apply();
    }

    public static void stopMouthLedForTts() {
        mouthTtsActive = false;
        MouthLedData.off().apply();
    }

    /** Whether TTS is playing (whether the mouth belongs to it) - disco must yield before it pushes the mouth, so it does not fight with it. */
    private static volatile boolean mouthTtsActive = false;

    public static boolean isMouthTtsActive() {
        return mouthTtsActive;
    }

    // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
    // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
    //   color: 1=red 2=green 3=blue 4=yellow 5=purple 6=cyan 7=white
    //   brightness: 1 (dimmest) .. 9 (brightest)
    //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
    //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
    // Head LED position map, 5 per side (confirmed by probing on real hardware):
    //   P4 = left side (forward order): off=0, light 1=1, 2=2, 3=4, 4=8, 5=16;
    //     1,2=3; 1,2,3=7; 1,2,3,4=15; all=31.
    //   P3 = right side (reverse order): off=0, light 1=16, 2=8, 3=4, 4=2, 5=1;
    //     1,2=24; 1,2,3=28; 1,2,3,4=30; all=31.
    //   Note: the 5th light on each side is taken by wifi (cannot be turned off).
    //     Only 1-4 are actually usable: left = P4=15, right = P3=30
    //     (disco uses exactly this group).
    //   V+/V- green volume meter (user request): loudest = 4 green lights, silent =
    //   all off.
    //   See showVolumeMeter() below. GestureCenter updates it on every volume change.
    public HttpServer.ApiResponse ledHeadSet(Map<String, String> query) {
        // pure-direct: the 5-mic goes directly through the libhead_led.so JNI (DirectLedController), no longer via binder.
        String preset = ApiValidator.requireLedHeadPreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopHead5Mic();
            return MainActivity.sentReadyResponse(stopped, headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        // Speed (ms, 50-500): flash uses p5/p6, chase/dual use p5, breathe scales
        // by ratio;
        // absent = keep the classic value of each preset (no impact on
        // Blockly / old clients).
        int speed = ApiValidator.optionalIntRange(query, "speed", 50, 500, -1);
        int p5, p6, p8;
        switch (preset) {
            case "flash":   p5 = speed < 0 ? 100 : speed; p6 = speed < 0 ? 100 : speed; p8 = 0; break;
            case "breathe": p5 = speed < 0 ? 5 : Math.max(1, speed / 20); p6 = speed < 0 ? 20 : speed / 5; p8 = 1; break;
            case "chase":   p5 = speed < 0 ? 100 : speed; p6 = 0; p8 = 3; break;
            case "dual":    p5 = speed < 0 ? 500 : speed; p6 = 0; p8 = 5; break;
            case "long":
            default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        // Per-light test: the preset follows the currently lit mask (absent = all
        // on, compatible with old clients / Blockly).
        int p3 = ApiValidator.optionalIntRange(query, "p3", 0, 31, 31);
        int p4 = ApiValidator.optionalIntRange(query, "p4", 0, 31, 31);
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, p3, p4, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    public HttpServer.ApiResponse ledEyeSet(Map<String, String> query) {
        String preset = ApiValidator.requireLedEyePreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopEye5Mic();
            return MainActivity.sentReadyResponse(stopped, headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        // Speed (ms, 50-500): flash uses p5/p6, chase/dual use p5; absent = the
        // classic value.
        int speed = ApiValidator.optionalIntRange(query, "speed", 50, 500, -1);
        int p5, p6, p8;
        switch (preset) {
            case "flash": p5 = speed < 0 ? 100 : speed; p6 = speed < 0 ? 100 : speed; p8 = 0; break;
            case "chase": p5 = speed < 0 ? 100 : speed; p6 = 0; p8 = 1; break;
            case "dual":  p5 = speed < 0 ? 500 : speed; p6 = 0; p8 = 3; break;
            case "long":
            default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        // Per-light test: the preset follows the currently lit mask (absent = all
        // on, compatible with old clients / Blockly).
        int p3 = ApiValidator.optionalIntRange(query, "p3", 0, 255, 255);
        int p4 = ApiValidator.optionalIntRange(query, "p4", 0, 255, 255);
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, p3, p4, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    // Eye LED position map, 8 per side (confirmed by probing on real hardware):
    // P3 = left eye, P4 = right eye;
    // clockwise order: 12 o'clock = number 1, 3 o'clock = number 3, 6 o'clock =
    // number 5, 9 o'clock = number 7.
    // Bit mapping: light 1=16, 2=8, 3=4, 4=2, 5=1, 6=128, 7=64, 8=32;
    //   1,2=24; 1,2,3=28; 1-4=30; 1-5=31;
    //   1-6=159; 1-7=223; all on=255.
    // Normally 255/255 all on.

    /** For per-light test: write the p3/p4 mask directly (stays on at MAX, same
     *  shape as the long preset).
     *  Head: P4 = the 5 left lights (forward-order bits 1,2,4,8,16), P3 = the 5
     *  right lights (reverse order 16,8,4,2,1);
     *  the 5th light is the wifi slot (cannot be held reliably), see the position
     *  map above. */
    public HttpServer.ApiResponse ledHeadRaw(Map<String, String> query) {
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p3 = ApiValidator.requireIntRange(query, "p3", 0, 31);
        int p4 = ApiValidator.requireIntRange(query, "p4", 0, 31);
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, p3, p4,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    /** For per-light test: write the p3/p4 mask directly (stays on at MAX). Eye:
     *  P3 = left eye, P4 = right eye,
     *  8-light ring per side (see the position map above for the bits; all on =
     *  255). */
    public HttpServer.ApiResponse ledEyeRaw(Map<String, String> query) {
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p3 = ApiValidator.requireIntRange(query, "p3", 0, 255);
        int p4 = ApiValidator.requireIntRange(query, "p4", 0, 255);
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, p3, p4,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    /** For the per-light test mirror: return the last light state recorded by
     *  DirectLedController (the hardware is write-only and cannot be read back).
     *  Never sent = -1, off = 0. */
    public HttpServer.ApiResponse ledStateGet() {
        String s = DirectLedController.lastStateJson();
        // Also include the pad test-light state + the wifi light state:
        // the end of s is ...}}. Strip the last outer }, insert "pad" and "wifi",
        // and add one } back at the end (not two! one extra JSON brace breaks it;
        // that is how the earlier bug happened).
        // Guard: if s is empty / too short / does not end with } (when upstream
        // changes the format), return the original string. Do not throw a
        // StringIndexOutOfBoundsException and turn it into a 500.
        if (s == null || s.length() < 2 || s.charAt(s.length() - 1) != '}') {
            return HttpServer.ApiResponse.ok(s == null ? "{\"ok\":false}" : s);
        }
        String extra = ",\"pad\":{\"minus\":" + padMinusLit
                + ",\"plus\":" + padPlusLit + "}"
                + ",\"wifi\":\"" + wifiLedState + "\"}";
        // Effective state of the chest mute light (Xiaozhi connected OR music playing):
        // the LED tab mute button uses it to follow the real hardware color.
        // If the query is not injected, report false (do not block the whole
        // state).
        boolean muteLit = false;
        try {
            MuteStateQuery q = muteStateQuery;
            if (q != null) muteLit = q.isMuteLit();
        } catch (Throwable ignore) {
            muteLit = false;
        }
        extra = extra.substring(0, extra.length() - 1) + ",\"mute\":" + muteLit + "}";
        return HttpServer.ApiResponse.ok(s.substring(0, s.length() - 1) + extra);
    }

    // -- System light test (wifi / pad / mute)------------------------------------------
    // The wifi light does not light on its own in firmware. applyWifiLedInternal sets
    // it manually by connection state (red 13 / blue 12). Here we force red / blue /
    // off manually - the next wifi state change corrects it automatically.
    // Fire-and-forget, for testing (like the preset button).
    public HttpServer.ApiResponse ledWifiSet(Map<String, String> query) {
        String color = ApiValidator.requireWifiLedColor(query);
        boolean ok;
        if ("off".equals(color)) {
            // Off = global OFF (head/eye get cleared too, same semantics as
            // applyWifiLedInternal).
            ok = assertPadLedsOffBurst();
            if (ok) wifiLedState = "off";
        } else {
            // Red<->blue only needs one ledSetOn(12/13). No global OFF first -
            // confirmed on real hardware that ledSetOn replaces the previous color
            // on its own. (In 2026-09 we tried switching to
            // an "OFF + restore head/eye/pad + light the new color" batch. Result:
            // every color change made the whole board flash off, v-/v+ visibly went
            // out, and the user complained. Do not go down that path.)
            ok = assertSingleLedBurst("blue".equals(color) ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED);
            if (ok) wifiLedState = color;
        }
        return HttpServer.ApiResponse.okBool(ok);
    }

    /** Volume -/+ pad light (ledSetOn 14 / 16) test: one press turns it on, another
     *  press turns it off.
     *  The hardware has no per-light off - when turning off, it does a global OFF
     *  and then restores each item one by one (the other pad -> wifi ->
     *  head -> eye, long shape). To the eye, only this light goes out. While disco
     *  is running, the next push (<=50 ms) overwrites it naturally. The LED tab
     *  animation preset becomes a long on (to flash it back, press several times).
     *  Absent on = toggle. While a physical key is held, the worker takes over (on
     *  release it goes globally off,
     *  the mirror stays correct, see assertPadLedsOffBurst). */
    private volatile boolean padMinusLit = false;
    private volatile boolean padPlusLit = false;

    /** Control of the chest mute light belongs to XiaozhiBridge (the Xiaozhi
     *  connection state). This is only a
     *  callback: the rhythm light and the "connected" light share one physical
     *  light, so this only reports "this time it is
     *  decided by the rhythm light / not decided", and the side there ORs in the
     *  Xiaozhi state to pick the actual color. */
    public interface MuteLedSink {
        void applyMuteLed(boolean litByDisco);
    }

    private volatile MuteLedSink muteLedSink = null;

    public void setMuteLedSink(MuteLedSink s) {
        this.muteLedSink = s;
    }

    /** Light the chest mute light together with the rhythm light / turn it off
     *  together. Called at the same timing as the disco job,
     *  not as another state machine. */
    public void discoMuteLed(boolean litByDisco) {
        MuteLedSink s = muteLedSink;
        if (s == null) return;
        try {
            s.applyMuteLed(litByDisco);
        } catch (Throwable t) {
            Log.w(TAG, "disco mute led failed", t);
        }
    }

    /** Effective-state query for the chest mute light (Xiaozhi OR music playing, see
     *  XiaozhiBridge.isMuteLedLit) -
     *  for the led/state/get mirror to follow the real hardware color (report false
     *  if not injected, do not block the whole state). */
    public interface MuteStateQuery {
        boolean isMuteLit();
    }

    private volatile MuteStateQuery muteStateQuery = null;

    public void setMuteStateQuery(MuteStateQuery q) {
        this.muteStateQuery = q;
    }
    // Pad toggle read-modify-write lock: when pressing rapidly, two requests read
    // the same stale flag at the same time,
    // both light it, and the result that should be off becomes on (that is how the
    // earlier "sometimes works, sometimes does not" came about). The whole toggle
    // holds this lock (including the driver ops), queued one at a time.
    private final Object padToggleLock = new Object();

    public HttpServer.ApiResponse ledPadSet(Map<String, String> query) {
        synchronized (padToggleLock) {
            return ledPadSetLocked(query);
        }
    }

    private HttpServer.ApiResponse ledPadSetLocked(Map<String, String> query) {
        String key = ApiValidator.requirePadKey(query);
        boolean minus = "minus".equals(key);
        String onStr = query.get("on");
        boolean on = (onStr == null || onStr.isEmpty())
                ? !(minus ? padMinusLit : padPlusLit)
                : ApiValidator.requireBoolean(query, "on");
        boolean ok;
        if (on) {
            ok = assertSingleLedBurst(minus ? PAD_LED_INDEX_MINUS : PAD_LED_INDEX_PLUS);
            if (ok) {
                if (minus) padMinusLit = true; else padPlusLit = true;
            }
        } else {
            // Whichever light goes out, the other stays: OFF is "clear all at
            // once", so a restore is needed.
            // Decide which lights to restore from "what should actually be on"
            // (manual OR the music-playing indicator light).
            // Note: the manual flag can only change "the light pressed this time".
            // It must not overwrite the opposite light's manual
            // flag into an active value - that pollutes the manual state (it once
            // made it so that after the song stopped, the other
            // pad light could never be turned off). The opposite light keeps its
            // original state, and appendRestoreOps knows how to restore it.
            if (minus) padMinusLit = false; else padPlusLit = false;
            // One batch: OFF -> head -> eye -> kept pad -> wifi, in a single open,
            // zero pause in between - sending them one by one takes several hundred
            // ms, and the whole group visibly flickers.
            java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(6);
            ops.add(DirectLedController.LedOp.off());
            appendRestoreOps(ops, true, null);
            ok = DirectLedController.runBatch(ops);
            if (!ok) {
                // Failed (usually the first OFF cannot even open): the response
                // says failed, and the user will press again.
                Log.w(TAG, "pad set off failed");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":" + ok
                + ",\"minus\":" + padMinusLit
                + ",\"plus\":" + padPlusLit + "}");
    }

    /** Chest mute light (chest cmd 68) test: purely light it. It does not touch the
     *  Xiaozhi connection.
     *  Note: Xiaozhi connect / disconnect and the physical mute key overwrite it by
     *  real state (unrelated to pressing the button),
     *  so this is fire-and-forget here and does not enter the mirror.
     *  The frame format is copied from XiaozhiBridge (F8 8F 08 00 00 44 data sum
     *  ED),
     *  sent once (it used to send three times in a row, washing three lines of
     *  chest_rcv into the WebSocket, and the user found that annoying). */
    public HttpServer.ApiResponse ledMuteSet(Map<String, String> query) {
        boolean on = ApiValidator.requireBoolean(query, "on");
        byte data = (byte) (on ? 1 : 0);
        int sum = (8 + 68 + (data & 0xFF)) & 0xFF;
        byte[] frame = {(byte) 0xF8, (byte) 0x8F, 0x08, 0x00, 0x00,
                (byte) 68, data, (byte) sum, (byte) 0xED};
        boolean sent = false;
        try {
            sent = HardwareDirectManager.get(appContext).chest().sendRaw(frame);
        } catch (Throwable t) {
            Log.w(TAG, "mute LED send failed", t);
        }
        return HttpServer.ApiResponse.okBool(sent);
    }

    // NOTE: unlike led/head/set and led/eye/set above, this does NOT go through
    // Alpha2RobotApi/AIDL at all - there is no AIDL "mouth LED" method. It goes
    // through MouthLedData, which since beta6 delegates to DirectLedController
    // (DRIVER_LOCK) - serialized with every other /dev/led_eye call, because the
    // old driver wedges hard on concurrent open() (disco mouth + head/eye racing
    // hung the whole robot with no ANR and dead adb). See MouthLedData's
    // javadoc for the confirmed field semantics.
    //
    // Simplified to the two effects confirmed usable on this hardware: a
    // breathing effect (speed adjustable, 100-1000ms) and off. effectMode values
    // other than 1 produced no light in testing, so there's no third "always
    // solid, no breathing" preset here - see README for what was tried. Also
    // triggered automatically around TTS start/end - see startMouthLedForTts()/
    // stopMouthLedForTts() above and their call sites in speech/tts,
    // onServerPlayEnd, and the Android TTS UtteranceProgressListener.
    public HttpServer.ApiResponse ledMouthSet(Map<String, String> query) {
        String mouthPreset = ApiValidator.requireMouthPreset(query);
        if ("off".equals(mouthPreset)) {
            boolean ok = MouthLedData.off().apply();
            return HttpServer.ApiResponse.okBool(ok);
        }
        int speed = ApiValidator.requireMouthSpeed(query);
        boolean ok = MouthLedData.breathing(speed).apply();
        return HttpServer.ApiResponse.okBool(ok);
    }

    public HttpServer.ApiResponse debugJniLed(Map<String, String> query) {
        // Directly test each native
        // function of the /dev/led_eye JNI driver (eye/head/mouth/pad all go
        // through this path).
        // Very likely another ioctl of the same driver (for example the not-yet-used
        // ledSetOn(i)).
        // func=on&i=N -> ledSetOn(N); func=eye/head&a1..a8 -> the corresponding
        // setter.
        String func = ApiValidator.requireDebugLedFunc(query);
        // Also queue the global driver lock as before: do not start another open
        // in the middle of the disco's high-frequency light sending.
        if ("off".equals(func)) {
            final boolean[] res = new boolean[2];
            DirectLedController.runExclusive(() -> {
                res[0] = LedControl.open();
                res[1] = LedControl.ledSetOFF(0);
                LedControl.close();
            });
            // OFF clears the mouth too, so restore it (used to restore only when
            // breathing).
            DirectLedController.restoreMouthAfterOff();
            // Raw inverted convention: false = the ioctl succeeded. It used to report
            // ok:true in all cases, even when the open failed.
            boolean offOk = res[0] && !res[1];
            Log.i(TAG, "ledSetOFF open=" + res[0] + " raw=" + res[1]);
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":" + offOk + ",\"open\":" + res[0] + ",\"raw\":" + res[1] + "}");
        }
        final boolean[] openOk = new boolean[1];
        final String[] rbody = new String[1];
        try {
            DirectLedController.runExclusive(() -> {
                openOk[0] = LedControl.open();
                if (!openOk[0]) {
                    // If it cannot open, do not touch the ioctl (it used to send the
                    // ioctl anyway, close ran for nothing, and it still returned
                    // ok:true, lying).
                    rbody[0] = "{\"ok\":false,\"open\":false}";
                    return;
                }
                try {
                    if ("on".equals(func)) {
                        int i = ApiValidator.requireIntRange(query, "i", 0, 255);
                        boolean r = LedControl.ledSetOn(i);
                        Log.i(TAG, "ledSetOn(" + i + ") open=" + openOk[0] + " raw=" + r);
                        rbody[0] = "{\"ok\":" + !r + ",\"open\":" + openOk[0] + ",\"raw\":" + r + "}";
                        return;
                    }
                    int[] a = new int[8];
                    for (int k = 0; k < 8; k++) {
                        a[k] = ApiValidator.requireIntRange(query, "a" + (k + 1), 0, 255);
                    }
                    boolean r;
                    if ("eye".equals(func)) {
                        r = LedControl.ledSetEye(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    } else {
                        r = LedControl.ledSetHead(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    }
                    Log.i(TAG, "ledSet" + func + " open=" + openOk[0]
                            + " raw=" + r + " args=" + java.util.Arrays.toString(a));
                    rbody[0] = "{\"ok\":" + !r + ",\"open\":" + openOk[0]
                            + ",\"raw\":" + r + ",\"args\":"
                            + java.util.Arrays.toString(a).replace(" ", "") + "}";
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            // runExclusive threw (rare) / parameter out of range: do not return 500
            // on an NPE. Return ok:false as usual.
            Log.w(TAG, "debugJniLed failed", t);
            return HttpServer.ApiResponse.ok("{\"ok\":false}");
        }
        // Before runExclusive could throw, the catch above caught the moment rbody
        // was still unassigned. Now it is definitely non-null.
        return HttpServer.ApiResponse.ok(rbody[0] != null ? rbody[0] : "{\"ok\":false}");
    }

    public HttpServer.ApiResponse debugSerialSend(Map<String, String> query) {
        // Raw serial send test endpoint, used to reverse-engineer the control
        // commands for the volume key LED and
        // the chest mute key LED (on headboard v1.1 the alpha2services v1.0
        // protocol does not fit. As soon as it moves, the MCU stops lighting
        // automatically, and our app has to make up for it). port=head
        // goes through header_sendRawData (ttyS3), port=chest goes through
        // chest_sendRawData
        // (ttyS1). hex is the complete wire frame (f8 ... ed), assembled on the PC
        // side before sending.
        String port = ApiValidator.optionalSerialPort(query);
        byte[] data = parseHexBytes(ApiValidator.require(query, "hex"));
        // alpha2-brain#24: chest cmd 0x19 [00] and cmd 0x40 [01 ..] start "Sit down".
        // 400 unless allow_sit_down=true.
        ApiValidator.checkSerialSendSitDown(query, port, data);
        // alpha2-brain#130: a chest move command (cmd 0x03 move one, 0x05 move all,
        // 0x06 read with release) moves the body: 403 unless the motion gate allows it.
        // While the gate does not allow a body move, chest data that is not one or more
        // complete frames is refused too, whatever allow_sit_down says: the chest could
        // join the bytes into a move frame. Read commands are not gated, and head frames
        // are not checked.
        MotionGate g = motionGate;
        String moveWhy = g == null
                ? MotionGate.refusalWithoutGate(port, data)
                : g.refusalForSerialSend(port, data);
        if (moveWhy != null) {
            Log.w("MotionGate", "refused: " + moveWhy);
            return new HttpServer.ApiResponse(403, "application/json; charset=utf-8",
                    JsonUtil.okFalse(moveWhy));
        }
        // alpha2-brain#29: a cmd 0x17 reply for this client goes out as chest_rcv also while
        // the servo read stream runs.
        if ("chest".equals(port)) ServoReadStream.noteExternalReads(data);
        // pure-direct: passes the complete wire frame through via DirectSerialPort.sendRaw.
        boolean sent = "chest".equals(port)
                ? HardwareDirectManager.get(appContext).chest().sendRaw(data,
                        ApiValidator.optionalAllowSitDown(query))
                : HardwareDirectManager.get(appContext).head().sendRaw(data);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        Log.i(TAG, "debug/serial/send port=" + port + " hex=" + MainActivity.toHex(data, data.length)
                + " -> " + code.name());
        return HttpServer.ApiResponse.ok("{\"ok\":"
                + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + ",\"code\":\""
                + code.name() + "\"}");
    }

    /** Parses "f8 8f 08 ..." style hex (spaces/colons optional, case-insensitive) back
     *  into raw bytes for the debug/serial/send endpoint. Returns empty array on junk. */
    private static byte[] parseHexBytes(String hex) {
        String cleaned = hex.replaceAll("[^0-9a-fA-F]", "");
        int n = cleaned.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // -- MCP tools (XiaozhiBridge callTool switch delegates here) --

    /** Body of self.robot.led_set_head (delegated from XiaozhiBridge).
     *  pure-direct: driven directly via JNI. A single send settles it.
     *  The same validation as HTTP (preset enum + color 1-7 + brightness 1-9).
     *  Mismatch is an error,
     *  no silent clamp / no silent treatment as long (previously a wrong value went
     *  straight into the JNI / a wrong preset silently became long). */
    public SonarCenter.McpResult mcpLedSetHead(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "long");
        if (!"long".equals(preset) && !"flash".equals(preset) && !"breathe".equals(preset)
                && !"chase".equals(preset) && !"dual".equals(preset) && !"stop".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [long, flash, breathe, chase, dual, stop], got: " + preset);
        }
        UbxErrorCode.API_ERROR_CODE code;
        if ("stop".equals(preset)) {
            code = MainActivity.directCode(DirectLedController.stopHead5Mic());
        } else {
            if (!arguments.has("color")) {
                return SonarCenter.McpResult.err("color is required unless preset=stop");
            }
            int color = arguments.optInt("color");
            int brightness = arguments.optInt("brightness", LED_DEFAULT_BRIGHTNESS);
            if (color < ApiValidator.LED_COLOR_MIN || color > ApiValidator.LED_COLOR_MAX) {
                return SonarCenter.McpResult.err("color must be between "
                        + ApiValidator.LED_COLOR_MIN + " and " + ApiValidator.LED_COLOR_MAX
                        + ", got: " + color);
            }
            if (brightness < ApiValidator.LED_BRIGHTNESS_MIN
                    || brightness > ApiValidator.LED_BRIGHTNESS_MAX) {
                return SonarCenter.McpResult.err("brightness must be between "
                        + ApiValidator.LED_BRIGHTNESS_MIN + " and " + ApiValidator.LED_BRIGHTNESS_MAX
                        + ", got: " + brightness);
            }
            int p5, p6, p8;
            switch (preset) {
                case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
                case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
                case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
                case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
                case "long":
                default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
            }
            code = MainActivity.directCode(DirectLedController.setHead5MicRaw(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8));
        }
        boolean hReady = headerReady();
        return new SonarCenter.McpResult(!MainActivity.isOk(code) || !hReady,
                String.valueOf(code) + " (headerReady=" + hReady + ")");
    }

    /** Body of self.robot.led_set_eye (delegated from XiaozhiBridge).
     *  pure-direct: driven directly via JNI.
     *  The same validation as HTTP - the eye light has no breathe (previously
     *  "breathe" silently became long, which did not match what the
     *  schema said). A wrong preset is an error. */
    public SonarCenter.McpResult mcpLedSetEye(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "long");
        if (!"long".equals(preset) && !"flash".equals(preset) && !"chase".equals(preset)
                && !"dual".equals(preset) && !"stop".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [long, flash, chase, dual, stop]"
                            + " (eye has no breathe), got: " + preset);
        }
        UbxErrorCode.API_ERROR_CODE code;
        if ("stop".equals(preset)) {
            code = MainActivity.directCode(DirectLedController.stopEye5Mic());
        } else {
            if (!arguments.has("color")) {
                return SonarCenter.McpResult.err("color is required unless preset=stop");
            }
            int color = arguments.optInt("color");
            int brightness = arguments.optInt("brightness", LED_DEFAULT_BRIGHTNESS);
            if (color < ApiValidator.LED_COLOR_MIN || color > ApiValidator.LED_COLOR_MAX) {
                return SonarCenter.McpResult.err("color must be between "
                        + ApiValidator.LED_COLOR_MIN + " and " + ApiValidator.LED_COLOR_MAX
                        + ", got: " + color);
            }
            if (brightness < ApiValidator.LED_BRIGHTNESS_MIN
                    || brightness > ApiValidator.LED_BRIGHTNESS_MAX) {
                return SonarCenter.McpResult.err("brightness must be between "
                        + ApiValidator.LED_BRIGHTNESS_MIN + " and " + ApiValidator.LED_BRIGHTNESS_MAX
                        + ", got: " + brightness);
            }
            int p5, p6, p8;
            switch (preset) {
                case "flash": p5 = 100; p6 = 100; p8 = 0; break;
                case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
                case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
                case "long":
                default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
            }
            code = MainActivity.directCode(DirectLedController.setEye5MicRaw(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8));
        }
        boolean eReady = headerReady();
        return new SonarCenter.McpResult(!MainActivity.isOk(code) || !eReady,
                String.valueOf(code) + " (headerReady=" + eReady + ")");
    }

    /** Body of self.robot.led_set_mouth (delegated from XiaozhiBridge).
     *  The same validation as HTTP (preset enum + speed 100-1000): previously a
     *  wrong preset
     *  (e.g. "flash") silently became breathing, and an out-of-range speed went
     *  straight through - now it is an error at once. */
    public SonarCenter.McpResult mcpLedSetMouth(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "breathing");
        if (!"breathing".equals(preset) && !"off".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [breathing, off], got: " + preset);
        }
        boolean ok;
        if ("off".equals(preset)) {
            ok = MouthLedData.off().apply();
        } else {
            // Absent = default 150, matching the HTTP requireMouthSpeed
            // (the previous default was 0,
            // but 0 is out of range and was always an error. Absent was an error).
            int speedMs = arguments.optInt("speed_ms", 150);
            if (speedMs < ApiValidator.LED_MOUTH_SPEED_MIN_MS
                    || speedMs > ApiValidator.LED_MOUTH_SPEED_MAX_MS) {
                return SonarCenter.McpResult.err("speed_ms must be between "
                        + ApiValidator.LED_MOUTH_SPEED_MIN_MS + " and "
                        + ApiValidator.LED_MOUTH_SPEED_MAX_MS + ", got: " + speedMs);
            }
            ok = MouthLedData.breathing(speedMs).apply();
        }
        return new SonarCenter.McpResult(!ok, "ok=" + ok);
    }
}



