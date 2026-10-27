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

/** Checks GitHub Releases and installs the latest APK when requested. */
public final class UpdateChecker {

    private static final String PREFS = "github_updates";
    private static final String IGNORED = "ignored_tag";
    private static final String PENDING_VERSION = "pending_install_version";

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor();

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    private static volatile boolean checking;

    private UpdateChecker() {}

    public static void check(Activity activity, ShizukuManager shizuku) {
        if (activity == null || activity.isFinishing() || checking) return;

        checking = true;

        IO.execute(() -> {
            try {
                String repo = activity.getString(R.string.project_repo).trim();
                repo = repo.replaceAll("\\.git/?$", "").replaceAll("/$", "");

                String[] parts = repo.split("/");
                if (parts.length < 2) {
                    throw new IllegalStateException("Invalid project_repo URL");
                }

                String owner = parts[parts.length - 2];
                String name = parts[parts.length - 1];

                String api = "https://api.github.com/repos/"
                        + owner + "/" + name + "/releases/latest";

                JSONObject release = new JSONObject(readUrl(api));

                if (release.optBoolean("draft", false)
                        || release.optBoolean("prerelease", false)) {
                    return;
                }

                String tag = release.optString("tag_name", "").trim();

                if (tag.isEmpty()
                        || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                    return;
                }

                SharedPreferences prefs =
                        activity.getSharedPreferences(PREFS, 0);

                if (prefs.getString(IGNORED, "").equals(tag)) return;

                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;

                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;

                        String assetName =
                                asset.optString("name", "");

                        if (assetName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");

                            if (!apkUrl.isEmpty()) break;
                        }
                    }
                }

                if (apkUrl == null || apkUrl.isEmpty()) {
                    throw new IllegalStateException(
                            "The GitHub release has no APK asset");
                }

                String downloadUrl = apkUrl;
                String title = release.optString("name", tag);

                MAIN.post(() -> {
                    if (!activity.isFinishing()) {
                        showUpdateDialog(
                                activity, shizuku, tag, title, downloadUrl);
                    }
                });

            } catch (Exception e) {
                String message = e.getMessage();

                if (message == null || message.trim().isEmpty()) {
                    message = e.toString();
                }

                final String errorMessage =
                        "Update check failed: " + message;

                MAIN.post(() -> {
                    if (!activity.isFinishing()) {
                        Ui.toast(activity, errorMessage);
                    }
                });

            } finally {
                checking = false;
            }
        });
    }

    /** Removes the downloaded APK after the updated version is running. */
    public static void cleanupAfterSuccessfulInstall(Activity activity) {
        if (activity == null) return;

        SharedPreferences prefs =
                activity.getSharedPreferences(PREFS, 0);

        String pending = prefs.getString(PENDING_VERSION, "");

        if (!pending.isEmpty()
                && compareVersions(BuildConfig.VERSION_NAME, pending) >= 0) {

            File dir = activity.getExternalFilesDir("updates");

            if (dir != null) {
                File apk = new File(dir, "update.apk");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }

            prefs.edit().remove(PENDING_VERSION).apply();
        }
    }

    private static void showUpdateDialog(
            Activity activity,
            ShizukuManager shizuku,
            String tag,
            String title,
            String url) {

        Ui.show(Ui.dialog(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(activity.getString(
                        R.string.update_available_message, title, tag))
                .setPositiveButton(R.string.update_now,
                        (dialog, which) ->
                                downloadAndInstall(activity, shizuku, tag, url))
                .setNegativeButton(R.string.update_ignore,
                        (dialog, which) ->
                                activity.getSharedPreferences(PREFS, 0)
                                        .edit()
                                        .putString(IGNORED, tag)
                                        .apply()));
    }

    private static void downloadAndInstall(
            Activity activity,
            ShizukuManager shizuku,
            String tag,
            String url) {

        File dir = activity.getExternalFilesDir("updates");

        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            showError(activity, R.string.update_storage_error,
                    "Unable to create the update directory.");
            return;
        }

        File apk = new File(dir, "update.apk");
        ProgressUi progress = showProgress(
                activity, R.string.update_downloading,
                activity.getString(R.string.update_please_wait));

        IO.execute(() -> {
            String error = null;

            try {
                download(url, apk, progress);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();

                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }

            final String finalError = error;

            MAIN.post(() -> {
                progress.dismiss();

                if (activity.isFinishing()) return;

                if (finalError != null) {
                    showError(activity, R.string.update_failed, finalError);
                    return;
                }

                activity.getSharedPreferences(PREFS, 0)
                        .edit()
                        .putString(PENDING_VERSION, tag)
                        .apply();

                if (shizuku != null
                        && shizuku.state() == ShizukuManager.State.READY) {
                    installWithShizuku(activity, shizuku, apk, tag);
                } else {
                    installWithAndroid(activity, apk);
                }
            });
        });
    }

    private static void installWithShizuku(
            Activity activity,
            ShizukuManager shizuku,
            File apk,
            String tag) {

        ProgressUi progress = showProgress(
                activity,
                R.string.update_installing,
                activity.getString(R.string.update_please_wait));

        IO.execute(() -> {
            String output = "";
            boolean success = false;

            try {
                com.suvo.agent.terminal.ExecResult result = shizuku.run(
                        "pm install -r -t "
                                + ShizukuManager.shellQuote(apk.getAbsolutePath()),
                        null,
                        180_000,
                        new com.suvo.agent.core.util.CancellationToken());

                output = (result.stdout + "\n"
                        + result.stderr + "\n"
                        + result.error).trim();

                success = result.ok() && output.contains("Success");

                if (output.isEmpty()) {
                    output = "Exit code: " + result.exitCode;
                }

            } catch (Throwable e) {
                output = e.toString();
            }

            final boolean installed = success;
            final String detail = output;

            MAIN.post(() -> {
                progress.dismiss();

                if (activity.isFinishing()) return;

                if (installed) {
                    Ui.show(Ui.dialog(activity)
                            .setTitle(R.string.update_available_title)
                            .setMessage(R.string.update_install_success)
                            .setPositiveButton(android.R.string.ok,
                                    (dialog, which) -> {
                                        Intent launch = activity
                                                .getPackageManager()
                                                .getLaunchIntentForPackage(
                                                        activity.getPackageName());

                                        if (launch != null) {
                                            launch.addFlags(
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                                            activity.startActivity(launch);
                                        }
                                    }));
                } else {
                    Ui.show(Ui.dialog(activity)
                            .setTitle(R.string.update_install_fallback)
                            .setMessage(activity.getString(
                                    R.string.update_install_error, detail))
                            .setPositiveButton(
                                    R.string.update_install_with_android,
                                    (dialog, which) ->
                                            installWithAndroid(activity, apk))
                            .setNegativeButton(android.R.string.cancel, null));
                }
            });
        });
    }

    private static void installWithAndroid(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".updates",
                    apk);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(
                    uri, "application/vnd.android.package-archive");

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);

            activity.startActivity(intent);

        } catch (Exception e) {
            showError(activity, R.string.update_failed, e.toString());
        }
    }

    private static void download(
            String url,
            File target,
            ProgressUi progress) throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty(
                "User-Agent", "YourFriend-Android-Updater");

        try {
            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Download HTTP " + code);
            }

            int total = connection.getContentLength();

            MAIN.post(() -> progress.setIndeterminate(total <= 0));

            if (total > 0) {
                MAIN.post(() -> progress.setMax(100));
            }

            try (InputStream input = new BufferedInputStream(
                        connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(target)) {

                byte[] buffer = new byte[65536];
                int count;
                long done = 0;
                int lastPercent = -1;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    done += count;

                    if (total > 0) {
                        int percent = (int) (done * 100 / total);

                        if (percent != lastPercent) {
                            lastPercent = percent;
                            MAIN.post(() -> progress.setProgress(percent));
                        }
                    }
                }

                output.getFD().sync();
            }

            if (target.length() < 1024) {
                throw new IllegalStateException(
                        "Downloaded APK is too small or empty");
            }

        } finally {
            connection.disconnect();
        }
    }

    /** Creates a progress dialog using the app's glass dialog UI. */
    private static ProgressUi showProgress(
            Activity activity, int titleRes, String message) {

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(activity, 4);
        content.setPadding(padding, padding, padding, padding);

        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextSize(14);
        content.addView(messageView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar bar = new ProgressBar(
                activity, null, android.R.attr.progressBarStyleHorizontal);

        bar.setIndeterminate(true);

        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

        barParams.topMargin = dp(activity, 18);
        content.addView(bar, barParams);

        Overlay overlay = Ui.show(Ui.dialog(activity)
                .setTitle(titleRes)
                .setView(content)
                .setCancelable(false));

        return new ProgressUi(overlay, bar, messageView);
    }

    private static void showError(
            Activity activity, int titleRes, String message) {

        if (activity == null || activity.isFinishing()) return;

        Ui.show(Ui.dialog(activity)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null));
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private static final class ProgressUi {
        private final Overlay overlay;
        private final ProgressBar bar;
        private final TextView message;

        ProgressUi(Overlay overlay, ProgressBar bar, TextView message) {
            this.overlay = overlay;
            this.bar = bar;
            this.message = message;
        }

        void dismiss() {
            if (overlay != null) overlay.dismiss();
        }

        void setIndeterminate(boolean value) {
            bar.setIndeterminate(value);
        }

        void setMax(int value) {
            bar.setMax(value);
        }

        void setProgress(int value) {
            bar.setProgress(value);
        }
    }

    private static String readUrl(String address) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();

        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty(
                "Accept", "application/vnd.github+json");
        connection.setRequestProperty(
                "User-Agent", "YourFriend-Android-Updater");

        try (InputStream input = connection.getInputStream()) {
            java.io.ByteArrayOutputStream output =
                    new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return output.toString("UTF-8");

        } finally {
            connection.disconnect();
        }
    }

    private static int compareVersions(String a, String b) {
        int[] x = versionParts(a);
        int[] y = versionParts(b);

        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int first = i < x.length ? x[i] : 0;
            int second = i < y.length ? y[i] : 0;

            if (first != second) {
                return Integer.compare(first, second);
            }
        }

        return 0;
    }

    private static int[] versionParts(String version) {
        String clean = version == null
                ? ""
                : version.trim().replaceFirst("^[vV]", "");

        String[] pieces = clean.split("[.+-]", 4);
        int[] result = new int[Math.min(3, pieces.length)];

        for (int i = 0; i < result.length; i++) {
            String digits = pieces[i].replaceAll("[^0-9].*$", "");

            try {
                result[i] = digits.isEmpty()
                        ? 0
                        : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }

        return result;
    }
}