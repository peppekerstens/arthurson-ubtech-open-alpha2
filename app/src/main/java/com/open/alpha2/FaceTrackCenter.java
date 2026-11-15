package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * Built-in Android face tracking: zero extra dependencies, uses only the
 * framework's own {@code android.media.FaceDetector} (present since API 1,
 * safe on API 19/22, no Play Services, no model download; matches this
 * project's offline/framework-only policy).
 *
 * Pipeline: {@link CameraController} continuous preview JPEG frames -> this
 * class samples by intervalMs -> a background thread downscales and decodes
 * (4:3 preview -> 320x240, 16:9 -> 320x180, RGB_565; FaceDetector requires
 * RGB_565 and an even width) -> findFaces takes the largest face -> the
 * normalized offset dx/dy -> a dead band plus a proportional step drive the
 * head yaw(19)/pitch(20) to follow (cmd05 single-servo, via {@link UbxApi};
 * pose tracking keeps its record and does not touch the other 19 axes).
 *
 * ROI follow (roiEnabled, default on): after one full-frame hit, later frames
 * crop a box 2x the last face box (only when >=96px and smaller than 80% of
 * the frame) and detect directly, without upsampling pixels - an earlier test
 * showed upscaled detection dropped recall to 2%; direct detection keeps
 * recall. One ROI frame takes ~20-30ms, which sustains 10fps at 320x240;
 * three missed scans in a row fall back to full frame. Servo commands also
 * have a 150ms minimum gap, so 10fps does not hammer the chest serial port.
 *
 * Safety/performance notes:
 * - Default: detect every 600ms (a 320x180 FaceDetector takes about 50-150ms
 *   on RK3288); the camera thread only does the throttle check and the post,
 *   and never blocks (see the comment in CameraController.onPreviewFrame).
 * - Each pass moves at most one stepDeg (default 5 degrees), clamped to the
 *   same calibration table the frontend uses (19: 75-165, 20: 105-155, home
 *   120, see SERVO_CALIBRATION in app-core.js), so it never hits the
 *   mechanical limits; stop tracking before playing an action (UbxPlayer), or
 *   both sides fight over 19/20. Direction was verified on real hardware, so
 *   there is no invert switch.
 * - Face WS logs are muted (real-hardware requirement): nothing goes through
 *   EventBus; only HTTP status polling and the frontend overlay remain; state
 *   is polled via face/track/status.
 */
public final class FaceTrackCenter {
    private static final String TAG = "FaceTrackCenter";

    /** Head servos: 19 yaw (left/right), 20 pitch (up/down). Ranges match the frontend calibration table. */
    public static final int PAN_ID = 19;
    public static final int TILT_ID = 20;
    public static final int PAN_MIN = 75;
    public static final int PAN_MAX = 165;
    public static final int PAN_HOME = 120;
    public static final int TILT_MIN = 105;
    public static final int TILT_MAX = 155;
    public static final int TILT_HOME = 120;

    public static final int DEFAULT_INTERVAL_MS = 500;
    public static final int DEFAULT_STEP_DEG = 17;
    public static final float DEFAULT_DEADBAND = 0.10f;
    public static final int DEFAULT_TIME_MS = 400;
    public static final int DEFAULT_MAX_FACES = 3;

    private final CameraController cameraController;
    private final UbxApi ubxApi;

    // -- Tunable parameters (volatile, written by the HTTP thread, read by the detect thread) --
    private volatile boolean running = false;
    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int stepDeg = DEFAULT_STEP_DEG;
    private volatile float deadband = DEFAULT_DEADBAND;
    private volatile int timeMs = DEFAULT_TIME_MS;
    private volatile int maxFaces = DEFAULT_MAX_FACES;
    /** Master head-follow switch (real-hardware requirement: replaces the old per-axis pan/tilt switches). Off = detect and draw the box only, the head does not move. On = 19/20 both follow. */
    private volatile boolean headFollow = true;
    /** ROI follow switch: on = after one detection, scan only a small box near the face (one frame ~20-30ms, for 10fps at 320x240);
     *  off = scan the full image every frame (slow but stable). Missing the face more than the limit falls back to full frame, see ROI_MISS_LIMIT. */
    private volatile boolean roiEnabled = true;
    /** Automatic interval (default on): no face / full-frame search uses intervalMs (slow, saves CPU); ROI following a face uses
     *  trackIntervalMs (fast, tracks closely). Off = always use intervalMs (old manual behavior). */
    private volatile boolean autoInterval = true;
    private volatile int trackIntervalMs = 100;

    // -- State / statistics --
    private volatile int curPan = PAN_HOME;
    private volatile int curTilt = TILT_HOME;
    private volatile long framesSeen = 0;
    private volatile long framesProcessed = 0;
    private volatile long moves = 0;
    private volatile long lastDetectMs = 0;
    private volatile int lastFaces = 0;
    private volatile float lastFx = -1f;
    private volatile float lastFy = -1f;
    private volatile float lastFw = 0f;
    private volatile float lastFh = 0f;
    private volatile float lastConfidence = 0f;
    private volatile long lastMoveMs = 0;
    private volatile int lostStreak = 0;
    /** Detect cost of the previous frame in ms (decode + detect; the measured basis for tuning the fps ceiling). */
    private volatile long lastCostMs = 0;

    private HandlerThread detectThread;
    private Handler detectHandler;
    private final Object subLock = new Object();
    private boolean subscribed = false;
    private volatile long lastSampleMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** FaceDetector cached by (width, height, cap): native init is not cheap; do not create one per frame.
     *  Only read/written on the detect worker thread, inherently single-threaded, no synchronization needed. */
    private FaceDetector cachedDetector;
    private int cachedW = -1;
    private int cachedH = -1;
    private int cachedCap = -1;
    /** Second cache for ROI only (the ROI size changes as the face moves; kept separate from the main cache to avoid evictions). */
    private FaceDetector cachedRoiDetector;
    private int cachedRoiW = -1;
    private int cachedRoiH = -1;
    // -- ROI follow state (roiHasTrack is read by the camera thread (for the auto interval); the rest is only read/written on the worker thread) --
    /** Face box of the last full-frame hit (normalized to the detect image). Basis for the ROI crop box. */
    private volatile boolean roiHasTrack = false;
    private float roiFx = 0.5f;
    private float roiFy = 0.5f;
    private float roiFw = 0.3f;
    private float roiFh = 0.4f;
    private int roiMiss = 0;
    /** Whether this frame ran in ROI (still reported in event/status, visible when tuning). */
    private volatile boolean roiTracking = false;
    /** After how many consecutive ROI misses to fall back to full frame (the face may have left the box). */
    private static final int ROI_MISS_LIMIT = 3;
    /** How many times the ROI box is the last face box (direct detection, no pixel upsampling - an earlier test showed upscaled detection dropped recall to 2%). */
    private static final float ROI_EXPAND = 2.0f;
    /** Below this size, do not crop (a small distant face needs the full-frame context; run full frame directly). */
    private static final int ROI_MIN_PX = 96;
    /** If the ROI is nearly the full frame, do not crop (it saves little; run full frame directly). */
    private static final float ROI_MAX_FRACTION = 0.8f;
    /** Minimum servo command gap in ms: at 10fps, do not hammer the chest serial port every frame (see the UbxApi read-gap comment). */
    private static final long SERVO_MIN_GAP_MS = 150;
    private volatile long lastServoSendMs = 0;

    private final CameraController.FrameListener sampler = new CameraController.FrameListener() {
        @Override public void onFrame(CameraController.Frame frame) {
            if (!running) return;
            framesSeen++;
            long now = System.currentTimeMillis();
            // Throttling: the camera thread only does the time check; the detect itself is posted to a background thread.
            // Lesson from the old FaceDetectCenter on real hardware: sustained high load on the RK3288 reboots at the system level (no tombstone),
            // so no-face / full-frame search uses the slow intervalMs and ROI face-following uses the fast trackIntervalMs
            // (autoInterval off = always slow); inFlight prevents post pileup when detection is slower than the interval.
            // (The inner class reads the outer volatile fields / private methods directly; that is legal.)
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
                    try { detectAndFollow(jpeg); }
                    finally { inFlight.set(false); }
                }
            });
        }
    };

    public FaceTrackCenter(CameraController cameraController, UbxApi ubxApi) {
        this.cameraController = cameraController;
        this.ubxApi = ubxApi;
    }

    private void ensureThreadLocked() {
        if (detectThread == null || !detectThread.isAlive()) {
            detectThread = new HandlerThread("FaceTrackThread");
            detectThread.start();
            detectHandler = new Handler(detectThread.getLooper());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** Currently effective interval (shared by sampler and status; auto on = fast when following a face, otherwise slow). */
    private int effectiveIntervalMs() {
        if (autoInterval && roiHasTrack) return trackIntervalMs;
        return intervalMs;
    }

    /** Read and apply the optional tracking parameters from the query (shared by start/config; a missing key keeps the current value). */
    public void applyConfigFromQuery(Map<String, String> query) {
        if (query == null) return;
        intervalMs = ApiValidator.optionalIntRange(query, "intervalMs", 100, 3000, intervalMs);
        trackIntervalMs = ApiValidator.optionalIntRange(query, "trackIntervalMs", 100, 3000, trackIntervalMs);
        stepDeg = ApiValidator.optionalIntRange(query, "stepDeg", 1, 30, stepDeg);
        deadband = ApiValidator.optionalFloatRange(query, "deadband", 0.0, 0.5, deadband);
        timeMs = ApiValidator.optionalIntRange(query, "timeMs", 20, 2000, timeMs);
        maxFaces = ApiValidator.optionalIntRange(query, "maxFaces", 1, 5, maxFaces);
        if (query.containsKey("headFollow") && query.get("headFollow") != null
                && !query.get("headFollow").isEmpty()) {
            headFollow = ApiValidator.optionalBoolean(query, "headFollow", headFollow);
        }
        if (query.containsKey("roiEnabled") && query.get("roiEnabled") != null
                && !query.get("roiEnabled").isEmpty()) {
            roiEnabled = ApiValidator.optionalBoolean(query, "roiEnabled", roiEnabled);
            if (!roiEnabled) { roiHasTrack = false; roiMiss = 0; roiTracking = false; }
        }
        if (query.containsKey("autoInterval") && query.get("autoInterval") != null
                && !query.get("autoInterval").isEmpty()) {
            autoInterval = ApiValidator.optionalBoolean(query, "autoInterval", autoInterval);
        }
    }

    /** Start tracking: first make sure the camera is streaming, then subscribe to sampling. Called on an HTTP worker thread (blocks while the camera starts). */
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
        roiHasTrack = false;
        roiMiss = 0;
        roiTracking = false;
        // Face WS logs are muted (real-hardware requirement): start/stop and per-frame events never go through EventBus; only HTTP status polling remains.
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public synchronized HttpServer.ApiResponse stop() {
        running = false;
        roiTracking = false;
        roiHasTrack = false;
        synchronized (subLock) {
            if (subscribed) {
                try { cameraController.unsubscribe(sampler); } catch (Throwable ignored) {}
                subscribed = false;
            }
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

    /** Probe the hardware / software face-detection capability (read-only, does not start the tracking loop).
     *  hardwareMaxFaces>0 = the HAL supports Camera.startFaceDetection (zero-CPU full-speed path, can do 10+fps);
     *  softwareAvailable = the framework FaceDetector can be instantiated (the current software path). */
    public HttpServer.ApiResponse probe() {
        int hw = -1;
        try { hw = cameraController.getMaxNumDetectedFacesSync(4000); }
        catch (Throwable t) { Log.w(TAG, "hw probe failed", t); }
        boolean sw = false;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
            FaceDetector fd = new FaceDetector(320, 240, 1);
            sw = (fd != null);
        } catch (Throwable t) { sw = false; }
        finally {
            if (bmp != null) { try { bmp.recycle(); } catch (Throwable ignored) {} }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"hardwareMaxFaces\":").append(hw).append(",");
        sb.append("\"hardwareSupported\":").append(hw > 0).append(",");
        sb.append("\"softwareAvailable\":").append(sw).append(",");
        sb.append("\"detector\":\"android.media.FaceDetector\",");
        sb.append("\"previewWidth\":").append(cameraController.getPreviewWidth()).append(",");
        sb.append("\"previewHeight\":").append(cameraController.getPreviewHeight());
        sb.append("}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public boolean isRunning() { return running; }

    /** Release the camera subscription and stop the background thread (used by MainActivity.onDestroy). */
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

    private void detectAndFollow(byte[] jpeg) {
        if (!running || jpeg == null || jpeg.length == 0) return;
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            int prevW = cameraController.getPreviewWidth();
            int prevH = cameraController.getPreviewHeight();
            // Detect input size: width locked at 320 (measured upper limit; larger drops recall to 0%), height follows the preview ratio -
            // 4:3 (800x600 etc.) -> 320x240, 16:9 (1280x720 etc.) -> 320x180, no squash,
            // no crop, the face does not distort. First a coarse decode with inSampleSize (a power of 2, stop once the decoded size >= target), then
            // createScaledBitmap scales precisely to the target in one step.
            boolean fourThree = Math.abs(prevW * 3 - prevH * 4)
                    <= Math.abs(prevW * 9 - prevH * 16);
            int targetW = 320;
            int targetH = fourThree ? 240 : 180;
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
            // FaceDetector hard-requires RGB_565.
            if (bmp.getConfig() != Bitmap.Config.RGB_565) {
                Bitmap converted = null;
                try { converted = bmp.copy(Bitmap.Config.RGB_565, false); }
                catch (Throwable t) { Log.w(TAG, "RGB_565 convert failed", t); }
                bmp.recycle();
                bmp = converted;
                if (bmp == null) return;
            }
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            // Scale to the target size (also guarantees an even width, which FaceDetector hard-requires).
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
            // Width must be even (an odd width is cropped by 1px to keep findFaces from throwing; after the scaling above the width is normally even, this is a pure failsafe).
            if ((bw & 1) == 1 && bw > 2) {
                Bitmap cropped = null;
                try { cropped = Bitmap.createBitmap(bmp, 0, 0, bw - 1, bh); }
                catch (Throwable t) { Log.w(TAG, "even-crop failed", t); }
                if (cropped != null) {
                    bmp.recycle();
                    bmp = cropped;
                    bw = bmp.getWidth();
                    bh = bmp.getHeight();
                }
            }
            // -- ROI crop: compute the ROI rectangle only when there is a previous face box and the switch is on --
            Bitmap work = bmp;
            boolean inRoi = false;
            int roiLeft = 0;
            int roiTop = 0;
            int roiW = bw;
            int roiH = bh;
            if (roiEnabled && roiHasTrack) {
                float cx = roiFx * bw;
                float cy = roiFy * bh;
                float rw = Math.max(roiFw * bw * ROI_EXPAND, ROI_MIN_PX);
                float rh = Math.max(roiFh * bh * ROI_EXPAND, ROI_MIN_PX);
                if (rw * rh < bw * bh * ROI_MAX_FRACTION) {
                    int left = Math.round(cx - rw / 2);
                    int top = Math.round(cy - rh / 2);
                    int right = Math.round(cx + rw / 2);
                    int bottom = Math.round(cy + rh / 2);
                    if (left < 0) { right -= left; left = 0; }
                    if (top < 0) { bottom -= top; top = 0; }
                    if (right > bw) { left -= right - bw; right = bw; }
                    if (bottom > bh) { top -= bottom - bh; bottom = bh; }
                    if (left < 0) left = 0;
                    if (top < 0) top = 0;
                    int cw = right - left;
                    int ch = bottom - top;
                    if (cw >= ROI_MIN_PX && ch >= ROI_MIN_PX && cw > 0 && ch > 0) {
                        // Even alignment (FaceDetector requires an even width).
                        if ((cw & 1) == 1) { if (left + cw < bw) cw++; else { left++; cw--; } }
                        if (cw >= ROI_MIN_PX && ch >= ROI_MIN_PX && left + cw <= bw && top + ch <= bh) {
                            Bitmap cropped = null;
                            try { cropped = Bitmap.createBitmap(bmp, left, top, cw, ch); }
                            catch (Throwable t) { Log.w(TAG, "roi crop failed", t); }
                            if (cropped != null) {
                                work = cropped;
                                inRoi = true;
                                roiLeft = left;
                                roiTop = top;
                                roiW = cw;
                                roiH = ch;
                            }
                        }
                    }
                }
            }
            roiTracking = inRoi;
            int ww = work.getWidth();
            int wh = work.getHeight();
            // ROI tracks only one face (the one being followed); cap=1 is faster. Full frame uses maxFaces.
            int cap = inRoi ? 1 : Math.max(1, Math.min(5, maxFaces));
            FaceDetector fd;
            if (inRoi) {
                if (cachedRoiDetector == null || ww != cachedRoiW || wh != cachedRoiH) {
                    try {
                        cachedRoiDetector = new FaceDetector(ww, wh, 1);
                    } catch (Throwable t) {
                        Log.w(TAG, "ROI FaceDetector ctor failed", t);
                        try { work.recycle(); } catch (Throwable ignored) {}
                        return;
                    }
                    cachedRoiW = ww;
                    cachedRoiH = wh;
                }
                fd = cachedRoiDetector;
            } else {
                if (cachedDetector == null || bw != cachedW || bh != cachedH || cap != cachedCap) {
                    try {
                        cachedDetector = new FaceDetector(bw, bh, cap);
                    } catch (Throwable t) {
                        Log.w(TAG, "FaceDetector ctor failed", t);
                        return;
                    }
                    cachedW = bw;
                    cachedH = bh;
                    cachedCap = cap;
                }
                fd = cachedDetector;
            }
            FaceDetector.Face[] faces = new FaceDetector.Face[cap];
            int found;
            try {
                found = fd.findFaces(work, faces);
            } catch (Throwable t) {
                Log.w(TAG, "findFaces failed", t);
                if (inRoi) { try { work.recycle(); } catch (Throwable ignored) {} }
                return;
            }
            // The ROI crop must be recycled (the full-frame bmp is kept for the finally block).
            if (inRoi) { try { work.recycle(); } catch (Throwable ignored) {} }
            framesProcessed++;
            lastDetectMs = System.currentTimeMillis();
            lastCostMs = lastDetectMs - t0;
            if (found <= 0) {
                lastFaces = 0;
                lostStreak++;
                if (inRoi) {
                    roiMiss++;
                    if (roiMiss >= ROI_MISS_LIMIT) { roiHasTrack = false; roiMiss = 0; }
                }
                return;
            }
            // Take the face with the largest eye distance (the biggest face) and a higher confidence.
            FaceDetector.Face best = null;
            float bestScore = -1f;
            for (int i = 0; i < found; i++) {
                FaceDetector.Face f = faces[i];
                if (f == null) continue;
                float score = f.eyesDistance() * (0.5f + f.confidence());
                if (score > bestScore) { bestScore = score; best = f; }
            }
            if (best == null) {
                lastFaces = 0;
                lostStreak++;
                return;
            }
            PointF mid = new PointF();
            best.getMidPoint(mid);
            float eyes = best.eyesDistance();
            float conf = best.confidence();
            // Filter out false detections with very low confidence (FaceDetector confidence is about 0-1).
            if (conf < 0.15f) {
                lastFaces = 0;
                lostStreak++;
                if (inRoi) {
                    roiMiss++;
                    if (roiMiss >= ROI_MISS_LIMIT) { roiHasTrack = false; roiMiss = 0; }
                }
                return;
            }
            // Map the ROI coordinates back to the full image (pixels of the detect image; eyesDistance is already in pixels, no rescaling - the crop does not upsample).
            float fullMx = inRoi ? roiLeft + mid.x : mid.x;
            float fullMy = inRoi ? roiTop + mid.y : mid.y;
            float fx = fullMx / Math.max(1, bw);
            float fy = fullMy / Math.max(1, bh);
            // Estimated face box: width ~= eyes*2.2, height scaled proportionally (for the overlay, normalized).
            float fw = (eyes * 2.2f) / Math.max(1, bw);
            float fh = fw * bw / Math.max(1, bh) * 1.25f;
            if (fw > 1f) fw = 1f;
            if (fh > 1f) fh = 1f;
            lastFaces = found;
            lastFx = fx;
            lastFy = fy;
            lastFw = fw;
            lastFh = fh;
            lastConfidence = conf;
            lostStreak = 0;
            // ROI track update: on a hit, this box becomes the basis for the next frame (tracks closely); on a miss, roiMiss already accumulated above.
            roiHasTrack = true;
            roiMiss = 0;
            roiFx = Math.max(0f, Math.min(1f, fx));
            roiFy = Math.max(0f, Math.min(1f, fy));
            roiFw = Math.max(0.05f, Math.min(1f, fw));
            roiFh = Math.max(0.05f, Math.min(1f, fh));

            float dx = (fx - 0.5f) * 2f; // -1 left ... +1 right
            float dy = (fy - 0.5f) * 2f; // -1 up ... +1 down
            if (dx < -1f) dx = -1f;
            if (dx > 1f) dx = 1f;
            if (dy < -1f) dy = -1f;
            if (dy > 1f) dy = 1f;

            boolean moved = false;
            int newPan = curPan;
            int newTilt = curTilt;
            float db = deadband;
            // Master head-follow switch: off = detect and draw the box only, neither axis moves (for debugging / showing the face box).
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
            // Send a servo command only when the angle really changed and enough time passed since the last send (SERVO_MIN_GAP_MS):
            // at 10fps, do not hammer the chest serial port every frame (see the UbxApi read-gap comment).
            long nowMs = System.currentTimeMillis();
            boolean servoDue = nowMs - lastServoSendMs >= SERVO_MIN_GAP_MS;
            if (headFollow && servoDue && newPan != curPan) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(PAN_ID, newPan, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curPan = newPan;
                    moved = true;
                }
            }
            if (headFollow && servoDue && newTilt != curTilt) {
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
        } catch (Throwable t) {
            Log.w(TAG, "detectAndFollow failed", t);
        } finally {
            if (bmp != null) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** Body of the face/track/status response (start/stop/config return the same shape; the frontend parses it once). */
    public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"running\":").append(running).append(",");
        sb.append("\"detector\":\"android.media.FaceDetector\",");
        sb.append("\"pan\":").append(curPan).append(",\"tilt\":").append(curTilt).append(",");
        sb.append("\"faces\":").append(lastFaces).append(",");
        sb.append("\"fx\":").append(fmt3(lastFx)).append(",\"fy\":").append(fmt3(lastFy));
        sb.append(",\"fw\":").append(fmt3(lastFw)).append(",\"fh\":").append(fmt3(lastFh));
        sb.append(",\"confidence\":").append(fmt3(lastConfidence)).append(",");
        sb.append("\"roiTracking\":").append(roiTracking).append(",");
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
        sb.append("\"maxFaces\":").append(maxFaces).append(",");
        sb.append("\"headFollow\":").append(headFollow).append(",");
        sb.append("\"roiEnabled\":").append(roiEnabled).append(",");
        sb.append("\"autoInterval\":").append(autoInterval).append(",");
        sb.append("\"trackIntervalMs\":").append(trackIntervalMs);
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
