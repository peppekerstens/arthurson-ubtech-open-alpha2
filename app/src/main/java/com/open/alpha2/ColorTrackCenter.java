package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * Pure Java color blob tracking: zero extra dependencies (no TFLite/OpenCV/ML Kit, user decision).
 *
 * Pipeline: {@link CameraController} continuous preview JPEG frames → this class samples every intervalMs →
 * background thread downscales to 160x120 (16:9 preview → 160x90) → per-pixel RGB→HSV thresholding
 * (hue wrap-aware, default red 335°-25°) → largest connected blob (flood fill) centroid →
 * normalized offset dx/dy → deadband + proportional step drives head yaw(19)/pitch(20) color following
 * (cmd05 single servo, via {@link UbxApi}).
 *
 * Why no ROI (the fork point from FaceTrackCenter): per-pixel HSV + flood fill on the full 160x120 image
 * takes a few ms on the RK3288, so scanning the full image every frame directly; an ROI would save nothing
 * and add a second state to maintain. Default scan every 200ms (CPU nearly invisible); when color is found,
 * automatically switch to 100ms (autoInterval).
 *
 * Mutual exclusion: competes with FaceTrackCenter for the same head servos; both cannot run at once - the
 * exclusion lives in the {@link CameraApi} layer (starting one side stops the other); this class is unaware
 * of the other's existence. Face WS muted the same way: never through EventBus, only HTTP status polling
 * + front-end overlay. The status shape is deliberately aligned with face/track/status (running/fx/fy/fw/fh/
 * confidence/effectiveIntervalMs/config/servo) so the front-end's one box-drawing logic handles both.
 */
public final class ColorTrackCenter {
    private static final String TAG = "ColorTrackCenter";

    /** Head servos: 19 yaw (left-right), 20 pitch (up-down); ranges match the front-end calibration table (see SERVO_CALIBRATION in app-core.js). */
    public static final int PAN_ID = 19;
    public static final int TILT_ID = 20;
    public static final int PAN_MIN = 75;
    public static final int PAN_MAX = 165;
    public static final int PAN_HOME = 120;
    public static final int TILT_MIN = 105;
    public static final int TILT_MAX = 155;
    public static final int TILT_HOME = 120;

    public static final int DEFAULT_INTERVAL_MS = 200;
    public static final int DEFAULT_TRACK_INTERVAL_MS = 100;
    public static final int DEFAULT_STEP_DEG = 17;
    public static final float DEFAULT_DEADBAND = 0.10f;
    public static final int DEFAULT_TIME_MS = 400;
    /** Default follows red: hue 335°→25° (wrap); saturation + brightness thresholds remove background/white light. */
    public static final float DEFAULT_H_MIN = 335f;
    public static final float DEFAULT_H_MAX = 25f;
    public static final float DEFAULT_S_MIN = 0.45f;
    /** Saturation upper limit (default 1.0 = unlimited; the white preset uses 0.25 to remove color, keeping only low-saturation white/grayish white). */
    public static final float DEFAULT_S_MAX = 1.0f;
    public static final float DEFAULT_V_MIN = 0.25f;
    public static final float DEFAULT_V_MAX = 1.0f;
    public static final int DEFAULT_MIN_PIXELS = 80;
    // ── Walk follow (all off by default, opt-in required; means = play built-in .ubx, fileId/name/file-name all work, see ActionDirect)──
    /** Walk-follow master switch (default false: head-only follow, no walking; on = turn when offset, step forward when far, stand still when close). */
    public static final boolean DEFAULT_WALK_FOLLOW = false;
    /** Turn deadband (turn only when |dx| exceeds it, default 0.35; separate from the head deadband 0.10, body steps bigger than head). */
    public static final float DEFAULT_TURN_DB = 0.35f;
    /** Close-enough coverage (blob/full image, default 0.12; above it stands still and does not walk, only the head aims). */
    public static final float DEFAULT_CLOSE_COVERAGE = 0.12f;
    /** Walk actions (fileId, see the movement class in blockly-actions-data.js; config can swap name/file-name). */
    public static final String DEFAULT_WALK_FWD = "1508999860568"; // forward
    public static final String DEFAULT_WALK_LEFT = "1464835936047"; // turn left
    public static final String DEFAULT_WALK_RIGHT = "1464835936041"; // turn right
    /** Detection image width locked at 160 (height follows the preview ratio: 4:3→120, 16:9→90); a full-image scan still takes a few ms. */
    public static final int DETECT_W = 160;

    private final CameraController cameraController;
    private final UbxApi ubxApi;
    /** For walk follow (plays the forward/turn-left/turn-right .ubx; MainActivity injects it late; while not injected, walk follow does not move). */
    private volatile ActionDirect actionDirect;

    // ── Tunable parameters (volatile, HTTP thread writes, detection thread reads)──
    private volatile boolean running = false;
    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int trackIntervalMs = DEFAULT_TRACK_INTERVAL_MS;
    private volatile int stepDeg = DEFAULT_STEP_DEG;
    private volatile float deadband = DEFAULT_DEADBAND;
    private volatile int timeMs = DEFAULT_TIME_MS;
    /** Head-follow master switch: off = detect + box only, head does not move; on = 19/20 follow together. */
    private volatile boolean headFollow = true;
    /** Auto interval (default on): no detection uses intervalMs (slow), color found uses trackIntervalMs (fast); off = always slow. */
    private volatile boolean autoInterval = true;
    private volatile float hMin = DEFAULT_H_MIN;
    private volatile float hMax = DEFAULT_H_MAX;
    private volatile float sMin = DEFAULT_S_MIN;
    private volatile float sMax = DEFAULT_S_MAX;
    private volatile float vMin = DEFAULT_V_MIN;
    private volatile float vMax = DEFAULT_V_MAX;
    private volatile int minPixels = DEFAULT_MIN_PIXELS;
    private volatile boolean walkFollow = DEFAULT_WALK_FOLLOW;
    private volatile float turnDb = DEFAULT_TURN_DB;
    private volatile float closeCoverage = DEFAULT_CLOSE_COVERAGE;
    private volatile String walkFwd = DEFAULT_WALK_FWD;
    private volatile String walkLeft = DEFAULT_WALK_LEFT;
    private volatile String walkRight = DEFAULT_WALK_RIGHT;

    // ── State/statistics ──
    private volatile int curPan = PAN_HOME;
    private volatile int curTilt = TILT_HOME;
    private volatile long framesSeen = 0;
    private volatile long framesProcessed = 0;
    private volatile long moves = 0;
    private volatile long lastDetectMs = 0;
    private volatile int lastFound = 0;
    private volatile float lastFx = -1f;
    private volatile float lastFy = -1f;
    private volatile float lastFw = 0f;
    private volatile float lastFh = 0f;
    /** Centroid blob coverage (blob pixels/full-image pixels, 0-1, used for overlay confidence). */
    private volatile float lastCoverage = 0f;
    private volatile int lastPixels = 0;
    private volatile int lastMatches = 0;
    /** Walk-follow state: last thing played (fileId/name/""=never walked) + cumulative step count. */
    private volatile String lastWalk = "";
    private volatile long walkMoves = 0;
    private volatile long lastMoveMs = 0;
    private volatile int lostStreak = 0;
    /** Previous frame detection cost in ms (decode + HSV + flood fill). */
    private volatile long lastCostMs = 0;
    /** Whether color is being followed (for the auto interval): true on a hit, false when a scan finds nothing (unlike the face ROI which must hold 3 hits - a full-image scan has no lost-track cost). */
    private volatile boolean hasTrack = false;

    private HandlerThread detectThread;
    private Handler detectHandler;
    private final Object subLock = new Object();
    private boolean subscribed = false;
    private volatile long lastSampleMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Minimum servo-command interval in ms (same as FaceTrackCenter, so the chest serial is not hammered). */
    private static final long SERVO_MIN_GAP_MS = 150;
    private volatile long lastServoSendMs = 0;

    private final CameraController.FrameListener sampler = new CameraController.FrameListener() {
        @Override public void onFrame(CameraController.Frame frame) {
            if (!running) return;
            framesSeen++;
            long now = System.currentTimeMillis();
            long eff = effectiveIntervalMs();
            if (now - lastSampleMs < eff) return;
            if (inFlight.get()) return;
            lastSampleMs = now;
            final byte[] jpeg = frame.jpeg;
            Handler h;
            synchronized (subLock) { h = detectHandler; }
            if (h == null) return;
            inFlight.set(true);
            h.post(new Runnable() {
                @Override public void run() {
                    try { detectAndTrack(jpeg); }
                    finally { inFlight.set(false); }
                }
            });
        }
    };

    public ColorTrackCenter(CameraController cameraController, UbxApi ubxApi) {
        this.cameraController = cameraController;
        this.ubxApi = ubxApi;
    }

    /** For MainActivity wiring: inject ActionDirect late (it boots slower than the Center; while not injected, walk follow does not move, the head still follows). */
    public void setActionDirect(ActionDirect actionDirect) {
        this.actionDirect = actionDirect;
    }

    // ── Pure algorithm (RGB→HSV/hue range/largest blob) in ColorTrackLogic (zero-dependency separate file,
    // unit-tested directly; not re-implemented here)──
    private void ensureThreadLocked() {
        if (detectThread == null || !detectThread.isAlive()) {
            detectThread = new HandlerThread("ColorTrackThread");
            detectThread.start();
            detectHandler = new Handler(detectThread.getLooper());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** Currently effective interval (shared by sampler and status; auto on = fast rate when color is found, otherwise slow rate). */
    private int effectiveIntervalMs() {
        if (autoInterval && hasTrack) return trackIntervalMs;
        return intervalMs;
    }

    /** Read optional tracking parameters from the query and apply them (shared by start/config; absent = keep the current value). */
    public void applyConfigFromQuery(Map<String, String> query) {
        if (query == null) return;
        intervalMs = ApiValidator.optionalIntRange(query, "intervalMs", 100, 3000, intervalMs);
        trackIntervalMs = ApiValidator.optionalIntRange(query, "trackIntervalMs", 100, 3000, trackIntervalMs);
        stepDeg = ApiValidator.optionalIntRange(query, "stepDeg", 1, 30, stepDeg);
        deadband = ApiValidator.optionalFloatRange(query, "deadband", 0.0, 0.5, deadband);
        timeMs = ApiValidator.optionalIntRange(query, "timeMs", 20, 2000, timeMs);
        hMin = ApiValidator.optionalFloatRange(query, "hMin", 0.0, 360.0, hMin);
        hMax = ApiValidator.optionalFloatRange(query, "hMax", 0.0, 360.0, hMax);
        sMin = ApiValidator.optionalFloatRange(query, "sMin", 0.0, 1.0, sMin);
        sMax = ApiValidator.optionalFloatRange(query, "sMax", 0.0, 1.0, sMax);
        vMin = ApiValidator.optionalFloatRange(query, "vMin", 0.0, 1.0, vMin);
        vMax = ApiValidator.optionalFloatRange(query, "vMax", 0.0, 1.0, vMax);
        minPixels = ApiValidator.optionalIntRange(query, "minPixels", 10, 20000, minPixels);
        turnDb = ApiValidator.optionalFloatRange(query, "turnDb", 0.0, 0.8, turnDb);
        closeCoverage = ApiValidator.optionalFloatRange(query, "closeCoverage", 0.02, 0.6, closeCoverage);
        walkFwd = optionalActionName(query, "walkFwd", walkFwd);
        walkLeft = optionalActionName(query, "walkLeft", walkLeft);
        walkRight = optionalActionName(query, "walkRight", walkRight);
        if (query.containsKey("headFollow") && query.get("headFollow") != null
                && !query.get("headFollow").isEmpty()) {
            headFollow = ApiValidator.optionalBoolean(query, "headFollow", headFollow);
        }
        if (query.containsKey("autoInterval") && query.get("autoInterval") != null
                && !query.get("autoInterval").isEmpty()) {
            autoInterval = ApiValidator.optionalBoolean(query, "autoInterval", autoInterval);
        }
        if (query.containsKey("walkFollow") && query.get("walkFollow") != null
                && !query.get("walkFollow").isEmpty()) {
            walkFollow = ApiValidator.optionalBoolean(query, "walkFollow", walkFollow);
        }
    }

    /** Walk action name validation: non-empty, ≤64 chars, no path characters (/ \ ..); if invalid, keep the current value. */
    private static String optionalActionName(Map<String, String> query, String key, String current) {
        if (query == null) return current;
        String v = query.get(key);
        if (v == null || v.isEmpty()) return current;
        v = v.trim();
        if (v.isEmpty() || v.length() > 64 || v.contains("/") || v.contains("\\") || v.contains("..")) {
            return current;
        }
        return v;
    }

    /** Start tracking: first ensure the camera stream is open, then subscribe to sampling. Called on the HTTP worker thread (blocks on camera open). */
    public synchronized HttpServer.ApiResponse start(Map<String, String> query) {
        applyConfigFromQuery(query);
        CameraController.StartResult sr = cameraController.start(8000);
        if (sr.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + JsonUtil.esc(sr.error) + "\"}");
        }
        synchronized (subLock) {
            ensureThreadLocked();
            if (!subscribed) {
                cameraController.subscribe(sampler);
                subscribed = true;
            }
            lastSampleMs = 0;
        }
        running = true;
        lostStreak = 0;
        hasTrack = false;
        lastWalk = "";
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public synchronized HttpServer.ApiResponse stop() {
        running = false;
        hasTrack = false;
        synchronized (subLock) {
            if (subscribed) {
                try { cameraController.unsubscribe(sampler); } catch (Throwable ignored) {}
                subscribed = false;
            }
        }
        // Stopping tracking while walk follow is on and a step is playing: interrupt-stop + squat-and-rise to re-stabilize (the robot
        // must not stand in a mid-walk pose; do not interrupt while a scan finds nothing - the step in flight is bounded, it just plays out).
        ActionDirect ad = actionDirect;
        if (walkFollow && ad != null) {
            try {
                if (ad.isPlaying()) ad.stopActionWithRecovery();
            } catch (Throwable ignored) {}
        }
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public HttpServer.ApiResponse config(Map<String, String> query) {
        applyConfigFromQuery(query);
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public HttpServer.ApiResponse status() {
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public boolean isRunning() { return running; }

    /** Release the camera subscription + stop the background thread (used by MainActivity.onDestroy). */
    public synchronized void shutdown() {
        running = false;
        synchronized (subLock) {
            if (subscribed) {
                try { cameraController.unsubscribe(sampler); } catch (Throwable ignored) {}
                subscribed = false;
            }
        }
        if (detectThread != null) {
            try { detectThread.quitSafely(); } catch (Throwable ignored) {}
            detectThread = null;
            detectHandler = null;
        }
    }

    private void detectAndTrack(byte[] jpeg) {
        if (!running || jpeg == null || jpeg.length == 0) return;
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            int prevW = cameraController.getPreviewWidth();
            int prevH = cameraController.getPreviewHeight();
            // Detection input: width locked at 160, height follows the preview ratio (4:3→160x120, 16:9→160x90),
            // no squash, no crop. Rough decode with inSampleSize first, then scale exactly, same technique as FaceTrackCenter.
            boolean fourThree = Math.abs(prevW * 3 - prevH * 4)
                    <= Math.abs(prevW * 9 - prevH * 16);
            int targetW = DETECT_W;
            int targetH = fourThree ? 120 : 90;
            int sample = 1;
            while ((prevW / (sample * 2)) >= targetW && (prevH / (sample * 2)) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sample;
            try {
                bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            } catch (OutOfMemoryError oom) {
                Log.w(TAG, "decode OOM, skip frame");
                return;
            }
            if (bmp == null) return;
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw != targetW || bh != targetH) {
                Bitmap scaled = null;
                try { scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true); }
                catch (Throwable t) { Log.w(TAG, "scale to detect size failed, skip frame", t); }
                if (scaled != null) {
                    try { bmp.recycle(); } catch (Throwable ignored) {}
                    bmp = scaled;
                    bw = bmp.getWidth();
                    bh = bmp.getHeight();
                }
            }
            int w = bw;
            int h = bh;
            int n = w * h;
            int[] px = new int[n];
            try {
                bmp.getPixels(px, 0, w, 0, 0, w, h);
            } catch (Throwable t) {
                Log.w(TAG, "getPixels failed", t);
                return;
            }
            // Per-pixel HSV thresholding (snapshot the parameters, so a config change cannot alter a scan halfway through).
            float qHMin = hMin;
            float qHMax = hMax;
            float qSMin = sMin;
            float qSMax = sMax;
            float qVMin = vMin;
            float qVMax = vMax;
            boolean[] mask = new boolean[n];
            int matches = 0;
            for (int i = 0; i < n; i++) {
                int p = px[i];
                float[] hsv = ColorTrackLogic.rgbToHsv((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                if (ColorTrackLogic.matchHsv(hsv[0], hsv[1], hsv[2], qHMin, qHMax, qSMin, qSMax, qVMin, qVMax)) {
                    mask[i] = true;
                    matches++;
                }
            }
            px = null;
            framesProcessed++;
            lastDetectMs = System.currentTimeMillis();
            lastCostMs = lastDetectMs - t0;
            lastMatches = matches;
            ColorTrackLogic.Blob best = (matches > 0) ? ColorTrackLogic.largestBlob(mask, w, h, minPixels) : null;
            mask = null;
            if (best == null) {
                lastFound = 0;
                hasTrack = false;
                lostStreak++;
                return;
            }
            // Centroid + bbox (normalized; the overlay uses the same coordinate set as face).
            float fx = ((float) best.sumX / best.count + 0.5f) / w;
            float fy = ((float) best.sumY / best.count + 0.5f) / h;
            float fw = (float) (best.x1 - best.x0 + 1) / w;
            float fh = (float) (best.y1 - best.y0 + 1) / h;
            if (fw > 1f) fw = 1f;
            if (fh > 1f) fh = 1f;
            lastFound = 1;
            lastFx = fx;
            lastFy = fy;
            lastFw = fw;
            lastFh = fh;
            lastCoverage = (float) best.count / Math.max(1, n);
            lastPixels = best.count;
            lostStreak = 0;
            hasTrack = true;

            float dx = (fx - 0.5f) * 2f; // -1 left … +1 right
            float dy = (fy - 0.5f) * 2f; // -1 up … +1 down
            if (dx < -1f) dx = -1f;
            if (dx > 1f) dx = 1f;
            if (dy < -1f) dy = -1f;
            if (dy > 1f) dy = 1f;

            // No head servo commands while walk follow is playing an action: the .ubx walk gait moves the head itself (19/20 every frame),
            // and cmd05 would fight it; wait for the step to finish (isPlaying false) before the head takes back over.
            boolean ubxBusy = false;
            ActionDirect ad = actionDirect;
            if (walkFollow && ad != null) {
                try { ubxBusy = ad.isPlaying(); } catch (Throwable ignored) {}
            }
            boolean moved = false;
            int newPan = curPan;
            int newTilt = curTilt;
            float db = deadband;
            // Head-follow master switch: off = detect + box only, neither axis moves.
            if (headFollow && Math.abs(dx) > db) {
                float mag = (Math.abs(dx) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dx < 0) delta = -delta;
                newPan = clamp(curPan + delta, PAN_MIN, PAN_MAX);
            }
            if (headFollow && Math.abs(dy) > db) {
                float mag = (Math.abs(dy) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dy < 0) delta = -delta;
                newTilt = clamp(curTilt + delta, TILT_MIN, TILT_MAX);
            }
            // Send a servo only when the angle truly changed AND at least SERVO_MIN_GAP_MS has passed since the last send (same as FaceTrackCenter).
            long nowMs = System.currentTimeMillis();
            boolean servoDue = nowMs - lastServoSendMs >= SERVO_MIN_GAP_MS;
            if (headFollow && !ubxBusy && servoDue && newPan != curPan) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(PAN_ID, newPan, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curPan = newPan;
                    moved = true;
                }
            }
            if (headFollow && !ubxBusy && servoDue && newTilt != curTilt) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(TILT_ID, newTilt, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curTilt = newTilt;
                    moved = true;
                }
            }
            if (moved) {
                moves++;
                lastMoveMs = nowMs;
                lastServoSendMs = nowMs;
            }
            // ── Walk follow: offset → one turn step, centered but far → one forward step, close → stand still.
            // One action at a time: while the previous one is playing, do not stack a new one (isPlaying guard); frames with no detection send no new step,
            // the step in flight plays out (bounded, no blind walking); if it cannot start (no file/chest board fails), still log a warn, the head keeps following.
            if (walkFollow && !ubxBusy && ad != null) {
                String want = ColorTrackLogic.walkDecision(dx, lastCoverage, turnDb, closeCoverage);
                if (!"none".equals(want)) {
                    String file = "left".equals(want) ? walkLeft
                            : "right".equals(want) ? walkRight : walkFwd;
                    try {
                        if (ad.playActionDirect(file)
                                == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                            lastWalk = file;
                            walkMoves++;
                        } else {
                            Log.w(TAG, "walk step not started: " + file);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "walk step failed: " + file, t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "detectAndTrack failed", t);
        } finally {
            if (bmp != null) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** The color/track/status response body itself (start/stop/config return the same shape, so the front-end parses it once; fields align with face to share the box drawing). */
    public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"running\":").append(running).append(",");
        sb.append("\"detector\":\"color-blob-hsv\",");
        sb.append("\"pan\":").append(curPan).append(",\"tilt\":").append(curTilt).append(",");
        sb.append("\"faces\":").append(lastFound).append(",");
        sb.append("\"fx\":").append(fmt3(lastFx)).append(",\"fy\":").append(fmt3(lastFy));
        sb.append(",\"fw\":").append(fmt3(lastFw)).append(",\"fh\":").append(fmt3(lastFh));
        sb.append(",\"confidence\":").append(fmt3(lastCoverage)).append(",");
        sb.append("\"pixels\":").append(lastPixels).append(",");
        sb.append("\"matches\":").append(lastMatches).append(",");
        sb.append("\"lastWalk\":\"").append(JsonUtil.esc(lastWalk)).append("\",");
        sb.append("\"walkMoves\":").append(walkMoves).append(",");
        ActionDirect ad = actionDirect;
        boolean ubxPlaying = false;
        if (ad != null) {
            try { ubxPlaying = ad.isPlaying(); } catch (Throwable ignored) {}
        }
        sb.append("\"ubxPlaying\":").append(ubxPlaying).append(",");
        sb.append("\"effectiveIntervalMs\":").append(effectiveIntervalMs()).append(",");
        sb.append("\"framesSeen\":").append(framesSeen)
                .append(",\"framesProcessed\":").append(framesProcessed)
                .append(",\"moves\":").append(moves)
                .append(",\"lostStreak\":").append(lostStreak)
                .append(",\"lastDetectMs\":").append(lastDetectMs)
                .append(",\"lastCostMs\":").append(lastCostMs)
                .append(",\"lastMoveMs\":").append(lastMoveMs).append(",");
        sb.append("\"config\":{");
        sb.append("\"intervalMs\":").append(intervalMs).append(",");
        sb.append("\"stepDeg\":").append(stepDeg).append(",");
        sb.append("\"deadband\":").append(fmt3(deadband)).append(",");
        sb.append("\"timeMs\":").append(timeMs).append(",");
        sb.append("\"headFollow\":").append(headFollow).append(",");
        sb.append("\"autoInterval\":").append(autoInterval).append(",");
        sb.append("\"trackIntervalMs\":").append(trackIntervalMs).append(",");
        sb.append("\"hMin\":").append(fmt3(hMin)).append(",");
        sb.append("\"hMax\":").append(fmt3(hMax)).append(",");
        sb.append("\"sMin\":").append(fmt3(sMin)).append(",");
        sb.append("\"sMax\":").append(fmt3(sMax)).append(",");
        sb.append("\"vMin\":").append(fmt3(vMin)).append(",");
        sb.append("\"vMax\":").append(fmt3(vMax)).append(",");
        sb.append("\"minPixels\":").append(minPixels).append(",");
        sb.append("\"walkFollow\":").append(walkFollow).append(",");
        sb.append("\"turnDb\":").append(fmt3(turnDb)).append(",");
        sb.append("\"closeCoverage\":").append(fmt3(closeCoverage)).append(",");
        sb.append("\"walkFwd\":\"").append(JsonUtil.esc(walkFwd)).append("\",");
        sb.append("\"walkLeft\":\"").append(JsonUtil.esc(walkLeft)).append("\",");
        sb.append("\"walkRight\":\"").append(JsonUtil.esc(walkRight)).append("\"");
        sb.append("},");
        sb.append("\"servo\":{\"panId\":").append(PAN_ID)
                .append(",\"tiltId\":").append(TILT_ID)
                .append(",\"panRange\":[").append(PAN_MIN).append(",").append(PAN_MAX).append("]")
                .append(",\"tiltRange\":[").append(TILT_MIN).append(",").append(TILT_MAX).append("]")
                .append(",\"streaming\":").append(cameraController.isStreaming());
        sb.append("}}");
        return sb.toString();
    }
}
