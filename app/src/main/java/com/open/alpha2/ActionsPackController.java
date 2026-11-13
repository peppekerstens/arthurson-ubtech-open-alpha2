package com.open.alpha2;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * Action pack download + replace (used by the action pack card of the experiments tab).
 *
 * <p>Fixed single URL (allowlist; no arbitrary URL, SSRF prevention):
 * {@code https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/actions.zip}.
 * Flow: the backend drops the zip directly into the sdcard root -> rename the old {@code /sdcard/actions}
 * to {@code /sdcard/actions-backup} (the old backup is first deleted in whole) -> unzip into sdcard
 * (extract into staging first, then move; compatible with zips with or without a top-level {@code actions/}) ->
 * verify {@code actions/actionInfo.txt} -> clear the {@link ActionDirect} cache.
 *
 * <p>The state machine matches the {@link VoskController} download set (idle/downloading/unzipping/
 * done/error/cancelled); the frontend polls {@code action/pack/status} (or listens for the
 * {@code actions_pack} event) to show progress. The heavy work opens its own thread; public methods
 * are thread-safe.
 */
public final class ActionsPackController {
    private static final String TAG = "ActionsPackController";

    /** Fixed download link (the only legal source; to switch releases, change here + the spec description first). */
    public static final String PACK_URL =
            "https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/actions.zip";

    private static final String ACTIONS_DIR_NAME = "actions";
    private static final String BACKUP_DIR_NAME = "actions-backup";
    private static final String ZIP_TMP_NAME = "actions.zip.tmp";
    private static final String ZIP_NAME = "actions.zip";
    private static final String STAGING_DIR_NAME = "actions-tmp";

    private final Context appContext;
    private final ActionDirect actionDirect;

    // Download state machine (independent of the VoskController DL set: other functions keep running during a download).
    private volatile String packState = "idle";
    private volatile int packProgress = -1; // 0-100, -1 = unknown (unzipping / length unknown)
    private volatile long packBytes = 0;
    private volatile long packTotal = -1;
    private volatile String packError;
    private volatile boolean packCancel = false;
    private Thread packThread;

    public ActionsPackController(Context context, ActionDirect actionDirect) {
        this.appContext = context.getApplicationContext();
        this.actionDirect = actionDirect;
    }

    /** Download root = sdcard top level (same as VoskController.downloadRoot). */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                // It exists but writing is not certain; try anyway (older devices misreport canWrite); if it cannot be written, it errors later.
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** Start the download + replace. return null = started, otherwise an immediate error string. */
    public synchronized String startDownload() {
        if ("downloading".equals(packState) || "unzipping".equals(packState)) {
            return "already downloading (" + packProgress + "%)";
        }
        // Single channel: cannot start while the Vosk model is downloading (see DownloadGate, experiments tab resource download card).
        if (!DownloadGate.tryAcquire("actions", "actions.zip")) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        packState = "downloading";
        packProgress = 0;
        packBytes = 0;
        packTotal = -1;
        packError = null;
        packCancel = false;
        publishPack();
        packThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload();
            }
        }, "ActionsPackDownload");
        packThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(packState) && !"unzipping".equals(packState)) {
            return "not downloading";
        }
        packCancel = true;
        return null;
    }

    public void shutdown() {
        if ("downloading".equals(packState) || "unzipping".equals(packState)) packCancel = true;
    }

    /** For the action/pack/status endpoint (pass-through JSON, not wrapped in an extra ApiResponse layer). */
    public String statusJson() {
        String st;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = packState;
            prog = packProgress;
            bytes = packBytes;
            total = packTotal;
            err = packError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        sb.append(",\"url\":\"").append(escape(PACK_URL)).append('"');
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishPack() {
        String st;
        int prog;
        String err;
        synchronized (this) {
            st = packState;
            prog = packProgress;
            err = packError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("actions_pack", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setPack(String st, int prog, String err) {
        synchronized (this) {
            packState = st;
            packProgress = prog;
            packError = err;
        }
        publishPack();
    }

    private void runDownload() {
        File root = downloadRoot();
        File zipTmp = new File(root, ZIP_TMP_NAME);
        File zipDone = new File(root, ZIP_NAME);
        File staging = new File(root, STAGING_DIR_NAME);
        File actionsDir = new File(root, ACTIONS_DIR_NAME);
        File backupDir = new File(root, BACKUP_DIR_NAME);
        // Whether the old actions have already been moved to backup (used for the failure rollback).
        boolean renamedToBackup = false;
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            // --- download ---
            java.net.HttpURLConnection conn = null;
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                java.net.URL url = new java.net.URL(PACK_URL);
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
                // Use the int version of getContentLength (available since API 1): actions.zip is far smaller than 2GB;
                // getContentLengthLong needs API 24+, but this APK must run on API 21/22;
                // a direct call throws NoSuchMethodError (same class as the vosk circuit breaker that keeps API 19).
                long total = -1;
                try {
                    total = conn.getContentLength();
                } catch (Throwable ignore) {
                }
                synchronized (this) {
                    packTotal = total;
                }
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(zipTmp);
                byte[] buf = new byte[32768];
                long got = 0;
                int n;
                long lastPub = 0;
                while ((n = in.read(buf)) != -1) {
                    synchronized (this) {
                        if (packCancel) throw new java.io.IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (this) {
                        packBytes = got;
                        if (total > 0) packProgress = (int) Math.min(100, got * 100 / total);
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPub > 500) {
                        lastPub = now;
                        publishPack();
                    }
                }
                try {
                    out.flush();
                } catch (Throwable ignore) {
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
            synchronized (this) {
                if (packCancel) throw new java.io.IOException("cancelled");
            }
            // --- unzip (extract into staging first, then move; compatible with both zip layouts) ---
            setPack("unzipping", 100, null);
            try {
                zipTmp.renameTo(zipDone);
            } catch (Throwable ignore) {
            }
            File src = zipDone.exists() ? zipDone : zipTmp;
            // Clear and rebuild staging.
            deleteRecursive(staging);
            try {
                staging.mkdirs();
            } catch (Throwable ignore) {
            }
            unzipToDir(src, staging);
            // Verify staging: use the top-level actions/ if present; otherwise staging itself must have actionInfo.txt.
            File nested = new File(staging, ACTIONS_DIR_NAME);
            File srcDir;
            if (nested.isDirectory()
                    && new File(nested, "actionInfo.txt").isFile()) {
                srcDir = nested;
            } else if (new File(staging, "actionInfo.txt").isFile()) {
                srcDir = staging;
            } else {
                throw new java.io.IOException("unzip ok but actionInfo.txt not found"
                        + " (need actions/actionInfo.txt in zip)");
            }
            // Rename the old actions to backup (the old backup is first deleted in whole; a rename failure dies here, no unzip overwrite).
            if (actionsDir.exists()) {
                deleteRecursive(backupDir);
                boolean renamed;
                try {
                    renamed = actionsDir.renameTo(backupDir);
                } catch (Throwable e) {
                    renamed = false;
                }
                if (!renamed) {
                    throw new java.io.IOException("cannot backup old actions"
                            + " (rename actions -> actions-backup failed)");
                }
                renamedToBackup = true;
            } else {
                // The old actions were not present; the leftover backup stays, not deleted (unrelated to this run).
                renamedToBackup = false;
            }
            // Move staging to actions.
            boolean moved;
            try {
                if (srcDir == staging) {
                    moved = staging.renameTo(actionsDir);
                    if (!moved) {
                        // A cross-volume rename failure moves file by file (older device sdcard occasionally has this issue).
                        moved = moveDirContents(staging, actionsDir);
                        deleteRecursive(staging);
                    }
                } else {
                    moved = nested.renameTo(actionsDir);
                    if (!moved) {
                        moved = moveDirContents(nested, actionsDir);
                    }
                    deleteRecursive(staging);
                }
            } catch (Throwable e) {
                moved = false;
            }
            if (!moved || !new File(actionsDir, "actionInfo.txt").isFile()) {
                throw new java.io.IOException("cannot install new actions"
                        + " (move staging -> actions failed)");
            }
            try {
                src.delete();
            } catch (Throwable ignore) {
            }
            try {
                if (zipTmp.exists()) zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // The new pack is in effect: clear the ActionDirect cache (the next action/list reads the new actionInfo.txt).
            try {
                if (actionDirect != null) actionDirect.invalidateCache();
            } catch (Throwable e) {
                Log.w(TAG, "invalidateCache failed", e);
            }
            setPack("done", 100, null);
            DownloadGate.release("actions");
            Log.i(TAG, "actions pack downloaded+installed");
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = packCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "actions pack download failed: " + msg, e);
            try {
                zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // Failure rollback: the old actions were moved away but the new pack is corrupt or cannot be moved,
            // so move the backup back, otherwise the action tab has nothing to play (if the backup itself
            // cannot be restored, report the error as-is).
            if (renamedToBackup) {
                try {
                    if (!actionsDir.exists()
                            || !new File(actionsDir, "actionInfo.txt").isFile()) {
                        deleteRecursive(actionsDir);
                        if (!backupDir.renameTo(actionsDir)) {
                            msg = msg + " (rollback failed: cannot restore actions-backup)";
                        } else {
                            msg = msg + " (rolled back to old actions)";
                            renamedToBackup = false;
                        }
                    }
                } catch (Throwable re) {
                    msg = msg + " (rollback failed)";
                }
            }
            try {
                deleteRecursive(staging);
            } catch (Throwable ignore) {
            }
            DownloadGate.release("actions");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setPack("cancelled", packProgress, "cancelled");
            } else {
                setPack("error", packProgress, msg);
            }
        }
    }

    /** Only when staging cannot be moved (a cross-volume rename failure), move file by file: create directories + rename/copy. */
    private static boolean moveDirContents(File from, File to) {
        try {
            if (!to.exists() && !to.mkdirs()) return false;
            File[] fs;
            try {
                fs = from.listFiles();
            } catch (Throwable t) {
                return false;
            }
            if (fs == null) return false;
            for (File c : fs) {
                File dest = new File(to, c.getName());
                if (c.isDirectory()) {
                    if (!moveDirContents(c, dest)) return false;
                } else {
                    boolean ok = false;
                    try {
                        ok = c.renameTo(dest);
                    } catch (Throwable ignore) {
                        ok = false;
                    }
                    if (!ok) {
                        // A failed rename does copy + delete (within the same volume this should not be reached).
                        java.io.InputStream in = null;
                        java.io.OutputStream out = null;
                        try {
                            in = new java.io.BufferedInputStream(new java.io.FileInputStream(c));
                            out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dest));
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            out.flush();
                            ok = true;
                        } catch (Throwable ignore) {
                            ok = false;
                        } finally {
                            try {
                                if (in != null) in.close();
                            } catch (Throwable ignore) {
                            }
                            try {
                                if (out != null) out.close();
                            } catch (Throwable ignore) {
                            }
                        }
                        if (ok) {
                            try {
                                c.delete();
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                    if (!ok) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Throwable t) {
            fs = null;
        }
        if (fs != null) {
            for (File c : fs) {
                try {
                    if (c.isDirectory()) deleteRecursive(c);
                    else c.delete();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    /** Zip-slip safe: an entry must extract inside dir, otherwise skip it. */
    private void unzipToDir(File zip, File dir) throws java.io.IOException {
        String dirCanon = dir.getCanonicalPath();
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zis.getNextEntry()) != null) {
                synchronized (this) {
                    if (packCancel) throw new java.io.IOException("cancelled");
                }
                String name = e.getName();
                // Block absolute paths / .. escapes (do not trust an outside zip).
                File f = new File(dir, name);
                String canon = f.getCanonicalPath();
                if (!canon.equals(dirCanon) && !canon.startsWith(dirCanon + File.separator)) {
                    try {
                        zis.closeEntry();
                    } catch (Throwable ignore) {
                    }
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.OutputStream o = null;
                    try {
                        o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            synchronized (this) {
                                if (packCancel) throw new java.io.IOException("cancelled");
                            }
                            o.write(buf, 0, n);
                        }
                        o.flush();
                    } finally {
                        try {
                            if (o != null) o.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
                try {
                    zis.closeEntry();
                } catch (Throwable ignore) {
                }
            }
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
