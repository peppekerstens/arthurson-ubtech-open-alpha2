/*
 * alpha2mic: raw ALSA capture of the 5-mic FPGA sound card (card 0, "RK-FPGA-I2S").
 *
 * The Android audio HAL of this robot does not expose the FPGA card (AudioRecord reads
 * the USB C-Media CM108B card 3). The stock UBTECH app (alpha2services, iFlytek
 * AlsaRecorder + libalsa-jni.so) read /dev/snd/pcmC0D0c directly at 64000 Hz.
 * ueventd sets that node to mode 0777, so a normal app can open it without root.
 *
 * This file uses the kernel ALSA ioctls only (no tinyalsa on the robot).
 * Format: 2 channels, S16_LE, interleaved. The Java side (MultiMicController)
 * de-interleaves the 8 TDM slots.
 *
 * The device opens with O_NONBLOCK. A read waits with poll() for at most the timeout
 * of the caller. Thus a stopped FPGA clock cannot block the capture thread for all
 * time, and the thread can close the device (alpha2-brain#27).
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>
#include <android/log.h>

#define TAG "alpha2mic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static struct snd_interval *ival(struct snd_pcm_hw_params *p, int n) {
    return &p->intervals[n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
}

static struct snd_mask *pmask(struct snd_pcm_hw_params *p, int n) {
    return &p->masks[n - SNDRV_PCM_HW_PARAM_FIRST_MASK];
}

static void param_any(struct snd_pcm_hw_params *p) {
    int n;
    memset(p, 0, sizeof(*p));
    for (n = SNDRV_PCM_HW_PARAM_FIRST_MASK; n <= SNDRV_PCM_HW_PARAM_LAST_MASK; n++)
        memset(pmask(p, n), 0xff, sizeof(struct snd_mask));
    for (n = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; n <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL; n++) {
        ival(p, n)->min = 0;
        ival(p, n)->max = ~0u;
    }
    p->rmask = ~0u;
    p->info = ~0u;
}

static void set_int(struct snd_pcm_hw_params *p, int n, unsigned v) {
    ival(p, n)->min = v;
    ival(p, n)->max = v;
    ival(p, n)->integer = 1;
}

static void set_mask(struct snd_pcm_hw_params *p, int n, unsigned bit) {
    struct snd_mask *m = pmask(p, n);
    memset(m, 0, sizeof(*m));
    m->bits[bit >> 5] = 1u << (bit & 31);
}

/* Returns an fd (>= 0) or -errno. */
JNIEXPORT jint JNICALL
Java_com_open_alpha2_MultiMicNative_open(JNIEnv *env, jclass cls, jstring jdev,
        jint channels, jint rate, jint periodFrames, jint periods) {
    (void) cls;
    if (jdev == NULL || channels <= 0 || rate <= 0) return -EINVAL;
    const char *dev = (*env)->GetStringUTFChars(env, jdev, NULL);
    if (dev == NULL) return -ENOMEM; /* an OutOfMemoryError is pending */
    int fd = open(dev, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    int err = errno;
    if (fd < 0) {
        LOGW("open %s: %s", dev, strerror(err));
        (*env)->ReleaseStringUTFChars(env, jdev, dev);
        return -err;
    }
    struct snd_pcm_hw_params p;
    param_any(&p);
    set_mask(&p, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    set_mask(&p, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    set_mask(&p, SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
    set_int(&p, SNDRV_PCM_HW_PARAM_CHANNELS, (unsigned) channels);
    set_int(&p, SNDRV_PCM_HW_PARAM_RATE, (unsigned) rate);
    set_int(&p, SNDRV_PCM_HW_PARAM_PERIOD_SIZE, (unsigned) periodFrames);
    set_int(&p, SNDRV_PCM_HW_PARAM_PERIODS, (unsigned) periods);
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &p) < 0) {
        /* Fall back: let the driver pick the period and buffer size. */
        param_any(&p);
        set_mask(&p, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
        set_mask(&p, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
        set_int(&p, SNDRV_PCM_HW_PARAM_CHANNELS, (unsigned) channels);
        set_int(&p, SNDRV_PCM_HW_PARAM_RATE, (unsigned) rate);
        if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &p) < 0) {
            err = errno;
            LOGW("hw_params %s ch=%d rate=%d: %s", dev, channels, rate, strerror(err));
            close(fd);
            (*env)->ReleaseStringUTFChars(env, jdev, dev);
            return -err;
        }
    }
    LOGI("%s open ch=%d rate=%d period=%u periods=%u", dev, channels, rate,
            ival(&p, SNDRV_PCM_HW_PARAM_PERIOD_SIZE)->min, ival(&p, SNDRV_PCM_HW_PARAM_PERIODS)->min);
    (*env)->ReleaseStringUTFChars(env, jdev, dev);
    if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) {
        err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

/* One non-blocking READI_FRAMES. Returns the frame count or -errno. */
static int read_once(JNIEnv *env, jint fd, jshortArray jbuf, jint frames) {
    jshort *buf = (*env)->GetShortArrayElements(env, jbuf, NULL);
    if (buf == NULL) return -ENOMEM; /* an OutOfMemoryError is pending */
    struct snd_xferi x;
    x.result = 0;
    x.buf = buf;
    x.frames = (snd_pcm_uframes_t) frames;
    int rc = ioctl(fd, SNDRV_PCM_IOCTL_READI_FRAMES, &x);
    int err = errno;
    /* Copy back only when the kernel wrote data. */
    (*env)->ReleaseShortArrayElements(env, jbuf, buf, rc < 0 ? JNI_ABORT : 0);
    if (rc < 0) return -err;
    return (int) x.result;
}

/*
 * Reads up to `frames` interleaved frames of `channels` channels into buf.
 * Returns the frame count (can be less than `frames`), -ETIMEDOUT when no data came
 * in `timeoutMs`, -EPIPE after an overrun (the stream is prepared again, the caller
 * must lock the slot order again), -EINVAL for bad arguments (buf too small), or
 * another -errno.
 */
JNIEXPORT jint JNICALL
Java_com_open_alpha2_MultiMicNative_read(JNIEnv *env, jclass cls, jint fd, jshortArray jbuf,
        jint frames, jint channels, jint timeoutMs) {
    (void) cls;
    if (fd < 0 || jbuf == NULL || frames <= 0 || channels <= 0) return -EINVAL;
    jsize len = (*env)->GetArrayLength(env, jbuf);
    if ((long long) frames * (long long) channels > (long long) len) return -EINVAL;
    int attempt;
    for (attempt = 0; attempt < 2; attempt++) {
        /* In the PREPARED state the first READI also starts the capture. */
        int rc = read_once(env, fd, jbuf, frames);
        if (rc >= 0) return rc;
        if (rc == -EPIPE) {
            ioctl(fd, SNDRV_PCM_IOCTL_PREPARE);
            return -EPIPE;
        }
        if (rc != -EAGAIN) return rc;
        if (attempt > 0) break;
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr;
        do {
            pr = poll(&pfd, 1, timeoutMs > 0 ? timeoutMs : 0);
        } while (pr < 0 && errno == EINTR);
        if (pr == 0) return -ETIMEDOUT;
        if (pr < 0) return -errno;
        /* POLLIN or POLLERR (an overrun): the next READI gives the data or -EPIPE. */
    }
    return -ETIMEDOUT;
}

JNIEXPORT void JNICALL
Java_com_open_alpha2_MultiMicNative_close(JNIEnv *env, jclass cls, jint fd) {
    (void) env;
    (void) cls;
    if (fd >= 0) {
        ioctl(fd, SNDRV_PCM_IOCTL_DROP);
        close(fd);
    }
}
