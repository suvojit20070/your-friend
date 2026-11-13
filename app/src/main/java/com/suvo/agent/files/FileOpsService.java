package com.suvo.agent.files;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.ui.FilesActivity;
import com.suvo.agent.ui.Notifications;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Your Friend — Original project by Suvo.
 *
 * Copy, move and delete from the Files screen run here, not in the screen: closing the screen
 * (or the app going to the background) never cuts a job in half. Jobs run one after another with
 * their progress in a notification that can cancel them; the screen listens to refresh itself.
 */
public final class FileOpsService extends Service {

    public enum Kind {COPY, MOVE, DELETE}

    public static final class Job {
        public final Kind kind;
        public final List<String> sources;
        /** Destination folder (copy / move). */
        public final String destDir;

        public Job(Kind kind, List<String> sources, String destDir) {
            this.kind = kind;
            this.sources = new ArrayList<>(sources);
            this.destDir = destDir;
        }
    }

    public interface Listener {
        /** A job ended; {@code error} is null when it went fine. */
        void onJobDone(Job job, String error);
    }

    private static final String ACTION_CANCEL = "com.suvo.agent.files.CANCEL";
    private static final int NOTIF_ID = 1012;
    private static final int NOTIF_DONE = 1013;

    private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean cancelled;
    private static volatile boolean busy;

    private Thread worker;

    /** Queues a job and makes sure the service runs it. */
    public static void start(Context ctx, Job job) {
        QUEUE.add(job);
        Intent i = new Intent(ctx, FileOpsService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static boolean busy() {
        return busy || !QUEUE.isEmpty();
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            QUEUE.clear();
            return START_NOT_STICKY;
        }
        Notification n = progress(getString(R.string.files_op_starting), 0, 0);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "yf-file-ops");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        Job job;
        while ((job = QUEUE.poll()) != null) {
            busy = true;
            cancelled = false;
            String error = null;
            try {
                runJob(job);
                if (cancelled) error = getString(R.string.files_op_cancelled);
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            busy = false;
            finished(job, error);
        }
        stopForeground(true);
        stopSelf();
    }

    private void runJob(Job job) throws IOException {
        AppGraph g = AppGraph.get();
        FileAccess fa = FileAccess.forUser(g.workspace, g.saf);
        int total = job.sources.size();
        for (int i = 0; i < total && !cancelled; i++) {
            String src = job.sources.get(i);
            String name = nameOf(src);
            update(getString(job.kind == Kind.DELETE ? R.string.files_op_deleting : job.kind == Kind.MOVE ? R.string.files_op_moving
                    : R.string.files_op_copying, name), i, total);
            if (job.kind == Kind.DELETE) {
                fa.delete(src);
                continue;
            }
            String dest = freeName(fa, join(job.destDir, name));
            if (job.destDir.equals(src) || job.destDir.startsWith(src.endsWith("/") ? src : src + "/")) {
                throw new IOException(getString(R.string.files_op_into_itself, name));
            }
            boolean move = job.kind == Kind.MOVE;
            if (fa.bothShizuku(src, dest)) {
                fa.shizukuCopy(src, dest, move);
            } else if (move && !FileAccess.isExternal(src) && !FileAccess.isExternal(dest)) {
                fa.move(src, dest, false); // a rename inside the workspace
            } else {
                copyTree(fa, src, dest);
                if (move && !cancelled) fa.remove(src);
            }
        }
    }

    /** Copies a file or a whole folder, file by file (works across workspace, picked folders and Shizuku). */
    private void copyTree(FileAccess fa, String src, String dest) throws IOException {
        if (cancelled) return;
        FileAccess.Entry e = fa.stat(src);
        if (e.dir) {
            fa.mkdirs(dest);
            for (FileAccess.Entry c : fa.list(src)) {
                if (cancelled) return;
                copyTree(fa, c.path, join(dest, c.name));
            }
            return;
        }
        try (InputStream in = fa.open(src)) {
            fa.writeStream(dest, in, false);
        }
    }

    /** "a.txt" → "a (1).txt" when the name is taken. */
    private static String freeName(FileAccess fa, String path) {
        if (!fa.exists(path)) return path;
        int slash = path.lastIndexOf('/');
        String dir = path.substring(0, slash + 1), name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String p = dir + base + " (" + i + ")" + ext;
            if (!fa.exists(p)) return p;
        }
        return path;
    }

    static String nameOf(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        if (slash >= 0) return p.substring(slash + 1);
        int colon = p.indexOf(':');
        return colon >= 0 ? p.substring(colon + 1) : p;
    }

    static String join(String dir, String name) {
        if (dir.endsWith("/") || dir.endsWith(":")) return dir + name;
        return dir + "/" + name;
    }

    private void update(String text, int done, int total) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, progress(text, done, total));
    }

    private Notification progress(String text, int done, int total) {
        PendingIntent cancel = PendingIntent.getService(this, 7, new Intent(this, FileOpsService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 8, new Intent(this, FilesActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Notifications.builder(this, Notifications.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.files_op_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(android.R.string.cancel), cancel).build());
        if (total > 1) b.setProgress(total, done, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void finished(Job job, String error) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Notification.Builder b = Notifications.builder(this, Notifications.CHANNEL_AGENT)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(getString(R.string.files_op_title))
                    .setContentText(error == null ? getString(R.string.files_op_done) : getString(R.string.error_prefix, error))
                    .setAutoCancel(true);
            if (error == null) b.setTimeoutAfter(8000);
            nm.notify(NOTIF_DONE, b.build());
        }
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onJobDone(job, error);
        });
    }
}
