/* miccap: show the hw ranges of an ALSA capture device, then record N seconds with the max channel count.
 * Raw ALSA ioctls only (the robot has no tinyalsa tools). Usage: miccap /dev/snd/pcmC0D0c SECONDS OUT.raw */
 * Build: $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang -O2 -o miccap miccap.c
 * Test tool only. The app uses app/src/main/cpp/alpha2mic/alpha2mic.c.
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>

static struct snd_interval *ival(struct snd_pcm_hw_params *p, int n) {
    return &p->intervals[n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
}
static struct snd_mask *mask(struct snd_pcm_hw_params *p, int n) {
    return &p->masks[n - SNDRV_PCM_HW_PARAM_FIRST_MASK];
}
static void any(struct snd_pcm_hw_params *p) {
    memset(p, 0, sizeof(*p));
    for (int n = SNDRV_PCM_HW_PARAM_FIRST_MASK; n <= SNDRV_PCM_HW_PARAM_LAST_MASK; n++)
        memset(mask(p, n), 0xff, sizeof(struct snd_mask));
    for (int n = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; n <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL; n++) {
        ival(p, n)->min = 0;
        ival(p, n)->max = ~0u;
    }
    p->rmask = ~0u;
    p->info = ~0u;
}
static void setint(struct snd_pcm_hw_params *p, int n, unsigned v) {
    ival(p, n)->min = v; ival(p, n)->max = v; ival(p, n)->integer = 1;
}
static void setmask(struct snd_pcm_hw_params *p, int n, unsigned bit) {
    struct snd_mask *m = mask(p, n);
    memset(m, 0, sizeof(*m));
    m->bits[bit >> 5] = 1u << (bit & 31);
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: miccap DEV SECONDS OUT\n"); return 2; }
    int fd = open(argv[1], O_RDWR);
    if (fd < 0) { printf("open %s: %s\n", argv[1], strerror(errno)); return 1; }
    struct snd_pcm_info info;
    memset(&info, 0, sizeof(info));
    if (ioctl(fd, SNDRV_PCM_IOCTL_INFO, &info) == 0)
        printf("info id=%s name=%s subname=%s\n", info.id, info.name, info.subname);
    struct snd_pcm_hw_params p;
    any(&p);
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &p) < 0) { printf("refine: %s\n", strerror(errno)); return 1; }
    printf("channels %u..%u\n", ival(&p, SNDRV_PCM_HW_PARAM_CHANNELS)->min, ival(&p, SNDRV_PCM_HW_PARAM_CHANNELS)->max);
    printf("rate %u..%u\n", ival(&p, SNDRV_PCM_HW_PARAM_RATE)->min, ival(&p, SNDRV_PCM_HW_PARAM_RATE)->max);
    printf("sample bits %u..%u\n", ival(&p, SNDRV_PCM_HW_PARAM_SAMPLE_BITS)->min, ival(&p, SNDRV_PCM_HW_PARAM_SAMPLE_BITS)->max);
    printf("format mask %08x\n", mask(&p, SNDRV_PCM_HW_PARAM_FORMAT)->bits[0]);
    unsigned ch = ival(&p, SNDRV_PCM_HW_PARAM_CHANNELS)->max;
    if (ch > 16) ch = 16;
    unsigned rate = argc > 4 ? (unsigned)atoi(argv[4]) : 16000;
    if (rate < ival(&p, SNDRV_PCM_HW_PARAM_RATE)->min || rate > ival(&p, SNDRV_PCM_HW_PARAM_RATE)->max)
        rate = ival(&p, SNDRV_PCM_HW_PARAM_RATE)->min;
    any(&p);
    setmask(&p, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    setmask(&p, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    setint(&p, SNDRV_PCM_HW_PARAM_CHANNELS, ch);
    setint(&p, SNDRV_PCM_HW_PARAM_RATE, rate);
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &p) < 0) { printf("hw_params ch=%u rate=%u: %s\n", ch, rate, strerror(errno)); return 1; }
    unsigned period = ival(&p, SNDRV_PCM_HW_PARAM_PERIOD_SIZE)->min;
    printf("set ch=%u rate=%u period=%u frames\n", ch, rate, period);
    if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) { printf("prepare: %s\n", strerror(errno)); return 1; }
    FILE *out = fopen(argv[3], "wb");
    if (!out) { printf("out: %s\n", strerror(errno)); return 1; }
    unsigned total = rate * (unsigned)atoi(argv[2]), got = 0;
    size_t fbytes = 2 * ch;
    char *buf = malloc(period * fbytes);
    while (got < total) {
        struct snd_xferi x = { .result = 0, .buf = buf, .frames = period };
        if (ioctl(fd, SNDRV_PCM_IOCTL_READI_FRAMES, &x) < 0) {
            if (errno == EPIPE) { ioctl(fd, SNDRV_PCM_IOCTL_PREPARE); continue; }
            printf("read: %s\n", strerror(errno)); break;
        }
        fwrite(buf, fbytes, x.result, out);
        got += x.result;
    }
    fclose(out);
    printf("frames %u\n", got);
    close(fd);
    return 0;
}
