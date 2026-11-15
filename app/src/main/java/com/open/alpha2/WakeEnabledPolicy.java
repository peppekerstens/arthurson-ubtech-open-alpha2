package com.open.alpha2;

/**
 * The on/off state of the wake word (alpha2-brain#136). Pure Java, no Android classes.
 *
 * wake/set?enabled=... is a test mode. It lives in memory only: a restart of the app
 * makes a new object, and the override is gone. After a restart the stored default
 * decides. The stored default is on, unless the owner sets default_enabled=false.
 */
final class WakeEnabledPolicy {
    /** The stored default when nothing is stored: the wake word is on. */
    static final boolean BUILT_IN_DEFAULT = true;

    /** null: no override, use the stored default. */
    private volatile Boolean override;

    /** wake/set?enabled=... : the override holds until the app restarts. */
    void setOverride(boolean enabled) {
        override = Boolean.valueOf(enabled);
    }

    /** The state now: the override if there is one, else the stored default. */
    boolean effective(boolean storedDefault) {
        Boolean o = override;
        return o != null ? o.booleanValue() : storedDefault;
    }

    /** True when a test mode (an override) is active. */
    boolean overridden() {
        return override != null;
    }
}
