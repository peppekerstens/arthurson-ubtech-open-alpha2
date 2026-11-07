# 5-mic capture (FPGA sound card)

The robot head has 5 microphones. This app reads them on the FPGA sound card (card 0) and gives them to clients as multichannel WAV. Tracking issue: peppekerstens/alpha2-brain#23 (slice M1).

## API

| Call | Result |
|---|---|
| `GET /stream/mic/record?seconds=N&channels=5` | One WAV, 16 kHz, 16-bit PCM. `seconds` is 1 to 60 (default 5). `channels` is 5 (default) or 8. |
| `GET /stream/mic?channels=5` | Multipart stream of WAV parts, 500 ms each. The framing is the same as `/stream/mic`. Use `channels=8` for all slots. |
| `GET /stream/mic` | No change: the mono stream from the USB card (card 3). |
| `GET /api/mic/multi/status` | State, slot lock (`rotation`, `confidence`, `cross_check`), `relocks`, `overruns`, and the RMS level of each channel over the last 0.5 s. |

All calls need the panel token when it is on. The header `X-Mic-Channels` gives the channel order.

Example:

```sh
curl -H "Authorization: Bearer $ALPHA2_PANEL_TOKEN" -o mic8.wav \
  "http://$ALPHA2_ROBOT_HOST:8888/stream/mic/record?seconds=5&channels=8"
python3 scripts/mic5_tdoa.py mic8.wav
```

## Channel order

| Channel | Raw slot | Signal |
|---|---|---|
| m1 | c0s0 | Mic |
| m2 | c0s2 | Mic |
| m3 | c1s1 | Mic |
| m4 | c1s2 | Mic |
| m5 | c1s3 | Mic |
| ref1 | c0s1 | Speaker reference (for echo cancellation) |
| ref2 | c0s3 | Speaker reference, the same signal as ref1 (correlation 1.00) |
| aux | c1s0 | Unknown. It has no mic signal and no speaker signal. |

`channels=5` gives m1 to m5. `channels=8` adds ref1, ref2 and aux.

The physical position of each mic on the head is not known yet (slice M2).

## How it works

- The stock UBTECH service (alpha2services, now disabled on this robot) read the mics without the Android audio HAL. Its iFlytek `AlsaRecorder` opened `/dev/snd/pcmC0D0c` at 64000 Hz through `libalsa-jni.so` and sent the raw stream to the iFlytek CAE engine. `ueventd.rk30board.rc` sets the node to mode 0777. Thus a normal app can open it without root.
- The audio HAL (`audio.primary.rk30board.so`) and `AudioRecord` use the USB C-Media CM108B (card 3): a mono mic input and the speaker output. The wake word, Vosk, Xiaozhi and `/stream/mic` use this path. They do not change.
- `libalpha2mic.so` (`app/src/main/cpp/alpha2mic/`) opens card 0 with raw ALSA ioctls: 2 channels, S16_LE, 64000 Hz.
- Two CX20810 ADCs (4 channels each) feed the FPGA. The FPGA sends 4 time slots in turn on each of the 2 channels. Each slot is a 16 kHz signal.
- The slot phase changes at each open. `MultiMicController` finds it again in 128 ms: on channel 1, only the aux slot has a smooth signal (mean absolute step 0.6 against 3 or more for a mic). The two reference slots on channel 0 must then be smooth too (`cross_check`). The lock is checked again each second. After an overrun the lock starts again.
- The capture uses a different sound card. Thus it runs at the same time as wake mode and Xiaozhi, and it does not pause them.

## Tests (2026-11-01)

- 5 opens in a row: the rotation was 1, 2, 2, 3, 0. Each time the lock gave the same channel map (`cross_check` true, no relock).
- 440 Hz test tone on the speaker: m1 to m5 show 4200 to 5300, ref1 and ref2 show 1430, aux shows 0.
- Capture of card 0 and `/stream/mic` (card 3) at the same time: both work.

## Limits

- Sample alignment between the slots is not proved. A 4-frame group starts at slot 0, which is an assumption. For the speaker tone the lag between the mics was 0.0 samples.
- The mic positions are not measured. Direction of arrival (`sound_direction`) needs them (slice M3).
- Only one process can open card 0. When another process holds it, the API answers 503 with errno 16.
