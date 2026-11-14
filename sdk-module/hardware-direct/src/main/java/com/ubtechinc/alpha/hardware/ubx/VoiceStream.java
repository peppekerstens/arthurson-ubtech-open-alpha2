package com.ubtechinc.alpha.hardware.ubx;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Speed music streaming (decode + linear resampling + AudioTrack, API 19 usable API surface).
 *
 * <p>Background: the robot Android 5.1 (API 22), {@code MediaPlayer.setPlaybackParams}
 * needs API 23+, no speed capability. This implements speed per the official music
 * semantics (slot start, play at most b*timeBase,
 * frame-switch interrupt): MediaExtractor + MediaCodec decode mp3 → PCM,
 * linear interpolation resampling by step {@code speed} (speed without pitch change is
 * Sonic/WSOLA-level work,
 * this locks pitch to the speed, i.e. "tape-style" speed, noted here),
 * out through AudioTrack(STREAM_MUSIC).</p>
 *
 * <p>Streaming ring input (keep about 0.4s of decoded frames), do not load the whole
 * song into memory; output is written in small chunks,
 * stop delay is at most one chunk (about 50ms). The MediaCodec APIs used (getInputBuffers
 * etc.)
 * are all API 16+, safe on minSdk 19.</p>
 */
final class VoiceStream {
    private static final String TAG = "VoiceStream";
    private static final int RING_FRAMES = 16384;
    private static final int OUT_CHUNK_FRAMES = 2048;

    /** Stop request visible to another thread (set by UbxPlayer.stop/stopVoice). */
    interface StopFlag { boolean isStopped(); }

    private volatile boolean abort;
    private volatile AudioTrack liveTrack;

    void abort() {
        abort = true;
        AudioTrack t = liveTrack;
        if (t != null) {
            try { t.stop(); } catch (Exception ignore) {
                // ignore
            }
        }
    }

    /**
     * Play file at speed speed (= the input step per output frame, 2x takes one of
     * every 2 frames),
     * until natural end / reaching deadlineMonoMs / stop, whichever comes first, then
     * close out (stop+release all off).
     * Blocking call, designed to run on UbxPlayer's voice thread.
     */
    void play(File file, float speed, long deadlineMonoMs, StopFlag stop) {
        abort = false;
        MediaExtractor ex = null;
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(file.getAbsolutePath());
            int audioIdx = -1;
            MediaFormat srcFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioIdx = i;
                    srcFmt = f;
                    break;
                }
            }
            if (audioIdx < 0 || srcFmt == null) {
                Log.w(TAG, "no audio track: " + file.getName());
                return;
            }
            String mime = srcFmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(audioIdx);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(srcFmt, null, null, 0);
            codec.start();
            int sampleRate = srcFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = srcFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (channels < 1) channels = 1;
            if (channels > 2) channels = 2;
            // A malformed mp3 (sampleRate 0 / a wild value) must not reach new AudioTrack to blow up, reject early.
            if (sampleRate < 8000 || sampleRate > 48000) {
                Log.w(TAG, "bad sample rate " + sampleRate + ": " + file.getName());
                return;
            }
            int chCfg = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, 65536);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chCfg,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
            liveTrack = track;
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "audiotrack init failed");
                return;
            }
            track.play();
            Log.i(TAG, "streaming " + file.getName() + " " + speed + "x sr=" + sampleRate + " ch=" + channels);
            String why = pump(ex, codec, track, channels, speed, deadlineMonoMs, stop);
            Log.i(TAG, "stream end " + file.getName() + " " + why);
        } catch (Exception e) {
            Log.w(TAG, "voice stream failed " + file.getName() + ": " + e.getMessage());
        } finally {
            liveTrack = null;
            if (track != null) {
                try { track.stop(); } catch (Exception ignore) {
                    // ignore
                }
                try { track.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignore) {
                    // ignore
                }
                try { codec.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
            if (ex != null) {
                try { ex.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    /** @return end reason: eos (natural end) / bound (b*timeBase auto-stop) / stopped (interrupted) / track-dead. */
    private String pump(MediaExtractor ex, MediaCodec codec, AudioTrack track, int channels,
                        float speed, long deadlineMonoMs, StopFlag stop) {
        ByteBuffer[] inBufs = codec.getInputBuffers();
        ByteBuffer[] outBufs = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] ring = new short[RING_FRAMES * channels];
        int ringStart = 0; // consumed absolute frame count (only added, never reduced; data in the array has moved forward, the write position uses the ringAvail relative index)
        int ringAvail = 0; // valid input frames in the ring (relative index 0..ringAvail-1)
        boolean inputEos = false;
        boolean outEosSeen = false;
        double pos = 0; // the input frame position (fraction) for the current output frame
        short[] out = new short[OUT_CHUNK_FRAMES * channels];
        int stallRounds = 0; // consecutive no-progress count: a bad song must not idle to the bound (can be minutes)

        while (true) {
            if (abort || stop.isStopped()) return "stopped";
            if (System.nanoTime() / 1000000L >= deadlineMonoMs) return "bound"; // b*timeBase auto-stop
            // feed input (stop feeding when the ring is nearly full, to avoid overflow
            // dropping the tail at low speed; the free space looks only at ringAvail,
            // ringStart is the absolute consumed count, cannot be used as an index)
            if (!inputEos && (RING_FRAMES - ringAvail) > 4096) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer ib = inBufs[ii];
                    int n = ex.readSampleData(ib, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            // collect output → ring
            boolean progressed = false;
            for (int k = 0; k < 4; k++) {
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    progressed = true;
                    if (info.size > 0) {
                        ByteBuffer ob = outBufs[oi];
                        int frames = info.size / (2 * channels);
                        int space = RING_FRAMES - ringAvail;
                        if (frames > space) frames = space; // drop the tail when the ring is full (only happens at extreme low speed)
                        ob.position(info.offset);
                        for (int f = 0; f < frames; f++) {
                            for (int c = 0; c < channels; c++) {
                                ring[ringAvail * channels + c] = ob.getShort();
                            }
                            ringAvail++;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEosSeen = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = codec.getOutputBuffers();
                } else {
                    break; // TRY_AGAIN_LATER or FORMAT_CHANGED (mp3 uses the same params throughout, keep the initial value)
                }
            }
            // consume: produce one chunk of output by linear interpolation
            int produced = 0;
            while (produced < OUT_CHUNK_FRAMES) {
                int need = (int) Math.floor(pos) + 2 - ringStart; // relative to the ring head
                if (need > ringAvail) {
                    if (outEosSeen && ringAvail > 0 && pos < ringStart + ringAvail) {
                        // tail frame: hold with the last frame
                    } else if (outEosSeen) {
                        break;
                    } else {
                        break; // wait for more input
                    }
                }
                int i0 = (int) Math.floor(pos);
                double frac = pos - i0;
                int r0 = i0 - ringStart;
                int r1 = r0 + 1;
                if (r0 < 0) { r0 = 0; frac = 0; }
                if (r1 >= ringAvail) r1 = ringAvail - 1;
                for (int c = 0; c < channels; c++) {
                    double v = ring[r0 * channels + c] * (1 - frac) + ring[r1 * channels + c] * frac;
                    out[produced * channels + c] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                }
                produced++;
                pos += speed;
                // tidy the ring: move forward when half is consumed
                int consumed = (int) Math.floor(pos) - ringStart;
                if (consumed > RING_FRAMES / 2) {
                    int remain = ringAvail - consumed;
                    if (remain > 0) {
                        System.arraycopy(ring, consumed * channels, ring, 0, remain * channels);
                    }
                    ringStart += consumed;
                    ringAvail = Math.max(0, remain);
                }
            }
            if (produced > 0) {
                stallRounds = 0; // sound out means progress, clear the count
                byte[] bytes = new byte[produced * channels * 2];
                for (int i = 0; i < produced * channels; i++) {
                    bytes[i * 2] = (byte) (out[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((out[i] >> 8) & 0xFF);
                }
                int off = 0;
                while (off < bytes.length) {
                    if (abort || stop.isStopped()) return "stopped";
                    int w = track.write(bytes, off, bytes.length - off);
                    if (w < 0) return "track-dead"; // track failed
                    if (w == 0) {
                        try { Thread.sleep(5); } catch (InterruptedException e) { return "stopped"; }
                        continue;
                    }
                    off += w;
                }
            } else if (outEosSeen && pos >= ringStart + ringAvail) {
                return "eos"; // natural end
            } else if (!progressed) {
                // Input not EOS but no progress for hundreds of rounds (decoder
                // stuck / bad file): exit early, do not idle.
                // A normal play really has progress (each round waits at most 4×10ms
                // dequeue + 5ms sleep),
                // 400 rounds ≈ 20s, far beyond normal jitter.
                if (++stallRounds > 400) return "stalled";
                try { Thread.sleep(5); } catch (InterruptedException e) { return "stopped"; }
            } else {
                stallRounds = 0;
            }
        }
    }
}
