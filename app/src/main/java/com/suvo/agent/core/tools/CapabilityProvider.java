package com.suvo.agent.core.tools;

/** Answers capability discovery questions. The Android implementation inspects real system state. */
public interface CapabilityProvider {

    CapabilityState state(Capability capability);

    /** Human-readable explanation of the current state, e.g. what to enable in Settings. */
    String detail(Capability capability);

    /** Provider where everything is available (tests). */
    CapabilityProvider ALL = new CapabilityProvider() {
        @Override
        public CapabilityState state(Capability capability) {
            return CapabilityState.AVAILABLE;
        }

        @Override
        public String detail(Capability capability) {
            return "available";
        }
    };
}
