package com.ubtechinc.alpha.jni;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JNI bridge to libserial_port.so (used by pure-direct).
 *
 * <p>The package name + class name + method signatures must match the
 * symbols exported by the .so exactly:
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_open} /
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_close} (verified with
 * NDK readelf). Decompilation confirms that native open(path, baudrate,
 * flags) uses termios inside to set 115200. This is exactly the step the
 * pure File path misses (measured: ttyS1 defaults to 9600, and without a
 * baud setting the MCU rejects everything sent).</p>
 *
 * <p>Usage matches the classic android-serialport-api. DirectSerialPort
 * calls this class by reflection, so hardware-direct carries no
 * compile-time dependency on it.</p>
 */
public class SerialPortFile implements java.io.Closeable {
    private static final String TAG = "SerialPortFile";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("serial_port");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary serial_port failed: " + t.getMessage());
        }
    }

    /** Whether the .so is loaded. The caller can pre-check before opening the port (same check as LedControl/HeadKeyMgr). */
    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    public SerialPortFile(File device, int baudrate, int flags) throws SecurityException, IOException {
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) {
            Log.e(TAG, "native open returned null fd for " + device);
            throw new IOException("native open failed for " + device);
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    /** For DirectSerialPort to take the fd via reflection (File wrapper). */
    public FileDescriptor getFD() {
        return mFd;
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    private static native FileDescriptor open(String path, int baudrate, int flags);

    /**
     * Closes the native fd (also satisfies {@link java.io.Closeable} — a
     * throws clause narrower than the interface is allowed).
     * Not idempotent: closing the same fd twice risks closing a reused fd
     * number. Ownership belongs to DirectSerialPort (the JNI path calls
     * here once only, see DirectSerialPort.closeQuietly). Do not call from
     * outside.
     */
    public native void close();
}
