package com.suvo.agent.update;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.suvo.agent.BuildConfig;
import com.suvo.agent.R;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.ui.Overlay;
import com.suvo.agent.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks GitHub Releases and installs the latest APK when the user requests it. */
public final class UpdateChecker {
    private static final String PREFS = "github_updates";
    private static final String IGNORED = "ignored_tag";
    private static final String PENDING_VERSION = "pending_install_version";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean checking;

    private UpdateChecker() {}

    public static void check(Activity activity, ShizukuManager shizuku) {
        if (activity == null || activity.isFinishing()) return;
        if (checking) {
            toastIfAlive(activity, "Update check is already running.");
            return;
        }
        checking = true;
        IO.execute(() -> {
            try {
                String repo = activity.getString(R.string.project_repo).trim();
                repo = repo.replaceAll("\\.git/?$", "").replaceAll("/$", "");
                String[] parts = repo.split("/");
                if (parts.length < 2) throw new IllegalStateException("Invalid project_repo: " + repo);

                String api = "https://api.github.com/repos/" + parts[parts.length - 2] + "/"
                        + parts[parts.length - 1] + "/releases/latest";
                JSONObject release = new JSONObject(readUrl(api));

                if (release.optBoolean("draft", false)) {
                    toastIfAlive(activity, "Update skipped: GitHub release is a draft.");
                    return;
                }
                if (release.optBoolean("prerelease", false)) {
                    toastIfAlive(activity, "Update skipped: GitHub release is marked pre-release.");
                    return;
                }

                String tag = release.optString("tag_name", "").trim();
                if (tag.isEmpty()) throw new IllegalStateException("GitHub release has no tag_name.");
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                    toastIfAlive(activity, "No newer update. Current version: " + BuildConfig.VERSION_NAME);
                    return;
                }
                if (activity.getSharedPreferences(PREFS, 0).getString(IGNORED, "").equals(tag)) {
                    toastIfAlive(activity, "Update " + tag + " was ignored.");
                    return;
                }

                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            if (!apkUrl.isEmpty()) break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) {
                    throw new IllegalStateException("Release " + tag + " has no downloadable APK asset.");
                }

                String downloadUrl = apkUrl;
                String title = release.optString("name", tag);
                MAIN.post(() -> {
                    if (!activity.isFinishing()) showUpdateDialog(activity, shizuku, tag, title, downloadUrl);
                });
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = e.toString();
                toastIfAlive(activity, "Update check failed: " + message);
            } finally {
                checking = false;
            }
        });
    }

    /** Removes the downloaded APK only after the new version is running. */
    public static void cleanupAfterSuccessfulInstall(Activity activity) {
        if (activity == null) return;
        SharedPreferences p = activity.getSharedPreferences(PREFS, 0);
        String pending = p.getString(PENDING_VERSION, "");
        if (!pending.isEmpty() && compareVersions(BuildConfig.VERSION_NAME, pending) >= 0) {
            File dir = activity.getExternalFilesDir("updates");
            if (dir != null) {
                File apk = new File(dir, "update.apk");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
            p.edit().remove(PENDING_VERSION).apply();
        }
    }

    private static void showUpdateDialog(Activity a, ShizukuManager shizuku, String tag, String title, String url) {
        Ui.show(Ui.dialog(a)
                .setTitle(a.getString(R.string.update_available_title))
                .setMessage(a.getString(R.string.update_available_message, title, tag))
                .setPositiveButton(R.string.update_now, (d, w) -> downloadAndInstall(a, shizuku, tag, url))
                .setNegativeButton(R.string.update_ignore, (d, w) ->
                        a.getSharedPreferences(PREFS, 0).edit().putString(IGNORED, tag).apply()));
    }

    private static void downloadAndInstall(Activity a, ShizukuManager shizuku, String tag, String url) {
        File dir = a.getExternalFilesDir("updates");
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            fail(a, "Unable to create the update download directory.");
            return;
        }

        File apk = new File(dir, "update.apk");
        ProgressUi progress = showProgress(a, R.string.update_downloading, a.getString(R.string.update_please_wait));
        IO.execute(() -> {
            String error = null;
            try {
                download(url, apk, progress);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
            String finalError = error;
            MAIN.post(() -> {
                progress.dismiss();
                if (a.isFinishing()) return;
                if (finalError != null) {
                    fail(a, "Update download failed: " + finalError);
                    return;
                }
                a.getSharedPreferences(PREFS, 0).edit().putString(PENDING_VERSION, tag).apply();
                if (shizuku != null && shizuku.state() == ShizukuManager.State.READY) {
                    installWithShizuku(a, shizuku, apk, tag);
                } else {
                    toastIfAlive(a, "Shizuku is not ready; opening Android installer.");
                    installWithAndroid(a, apk);
                }
            });
        });
    }

    private static void installWithShizuku(
        Activity a,
        ShizukuManager shizuku,
        File apk,
        String tag
) {
    ProgressUi progress = showProgress(
            a,
            R.string.update_installing,
            a.getString(R.string.update_please_wait)
    );

    IO.execute(() -> {
        String output = "";
        boolean success = false;

        // system_server cannot reliably read APKs from shared storage.
        // Copy the APK into /data/local/tmp before installing.
        final String tempApk = "/data/local/tmp/yourfriend-update.apk";

        try {
            String source = ShizukuManager.shellQuote(apk.getAbsolutePath());
            String target = ShizukuManager.shellQuote(tempApk);

            String command =
                    "mv " + source + " " + target
                    + " && chmod 644 " + target
                    + " && pm install -r -t " + target
                    + "; install_status=$?; "
                    + "rm -f " + target + "; "
                    + "exit $install_status";

            com.suvo.agent.terminal.ExecResult result = shizuku.run(
                    command,
                    null,
                    180_000,
                    new com.suvo.agent.core.util.CancellationToken()
            );

            output = (
                    result.stdout + "\n"
                    + result.stderr + "\n"
                    + result.error
            ).trim();

            success = result.ok() && output.contains("Success");

            if (output.isEmpty()) {
                output = "Shizuku install failed. Exit code: "
                        + result.exitCode;
            }

        } catch (Throwable e) {
            output = e.getMessage() == null
                    ? e.toString()
                    : e.getMessage();
        }

        final boolean installed = success;
        final String detail = output;

        MAIN.post(() -> {
            progress.dismiss();

            if (a.isFinishing()) return;

            if (installed) {
                Ui.show(
                        Ui.dialog(a)
                                .setMessage(R.string.update_install_success)
                                .setPositiveButton(
                                        android.R.string.ok,
                                        (d, w) -> {
                                            Intent launch = a
                                                    .getPackageManager()
                                                    .getLaunchIntentForPackage(
                                                            a.getPackageName()
                                                    );

                                            if (launch != null) {
                                                launch.addFlags(
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                                );
                                                a.startActivity(launch);
                                            }
                                        }
                                )
                );
            } else {
                toastIfAlive(
                        a,
                        "Shizuku installation failed: " + detail
                );

                Ui.show(
                        Ui.dialog(a)
                                .setTitle(R.string.update_install_fallback)
                                .setMessage(
                                        a.getString(
                                                R.string.update_install_error,
                                                detail
                                        )
                                )
                                .setPositiveButton(
                                        R.string.update_install_with_android,
                                        (d, w) -> installWithAndroid(a, apk)
                                )
                                .setNegativeButton(
                                        android.R.string.cancel,
                                        null
                                )
                );
            }
        });
    });
}

    private static void installWithAndroid(Activity a, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".updates", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            fail(a, "Could not open Android installer: " + e);
        }
    }

    private static void download(String url, File target, ProgressUi progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "YourFriend-Android-Updater");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Download HTTP " + code);
            int total = c.getContentLength();
            MAIN.post(() -> progress.setIndeterminate(total <= 0));
            if (total > 0) MAIN.post(() -> progress.setMax(100));
            try (InputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                int n;
                long done = 0;
                int lastPercent = -1;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            MAIN.post(() -> progress.setProgress(percent));
                        }
                    }
                }
                out.getFD().sync();
            }
            if (target.length() < 1024) throw new IllegalStateException("Downloaded APK is too small or empty.");
        } finally {
            c.disconnect();
        }
    }

    private static ProgressUi showProgress(Activity a, int titleRes, String message) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 4);
        box.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(a);
        label.setText(message);
        label.setTextSize(14);
        box.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(a, 16);
        box.addView(bar, params);

        Overlay overlay = Ui.show(Ui.dialog(a).setTitle(titleRes).setMessage((CharSequence) null).setView(box).setCancelable(false));
        return new ProgressUi(overlay, bar);
    }

    private static void fail(Activity a, String message) {
        toastIfAlive(a, message);
    }

    private static void toastIfAlive(Activity a, String message) {
        MAIN.post(() -> {
            if (a != null && !a.isFinishing()) Ui.toast(a, message);
        });
    }

    private static String readUrl(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YourFriend-Android-Updater");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("GitHub API HTTP " + code + " for " + address);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static int compareVersions(String a, String b) {
        int[] x = versionParts(a), y = versionParts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] versionParts(String s) {
        String clean = s == null ? "" : s.trim().replaceFirst("^[vV]", "");
        String[] pieces = clean.split("[.+-]", 4);
        int[] result = new int[Math.min(3, pieces.length)];
        for (int i = 0; i < result.length; i++) {
            String digits = pieces[i].replaceAll("[^0-9].*$", "");
            try {
                result[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static final class ProgressUi {
        private final Overlay overlay;
        private final ProgressBar bar;

        ProgressUi(Overlay overlay, ProgressBar bar) {
            this.overlay = overlay;
            this.bar = bar;
        }

        void dismiss() {
            if (overlay != null) overlay.dismiss();
        }

        void setIndeterminate(boolean value) { bar.setIndeterminate(value); }
        void setMax(int value) { bar.setMax(value); }
        void setProgress(int value) { bar.setProgress(value); }
    }
}
