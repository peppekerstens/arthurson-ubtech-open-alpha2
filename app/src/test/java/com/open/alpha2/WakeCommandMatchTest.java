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

        System.out.println("WakeCommandMatchTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
