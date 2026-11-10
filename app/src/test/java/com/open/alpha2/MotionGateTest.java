package com.open.alpha2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests of MotionGate (alpha2-brain#113). A plain Java runner, with no JUnit, as the other tests.
 * Run: python scripts/test-apivalidator.py
 *
 * A fake sender replaces the serial line. It records each servo move that reaches it.
 * The routes in the tests use the same guard calls as ApiDispatcher.
 */
public final class MotionGateTest {
    private static int passed = 0;
    private static int failed = 0;

    private MotionGateTest() {}

    /** The fake of the line to the servos. */
    private static final class FakeSender {
        final List<String> sent = new ArrayList<String>();

        String sendOne(int id, int angle, int time) {
            sent.add("one:" + id + ":" + angle + ":" + time);
            return "sent";
        }

        String sendAll(int time) {
            sent.add("all:" + time);
            return "sent";
        }
    }

    /** What a route gives back: a status and a body. */
    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final MotionGate.Refused<Reply> REFUSED = new MotionGate.Refused<Reply>() {
        @Override
        public Reply refuse(int status, String text) {
            return new Reply(status, text);
        }
    };

    private static Reply routeOne(final MotionGate gate, final FakeSender s, final int id) {
        return gate.guardServo(id, new MotionGate.Allowed<Reply>() {
            @Override
            public Reply run() {
                return new Reply(200, s.sendOne(id, 128, 500));
            }
        }, REFUSED);
    }

    private static Reply routeAll(final MotionGate gate, final FakeSender s) {
        return gate.guardAll(new MotionGate.Allowed<Reply>() {
            @Override
            public Reply run() {
                return new Reply(200, s.sendAll(500));
            }
        }, REFUSED);
    }

    /** A route that moves the whole body: ubx/play (both route sets), servo/angle-restore,
     *  servo/angle-all and the voice commands. The fake sender records the call by name. */
    private static Reply routeWholeBody(final MotionGate gate, final FakeSender s, final String what) {
        return gate.guardWholeBody(what, new MotionGate.Allowed<Reply>() {
            @Override
            public Reply run() {
                s.sent.add(what);
                return new Reply(200, "sent");
            }
        }, REFUSED);
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void refused(Reply r, FakeSender s, String name) {
        check(r.status == 403, name + ": status 403, got " + r.status);
        check(r.body.contains("motion_enabled"), name + ": the text names the setting");
        check(s.sent.isEmpty(), name + ": nothing reached the sender");
    }

    public static void main(String[] args) {
        // 1. Both switches are off by default. servo/one gives 403 and sends nothing.
        MotionGate gate = new MotionGate();
        FakeSender s = new FakeSender();
        check(!gate.isMotionEnabled() && !gate.isHeadOnlyEnabled(), "default: both off");
        refused(routeOne(gate, s, 1), s, "gate off, servo 1");
        refused(routeOne(gate, s, 19), s, "gate off, servo 19");
        refused(routeAll(gate, s), s, "gate off, all");

        // 2. Gate on: the call reaches the sender.
        gate.setMotionEnabled(true);
        Reply r = routeOne(gate, s, 1);
        check(r.status == 200 && s.sent.size() == 1 && s.sent.get(0).equals("one:1:128:500"), "gate on, servo 1 passes");
        r = routeAll(gate, s);
        check(r.status == 200 && s.sent.size() == 2, "gate on, all passes");

        // 3. Only the head switch: servo 19 passes, servo 1 and all give 403.
        gate = new MotionGate();
        s = new FakeSender();
        gate.setHeadOnlyEnabled(true);
        r = routeOne(gate, s, 19);
        check(r.status == 200 && s.sent.size() == 1 && s.sent.get(0).startsWith("one:19:"), "head only, servo 19 passes");
        s = new FakeSender();
        r = routeOne(gate, s, 1);
        refused(r, s, "head only, servo 1");
        check(r.body.contains("head_only_enabled"), "head only, servo 1: the text names the head switch");
        refused(routeAll(gate, s), s, "head only, all");

        // 4. A restart (a new instance) has both switches off.
        gate.setMotionEnabled(true);
        gate.setHeadOnlyEnabled(true);
        MotionGate restarted = new MotionGate();
        check(!restarted.isMotionEnabled() && !restarted.isHeadOnlyEnabled(), "restart: both off");
        s = new FakeSender();
        refused(routeOne(restarted, s, 19), s, "restart, servo 19");

        // 5. The switch route: apply query, status, bad value.
        gate = new MotionGate();
        gate.applyQuery(q("head_only_enabled", "true"));
        check(!gate.isMotionEnabled() && gate.isHeadOnlyEnabled(), "set head only");
        check(gate.statusJson().equals("{\"ok\":true,\"motion_enabled\":false,\"head_only_enabled\":true}"), "status json");
        gate.applyQuery(q("motion_enabled", "true", "head_only_enabled", "false"));
        check(gate.isMotionEnabled() && !gate.isHeadOnlyEnabled(), "set both");
        gate.applyQuery(q());
        check(gate.isMotionEnabled(), "empty query changes nothing");
        boolean threw = false;
        try {
            gate.applyQuery(q("motion_enabled", "false", "head_only_enabled", "yes"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw && gate.isMotionEnabled(), "a bad value throws and changes nothing");

        // 6. Whole-body routes (alpha2-brain#121): each one is refused with the gate off,
        //    and refused with only the head switch on. Each one passes with the gate on.
        String[] routes = {"ubx/play", "ubx/play (direct)", "action/play", "playActionDirect(chat, semantic, gamepad, MCP)", "servo/angle-restore", "servo/angle-all",
                "the voice command stand_up", "the voice command squat",
                "the voice command rest", "the voice command sleep"};
        for (int i = 0; i < routes.length; i++) {
            String route = routes[i];
            gate = new MotionGate();
            s = new FakeSender();
            Reply rr = routeWholeBody(gate, s, route);
            refused(rr, s, "gate off, " + route);
            check(rr.body.contains(route), "gate off, " + route + ": the text names the call");
            gate.setHeadOnlyEnabled(true);
            rr = routeWholeBody(gate, s, route);
            refused(rr, s, "head only, " + route);
            check(rr.body.contains("head_only_enabled"), "head only, " + route + ": the text names the head switch");
            gate.setMotionEnabled(true);
            rr = routeWholeBody(gate, s, route);
            check(rr.status == 200 && s.sent.size() == 1 && s.sent.get(0).equals(route), "gate on, " + route + " passes");
            check(new MotionGate().refusalForWholeBody(route) != null, "restart, " + route + ": refused");
        }
        // Even servo 19 (angle-restore on the head) needs the full switch.
        gate = new MotionGate();
        gate.setHeadOnlyEnabled(true);
        check(gate.refusalForServo(19) == null && gate.refusalForWholeBody("servo/angle-restore") != null,
                "head only: servo/one 19 passes, servo/angle-restore does not");
        check(gate.refusalForWholeBody("x") != null && !gate.isMotionEnabled(), "refusal does not change a switch");

        System.out.println("MotionGateTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
