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

    /** The key of the stored default in the wake preferences. */
    static final String KEY_DEFAULT_ENABLED = "default_enabled";

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

    /**
     * The state now, from the stored value. A null stored value means that nothing is
     * stored: the built-in default applies.
     */
    boolean effective(Boolean stored) {
        return effective(stored != null ? stored.booleanValue() : BUILT_IN_DEFAULT);
    }

    /**
     * Parse a boolean value of a request. It accepts true, false, 1 and 0 (the same words as
     * ApiValidator). Any other value, or null, gives null: the caller must refuse it.
     */
    static Boolean parse(String value) {
        if (value == null) return null;
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return Boolean.FALSE;
        return null;
    }

    /** True when a test mode (an override) is active. */
    boolean overridden() {
        return override != null;
    }
}
