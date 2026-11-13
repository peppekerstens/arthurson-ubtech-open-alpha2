package com.theeasiestway.opus;

/**
 * A pure Java replacement for the Constants class
 * (com.theeasiestway.opus.Constants) that the Kotlin compiler produces inside the
 * opus.aar of theeasiestway/android-opus-codec. It has no dependency on the Kotlin
 * runtime (in the original aar version, SampleRate/Channels/Application/FrameSize
 * and the like are all Kotlin inline value classes, each internally wrapping a
 * single int field "v". Here they are flattened to plain int. The effect is exactly
 * the same, but kotlin-stdlib is not needed).
 *
 * 2026-08: this project fully removed opus.aar and switched to compiling the
 * easyopus-jni-src source code itself with CMake (see app/src/main/cpp/
 * CMakeLists.txt). The classes.jar of the original aar had no source code, only
 * compiled bytecode. So this class was reverse-engineered from the constant-pool /
 * method signatures of classes.jar, and then cross-checked against the five
 * factory calls actually used in XiaozhiAudioController.java
 * (SampleRate._16000() / Channels.mono() / Application.voip() /
 * FrameSize._960()).
 *
 * Source of each value:
 *   - SampleRate: the raw Hz value directly accepted by opus_encoder_init()/
 *     opus_decoder_init() (opus.h: it must be one of 8000/12000/16000/24000/48000).
 *   - Channels: 1 = mono, 2 = stereo (the opus.h standard).
 *   - Application: the OPUS_APPLICATION_* macro values, from the official
 *     opus_defines.h (VOIP=2048, AUDIO=2049, RESTRICTED_LOWDELAY=2051).
 *   - FrameSize: samples per frame (not ms), back-calculated from
 *     SAMPLES_PER_FRAME = 16000Hz * 60ms / 1000 = 960 in XiaozhiAudioController.
 *
 * This project uses only the four kinds SampleRate/Channels/Application/FrameSize,
 * and does not use the Bitrate/Complexity factories (XiaozhiAudioController does
 * not call encoderSetBitrate()/encoderSetComplexity()). But to keep the shape of
 * the original API consistent, both of these are filled in as well.
 */
public final class Constants {

    private Constants() {}

    // ================= SampleRate =================

    public static final class SampleRate {
        public final int v;
        private SampleRate(int v) { this.v = v; }

        public static SampleRate _8000() { return new SampleRate(8000); }
        public static SampleRate _12000() { return new SampleRate(12000); }
        public static SampleRate _16000() { return new SampleRate(16000); }
        public static SampleRate _24000() { return new SampleRate(24000); }
        public static SampleRate _48000() { return new SampleRate(48000); }

        /** Keep the ".Companion" layer, to stay compatible with the old call site
         *  (Constants.SampleRate.Companion._16000()), without having to change the
         *  way calls are written in XiaozhiAudioController.java. */
        public static final class Companion {
            private Companion() {}
            public static SampleRate _8000() { return SampleRate._8000(); }
            public static SampleRate _12000() { return SampleRate._12000(); }
            public static SampleRate _16000() { return SampleRate._16000(); }
            public static SampleRate _24000() { return SampleRate._24000(); }
            public static SampleRate _48000() { return SampleRate._48000(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Channels =================

    public static final class Channels {
        public final int v;
        private Channels(int v) { this.v = v; }

        public static Channels mono() { return new Channels(1); }
        public static Channels stereo() { return new Channels(2); }

        public static final class Companion {
            private Companion() {}
            public static Channels mono() { return Channels.mono(); }
            public static Channels stereo() { return Channels.stereo(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Application =================

    public static final class Application {
        public final int v;
        private Application(int v) { this.v = v; }

        /** OPUS_APPLICATION_VOIP - optimized for voice. This project (the XiaoZhi
         *  voice assistant) uses this. */
        public static Application voip() { return new Application(2048); }
        /** OPUS_APPLICATION_AUDIO - optimized for music/general audio. */
        public static Application audio() { return new Application(2049); }
        /** OPUS_APPLICATION_RESTRICTED_LOWDELAY - the lowest latency, at the cost
         *  of voice optimization. */
        public static Application restrictedLowdelay() { return new Application(2051); }

        public static final class Companion {
            private Companion() {}
            public static Application voip() { return Application.voip(); }
            public static Application audio() { return Application.audio(); }
            public static Application restrictedLowdelay() { return Application.restrictedLowdelay(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= FrameSize =================
    // The values are samples per frame (the number of samples per channel), not ms.
    // These are 60 ms converted at different sample rates, and they also include
    // the common 2.5/5/10/20/40 ms steps (the legal frame durations defined in
    // opus.h). This project uses only _960() (16kHz * 60ms). The rest are filled in
    // to keep the shape of the API complete.

    public static final class FrameSize {
        public final int v;
        private FrameSize(int v) { this.v = v; }

        public static FrameSize _120() { return new FrameSize(120); }
        public static FrameSize _160() { return new FrameSize(160); }
        public static FrameSize _240() { return new FrameSize(240); }
        public static FrameSize _320() { return new FrameSize(320); }
        public static FrameSize _480() { return new FrameSize(480); }
        public static FrameSize _640() { return new FrameSize(640); }
        public static FrameSize _960() { return new FrameSize(960); }
        public static FrameSize _1920() { return new FrameSize(1920); }
        public static FrameSize _2880() { return new FrameSize(2880); }

        public static final class Companion {
            private Companion() {}
            public static FrameSize _120() { return FrameSize._120(); }
            public static FrameSize _160() { return FrameSize._160(); }
            public static FrameSize _240() { return FrameSize._240(); }
            public static FrameSize _320() { return FrameSize._320(); }
            public static FrameSize _480() { return FrameSize._480(); }
            public static FrameSize _640() { return FrameSize._640(); }
            public static FrameSize _960() { return FrameSize._960(); }
            public static FrameSize _1920() { return FrameSize._1920(); }
            public static FrameSize _2880() { return FrameSize._2880(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Bitrate =================
    // This project does not use it (XiaozhiAudioController does not call
    // encoderSetBitrate()). It is filled in only to make the shape of the API
    // complete.

    public static final class Bitrate {
        public final int v;
        private Bitrate(int v) { this.v = v; }

        /** OPUS_AUTO */
        public static Bitrate auto() { return new Bitrate(-1000); }
        /** OPUS_BITRATE_MAX */
        public static Bitrate max() { return new Bitrate(-1); }
        public static Bitrate instance(int bps) { return new Bitrate(bps); }

        public static final class Companion {
            private Companion() {}
            public static Bitrate auto() { return Bitrate.auto(); }
            public static Bitrate max() { return Bitrate.max(); }
            public static Bitrate instance(int bps) { return Bitrate.instance(bps); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Complexity =================
    // This project does not use it. It is filled in only to make the shape of the
    // API complete.

    public static final class Complexity {
        public final int v;
        private Complexity(int v) { this.v = v; }

        public static Complexity instance(int level) { return new Complexity(level); }

        public static final class Companion {
            private Companion() {}
            public static Complexity instance(int level) { return Complexity.instance(level); }
        }
        public static final Companion Companion = new Companion();
    }
}
