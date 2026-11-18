package android.util;

/** Test stub of android.util.Log for the host unit tests (scripts/test-apivalidator.py).
 *  The android.jar stub throws on each call, so the tests use this class. Not in the APK. */
public final class Log {
    private Log() {}

    /** The warnings of the test run, for a test that looks for a refusal log. */
    public static final java.util.List<String> LINES = new java.util.concurrent.CopyOnWriteArrayList<String>();

    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable t) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { LINES.add(tag + ": " + msg); return 0; }
    public static int w(String tag, String msg, Throwable t) { LINES.add(tag + ": " + msg); return 0; }
    public static int w(String tag, Throwable t) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}
