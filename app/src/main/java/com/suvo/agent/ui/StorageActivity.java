package com.suvo.agent.ui;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Process;
import android.os.storage.StorageManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * How much of the phone's storage this app uses: Android's own numbers for the app, its data and
 * its cache (the same as in system Settings), a bar that shows the split, and a breakdown of what
 * the data is (voice models, voice engine, 3D character, workspace files, trash, conversations and
 * memory, cache). Cache and trash can be cleared here.
 */
public final class StorageActivity extends BaseActivity {

    private LinearLayout box;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.storage_title);
        box = findViewById(R.id.rows);
        build();
    }

    /** One measured part of the app's storage. */
    private static final class Part {
        final int icon, title;
        final File[] dirs;
        long bytes;
        Runnable clear;

        Part(int icon, int title, File... dirs) {
            this.icon = icon;
            this.title = title;
            this.dirs = dirs;
        }
    }

    private void build() {
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.storage_desc));
        TextView total = new TextView(this, null, 0, R.style.YF_Title);
        total.setTextSize(40f);
        total.setText(R.string.storage_calculating);
        total.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        box.addView(total);
        Bar bar = new Bar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 14));
        bp.setMargins(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4));
        box.addView(bar, bp);
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        box.addView(legend);

        File files = getFilesDir();
        File data = files.getParentFile();
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(R.drawable.ic_voice, R.string.storage_voice_models, new File(files, "omnivoice/models")));
        parts.add(new Part(R.drawable.ic_download, R.string.storage_voice_libs, new File(files, "omnivoice/lib")));
        parts.add(new Part(R.drawable.ic_character, R.string.storage_character, new File(files, "models")));
        File ws = g.workspace.root();
        File trash = new File(ws, ".trash");
        Part workspace = new Part(R.drawable.ic_files, R.string.storage_workspace, ws);
        parts.add(workspace);
        Part trashPart = new Part(R.drawable.ic_delete, R.string.storage_trash, trash);
        trashPart.clear = () -> deleteChildren(trash);
        parts.add(trashPart);
        parts.add(new Part(R.drawable.ic_memory, R.string.storage_database, data == null ? null : new File(data, "databases"),
                data == null ? null : new File(data, "shared_prefs")));
        Part cache = new Part(R.drawable.ic_refresh, R.string.storage_cache, getCacheDir(), getCodeCacheDir(), getExternalCacheDir());
        cache.clear = () -> {
            deleteChildren(getCacheDir());
            if (getExternalCacheDir() != null) deleteChildren(getExternalCacheDir());
        };
        parts.add(cache);
        parts.add(new Part(R.drawable.ic_upload, R.string.storage_external, getExternalFilesDir(null)));

        r.section(R.string.storage_breakdown);
        List<TextView> sizes = new ArrayList<>();
        for (Part p : parts) {
            TextView s = r.row(p.icon, getString(p.title), getString(R.string.storage_calculating), null, p.clear == null ? null : v ->
                    Ui.confirm(this, p.title, getString(p == cache ? R.string.storage_confirm_cache : R.string.storage_confirm_trash), () ->
                            g.io.execute(() -> {
                                long before = p.bytes;
                                p.clear.run();
                                runOnUiThread(() -> {
                                    Ui.toast(this, getString(R.string.storage_cleared, Ui.size(Math.max(0, before))));
                                    build();
                                });
                            })));
            sizes.add(s);
        }
        r.button(R.string.storage_clear_cache, false, v -> Ui.confirm(this, R.string.storage_clear_cache,
                getString(R.string.storage_confirm_cache), () -> g.io.execute(() -> {
                    cache.clear.run();
                    runOnUiThread(this::build);
                })));

        g.io.execute(() -> {
            long app = -1, dataB = -1, cacheB = -1;
            try {
                StorageStatsManager sm = getSystemService(StorageStatsManager.class);
                StorageStats st = sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, getPackageName(), Process.myUserHandle());
                app = st.getAppBytes();
                dataB = st.getDataBytes();
                cacheB = st.getCacheBytes();
            } catch (Exception ignored) {
                // Some devices refuse; the folder sizes below still work.
            }
            for (Part p : parts) {
                long sum = 0;
                for (File f : p.dirs) if (f != null) sum += size(f, p == workspace ? trash : null);
                p.bytes = sum;
            }
            if (app < 0) {
                app = new File(getApplicationInfo().sourceDir).length();
                if (getApplicationInfo().splitSourceDirs != null) {
                    for (String sp : getApplicationInfo().splitSourceDirs) app += new File(sp).length();
                }
            }
            if (dataB < 0) {
                dataB = data == null ? 0 : size(data, null);
                cacheB = cache.bytes;
            }
            final long fa = app, fd = Math.max(0, dataB - Math.max(0, cacheB)), fc = Math.max(0, cacheB);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                total.setText(Ui.size(fa + fd + fc));
                Palette pal = Palette.of(this);
                bar.set(new long[]{fa, fd, fc}, new int[]{pal.primary, pal.tertiary, Palette.alpha(pal.onSurfaceVariant, 0.6f)});
                legend.removeAllViews();
                legend(legend, pal.primary, getString(R.string.storage_app), fa);
                legend(legend, pal.tertiary, getString(R.string.storage_data), fd);
                legend(legend, Palette.alpha(pal.onSurfaceVariant, 0.6f), getString(R.string.storage_cache), fc);
                for (int i = 0; i < parts.size(); i++) sizes.get(i).setText(Ui.size(parts.get(i).bytes));
            });
        });
    }

    private void legend(LinearLayout into, int color, String label, long bytes) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 2));
        View dot = new View(this);
        dot.setBackground(new TonalDrawable(color, -1));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dp.setMarginEnd(Ui.dp(this, 10));
        row.addView(dot, dp);
        TextView t = new TextView(this, null, 0, R.style.YF_Body);
        t.setText(label);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = new TextView(this, null, 0, R.style.YF_Caption);
        s.setText(Ui.size(bytes));
        s.setTextSize(14f);
        row.addView(s);
        into.addView(row);
    }

    /** Total size of a file or folder (symlinks are not followed). */
    static long size(File f, File skip) {
        if (f == null || !f.exists() || f.equals(skip)) return 0;
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += size(k, skip);
        return sum;
    }

    static void deleteChildren(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) deleteChildren(k);
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }

    /** Capsule bar split into coloured parts with small gaps. */
    static final class Bar extends View {
        private long[] values = new long[0];
        private int[] colors = new int[0];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();

        Bar(Context c) {
            super(c);
        }

        void set(long[] v, int[] c) {
            values = v;
            colors = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(r, h / 2f, h / 2f, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            paint.setColor(Palette.of(getContext()).surfaceHighest);
            c.drawRect(r, paint);
            long total = 0;
            for (long v : values) total += v;
            if (total > 0) {
                float x = 0, gap = 2 * getResources().getDisplayMetrics().density;
                for (int i = 0; i < values.length; i++) {
                    float seg = w * values[i] / total;
                    if (seg <= 0) continue;
                    paint.setColor(colors[i]);
                    c.drawRect(x, 0, Math.min(w, x + seg - (i < values.length - 1 ? gap : 0)), h, paint);
                    x += seg;
                }
            }
            c.restore();
        }
    }
}
