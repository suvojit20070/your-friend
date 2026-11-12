package com.suvo.agent.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.util.AttachmentList;
import com.suvo.agent.files.WorkspaceProvider;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Real previews of what a message has attached: photos and video frames as rounded tiles (a play
 * badge on videos, the first page of a PDF), other files as a small card with the type, name and
 * size. Tap a tile to open it (photos open large right here). Thumbnails are made off the main
 * thread and kept in a small memory cache.
 */
final class AttachmentViews {

    private AttachmentViews() {
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** Fills {@code box} (a horizontal LinearLayout) with the previews. */
    static void fill(Activity a, LinearLayout box, List<AttachmentList.Att> atts, List<ChatMessage.Image> images, boolean end) {
        box.removeAllViews();
        box.setGravity(end ? Gravity.END : Gravity.START);
        for (int i = 0; i < atts.size(); i++) {
            AttachmentList.Att at = atts.get(i);
            View v = at.kind == AttachmentList.IMAGE || at.kind == AttachmentList.VIDEO || at.kind == AttachmentList.PDF
                    ? tile(a, at, images) : card(a, at);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.setMargins(i == 0 ? 0 : Ui.dp(a, 6), 0, 0, Ui.dp(a, 6));
            box.addView(v, lp);
        }
    }

    private static void round(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private static View tile(Activity a, AttachmentList.Att at, List<ChatMessage.Image> images) {
        Palette pal = Palette.of(a);
        int size = Ui.dp(a, at.kind == AttachmentList.IMAGE ? 156 : 132);
        FrameLayout f = new FrameLayout(a);
        f.setLayoutParams(new LinearLayout.LayoutParams(at.kind == AttachmentList.PDF ? Ui.dp(a, 104) : size, size));
        f.setBackground(new TonalDrawable(pal.surfaceHigh, Ui.dp(a, 18)));
        round(f, Ui.dp(a, 18));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final String key = at.path.isEmpty() ? "img:" + at.image + ":" + (at.image < images.size() ? images.get(at.image).url.hashCode() : 0)
                : at.kind + ":" + at.path;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            img.setImageBitmap(cached);
        } else {
            img.setTag(key);
            final int px = size;
            AppGraph.get().io.execute(() -> {
                Bitmap b = null;
                try {
                    b = load(a, at, images, px);
                } catch (Throwable ignored) {
                    // unreadable: the tile stays plain
                }
                if (b == null) return;
                CACHE.put(key, b);
                final Bitmap fb = b;
                img.post(() -> {
                    if (key.equals(img.getTag())) img.setImageBitmap(fb);
                });
            });
        }
        if (at.kind == AttachmentList.VIDEO) {
            // A play badge in the middle and the length in a corner.
            TextView play = new TextView(a);
            play.setText("▶");
            play.setTextColor(Color.WHITE);
            play.setTextSize(18f);
            play.setGravity(Gravity.CENTER);
            play.setBackground(new TonalDrawable(0x88000000, -1));
            f.addView(play, new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.CENTER));
            String len = at.info.contains(" s") ? at.info.substring(0, at.info.indexOf(" s")) : "";
            if (!len.isEmpty()) f.addView(badge(a, formatSeconds(len)), badgeLp(a, Gravity.BOTTOM | Gravity.END));
        } else if (at.kind == AttachmentList.PDF) {
            f.addView(badge(a, "PDF"), badgeLp(a, Gravity.BOTTOM | Gravity.START));
        }
        f.setContentDescription(at.name.isEmpty() ? a.getString(R.string.chat_attachment_image) : at.name);
        f.setOnClickListener(v -> {
            if (at.path.isEmpty()) showLarge(a, at, images);
            else open(a, at.path);
        });
        LiquidPress.attach(f, LiquidPress.LARGE);
        return f;
    }

    private static TextView badge(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(new TonalDrawable(0x99000000, -1));
        t.setPadding(Ui.dp(a, 7), Ui.dp(a, 2), Ui.dp(a, 7), Ui.dp(a, 2));
        return t;
    }

    private static FrameLayout.LayoutParams badgeLp(Activity a, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7));
        return lp;
    }

    private static String formatSeconds(String s) {
        try {
            int t = Math.round(Float.parseFloat(s));
            return String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** A file card: coloured type tag, name, size. */
    private static View card(Activity a, AttachmentList.Att at) {
        Palette pal = Palette.of(a);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(new TonalDrawable(pal.surfaceHigh, Ui.dp(a, 18)));
        c.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 236), ViewGroup.LayoutParams.WRAP_CONTENT));
        String ext = extension(at.name);
        TextView tag = new TextView(a);
        tag.setText(ext.isEmpty() ? "FILE" : ext.toUpperCase(Locale.ROOT));
        tag.setTextSize(ext.length() > 4 ? 9.5f : 11f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setGravity(Gravity.CENTER);
        tag.setMaxLines(1);
        int col = colorFor(ext, pal);
        tag.setTextColor(col);
        tag.setBackground(new TonalDrawable(Palette.alpha(col, 0.16f), Ui.dp(a, 12)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
        tl.setMargins(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        c.addView(tag, tl);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(a);
        name.setText(at.name);
        name.setTextColor(pal.onSurface);
        name.setTextSize(14f);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        TextView sub = new TextView(a);
        String info = at.info;
        int comma = info.indexOf(", ");
        if (at.kind == AttachmentList.FILE && comma > 0) info = info.substring(0, comma); // the size; the type is on the tag
        sub.setText(info);
        sub.setTextColor(pal.onSurfaceVariant);
        sub.setTextSize(12f);
        sub.setMaxLines(1);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(a, 2);
        texts.addView(sub, sl);
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        xl.setMargins(0, Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        c.addView(texts, xl);
        c.setContentDescription(at.name);
        c.setOnClickListener(v -> open(a, at.path));
        LiquidPress.attach(c, LiquidPress.LARGE);
        return c;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static int colorFor(String ext, Palette pal) {
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "doc": case "docx": case "odt": case "rtf":
                return 0xFF4C8DF6;
            case "xls": case "xlsx": case "csv": case "ods": case "tsv":
                return 0xFF34A853;
            case "ppt": case "pptx": case "key": case "odp":
                return 0xFFF0883E;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "apk":
                return 0xFFB57EDC;
            case "mp3": case "wav": case "m4a": case "ogg": case "flac": case "opus":
                return 0xFFE8618C;
            default:
                return pal.primary;
        }
    }

    /** A thumbnail for the tile (io thread). */
    private static Bitmap load(Activity a, AttachmentList.Att at, List<ChatMessage.Image> images, int px) throws Exception {
        if (at.path.isEmpty()) {
            if (at.image < 0 || at.image >= images.size()) return null;
            return decodeDataUrl(images.get(at.image).url, px);
        }
        File f = AppGraph.get().workspace.resolve(at.path);
        if (!f.isFile()) return null;
        switch (at.kind) {
            case AttachmentList.VIDEO: {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    Bitmap b = android.os.Build.VERSION.SDK_INT >= 27
                            ? r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px * 2, px * 2)
                            : r.getFrameAtTime(0);
                    return b == null ? null : com.suvo.agent.device.ImageUtil.scaleToMax(b, px * 2);
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            }
            case AttachmentList.PDF: {
                try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                     android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(fd);
                     android.graphics.pdf.PdfRenderer.Page page = pr.openPage(0)) {
                    float scale = (px * 2f) / Math.max(page.getWidth(), page.getHeight());
                    Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)), Math.max(1, (int) (page.getHeight() * scale)),
                            Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    page.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return b;
                }
            }
            default:
                return com.suvo.agent.device.ImageUtil.decodeFile(f, px * 2);
        }
    }

    static Bitmap decodeDataUrl(String url, int px) {
        if (url == null) return null;
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0) return null;
        byte[] data = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= px * 2) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** A photo that was only sent to the model (no file): shown large in a glass dialog. */
    private static void showLarge(Activity a, AttachmentList.Att at, List<ChatMessage.Image> images) {
        if (at.image < 0 || at.image >= images.size()) return;
        ImageView iv = new ImageView(a);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        round(iv, Ui.dp(a, 16));
        Bitmap b = decodeDataUrl(images.get(at.image).url, 1200);
        if (b == null) return;
        iv.setImageBitmap(b);
        LinearLayout box = new LinearLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        box.addView(iv, lp);
        Ui.show(Ui.dialog(a).setView(box).setPositiveButton(android.R.string.ok, null));
    }

    /** Opens a workspace file with another app (the viewer of its type). */
    static void open(Activity a, String path) {
        try {
            File f = AppGraph.get().workspace.resolve(path);
            if (!f.isFile()) {
                Ui.toast(a, a.getString(R.string.block_not_found, path));
                return;
            }
            Uri u = WorkspaceProvider.uriFor(a, f);
            String ext = extension(f.getName()).toLowerCase(Locale.ROOT);
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type == null ? "*/*" : type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, null));
        } catch (ActivityNotFoundException e) {
            Ui.toast(a, R.string.block_no_app);
        } catch (Exception e) {
            Ui.toast(a, a.getString(R.string.error_prefix, e.getMessage()));
        }
    }
}
