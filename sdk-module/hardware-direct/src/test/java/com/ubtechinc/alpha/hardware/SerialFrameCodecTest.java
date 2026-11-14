package com.ubtechinc.alpha.hardware;

import java.util.Arrays;

/**
 * SerialFrameCodec unit test (zero dependencies, no JUnit).
 *
 * Why no JUnit: same as ApiValidatorTest in app/src/test. This project
 * compiles offline (`--offline`), the gradle cache holds no junit, and
 * adding `testImplementation` would break every offline build. This file
 * is pure Java + a `main()` runner. `scripts/test-serialcodec.py` compiles
 * and runs it directly with `javac`/`java` (CI and local both work, and
 * android.jar is not needed — SerialFrameCodec uses only java.*).
 * `./gradlew assembleDebug` is not affected (not in the main sourceSet).
 *
 * Why test: SerialFrameCodec (strict SUM/ED check) and
 * stripSerialFrame() on the MainActivity side (no check, hard cut) form a
 * two-parser fork. Until they converge, this test pins the wire format
 * proven by 651 real frames — a wrong change on the serial side turns
 * red here. Note: this tests the codec itself only. A differential test
 * against stripSerialFrame needs the full app classpath to compile
 * MainActivity, so it waits until convergence.
 *
 * Vector source: all come from code comments or real frames captured in
 * device logcat, not invented on the spot.
 *
 * Run: `python scripts/test-serialcodec.py`
 */
public final class SerialFrameCodecTest {
    private static int passed = 0;
    private static int failed = 0;

    private SerialFrameCodecTest() {}

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        eq(Integer.valueOf(expected), Integer.valueOf(actual), name);
    }

    private static void eqBytes(byte[] expected, byte[] actual, String name) {
        boolean ok = Arrays.equals(expected, actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + SerialFrameCodec.toHex(expected)
                    + " actual=" + SerialFrameCodec.toHex(actual));
        }
    }

    private static void isNull(Object actual, String name) {
        eq(null, actual, name);
    }

    public static void main(String[] args) {
        // -- encode: official proven vectors (javadoc §真實線格式) --
        // Official single-servo frame: 12+5+0+5+3+0+123+0+100=248=0xF8 (counting SRC gives the right value)
        eqBytes(hex("f8 8f 0c 05 00 05 03 00 7b 00 64 f8 ed"),
                SerialFrameCodec.encode((byte) 0x05, hex("03 00 7b 00 64")),
                "encode single-servo official");
        // Read reply: 9+5+0+5+0+3=22=0x16
        eqBytes(hex("f8 8f 09 05 00 05 00 03 16 ed"),
                SerialFrameCodec.encode((byte) 0x05, hex("00 03")),
                "encode read-reply official");
        // chest/version query captured on real-device TX: f8 8f 07 05 00 33 3f ed
        // (7+5+0+0x33=63=0x3F)
        eqBytes(hex("f8 8f 07 05 00 33 3f ed"),
                SerialFrameCodec.encode((byte) 0x33, null),
                "encode version query null-param");
        // Empty param and null encode identically
        eqBytes(SerialFrameCodec.encode((byte) 0x33, null),
                SerialFrameCodec.encode((byte) 0x33, new byte[0]),
                "encode null==empty");
        // Mute LED cmd68[01]: 8+5+0+0x44+1=82=0x52
        eqBytes(hex("f8 8f 08 05 00 44 01 52 ed"),
                SerialFrameCodec.encode((byte) 0x44, hex("01")),
                "encode mute-led");

        // -- decode round-trip (encode output decodes back) --
        byte[] enc = SerialFrameCodec.encode((byte) 0x44, hex("01"));
        SerialFrameCodec.DecodeResult rt = SerialFrameCodec.tryDecode(enc, 0, enc.length);
        eqBytes(enc, rt.frame, "roundtrip frame");
        eq(enc.length, rt.consumed, "roundtrip consumed");

        // -- decode 00 00 event frame (mute key press, real frame from a MainActivity comment) --
        // 8+0+0+0x91+1=154=0x9A
        byte[] mute = hex("f8 8f 08 00 00 91 01 9a ed");
        SerialFrameCodec.DecodeResult m = SerialFrameCodec.tryDecode(mute, 0, mute.length);
        eqBytes(mute, m.frame, "decode 00-head event");
        eq(9, m.consumed, "decode 00-head consumed");

        // -- decode skips leading garbage --
        byte[] noisy = hex("00 55 f8 8f 08 00 00 91 01 9a ed");
        SerialFrameCodec.DecodeResult n = SerialFrameCodec.tryDecode(noisy, 0, noisy.length);
        eqBytes(mute, n.frame, "decode skip garbage");
        eq(11, n.consumed, "decode skip consumed");

        // -- decode offset>0 --
        byte[] pad = hex("aa f8 8f 08 00 00 91 01 9a ed bb");
        SerialFrameCodec.DecodeResult o = SerialFrameCodec.tryDecode(pad, 1, 9);
        eqBytes(mute, o.frame, "decode offset frame");
        eq(9, o.consumed, "decode offset consumed");

        // -- decode too few bytes: return null and wait --
        SerialFrameCodec.DecodeResult t = SerialFrameCodec.tryDecode(
                hex("f8 8f 08 00 00"), 0, 5);
        isNull(t, "decode truncated");

        // -- decode bad SUM: skip 1 byte (frame null) --
        byte[] badSum = hex("f8 8f 08 00 00 91 01 9b ed");
        SerialFrameCodec.DecodeResult bs = SerialFrameCodec.tryDecode(badSum, 0, badSum.length);
        isNull(bs.frame, "decode bad-sum frame");
        eq(1, bs.consumed, "decode bad-sum consumed");

        // -- decode bad tail: skip 1 byte --
        byte[] badTail = hex("f8 8f 08 00 00 91 01 9a ee");
        SerialFrameCodec.DecodeResult bt = SerialFrameCodec.tryDecode(badTail, 0, badTail.length);
        isNull(bt.frame, "decode bad-tail frame");
        eq(1, bt.consumed, "decode bad-tail consumed");

        // -- decode short form (F8 8F LEN CMD PAYLOAD SUM ED): 2+4+0=6 --
        byte[] shortFrame = hex("f8 8f 02 04 00 06 ed");
        SerialFrameCodec.DecodeResult s = SerialFrameCodec.tryDecode(shortFrame, 0, shortFrame.length);
        eqBytes(shortFrame, s.frame, "decode short form");
        eq(7, s.consumed, "decode short consumed");

        // -- decode no frame head at all / too short --
        isNull(SerialFrameCodec.tryDecode(hex("00 11 22 33 44 55"), 0, 6), "decode no-head");
        isNull(SerialFrameCodec.tryDecode(hex("f8 8f 08"), 0, 3), "decode too-short");

        // -- upgrade frame (same ID=1 + INDEX as the official upgrade APK): encodeUpgrade --
        // 48 START (fileLen 108360, small image): 11+1+1+48+0+1+167+72=301→0x2D
        eqBytes(hex("f8 8f 0b 01 01 30 00 01 a7 48 2d ed"),
                SerialFrameCodec.encodeUpgrade((byte) 1, (byte) 1, (byte) 48,
                        hex("00 01 a7 48")),
                "encodeUpgrade start");
        // 49 page reply [01 idx7 01]: 8+1+7+49+1=66=0x42.
        // The old decoder cannot handle this (ID != 05/00 falls into the short form and misreads). The false success hides the bug; pin it down.
        byte[] upgReply = hex("f8 8f 08 01 07 31 01 42 ed");
        SerialFrameCodec.DecodeResult ur = SerialFrameCodec.tryDecode(upgReply, 0, upgReply.length);
        eqBytes(upgReply, ur.frame, "decode upgrade reply");
        eq(9, ur.consumed, "decode upgrade reply consumed");
        // Upgrade round-trip (ID/INDEX come back unchanged)
        byte[] upg = SerialFrameCodec.encodeUpgrade((byte) 1, (byte) 5, (byte) 50,
                hex("00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f"));
        SerialFrameCodec.DecodeResult ut = SerialFrameCodec.tryDecode(upg, 0, upg.length);
        eqBytes(upg, ut.frame, "upgrade roundtrip frame");
        eq(upg.length, ut.consumed, "upgrade roundtrip consumed");

        // -- toHex --
        eq("f8 8f", SerialFrameCodec.toHex(hex("f8 8f")), "toHex basic");
        eq("null", SerialFrameCodec.toHex(null), "toHex null");
        eq("", SerialFrameCodec.toHex(new byte[0]), "toHex empty");

        System.out.println("SerialFrameCodecTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
