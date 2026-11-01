package com.open.alpha2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure Java part of the 5-mic capture (MultiMicController): the slot lock, the de-interleave
 * of the 8 TDM slots into the canonical channel order, the {@code channels} check and the WAV
 * header. No Android classes (unit test: MicSlotsTest, alpha2-brain#30).
 *
 * <p>Raw stream: 2 channels, S16_LE, 64000 Hz. Each raw channel carries 4 TDM slots in turn.
 * The slot phase is not stable: each open starts at a random slot. Channel 1 has exactly one
 * smooth slot, the "aux" slot. It defines canonical slot 0. Channel 0 must then show its two
 * smooth slots (the speaker reference) at slots 1 and 3 (the cross-check). See
 * MultiMicController for the measurements.
 *
 * <p>Not thread safe: the capture thread owns one instance. The lock fields are volatile for
 * the status reader.
 */
final class MicSlots {
    static final int RAW_RATE = 64000;
    static final int SLOT_RATE = 16000;
    static final int WARMUP_FRAMES = 6400;  // 100 ms: the first samples are junk
    static final int LOCK_FRAMES = 8192;    // 128 ms of data for the first lock
    static final int CHECK_FRAMES = RAW_RATE; // check the lock again each second

    /** Canonical order: names and (raw channel, canonical slot). */
    static final String[] NAMES = {"m1", "m2", "m3", "m4", "m5", "ref1", "ref2", "aux"};
    static final int[] CH = {0, 0, 1, 1, 1, 0, 0, 1};
    static final int[] SLOT = {0, 2, 1, 2, 3, 1, 3, 0};

    volatile int rotation = -1;
    volatile float confidence;
    volatile boolean crossCheckOk;
    volatile int relocks;
    /** Set when the lock moved or was found (for the log of the caller). */
    volatile String lastLockEvent;

    private long pos;                 // frame counter since open or since the last overrun
    private final long[] d0 = new long[4];
    private final long[] d1 = new long[4];
    private final short[] prev = new short[8];
    private final short[] sf = new short[8];
    private long accFrames;
    private int pendingRot = -1;
    private int pendingCount;

    /** After an overrun (EPIPE): the slot phase can change, lock again. */
    void overrun() {
        pos = 0;
        rotation = -1;
        accFrames = 0;
        Arrays.fill(d0, 0);
        Arrays.fill(d1, 0);
    }

    /**
     * De-interleaves {@code n} raw frames ({@code raw[2 * f]} = channel 0, {@code raw[2 * f + 1]}
     * = channel 1) into {@code out[k][i]} in the canonical order. Returns the number of output
     * samples for each channel (0 before the lock). {@code out[k]} must hold at least
     * {@code n / 4 + 1} samples.
     */
    int process(short[] raw, int n, short[][] out) {
        int outN = 0;
        for (int f = 0; f < n; f++, pos++) {
            short a = raw[2 * f], b = raw[2 * f + 1];
            if (pos < WARMUP_FRAMES) continue;
            int q = (int) (pos & 3);
            // smoothness statistics per raw slot (for lock and re-check)
            d0[q] += Math.abs(a - prev[q]);
            d1[q] += Math.abs(b - prev[4 + q]);
            prev[q] = a;
            prev[4 + q] = b;
            accFrames++;
            int r = rotation;
            if (r < 0) {
                if (accFrames >= LOCK_FRAMES) {
                    int best = lockFrom();
                    rotation = best;
                    lastLockEvent = "slot lock rotation=" + best + " conf=" + confidence
                            + " crossCheck=" + crossCheckOk;
                    accFrames = 0;
                    Arrays.fill(d0, 0);
                    Arrays.fill(d1, 0);
                }
                continue;
            }
            if (accFrames >= CHECK_FRAMES) {
                int best = bestRotation(d1);
                if (best != r && smoothRatio(d1, best) < 0.5f) {
                    if (best == pendingRot) pendingCount++; else { pendingRot = best; pendingCount = 1; }
                    if (pendingCount >= 2) {
                        lastLockEvent = "slot phase moved: rotation " + r + " -> " + best;
                        lockFrom();
                        rotation = best;
                        relocks++;
                        pendingRot = -1;
                        pendingCount = 0;
                    }
                } else {
                    pendingRot = -1;
                    pendingCount = 0;
                }
                accFrames = 0;
                Arrays.fill(d0, 0);
                Arrays.fill(d1, 0);
            }
            int s = (q - rotation) & 3;  // canonical slot
            sf[s] = a;
            sf[4 + s] = b;
            if (s == 3 && outN < out[0].length) {
                for (int k = 0; k < 8; k++) out[k][outN] = sf[CH[k] * 4 + SLOT[k]];
                outN++;
            }
        }
        return outN;
    }

    static int bestRotation(long[] d1) {
        int best = 0;
        for (int i = 1; i < 4; i++) if (d1[i] < d1[best]) best = i;
        return best;
    }

    /** d1[r] / smallest other d1: low = clear lock. */
    static float smoothRatio(long[] d1, int r) {
        long other = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) if (i != r) other = Math.min(other, d1[i]);
        return other <= 0 ? 1f : (float) d1[r] / other;
    }

    /** Sets confidence and crossCheckOk from the statistics, returns the rotation. */
    private int lockFrom() {
        int r = bestRotation(d1);
        confidence = 1f - Math.min(1f, smoothRatio(d1, r));
        // channel 0: the reference slots (canonical 1 and 3) must be smoother than the
        // mic slots (canonical 0 and 2).
        long refs = d0[(r + 1) & 3] + d0[(r + 3) & 3];
        long mics = d0[r] + d0[(r + 2) & 3];
        crossCheckOk = refs < mics;
        return r;
    }

    // -- HTTP helpers ------------------------------------------------------------------------

    /**
     * The {@code channels} query value. Null or empty gives {@code dflt}. Returns -1 for a
     * value that is not in {@code allowed} (the caller gives 400, alpha2-brain#28).
     */
    static int parseChannels(String value, int dflt, int... allowed) {
        if (value == null || value.trim().isEmpty()) return dflt;
        int c;
        try {
            c = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        for (int a : allowed) if (a == c) return c;
        return -1;
    }

    static String channelNames(int channels) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < channels; k++) {
            if (k > 0) sb.append(',');
            sb.append(NAMES[k]);
        }
        return sb.toString();
    }

    /** The 44-byte header of an interleaved 16-bit PCM WAV (format 1) at 16 kHz. */
    static byte[] wavHeader(int channels, int samplesPerChannel) {
        int dataLen = samplesPerChannel * channels * 2;
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(SLOT_RATE)
                .putInt(SLOT_RATE * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        return bb.array();
    }

    /** Interleaved little-endian PCM of samples [from, from + n) of the first {@code channels}. */
    static byte[] pcm(short[][] ch, int channels, int from, int n) {
        ByteBuffer bb = ByteBuffer.allocate(n * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = from; i < from + n; i++) {
            for (int k = 0; k < channels; k++) bb.putShort(ch[k][i]);
        }
        return bb.array();
    }

    /** One complete WAV file (header and data). */
    static byte[] wav(short[][] ch, int channels, int n) {
        byte[] h = wavHeader(channels, n);
        byte[] d = pcm(ch, channels, 0, n);
        byte[] out = new byte[h.length + d.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(d, 0, out, h.length, d.length);
        return out;
    }
}
