#!/usr/bin/env python3
"""
mic5_tdoa.py: time difference of arrival (GCC-PHAT) between the 5 mics of a WAV
from /stream/mic/record (5 or 8 channels, 16 kHz).

Use it to measure the mic geometry (alpha2-brain#23, slice M2/M3):
clap one time at 1 m from the head, at a known angle, and record 3 s:

  curl -H "Authorization: Bearer $ALPHA2_PANEL_TOKEN" -o clap_090.wav \
    "http://$ALPHA2_ROBOT_HOST:8888/stream/mic/record?seconds=3&channels=8"
  python3 scripts/mic5_tdoa.py clap_090.wav

Or let the script record the WAV (needs the robot URL and ALPHA2_PANEL_TOKEN):

  python3 scripts/mic5_tdoa.py --record clap_090.wav [--url http://HOST:8888]

The robot URL comes from --url, else from ALPHA2_URL, else from
http://$ALPHA2_ROBOT_HOST:8888. With none of them the script stops with an error.

The script finds the loudest 100 ms (the clap) and prints the lag of m2..m5
against m1 in samples (sub-sample, parabolic fit) and in microseconds.
Sound moves 343 mm/ms, so 1 sample at 16 kHz = 21.4 mm path difference.
Needs numpy.
"""
import argparse
import os
import sys
import urllib.request
import wave

import numpy as np

RATE = 16000
NAMES = ["m1", "m2", "m3", "m4", "m5", "ref1", "ref2", "aux"]


def gcc_phat(a, b, max_lag):
    n = 1
    while n < 2 * len(a):
        n *= 2
    A = np.fft.rfft(a, n)
    B = np.fft.rfft(b, n)
    R = A * np.conj(B)
    R /= np.abs(R) + 1e-12
    cc = np.fft.irfft(R, n)
    cc = np.concatenate((cc[-max_lag:], cc[:max_lag + 1]))
    i = int(np.argmax(cc))
    shift = 0.0
    if 0 < i < len(cc) - 1:
        y0, y1, y2 = cc[i - 1], cc[i], cc[i + 1]
        d = y0 - 2 * y1 + y2
        if d != 0:
            shift = 0.5 * (y0 - y2) / d
    peak = float(cc[i]) / (np.sum(np.abs(cc)) / len(cc) + 1e-12)
    # positive lag: the sound reaches b before a
    return (i - max_lag) + shift, peak


def main(path):
    w = wave.open(path)
    ch = w.getnchannels()
    if w.getframerate() != RATE or w.getsampwidth() != 2:
        sys.exit("expect 16 kHz 16-bit")
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").reshape(-1, ch).astype(float)
    mics = x[:, :5] - x[:, :5].mean(0)
    win = RATE // 10
    e = np.convolve((mics ** 2).sum(1), np.ones(win), "valid")
    s = int(np.argmax(e))
    seg = mics[s:s + win]
    print("%s: %d ch, loudest 100 ms at %.2f s" % (path, ch, s / RATE))
    for k in range(1, 5):
        lag, peak = gcc_phat(seg[:, 0], seg[:, k], 8)
        print("  %s vs m1: lag %+.2f samples = %+6.0f us, peak %.1f" % (
            NAMES[k], lag, lag * 1e6 / RATE, peak))


def robot_url(arg):
    """Return the robot base URL from the argument, ALPHA2_URL or ALPHA2_ROBOT_HOST."""
    if arg:
        return arg.rstrip("/")
    if os.environ.get("ALPHA2_URL"):
        return os.environ["ALPHA2_URL"].rstrip("/")
    if os.environ.get("ALPHA2_ROBOT_HOST"):
        return "http://%s:8888" % os.environ["ALPHA2_ROBOT_HOST"]
    sys.exit("no robot URL: give --url, or set ALPHA2_URL or ALPHA2_ROBOT_HOST")


def record(base, out, seconds=3):
    req = urllib.request.Request(
        "%s/stream/mic/record?seconds=%d&channels=8" % (base, seconds))
    token = os.environ.get("ALPHA2_PANEL_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=seconds + 10) as r, open(out, "wb") as f:
        f.write(r.read())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="*", metavar="FILE",
                        help="WAV file(s) to analyse")
    parser.add_argument("--record", metavar="FILE",
                        help="record a WAV, then analyse it")
    parser.add_argument("--url", metavar="URL",
                        help="robot base URL")
    args = parser.parse_args()
    files = list(args.files)
    if args.record:
        record(robot_url(args.url), args.record)
        files.append(args.record)
    if not files:
        parser.print_usage()
        sys.exit(2)
    for p in files:
        main(p)
