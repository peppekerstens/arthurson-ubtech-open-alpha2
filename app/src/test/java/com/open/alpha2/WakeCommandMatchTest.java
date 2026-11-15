package com.open.alpha2;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WakeCommandMatch unit test (no JUnit). scripts/test-apivalidator.py runs it. */
public final class WakeCommandMatchTest {
    private static int passed = 0;
    private static int failed = 0;

    private WakeCommandMatchTest() {}

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    public static void main(String[] args) {
        List<String> wake = Arrays.asList("alpha", "robot");
        Map<String, String> cmds = new LinkedHashMap<>();
        cmds.put("stop", "stop");
        cmds.put("stand up", "stand_up");
        cmds.put("sleep", "sleep");
        cmds.put("volume up", "volume_up");

        // The whole utterance, with or without the wake word.
        eq("stand_up", WakeCommandMatch.command("alpha stand up", wake, cmds), "wake + command");
        eq("stand_up", WakeCommandMatch.command("stand up", wake, cmds), "command alone");
        eq("stand_up", WakeCommandMatch.command("  Stand   Up ", wake, cmds), "spaces and case");
        eq("volume_up", WakeCommandMatch.command("robot volume up", wake, cmds), "other wake word");
        // [unk] makes it a sentence: never a command.
        eq(null, WakeCommandMatch.command("[unk] sleep", wake, cmds), "unk before");
        eq(null, WakeCommandMatch.command("stand up [unk]", wake, cmds), "unk after");
        eq(null, WakeCommandMatch.command("alpha [unk] stand up", wake, cmds), "wake unk command");
        eq(null, WakeCommandMatch.command("alpha", wake, cmds), "wake word only");
        eq(null, WakeCommandMatch.command("", wake, cmds), "empty");
        eq(null, WakeCommandMatch.command(null, wake, cmds), "null");

        eq(true, WakeCommandMatch.hasWakeWord("[unk] alpha", wake), "wake word with unk");
        eq(false, WakeCommandMatch.hasWakeWord("alphabet", wake), "no part word");
        eq(true, WakeCommandMatch.hasStop("[unk] stop [unk]"), "stop in a sentence");
        eq(false, WakeCommandMatch.hasStop("stopping"), "no part stop");

        // alpha2-brain#133: the word check. Only the exact configured wake word
        // wakes (case and punctuation do not matter). Accepted: "alpha", "Alpha.".
        // Not accepted: "aha", "aha.", "ah", "alfa".
        eq(true, WakeCommandMatch.hasWakeWord("alpha", wake), "133: alpha");
        eq(true, WakeCommandMatch.hasWakeWord("Alpha.", wake), "133: Alpha. (punctuation)");
        eq(false, WakeCommandMatch.hasWakeWord("aha", wake), "133: aha");
        eq(false, WakeCommandMatch.hasWakeWord("aha.", wake), "133: aha.");
        eq(false, WakeCommandMatch.hasWakeWord("ah", wake), "133: ah");
        eq(false, WakeCommandMatch.hasWakeWord("alfa", wake), "133: alfa");
        eq(true, WakeCommandMatch.hasWakeWord("alpha stand up", wake), "133: wake word in the phrase");
        eq(true, WakeCommandMatch.hasWakeWord("[unk] robot [unk]", wake), "133: the other wake word");
        eq(false, WakeCommandMatch.hasWakeWord("", wake), "133: empty");

        // alpha2-brain#133: the word confidence of the wake word (the highest of
        // the wake-word tokens). -1 = no wake-word token, or conf unknown.
        eq(1.0, WakeCommandMatch.wakeConf(Arrays.asList("alpha"), Arrays.asList(1.0), wake),
                "133: conf of alpha");
        eq(0.6, WakeCommandMatch.wakeConf(Arrays.asList("oh", "alpha"), Arrays.asList(1.0, 0.6), wake),
                "133: conf with a word in front");
        eq(0.8, WakeCommandMatch.wakeConf(Arrays.asList("alpha", "alpha"), Arrays.asList(0.4, 0.8), wake),
                "133: the highest of the wake words");
        eq(-1.0, WakeCommandMatch.wakeConf(Arrays.asList("ah", "oh"), Arrays.asList(0.8, 0.7), wake),
                "133: no wake-word token");
        eq(-1.0, WakeCommandMatch.wakeConf(Arrays.asList("alpha"), Arrays.asList((Double) null), wake),
                "133: conf unknown (fail-open)");
        eq(-1.0, WakeCommandMatch.wakeConf(Arrays.asList(), Arrays.asList(), wake), "133: empty");
        eq(-1.0, WakeCommandMatch.wakeConf(null, null, wake), "133: no result array");

        // alpha2-brain#133: the conf range check. NaN is out of range (the old check
        // v < min || v > max let Float.parseFloat("NaN") through). wake_conf bounds: 0.5-1.0.
        eq(true, WakeCommandMatch.confOutOfRange(Double.NaN, 0.5, 1.0), "133: NaN is out of range");
        eq(true, WakeCommandMatch.confOutOfRange(0.49, 0.5, 1.0), "133: 0.49 is out of range");
        eq(false, WakeCommandMatch.confOutOfRange(0.5, 0.5, 1.0), "133: 0.5 is in range (min boundary)");
        eq(false, WakeCommandMatch.confOutOfRange(1.0, 0.5, 1.0), "133: 1.0 is in range (max boundary)");
        eq(true, WakeCommandMatch.confOutOfRange(1.01, 0.5, 1.0), "133: 1.01 is out of range");

        // Robot session 2026-11-01: "alpha", a pause, then "stand up". The wake turn opens
        // after the cue, and WakeController starts the window again at that time.
        long wakeAt = 1_000_000L;
        long window = 4000;
        long turnOpen = wakeAt + 1800; // connect + cue "Yes?"
        long heard = turnOpen + 3000;  // the user speaks after the cue, Vosk waits for silence
        eq(false, WakeCommandMatch.runsCommand("stand_up", false, heard, wakeAt + window),
                "old window from the wake word: closed");
        eq(true, WakeCommandMatch.runsCommand("stand_up", false, heard, turnOpen + window),
                "window from the turn open: runs");
        eq(true, WakeCommandMatch.runsCommand("stand_up", true, heard, 0), "wake in the phrase");
        eq(false, WakeCommandMatch.runsCommand("stand_up", false, heard, 0), "normal talk: no motion");
        eq(false, WakeCommandMatch.runsCommand("stand_up", false, turnOpen + window, turnOpen + window),
                "window end is closed");
        eq(false, WakeCommandMatch.runsCommand("stop", true, heard, turnOpen + window),
                "stop has its own rule");
        eq(false, WakeCommandMatch.runsCommand(null, true, heard, turnOpen + window), "no command");

        // alpha2-brain#45: the window rules (start, start again, end).
        WakeCommandMatch.CommandWindow w = new WakeCommandMatch.CommandWindow();
        eq("not open", w.describe(wakeAt), "window: not open at first");
        eq(false, w.runs("volume_up", false, wakeAt), "window: closed at first");
        w.onTurnOpen(turnOpen, window);
        eq(0L, w.until(), "window: a turn open with no wake word opens no window");
        w.onWake(wakeAt, window);
        eq(wakeAt + window, w.until(), "window: the wake word opens it");
        w.onTurnOpen(turnOpen, window);
        eq(turnOpen + window, w.until(), "window: the wake turn open starts it again");
        eq(true, w.runs("volume_up", false, heard), "window: a command after the cue runs");
        eq("open", w.describe(heard), "window: open");
        w.onCommand(heard);
        eq(0L, w.until(), "window: a command closes it");
        eq(false, w.runs("volume_up", false, heard + 10), "window: one command for each wake word");
        // A short answer, then the follow-up turn: no command with no wake word.
        w.onWake(wakeAt, window);
        w.onTurnOpen(turnOpen, window);
        long ttsStart = turnOpen + 1500;
        w.onTtsStart(ttsStart);
        eq(0L, w.until(), "window: the answer closes it");
        long followUp = ttsStart + 1000; // still before the old end of the window
        w.onTurnOpen(followUp, window);
        eq(0L, w.until(), "window: a follow-up turn opens no window");
        eq(false, w.runs("stand_up", false, followUp + 500), "window: no motion in the follow-up turn");
        eq(true, w.runs("volume_up", true, followUp + 500), "window: the wake word in the phrase still runs");
        eq("closed 1500 ms ago (turn end, answer or command)", w.describe(ttsStart + 1500),
                "window: the log gives the close time");
        // The turn end closes the window.
        w.onWake(wakeAt, window);
        w.onTurnEnd(wakeAt + 100);
        eq(0L, w.until(), "window: the turn end closes it");
        // Time out: the log gives the time after the end.
        w.onWake(wakeAt, window);
        eq("closed 200 ms ago (time out)", w.describe(wakeAt + window + 200), "window: time out");
        // alpha2-brain#46: the window times out, and the turn ends later. The log still
        // gives the time out as the reason, with the time after the end of the window.
        w.onTurnEnd(wakeAt + window + 300);
        eq("closed 500 ms ago (time out)", w.describe(wakeAt + window + 500),
                "window: time out, then the turn end");
        // A close before the end of the window is not a time out.
        w.onWake(wakeAt, window);
        w.onTurnEnd(wakeAt + 100);
        eq("closed 400 ms ago (turn end, answer or command)", w.describe(wakeAt + 500),
                "window: the turn end before the time out");

        System.out.println("WakeCommandMatchTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
