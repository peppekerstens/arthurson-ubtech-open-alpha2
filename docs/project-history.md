# Project history of our fork changes

Dated results of our changes, newest date first. The current state lives in the main docs (hub rule D-168).

## 2026-11-05

- The spec text of `ids` names the 20 kg servos since branch `robot-build-20261105`. Older builds serve a spec that says "17 to 22".

## 2026-11-01

### The 5-channel mic capture (docs/mic-5ch.md)

- 5 opens in a row: the rotation was 1, 2, 2, 3, 0. Each time the lock gave the same channel map (`cross_check` true, no relock).
- 440 Hz test tone on the speaker: m1 to m5 show 4200 to 5300, ref1 and ref2 show 1430, aux shows 0.
- Capture of card 0 and `/stream/mic` (card 3) at the same time: both work.

