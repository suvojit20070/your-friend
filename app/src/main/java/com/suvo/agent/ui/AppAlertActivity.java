package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.appalert.AppAlerts;
import com.suvo.agent.appalert.AppInfoReader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Settings → New app install alert: the switch, a manual scan, and the log of every app installed
 * since it was turned on (with "not notified" for installs whose notification could not be shown).
 * Opened with {@link AppAlerts#EXTRA_PKG} (from the notification) it shows everything about one
 * app with Uninstall / App info / Open / Copy.
 */
public final class AppAlertActivity extends BaseActivity implements AppAlerts.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private String pkg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        pkg = getIntent().getStringExtra(AppAlerts.EXTRA_PKG);
        setTopBar(pkg == null ? R.string.appalert_title : R.string.appalert_details_title);
        AppAlerts.addListener(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pkg = intent.getStringExtra(AppAlerts.EXTRA_PKG);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onDestroy() {
        AppAlerts.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onAppAlertsChanged() {
        main.post(() -> {
            if (!isFinishing()) build();
        });
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        if (box == null) return;
        box.removeAllViews();
        Rows r = new Rows(this, box);
        if (pkg != null) details(r, pkg);
        else list(r);
    }

    // ------------------------------------------------------------------ list

    private void list(Rows r) {
        Palette pal = Palette.of(this);
        r.note(getString(R.string.appalert_intro));
        r.switchRow(R.drawable.ic_shield, R.string.appalert_enable, R.string.appalert_enable_desc, AppAlerts.ENABLED, true, on -> {
            if (on) AppAlerts.init(this);
            else AppAlerts.stop(this);
            build();
        });
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null && !nm.areNotificationsEnabled()) {
            r.row(R.drawable.ic_bell, getString(R.string.appalert_notif_off), getString(R.string.appalert_notif_off_desc),
                    r.chip(getString(R.string.ar_off), pal.error), v -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
        }
        r.row(R.drawable.ic_refresh, getString(R.string.appalert_scan), getString(R.string.appalert_scan_desc), null, v -> {
            Ui.toast(this, R.string.appalert_scanning);
            new Thread(() -> {
                AppAlerts.scan(getApplicationContext());
                main.post(() -> Ui.toast(this, R.string.appalert_scan_done));
            }, "yf-appalert-manual").start();
        });

        List<AppAlerts.Entry> log = AppAlerts.log(this);
        r.section(R.string.appalert_log);
        if (log.isEmpty()) r.note(getString(R.string.appalert_log_empty));
        SimpleDateFormat f = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        for (AppAlerts.Entry e : log) {
            StringBuilder sub = new StringBuilder(e.pkg);
            sub.append("\n").append(f.format(new Date(e.time)));
            sub.append(" · ").append(e.installerLabel.isEmpty() ? getString(R.string.appalert_unknown_installer) : e.installerLabel);
            if (e.late) sub.append(" · ").append(getString(R.string.appalert_late_short));
            String chip;
            int color;
            if (e.removed) {
                chip = getString(R.string.appalert_removed);
                color = pal.onSurfaceVariant;
            } else if (!e.notified) {
                chip = getString(R.string.appalert_not_notified);
                color = pal.error;
            } else if (e.risky > 0) {
                chip = "⚠ " + e.risky;
                color = pal.error;
            } else {
                chip = getString(R.string.appalert_notified);
                color = pal.tertiary;
            }
            r.row(R.drawable.ic_shield, (e.seen ? "" : "● ") + e.label, sub, r.chip(chip, color), v -> {
                Intent i = new Intent(this, AppAlertActivity.class).putExtra(AppAlerts.EXTRA_PKG, e.pkg);
                startActivity(i);
            });
        }
        if (!log.isEmpty()) {
            r.row(R.drawable.ic_delete, getString(R.string.appalert_clear), null, null,
                    v -> Ui.confirm(this, R.string.appalert_clear, getString(R.string.appalert_clear_confirm), () -> AppAlerts.clear(this)));
        }
        AppAlerts.markSeen(this);
    }

    // ------------------------------------------------------------------ one app

    private void details(Rows r, String pkg) {
        AppInfoReader.Info in = AppInfoReader.read(this, pkg);
        Palette pal = Palette.of(this);
        r.row(R.drawable.ic_shield, in.label, in.pkg + (in.installed ? "" : "\n" + getString(R.string.appalert_removed)),
                in.installed ? null : r.chip(getString(R.string.appalert_removed), pal.onSurfaceVariant), null);
        if (in.installed) {
            r.button(R.string.appalert_uninstall, true, v -> {
                try {
                    startActivity(AppInfoReader.uninstallIntent(pkg));
                } catch (RuntimeException e) {
                    Ui.toast(this, e.getMessage());
                }
            });
            r.button(R.string.appalert_app_info, false, v -> {
                try {
                    startActivity(AppInfoReader.appInfoIntent(pkg));
                } catch (RuntimeException ignored) {
                }
            });
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) r.button(R.string.appalert_open, false, v -> startActivity(launch));
        }
        r.button(R.string.copy, false, v -> {
            Ui.copy(this, in.describe());
            Ui.toast(this, R.string.copied);
        });
        if (!in.installed) return;

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        r.section(R.string.appalert_sec_source);
        r.row(0, getString(R.string.appalert_installed_by), in.installer.isEmpty() ? getString(R.string.appalert_unknown_installer)
                : in.installerLabel + "\n" + in.installer, null, null);
        if (!in.initiator.isEmpty() && !in.initiator.equals(in.installer)) {
            r.row(0, getString(R.string.appalert_started_by), AppInfoReader.installerLabel(this, in.initiator) + "\n" + in.initiator, null, null);
        }
        r.row(0, getString(R.string.appalert_installed_at), in.firstInstall > 0 ? f.format(new Date(in.firstInstall)) : "?", null, null);
        if (in.lastUpdate > 0 && in.lastUpdate != in.firstInstall) r.row(0, getString(R.string.appalert_updated_at), f.format(new Date(in.lastUpdate)), null, null);

        if (!in.risky.isEmpty()) {
            r.section(R.string.appalert_sec_risk);
            for (String s : in.risky) r.row(0, "⚠ " + s, null, null, null);
        }

        r.section(R.string.appalert_sec_app);
        r.row(0, getString(R.string.appalert_version), in.version + " (" + in.versionCode + ")", null, null);
        r.row(0, getString(R.string.appalert_type), (in.system ? (in.updatedSystem ? getString(R.string.appalert_system_updated) : getString(R.string.appalert_system))
                : getString(R.string.appalert_user)) + (in.hasLauncher ? "" : "\n" + getString(R.string.appalert_hidden))
                + (in.enabled ? "" : "\n" + getString(R.string.appalert_disabled)) + (in.debuggable ? "\ndebuggable" : ""), null, null);
        r.row(0, "SDK", "target " + in.targetSdk + (in.minSdk > 0 ? " · min " + in.minSdk : "") + " · uid " + in.uid, null, null);
        if (in.apkBytes > 0) r.row(0, getString(R.string.appalert_size), Ui.size(in.apkBytes) + "\n" + in.apkPath, null, null);
        r.row(0, getString(R.string.appalert_components), in.activities + " activities · " + in.services + " services · "
                + in.receivers + " receivers · " + in.providers + " providers", null, null);
        if (!in.sha256.isEmpty()) r.row(0, getString(R.string.appalert_cert), in.sha256, null, v -> {
            Ui.copy(this, in.sha256);
            Ui.toast(this, R.string.copied);
        });

        r.section(R.string.appalert_sec_perms);
        if (!in.dangerousGranted.isEmpty()) r.row(0, getString(R.string.appalert_perms_granted), String.join("\n", in.dangerousGranted),
                r.chip(String.valueOf(in.dangerousGranted.size()), pal.error), null);
        if (!in.dangerousNotGranted.isEmpty()) r.row(0, getString(R.string.appalert_perms_asked), String.join("\n", in.dangerousNotGranted), null, null);
        if (!in.otherPerms.isEmpty()) {
            r.section(getString(R.string.appalert_perms_other, in.otherPerms.size()));
            r.note(String.join(", ", in.otherPerms));
        }
        if (in.dangerousGranted.isEmpty() && in.dangerousNotGranted.isEmpty() && in.otherPerms.isEmpty()) r.note(getString(R.string.appalert_no_perms));
    }
}
