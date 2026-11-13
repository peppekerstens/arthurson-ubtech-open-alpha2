package com.open.alpha2;

import android.content.Context;
import android.content.Intent;

import java.nio.charset.StandardCharsets;

import java.util.Map;

/**
 * Camera stills layer: single-frame snapshot/save/info (streaming goes via /stream/camera, not here).
 * Hardware through the same passed-in CameraController; save broadcasts through Context;
 * shutter sound through RingtoneCenter.
 */
public final class CameraApi {
    private final Context appContext;
    private final CameraController cameraController;
    private final RingtoneCenter ringtoneCenter;
    private volatile FaceTrackCenter faceTrackCenter;
    private volatile ColorTrackCenter colorTrackCenter;
    private volatile VideoCenter videoCenter;

    /** Shared by the GamepadCenter track toggle: read the real state (no self-maintained flag, so mutual exclusion holds on both sides). */
    public boolean isFaceTrackRunning() {
        try {
            return faceTrackCenter != null && faceTrackCenter.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shared by the GamepadCenter track toggle: read the real state. */
    public boolean isColorTrackRunning() {
        try {
            return colorTrackCenter != null && colorTrackCenter.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    public CameraApi(Context context, CameraController cameraController, RingtoneCenter ringtoneCenter) {
        this.appContext = context.getApplicationContext();
        this.cameraController = cameraController;
        this.ringtoneCenter = ringtoneCenter;
    }

    /** For MainActivity wiring: inject the face-tracking center (before injection, face/track/* returns a clear error). */
    public void setFaceTrackCenter(FaceTrackCenter center) {
        this.faceTrackCenter = center;
    }

    private HttpServer.ApiResponse faceOrError() {
        if (faceTrackCenter == null) {
            return HttpServer.ApiResponse.error("face track not initialised");
        }
        return null;
    }

    /** For MainActivity wiring: inject the color-tracking center (before injection, color/track/* returns a clear error). */
    public void setColorTrackCenter(ColorTrackCenter center) {
        this.colorTrackCenter = center;
    }

    private HttpServer.ApiResponse colorOrError() {
        if (colorTrackCenter == null) {
            return HttpServer.ApiResponse.error("color track not initialised");
        }
        return null;
    }

    // -- Pure Java color tracking (see ColorTrackCenter): thin delegate, no logic added here --
    // Mutual exclusion: competes with face tracking for the same head servos (19/20); starting one side stops the other.
    public HttpServer.ApiResponse colorTrackStart(Map<String, String> query) {
        HttpServer.ApiResponse err = colorOrError();
        if (err != null) return err;
        try {
            if (faceTrackCenter != null && faceTrackCenter.isRunning()) faceTrackCenter.stop();
        } catch (Throwable ignored) {}
        return colorTrackCenter.start(query);
    }

    public HttpServer.ApiResponse colorTrackStop() {
        HttpServer.ApiResponse err = colorOrError();
        if (err != null) return err;
        return colorTrackCenter.stop();
    }

    public HttpServer.ApiResponse colorTrackStatus() {
        HttpServer.ApiResponse err = colorOrError();
        if (err != null) return err;
        return colorTrackCenter.status();
    }

    public HttpServer.ApiResponse colorTrackConfig(Map<String, String> query) {
        HttpServer.ApiResponse err = colorOrError();
        if (err != null) return err;
        return colorTrackCenter.config(query);
    }

    /** For MainActivity wiring: inject the video center (before injection, camera/video/* returns a clear error). */
    public void setVideoCenter(VideoCenter center) {
        this.videoCenter = center;
    }

    private HttpServer.ApiResponse videoOrError() {
        if (videoCenter == null) {
            return HttpServer.ApiResponse.error("video not initialised");
        }
        return null;
    }

    // -- On-device mp4 video (see VideoCenter): thin delegate, no logic added here --
    public HttpServer.ApiResponse videoStart(Map<String, String> query) {
        HttpServer.ApiResponse err = videoOrError();
        if (err != null) return err;
        return videoCenter.start(query);
    }

    public HttpServer.ApiResponse videoStop() {
        HttpServer.ApiResponse err = videoOrError();
        if (err != null) return err;
        return videoCenter.stop();
    }

    public HttpServer.ApiResponse videoStatus() {
        HttpServer.ApiResponse err = videoOrError();
        if (err != null) return err;
        return videoCenter.status();
    }

    /** Shared by the recording guard: true while recording (photo/resolution callers must block first, or they interrupt the encoder). */
    public boolean isVideoRecording() {
        try {
            return videoCenter != null && videoCenter.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is the caller on the main thread (the mapped physical button is, the HTTP worker is not). */
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shared by the GamepadCenter mapping: single-button video toggle (stop while recording, record while idle).
     *  Main thread via the physical button: run it in the background (prepare/stop block, ANR death, see traces),
     *  returns true = accepted. The HTTP worker uses the synchronous version (the return value is honest). */
    public boolean toggleVideoRecording() {
        if (onMainThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    toggleVideoRecordingSync();
                }
            }, "CameraVideoToggle").start();
            return videoCenter != null;
        }
        return toggleVideoRecordingSync();
    }

    private boolean toggleVideoRecordingSync() {
        try {
            if (videoCenter == null) return false;
            HttpServer.ApiResponse resp = videoCenter.isRecording()
                    ? videoCenter.stop()
                    : videoCenter.start(new java.util.HashMap<String, String>());
            return resp != null && resp.status == 200
                    && resp.body != null && resp.body.contains("\"ok\":true");
        } catch (Throwable t) {
            return false;
        }
    }

    // -- Android built-in face tracking (see FaceTrackCenter): thin delegate, no logic added here --
    // Mutual exclusion: competes with color tracking for the same head servos (19/20); starting one side stops the other.
    public HttpServer.ApiResponse faceTrackStart(Map<String, String> query) {
        HttpServer.ApiResponse err = faceOrError();
        if (err != null) return err;
        try {
            if (colorTrackCenter != null && colorTrackCenter.isRunning()) colorTrackCenter.stop();
        } catch (Throwable ignored) {}
        return faceTrackCenter.start(query);
    }

    public HttpServer.ApiResponse faceTrackStop() {
        HttpServer.ApiResponse err = faceOrError();
        if (err != null) return err;
        return faceTrackCenter.stop();
    }

    public HttpServer.ApiResponse faceTrackStatus() {
        HttpServer.ApiResponse err = faceOrError();
        if (err != null) return err;
        return faceTrackCenter.status();
    }

    public HttpServer.ApiResponse faceTrackConfig(Map<String, String> query) {
        HttpServer.ApiResponse err = faceOrError();
        if (err != null) return err;
        return faceTrackCenter.config(query);
    }

    public HttpServer.ApiResponse faceTrackProbe() {
        HttpServer.ApiResponse err = faceOrError();
        if (err != null) return err;
        return faceTrackCenter.probe();
    }

    /** Polls CameraController.getLastFrame() until a frame newer than "none yet"
     *  appears, for the single-shot camera/snapshot endpoint. */
    private static CameraController.Frame waitForFrame(CameraController controller, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            CameraController.Frame frame = controller.getLastFrame();
            if (frame != null) {
                return frame;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return controller.getLastFrame();
    }

    /** Shared opening for the three snapshot endpoints: start the camera; on failure return the ready-made error response.
     *  On success return null. */
    private HttpServer.ApiResponse startCameraOrError() {
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(started.error) + "\"}");
        }
        return null;
    }

    /** Shared by snapshot/snapshotSave: wait for AE/AF convergence + focus + AE lock before taking the frame (the previous two copies were word-for-word identical;
     *  takePhotoSave does not use it - takePicture runs AF/AE internally). */
    private void settleCameraForSnapshot() {
        // Wait for AE/AF convergence + focus + AE lock before taking the frame, to avoid a blurry/dark/overexposed shot from pressing the shutter before ready
        cameraController.waitForPreviewReady(2500);
        cameraController.triggerAutoFocusAndWait(2200);
        cameraController.lockAeAwbSync(700);
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Software zoom fallback (when hardware is unsupported, crop-and-enlarge the photo; the previous three copies had identical logic, only variable names differed). */
    private byte[] maybeSoftwareZoom(byte[] jpeg) {
        float z = cameraController.getZoom();
        if (z > 1.01f) {
            CameraController.ZoomInfo zi = cameraController.getZoomInfoSync(800);
            if (zi != null && !zi.hardwareSupported) {
                return cameraController.applySoftwareZoomToJpeg(jpeg, z);
            }
        }
        return jpeg;
    }

    // -- Camera: standard Android legacy Camera API, not SDK-gated (see
    // CameraController for the front/back index quirk on this hardware). The
    // live feed itself is served at GET /stream/camera (see handleStream()) as
    // MJPEG, not through this JSON api/ path - a continuous multipart response
    // doesn't fit the single-JSON-body ApiResponse shape. This single-frame
    // snapshot endpoint just starts the camera (if it isn't already streaming)
    // and returns whatever the most recent preview frame is, for callers that
    // want one still image rather than opening the stream. -----------------------
    public HttpServer.ApiResponse snapshot() {
        if (isVideoRecording()) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"recording in progress, stop video first\"}");
        }
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        if (startErr != null) return startErr;
        settleCameraForSnapshot();
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        cameraController.unlockAeAwbAsync();
        if (frame == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"timed out waiting for a preview frame\"}");
        }
        byte[] jpeg = maybeSoftwareZoom(frame.jpeg);
        String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"jpegBase64\":\"" + b64 + "\"}");
    }

    public HttpServer.ApiResponse snapshotSave(Map<String, String> query) {
        if (isVideoRecording()) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"recording in progress, stop video first\"}");
        }
        // Batch of 9 photos, saved to Android: optional w/h; when not provided, use the current preview resolution; saved to /sdcard/DCIM/Alpha2
        // When provided, validate against the spec ranges.
        Integer wOpt = ApiValidator.optionalIntegerRange(query, "w", 1, 4208);
        Integer hOpt = ApiValidator.optionalIntegerRange(query, "h", 1, 3120);
        int reqW = 0, reqH = 0;
        boolean hasSize = wOpt != null && hOpt != null;
        if (hasSize) {
            reqW = wOpt.intValue();
            reqH = hOpt.intValue();
        }
        int prevW = cameraController.getPreviewWidth();
        int prevH = cameraController.getPreviewHeight();
        if (hasSize) {
            cameraController.setRequestedResolution(reqW, reqH);
            cameraController.forceStopAndWait(3000);
        }
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        settleCameraForSnapshot();
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        cameraController.unlockAeAwbAsync();
        if (frame == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"timed out waiting for frame\"}");
        }
        byte[] jpegToSave = maybeSoftwareZoom(frame.jpeg);
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_" + jpegToSave.length + "_" + cameraController.getPreviewWidth() + "x" + cameraController.getPreviewHeight() + "_" + ts + ".jpg";
            // When a size is given, naming with the given size is more intuitive
            if (hasSize) name = "alpha2_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(jpegToSave); }
            // Trigger a media scan at the same time so the album sees it immediately
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            // Restore the previous resolution (if it was switched)
            if (hasSize && (prevW != reqW || prevH != reqH)) {
                cameraController.setRequestedResolution(prevW, prevH);
                cameraController.forceStopAndWait(2000);
                // Do not reopen automatically; let the front end reopen on demand, to avoid long-term occupation
            }
            String b64 = android.util.Base64.encodeToString(jpegToSave, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + cameraController.getPreviewWidth() + ",\"height\":" + cameraController.getPreviewHeight() + "}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse takePhotoSave(Map<String, String> query) {
        if (isVideoRecording()) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"recording in progress, stop video first\"}");
        }
        // A true single shot (picture size, through the full ISP of Camera.takePicture), saved to Android
        // When not specified, use the largest picture size (see openapi defaults w=4208 h=3120).
        int reqW = ApiValidator.optionalIntRange(query, "w", 1, 4208, 4208);
        int reqH = ApiValidator.optionalIntRange(query, "h", 1, 3120, 3120);
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        CameraController.PhotoResult photo = cameraController.takePhoto(reqW, reqH, 8000);
        if (photo.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(photo.error) + "\"}");
        }
        // Software zoom fallback (when hardware is unsupported, crop-and-enlarge the photo)
        byte[] outJpeg = maybeSoftwareZoom(photo.jpeg);
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_pic_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(outJpeg); }
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            String b64 = android.util.Base64.encodeToString(outJpeg, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + reqW + ",\"height\":" + reqH + ",\"bytes\":" + outJpeg.length + "}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        }
    }

    // Plays the "Sirrah" shutter cue out of the robot's own speaker (see
    // RingtoneCenter.playShutterCue() javadoc) - called by the browser right after a
    // successful camera/snapshot, instead of synthesizing a click sound in
    // the browser itself.
    public HttpServer.ApiResponse shutterSound() {
        ringtoneCenter.playShutterCue();
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse info() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"previewWidth\":" + cameraController.getPreviewWidth() + ","
                + "\"previewHeight\":" + cameraController.getPreviewHeight() + "}");
    }

    public HttpServer.ApiResponse fps() {
        double fps = cameraController.getFps();
        String fpsStr = String.format(java.util.Locale.US, "%.1f", fps);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"fps\":" + fpsStr + ",\"streaming\":" + cameraController.isStreaming() + "}");
    }

    // Digital zoom x1-x5 (when hardware supports it, map to the nearest ratio; otherwise front-end CSS / software crop)
    public HttpServer.ApiResponse zoom(Map<String, String> query) {
        // Two usages: no parameter = query, with zoom = set and return the current state
        String zoomStr = query != null ? query.get("zoom") : null;
        if (zoomStr != null && !zoomStr.isEmpty()) {
            float z;
            try { z = Float.parseFloat(zoomStr.trim()); } catch (Exception e) { throw new IllegalArgumentException("parameter 'zoom' must be a number 1.0-5.0, got: " + zoomStr); }
            if (z < 1.0f || z > 5.0f) throw new IllegalArgumentException("parameter 'zoom' must be between 1.0 and 5.0, got: " + z);
            cameraController.setZoom(z);
            // Wait briefly for the hardware to take effect (up to 200ms)
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        CameraController.ZoomInfo info = cameraController.getZoomInfoSync(1500);
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"zoom\":").append(String.format(java.util.Locale.US, "%.1f", info.current)).append(",");
        sb.append("\"hardwareSupported\":").append(info.hardwareSupported).append(",");
        sb.append("\"maxZoom\":").append(info.maxZoom).append(",");
        sb.append("\"ratios\":[");
        if (info.ratios != null) {
            for (int i = 0; i < info.ratios.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(info.ratios.get(i));
            }
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse supportedSizes() {
        java.util.List<android.hardware.Camera.Size> preview = cameraController.getSupportedPreviewSizesSync(4000);
        java.util.List<android.hardware.Camera.Size> picture = cameraController.getSupportedPictureSizesSync(4000);
        java.util.List<int[]> fpsRanges = cameraController.getSupportedPreviewFpsRangesSync(4000);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"preview\":[");
        if (preview != null) {
            boolean first = true;
            for (android.hardware.Camera.Size s : preview) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
            }
        }
        sb.append("],\"picture\":[");
        if (picture != null) {
            boolean first = true;
            for (android.hardware.Camera.Size s : picture) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
            }
        }
        sb.append("],\"fpsRanges\":[");
        if (fpsRanges != null) {
            boolean first = true;
            for (int[] r : fpsRanges) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(r[0]/1000.0).append("-").append(r[1]/1000.0).append("\"");
            }
        }
        sb.append("],\"current\":\"").append(cameraController.getPreviewWidth()).append("x").append(cameraController.getPreviewHeight()).append("\"}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse resolution(Map<String, String> query) {
        if (isVideoRecording()) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"recording in progress, stop video first\"}");
        }
        // Explicit validation against the spec (w 1-4208, h 1-3120).
        int w = ApiValidator.requireIntRange(query, "w", 1, 4208);
        int h = ApiValidator.requireIntRange(query, "h", 1, 3120);
        cameraController.setRequestedResolution(w, h);
        // Block until the camera is genuinely released before answering - see
        // forceStopAndWait()'s javadoc for why stopIfIdle() alone isn't enough
        // here (it doesn't guarantee timing, just that it *will* close once idle).
        cameraController.forceStopAndWait(3000);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"requestedWidth\":" + w
                + ",\"requestedHeight\":" + h + "}");
    }

    // -- Camera streaming (MJPEG over "/stream/camera") -------------------------------

    private static final String MJPEG_BOUNDARY = "alpha2testpanelframe";
    /**
     * Serves the live camera feed as "multipart/x-mixed-replace" MJPEG - the format
     * every browser's plain &lt;img src="..."&gt; already knows how to render as a live
     * video-like feed with zero client-side JS, which is why this is a stream/ HTTP
     * route rather than a WebSocket: an &lt;img&gt; tag can't speak WebSocket, but it can
     * point straight at a URL that never stops responding.
     *
     * Runs on an HttpServer worker thread and blocks for as long as the client stays
     * connected, same as WebSocketServer.Connection.readLoop() does for "/ws" - both
     * rely on the pool's cached-thread-per-connection model rather than needing NIO.
     */
    public void handleCameraStream(java.net.Socket socket) throws java.io.IOException {
        CameraController.StartResult started = cameraController.start(8000);
        java.io.OutputStream out = socket.getOutputStream();
        if (started.error != null) {
            byte[] msg = ("Camera unavailable: " + started.error).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // BlockingQueue rather than writing directly from onFrame(): onFrame() runs on
        // CameraController's own camera thread and must return immediately (it's also
        // fanning the same frame out to every other connected stream client) - it must
        // not block on this connection's socket write, which can stall arbitrarily long
        // on a slow/stuck client. capacity 1 + offer-that-drops-the-oldest keeps this
        // socket's writer thread always working from the newest frame rather than
        // buffering up a backlog if the network can't keep up with 30fps.
        final java.util.concurrent.ArrayBlockingQueue<CameraController.Frame> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        CameraController.FrameListener listener = new CameraController.FrameListener() {
            @Override
            public void onFrame(CameraController.Frame frame) {
                queue.poll(); // drop whatever stale frame was waiting, if any
                queue.offer(frame);
            }
        };
        cameraController.subscribe(listener);
        try {
            while (true) {
                CameraController.Frame frame;
                try {
                    frame = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) {
                    // No frame in 10s - camera likely died; stop rather than hold the
                    // connection (and the pool thread) open forever with a frozen image.
                    break;
                }
                out.write(("--" + MJPEG_BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.jpeg.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame.jpeg);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); // each part must reach the client promptly, not batch up
            }
        } finally {
            cameraController.unsubscribe(listener);
            // Only actually releases the camera once every other stream client (if any)
            // has also disconnected - see CameraController.stopIfIdle() javadoc.
            cameraController.stopIfIdle();
        }
    }

}
