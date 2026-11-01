package com.open.alpha2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Unit test for MicSlots (alpha2-brain#30): the slot lock on a synthetic TDM stream with a
 * known rotation, the relock after an overrun and after a phase move, the channel order, the
 * WAV header and the channels check. Pure java.*, no JUnit. Run: scripts/test-apivalidator.py.
 */
public class MicSlotsTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    /**
     * A synthetic stream like the FPGA card. Each canonical channel k has its own DC level
     * (1000 * (k + 1)). Mic slots have strong noise, the ref and aux slots are smooth.
     * {@code rotation} is the raw slot of canonical slot 0.
     */
    static final class Synth {
        final Random rnd = new Random(42);
        int rotation;
        long frame;

        Synth(int rotation) { this.rotation = rotation; }

        /** The value of canonical slot s on raw channel c. */
        short value(int c, int s) {
            for (int k = 0; k < 8; k++) {
                if (MicSlots.CH[k] == c && MicSlots.SLOT[k] == s) {
                    boolean smooth = k >= 5; // ref1, ref2, aux
                    int noise = smooth ? rnd.nextInt(3) - 1 : rnd.nextInt(4001) - 2000;
                    return (short) (1000 * (k + 1) + noise);
                }
            }
            throw new IllegalStateException();
        }

        void fill(short[] raw, int n) {
            for (int f = 0; f < n; f++, frame++) {
                int q = (int) (frame & 3);
                int s = (q - rotation) & 3;
                raw[2 * f] = value(0, s);
                raw[2 * f + 1] = value(1, s);
            }
        }
    }

    /** Feeds `frames` raw frames, returns the mean of each output channel (last block). */
    static double[] feed(MicSlots ms, Synth syn, int frames) {
        short[] raw = new short[2 * 1024];
        short[][] out = new short[8][1024 / 4 + 4];
        double[] sum = new double[8];
        long cnt = 0;
        for (int done = 0; done < frames; done += 1024) {
            int n = Math.min(1024, frames - done);
            syn.fill(raw, n);
            int outN = ms.process(raw, n, out);
            for (int i = 0; i < outN; i++) {
                for (int k = 0; k < 8; k++) sum[k] += out[k][i];
            }
            cnt += outN;
        }
        for (int k = 0; k < 8; k++) sum[k] = cnt == 0 ? Double.NaN : sum[k] / cnt;
        return sum;
    }

    static String order(double[] mean) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 8; k++) {
            if (k > 0) sb.append(',');
            sb.append(Math.round(mean[k] / 1000.0));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        // -- the lock finds each rotation, and the channel order is m1..m5, ref1, ref2, aux --
        for (int r = 0; r < 4; r++) {
            MicSlots ms = new MicSlots();
            Synth syn = new Synth(r);
            feed(ms, syn, MicSlots.WARMUP_FRAMES + MicSlots.LOCK_FRAMES + 1024);
            eq(r, ms.rotation, "lock.rotation" + r);
            eq(true, ms.crossCheckOk, "lock.crossCheck" + r);
            eq(true, ms.confidence > 0.9f, "lock.confidence" + r);
            eq("1,2,3,4,5,6,7,8", order(feed(ms, syn, 32768)), "order.rotation" + r);
        }

        // -- no output before the lock --
        MicSlots early = new MicSlots();
        double[] m = feed(early, new Synth(1), MicSlots.WARMUP_FRAMES + 1024);
        eq(-1, early.rotation, "early.noLock");
        eq(true, Double.isNaN(m[0]), "early.noOutput");

        // -- overrun: the phase changes, the lock is found again --
        MicSlots ms = new MicSlots();
        Synth syn = new Synth(2);
        feed(ms, syn, MicSlots.WARMUP_FRAMES + MicSlots.LOCK_FRAMES + 1024);
        eq(2, ms.rotation, "overrun.before");
        ms.overrun();
        eq(-1, ms.rotation, "overrun.unlocked");
        syn.rotation = 3;
        feed(ms, syn, MicSlots.WARMUP_FRAMES + MicSlots.LOCK_FRAMES + 1024);
        eq(3, ms.rotation, "overrun.relock");
        eq("1,2,3,4,5,6,7,8", order(feed(ms, syn, 32768)), "overrun.order");

        // -- a phase move without an overrun: relock after two check windows --
        ms = new MicSlots();
        syn = new Synth(0);
        feed(ms, syn, MicSlots.WARMUP_FRAMES + MicSlots.LOCK_FRAMES + 1024);
        eq(0, ms.rotation, "move.before");
        syn.rotation = 1;
        feed(ms, syn, 3 * MicSlots.CHECK_FRAMES + 1024);
        eq(1, ms.rotation, "move.after");
        eq(1, ms.relocks, "move.relocks");
        eq("1,2,3,4,5,6,7,8", order(feed(ms, syn, 32768)), "move.order");

        // -- channels --
        eq(5, MicSlots.parseChannels(null, 5, 5, 8), "ch.default");
        eq(5, MicSlots.parseChannels("", 5, 5, 8), "ch.empty");
        eq(8, MicSlots.parseChannels("8", 5, 5, 8), "ch.8");
        eq(5, MicSlots.parseChannels(" 5 ", 5, 5, 8), "ch.5");
        eq(-1, MicSlots.parseChannels("2", 5, 5, 8), "ch.2");
        eq(-1, MicSlots.parseChannels("1", 5, 5, 8), "ch.1record");
        eq(-1, MicSlots.parseChannels("abc", 5, 5, 8), "ch.abc");
        eq("m1,m2,m3,m4,m5", MicSlots.channelNames(5), "names.5");
        eq("m1,m2,m3,m4,m5,ref1,ref2,aux", MicSlots.channelNames(8), "names.8");

        // -- WAV header and data for 5 and 8 channels --
        for (int ch : new int[]{5, 8}) {
            short[][] data = new short[ch][3];
            for (int k = 0; k < ch; k++) for (int i = 0; i < 3; i++) data[k][i] = (short) (100 * k + i);
            byte[] w = MicSlots.wav(data, ch, 3);
            ByteBuffer bb = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
            eq(44 + 3 * ch * 2, w.length, "wav.len" + ch);
            eq("RIFF", new String(w, 0, 4), "wav.riff" + ch);
            eq(36 + 3 * ch * 2, bb.getInt(4), "wav.riffSize" + ch);
            eq("WAVEfmt ", new String(w, 8, 8), "wav.fmt" + ch);
            eq((short) 1, bb.getShort(20), "wav.pcm" + ch);
            eq((short) ch, bb.getShort(22), "wav.channels" + ch);
            eq(16000, bb.getInt(24), "wav.rate" + ch);
            eq(16000 * ch * 2, bb.getInt(28), "wav.byteRate" + ch);
            eq((short) (ch * 2), bb.getShort(32), "wav.align" + ch);
            eq((short) 16, bb.getShort(34), "wav.bits" + ch);
            eq("data", new String(w, 36, 4), "wav.data" + ch);
            eq(3 * ch * 2, bb.getInt(40), "wav.dataSize" + ch);
            // interleaved: sample 1 of channel 2 is at frame 1, slot 2
            eq((short) 201, bb.getShort(44 + (1 * ch + 2) * 2), "wav.interleave" + ch);
            // header + parts == one wav
            byte[] h = MicSlots.wavHeader(ch, 3);
            byte[] p1 = MicSlots.pcm(data, ch, 0, 2);
            byte[] p2 = MicSlots.pcm(data, ch, 2, 1);
            byte[] joined = new byte[h.length + p1.length + p2.length];
            System.arraycopy(h, 0, joined, 0, h.length);
            System.arraycopy(p1, 0, joined, h.length, p1.length);
            System.arraycopy(p2, 0, joined, h.length + p1.length, p2.length);
            eq(true, java.util.Arrays.equals(w, joined), "wav.parts" + ch);
        }

        System.out.println("MicSlotsTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
