package com.ubtechinc.alpha.hardware.ubx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * .ubx clean-room parser (absolute-offset version).
 *
 * <p>The format follows the measured real .ubx (full set of 202 UBX/actions + the
 * Alpha2_pc example file, the motion tail aligned exactly, no slack; the earlier
 * "4B slack / empty marker / X field" were all misreads, now corrected against the
 * real thing):
 * <pre>
 * top-level (f.k.setRawData): [total][ubx-alpha 9B][version≤2][eLen][motion eLen B][cLen][side segment skip]
 *   (eLen equals the first word of motion, double-written; the side segment measured cLen=8 always)
 * motion (d.c): [len][count][item]*, item=[outer len][echo==len][id][fLen...],
 *   len counts from the echo (excluding the outer itself), spend {@code 4 + len} to
 *   align the next item, the tail meets the motion tail exactly.
 * track (d.b): [outer][echo][id][fLen outer][f→d.f/d.e pose reference]
 *              [aLen outer][a→d.d leaf list][bLen outer][b→d.a composite frame list] (no X field).
 *   d.d leaf list (private a): [echo==len][count][L1 outer][echo==L1 + 20B head + tail]*
 *     d.d leaf head: [echo][a][b][c][d] (values mostly -1/-2 sentinel, kept in full).
 *   d.a composite frame list (private b): [echo==len][count][L1 outer][L1 content→d.a composite frame]*,
 *     strictly does not leave this segment (the old version used the file tail as the bound, too loose).
 * d.a composite frame (pos points at L2', len is L1'). Layout:
 * [len][fLen][f table][a layout][b layout][c[50]][d[50]][e][f][g][h][h bytes→i].
 *   whether servo is decided by f (f==0 goes through the a.d servo chain; b measured 72~229 always nonzero, not the criterion).
 *   the servo i blob + h length feed the a.d multi-track dispatch.
 * a.d dispatch: [len==self][count][type][len][data]*
 *   type==0 and len==8 → time base [a][b] (static, a.p semantics; recorded in this track, out.timeBaseMs takes the first)
 *   type==1 → data → a.h → a.c container [len==self][count][L1][content]*
 *     → a.b block [L1 outer][echo][a][b=start][c=end][d[30]][count][a.a]*
 *       → a.a servo frame [L1 outer][echo][a][b=start][c=end][d[30]][f][e(f bytes)]
 *   type==2/3/4... (lights/music etc.) stored as-is in {@code track.blocks} and the music name/path extracted, not dropped.
 * a.a frame angles (a.m private send original): groups=f()/8 (cap 20);
 * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF; fewer than 20 groups, the rest are naturally 0.
 * send time actual param = b*timeBase (f.b.f() original {@code send(f, f.b()*p.a)}, using the same track time base).
 * chest serial send: cmd 3 [20 axis bytes + short time] (a.m original, matches StaticValue.CHEST_CMD_SENDMOTOR).
 * </pre>
 * </p>
 */
public final class UbxParser {
    private UbxParser() {}

    public static final class UbxParseException extends Exception {
        UbxParseException(String m) { super(m); }
    }

    public static UbxFile parseFile(File f) throws IOException, UbxParseException {
        long flen = f.length();
        // Guard first: do not new byte[] for an empty / oversized file (>2GB turning
        // into a negative int gives a NegativeArraySize Error, the caller catch
        // (Exception) cannot catch it and the whole worker thread dies).
        // Note: the p+len checks in each segment below are int addition; a malicious
        // len can wrap in theory, but every Java array access has a bounds check and
        // le() overrun always throws UbxParseException (the caller treats it as parse
        // failed, returns 500, no corruption), so do not change each one to long;
        // guarding the OOM case that really would kill the process is enough.
        if (flen <= 0) throw new UbxParseException("empty file: " + f.getName());
        if (flen > 64L * 1024L * 1024L) throw new UbxParseException("file too large: " + flen);
        byte[] data = new byte[(int) flen];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != data.length) throw new UbxParseException("short read " + off + "/" + data.length);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
        return parse(data);
    }

    public static UbxFile parse(byte[] data) throws UbxParseException {
        Cursor r = new Cursor(data, 0, data.length);
        int total = r.i32();
        if (total != data.length) throw new UbxParseException("total " + total + "!=" + data.length);
        byte[] magic = r.bytes(9);
        String ms;
        try {
            ms = new String(magic, "GB2312");
        } catch (Exception e) {
            throw new UbxParseException("gb2312 missing");
        }
        if (!"ubx-alpha".equals(ms)) throw new UbxParseException("bad magic " + ms);
        UbxFile out = new UbxFile();
        out.version = r.i32();
        if (out.version > 2) throw new UbxParseException("version " + out.version);
        int eLen = r.i32();
        int motionStart = r.pos;
        if (eLen < 0 || motionStart + eLen > data.length) throw new UbxParseException("bad motion len " + eLen);
        parseMotion(data, motionStart, eLen, out);
        // Backfill: the same track time base can appear after the servo frames (multiple d.a), so recompute uniformly from the final state.
        for (UbxFile.UbxTrack t : out.tracks) {
            int base = t.timeBaseMs > 0 ? t.timeBaseMs
                    : (out.timeBaseMs > 0 ? out.timeBaseMs : 0);
            if (base <= 0) continue;
            for (UbxFile.UbxServoFrame sf : t.frames) {
                sf.moveTimeHintMs = sf.start * base;
            }
        }
        int p = motionStart + eLen;
        if (data.length - p >= 4) {
            int cLen = le(data, p);
            p += 4;
            if (cLen < 0 || p + cLen > data.length) throw new UbxParseException("bad aux len " + cLen);
            p += cLen;
        }
        if (p != data.length) throw new UbxParseException("top slack " + (data.length - p));
        return out;
    }

    /**
     * motion segment = d.c: [len][count][item]*.
     * Length semantics unified across the file: each item [outer len][echo==len][body],
     * len excludes the outer itself, advance {@code 4 + len}; the tail must meet the
     * motion tail exactly (the original 202+1 full set is like this).
     * The len&lt;=0 empty marker is kept only for forward compatibility (no real empty
     * slot in the measured original).
     */
    private static void parseMotion(byte[] d, int off, int len, UbxFile out) throws UbxParseException {
        if (le(d, off) != len) throw new UbxParseException("motion len check");
        int count = le(d, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int itemLen = le(d, p);
            if (itemLen <= 0) {
                out.skipped++;
                p += 4;
                continue;
            }
            if (le(d, p + 4) != itemLen) throw new UbxParseException("track echo");
            if (p + 4 + itemLen > off + len) throw new UbxParseException("bad track len " + itemLen);
            parseTrack(d, p, itemLen, out); // off aligned to the outer (including the echo check, d.b original copies from L1)
            p += 4 + itemLen;
        }
        if (p != off + len) throw new UbxParseException("motion slack " + (off + len - p));
    }

    /**
     * track = d.b: [outer][echo==len][id][fLen outer][f→d.f/d.e pose reference]
     * [aLen outer][a→d.d leaf list][bLen outer][b→d.a composite frame list].
     * The original has no X field (the old version mistook the echo for the id and the
     * real id for X; id is always 0 for a single track, multiple tracks like
     * 1464835936031's 0/2/3/4/1).
     * Each segment's first content echo self-checks the len (double-write convention);
     * the segment tail must meet the next segment exactly.
     */
    private static void parseTrack(byte[] t, int off, int declaredLen, UbxFile out) throws UbxParseException {        if (le(t, off) != declaredLen) throw new UbxParseException("track len check");
        if (le(t, off + 4) != declaredLen) throw new UbxParseException("track echo");
        UbxFile.UbxTrack track = new UbxFile.UbxTrack();
        int p = off + 8;
        track.id = le(t, p); p += 4;
        int fLen = le(t, p); p += 4;
        int trackEnd = off + 4 + declaredLen;
        if (fLen < 0 || p + fLen > trackEnd) throw new UbxParseException("bad ftable " + fLen);
        if (fLen > 0) parseFrameTable(t, p, fLen, out, track);
        p += fLen;
        int aLen = le(t, p); p += 4;
        if (aLen < 0 || p + aLen > trackEnd) throw new UbxParseException("bad aleaflist " + aLen);
        parseFrameDList(t, p, aLen, track);
        p += aLen;
        int bLen = le(t, p); p += 4;
        if (bLen < 0 || p + bLen > trackEnd) throw new UbxParseException("bad aframelist " + bLen);
        parseFrameAList(t, p, bLen, track, out);
        p += bLen;
        if (p != trackEnd) throw new UbxParseException("track slack " + (trackEnd - p));
        track.playOrder = computePlayOrder(track);
        out.tracks.add(track);
    }

    /**
     * Official chained servo play order (proven by smali f/b.f + f/c.a + e/c).
     * entry = the (a==-1,b==0) leaf (f/e.play uses the same filter as track.b());
     * follow the chain leaf by leaf:
     * leaf.c selects the segment against d/a.e (d/b.a(I) searches e==I), v7 = the d
     * value of keyframe[a==leaf.d], skip when not 0/3, c==-2 ends the chain (f/c.a
     * completed branch).
     * After a segment finishes, use the out index to find the next batch of (a==e,
     * b==outIdx) leaves: servo(f==0)→2,
     * f/e(f==1)→0, voice(f==4)→2 (chain continuation assumption, rare in the corpus),
     * e/d(f==2)→the a value of the True/False named keyframe - zero named across the
     * full 217 files,
     * always -1 to break the chain (matches the official on-device behavior).
     * return null = no chain head (the caller falls back to parse order, behavior
     * identical to the old version word for word);
     * return an empty table = the chain says do not play (follow the official zero cells).
     */
    static List<Integer> computePlayOrder(UbxFile.UbxTrack track) {
        List<int[]> entry = new ArrayList<>();
        for (int[] leaf : track.leafRows) {
            if (leaf.length >= 4 && leaf[0] == -1 && leaf[1] == 0) entry.add(leaf);
        }
        if (entry.isEmpty()) return null; // no chain head to follow, fall back to the old order
        Map<Integer, Integer> keyD = new HashMap<>();
        for (UbxFile.UbxServoFrame kf : track.keyframes) {
            keyD.put(kf.leafA, kf.leafD);
        }
        boolean hasKeys = !keyD.isEmpty();
        List<Integer> order = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<int[]> pending = new ArrayList<>(entry);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 1024) {
            int[] leaf = pending.remove(0);
            String k = leaf[0] + "," + leaf[1] + "," + leaf[2] + "," + leaf[3];
            if (!seen.add(k)) continue; // loop guard (the official hangs the machine, break the chain here to stay alive)
            int c = leaf[2];
            int d = leaf[3];
            // c==-2: chain terminated (f/c.a completed branch: the whole action is
            // done, no later track plays;
            // forward track1, reverse track0 (chain tail) proven, checked against the
            // real machine video).
            if (c == -2) { track.chainTerminated = true; break; }
            if (hasKeys) {
                Integer v7 = keyD.get(d);
                if (v7 == null || (v7 != 0 && v7 != 3)) continue; // v7 gate
            }
            int dai = -1;
            for (int i = 0; i < track.daAll.size(); i++) {
                if (track.daAll.get(i)[0] == c) { dai = i; break; }
            }
            if (dai < 0) continue; // official return
            int ff = track.daAll.get(dai)[1];
            int outIdx;
            if (ff == 0) {
                order.add(dai); // official per-leaf independent f/b run, a repeated reference replays
                outIdx = 2; // f/i.getOutPutIndex
            } else if (ff == 1) {
                outIdx = 0; // f/e.getOutPutIndex
            } else if (ff == 4) {
                outIdx = 2; // voice chain continuation assumption (see above)
            } else if (ff == 2) {
                outIdx = -1; // e/d: zero named keyframes, break the chain (see above)
            } else {
                continue;
            }
            int de = track.daAll.get(dai)[0];
            for (int[] lf : track.leafRows) {
                if (lf.length >= 4 && lf[0] == de && lf[1] == outIdx) pending.add(lf);
            }
        }
        return order;
    }

    /**
     * d.d leaf list (d.b private a): [echo==len][count][L1 outer][echo==L1 + 20B head + tail]*.
     * The leaf head 20B [echo][a][b][c][d] (original values mostly -1/-2 sentinel);
     * kept in full,
     * leafA~D carry the last item for compatibility.
     */
    private static void parseFrameDList(byte[] b, int off, int len, UbxFile.UbxTrack track) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("dlist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // empty marker: same semantics as the stock if>0
            if (p + l1 > off + len) throw new UbxParseException("bad dleaf len " + l1);
            if (le(b, p) != l1) throw new UbxParseException("dleaf len check");
            int a = le(b, p + 4);
            int bb = le(b, p + 8);
            int c = le(b, p + 12);
            int dd = le(b, p + 16);
            track.leafRows.add(new int[]{a, bb, c, dd});
            track.leafA = a;
            track.leafB = bb;
            track.leafC = c;
            track.leafD = dd;
            track.leafCount++;
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("dlist slack " + (off + len - p));
    }

    /**
     * d.a composite frame list (d.b private b, b-section): [echo==len][count][L1 outer][L1 content→d.a composite frame]*.
     * The first content echo == L1 (double-write); strictly does not leave this segment.
     */
    private static void parseFrameAList(byte[] b, int off, int len, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("alist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // empty marker: same semantics as the stock if>0
            if (le(b, p) != l1) throw new UbxParseException("aframe echo");
            if (p + l1 > off + len) throw new UbxParseException("bad aframe len " + l1);
            parseFrameA(b, p, l1, track, out);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("alist slack " + (off + len - p));
    }

    /**
 * d.a composite frame (pos points at L2', len is L1'). Layout:
 * [len][fLen][f table][a layout][b layout][c[50]][d[50]][e][f][g][h][h bytes→i].
 * Look only at f (f.b.f dispatch original): f==0 goes through the f.i/a.d servo chain;
 * f==4 goes through voice/b (online TTS,
 * not parsed); b measured always nonzero, not the criterion. The music mp3 comes from
 * the type==4 block inside the servo chain (a/d original
 * builds a handler by type: 1→a/h servo, 2→a/f, 3→a/e, 4→a/j/mp3, 5→a/i), a/j inside is
 * the same-shape a/c→a/b→a.a, the frame e-blob minus 8B head/tail is the GBK song name
 * (a/o.a() original).
     */
    private static void parseFrameA(byte[] f, int off, int declaredLen, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(f, off) != declaredLen) throw new UbxParseException("aframe len check");
        int itemEnd = off + declaredLen;
        int p = off + 4;
        int fLen = le(f, p); p += 4;
        if (fLen < 0 || p + fLen > itemEnd) throw new UbxParseException("bad aframe ftable");
        p += fLen;
        if (p + 216 > itemEnd) throw new UbxParseException("bad aframe head");
        p += 4; // a (layout field)
        int fb = le(f, p); p += 4; // b (layout field; not the servo criterion, original measured 72~229)
        p += 100; // c[50]
        p += 100; // d[50]
        int fe = le(f, p); p += 4; // e=d/a.a(): the official leaf.c segment key (f/b.f proven)
        int ff = le(f, p); p += 4; // f: ==0 is servo (f.b.f→f.i original)
        p += 4; // g
        int fh = le(f, p); p += 4; // h=i blob length
        if (fh < 0 || p + fh > itemEnd) throw new UbxParseException("bad aframe iblob " + fh);
        if (p + fh != itemEnd) throw new UbxParseException("aframe slack " + (itemEnd - p - fh));
        track.framesA++;
        track.frameBValues.add(fb);
        // record (e,f) for the whole segment (for chained sorting; a non-servo segment records its span as null)
        track.daAll.add(new int[]{fe, ff});
        if (fh == 0) { track.daSpans.add(null); return; }
        if (ff != 0) { track.nonServoFrames++; track.daSpans.add(null); return; }
        int spanStart = track.frames.size();
        try {
            parseServoChain(f, p, fh, out, track, false);
            track.servoGroups++;
        } catch (UbxParseException e) {
            track.nonServoFrames++;
        }
        // record the span of the servo d.a segment (for chained sorting + the player to pick frames; even a half chain is recorded, it still plays out)
        track.daSpans.add(new int[]{spanStart, track.frames.size()});
    }

    /**
     * servo/voice execution chain (a.d multi-track dispatch): [len==self][count][type][len][data]* (pos points at len).
     * type==0 and len==8 → time base [a][b] (static, a.p semantics; recorded in this track, out takes the first);
     * type==1 → data → a.h servo frame;
     * type==4 → data → a/j music chain (same-shape a/c→a/b→a.a, the frame is marked voice for music scheduling, not into the servo);
     * the other types (2/3/5...) are kept as-is and the music name/path extracted (fallback).
     */
    static void parseServoChain(byte[] eBytes, int off, int eLen, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(eBytes, off) != eLen) throw new UbxParseException("adispatch len check");
        int count = le(eBytes, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int type = le(eBytes, p); p += 4;
            int len = le(eBytes, p); p += 4;
            if (len <= 0) { track.skipped++; continue; }
            if (p + len > off + eLen) throw new UbxParseException("bad block len");
            if (type == 0 && len == 8) {
                int base = le(eBytes, p);
                track.timeBaseMs = base;
                track.timeBaseB = le(eBytes, p + 4);
                if (out.timeBaseMs <= 0) out.timeBaseMs = base;
            } else if (type == 1) {
                parseTrackH(eBytes, p, len, out, track, voice);
            } else if (type == 4) {
                // a/j music chain: the content is the a/c container (on failure, fall back to keeping it as-is, does not affect the servo).
                try {
                    parseTrackH(eBytes, p, len, out, track, true);
                    track.voiceGroups++;
                } catch (UbxParseException e) {
                    track.nonServoFrames++;
                }
                UbxFile.UbxBlock blk = new UbxFile.UbxBlock();
                blk.type = type;
                blk.data = new byte[len];
                System.arraycopy(eBytes, p, blk.data, 0, len);
                track.blocks.add(blk);
                supplementMusic(blk.data, track);
            } else {
                UbxFile.UbxBlock blk = new UbxFile.UbxBlock();
                blk.type = type;
                blk.data = new byte[len];
                System.arraycopy(eBytes, p, blk.data, 0, len);
                track.blocks.add(blk);
                supplementMusic(blk.data, track);
            }
            p += len;
        }
        if (p != off + eLen) throw new UbxParseException("adispatch slack " + (off + eLen - p));
    }

    /**
     * Extract the {@code *.mp3} file name and absolute path from the music/light block binary.
     * The original stores the name like {@code rap.mp3} in UTF-16LE and the full path {@code D:\...\rap.mp3} in ASCII;
     * this scans both encodings, reads only, does not change.
     */
    private static void supplementMusic(byte[] data, UbxFile.UbxTrack track) {
        // ASCII/GBK path: back up byte by byte (accept all except NUL/control chars, Chinese directories are GBK encoded), then decode as GBK.
        String lowerRaw = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.US);
        int idx = lowerRaw.indexOf(".mp3");
        while (idx >= 0) {
            int e = idx + 4;
            int s = idx;
            while (s > 0) {
                int bb = data[s - 1] & 0xFF;
                if (bb == 0 || bb < 0x20) break;
                s--;
            }
            if (e - s >= 5 && e - s <= 260) {
                byte[] tok = new byte[e - s];
                System.arraycopy(data, s, tok, 0, e - s);
                String token;
                try {
                    token = new String(tok, "GBK");
                } catch (Exception ex) {
                    token = new String(tok, java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                token = token.trim();
                if (token.length() >= 5) {
                    if (token.indexOf('\\') >= 0 || token.indexOf('/') >= 0 || token.indexOf(':') >= 0) {
                        if (track.musicPath == null) track.musicPath = token;
                        int slash = Math.max(token.lastIndexOf('\\'), token.lastIndexOf('/'));
                        String base = token.substring(slash + 1).trim();
                        if (base.length() >= 5 && track.musicName == null) track.musicName = base;
                    } else if (track.musicName == null) {
                        track.musicName = token;
                    }
                }
            }
            idx = lowerRaw.indexOf(".mp3", e);
        }
        // UTF-16LE (name segment): like r\0a\0p\0.\0m\0p\03\0
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i + 1 < data.length; i += 2) {
            int lo = data[i] & 0xFF;
            int hi = data[i + 1] & 0xFF;
            char c;
            if (hi == 0 && lo >= 0x20 && lo < 0x7F) {
                c = (char) lo;
            } else if (lo == 0 && hi == 0) {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
                continue;
            } else {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
            if (cur.length() > 260) {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
            }
        }
        finishMusicToken(cur.toString(), track);
    }

    private static void finishMusicToken(String token, UbxFile.UbxTrack track) {
        String t = token.trim();
        int n = t.toLowerCase(java.util.Locale.US).indexOf(".mp3");
        if (n < 0) return;
        t = t.substring(0, n + 4);
        int cut = Math.max(t.lastIndexOf('\0'), t.lastIndexOf(' '));
        // keep the drive letter / slash, the basename is musicName separately
        int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        if (slash >= 0 || t.indexOf(':') >= 0) {
            if (track.musicPath == null) track.musicPath = t;
            String base = t.substring(slash + 1);
            if (base.length() >= 5 && track.musicName == null) track.musicName = base;
        } else if (t.length() >= 5 && track.musicName == null) {
            track.musicName = t;
        }
    }

    /** a.h → a.c container: [echo==len][count][L1 outer][content]* (content read from the echo). */
    private static void parseTrackH(byte[] h, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(h, off) != len) throw new UbxParseException("ablock len check");
        int count = le(h, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(h, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (le(h, p) != l1) throw new UbxParseException("ablock echo");
            if (p + l1 > off + len) throw new UbxParseException("bad ablock item " + l1);
            parseBlockB(h, p, l1, out, track, voice);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("ablock slack " + (off + len - p));
    }

    /**
     * a.b block (off points at echo): [echo][a][b=start][c=end][d[30]][count]
     * [L1 outer][L1 content→a.a]*.
     * a.a servo frame (off points at echo): [echo][a][b=start][c=end][d[30]][f][e(f bytes)].
     */
    private static void parseBlockB(byte[] bl, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(bl, off) != len) throw new UbxParseException("bblock len check");
        int p = off + 4;
        p += 4; // a
        p += 4; // b=start (block level, the frame-level start is the criterion)
        p += 4; // c=end
        p += 60; // d[30]
        int count = le(bl, p); p += 4;
        for (int i = 0; i < count; i++) {
            int l1 = le(bl, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (le(bl, p) != l1) throw new UbxParseException("sframe echo");
            if (p + l1 > off + len) throw new UbxParseException("bad aframe2 len " + l1);
            parseServoFrame(bl, p, l1, out, track, voice);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("bblock slack " + (off + len - p));
    }

    /**
     * a.a frame (pos points at echo, len is L1').
     * servo: angles (a.m private send original) groups=f()/8 (cap 20);
     * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF; fewer than 20 groups, the rest stay 0.
     * voice (a/o original): the e-blob is [8B head][GBK path][8B tail], take the middle to decode the song name, no angles.
     * send time = b*timeBase (using the same track time base, backfilled uniformly after parse).
     */
    private static void parseServoFrame(byte[] f, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(f, off) != len) throw new UbxParseException("sframe len check");
        int p = off + 4;
        p += 4; // a
        int start = le(f, p); p += 4; // b=mStartTime
        int end = le(f, p); p += 4; // c=mEndTime
        p += 60; // d[30]
        int ff = le(f, p); p += 4; // f=group count byte length (for voice, the song name blob length)
        if (ff < 0 || p + ff > off + len) throw new UbxParseException("bad sframe eblob " + ff);
        if (p + ff != off + len) throw new UbxParseException("sframe slack " + (off + len - p - ff));
        int epos = p;
        UbxFile.UbxServoFrame sf = new UbxFile.UbxServoFrame();
        sf.start = start;
        sf.end = end;
        sf.baseB = start;
        sf.baseC = end;
        if (voice) {
            sf.voice = true;
            sf.groupBytes = ff;
            sf.music = extractVoiceMusic(f, epos, ff);
        } else {
            int groups = Math.min(ff / 8, 20);
            for (int i = 0; i < groups; i++) {
                int o = epos + i * 8 + 8;
                if (o + 4 > epos + ff) break;
                sf.angles20[i] = (byte) (f[o] & 0xFF);
            }
            sf.groupBytes = ff;
        }
        int base = track.timeBaseMs > 0 ? track.timeBaseMs
                : (out.timeBaseMs > 0 ? out.timeBaseMs : 0);
        sf.moveTimeHintMs = base > 0 ? start * base : start;
        track.frames.add(sf);
    }

    /**
     * voice frame song name (a/o.a() original): take the e-blob from byte 8 to the end (arraycopy(e,8,dst,0,len-8),
     * strip the head only, not the tail), GBK decode and trim, take what is after the last {@code \} or {@code /} as the basename.
     */
    private static String extractVoiceMusic(byte[] f, int epos, int eLen) {
        if (eLen <= 8) return null;
        try {
            String s = new String(f, epos + 8, eLen - 8, "GBK").trim();
            if (s.isEmpty()) return null;
            int slash = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
            String base = (slash >= 0 ? s.substring(slash + 1) : s).trim();
            return base.isEmpty() ? null : base;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * d.f keyframe table: [echo==len][count][L1 outer][L1 content→d.e leaf]*.
     * The d.e leaf is a pose reference (the original is often all zeros), stored in {@code track.keyframes}, not into {@code track.frames}.
     * Leaf absolute offsets: [0..3]=echo, [4..7]=a, [8]=b, [9..12]=c, [13..16]=d,
     * [17..20]=f, [21..21+f)=e, [21+f]=g, [22+f..61+f)=h=20×LE int16.
     */
    private static void parseFrameTable(byte[] ft, int off, int len, UbxFile out, UbxFile.UbxTrack track) throws UbxParseException {
        if (le(ft, off) != len) throw new UbxParseException("ftable len check");
        int count = le(ft, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(ft, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (l1 < 62 || p + l1 > off + len) throw new UbxParseException("bad ftable item " + l1);
            byte[] it = ft;
            int q = p;
            if (le(it, q) != l1) throw new UbxParseException("fleaf len check");
            UbxFile.UbxServoFrame sf = new UbxFile.UbxServoFrame();
            sf.leafA = le(it, q + 4);
            sf.leafB = it[q + 8] & 0xFF;
            sf.leafC = le(it, q + 9);
            sf.leafD = le(it, q + 13);
            int ff = le(it, q + 17);
            if (ff < 0 || 62 + ff > l1) throw new UbxParseException("bad fleaf blob " + ff);
            sf.leafF = ff;
            sf.leafG = it[q + 21 + ff] & 0xFF;
            for (int s = 0; s < 20; s++) {
                int o = q + 22 + ff + s * 2;
                int v = (it[o] & 0xFF) | (it[o + 1] << 8);
                sf.angles20[s] = (byte) (v & 0xFF);
            }
            sf.start = sf.leafC;
            sf.end = sf.leafD;
            sf.groupBytes = 0; // pose reference: the player only sends timed frames with groupBytes>0, here always 0 to distinguish
            track.keyframes.add(sf);
            track.leafCount++;
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("ftable slack " + (off + len - p));
    }

    private static int le(byte[] b, int p) throws UbxParseException {
        if (p < 0 || p + 4 > b.length) throw new UbxParseException("overrun at " + p);
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
    }

    /** Little-endian cursor (legacy: new code always uses the absolute-offset le(), this cursor is only used by the parse() top level). */
    static final class Cursor {
        final byte[] d;
        int pos;
        Cursor(byte[] d) { this(d, 0, 0); }
        Cursor(byte[] d, int pos, int ignored) { this.d = d; this.pos = pos; }
        int i32() throws UbxParseException {
            int v = le(d, pos);
            pos += 4;
            return v;
        }
        byte[] bytes(int n) throws UbxParseException {
            if (n < 0 || pos + n > d.length) throw new UbxParseException("overrun bytes " + n + " at " + pos);
            byte[] o = new byte[n];
            System.arraycopy(d, pos, o, 0, n);
            pos += n;
            return o;
        }
        void skip(int n) throws UbxParseException {
            if (n < 0 || pos + n > d.length) throw new UbxParseException("overrun skip " + n + " at " + pos);
            pos += n;
        }
    }
}
