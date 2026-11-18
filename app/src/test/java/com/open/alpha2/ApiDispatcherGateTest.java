package com.open.alpha2;

import android.content.Context;
import android.content.ContextWrapper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Dispatcher test of the motion gate (alpha2-brain#122 item 3).
 *
 * It builds the real ApiDispatcher with empty helper objects (no constructor runs, so no Android
 * call). Each helper has no state. With the gate off, a gated route must answer 403 before it
 * touches a helper. If a route loses its gate call, the call reaches the empty helper and throws
 * an exception, and this test fails.
 *
 * Run with the stub of android.util.Log in front of android.jar (scripts/test-apivalidator.py).
 */
public class ApiDispatcherGateTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    /** A context that is its own application context. Unsafe builds it: the android.jar constructor throws. */
    public static class FakeContext extends ContextWrapper {
        public FakeContext() { super(null); } // never run: Unsafe builds the object
        
        @Override public Context getApplicationContext() { return this; }
    }

    private static sun.misc.Unsafe unsafe;

    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> c) throws Exception {
        return (T) unsafe.allocateInstance(c);
    }

    private static void set(Object o, String field, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Status of a call, or -1 when the route threw (the gate call is missing). */
    private static int status(ApiDispatcher d, boolean direct, String path, Map<String, String> query) {
        try {
            HttpServer.ApiResponse r = direct
                    ? d.handleDirectApi(path, query, "GET", "")
                    : d.handleApi(path, query, "GET", "");
            return r.status;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Status of a direct call to the servoOneResponse helper (bypassing the dispatcher guard,
     *  which would refuse the body servo first), or -1 when it threw (the method-level gate call
     *  is missing and the trim write / send ran on the empty helper). */
    private static int callServoOne(UbxApi ubxApi, Map<String, String> query) {
        try {
            return ubxApi.servoOneResponse(query).status;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        unsafe = (sun.misc.Unsafe) uf.get(null);

        ActionDirect actionDirect = empty(ActionDirect.class);
        UbxApi ubxApi = empty(UbxApi.class);
        set(ubxApi, "actionDirect", actionDirect);
        LedCenter ledCenter = empty(LedCenter.class);
        WalkCenter walkCenter = empty(WalkCenter.class);
        com.ubtechinc.alpha.hardware.ubx.UbxPlayer player = empty(com.ubtechinc.alpha.hardware.ubx.UbxPlayer.class);

        Constructor<?> ctor = ApiDispatcher.class.getConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] a = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Context.class) a[i] = empty(FakeContext.class);
            else if (types[i] == ActionDirect.class) a[i] = actionDirect;
            else if (types[i] == UbxApi.class) a[i] = ubxApi;
            else if (types[i] == LedCenter.class) a[i] = ledCenter;
            else if (types[i] == WalkCenter.class) a[i] = walkCenter;
            else if (types[i].isInterface() || java.lang.reflect.Modifier.isAbstract(types[i].getModifiers())) a[i] = null;
            else a[i] = empty(types[i]);
        }
        ApiDispatcher d = (ApiDispatcher) ctor.newInstance(a);
        MotionGate gate = d.motionGate();
        check(!gate.isMotionEnabled() && !gate.isHeadOnlyEnabled(), "a new dispatcher has both switches off");
        // The method-level refusal calls (servo/angle, the servo/one trim write) read the helper's
        // own gate. Point it at the dispatcher's real gate, or refusalForServoMove(null, ...) would
        // refuse every servo and the "head only, servo 19 passes" check below could not pass.
        set(actionDirect, "motionGate", gate);

        String move = "f88f0800000" + "3" + "01" + "0c" + "ed"; // not a valid sum: only the scan of the command matters
        String moveAll = "f88f0800000" + "5" + "01" + "0e" + "ed";
        // Gate off: each gated route answers 403.
        for (int mode = 0; mode < 2; mode++) {
            if (mode == 1) gate.setHeadOnlyEnabled(true); // head only does not allow these routes
            String m = mode == 0 ? "gate off, " : "head only, ";
            check(status(d, false, "debug/serial/send", q("port", "chest", "hex", move)) == 403, m + "debug/serial/send chest move cmd 0x03 is 403");
            check(status(d, false, "debug/serial/send", q("port", "chest", "hex", moveAll)) == 403, m + "debug/serial/send chest move cmd 0x05 is 403");
            check(status(d, false, "debug/serial/send", q("port", "chest", "hex", "f8", "allow_sit_down", "true")) == 403, m + "debug/serial/send lone F8 is 403 (alpha2-brain#132)");
            check(status(d, false, "ubx/play", q("name", "x")) == 403, m + "ubx/play is 403");
            check(status(d, false, "action/play", q("name", "x")) == 403, m + "action/play is 403");
            check(status(d, false, "servo/one", q("id", "5", "angle", "100")) == 403, m + "servo/one servo 5 is 403");
            check(status(d, false, "servo/all", q("angles", "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20")) == 403, m + "servo/all is 403");
            check(status(d, false, "servo/angle-restore", q("id", "5")) == 403, m + "servo/angle-restore is 403");
            check(status(d, false, "servo/angle-all", q()) == 403, m + "servo/angle-all is 403");
            check(status(d, true, "servo/one", q("id", "5", "angle", "100")) == 403, m + "direct servo/one servo 5 is 403");
            check(status(d, true, "servo/all", q("angles", "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20")) == 403, m + "direct servo/all is 403");
        }
        // Head only allows servo 19: the dispatcher lets the call reach the helper (here it throws: the helper is empty).
        check(status(d, false, "servo/one", q("id", "19", "angle", "100")) != 403, "head only, servo/one servo 19 passes the gate");
        gate.setHeadOnlyEnabled(false);

        // servo/angle has no dispatcher guard, and the servo/one trim write is gated inside the
        // helper. Both are reached only when bootServoBlocked() returns null, so the boot flag is
        // on for this block; off, the route answers "booting" before the gate and the checks would
        // not prove the gate. Restored to off afterwards (the ubx/speed checks run with it off).
        Field bootField = MainActivity.class.getDeclaredField("bootServoReady");
        bootField.setAccessible(true);
        bootField.setBoolean(null, true);
        try {
            // alpha2-brain#122 blocker 1: the route servo/angle (cmd 6, read with release) is gated
            // only by the method-level call in servoAngleResponse.
            check(status(d, false, "servo/angle", q("id", "5")) == 403, "gate off, servo/angle servo 5 is 403");
            gate.setHeadOnlyEnabled(true);
            check(status(d, false, "servo/angle", q("id", "19")) != 403, "head only, servo/angle servo 19 passes the gate");
            check(status(d, false, "servo/angle", q("id", "5")) == 403, "head only, servo/angle servo 5 is 403");
            gate.setHeadOnlyEnabled(false);
            // alpha2-brain#122 finding 1: the method-level gate of servoOneResponse, before the trim
            // write (cmd 12). Called directly (the dispatcher guard would refuse servo 5 first), so
            // removing the gate call is detected: with it, servo 5 is refused (403) before the trim
            // write; without it, the trim write and the send run and the status is not 403.
            check(callServoOne(ubxApi, q("id", "5", "angle", "100", "trim", "10")) == 403,
                    "servo/one trim write: the gate refuses servo 5 before the trim write (alpha2-brain#122)");
        } finally {
            bootField.setBoolean(null, false);
        }
        // alpha2-brain#132: with the gate ON, a lone F8 is still refused (the firmware scan has no switch).
        gate.setMotionEnabled(true);
        check(status(d, false, "debug/serial/send", q("port", "chest", "hex", "f8", "allow_sit_down", "true")) == 403,
                "gate on, debug/serial/send lone F8 is 403 (alpha2-brain#132)");
        gate.setMotionEnabled(false);

        // ubx/speed: it sends no frame itself. With the gate off, the restart of a running action is refused.
        File speedFile = File.createTempFile("gate", ".ubx");
        speedFile.deleteOnExit();
        java.io.FileOutputStream fo = new java.io.FileOutputStream(speedFile);
        fo.write(1);
        fo.close();
        Thread alive = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(60000); } catch (InterruptedException e) { }
            }
        });
        alive.setDaemon(true);
        alive.start();
        set(player, "playThread", alive);
        set(ubxApi, "ubxPlayer", player);
        set(ubxApi, "speedLock", new Object());
        set(ubxApi, "appContext", empty(FakeContext.class));
        set(actionDirect, "lastPlayedFile", speedFile);
        android.util.Log.LINES.clear();
        int st = status(d, false, "ubx/speed", q("value", "1.5"));
        check(st == 200, "gate off, ubx/speed answers 200");
        boolean refused = false, tried = false;
        for (String l : android.util.Log.LINES) {
            if (l.startsWith("MotionGate: refused") && l.contains("ubx/speed")) refused = true;
            if (l.contains("speed restart failed")) tried = true;
        }
        check(refused, "gate off, ubx/speed logs the refusal of the restart");
        check(!tried, "gate off, ubx/speed does not try the restart");
        gate.setMotionEnabled(true);
        android.util.Log.LINES.clear();
        st = status(d, false, "ubx/speed", q("value", "1.5"));
        tried = false;
        for (String l : android.util.Log.LINES) if (l.contains("speed restart failed")) tried = true;
        check(tried, "gate on, ubx/speed tries the restart (the test file is not a valid action)");
        gate.setMotionEnabled(false);

        System.out.println("ApiDispatcherGateTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
