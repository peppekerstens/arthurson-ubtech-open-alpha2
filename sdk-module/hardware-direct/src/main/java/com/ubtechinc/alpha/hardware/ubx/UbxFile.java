package com.ubtechinc.alpha.hardware.ubx;

import java.util.ArrayList;
import java.util.List;

/**
 * .ubx parse result (clean-room implementation; the structure follows the measured
 * real .ubx format, verified byte for byte against the full set of 202 UBX/actions
 * files + the Alpha2_pc example file).
 *
 * <p>File = top-level header + motion segment (multiple tracks, each track has
 * several servo frames) + side segment (cLen, usually 8).
 * Length semantics: every segment is [outer len][echo==len][body]; len counts from
 * the echo (including the echo, excluding the outer itself); after consuming a
 * segment, advance {@code 4 + len} to align the next segment.</p>
 */
public final class UbxFile {
    public int version;
    public final List<UbxTrack> tracks = new ArrayList<>();
    /**
     * tick time base (ms), from the first track containing a type-0 block;
     * for multiple tracks, each track uses its own {@link UbxTrack#timeBaseMs};
     * here it is only a single-track shortcut.
     * When the file has no such block, &lt;=0 (unknown).
     */
    public int timeBaseMs = -1;
    /**
     * Count of empty markers skipped tolerantly. Measured on the original 202+1
     * files: the motion segment is exactly aligned, with no real empty marker
     * (the empty slot counted before was a phantom from stepping 4B too few); this
     * counter is kept only for forward compatibility and is always 0 on normal
     * files.
     */
    public int skipped;

    /** A single track (maps to util.d.b). Servo frames are collected into frames only from d.a with f==0, through the a.d chain. */
    public static final class UbxTrack {
        /** track id (4B after the header echo; always 0 for a single action, e.g. 0/2/3/4/1 for multiple actions). */
        public int id;
        public int servoGroups;
        /** Count of voice d.a (f==4 → a/j/a/o chain) successfully split out (the music frame is the voice item in frames). */
        public int voiceGroups;
        public int framesA;
        public int nonServoFrames;
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafCount;
        /** Full d.d leaves (each item [a][b][c][d]; leafA~D keep the last item for compatibility). */
        public final List<int[]> leafRows = new ArrayList<>();
        /** Full d.f keyframe table (pose reference, not merged into frames; an old version wrongly merged it into frames). */
        public final List<UbxServoFrame> keyframes = new ArrayList<>();
        /** This track's time base (ms, the a of type-0); when absent &lt;=0, falls back to {@link UbxFile#timeBaseMs}. */
        public int timeBaseMs = -1;
        public int timeBaseB;
        public final List<Integer> frameBValues = new ArrayList<>();
        /** a.d non-0/1 blocks as-is (type2/3 lights etc., type4 music meta; an old version dropped them directly). */
        public final List<UbxBlock> blocks = new ArrayList<>();
        /** Music file name (e.g. rap.mp3, null when absent; extracted from the UTF-16 segment). */
        public String musicName;
        /** Original absolute music path (e.g. D:\work\...\rap.mp3, null when absent, for tracing only). */
        public String musicPath;
        /** Count of empty markers skipped tolerantly (same semantics as the stock `if (L1>0)`). */
        public int skipped;
        public final List<UbxServoFrame> frames = new ArrayList<>();
        /**
         * All d.a segments as [e-field, f-flag] (file order; d/a.a() is e, d/a.b() is
         * f, used by the smali f/b.f segment-selection branch). f==0 servo, 1→f/e,
         * 2→e/d, 4→voice.
         */
        public final List<int[]> daAll = new ArrayList<>();
        /**
         * Parallel to daAll: each segment's [start,end) within {@link #frames};
         * a non-servo segment records null (goes through the f/e, e/d side channel,
         * not into the servo order).
         */
        public final List<int[]> daSpans = new ArrayList<>();
        /**
         * Official chained servo play order: a list of daAll indices (proven by smali
         * f/b.f + f/c.a:
         * entry = the (a==-1,b==0) leaf; follow the chain leaf by leaf, leaf.c
         * selects the segment against d/a.e,
         * the v7 gate (the d value of the keyframe a==leaf.d) skips when not 0/3,
         * c==-2 ends the chain; after a segment finishes, use the out index (servo 2,
         * f/e 0, e/d by True/False keyframe, -1 when unnamed to break the chain) to
         * find the next batch of (a==e,b==outIdx) leaves.
         * f!=0 segments do not enter the servo order). null = no chain head, fall back
         * to parse order;
         * an empty table = the chain says do not play (follow the official zero cells).
         */
        public List<Integer> playOrder;
        /**
         * The chain ran to the c==-2 terminator (smali f/c.a completed branch: the
         * whole action is done, no later track starts; forward track1, reverse track0
         * proven - up to here, the tracks after do not play).
         */
        public boolean chainTerminated;
    }

    /**
     * a.d non-servo block as-is (type + data fully copied).
     */
    public static final class UbxBlock {
        public int type;
        public byte[] data = new byte[0];
    }

    /**
     * A single servo keyframe (maps to util.a.a).
     * start/end share the play tick unit (ticks, multiply by timeBaseMs to get ms;
     * the original b/c often has start&gt;end like 20/0, semantics pending a chest dump,
     * play uses start).
     * angles20 is the 20-axis target values (0-255, pre-extracted by the a.m rule
     * "every 8 bytes to 1 int → byte";
     * when groups are fewer than 20 the rest are naturally 0, no rewrite).
     * moveTimeHintMs = the stock send time parameter (a.a.b() * the same track
     * timeBase), for the player to reference.
     */
    public static final class UbxServoFrame {
        public int start;
        public int end;
        /** voice frame (d.a f==4 → a/j/a/o chain): for music scheduling only, servo send skips it. music is the GBK name at the end of the e-blob. */
        public boolean voice;
        public String music;
        public final byte[] angles20 = new byte[20];
        public int baseB;
        public int baseC;
        public int groupBytes;
        public int moveTimeHintMs;
        // d.e leaf raw fields (timing semantics pending a dump)
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafF;
        public int leafG;
    }
}
