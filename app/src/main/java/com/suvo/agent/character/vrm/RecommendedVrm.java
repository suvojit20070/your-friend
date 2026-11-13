package com.suvo.agent.character.vrm;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.character.CharacterView;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.Notifications;
import com.suvo.agent.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * The 3D model we suggest: downloaded and put on in one go. The app does this by itself once after
 * setup when no 3D model is set (the floating companion needs one), and Settings can do it on a tap.
 * Progress shows in a notification; a failed download is tried again on the next app start.
 */
public final class RecommendedVrm {

    public static final String URL = "https://storage.devs.li/get?key=CQ3cWTdmp2A_m1Af0jzk8lGs&id=366";
    /** The model in use is the suggested one. */
    public static final String IN_USE = "vrm_recommended_in_use";
    private static final long MAX = 200L * 1024 * 1024;
    private static final int NOTIF_ID = 1011;

    public interface Listener {
        /** Progress text while it runs; {@code done} with the result message (ok or not) at the end. */
        void onProgress(String text);

        void onDone(boolean ok, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String progress;

    private RecommendedVrm() {
    }

    /** True when the character has a 3D model (picked, downloaded or built in). */
    public static boolean hasModel(Context ctx) {
        return CharacterView.modelSource(ctx) != null;
    }

    /** The progress text while a download runs, else null. */
    public static String progress() {
        return progress;
    }

    public static boolean running() {
        return progress != null;
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Once after setup: no 3D model yet → fetch the suggested one quietly in the background. */
    public static void autoApplyIfMissing(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (hasModel(app) || running()) return;
        start(app);
    }

    /** Starts the download (does nothing while one runs). */
    public static synchronized void start(Context ctx) {
        if (running()) return;
        Context app = ctx.getApplicationContext();
        setProgress(app, app.getString(R.string.settings_vrm_downloading, ""));
        AppGraph.get().io.execute(() -> run(app));
    }

    private static void run(Context app) {
        File tmp = new File(app.getCacheDir(), "avatar_download.vrm");
        String result;
        boolean ok = false;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(URL).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            long size = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long total = 0, shown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX) throw new IOException("The model is larger than 200 MB");
                    out.write(buf, 0, n);
                    if (total - shown > 256 * 1024) {
                        shown = total;
                        setProgress(app, app.getString(R.string.settings_vrm_downloading, size > 0
                                ? (total * 100 / size) + "% · " + Ui.size(total) + " / " + Ui.size(size) : Ui.size(total)));
                    }
                }
            } finally {
                c.disconnect();
            }
            String info = VrmModel.probe(tmp);
            File dir = new File(app.getFilesDir(), "models");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, "avatar.vrm");
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("Could not save the model");
            Settings st = Settings.get(app);
            st.put(Settings.VRM_PATH, dest.getAbsolutePath());
            st.put(IN_USE, true);
            result = app.getString(R.string.settings_vrm_imported, info);
            ok = true;
        } catch (Exception | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            result = app.getString(R.string.settings_vrm_invalid, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        progress = null;
        final boolean success = ok;
        final String msg = result;
        notifyDone(app, success, msg);
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onDone(success, msg);
            // The floating companion was waiting for a model.
            if (success) com.suvo.agent.character.FloatingCompanionService.onAppVisibilityChanged(app, true);
        });
    }

    private static void setProgress(Context app, String text) {
        progress = text;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onProgress(text);
        });
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            Notification n = Notifications.builder(app, Notifications.CHANNEL_AGENT)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(app.getString(R.string.settings_vrm_recommended))
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyDone(Context app, boolean ok, String msg) {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (ok) {
                nm.cancel(NOTIF_ID);
                return;
            }
            nm.notify(NOTIF_ID, Notifications.builder(app, Notifications.CHANNEL_AGENT)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(app.getString(R.string.settings_vrm_recommended))
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException ignored) {
        }
    }
}
