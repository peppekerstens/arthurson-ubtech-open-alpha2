package com.open.alpha2;

/**
 * The sleep_mode setting of WakeController (alpha2-brain#24). Pure Java, no Android classes
 * (unit test: SleepModePolicyTest, alpha2-brain#30).
 *
 * <p>Sleep releases the servos with cmd 6 only (a servo angle read for each servo). The old
 * modes servo_power (cmd 0x19 [00]) and power_save (cmd 0x40 [01 01]) started the stock
 * "Sit down" move of the chest. The app still accepts them for old clients, but it stores
 * and uses release.
 */
final class SleepModePolicy {
    static final String RELEASE = "release";
    /** The values that wake/set accepts. */
    static final String[] ACCEPTED = {"release", "servo_power", "power_save"};
    /** Routes that the sleep and wake path must never call: they can start "Sit down". */
    static final String[] FORBIDDEN_ROUTES = {"chest/servo_power", "chest/power_save", "serial/send"};

    private SleepModePolicy() {}

    /** The value to store for a requested sleep_mode, or null for a bad value. */
    static String toStored(String requested) {
        for (String a : ACCEPTED) if (a.equals(requested)) return RELEASE;
        return null;
    }

    /** The mode that the app uses (and wake/get returns) for each stored value. */
    static String effective(String stored) {
        return RELEASE;
    }
}
