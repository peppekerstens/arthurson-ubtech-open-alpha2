package com.open.alpha2;

/**
 * JNI bridge to libalpha2mic.so (app/src/main/cpp/alpha2mic/alpha2mic.c): raw ALSA
 * capture of the 5-mic FPGA card. All methods return a negative errno on failure.
 */
final class MultiMicNative {
    private static final Object LOCK = new Object();
    private static Boolean loaded;
    private static String loadError;

    private MultiMicNative() {}

    /** Loads the library one time. Returns null on success, else the error text. */
    static String ensureLoaded() {
        synchronized (LOCK) {
            if (loaded == null) {
                try {
                    System.loadLibrary("alpha2mic");
                    loaded = Boolean.TRUE;
                } catch (Throwable t) {
                    loaded = Boolean.FALSE;
                    loadError = "libalpha2mic.so: " + t.getMessage();
                }
            }
            return loaded ? null : loadError;
        }
    }

    static native int open(String dev, int channels, int rate, int periodFrames, int periods);

    /** Reads up to {@code frames} frames of {@code channels} channels into buf. Waits at most
     *  {@code timeoutMs} for data: then it returns -110 (ETIMEDOUT). -32 (EPIPE) after an
     *  overrun. -22 (EINVAL) when buf is too small (alpha2-brain#27, #28). */
    static native int read(int fd, short[] buf, int frames, int channels, int timeoutMs);

    static native void close(int fd);
}
