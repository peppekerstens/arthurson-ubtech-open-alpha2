package com.open.alpha2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared stream-reading implementation.
 *
 * {@link MainActivity#readFully}, {@code XiaozhiOtaClient.readFully} and the
 * static file-read loop of {@link HttpServer} each copied the same 4k/8k chunk
 * loop. This class holds that code in one place. Behavior is unchanged.
 */
public final class IOUtil {
    private IOUtil() {}

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    public static String readFully(InputStream in) throws IOException {
        return new String(readAllBytes(in), StandardCharsets.UTF_8);
    }

    /** Bounded version: throws IOException past the limit, so a lying client Content-Length cannot cause an OOM. */
    public static byte[] readAllBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) throw new IOException("body too large (limit " + maxBytes + ")");
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** Read exactly len bytes (a short stream returns what was read, no exception). */
    public static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int readTotal = 0;
        while (readTotal < len) {
            int n = in.read(out, readTotal, len - readTotal);
            if (n < 0) break;
            readTotal += n;
        }
        if (readTotal < len) return java.util.Arrays.copyOf(out, readTotal);
        return out;
    }
}

