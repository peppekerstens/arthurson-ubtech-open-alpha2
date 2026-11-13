package com.theeasiestway.opus;

/**
 * A pure Java replacement for the com.theeasiestway.opus.Opus class that the
 * Kotlin compiler produces inside the opus.aar of theeasiestway/android-opus-codec.
 * The native method signatures are checked one by one against the JNI function
 * names/signatures (Java_com_theeasiestway_opus_Opus_xxx) in
 * app/src/main/cpp/easyopus.cpp. Not a single character has been changed, so the
 * JNI binding is guaranteed to line up.
 *
 * This project compiles the easyopus-jni-src source code itself with CMake to
 * produce libeasyopus.so (see app/src/main/cpp/CMakeLists.txt). This class is the
 * Java entry point corresponding to that native library.
 *
 * Here, the encode()/decode() of both sets of public overloads (the byte[] version
 * and the short[] version) are exposed directly. This is because, based on the
 * javap/constant-pool analysis previously done on the compiled opus.aar for this
 * project, the "raw int frameSize" overload of the (short[], int) kind inside
 * easyopus.cpp is private (an internal helper) in the original aar. The public
 * API must use the (byte[]/short[], Constants.FrameSize) signature.
 *
 * The native signature of encode() itself has no fec parameter, so the public
 * encode(..., FrameSize) is the final form. The native signature of decode() does
 * have fec, but XiaozhiAudioController.java actually only uses the two-argument
 * call form decode(shorts, FRAME_SIZE) that does not pass fec. So, below, a
 * two-argument public overload is added to decode() with fec defaulting to 0,
 * matching the signature that the original aar must have.
 */
public class Opus {

    static {
        System.loadLibrary("easyopus");
    }

    public Opus() {}

    // ================= Encoding =================

    public int encoderInit(Constants.SampleRate sampleRate, Constants.Channels channels, Constants.Application application) {
        return encoderInit(sampleRate.v, channels.v, application.v);
    }
    private native int encoderInit(int sampleRate, int numChannels, int application);

    public int encoderSetBitrate(Constants.Bitrate bitrate) {
        return encoderSetBitrate(bitrate.v);
    }
    private native int encoderSetBitrate(int bitrate);

    public int encoderSetComplexity(Constants.Complexity complexity) {
        return encoderSetComplexity(complexity.v);
    }
    private native int encoderSetComplexity(int complexity);

    public byte[] encode(byte[] bytes, Constants.FrameSize frameSize) {
        return encode(bytes, frameSize.v);
    }
    private native byte[] encode(byte[] bytes, int frameSize);

    public short[] encode(short[] shorts, Constants.FrameSize frameSize) {
        return encode(shorts, frameSize.v);
    }
    private native short[] encode(short[] shorts, int frameSize);

    public native void encoderRelease();

    // ================= Decoding =================

    public int decoderInit(Constants.SampleRate sampleRate, Constants.Channels channels) {
        return decoderInit(sampleRate.v, channels.v);
    }
    private native int decoderInit(int sampleRate, int numChannels);

    public byte[] decode(byte[] bytes, Constants.FrameSize frameSize, int fec) {
        return decode(bytes, frameSize.v, fec);
    }
    /** The public overload with fec defaulting to 0 (no forward error
     *  correction) - XiaozhiAudioController.java has always used this
     *  two-argument version. */
    public byte[] decode(byte[] bytes, Constants.FrameSize frameSize) {
        return decode(bytes, frameSize.v, 0);
    }
    private native byte[] decode(byte[] bytes, int frameSize, int fec);

    public short[] decode(short[] shorts, Constants.FrameSize frameSize, int fec) {
        return decode(shorts, frameSize.v, fec);
    }
    /** Same as above, the short[] two-argument overload. */
    public short[] decode(short[] shorts, Constants.FrameSize frameSize) {
        return decode(shorts, frameSize.v, 0);
    }
    private native short[] decode(short[] shorts, int frameSize, int fec);

    public native void decoderRelease();

    // ================= Utils =================

    public native short[] convert(byte[] bytes);
    public native byte[] convert(short[] shorts);
}
