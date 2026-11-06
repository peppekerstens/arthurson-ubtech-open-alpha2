package com.ubtechinc.alpha.hardware;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChestSafety vector test (zero dependencies, no JUnit).
 *
 * <p>The project builds offline, so the test does not run through a gradle
 * test task. The project has no JUnit in the offline cache. This file is
 * pure Java plus a {@code main()} runner. It compiles and runs through
 * {@code scripts/test-chestsafety.py} with {@code javac}/{@code java}
 * (ChestSafety is pure java.*, so no android.jar is needed).
 *
 * <p>Vector source: the shared file
 * {@code $HOME/github/alpha2-python/tests/chest_guard_vectors.json}
 * (alpha2-python#27), the single source of truth for the chest Sit down
 * guard of alpha2-python, alpha2-bridge and open-alpha2. The first command
 * line argument can override the path.
 *
 * <p>The guard has no reason codes in this build, so the test compares
 * {@code expect} only: {@code refuse} means a reason is returned,
 * {@code allow} means null.
 *
 * <p>Run: {@code python3 scripts/test-chestsafety.py}
 */
public final class ChestSafetyVectorTest {
    private static int passed = 0;
    private static int failed = 0;

    private ChestSafetyVectorTest() {}

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static byte[] hex(String s) {
        int n = s.length();
        if (n % 2 != 0) throw new Error("hex length " + n + " is odd");
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((val(s.charAt(i)) << 4) | val(s.charAt(i + 1)));
        }
        return out;
    }

    private static int val(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new Error("bad hex char " + c);
    }

    private static Path vectorPath(String[] args) {
        if (args.length >= 1) return Path.of(args[0]);
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        return Path.of(home, "github", "alpha2-python", "tests", "chest_guard_vectors.json");
    }

    public static void main(String[] args) {
        Path path = vectorPath(args);
        String text;
        try {
            text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("SKIP: chest guard vectors not found at " + path);
            return;
        }

        Map<String, Object> root;
        try {
            root = (Map<String, Object>) new JsonParser(text).parse();
        } catch (Exception e) {
            System.err.println("the vector file is not valid JSON for its fixed shape: " + e);
            System.exit(1);
            return;
        }

        List<Object> cases;
        Object c = root.get("cases");
        if (!(c instanceof List)) {
            System.err.println("the vector file has no cases array");
            System.exit(1);
            return;
        }
        cases = (List<Object>) c;

        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> caseObj = (Map<String, Object>) cases.get(i);
            String name = (String) caseObj.getOrDefault("name", "<no name>");
            String hexStr = (String) caseObj.getOrDefault("hex", "");
            boolean allowSitDown = Boolean.TRUE.equals(caseObj.get("allow_sit_down"));
            String expect = (String) caseObj.get("expect");

            String actual;
            try {
                String reason = ChestSafety.checkChestFrames(hex(hexStr), allowSitDown);
                actual = reason == null ? "allow" : "refuse";
            } catch (Exception e) {
                actual = "ERROR (" + e + ")";
            }
            System.out.println(name + ": expect=" + expect + " actual=" + actual);
            eq(expect, actual, name + " (case " + i + ")");
        }

        System.out.println("ChestSafetyVectorTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * A small hand-rolled JSON reader for the fixed vector file shape:
     * an object with string, bool and null values, and one array of flat
     * objects. Values come back as String, Boolean, null, List or Map.
     */
    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            skipWs();
            if (pos != s.length()) throw new Error("trailing data at " + pos);
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private char peek() {
            if (pos >= s.length()) throw new Error("unexpected end of input");
            return s.charAt(pos);
        }

        private void expect(char c) {
            char p = peek();
            if (p != c) throw new Error("expected '" + c + "' at " + pos);
            pos++;
        }

        private Object value() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    throw new Error("bad value at " + pos);
            }
        }

        private void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw new Error("bad literal at " + pos);
            pos += lit.length();
        }

        private Object object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                Object v = value();
                m.put(key, v);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw new Error("expected , or } at " + pos);
                }
            }
        }

        private Object array() {
            expect('[');
            List<Object> items = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                items.add(value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return items;
                } else {
                    throw new Error("expected , or ] at " + pos);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new Error("bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new Error("bad escape at " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
