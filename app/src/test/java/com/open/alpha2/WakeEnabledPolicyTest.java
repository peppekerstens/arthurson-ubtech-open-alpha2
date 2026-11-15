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

        // enabledNow: nothing stored gives the built-in default (on); a stored value decides.
        WakeEnabledPolicy n = new WakeEnabledPolicy();
        check("nothing stored gives on", n.effective((Boolean) null));
        check("stored false gives off", !n.effective(Boolean.FALSE));
        check("stored true gives on", n.effective(Boolean.TRUE));
        n.setOverride(true);
        check("override true wins over stored false", n.effective(Boolean.FALSE));
        n.setOverride(false);
        check("override false wins over stored true", !n.effective(Boolean.TRUE));
        check("override false wins over nothing stored", !n.effective((Boolean) null));

        // The pref key of the stored default.
        check("pref key is default_enabled", "default_enabled".equals(WakeEnabledPolicy.KEY_DEFAULT_ENABLED));

        System.out.println("WakeEnabledPolicyTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
