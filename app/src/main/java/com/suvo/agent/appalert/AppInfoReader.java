package com.suvo.agent.appalert;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Everything Android tells about an installed app, for the new-app alert: name, version, who
 * installed it (installer / initiating app — shows silent installs by Play services, the system or
 * an OEM store), when, system flags, size, SDK levels, signing certificate, permissions (dangerous
 * ones first, granted or not), components, and risky declarations (accessibility service, device
 * admin, notification listener, overlay, install other apps…).
 */
public final class AppInfoReader {

    private AppInfoReader() {
    }

    public static final class Info {
        public String pkg = "", label = "", version = "", installer = "", initiator = "", installerLabel = "";
        public long versionCode, firstInstall, lastUpdate, apkBytes;
        public int targetSdk, minSdk, uid;
        public boolean system, updatedSystem, enabled = true, debuggable, hasLauncher, installed = true;
        public String sha256 = "";
        public final List<String> dangerousGranted = new ArrayList<>(), dangerousNotGranted = new ArrayList<>(), otherPerms = new ArrayList<>();
        public final List<String> risky = new ArrayList<>();
        public int activities, services, receivers, providers;
        public String apkPath = "";

        /** One-line summary for the notification. */
        public String summary() {
            StringBuilder sb = new StringBuilder(pkg);
            if (!version.isEmpty()) sb.append(" · v").append(version);
            if (!installerLabel.isEmpty()) sb.append(" · ").append(installerLabel);
            return sb.toString();
        }

        /** Full text (details screen, copy, agent). */
        public String describe() {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append(label).append('\n').append(pkg).append("\n\n");
            if (!installed) sb.append("NOT INSTALLED ANY MORE\n\n");
            sb.append("Version: ").append(version).append(" (").append(versionCode).append(")\n");
            sb.append("Installed: ").append(firstInstall > 0 ? f.format(new Date(firstInstall)) : "?").append('\n');
            if (lastUpdate > 0 && lastUpdate != firstInstall) sb.append("Updated: ").append(f.format(new Date(lastUpdate))).append('\n');
            sb.append("Installed by: ").append(installer.isEmpty() ? "unknown (system / adb)" : installer + (installerLabel.isEmpty() ? "" : " — " + installerLabel)).append('\n');
            if (!initiator.isEmpty() && !initiator.equals(installer)) sb.append("Started by: ").append(initiator).append('\n');
            sb.append("Type: ").append(system ? (updatedSystem ? "system app (updated)" : "system app") : "user app")
                    .append(enabled ? "" : ", disabled").append(debuggable ? ", debuggable" : "").append(hasLauncher ? "" : ", no launcher icon (hidden)").append('\n');
            sb.append("Target SDK ").append(targetSdk).append(minSdk > 0 ? ", min SDK " + minSdk : "").append(", uid ").append(uid).append('\n');
            if (apkBytes > 0) sb.append("APK size: ").append(String.format(Locale.ROOT, "%.1f MB", apkBytes / 1048576.0)).append('\n');
            if (!apkPath.isEmpty()) sb.append("APK: ").append(apkPath).append('\n');
            if (!sha256.isEmpty()) sb.append("Signing SHA-256: ").append(sha256).append('\n');
            sb.append("Components: ").append(activities).append(" activities, ").append(services).append(" services, ")
                    .append(receivers).append(" receivers, ").append(providers).append(" providers\n");
            if (!risky.isEmpty()) {
                sb.append("\nWatch out:\n");
                for (String r : risky) sb.append("• ").append(r).append('\n');
            }
            if (!dangerousGranted.isEmpty()) sb.append("\nSensitive permissions GRANTED:\n• ").append(String.join("\n• ", dangerousGranted)).append('\n');
            if (!dangerousNotGranted.isEmpty()) sb.append("\nSensitive permissions requested (not granted):\n• ").append(String.join("\n• ", dangerousNotGranted)).append('\n');
            if (!otherPerms.isEmpty()) sb.append("\nOther permissions (").append(otherPerms.size()).append("):\n").append(String.join(", ", otherPerms)).append('\n');
            return sb.toString().trim();
        }
    }

    @SuppressWarnings("deprecation")
    public static Info read(Context ctx, String pkg) {
        Info in = new Info();
        in.pkg = pkg;
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi;
        int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        else flags |= PackageManager.GET_SIGNATURES;
        try {
            pi = pm.getPackageInfo(pkg, flags);
        } catch (Exception e) {
            // Some huge apps fail with everything at once: try the basics.
            try {
                pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            } catch (Exception e2) {
                in.installed = false;
                in.label = pkg;
                return in;
            }
        }
        ApplicationInfo ai = pi.applicationInfo;
        in.label = ai == null ? pkg : String.valueOf(ai.loadLabel(pm));
        in.version = pi.versionName == null ? "" : pi.versionName;
        in.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        in.firstInstall = pi.firstInstallTime;
        in.lastUpdate = pi.lastUpdateTime;
        if (ai != null) {
            in.targetSdk = ai.targetSdkVersion;
            if (Build.VERSION.SDK_INT >= 24) in.minSdk = ai.minSdkVersion;
            in.uid = ai.uid;
            in.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            in.updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            in.enabled = ai.enabled;
            in.debuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            in.apkPath = ai.sourceDir == null ? "" : ai.sourceDir;
            long size = ai.sourceDir == null ? 0 : new File(ai.sourceDir).length();
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) size += new File(s).length();
            in.apkBytes = size;
        }
        in.hasLauncher = pm.getLaunchIntentForPackage(pkg) != null;

        // Who installed it.
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo src = pm.getInstallSourceInfo(pkg);
                in.installer = nz(src.getInstallingPackageName());
                in.initiator = nz(src.getInitiatingPackageName());
            } else {
                in.installer = nz(pm.getInstallerPackageName(pkg));
            }
        } catch (Exception ignored) {
        }
        in.installerLabel = installerLabel(ctx, in.installer);

        // Signing certificate.
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            } else if (pi.signatures != null) {
                sigs = pi.signatures;
            }
            if (sigs != null && sigs.length > 0) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[sigs.length - 1].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format(Locale.ROOT, "%02X", d[i]));
                }
                in.sha256 = sb.toString();
            }
        } catch (Exception ignored) {
        }

        // Permissions.
        if (pi.requestedPermissions != null) {
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                String p = pi.requestedPermissions[i];
                boolean granted = pi.requestedPermissionsFlags != null && i < pi.requestedPermissionsFlags.length
                        && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                boolean dangerous = false;
                try {
                    PermissionInfo info = pm.getPermissionInfo(p, 0);
                    int base = Build.VERSION.SDK_INT >= 28 ? info.getProtection() : info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                    dangerous = base == PermissionInfo.PROTECTION_DANGEROUS;
                } catch (Exception ignored) {
                }
                String shortName = p.startsWith("android.permission.") ? p.substring(19) : p;
                if (dangerous) (granted ? in.dangerousGranted : in.dangerousNotGranted).add(shortName);
                else in.otherPerms.add(shortName);
                switch (p) {
                    case "android.permission.SYSTEM_ALERT_WINDOW":
                        in.risky.add("Can draw over other apps");
                        break;
                    case "android.permission.REQUEST_INSTALL_PACKAGES":
                    case "android.permission.INSTALL_PACKAGES":
                        in.risky.add("Can install other apps");
                        break;
                    case "android.permission.QUERY_ALL_PACKAGES":
                        in.risky.add("Can see every app on the phone");
                        break;
                    case "android.permission.READ_SMS":
                    case "android.permission.RECEIVE_SMS":
                        in.risky.add("Can read SMS (OTP codes)");
                        break;
                    case "android.permission.READ_CALL_LOG":
                        in.risky.add("Can read the call log");
                        break;
                    case "android.permission.MANAGE_EXTERNAL_STORAGE":
                        in.risky.add("Can read all files");
                        break;
                    case "android.permission.PACKAGE_USAGE_STATS":
                        in.risky.add("Can see which apps you use");
                        break;
                    case "android.permission.RECEIVE_BOOT_COMPLETED":
                        in.risky.add("Starts by itself after every reboot");
                        break;
                    default:
                        break;
                }
            }
        }

        // Components and risky declarations.
        if (pi.activities != null) in.activities = pi.activities.length;
        if (pi.receivers != null) in.receivers = pi.receivers.length;
        if (pi.providers != null) in.providers = pi.providers.length;
        if (pi.services != null) {
            in.services = pi.services.length;
            for (ServiceInfo s : pi.services) {
                String perm = s.permission == null ? "" : s.permission;
                if (perm.equals("android.permission.BIND_ACCESSIBILITY_SERVICE")) in.risky.add("Has an accessibility service (can read and control the screen)");
                else if (perm.equals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")) in.risky.add("Can read all notifications");
                else if (perm.equals("android.permission.BIND_DEVICE_ADMIN")) in.risky.add("Device admin");
                else if (perm.equals("android.permission.BIND_VPN_SERVICE")) in.risky.add("VPN service (can see network traffic)");
                else if (perm.equals("android.permission.BIND_INPUT_METHOD")) in.risky.add("Keyboard (can see what you type)");
            }
        }
        if (pi.receivers != null) {
            for (android.content.pm.ActivityInfo r : pi.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(r.permission) && !in.risky.contains("Device admin")) in.risky.add("Device admin");
            }
        }
        if (!in.hasLauncher && !in.system) in.risky.add(0, "No launcher icon: it does not show in the app drawer");
        return in;
    }

    /** A readable name for the installer package. */
    public static String installerLabel(Context ctx, String installer) {
        if (installer == null || installer.isEmpty()) return "";
        switch (installer) {
            case "com.android.vending":
                return "Google Play Store";
            case "com.google.android.gms":
                return "Google Play services (silent)";
            case "com.google.android.packageinstaller":
            case "com.android.packageinstaller":
                return "Package installer (APK file)";
            case "com.android.shell":
                return "ADB / shell";
            case "android":
                return "Android system";
            default:
                try {
                    PackageManager pm = ctx.getPackageManager();
                    return String.valueOf(pm.getApplicationInfo(installer, 0).loadLabel(pm));
                } catch (Exception e) {
                    return installer;
                }
        }
    }

    public static Intent uninstallIntent(String pkg) {
        return new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent appInfoIntent(String pkg) {
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
