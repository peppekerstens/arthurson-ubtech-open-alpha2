package com.open.alpha2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Unit test for the sleep_mode mapping of WakeController (alpha2-brain#24, #30). It also reads
 * the source of WakeController: the sleep and wake path must call no route and no method that
 * sends cmd 0x19 or cmd 0x40. Pure java.*. Run from the repository root:
 * scripts/test-apivalidator.py.
 */
public class SleepModePolicyTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(Object want, Object got, String name) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        }
    }

    public static void main(String[] args) throws Exception {
        eq("release", SleepModePolicy.toStored("release"), "store.release");
        eq("release", SleepModePolicy.toStored("servo_power"), "store.servoPower");
        eq("release", SleepModePolicy.toStored("power_save"), "store.powerSave");
        eq(null, SleepModePolicy.toStored("sit"), "store.bad");
        eq(null, SleepModePolicy.toStored(""), "store.empty");
        eq(null, SleepModePolicy.toStored(null), "store.null");
        eq(null, SleepModePolicy.toStored("RELEASE"), "store.case");
        // wake/get: each stored value (also an old one) reads as release
        eq("release", SleepModePolicy.effective("release"), "get.release");
        eq("release", SleepModePolicy.effective("servo_power"), "get.oldServoPower");
        eq("release", SleepModePolicy.effective("power_save"), "get.oldPowerSave");
        eq("release", SleepModePolicy.effective(null), "get.unset");

        String src = new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/open/alpha2/WakeController.java")), StandardCharsets.UTF_8);
        for (String r : SleepModePolicy.FORBIDDEN_ROUTES) {
            eq(false, src.contains("\"" + r), "source.noRoute " + r);
        }
        String[] forbiddenCalls = {"setServoPower(", "setPowerSave(", "sendRaw(", "CHEST_SERVO_POWER",
                "CHEST_POWER_SAVE", "HardwareDirectManager"};
        for (String c : forbiddenCalls) {
            eq(false, src.contains(c), "source.noCall " + c);
        }
        // sleep releases with cmd 6 (servo/angle) after the squat
        int rest = src.indexOf("private void rest(boolean sleep)");
        int restEnd = src.indexOf("private void wakeBody(", rest);
        eq(true, rest > 0 && restEnd > rest, "source.restFound");
        String restBody = src.substring(rest, restEnd);
        eq(true, restBody.contains("\"servo/angle\""), "source.restUsesCmd6");
        eq(true, restBody.indexOf("SQUAT_FILE") < restBody.indexOf("\"servo/angle\""), "source.squatFirst");
        eq(true, src.contains("SleepModePolicy.toStored("), "source.usesPolicyStore");
        eq(true, src.contains("SleepModePolicy.effective("), "source.usesPolicyGet");

        System.out.println("SleepModePolicyTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
