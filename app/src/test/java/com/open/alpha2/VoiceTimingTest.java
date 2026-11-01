package com.open.alpha2;

import java.util.ArrayList;
import java.util.List;

/**
 * VoiceTiming unit test (no JUnit). scripts/test-apivalidator.py runs it with plain
 * javac and java. A fake clock gives exact milliseconds.
 */
public final class VoiceTimingTest {
    private static int passed = 0;
    private static int failed = 0;
    private static long nowMs = 0;

    private VoiceTimingTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(Object got, Object want, String name) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) System.out.println("  got:  " + got + "\n  want: " + want);
        check(ok, name);
    }

    private static VoiceTiming fresh(final List<String> out) {
        nowMs = 1000000; // a large t0: values must be relative
        VoiceTiming vt = new VoiceTiming(new VoiceTiming.Clock() {
            @Override public long nanos() {
                return nowMs * 1000000L;
            }
        });
        vt.setSink(new VoiceTiming.Sink() {
            @Override public void line(String text) {
                out.add(text);
            }
        });
        return vt;
    }

    private static void at(long ms) {
        nowMs = 1000000 + ms;
    }

    public static void main(String[] args) {
        // A full wake turn, then the follow-up window closes without speech.
        List<String> out = new ArrayList<String>();
        VoiceTiming vt = fresh(out);
        vt.wake();
        vt.play(); // idle playback before any frame: ignored
        at(800); vt.listen();
        at(1500); vt.speech();
        at(2300); vt.speech();
        at(2400); vt.stt();
        at(2450); vt.speech(); // after stt: ignored
        at(2900); vt.ttsStart();
        at(3000); vt.audioFrame();
        at(3010); vt.audioFrame();
        at(3100); vt.play();
        at(3200); vt.written();
        at(5000); vt.ttsStop();
        vt.followUp();
        at(5900); vt.written();
        check(out.isEmpty(), "no line before the audio ends");
        at(6300); vt.idle();
        eq(out.size(), 1, "one line after idle");
        eq(out.get(0), "v=1 turn=1 kind=wake wake=0 listen=800 speech_end=2300 stt=2400"
                + " tts_start=2900 audio_first=3000 play=3100 tts_stop=5000 play_end=5900",
                "full wake turn");
        at(12000); vt.end();
        eq(out.size(), 1, "silent follow-up gives no line");

        // A follow-up turn with a question.
        out.clear();
        vt = fresh(out);
        vt.wake();
        at(500); vt.listen();
        at(1000); vt.stt();
        at(1200); vt.ttsStart();
        at(1300); vt.audioFrame();
        at(1400); vt.play();
        at(2000); vt.ttsStop();
        vt.followUp(); // t0 = 2000
        at(2500); vt.idle(); // turn 1 ends, play_end = play
        at(3000); vt.speech();
        at(3200); vt.stt();
        at(3300); vt.ttsStart();
        at(3400); vt.audioFrame();
        at(3500); vt.play();
        at(4000); vt.end(); // stop command during the answer
        eq(out.size(), 2, "two lines");
        eq(out.get(0), "v=1 turn=1 kind=wake wake=0 listen=500 speech_end=- stt=1000"
                + " tts_start=1200 audio_first=1300 play=1400 tts_stop=2000 play_end=1400",
                "short wake turn");
        eq(out.get(1), "v=1 turn=2 kind=followup wake=- listen=0 speech_end=1000 stt=1200"
                + " tts_start=1300 audio_first=1400 play=1500 tts_stop=- play_end=1500",
                "follow-up turn");

        // Missed idle pause: the follow-up answer starts while turn 1 still closes.
        // The first frame of turn 2 closes turn 1, so play and play_end go to turn 2.
        out.clear();
        vt = fresh(out);
        vt.wake();
        at(500); vt.listen();
        at(1000); vt.stt();
        at(1200); vt.ttsStart();
        at(1300); vt.audioFrame();
        at(1400); vt.play();
        at(1900); vt.written();
        at(2000); vt.ttsStop(); // no idle pause before or after tts stop
        vt.followUp(); // t0 = 2000
        at(2600); vt.written(); // turn 1 audio still plays
        at(3000); vt.speech();
        at(3200); vt.stt();
        at(3300); vt.ttsStart();
        check(out.isEmpty(), "closing turn stays open before the new frame");
        at(3400); vt.audioFrame();
        eq(out.size(), 1, "first frame of turn 2 closes turn 1");
        eq(out.get(0), "v=1 turn=1 kind=wake wake=0 listen=500 speech_end=- stt=1000"
                + " tts_start=1200 audio_first=1300 play=1400 tts_stop=2000 play_end=2600",
                "turn 1 closed by the new frame");
        at(3410); vt.audioFrame(); // second frame: no new line
        eq(out.size(), 1, "second frame gives no line");
        at(3500); vt.play();
        at(3700); vt.written();
        at(4000); vt.ttsStop();
        at(4600); vt.idle();
        eq(out.size(), 2, "turn 2 line after idle");
        eq(out.get(1), "v=1 turn=2 kind=followup wake=- listen=0 speech_end=1000 stt=1200"
                + " tts_start=1300 audio_first=1400 play=1500 tts_stop=2000 play_end=1700",
                "follow-up turn has play");

        // A new wake word flushes an open turn. No connection: listen stays "-".
        out.clear();
        vt = fresh(out);
        vt.wake();
        at(7000); vt.wake();
        eq(out.size(), 1, "flush at the next wake");
        eq(out.get(0), "v=1 turn=1 kind=wake wake=0 listen=- speech_end=- stt=- tts_start=-"
                + " audio_first=- play=- tts_stop=- play_end=-", "empty wake turn");

        // No turn: all calls do nothing.
        out.clear();
        vt = fresh(out);
        vt.audioFrame(); vt.play(); vt.written(); vt.ttsStop(); vt.idle(); vt.end();
        check(out.isEmpty(), "no turn, no line");

        System.out.println("VoiceTimingTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
