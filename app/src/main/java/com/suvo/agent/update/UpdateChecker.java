package com.suvo.agent.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.suvo.agent.BuildConfig;
import com.suvo.agent.R;
import com.suvo.agent.device.shizuku.ShizukuManager;
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
    private static final String TAG = "YF.Update";
    private static final String PREFS = "github_updates";
    private static final String IGNORED = "ignored_tag";
    private static final String PENDING_VERSION = "pending_install_version";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
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
                if (parts.length < 2) return;
                String api = "https://api.github.com/repos/" + parts[parts.length - 2] + "/" + parts[parts.length - 1] + "/releases/latest";
                JSONObject release = new JSONObject(readUrl(api));
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return;
                String tag = release.optString("tag_name", "").trim();
                if (tag.isEmpty() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return;
                if (activity.getSharedPreferences(PREFS, 0).getString(IGNORED, "").equals(tag)) return;
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        if (apkUrl != null && !apkUrl.isEmpty()) break;
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) return;
                String downloadUrl = apkUrl;
                MAIN.post(() -> {
                    if (!activity.isFinishing()) showUpdateDialog(activity, shizuku, tag, release.optString("name", tag), downloadUrl);
                });
} catch (Exception e) {
    String message = e.getMessage();
    if (message == null || message.trim().isEmpty()) {
        message = "Unknown error";
    }

    final String errorMessage = "Update check failed: " + message;

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

    /** Call when the app resumes: removes the downloaded APK only after the new version is running. */
    public static void cleanupAfterSuccessfulInstall(Activity activity) {
        android.content.SharedPreferences p = activity.getSharedPreferences(PREFS, 0);
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
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.update_available_title))
                .setMessage(a.getString(R.string.update_available_message, title, tag))
                .setPositiveButton(R.string.update_now, (d, w) -> downloadAndInstall(a, shizuku, tag, url))
                .setNegativeButton(R.string.update_ignore, (d, w) -> a.getSharedPreferences(PREFS, 0).edit().putString(IGNORED, tag).apply())
                .setOnCancelListener(d -> {})
                .show();
    }

    private static void downloadAndInstall(Activity a, ShizukuManager shizuku, String tag, String url) {
        File dir = a.getExternalFilesDir("updates");
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            new AlertDialog.Builder(a).setMessage(R.string.update_storage_error).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        File apk = new File(dir, "update.apk");
        ProgressDialog progress = new ProgressDialog(a);
        progress.setTitle(R.string.update_downloading);
        progress.setMessage(a.getString(R.string.update_please_wait));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
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
                if (a.isFinishing()) return;
                progress.dismiss();
                if (finalError != null) {
                    new AlertDialog.Builder(a).setTitle(R.string.update_failed).setMessage(finalError).setPositiveButton(android.R.string.ok, null).show();
                    return;
                }
                a.getSharedPreferences(PREFS, 0).edit().putString(PENDING_VERSION, tag).apply();
                if (shizuku != null && shizuku.state() == ShizukuManager.State.READY) {
                    installWithShizuku(a, shizuku, apk, tag);
                } else {
                    installWithAndroid(a, apk);
                }
            });
        });
    }

    private static void installWithShizuku(Activity a, ShizukuManager shizuku, File apk, String tag) {
        ProgressDialog p = new ProgressDialog(a);
        p.setMessage(a.getString(R.string.update_installing));
        p.setCancelable(false);
        p.show();
        IO.execute(() -> {
            String output = "";
            boolean success = false;
            try {
                // The app-specific external-files directory is readable by Android's shell identity.
                com.suvo.agent.terminal.ExecResult result = shizuku.run(
                        "pm install -r -t " + ShizukuManager.shellQuote(apk.getAbsolutePath()), null, 180_000,
                        new com.suvo.agent.core.util.CancellationToken());
                output = (result.stdout + "\n" + result.stderr + " " + result.error).trim();
                success = result.ran() && result.exitCode == 0 && output.contains("Success");
            } catch (Throwable e) { output = e.toString(); }
            final boolean installed = success;
            final String detail = output;
            MAIN.post(() -> {
                if (a.isFinishing()) return;
                p.dismiss();
                if (installed) {
                    // If Android restarts the app process, cleanup runs on the next launch.
                    new AlertDialog.Builder(a).setMessage(R.string.update_install_success).setPositiveButton(android.R.string.ok, (d, w) -> {
                        Intent launch = a.getPackageManager().getLaunchIntentForPackage(a.getPackageName());
                        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(launch); }
                    }).show();
                } else {
                    new AlertDialog.Builder(a).setTitle(R.string.update_install_fallback)
                            .setMessage(a.getString(R.string.update_install_error, detail))
                            .setPositiveButton(R.string.update_install_with_android, (d, w) -> installWithAndroid(a, apk))
                            .setNegativeButton(android.R.string.cancel, null).show();
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
            new AlertDialog.Builder(a).setTitle(R.string.update_failed).setMessage(e.toString()).setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private static void download(String url, File target, ProgressDialog progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "YourFriend-Android-Updater");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Download HTTP " + code);
            int total = c.getContentLength();
            MAIN.post(() -> { progress.setIndeterminate(total <= 0); if (total > 0) progress.setMax(100); });
            try (InputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536]; int n; long done = 0;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n); done += n;
                    if (total > 0) { int percent = (int) (done * 100 / total); MAIN.post(() -> progress.setProgress(percent)); }
                }
                out.getFD().sync();
            }
            if (target.length() < 1024) throw new IllegalStateException("Downloaded APK is too small or empty");
        } finally { c.disconnect(); }
    }

    private static String readUrl(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YourFriend-Android-Updater");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static int compareVersions(String a, String b) {
        int[] x = versionParts(a), y = versionParts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
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
            try { result[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits); } catch (NumberFormatException ignored) { result[i] = 0; }
        }
        return result;
    }
}
