package com.ubtechinc.alpha.hardware.ubx;

import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectChestController;
import com.ubtechinc.alpha.hardware.ServoPoseTracker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * .ubx motion player (pure-direct, clean-room).
 *
 * <p>Play semantics follow the 1.1.7.3 smali original:
 * <pre>
 * f/i$a.run: each round, after sleep(i), a/d.c(1) advances 1 tick, where i = a/p.f() =
 *   static timeBase (the first int of the type-0 block, unit ms; a/p.b is only the
 *   loop cap, not the period).
 * a/m.a(I): tick accumulates into b; when b &gt;= c, switch to the next frame and send
 *   immediately. Frame i slot = (b_i + c_i)
 *   tick (b()=start is the motion duration part, c()=end is the hold part); send time
 *   actual param = b_i * timeBase.
 * Send packet: chest cmd 3 [20 axis bytes + short time], angles[i] = LE32(e[i*8+8..+4]) &amp; 0xFF.
 * Example tiny.ubx: slots (20+10)+(20+40)+(20+10)=120 tick × 50ms = 6s.
 * Music (a/d type==4 → a/j/a/o, in parallel with the servo on the same unit clock):
 *   at each voice frame slot start: interrupt the previous track, start a new o$a thread
 *   playing path = ubx minus extension / music name;
 *   play at most b*timeBase (auto-stop), interrupt on frame switch / stop; pause stops
 *   the music too.
 * </pre>
 * v1 simplification:
 * <ul>
 *   <li>only play the a.d chain timed frames (servo by groupBytes&gt;0; the d.f keyframe
 *   table is not sent).</li>
 *   <li>frame order by the official chained order (proven by smali f/b.f + f/c.a: entry
 *   from the (-1,0) leaf,
 *       leaf.c selects the segment against d/a.e, v7 gate + c==-2 terminates, after a
 *   segment follows the chain by the out index;
 *       see UbxParser.computePlayOrder). Fall back to parse order when the order cannot
 *   be derived.
 *       Chain terminated (c==-2) means the whole action is done, no later track plays;
 *   multiple tracks are head to tail;
 *       a.h/a.j window offsets are ignored.</li>
 *   <li>natural end stops at the last cell: send no correction frames, play what is
 *   there (a stop() interrupt also
 *       keeps the last pose).</li>
 *   <li>monotonic deadline scheduling, cumulative drift removed; stop() interrupts both
 *   lines, the servo keeps the last pose.</li>
 *   <li>speed (0.5/0.67/1/1.5/2, sticky): the servo slot and move duration are both
 *   divided by the factor;
 *       music at 1x uses MediaPlayer (starts at once), non-1x uses decode + linear
 *   resampling "tape-style"
 *       speed (no pitch change, 0.5x low, 2x shrill, the robot API 22 has no
 *   PlaybackParams).
 *       A speed change mid-play takes effect at once, the caller stops the old and
 *   replays (a snapshot inside the player isolates the in-flight plan).</li>
 * </ul>
 * </p>
 */
public final class UbxPlayer {
    private static final String TAG = "UbxPlayer";
    private static final int MIN_TIME_MS = 20;
    private static final int MAX_TIME_MS = 30000;

    private Thread playThread;
    private Thread voiceThread;
    private volatile boolean stopReq;
    private volatile boolean voiceStopReq;
    private volatile String currentName = "";
    private volatile int framesSent;
    private volatile int framesTotal;
    private volatile String lastError = "";
    private volatile String voiceSong = "";
    private android.media.MediaPlayer voicePlayer;
    private volatile VoiceStream curStream;
    /** Command pose (the 20 axes of the last successful cmd3; unknown if not moved since boot). */
    private final ServoPoseTracker poseTracker = new ServoPoseTracker();

    /** Copy of the current command pose; returns null when unknown (the caller must report unknown as-is). */
    public int[] pose() { return poseTracker.snapshot(); }

    public boolean poseKnown() { return poseTracker.isKnown(); }

    /** Sync the pose after an external cmd3 send succeeds (e.g. servo/all direct send). */
    public void notePose(int[] angles20) { poseTracker.update(angles20); }

    /** Sync that one axis pose after an external cmd05 single send succeeds (e.g. servo/one direct send). */
    public void notePoseOne(int id, int angle) { poseTracker.updateOne(id, angle); }

    /** Per-axis pose (an unknown axis is null; used by the servoAngleAllResponse command pose board). */
    public Integer[] poseBoxed() { return poseTracker.snapshotBoxed(); }

    /** For the stream path: mark the current song name (for status), equivalent to startVoiceLocked. */
    private synchronized void setVoiceSong(String s) { voiceSong = s != null ? s : ""; }
    /** Speed factor (sticky, applies to the next play; a change mid-play only affects the next). */
    private volatile float speed = 1.0f;

    private static final float[] SPEEDS = new float[]{0.5f, 0.67f, 1.0f, 1.5f, 2.0f};

    /** Set speed (only 0.5/0.67/1/1.5/2 are legal); while playing, the caller replays for it to take effect at once, returns whether accepted. */
    public synchronized boolean setSpeed(float s) {
        for (float v : SPEEDS) {
            if (Math.abs(v - s) < 0.001f) {
                speed = v;
                Log.i(TAG, "speed set " + v + "x (next play)");
                return true;
            }
        }
        return false;
    }

    public float getSpeed() { return speed; }

    /** One frame's play item: follow the official leaf-order frame list, the slot and send duration are computed separately per the a.m original. */
    private static final class Slot {
        final UbxFile.UbxServoFrame sf;
        final long slotMs;  // (start+end) * T: this frame's slot
        final int moveMs;   // start * timeBase: cmd3 time actual param (the parser pre-computed moveTimeHintMs)
        Slot(UbxFile.UbxServoFrame sf, long slotMs, int moveMs) {
            this.sf = sf; this.slotMs = slotMs; this.moveMs = moveMs;
        }
    }

    /** voice play item: schedule in parallel on the same clock, start/auto-stop per the a/o original. */
    private static final class VSong {
        final long startMs; // this slot's start (offset under the shared clock)
        final long boundMs; // b*timeBase: mp3 auto-stop upper bound
        final File file;    // the resolved local mp3 (null means the file is missing, the slot still runs)
        final String name;
        VSong(long startMs, long boundMs, File file, String name) {
            this.startMs = startMs; this.boundMs = boundMs; this.file = file; this.name = name;
        }
    }

    /** T fallback: use track.timeBaseMs when &gt; 0; else the file timeBase; else 50. */
    static int resolveTickMs(UbxFile ubx, UbxFile.UbxTrack t) {
        int base = t != null ? t.timeBaseMs : -1;
        if (base <= 0 && ubx != null) base = ubx.timeBaseMs;
        return base > 0 ? base : 50;
    }

    /**
     * Official leaf-order frame list (proven by smali f/b.f, see UbxParser.computePlayOrder).
     * playOrder==null (no leaf list) first falls back to parse order, behavior same as the old version;
     * an empty table = the official says do not play, follow by playing zero cells.
     */
    static List<UbxFile.UbxServoFrame> orderedFrames(UbxFile.UbxTrack t) {
        if (t == null) return new ArrayList<UbxFile.UbxServoFrame>();
        if (t.playOrder == null) return t.frames;
        List<UbxFile.UbxServoFrame> out = new ArrayList<>();
        for (int idx : t.playOrder) {
            if (idx < 0 || idx >= t.daSpans.size()) continue;
            int[] span = t.daSpans.get(idx);
            if (span == null || span.length < 2) continue;
            int s = Math.max(0, span[0]);
            int e = Math.min(t.frames.size(), span[1]);
            for (int i = s; i < e; i++) out.add(t.frames.get(i));
        }
        return out;
    }

    /**
     * Music file resolution (a/o.b(I) original): path = ubx minus extension / music name.
     * An exact music hit wins; when not found, fall back to the first *.mp3 in the
     * directory; no music returns null (the slot still runs).
     */
    static File resolveVoiceFile(File ubxFile, String base, String music) {
        if (ubxFile == null || music == null || music.isEmpty()) return null;
        File parent = ubxFile.getParentFile();
        if (parent == null) return null;
        File dir = base != null && !base.isEmpty() ? new File(parent, base) : parent;
        if (dir.isDirectory()) {
            File hit = new File(dir, music);
            if (hit.isFile()) return hit;
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".mp3")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Start playing (returns false if already playing). ubxFile is for music addressing (null means no sound). */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest, File ubxFile) {
        if ((playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive())) {
            lastError = "already playing " + currentName;
            return false;
        }
        final float sp = speed; // snapshot for this play; a mid-play speed change does not affect the one playing
        List<Slot> seq = new ArrayList<>();
        List<VSong> vsongs = new ArrayList<>();
        String base = name != null && name.endsWith(".ubx") ? name.substring(0, name.length() - 4) : name;
        if (ubx != null) {
            long voiceElapsed = 0;
            for (UbxFile.UbxTrack t : ubx.tracks) {
                int tickMs = resolveTickMs(ubx, t);
                for (UbxFile.UbxServoFrame sf : orderedFrames(t)) {
                    if (sf.voice) {
                        if (ubxFile == null) continue;
                        String music = sf.music != null ? sf.music : t.musicName;
                        File mf = resolveVoiceFile(ubxFile, base, music);
                        // The music auto-stop upper bound is not clamped to 30000 (that
                        // is the chest protocol move clamp; a song can run for minutes,
                        // clamping it would cut it short - a 69s bound at 0.67x was once
                        // wrongly killed to 30s).
                        long boundMs = scaleTime((long) sf.moveTimeHintMs, sp);
                        long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                        vsongs.add(new VSong(voiceElapsed, boundMs, mf,
                                mf != null ? mf.getName() : (music != null ? music : "")));
                        voiceElapsed += slotMs;
                        continue;
                    }
                    if (sf.groupBytes <= 0) continue; // d.f keyframe table leaf: pose reference only, not sent
                    int moveMs = scaleTime(sf.moveTimeHintMs, sp);
                    long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                    seq.add(new Slot(sf, slotMs, moveMs));
                }
                // Chain terminated (c==-2, smali f/c.a completed branch): the whole
                // action is done,
                // no later track plays - the forward/reverse step-retraction segment is
                // skipped that way (proven by the real machine video).
                if (t.chainTerminated) {
                    Log.i(TAG, "play " + name + " chain terminated after track "
                            + t.id + " (skipping later tracks)");
                    break;
                }
            }
        }
        if (seq.isEmpty() && vsongs.isEmpty()) {
            lastError = "no timed frames";
            return false;
        }
        if (!seq.isEmpty() && (chest == null || !chest.isAvailable())) {
            lastError = "chest not available";
            return false;
        }
        stopReq = false;
        voiceStopReq = false;
        framesSent = 0;
        framesTotal = seq.size();
        currentName = name != null ? name : "";
        lastError = "";
        voiceSong = "";
        final List<Slot> plan = seq;
        final List<VSong> vplan = vsongs;
        final DirectChestController c = chest;
        if (!plan.isEmpty()) {
            playThread = new Thread(new Runnable() {
                @Override public void run() { loop(plan, c); }
            }, "ubx-play");
            playThread.start();
        }
        if (!vplan.isEmpty()) {
            final float vsp = sp; // the music and servo use the same snapshot: a speed change before the voice line starts keeps audio and video in sync
            voiceThread = new Thread(new Runnable() {
                @Override public void run() { voiceLoop(vplan, vsp); }
            }, "ubx-voice");
            voiceThread.start();
        }
        long totalMs = 0;
        for (Slot s : plan) totalMs += s.slotMs;
        Log.i(TAG, "play start " + currentName + " frames=" + framesTotal
                + " vsongs=" + vplan.size() + " totalMs=" + totalMs + " speed=" + sp + "x");
        return true;
    }

    /** Speed scaling: long core (for music bound/slot, not clamped); the int version adds the [20,30000] clamp to protect the chest protocol. */
    private static long scaleTime(long ms, float sp) {
        long v = Math.round(ms / (double) sp);
        return v < 0 ? 0 : v;
    }

    private static int scaleTime(int ms, float sp) {
        long v = scaleTime((long) ms, sp);
        if (v < MIN_TIME_MS) v = MIN_TIME_MS;
        if (v > MAX_TIME_MS) v = MAX_TIME_MS;
        return (int) v;
    }

    /** Compatible with the old three-param (no-sound version, music disabled). */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest) {
        return play(ubx, name, chest, null);
    }

    private void loop(List<Slot> plan, DirectChestController chest) {
        long monoStart = System.nanoTime() / 1000000L;
        long elapsed = 0;
        try {
            for (Slot s : plan) {
                if (stopReq || Thread.currentThread().isInterrupted()) break;
                int[] angles = new int[20];
                for (int i = 0; i < 20; i++) angles[i] = s.sf.angles20[i] & 0xFF;
                try {
                    if (chest.playAllServos(angles, (short) s.moveMs)) {
                        poseTracker.update(angles); // command pose tracking (record only on a successful send)
                    }
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e.getMessage());
                }
                framesSent++;
                elapsed += s.slotMs; // next slot boundary (monotonic, does not drift with send time)
                long wait = monoStart + elapsed - System.nanoTime() / 1000000L;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            // A natural end sends no extra pose: stop at the .ubx last cell, play what
            // is there
            // (a HOME was once tried to close out, the real machine proved it made a
            // cross hand, so the whole thing is dropped;
            // to stand straight, manually replay crouch-stand-up / SERVO all-group
            // to center).
        } finally {
            Log.i(TAG, "play end " + currentName + " sent=" + framesSent + "/" + framesTotal);
        }
    }

    private void voiceLoop(List<VSong> plan, float sp) {
        long monoStart = System.nanoTime() / 1000000L;
        try {
            for (int i = 0; i < plan.size(); i++) {
                if (voiceStopReq || Thread.currentThread().isInterrupted()) break;
                VSong v = plan.get(i);
                long wait = monoStart + v.startMs - System.nanoTime() / 1000000L;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (voiceStopReq || Thread.currentThread().isInterrupted()) break;
                long nextStart = (i + 1 < plan.size()) ? plan.get(i + 1).startMs : Long.MAX_VALUE;
                long playUntil = Math.min(v.startMs + v.boundMs, nextStart); // auto-stop or interrupted by the next track
                if (v.file == null || !v.file.isFile()) {
                    Log.w(TAG, "voice missing: " + v.name);
                } else if (sp == 1.0f) {
                    startVoiceLocked(v);
                } else {
                    VoiceStream vs = new VoiceStream();
                    curStream = vs;
                    setVoiceSong(v.name);
                    Log.i(TAG, "voice stream " + v.name + " " + sp + "x");
                    try {
                        vs.play(v.file, sp, monoStart + playUntil, new VoiceStream.StopFlag() {
                            @Override public boolean isStopped() {
                                return voiceStopReq || Thread.currentThread().isInterrupted();
                            }
                        });
                    } finally {
                        curStream = null;
                        setVoiceSong("");
                    }
                    continue; // the stream path already handled auto-stop/interrupt, go straight to the next
                }
                long wait2 = monoStart + playUntil - System.nanoTime() / 1000000L;
                if (wait2 > 0) {
                    try {
                        Thread.sleep(wait2);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                stopVoiceLocked();
            }
        } finally {
            stopVoiceLocked();
            Log.i(TAG, "voice end " + currentName);
        }
    }

    /** Start a track (interrupt the previous; prepare is synchronous, no looper requirement, same as o$a). */
    private synchronized void startVoiceLocked(VSong v) {
        stopVoiceLocked();
        voiceSong = "";
        if (v.file == null || !v.file.isFile()) {
            Log.w(TAG, "voice missing: " + v.name);
            return;
        }
        android.media.MediaPlayer mp = null;
        try {
            mp = new android.media.MediaPlayer();
            mp.setDataSource(v.file.getAbsolutePath());
            final android.media.MediaPlayer self = mp;
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mpc) {
                    synchronized (UbxPlayer.this) {
                        // release only the one still in use; the old track already
                        // released by stopVoiceLocked
                        // is not touched again (prevents a double release native crash),
                        // if already replaced by a new track, do not wrongly kill the new
                        // player.
                        if (voicePlayer == self) {
                            try { self.release(); } catch (Exception ignore) {
                                // ignore
                            }
                            voicePlayer = null;
                            voiceSong = "";
                        }
                    }
                }
            });
            mp.prepare();
            mp.start();
            voicePlayer = mp;
            mp = null; // handed off, catch does not release again
            voiceSong = v.name;
            Log.i(TAG, "voice start " + v.name);
        } catch (Exception e) {
            Log.w(TAG, "voice failed " + v.name + ": " + e.getMessage());
            if (mp != null) {
                try { mp.release(); } catch (Exception ignore) {
                    // if prepare/start blew up without release, a native mediaplayer fd leaks
                }
            }
        }
    }

    private synchronized void stopVoiceLocked() {
        VoiceStream vs = curStream;
        curStream = null;
        if (vs != null) vs.abort();
        voiceSong = "";
        if (voicePlayer != null) {
            try {
                voicePlayer.stop();
            } catch (Exception ignored) {
                // mid-state race, release anyway, swallow it.
            }
            try {
                voicePlayer.release();
            } catch (Exception ignored) {
                // ignore
            }
            voicePlayer = null;
        }
    }

    /** join cannot be done inside the lock: the voiceLoop tail finally and OnCompletion
     * both need the same lock,
     * a join while holding the lock deadlocks with the finishing thread (a voiced stop
     * hangs the full 2s). Snapshot first, unlock and join, then come back to clean up. */
    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException ignore) {
            // ignore
        }
    }

    /** Stop playing (interrupt both lines, stop the music, the servo keeps the last pose). */
    public void stop() {
        final Thread pt;
        final Thread vt;
        synchronized (this) {
            stopReq = true;
            voiceStopReq = true;
            // abort the stream first (when AudioTrack.write blocks, interrupt cannot wake it, track.stop is needed to loosen it), then join.
            if (playThread != null) playThread.interrupt();
            if (voiceThread != null) voiceThread.interrupt();
            stopVoiceLocked();
            pt = playThread;
            vt = voiceThread;
        }
        joinQuietly(pt);
        joinQuietly(vt);
        synchronized (this) {
            if (playThread == pt) playThread = null;
            if (voiceThread == vt) voiceThread = null;
            stopVoiceLocked();
        }
    }

    /** Stop only the music (the local-music sound-steal scenario), the servo continues. join also cannot be inside the lock (see stop). */
    public void stopVoice() {
        final Thread vt;
        synchronized (this) {
            voiceStopReq = true;
            if (voiceThread != null) voiceThread.interrupt();
            stopVoiceLocked();
            vt = voiceThread;
        }
        joinQuietly(vt);
        synchronized (this) {
            if (voiceThread == vt) voiceThread = null;
            stopVoiceLocked();
        }
    }

    public synchronized boolean isPlaying() {
        return (playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive());
    }

    public int sent() { return framesSent; }

    public int total() { return framesTotal; }

    public String name() { return currentName; }

    public String voice() { return voiceSong; }

    public String lastError() { return lastError; }

    /** JSON used directly by /api/ubx/status (the caller wraps one ok layer and it is done). */
    public synchronized String statusJson() {
        boolean playing = (playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive());
        return "{\"ok\":true,\"playing\":" + playing
                + ",\"name\":\"" + esc(currentName) + "\""
                + ",\"sent\":" + framesSent + ",\"total\":" + framesTotal
                + ",\"voice\":\"" + esc(voiceSong) + "\""
                + ",\"speed\":" + speed
                + ",\"lastError\":\"" + esc(lastError) + "\"}";
    }

    private static String esc(String s) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
