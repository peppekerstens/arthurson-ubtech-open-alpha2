package com.ubtechinc.alpha.hardware;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct serial port with no dependency on alpha2services (the only chest/head
 * control path under pure-direct).
 *
 * <p>Open order (try the primary/alt/alt2 devices in sequence):
 * 1) JNI path: {@code com.ubtechinc.alpha.jni.SerialPortFile} (libserial_port.so,
 *    the native side sets termios to 115200 8N1 raw). This class and the .so ship
 *    in the same module, so Class.forName normally finds it. Only when it does
 *    not does the open fall to 2).
 * 2) Plain File path: open with FileInputStream/FileOutputStream directly, then
 *    busybox stty changes the baud rate to 115200 (the system default is 9600.
 *    Without the change the MCU receives only garbage — one of the root causes,
 *    measured 2026-09, of servo TX going out while the servos stay still).</p>
 *
 * <p>Local ttyS1/ttyS3 are 777, so a normal app can open them. On failure
 * isAvailable()=false, the caller reports the error directly, and there is no
 * binder fallback of any kind.</p>
 */
public final class DirectSerialPort {
    private static final String TAG = "DirectSerialPort";

    public interface OnFrameListener {
        void onFrame(byte[] frame);
    }

    private final String primaryPath;
    private final String altPath;
    private final String altPath2;
    private final int baudrate;

    private volatile InputStream input;
    private volatile OutputStream output;
    private FileDescriptor fd;
    private Object serialPortFileObj; // held for the JNI path, used by close()
    private volatile String openedPath;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArraySet<OnFrameListener> listeners = new CopyOnWriteArraySet<>();
    // Cross-instance exclusivity: chest and head ALT2 both point to /dev/ttyS0. If both sides open at once, frames split and fight.
    // Record the opened path. The other side sees it and skips to the next candidate.
    private static final java.util.Set<String> sOpenedPaths =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Has another port already taken this path (the exclusivity check)? */
    private static boolean claimPath(String path) {
        synchronized (sOpenedPaths) {
            if (sOpenedPaths.contains(path)) return false;
            sOpenedPaths.add(path);
            return true;
        }
    }

    private static void releasePath(String path) {
        if (path == null) return;
        synchronized (sOpenedPaths) { sOpenedPaths.remove(path); }
    }

    public DirectSerialPort(String primaryPath, String altPath, int baudrate) {
        this(primaryPath, altPath, null, baudrate);
    }

    public DirectSerialPort(String primaryPath, String altPath, String altPath2, int baudrate) {
        this.primaryPath = primaryPath;
        this.altPath = altPath;
        this.altPath2 = altPath2;
        this.baudrate = baudrate;
    }

    public static DirectSerialPort forChest() {
        return new DirectSerialPort(DirectSerialConfig.CHEST_TTY, DirectSerialConfig.CHEST_TTY_ALT, DirectSerialConfig.CHEST_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    public static DirectSerialPort forHead() {
        return new DirectSerialPort(DirectSerialConfig.HEAD_TTY, DirectSerialConfig.HEAD_TTY_ALT, DirectSerialConfig.HEAD_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    /** Host unit tests only (DirectChestControllerTest, alpha2-brain#30): a port that writes
     *  to {@code out}, reads nothing and starts no reader thread. The app never calls it. */
    static DirectSerialPort forTest(OutputStream out) {
        DirectSerialPort p = new DirectSerialPort("test", null, 0);
        p.input = new java.io.ByteArrayInputStream(new byte[0]);
        p.output = out;
        p.openedPath = "test";
        p.running.set(true);
        return p;
    }

    public void addListener(OnFrameListener l) { listeners.add(l); }
    public void removeListener(OnFrameListener l) { listeners.remove(l); }
    public String getOpenedPath() { return openedPath; }

    public synchronized boolean open() {
        if (running.get()) return true;
        // 1) JNI path (termios sets the baud itself)
        if (tryOpenViaJni(primaryPath) || tryOpenViaJni(altPath) || (altPath2 != null && tryOpenViaJni(altPath2))) {
            startReader();
            return true;
        }
        // 2) Plain File path + stty sets the baud
        if (tryOpenViaFile(primaryPath) || tryOpenViaFile(altPath) || (altPath2 != null && tryOpenViaFile(altPath2))) {
            startReader();
            return true;
        }
        Log.w(TAG, "open failed for all " + primaryPath + "," + altPath + "," + altPath2 + " (pure-direct, no fallback)");
        return false;
    }

    private boolean tryOpenViaJni(String path) {
        if (path == null) return false;
        File dev = new File(path);
        if (!dev.exists()) {
            Log.d(TAG, "device not found: " + path);
            return false;
        }
        try {
            Class<?> cls = Class.forName("com.ubtechinc.alpha.jni.SerialPortFile");
            Object obj = cls.getConstructor(File.class, int.class, int.class)
                    .newInstance(dev, baudrate, 0);
            InputStream in;
            OutputStream out;
            FileDescriptor jfd = null;
            try {
                in = (InputStream) cls.getMethod("getInputStream").invoke(obj);
                out = (OutputStream) cls.getMethod("getOutputStream").invoke(obj);
            } catch (NoSuchMethodException nsme) {
                Log.d(TAG, "SerialPortFile has no stream getters: " + nsme.getMessage());
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            if (in == null || out == null) {
                Log.d(TAG, "JNI open returned null stream for " + path);
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            try {
                try {
                    jfd = (FileDescriptor) cls.getMethod("getFD").invoke(obj);
                } catch (NoSuchMethodException nsme2) {
                    java.lang.reflect.Field f = cls.getDeclaredField("mFd");
                    f.setAccessible(true);
                    jfd = (FileDescriptor) f.get(obj);
                }
            } catch (Exception ignore) { /* the fd is optional. Store it if present, fine without it */ }
            if (!claimPath(path)) {
                Log.w(TAG, path + " already held by another port, skipping (exclusive, prevents split frames)");
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            this.input = in;
            this.output = out;
            this.fd = jfd;
            this.serialPortFileObj = obj;
            this.openedPath = path;
            Log.i(TAG, "opened via JNI SerialPortFile: " + path + " baud=" + baudrate);
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "SerialPortFile class not found, fallback to File: " + e.getMessage());
        } catch (Throwable t) {
            // UnsatisfiedLinkError/NoSuchMethodError (Error, not Exception). If not caught,
            // it punches through and kills the calling thread. Treat it as "cannot open" and try the next path.
            Log.d(TAG, "JNI open failed for " + path + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        return false;
    }

    private boolean tryOpenViaFile(String path) {
        if (path == null) return false;
        File dev = new File(path);
        if (!dev.exists()) {
            Log.d(TAG, "device not found: " + path);
            return false;
        }
        if (!claimPath(path)) {
            Log.w(TAG, path + " already held by another port, skipping (exclusive, prevents split frames)");
            return false;
        }
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(dev);
            FileOutputStream fout;
            try {
                fout = new FileOutputStream(dev);
            } catch (Exception e) {
                // The second step threw. Close the first fd back. That is not a leak.
                try { fin.close(); } catch (Exception ignore) {}
                fin = null;
                throw e;
            }
            this.input = fin;
            this.output = fout;
            try { this.fd = fin.getFD(); } catch (Exception ignore) {}
            this.serialPortFileObj = null;
            this.openedPath = path;
            // Plain Java cannot set termios. stty must change the baud rate from the default 9600 to 115200,
            // otherwise the MCU side sees only garbage (a measured 2026-09 lesson). busybox ships stty.
            configureBaudStty(path, baudrate);
            Log.i(TAG, "opened via File: " + path);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "File open failed for " + path + " (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            releasePath(path);
            closeQuietly();
            return false;
        }
    }

    /** Set baud rate + 8N1 raw with busybox stty (File path only. The JNI path has it set in native). */
    private static void configureBaudStty(String path, int baudrate) {
        String[] cmds = {
                "busybox stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
                "stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
        };
        for (String cmd : cmds) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + "; stty -F " + path + " 2>&1 | head -1"});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                try {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append(' ');
                    // Drain stderr after reading stdout, or the child process fills the pipe and waitFor hangs.
                    java.io.InputStream err = p.getErrorStream();
                    try {
                        byte[] drain = new byte[256];
                        while (err.available() > 0 && err.read(drain) > 0) { /* discard */ }
                    } catch (Exception ignore) {}
                    p.waitFor();
                    Log.i(TAG, "stty " + path + " [" + cmd.split(" ")[0] + "] -> " + sb.toString().trim());
                    if (sb.toString().contains(String.valueOf(baudrate))) return; // the baud is set. No need to try the next command
                } finally {
                    try { r.close(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.d(TAG, "stty attempt failed (" + cmd.split(" ")[0] + "): " + e.getMessage());
            } finally {
                if (p != null) p.destroy();
            }
        }
    }

    public boolean isAvailable() { return running.get() && input != null && output != null; }

    public synchronized boolean send(byte cmd, byte[] param) {
        OutputStream out = output;
        if (!isAvailable() || out == null) return false;
        try {
            byte[] frame = SerialFrameCodec.encode(cmd, param);
            out.write(frame);
            out.flush();
            Log.d(TAG, "TX(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // encode() parameter out of range (plen>248) and the like: do not send a bad frame to the MCU. Fail directly.
            Log.w(TAG, "send rejected (" + e.getMessage() + ")");
            return false;
        }
    }

    /** Send the upgrade frame directly (same ID/INDEX header as the official upgrade APK, see SerialFrameCodec.encodeUpgrade.
     *  LAP traffic such as servo/LED goes over send() above. Do not mix the two). */
    public synchronized boolean sendUpgrade(byte id, byte index, byte cmd, byte[] param) {
        OutputStream out = output;
        if (!isAvailable() || out == null) return false;
        try {
            byte[] frame = SerialFrameCodec.encodeUpgrade(id, index, cmd, param);
            out.write(frame);
            out.flush();
            Log.d(TAG, "TX-UPG(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "sendUpgrade failed: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "sendUpgrade rejected (" + e.getMessage() + ")");
            return false;
        }
    }

    /** Pass through a raw frame (bypasses encode), used for hand-written long frames such as set_uuid / version fallback */
    public synchronized boolean sendRaw(byte[] rawFrame) {
        OutputStream out = output;
        if (!isAvailable() || out == null || rawFrame == null) return false;
        try {
            out.write(rawFrame);
            out.flush();
            Log.d(TAG, "TX-RAW(" + openedPath + ") " + SerialFrameCodec.toHex(rawFrame));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sendRaw failed: " + e.getMessage());
            return false;
        }
    }

    private void startReader() {
        running.set(true);
        readerThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[4096];
                int pos = 0;
                while (running.get()) {
                    try {
                        InputStream in = input;
                        if (in == null) { Thread.sleep(50); continue; }
                        int n = in.read(buf, pos, buf.length - pos);
                        if (n <= 0) { Thread.sleep(10); continue; }
                        int avail = pos + n;
                        int off = 0;
                        while (off < avail) {
                            SerialFrameCodec.DecodeResult r = SerialFrameCodec.tryDecode(buf, off, avail - off);
                            if (r == null) break; // not enough for one frame
                            if (r.frame != null) {
                                for (OnFrameListener l : listeners) {
                                    try { l.onFrame(r.frame); } catch (Exception ignore) {}
                                }
                                logRxFrame(r.frame);
                            }
                            off += r.consumed;
                        }
                        // Move the unconsumed remainder to the front
                        if (off > 0 && off < avail) {
                            System.arraycopy(buf, off, buf, 0, avail - off);
                            pos = avail - off;
                        } else if (off >= avail) {
                            pos = 0;
                        } else {
                            pos = avail;
                            if (pos >= buf.length - 256) {
                                Log.w(TAG, "reader dropping " + pos + "B garbage (no F8 8F head)");
                                pos = 0; // prevent overflow, drop it
                            }
                        }
                    } catch (Exception e) {
                        if (running.get()) Log.w(TAG, "reader error: " + e.getMessage());
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        }, "DirectSerial-" + new File(openedPath != null ? openedPath : primaryPath).getName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private int heartbeatSuppressed = 0;
    private long heartbeatLastLogMs = 0;

    /** RX frame log: printing the heartbeats (0x8B/0x8D) frame by frame would wash out logcat (measured 0x8D flood), so here they are simply
     *  not printed, while dispatch still runs (whether they go up the WebSocket is decided by MainActivity.onDirectChestFrame,
     *  which filters the same two). Report a count once per 60 seconds, so a person knows the serial port is still alive. */
    private void logRxFrame(byte[] frame) {
        if (isHeartbeat(frame)) {
            heartbeatSuppressed++;
            long now = System.currentTimeMillis();
            if (now - heartbeatLastLogMs > 60000) {
                heartbeatLastLogMs = now;
                Log.d(TAG, "RX(" + openedPath + ") heartbeat 0x8B/0x8D suppressed x" + heartbeatSuppressed);
            }
            return;
        }
        Log.d(TAG, "RX(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
    }

    /** Long form F8 8F LEN SRC DST CMD…: SRC 05/00, DST 00, CMD 8B/8D means a heartbeat. */
    private static boolean isHeartbeat(byte[] frame) {
        if (frame == null || frame.length < 8) return false;
        if ((frame[0] & 0xFF) != 0xF8 || (frame[1] & 0xFF) != 0x8F) return false;
        int src = frame[3] & 0xFF;
        if (frame[4] != 0 || (src != 0x05 && src != 0x00)) return false;
        int cmd = frame[5] & 0xFF;
        return cmd == 0x8B || cmd == 0x8D;
    }

    public synchronized void close() {
        running.set(false);
        // Close the streams first to release the blocking read (FileInputStream.read does not take an interrupt.
        // Without the close, join times out for sure and leaves an orphan thread). Then join.
        closeQuietly();
        if (readerThread != null) { try { readerThread.interrupt(); readerThread.join(500); } catch (InterruptedException ignore) {} readerThread = null; }
    }

    private void closeQuietly() {
        // JNI path: input/output are views over the same native fd that SerialPortFile wraps.
        // Closing each of them plus a native close = double-close (a reused fd number would close someone else's file by mistake).
        // This path runs the native close once only. File path: fin/fout are two independent fds, both must close.
        if (serialPortFileObj != null) {
            try { serialPortFileObj.getClass().getMethod("close").invoke(serialPortFileObj); } catch (Exception ignore) {}
        } else {
            try { if (input != null) input.close(); } catch (IOException ignore) {}
            try { if (output != null) output.close(); } catch (IOException ignore) {}
        }
        releasePath(openedPath);
        input = null; output = null; fd = null; serialPortFileObj = null; openedPath = null;
    }
}
