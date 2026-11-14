package com.ubtechinc.alpha.hardware;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Wire protocol codec for the chest/head MCU (the only frame build/parse
 * implementation under pure-direct).
 * Real wire format (2026-09-06 measured from the official PC motion editor logcat,
 * 651 CHEST frames checked one by one):
 * <pre>
 *   F8 8F LEN SRC DST CMD PARAM... SUM ED
 *   SRC=05 DST=00 (chest commands sent by the official App always use this header;
 *   the MCU replies with the same header;
 *   MCU-initiated events like 0x80/0x91 use a 00 00 header)
 *   LEN = 7 + PARAM byte count, total frame length = LEN+1
 *   SUM = (LEN + SRC + DST + CMD + ΣPARAM) &amp; 0xFF
 * </pre>
 * Evidence: official single-servo {@code f8 8f 0c 05 00 05 03 00 7b 00 64 f8 ed}
 * (12+5+0+5+3+0+123+0+100=248=0xF8 ✓, without counting SRC you get 0xF3 ✗);
 * read reply {@code f8 8f 09 05 00 05 00 03 16 ed} (9+5+0+5+0+3=22=0x16 ✓);
 * the MCU still accepts the old 00 00 header (proven by set_uuid / version
 * fallback / mute key), but to match the official format byte for byte, always
 * send 05 00; the old 00 00 frame calculation (SRC+DST=0, SUM unchanged) stays
 * compatible by default.
 */
public final class SerialFrameCodec {
    private SerialFrameCodec() {}

    private static final byte FRAME_HEAD_0 = (byte) 0xF8;
    private static final byte FRAME_HEAD_1 = (byte) 0x8F;
    private static final byte FRAME_TAIL   = (byte) 0xED;
    /** Official App send header: SRC=05 DST=00 (2026-09-06 measured from the official tuner). */
    private static final byte FRAME_SRC = 0x05;
    private static final byte FRAME_DST = 0x00;
    /** MCU-initiated event header (0x80 heartbeat / 0x91 mute key, etc.): 00 00, for receive compatibility. */
    private static final byte EVENT_SRC = 0x00;

    /** checksum = (LEN + SRC + DST + CMD + ΣPARAM) & 0xFF (shared by encode / long form). */
    private static int checksum(int len, int src, int dst, int cmd, byte[] param, int paramOff, int plen) {
        int sum = (len & 0xFF) + (src & 0xFF) + (dst & 0xFF) + (cmd & 0xFF);
        for (int k = 0; k < plen; k++) sum += param[paramOff + k] & 0xFF;
        return sum & 0xFF;
    }

    /** Short form checksum = (LEN + ΣPAYLOAD) & 0xFF. */
    private static int checksumShort(int len, byte[] buf, int payloadOff, int plen) {
        int sum = len & 0xFF;
        for (int k = 0; k < plen; k++) sum += buf[payloadOff + k] & 0xFF;
        return sum & 0xFF;
    }

    /**
     * Build a complete frame to send (long form, the same 05 00 header as the official App).
     * @param cmd  the command byte from StaticValue (e.g. 52=CHEST_SET_ALL_ANGLE, 5=CHES_CMD_MOTORANGLE)
     * @param param may be null/empty
     */
    public static byte[] encode(byte cmd, byte[] param) {
        return encodeWith(FRAME_SRC, FRAME_DST, cmd, param);
    }

    /**
     * Upgrade frame encoding (same as the official upgrade APK Alpha2SerialProtocol,
     * checked byte for byte against the disassembly):
     * F8 8F LEN ID INDEX CMD PARAM SUM ED, LEN=7+plen,
     * SUM=(LEN+ID+INDEX+CMD+ΣPARAM)&0xFF. Official requests always use ID=1 with
     * INDEX rolling 1..127 (getUnusedIndex; 0 is never used), replies are matched
     * by (id,cmd,index).
     * LAP traffic like servo/LED still uses encode() (05 00) - do not mix them:
     * an earlier upgrade wrongly used 05 00 (ID=5 INDEX=0), which went through the
     * LAP parser, and whatever the MCU returned was treated as success, giving a
     * false success.
     */
    public static byte[] encodeUpgrade(byte id, byte index, byte cmd, byte[] param) {
        return encodeWith(id, index, cmd, param);
    }

    private static byte[] encodeWith(byte id, byte index, byte cmd, byte[] param) {
        int plen = param == null ? 0 : param.length;
        // LEN is a single byte (LEN=7+plen, total frame length=LEN+1): plen>248 wraps
        // and breaks decoding, so throw the error faster than send a bad frame to the
        // MCU.
        if (plen > 248) throw new IllegalArgumentException("param too long: " + plen + " (max 248)");
        int len = 7 + plen; // LEN = 7 + PARAM byte count (total frame length = LEN+1)
        byte[] frame = new byte[3 + 2 + 1 + plen + 1 + 1]; // F8 8F LEN ID INDEX CMD PARAM SUM ED
        int i = 0;
        frame[i++] = FRAME_HEAD_0;
        frame[i++] = FRAME_HEAD_1;
        frame[i++] = (byte) (len & 0xFF);
        frame[i++] = id;
        frame[i++] = index;
        frame[i++] = cmd;
        if (plen > 0) {
            System.arraycopy(param, 0, frame, i, plen);
            i += plen;
        }
        // checksum = (LEN + ID + INDEX + CMD + ΣPARAM) & 0xFF
        frame[i++] = (byte) checksum(len, id, index, cmd, param == null ? new byte[0] : param, 0, plen);
        frame[i++] = FRAME_TAIL;
        return frame;
    }

    /**
     * Try to decode one frame from the buffer, returning {frameBytes, consumed}.
     * Returns null when there is not enough data for a full frame.
     * Long form is preferred (F8 8F LEN ID INDEX CMD ... SUM ED, total length
     * LEN+1; ID/INDEX accepts 05 00 (LAP: official App / MCU reply), 00 00 (MCU
     * event / old hand-written frame), or 01 NN (upgrade protocol: ID=1 with
     * INDEX echo, see ChestUpgrade)).
     * When the long form does not match, fall back to the old short form
     * (F8 8F LEN CMD ... SUM ED), so both can always be decoded.
     * Note: only the ID==1 cell was added (INDEX is any value, guarded by the
     * trailing ED + checksum; a short-form frame with cmd==0x01 does not exist in
     * this system, so the misread risk is the same level as the old version).
     */
    public static DecodeResult tryDecode(byte[] buf, int offset, int available) {
        if (available < 5) return null;
        int start = -1;
        for (int j = offset; j < offset + available - 1; j++) {
            if (buf[j] == FRAME_HEAD_0 && buf[j+1] == FRAME_HEAD_1) { start = j; break; }
        }
        if (start < 0) return null;
        if (start + 4 >= offset + available) return null;
        int len = buf[start+2] & 0xFF;
        // Long form preferred: ID INDEX header (05 00 LAP / 00 00 event / 01 NN upgrade)
        if ((buf[start+4] == 0 && (buf[start+3] == FRAME_SRC || buf[start+3] == EVENT_SRC))
                || buf[start+3] == 0x01) {
            int total = len + 1; // F8 8F LEN(3) + ID INDEX CMD PARAM(len-7) SUM ED = len+1
            if (total < 8) return new DecodeResult(null, (start - offset) + 1);
            if (start + total > offset + available) return null;
            if (buf[start + total - 1] != FRAME_TAIL) {
                return new DecodeResult(null, (start - offset) + 1);
            }
            int plen = len - 7;
            if (checksum(len, buf[start+3], buf[start+4], buf[start+5], buf, start+6, plen)
                    != (buf[start+total-2] & 0xFF)) {
                return new DecodeResult(null, (start - offset) + 1);
            }
            byte[] frame = Arrays.copyOfRange(buf, start, start+total);
            return new DecodeResult(frame, (start - offset) + total);
        }
        // Short form fallback (reached only when the long form header does not match;
        // LEN capped at 250, a fake LEN=255 must not wait forever)
        int total = 2 + 1 + len + 1 + 1; // head(2) + len + payload(len) + checksum + tail
        if (len > 250) return new DecodeResult(null, (start - offset) + 1);
        if (start + total > offset + available) return null;
        // Enough bytes but the tail is wrong = bad frame, skip 1 the same way as a
        // checksum error (previously returning null would wait for more data before
        // moving, so a bad frame clogged the buffer and it kept piling up).
        if (buf[start + total - 1] != FRAME_TAIL) {
            return new DecodeResult(null, (start - offset) + 1);
        }
        if (checksumShort(len, buf, start+3, len) != (buf[start+total-2] & 0xFF)) {
            // checksum error, skip this header and keep searching
            return new DecodeResult(null, (start - offset) + 1);
        }
        byte[] frame = Arrays.copyOfRange(buf, start, start+total);
        return new DecodeResult(frame, (start - offset) + total);
    }

    public static final class DecodeResult {
        public final byte[] frame; // null means skip
        public final int consumed;
        DecodeResult(byte[] frame, int consumed) { this.frame = frame; this.consumed = consumed; }
    }

    /** Utility: print a byte[] as logcat-style hex, e.g. f8 8f 08 00 00 91 01 9a ed
     *  2026-09-09: hand-written hex table (previously String.format byte by byte,
     *  saves GC on the TX/RX hot path; behavior unchanged, tests pass). */
    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    public static String toHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            int v = b[i] & 0xFF;
            sb.append(HEX_LOWER[v >>> 4]).append(HEX_LOWER[v & 0x0F]);
        }
        return sb.toString();
    }
}
