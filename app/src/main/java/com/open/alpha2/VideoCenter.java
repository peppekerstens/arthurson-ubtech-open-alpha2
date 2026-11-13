package com.open.alpha2;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.util.Map;

/**
 * On-robot video recording (mp4, legacy Camera1 + MediaRecorder; shares the
 * camera handoff with CameraController).
 *
 * Flow (see start): open the camera (CameraController.start) -> attach a dummy
 * listener to hold off a mistaken release by stopIfIdle -> beginRecorderHandoff
 * (detach the preview callback + unlock) -> configure MediaRecorder (try the
 * mic with audio first; if prepare/start fails, reset and reconfigure video
 * only, so the recording does not fail outright) -> start.
 * Wrap-up (see stop): recorder.stop/reset/release -> endRecorderHandoff (lock
 * + re-attach the preview callback) -> media scan. Recording runs until the
 * user presses stop; no length limit.
 *
 * Concurrency constraints:
 * - During recording the preview callback is detached: MJPEG subscribers get
 *   no new frames (they time out and disconnect after 10 seconds, see
 *   CameraApi). Face/color tracking subscribers are still there but get no
 *   frames (they stay put; no motion from stale frames).
 * - takePhoto/snapshot/snapshotSave/resolution are rejected outright during
 *   recording (the CameraApi guard); otherwise takePicture would stop the
 *   preview and break the encoder.
 * - onDestroy first stops recording via stopIfRecording() before releasing
 *   the camera (see MainActivity).
 * - All methods synchronized (called from HTTP worker threads; stop blocks
 *   for about 1 second).
 */
public final class VideoCenter {
    private static final String TAG = "VideoCenter";

    private static final int VIDEO_W = 640;
    private static final int VIDEO_H = 480;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 2000000;
    private static final int AUDIO_SAMPLERATE = 44100;
    // Reject recording when free space is below this value (a half-written mp4 is useless).
    private static final long MIN_FREE_BYTES = 50L * 1024 * 1024;

    private final Context appContext;
    private final CameraController cameraController;

    private MediaRecorder recorder;
    private Surface previewSurface;
    private boolean recording;
    private boolean hasAudio;
    private String outPath = "";
    private long startElapsedMs;

    private String lastPath = "";
    private long lastDurationMs;
    private long lastBytes;
    private boolean lastHasAudio;

    // Guard against a mistaken release by stopIfIdle: during recording the
    // preview callback is detached and listeners can empty out at any time
    // (MJPEG timeout disconnect + tracking getting no new frames); this empty
    // subscription makes stopIfIdle always see a non-empty list.
    private final CameraController.FrameListener keepAlive =
            new CameraController.FrameListener() {
                @Override public void onFrame(CameraController.Frame frame) {
                }
            };
    private boolean keepAliveOn;

    public VideoCenter(Context context, CameraController cameraController) {
        this.appContext = context.getApplicationContext();
        this.cameraController = cameraController;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    /** Save directory (the same directory as photos, so it shows up in the album right away). */
    private static File videoDir() {
        File dir = new File("/sdcard/DCIM/Alpha2");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String newVideoPath(File dir) {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                java.util.Locale.US).format(new java.util.Date());
        return new File(dir, "alpha2_vid_" + ts + ".mp4").getAbsolutePath();
    }

    /** Single configure + record (withAudio = whether the mic is configured; null returned = already recording, otherwise returns an error). */
    private String tryStart(CameraController.RecorderHandoff h, String path, boolean withAudio) {
        MediaRecorder rec = new MediaRecorder();
        Surface surface = null;
        try {
            rec.setCamera(h.camera);
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (withAudio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            rec.setVideoSize(VIDEO_W, VIDEO_H);
            rec.setVideoFrameRate(VIDEO_FPS);
            rec.setVideoEncodingBitRate(VIDEO_BITRATE);
            if (withAudio) rec.setAudioSamplingRate(AUDIO_SAMPLERATE);
            surface = new Surface(h.previewTexture);
            rec.setPreviewDisplay(surface);
            rec.setOutputFile(path);
            rec.prepare();
            rec.start();
        } catch (Throwable t) {
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable ignored) {
                }
            }
            return String.valueOf(t.getMessage());
        }
        recorder = rec;
        previewSurface = surface;
        return null;
    }

    /** Start recording (the query has no parameters for now, kept for dispatcher consistency). Audio takes priority; if the mic cannot be acquired, fall back to video only. */
    public synchronized HttpServer.ApiResponse start(Map<String, String> query) {
        if (recording) {
            return HttpServer.ApiResponse.error("already recording: " + outPath);
        }
        try {
            android.os.StatFs stat = new android.os.StatFs("/sdcard/DCIM");
            if (stat.getAvailableBytes() < MIN_FREE_BYTES) {
                return HttpServer.ApiResponse.error("low storage, need 50MB free to record");
            }
        } catch (Throwable t) {
            return HttpServer.ApiResponse.error("storage check failed: " + t.getMessage());
        }
        CameraController.StartResult sr = cameraController.start(8000);
        if (sr.error != null) {
            return HttpServer.ApiResponse.error(sr.error);
        }
        cameraController.subscribe(keepAlive);
        keepAliveOn = true;
        CameraController.RecorderHandoff h = cameraController.beginRecorderHandoff(5000);
        if (h == null || h.camera == null || h.previewTexture == null) {
            if (keepAliveOn) {
                try {
                    cameraController.unsubscribe(keepAlive);
                } catch (Throwable ignored) {
                }
                keepAliveOn = false;
            }
            return HttpServer.ApiResponse.error("camera handoff for recorder failed");
        }
        String path = newVideoPath(videoDir());
        String err = tryStart(h, path, true);
        boolean audio = true;
        if (err != null) {
            Log.w(TAG, "record with audio failed, retry silent: " + err);
            err = tryStart(h, path, false);
            audio = false;
        }
        if (err != null) {
            try {
                cameraController.endRecorderHandoff(5000);
            } catch (Throwable ignored) {
            }
            if (keepAliveOn) {
                try {
                    cameraController.unsubscribe(keepAlive);
                } catch (Throwable ignored) {
                }
                keepAliveOn = false;
            }
            return HttpServer.ApiResponse.error("recorder start failed: " + err);
        }
        recording = true;
        hasAudio = audio;
        outPath = path;
        startElapsedMs = SystemClock.elapsedRealtime();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":true,\"path\":\""
                + MainActivity.jsonSafe(path) + "\",\"hasAudio\":" + audio + "}");
    }

    /** Stop recording (error if not recording; if stop throws, delete the broken file and return an error). */
    public synchronized HttpServer.ApiResponse stop() {
        if (!recording) {
            return HttpServer.ApiResponse.error("not recording");
        }
        MediaRecorder rec = recorder;
        recorder = null;
        String path = outPath;
        long durMs = SystemClock.elapsedRealtime() - startElapsedMs;
        boolean audio = hasAudio;
        recording = false;
        outPath = "";
        hasAudio = false;
        String err = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Throwable ignored) {
            }
            previewSurface = null;
        }
        try {
            cameraController.endRecorderHandoff(5000);
        } catch (Throwable t) {
            Log.w(TAG, "endRecorderHandoff failed", t);
        }
        if (keepAliveOn) {
            try {
                cameraController.unsubscribe(keepAlive);
            } catch (Throwable ignored) {
            }
            keepAliveOn = false;
        }
        File f = new File(path);
        if (err != null || !f.exists() || f.length() == 0) {
            try {
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {
            }
            return HttpServer.ApiResponse.error("recorder stop failed"
                    + (err != null ? ": " + err : ": empty file"));
        }
        try {
            appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    android.net.Uri.fromFile(f)));
        } catch (Throwable ignored) {
        }
        lastPath = path;
        lastDurationMs = durMs;
        lastBytes = f.length();
        lastHasAudio = audio;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":false,\"path\":\""
                + MainActivity.jsonSafe(path) + "\",\"durationMs\":" + durMs
                + ",\"bytes\":" + f.length() + ",\"hasAudio\":" + audio + "}");
    }

    /** Recording status (while recording, returns the elapsed time; while idle, returns the previous finished file). */
    public synchronized HttpServer.ApiResponse status() {
        if (recording) {
            long elapsed = SystemClock.elapsedRealtime() - startElapsedMs;
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":true,\"path\":\""
                    + MainActivity.jsonSafe(outPath) + "\",\"elapsedMs\":" + elapsed
                    + ",\"hasAudio\":" + hasAudio + "}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":false,\"lastPath\":\""
                + MainActivity.jsonSafe(lastPath) + "\",\"lastDurationMs\":" + lastDurationMs
                + ",\"lastBytes\":" + lastBytes + ",\"lastHasAudio\":" + lastHasAudio + "}");
    }

    /** Shared by onDestroy: stop immediately while recording (does not throw, so the camera shutdown does not clash with the encoder). */
    public void stopIfRecording() {
        try {
            if (isRecording()) stop();
        } catch (Throwable ignored) {
        }
    }
}
