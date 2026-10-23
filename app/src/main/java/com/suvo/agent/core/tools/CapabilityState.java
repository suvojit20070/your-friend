package com.suvo.agent.core.tools;

/** Accurate, user-visible state of a capability. */
public enum CapabilityState {
    AVAILABLE,
    /** A runtime permission the app can request. */
    PERMISSION_REQUIRED,
    /** Needs a settings toggle / external app authorization (Accessibility, Shizuku, overlay...). */
    AUTHORIZATION_REQUIRED,
    /** The device / Android version / installed apps cannot provide it. */
    UNSUPPORTED,
    /** Granted but currently not usable (service not connected, projection session ended...). */
    TEMPORARILY_UNAVAILABLE,
    /** Turned off by the user in Privacy settings. */
    DISABLED_BY_USER,
    FAILED;

    public boolean usable() {
        return this == AVAILABLE;
    }
}
