package com.open.alpha2;

/**
 * JSON string escape + lightweight assembly helper. Replaces the hand-written
 * escapes that used to be scattered across the code.
 *
 * Previously {@link MainActivity#jsonSafe} and {@link HttpServer.ApiResponse}
 * {@code esc()} each held a separate copy of the same escape (the comment
 * stated "no direct reference to avoid a cycle").
 * Now one implementation lives here and both sides delegate to it. Behaviour
 * unchanged.
 *
 * No dependencies, API 22 compatible (no String.join / Map.getOrDefault).
 */
public final class JsonUtil {
    private JsonUtil() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Escape JSON string content (no outer quotes). null returns "". */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        sb.append(HEX[(c >>> 12) & 0xF]);
                        sb.append(HEX[(c >>> 8) & 0xF]);
                        sb.append(HEX[(c >>> 4) & 0xF]);
                        sb.append(HEX[c & 0xF]);
                    } else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /** Add outer quotes: {@code " + esc(s) + "}. */
    public static String quote(String s) {
        return "\"" + esc(s) + "\"";
    }

    /** Nullable string value: null returns {@code null}, otherwise a quoted value. */
    public static String quoteOrNull(String s) {
        return s != null ? quote(s) : "null";
    }

    /** Shortcuts for {@code {"ok":true}} / {@code {"ok":false,"error":"..."}}. */
    public static String okTrue() {
        return "{\"ok\":true}";
    }

    public static String okFalse(String error) {
        return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(error)) + "\"}";
    }

    /** Truncate an over-long string (shared by log / NetLog): add "..." past the limit. */
    public static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}

