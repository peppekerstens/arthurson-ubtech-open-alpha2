package com.open.alpha2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Tests of the refusal choice of the MCP play tools (alpha2-brain#144). A plain Java runner,
 * with no JUnit, as the other tests. Run from the repository root:
 * scripts/test-apivalidator.py
 *
 * ActionDirect is an Android class, so the whole choice of the two MCP play tools lives
 * in the pure function PlayRefusal.route: the gate refusal first, and only when it is
 * null the play part. The tests call that function with a fake play part. They also read
 * the source of ActionDirect as an extra check: the two MCP tools call PlayRefusal.route,
 * and playActionDirect keeps its own gate check.
 */
public final class PlayRefusalTest {
    private static int passed = 0;
    private static int failed = 0;

    private PlayRefusalTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    /** The refused part of the tests: the result is the refusal text itself. */
    private static final PlayRefusal.Refused<String> REFUSED = new PlayRefusal.Refused<String>() {
        public String apply(String why) {
            return why;
        }
    };

    /** A fake play part: it records that it ran, and gives one fixed result text. */
    private static final class FakePlay implements PlayRefusal.Play<String> {
        static final String RESULT = "API_ERROR_SUCCEED (fake play part ran)";
        int runs = 0;
        public String get() {
            runs++;
            return RESULT;
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. The pure function: the gate is off by default (both switches off), and the
        //    result text starts with "Motion is refused".
        MotionGate gateOff = new MotionGate();
        check(!gateOff.isMotionEnabled() && !gateOff.isHeadOnlyEnabled(), "default: both off");
        String why = PlayRefusal.refusalForPlay(gateOff, "play_action");
        check(why != null, "gate off: refused");
        check(why != null && why.startsWith("Motion is refused"),
                "gate off: the text starts like the gate text");
        check(why != null && why.contains("motion_enabled"), "gate off: the text names the setting");
        check(why != null && why.contains("play_action"), "gate off: the text names the call");
        why = PlayRefusal.refusalForPlay(gateOff, "play_random_action");
        check(why != null && why.startsWith("Motion is refused")
                && why.contains("play_random_action"), "gate off, random: refused, text names the call");

        // 2. Only the head switch: a whole action still is refused (it moves more than the head).
        MotionGate headOnly = new MotionGate();
        headOnly.setHeadOnlyEnabled(true);
        why = PlayRefusal.refusalForPlay(headOnly, "play_action");
        check(why != null && why.startsWith("Motion is refused")
                && why.contains("head_only_enabled"),
                "head only: refused, the text names the head switch");

        // 3. The gate is on: the call can go on (null).
        MotionGate gateOn = new MotionGate();
        gateOn.setMotionEnabled(true);
        check(PlayRefusal.refusalForPlay(gateOn, "play_action") == null, "gate on: allowed");
        check(PlayRefusal.refusalForPlay(gateOn, "play_random_action") == null, "gate on, random: allowed");
        check(PlayRefusal.refusalForPlay(gateOn, "play_action") == null && gateOn.isMotionEnabled(),
                "the refusal does not change a switch");

        // 4. No gate: refused, as strict as a gate that allows nothing.
        why = PlayRefusal.refusalForPlay(null, "play_action");
        check(why != null && why.startsWith("Motion is refused")
                && why.contains("has no motion gate"), "no gate: refused, the text names the missing gate");

        // 5. The real route: the tests call PlayRefusal.route with a fake play part.
        //    Gate off: the result text starts with "Motion is refused", and the play
        //    part never runs. Gate on: the play part runs, and its result comes back
        //    unchanged. No gate: refused, as strict as a gate that allows nothing.
        FakePlay play = new FakePlay();
        String res = PlayRefusal.route(new MotionGate(), "play_action", REFUSED, play);
        check(res != null && res.startsWith("Motion is refused"),
                "route, gate off: the result text starts like the gate text");
        check(play.runs == 0, "route, gate off: the play part never runs");
        res = PlayRefusal.route(new MotionGate(), "play_random_action", REFUSED, play);
        check(res != null && res.startsWith("Motion is refused") && play.runs == 0,
                "route, gate off, random: refused, the play part never runs");
        res = PlayRefusal.route(null, "play_action", REFUSED, play);
        check(res != null && res.startsWith("Motion is refused") && play.runs == 0,
                "route, no gate: refused, the play part never runs");
        res = PlayRefusal.route(gateOn, "play_action", REFUSED, play);
        check(FakePlay.RESULT.equals(res) && play.runs == 1,
                "route, gate on: the play part runs, its result comes back unchanged");
        res = PlayRefusal.route(gateOn, "play_random_action", REFUSED, play);
        check(FakePlay.RESULT.equals(res) && play.runs == 2,
                "route, gate on, random: the play part runs, its result comes back unchanged");

        // 6. Extra check on the source of ActionDirect: the two MCP tools call the real
        //    PlayRefusal.route. The route itself is tested above; ActionDirect is an
        //    Android class, and this plain Java test cannot run it.
        String src = new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/open/alpha2/ActionDirect.java")), StandardCharsets.UTF_8);
        int startPlay = src.indexOf("public SonarCenter.McpResult mcpPlayAction(");
        int startStop = src.indexOf("public SonarCenter.McpResult mcpStopAction(");
        check(startPlay > 0 && startStop > startPlay, "source: mcpPlayAction found");
        String mcpPlay = src.substring(startPlay, startStop);
        check(mcpPlay.contains("PlayRefusal.route(motionGate, \"play_action\""),
                "source: mcpPlayAction calls PlayRefusal.route");
        int startRandom = src.indexOf("public SonarCenter.McpResult mcpPlayRandomAction(");
        check(startRandom > startStop, "source: mcpPlayRandomAction found");
        String mcpRandom = src.substring(startRandom, src.lastIndexOf('}'));
        check(mcpRandom.contains("PlayRefusal.route(motionGate, \"play_random_action\""),
                "source: mcpPlayRandomAction calls PlayRefusal.route");

        // 7. The boot stand-up (alpha2-brain#148): refused with the gate off, with only the head
        //    switch, and with no gate. Allowed with the gate on. The text names the call.
        why = PlayRefusal.refusalForBootStandup(new MotionGate());
        check(why != null && why.startsWith("Motion is refused") && why.contains("official boot standup"),
                "boot standup, gate off: refused, the text names the call");
        why = PlayRefusal.refusalForBootStandup(headOnly);
        check(why != null && why.startsWith("Motion is refused"), "boot standup, head only: refused");
        why = PlayRefusal.refusalForBootStandup(null);
        check(why != null && why.contains("has no motion gate"), "boot standup, no gate: refused");
        check(PlayRefusal.refusalForBootStandup(gateOn) == null, "boot standup, gate on: allowed");
        // Servo moves of the other paths (Xiaozhi MCP tools, gamepad, walk).
        check(PlayRefusal.refusalForServoMove(new MotionGate(), 5) != null, "servo move, gate off: refused");
        check(PlayRefusal.refusalForServoMove(null, 5) != null, "servo move, no gate: refused");
        check(PlayRefusal.refusalForServoMove(gateOn, 5) == null, "servo move, gate on: allowed");
        check(PlayRefusal.refusalForServoMove(headOnly, 5) != null, "servo move, head only, body servo: refused");
        check(PlayRefusal.refusalForAllServosMove(new MotionGate()) != null, "all servos, gate off: refused");
        check(PlayRefusal.refusalForAllServosMove(null) != null, "all servos, no gate: refused");
        check(PlayRefusal.refusalForAllServosMove(headOnly) != null, "all servos, head only: refused");
        check(PlayRefusal.refusalForAllServosMove(gateOn) == null, "all servos, gate on: allowed");
        // The stored setting "stand up at start" decides the boot stand-up (hub D-217).
        check(PlayRefusal.bootStandupChoice(gateOn, false) == PlayRefusal.BootStandup.SKIP,
                "boot choice, setting off, gate on: no move");
        check(PlayRefusal.bootStandupChoice(new MotionGate(), false) == PlayRefusal.BootStandup.SKIP,
                "boot choice, setting off, gate off: no move");
        check(PlayRefusal.bootStandupChoice(null, false) == PlayRefusal.BootStandup.SKIP,
                "boot choice, setting off, no gate: no move");
        check(PlayRefusal.bootStandupChoice(gateOn, true) == PlayRefusal.BootStandup.RUN,
                "boot choice, setting on, gate on: move");
        check(PlayRefusal.bootStandupChoice(new MotionGate(), true) == PlayRefusal.BootStandup.RUN_GATE_OFF,
                "boot choice, setting on, gate off: move, logged as gate off");
        check(PlayRefusal.bootStandupChoice(null, true) == PlayRefusal.BootStandup.RUN_GATE_OFF,
                "boot choice, setting on, no gate: move, logged as gate off");
        // Source check: officialBootStandup asks the setting and the gate before the first servo command.
        int bs = src.indexOf("public boolean officialBootStandup()");
        int firstSet = src.indexOf("setAllServos(", bs);
        int gateAsk = src.indexOf("PlayRefusal.bootStandupChoice(motionGate, isBootStandupEnabled())", bs);
        check(bs > 0 && gateAsk > bs && firstSet > gateAsk, "source: the boot standup asks the gate first");

        System.out.println("PlayRefusalTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
