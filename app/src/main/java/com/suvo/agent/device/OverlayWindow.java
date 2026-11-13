package com.suvo.agent.device;

import android.content.Context;
import android.provider.Settings;
import android.view.WindowManager;

import com.suvo.agent.device.accessibility.AgentAccessibilityService;

/**
 * Your Friend — Original project by Suvo.
 *
 * Windows over other apps (the floating companion, the floating browser, the tap marker, the
 * keep-awake helper) need one of two things, not both: the "Display over other apps" permission,
 * or the app's Accessibility service, which may draw its own overlay windows. With either one the
 * floating parts work; the overlay permission is only asked for when neither is there.
 */
public final class OverlayWindow {

    private OverlayWindow() {
    }

    /** True when the app can put a window over other apps right now. */
    public static boolean available(Context ctx) {
        return Settings.canDrawOverlays(ctx) || AgentAccessibilityService.isConnected();
    }

    /** Through the overlay permission (true), or else through Accessibility. */
    private static boolean own(Context ctx) {
        return Settings.canDrawOverlays(ctx) || !AgentAccessibilityService.isConnected();
    }

    /** The window type to use. */
    public static int type(Context ctx) {
        return own(ctx) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    /** The window manager to add the window with (an accessibility overlay needs the service's own). */
    public static WindowManager manager(Context ctx) {
        if (!own(ctx)) {
            AgentAccessibilityService svc = AgentAccessibilityService.get();
            if (svc != null) return (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        }
        return (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * True when the app may open a screen from the background: with the overlay permission, or
     * while its Accessibility service runs (Android lets accessibility services start activities).
     */
    public static boolean canStartFromBackground(Context ctx) {
        return android.os.Build.VERSION.SDK_INT < 29 || available(ctx);
    }

    /** The context to start a screen from in the background (the accessibility service when that is what allows it). */
    public static Context starter(Context ctx) {
        if (!Settings.canDrawOverlays(ctx) && AgentAccessibilityService.isConnected()) {
            AgentAccessibilityService svc = AgentAccessibilityService.get();
            if (svc != null) return svc;
        }
        return ctx;
    }
}
