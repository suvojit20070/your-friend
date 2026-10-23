package com.suvo.agent.core.tools;

/**
 * Device capabilities a tool may depend on. Discovery (is it possible / granted?) is answered by a
 * {@link CapabilityProvider}; authorization (may the agent use it now?) by the approval layer.
 */
public enum Capability {
    NETWORK,
    MICROPHONE,
    CAMERA,
    NOTIFICATIONS,
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    OVERLAY,
    WORKSPACE_FILES,
    EXTERNAL_FILES,
    SHIZUKU,
    TERMUX,
    LOCAL_SHELL,
    INSTALL_PACKAGES,
    APP_LIST
}
