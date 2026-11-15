package com.open.alpha2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests of the refusal choice of the MCP play tools (alpha2-brain#144). A plain Java runner,
 * with no JUnit, as the other tests. Run from the repository root:
 * scripts/test-apivalidator.py
 *
 * ActionDirect is an Android class, so the choice lives in the pure function
 * PlayRefusal.refusalForPlay. The tests check that function, and the shape of the new MCP
 * route: the refusal first, and only when it is null the play. They also read the source
 * of ActionDirect: the two MCP tools must call playRefusal before playActionDirect, and
 * playActionDirect keeps its own gate check.
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

    /** A fake player that records each action it plays. */
    private static final class FakePlayer {
        final List<String> played = new ArrayList<String>();
        void play(String id) {
            played.add(id);
        }
    }

    /** The shape of the new MCP route (mcpPlayAction / mcpPlayRandomAction): the refusal
     *  choice first, and only when it is null the play. Returns the result text the tool
     *  would give. */
    private static String mcpPlay(MotionGate gate, String what, String id, FakePlayer p) {
        String why = PlayRefusal.refusalForPlay(gate, what);
        if (why != null) return why;
        p.play(id);
        return "API_ERROR_SUCCEED (played action id " + id + ")";
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

        // 5. The shape of the new MCP route: with the gate off the result text starts with
        //    "Motion is refused" and no action plays; with the gate on the action plays.
        FakePlayer p = new FakePlayer();
        String res = mcpPlay(new MotionGate(), "play_action", "1", p);
        check(res.startsWith("Motion is refused"), "route, gate off: the result text starts like the gate text");
        check(p.played.isEmpty(), "route, gate off: no action plays");
        res = mcpPlay(new MotionGate(), "play_random_action", "2", p);
        check(res.startsWith("Motion is refused") && p.played.isEmpty(),
                "route, gate off, random: refused, no action plays");
        res = mcpPlay(null, "play_action", "3", p);
        check(res.startsWith("Motion is refused") && p.played.isEmpty(),
                "route, no gate: refused, no action plays");
        res = mcpPlay(gateOn, "play_action", "4", p);
        check(res.startsWith("API_ERROR_SUCCEED") && p.played.size() == 1 && p.played.get(0).equals("4"),
                "route, gate on: the action plays");
        res = mcpPlay(gateOn, "play_random_action", "5", p);
        check(res.startsWith("API_ERROR_SUCCEED") && p.played.size() == 2 && p.played.get(1).equals("5"),
                "route, gate on, random: the action plays");

        // 6. The source of ActionDirect: the two MCP tools call playRefusal before
        //    playActionDirect and return the refusal text; playActionDirect keeps its
        //    own gate check.
        String src = new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/open/alpha2/ActionDirect.java")), StandardCharsets.UTF_8);
        int startPlay = src.indexOf("public SonarCenter.McpResult mcpPlayAction(");
        int startStop = src.indexOf("public SonarCenter.McpResult mcpStopAction(");
        check(startPlay > 0 && startStop > startPlay, "source: mcpPlayAction found");
        String mcpPlay = src.substring(startPlay, startStop);
        check(mcpPlay.indexOf("playRefusal(") > 0
                && mcpPlay.indexOf("playRefusal(") < mcpPlay.indexOf("playActionDirect("),
                "source: mcpPlayAction checks the gate before the play");
        check(mcpPlay.contains("SonarCenter.McpResult.err(why)"),
                "source: mcpPlayAction returns the refusal text");
        int startRandom = src.indexOf("public SonarCenter.McpResult mcpPlayRandomAction(");
        check(startRandom > startStop, "source: mcpPlayRandomAction found");
        String mcpRandom = src.substring(startRandom, src.lastIndexOf('}'));
        check(mcpRandom.indexOf("playRefusal(") > 0
                && mcpRandom.indexOf("playRefusal(") < mcpRandom.indexOf("playActionDirect("),
                "source: mcpPlayRandomAction checks the gate before the play");
        check(mcpRandom.contains("SonarCenter.McpResult.err(why)"),
                "source: mcpPlayRandomAction returns the refusal text");
        int startDirect = src.indexOf("public UbxErrorCode.API_ERROR_CODE playActionDirect(");
        int startRecovery = src.indexOf("public UbxErrorCode.API_ERROR_CODE stopActionWithRecovery()");
        check(startDirect > 0 && startRecovery > startDirect, "source: playActionDirect found");
        String direct = src.substring(startDirect, startRecovery);
        check(direct.indexOf("playRefusal(") > 0
                && direct.indexOf("playRefusal(") < direct.indexOf("resolveActionFile("),
                "source: playActionDirect keeps its gate check first");
        check(direct.contains("playRefusal(\"playActionDirect(\" + nameOrId + \")\")"),
                "source: playActionDirect gate check unchanged");
        check(direct.contains("return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;"),
                "source: playActionDirect still answers FAILED on a refusal");

        System.out.println("PlayRefusalTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
