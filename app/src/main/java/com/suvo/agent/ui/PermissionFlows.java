package com.suvo.agent.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.suvo.agent.R;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.device.capture.ProjectionRequestActivity;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.terminal.TermuxBackend;

/** Opens the correct system flow for each capability. */
public final class PermissionFlows {

    public static final int REQ_RUNTIME = 501;

    private PermissionFlows() {
    }

    public static void fix(Activity a, Capability c) {
        com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
        CapabilityState s = g.capabilities.state(c);
        if (s == CapabilityState.DISABLED_BY_USER) {
            a.startActivity(new Intent(a, PermissionsActivity.class));
            Ui.toast(a, R.string.perm_enable_in_privacy);
            return;
        }
        try {
            switch (c) {
                case MICROPHONE:
                    a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RUNTIME);
                    break;
                case CAMERA:
                    a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_RUNTIME);
                    break;
                case NOTIFICATIONS:
                    if (Build.VERSION.SDK_INT >= 33 && s == CapabilityState.PERMISSION_REQUIRED) {
                        a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_RUNTIME);
                    } else {
                        a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
                    }
                    break;
                case ACCESSIBILITY:
                    Ui.show(Ui.dialog(a).setTitle(R.string.perm_accessibility_title).setMessage(R.string.perm_accessibility_explain)
                            .setPositiveButton(R.string.open_settings, (d, w) -> a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                            .setNegativeButton(android.R.string.cancel, null));
                    break;
                case SCREEN_CAPTURE:
                    new Thread(() -> {
                        try {
                            ProjectionRequestActivity.requestAndWait(a, 60_000);
                        } catch (InterruptedException ignored) {
                        }
                    }).start();
                    break;
                case OVERLAY:
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())));
                    break;
                case EXTERNAL_FILES:
                    a.startActivity(new Intent(a, FilesActivity.class).putExtra(FilesActivity.EXTRA_ADD_FOLDER, true));
                    break;
                case SHIZUKU:
                    shizuku(a, g.shizuku);
                    break;
                case TERMUX:
                    termux(a, g.termux);
                    break;
                case INSTALL_PACKAGES:
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                    break;
                default:
                    appDetails(a);
            }
        } catch (ActivityNotFoundException e) {
            appDetails(a);
        }
    }

    public static void appDetails(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
    }

    public static void shizuku(Activity a, ShizukuManager s) {
        switch (s.state()) {
            case NOT_INSTALLED:
                Ui.show(Ui.dialog(a).setTitle(R.string.shizuku_title).setMessage(R.string.shizuku_install_explain)
                        .setPositiveButton(R.string.open_website, (d, w) -> openUrl(a, "https://shizuku.rikka.app/download/"))
                        .setNegativeButton(android.R.string.cancel, null));
                break;
            case NOT_RUNNING:
                Ui.show(Ui.dialog(a).setTitle(R.string.shizuku_title).setMessage(R.string.shizuku_start_explain)
                        .setPositiveButton(R.string.open_shizuku, (d, w) -> {
                            Intent i = a.getPackageManager().getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE);
                            if (i != null) a.startActivity(i);
                        })
                        .setNegativeButton(android.R.string.cancel, null));
                break;
            case PERMISSION_DENIED:
                Ui.show(Ui.dialog(a).setTitle(R.string.shizuku_title).setMessage(R.string.shizuku_denied_explain)
                        .setPositiveButton(android.R.string.ok, null));
                break;
            case UNSUPPORTED_VERSION:
                Ui.toast(a, R.string.shizuku_too_old);
                break;
            default:
                if (!s.requestPermission()) Ui.toast(a, R.string.shizuku_not_running);
        }
    }

    public static void termux(Activity a, TermuxBackend t) {
        if (!t.isInstalled()) {
            Ui.show(Ui.dialog(a).setTitle(R.string.termux_title).setMessage(R.string.termux_install_explain)
                    .setPositiveButton(R.string.open_website, (d, w) -> openUrl(a, "https://f-droid.org/packages/com.termux/"))
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        Ui.show(Ui.dialog(a).setTitle(R.string.termux_title).setMessage(R.string.termux_setup_explain)
                .setPositiveButton(R.string.grant, (d, w) -> a.requestPermissions(new String[]{TermuxBackend.PERMISSION}, REQ_RUNTIME))
                .setNeutralButton(R.string.copy_command, (d, w) -> Ui.copy(a, "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"))
                .setNegativeButton(android.R.string.cancel, null));
    }

    public static void openUrl(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Ui.toast(a, url);
        }
    }

    public static String stateLabel(Activity a, CapabilityState s) {
        switch (s) {
            case AVAILABLE:
                return a.getString(R.string.cap_available);
            case PERMISSION_REQUIRED:
                return a.getString(R.string.cap_permission_required);
            case AUTHORIZATION_REQUIRED:
                return a.getString(R.string.cap_authorization_required);
            case UNSUPPORTED:
                return a.getString(R.string.cap_unsupported);
            case TEMPORARILY_UNAVAILABLE:
                return a.getString(R.string.cap_temporarily_unavailable);
            case DISABLED_BY_USER:
                return a.getString(R.string.cap_disabled_by_user);
            default:
                return a.getString(R.string.cap_failed);
        }
    }

    public static int stateColor(Activity a, CapabilityState s) {
        switch (s) {
            case AVAILABLE:
                return a.getColor(R.color.success);
            case DISABLED_BY_USER:
            case UNSUPPORTED:
                return a.getColor(R.color.text_secondary);
            case TEMPORARILY_UNAVAILABLE:
                return a.getColor(R.color.warning);
            default:
                return a.getColor(R.color.danger);
        }
    }

    public static int capabilityName(Capability c) {
        switch (c) {
            case NETWORK:
                return R.string.cap_network;
            case MICROPHONE:
                return R.string.cap_microphone;
            case CAMERA:
                return R.string.cap_camera;
            case NOTIFICATIONS:
                return R.string.cap_notifications;
            case ACCESSIBILITY:
                return R.string.cap_accessibility;
            case SCREEN_CAPTURE:
                return R.string.cap_screen_capture;
            case OVERLAY:
                return R.string.cap_overlay;
            case WORKSPACE_FILES:
                return R.string.cap_workspace;
            case EXTERNAL_FILES:
                return R.string.cap_external_files;
            case SHIZUKU:
                return R.string.cap_shizuku;
            case TERMUX:
                return R.string.cap_termux;
            case LOCAL_SHELL:
                return R.string.cap_local_shell;
            case INSTALL_PACKAGES:
                return R.string.cap_install;
            default:
                return R.string.cap_app_list;
        }
    }

    /** One-line explanation of why the agent may need a capability. */
    public static int capabilityWhy(Capability c) {
        switch (c) {
            case NETWORK:
                return R.string.cap_network_why;
            case MICROPHONE:
                return R.string.cap_microphone_why;
            case CAMERA:
                return R.string.cap_camera_why;
            case NOTIFICATIONS:
                return R.string.cap_notifications_why;
            case ACCESSIBILITY:
                return R.string.cap_accessibility_why;
            case SCREEN_CAPTURE:
                return R.string.cap_screen_capture_why;
            case OVERLAY:
                return R.string.cap_overlay_why;
            case WORKSPACE_FILES:
                return R.string.cap_workspace_why;
            case EXTERNAL_FILES:
                return R.string.cap_external_files_why;
            case SHIZUKU:
                return R.string.cap_shizuku_why;
            case TERMUX:
                return R.string.cap_termux_why;
            case LOCAL_SHELL:
                return R.string.cap_local_shell_why;
            case INSTALL_PACKAGES:
                return R.string.cap_install_why;
            default:
                return R.string.cap_app_list_why;
        }
    }
}
