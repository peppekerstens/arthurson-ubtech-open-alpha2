package com.open.alpha2;

/**
 * Tests of WakeEnabledPolicy (alpha2-brain#136). A plain Java runner, with no JUnit.
 * Run: python scripts/test-apivalidator.py
 *
 * An app restart is a new WakeEnabledPolicy object: the memory is empty.
 */
public final class WakeEnabledPolicyTest {
    private static int passed = 0;
    private static int failed = 0;

    private WakeEnabledPolicyTest() {}

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) {
        // wake/set?enabled=false: off now, on again after a restart.
        WakeEnabledPolicy p = new WakeEnabledPolicy();
        p.setOverride(false);
        check("off after set false", !p.effective(WakeEnabledPolicy.BUILT_IN_DEFAULT));
        check("override is active", p.overridden());
        WakeEnabledPolicy restarted = new WakeEnabledPolicy();
        check("restart gives enabled true", restarted.effective(WakeEnabledPolicy.BUILT_IN_DEFAULT));
        check("restart clears the override", !restarted.overridden());

        check("built-in default is on", WakeEnabledPolicy.BUILT_IN_DEFAULT);

        // The owner default off stays off after a restart.
        check("owner default off stays off", !restarted.effective(false));

        // enabled=true wins over an owner default off, until a restart.
        WakeEnabledPolicy q = new WakeEnabledPolicy();
        q.setOverride(true);
        check("set true wins over default off", q.effective(false));
        check("restart returns to default off", !new WakeEnabledPolicy().effective(false));

        System.out.println("WakeEnabledPolicyTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
