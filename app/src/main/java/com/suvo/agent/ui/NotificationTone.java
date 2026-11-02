package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Your Friend — Original project by Suvo.
 *
 * Settings → Notification tone: the sound of Your Friend's notifications (task done, approvals,
 * auto reply drafts, new app alerts). Default, silent, one of the phone's tones, or any audio file —
 * a file is copied into the app and shared read-only with the system so it can play it.
 */
public final class NotificationTone {

    private NotificationTone() {
    }

    /** What the setting row shows. */
    public static String label(Context ctx) {
        String t = Settings.get(ctx).str(Notifications.TONE, "");
        if (t.isEmpty()) return ctx.getString(R.string.tone_default);
        if (Notifications.SILENT.equals(t)) return ctx.getString(R.string.tone_silent);
        String name = Settings.get(ctx).str("notif_tone_name", "");
        if (!name.isEmpty()) return name;
        try {
            Ringtone r = RingtoneManager.getRingtone(ctx, Uri.parse(t));
            if (r != null) return r.getTitle(ctx);
        } catch (RuntimeException ignored) {
        }
        return ctx.getString(R.string.tone_custom);
    }

    /** The choice sheet: default, silent, phone tones, audio file. */
    public static void choose(Activity a, int reqSystem, int reqFile, Runnable changed) {
        CharSequence[] items = {a.getString(R.string.tone_default), a.getString(R.string.tone_silent),
                a.getString(R.string.tone_pick_system), a.getString(R.string.tone_pick_file)};
        GlassSheet.options(a, a.getString(R.string.settings_notif_tone), items, -1, null).setOnPick(which -> {
            switch (which) {
                case 0:
                    set(a, "", "");
                    changed.run();
                    break;
                case 1:
                    set(a, Notifications.SILENT, "");
                    changed.run();
                    break;
                case 2: {
                    Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, a.getString(R.string.settings_notif_tone));
                    String cur = Settings.get(a).str(Notifications.TONE, "");
                    if (!cur.isEmpty() && !Notifications.SILENT.equals(cur)) {
                        i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
                    }
                    try {
                        a.startActivityForResult(i, reqSystem);
                    } catch (RuntimeException e) {
                        Ui.toast(a, R.string.tone_no_picker);
                    }
                    break;
                }
                default: {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
                    try {
                        a.startActivityForResult(i, reqFile);
                    } catch (RuntimeException e) {
                        Ui.toast(a, R.string.tone_no_picker);
                    }
                    break;
                }
            }
        }).show();
    }

    /** Result of the phone's tone picker. */
    public static void onSystemPicked(Context ctx, Intent data) {
        Uri u = data == null ? null : data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (u == null) {
            set(ctx, Notifications.SILENT, "");
        } else if (RingtoneManager.isDefault(u)) {
            set(ctx, "", "");
        } else {
            set(ctx, u.toString(), "");
        }
    }

    /** Result of the file picker: copy the audio into the app and share it with the system. */
    public static boolean onFilePicked(Context ctx, Uri src) {
        try {
            File dir = new File(ctx.getFilesDir(), "tones");
            if (!dir.exists() && !dir.mkdirs()) return false;
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            String name = displayName(ctx, src);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT) : ".mp3";
            if (ext.length() > 6) ext = ".mp3";
            File out = new File(dir, "tone_" + System.currentTimeMillis() + ext);
            long total = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(out)) {
                if (in == null) return false;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    total += n;
                    if (total > 15L * 1024 * 1024) return false;
                }
            }
            Uri shared = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".updates", out);
            for (String pkg : new String[]{"com.android.systemui", "android"}) {
                try {
                    ctx.grantUriPermission(pkg, shared, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (RuntimeException ignored) {
                }
            }
            set(ctx, shared.toString(), name.replaceAll("\\.[A-Za-z0-9]{1,5}$", ""));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void set(Context ctx, String uri, String name) {
        Settings.get(ctx).put("notif_tone_name", name);
        Notifications.setTone(ctx, uri);
    }

    private static String displayName(Context ctx, Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
        }
        return "tone.mp3";
    }
}
