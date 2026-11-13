package com.open.alpha2;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * APK download (used by the Google TTS area of the "resource download" card in the experiments tab).
 *
 * <p>Fixed single URL (allowlist; no arbitrary URL, SSRF prevention):
 * the specified version of Google TTS. The backend drops the apk directly into the sdcard root;
 * after it lands, open the system installer to wait for manual confirmation (pressed with the
 * scrcpy mouse; this device has no touchscreen).
 *
 * <p>If the installer cannot be opened, that is still not a download failure (the file has already
 * landed and can still be installed via adb).
 *
 * <p>Shares the single download channel with Vosk / the action pack (see {@link DownloadGate});
 * only one download at a time. State machine: idle/downloading/done/error/cancelled.
 * The heavy work opens its own thread; public methods are thread-safe.
 */
public final class ApkDownloadController {
    private static final String TAG = "ApkDownloadController";

    /** Fixed download link (the only legal source; to switch versions, change here + the spec description first). */
    public static final String APK_URL =
            "https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/com.google.android.tts_24.9.361717975-210316629.apk";

    private final Context appContext;

    private volatile String apkState = "idle";
    private volatile int apkProgress = -1; // 0-100, -1 = unknown (length unknown)
    private volatile long apkBytes = 0;
    private volatile long apkTotal = -1;
    private volatile String apkError;
    private volatile boolean apkCancel = false;
    private Thread apkThread;

    public ApkDownloadController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** File name of the URL tail segment (fixed by the allowlist; still block ../ even without traversal defense). */
    static String apkFileName() {
        String u = APK_URL;
        int i = u.lastIndexOf('/');
        String name = (i >= 0 && i + 1 < u.length()) ? u.substring(i + 1) : "download.apk";
        if (name.indexOf("..") >= 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || !name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
            return "download.apk";
        }
        return name;
    }

    /** Download root = sdcard top level (same as Vosk / the action pack). */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** Start the download. return null = started, otherwise an immediate error string. */
    public synchronized String startDownload() {
        if ("downloading".equals(apkState)) {
            return "already downloading (" + apkProgress + "%)";
        }
        // Single channel: cannot start while the Vosk model / the action pack is downloading.
        if (!DownloadGate.tryAcquire("apk", apkFileName())) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        apkState = "downloading";
        apkProgress = 0;
        apkBytes = 0;
        apkTotal = -1;
        apkError = null;
        apkCancel = false;
        publishApk();
        apkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload();
            }
        }, "ApkDownload");
        apkThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(apkState)) {
            return "not downloading";
        }
        apkCancel = true;
        return null;
    }

    public void shutdown() {
        if ("downloading".equals(apkState)) apkCancel = true;
    }

    /** For the apk/status endpoint (pass-through JSON, not wrapped in an extra ApiResponse layer). */
    public String statusJson() {
        String st;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = apkState;
            prog = apkProgress;
            bytes = apkBytes;
            total = apkTotal;
            err = apkError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        sb.append(",\"url\":\"").append(escape(APK_URL)).append('"');
        sb.append(",\"file\":\"").append(escape(apkFileName())).append('"');
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishApk() {
        String st;
        int prog;
        String err;
        synchronized (this) {
            st = apkState;
            prog = apkProgress;
            err = apkError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("apk", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setApk(String st, int prog, String err) {
        synchronized (this) {
            apkState = st;
            apkProgress = prog;
            apkError = err;
        }
        publishApk();
    }

    private void runDownload() {
        File root = downloadRoot();
        File tmp = new File(root, apkFileName() + ".tmp");
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            downloadToTmp(root, tmp);
            synchronized (this) {
                if (apkCancel) throw new java.io.IOException("cancelled");
            }
            // As soon as it lands, open the system installer to wait for manual confirmation (if it cannot be opened, that is not a failure; the file has already landed).
            openSystemInstaller(new File(root, apkFileName()));
            setApk("done", 100, null);
            DownloadGate.release("apk");
            Log.i(TAG, "apk downloaded: /sdcard/" + apkFileName());
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = apkCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "apk download failed: " + msg, e);
            try {
                tmp.delete();
            } catch (Throwable ignore) {
            }
            DownloadGate.release("apk");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setApk("cancelled", apkProgress, "cancelled");
            } else {
                setApk("error", apkProgress, msg);
            }
        }
    }

    /** Download body (the same set as Vosk / the action pack: trust-all + timeouts + progress every 0.5s). */
    private void downloadToTmp(File root, File tmp) throws java.io.IOException {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            java.net.URL url = new java.net.URL(APK_URL);
            conn = (java.net.HttpURLConnection) url.openConnection();
            try {
                XiaozhiTrustAllSsl.applyTrustAll(conn);
            } catch (Throwable ignore) {
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "OpenAlpha2");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code);
            }
            // The int version of getContentLength (available since API 1; the apk is far smaller than 2GB,
            // getContentLengthLong needs API 24+, but this APK must run on API 21/22).
            long total = -1;
            try {
                total = conn.getContentLength();
            } catch (Throwable ignore) {
            }
            synchronized (this) {
                apkTotal = total;
            }
            in = conn.getInputStream();
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[32768];
            long got = 0;
            int n;
            long lastPub = 0;
            while ((n = in.read(buf)) != -1) {
                synchronized (this) {
                    if (apkCancel) throw new java.io.IOException("cancelled");
                }
                out.write(buf, 0, n);
                got += n;
                synchronized (this) {
                    apkBytes = got;
                    if (total > 0) apkProgress = (int) Math.min(100, got * 100 / total);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastPub > 500) {
                    lastPub = now;
                    publishApk();
                }
            }
            try {
                out.flush();
            } catch (Throwable ignore) {
            }
            try {
                out.close();
                out = null;
            } catch (Throwable ignore) {
                out = null;
            }
            if (tmp.length() <= 0) throw new java.io.IOException("empty download");
            File done = new File(root, apkFileName());
            try {
                done.delete();
            } catch (Throwable ignore) {
            }
            if (!tmp.renameTo(done)) {
                throw new java.io.IOException("cannot move apk into place");
            }
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignore) {
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** After landing, open the system installer to wait for manual confirmation (pressed with the scrcpy mouse; this device has no touchscreen).
     *  Even if it cannot be opened, that is not a download failure (the file has already landed and can still be installed via adb). Never throws. */
    private void openSystemInstaller(File apk) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(android.net.Uri.fromFile(apk),
                    "application/vnd.android.package-archive");
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            Log.i(TAG, "system installer opened for " + apk.getName());
        } catch (Throwable e) {
            Log.w(TAG, "open system installer failed (install via adb instead)", e);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
