package com.suvo.agent.device;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import com.suvo.agent.App;
import com.suvo.agent.R;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityProvider;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.device.capture.ScreenCaptureService;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.files.SafStore;
import com.suvo.agent.terminal.TermuxBackend;

/**
 * Real capability discovery. States come from Android (permission checks, enabled services,
 * system settings) and from the user's Privacy switches — never from what the model claims.
 */
public final class CapabilityRegistry implements CapabilityProvider {

    private final Context ctx;
    private final com.suvo.agent.data.Settings settings;
    private final ShizukuManager shizuku;
    private final TermuxBackend termux;
    private final SafStore saf;

    public CapabilityRegistry(Context ctx, com.suvo.agent.data.Settings settings, ShizukuManager shizuku,
                              TermuxBackend termux, SafStore saf) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.shizuku = shizuku;
        this.termux = termux;
        this.saf = saf;
    }

    private boolean granted(String perm) {
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public CapabilityState state(Capability c) {
        switch (c) {
            case NETWORK:
                return CapabilityState.AVAILABLE;
            case MICROPHONE:
                if (!settings.bool(com.suvo.agent.data.Settings.ALLOW_VOICE_INPUT, true)) return CapabilityState.DISABLED_BY_USER;
                return granted(Manifest.permission.RECORD_AUDIO) ? CapabilityState.AVAILABLE : CapabilityState.PERMISSION_REQUIRED;
            case CAMERA:
                if (!settings.bool(com.suvo.agent.data.Settings.ALLOW_CAMERA, true)) return CapabilityState.DISABLED_BY_USER;
                if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return CapabilityState.UNSUPPORTED;
                if (!granted(Manifest.permission.CAMERA)) return CapabilityState.PERMISSION_REQUIRED;
                return App.isInForeground() ? CapabilityState.AVAILABLE : CapabilityState.TEMPORARILY_UNAVAILABLE;
            case NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return CapabilityState.PERMISSION_REQUIRED;
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                return nm != null && nm.areNotificationsEnabled() ? CapabilityState.AVAILABLE : CapabilityState.AUTHORIZATION_REQUIRED;
            case ACCESSIBILITY:
                if (!settings.bool(com.suvo.agent.data.Settings.ALLOW_SCREEN_CONTROL, true)) return CapabilityState.DISABLED_BY_USER;
                if (AgentAccessibilityService.isConnected()) return CapabilityState.AVAILABLE;
                return isAccessibilityEnabledInSettings() ? CapabilityState.TEMPORARILY_UNAVAILABLE : CapabilityState.AUTHORIZATION_REQUIRED;
            case SCREEN_CAPTURE:
                if (!settings.bool(com.suvo.agent.data.Settings.ALLOW_SCREENSHOT, true)) return CapabilityState.DISABLED_BY_USER;
                if (AgentAccessibilityService.isConnected() && AgentAccessibilityService.canCaptureScreen()) return CapabilityState.AVAILABLE;
                if (ScreenCaptureService.isActive()) return CapabilityState.AVAILABLE;
                if (shizuku.state() == ShizukuManager.State.READY && settings.bool(com.suvo.agent.data.Settings.ALLOW_SHIZUKU, true)) return CapabilityState.AVAILABLE;
                // MediaProjection consent can be requested while the app is visible.
                return App.isInForeground() ? CapabilityState.AVAILABLE : CapabilityState.AUTHORIZATION_REQUIRED;
            case OVERLAY:
                return Settings.canDrawOverlays(ctx) ? CapabilityState.AVAILABLE : CapabilityState.AUTHORIZATION_REQUIRED;
            case WORKSPACE_FILES:
                return CapabilityState.AVAILABLE;
            case EXTERNAL_FILES:
                return saf.grants().isEmpty() ? CapabilityState.AUTHORIZATION_REQUIRED : CapabilityState.AVAILABLE;
            case SHIZUKU:
                if (!settings.bool(com.suvo.agent.data.Settings.ALLOW_SHIZUKU, true)) return CapabilityState.DISABLED_BY_USER;
                switch (shizuku.state()) {
                    case READY:
                        return CapabilityState.AVAILABLE;
                    case NOT_INSTALLED:
                    case UNSUPPORTED_VERSION:
                        return CapabilityState.UNSUPPORTED;
                    case NOT_RUNNING:
                        return CapabilityState.TEMPORARILY_UNAVAILABLE;
                    default:
                        return CapabilityState.AUTHORIZATION_REQUIRED;
                }
            case TERMUX:
                if (!termux.isInstalled()) return CapabilityState.UNSUPPORTED;
                return termux.hasPermission() ? CapabilityState.AVAILABLE : CapabilityState.PERMISSION_REQUIRED;
            case LOCAL_SHELL:
                return CapabilityState.AVAILABLE;
            case INSTALL_PACKAGES:
                return ctx.getPackageManager().canRequestPackageInstalls() ? CapabilityState.AVAILABLE : CapabilityState.AUTHORIZATION_REQUIRED;
            case APP_LIST:
                return CapabilityState.AVAILABLE;
            default:
                return CapabilityState.UNSUPPORTED;
        }
    }

    @Override
    public String detail(Capability c) {
        CapabilityState s = state(c);
        if (s == CapabilityState.AVAILABLE) return ctx.getString(R.string.cap_detail_available);
        if (s == CapabilityState.DISABLED_BY_USER) return ctx.getString(R.string.cap_detail_disabled_by_user);
        switch (c) {
            case MICROPHONE:
                return ctx.getString(R.string.cap_detail_microphone);
            case CAMERA:
                return s == CapabilityState.TEMPORARILY_UNAVAILABLE ? ctx.getString(R.string.cap_detail_camera_foreground)
                        : s == CapabilityState.UNSUPPORTED ? ctx.getString(R.string.cap_detail_camera_missing)
                        : ctx.getString(R.string.cap_detail_camera);
            case NOTIFICATIONS:
                return ctx.getString(R.string.cap_detail_notifications);
            case ACCESSIBILITY:
                return s == CapabilityState.TEMPORARILY_UNAVAILABLE ? ctx.getString(R.string.cap_detail_accessibility_restart)
                        : ctx.getString(R.string.cap_detail_accessibility);
            case SCREEN_CAPTURE:
                return ctx.getString(R.string.cap_detail_screen_capture);
            case OVERLAY:
                return ctx.getString(R.string.cap_detail_overlay);
            case EXTERNAL_FILES:
                return ctx.getString(R.string.cap_detail_external_files);
            case SHIZUKU:
                String why = shizuku.unavailableReason();
                return why == null ? "" : why;
            case TERMUX:
                String t = termux.unavailableReason();
                return t == null ? "" : t;
            case INSTALL_PACKAGES:
                return ctx.getString(R.string.cap_detail_install);
            default:
                return s.name();
        }
    }

    public boolean isAccessibilityEnabledInSettings() {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(ctx, AgentAccessibilityService.class).flattenToString();
        for (String s : enabled.split(":")) if (s.equalsIgnoreCase(me)) return true;
        return false;
    }

    public boolean speechRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    /** One-line-per-capability summary for the system prompt. */
    public String summaryForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Capability c : Capability.values()) {
            CapabilityState s = state(c);
            sb.append("- ").append(c.name().toLowerCase()).append(": ").append(s.name().toLowerCase());
            if (s != CapabilityState.AVAILABLE) {
                String d = detail(c);
                if (d != null && !d.isEmpty()) sb.append(" (").append(d).append(')');
            }
            sb.append('\n');
        }
        if (shizuku.state() == ShizukuManager.State.READY) sb.append("- shizuku identity: ").append(shizuku.identity()).append('\n');
        return sb.toString().trim();
    }
}
