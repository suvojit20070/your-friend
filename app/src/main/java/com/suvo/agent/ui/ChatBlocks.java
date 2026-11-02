package com.suvo.agent.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.files.WorkspaceProvider;
import com.suvo.agent.tools.Http;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your Friend — Original project by Suvo.
 *
 * Rich blocks the AI can put in an answer, written as fenced blocks with a special language:
 * <pre>
 * ```image      url / workspace path / content:// (+ "caption: …")
 * ```video      the same, plays inline
 * ```audio      the same, a small player
 * ```file       a document card (Open / Share)
 * ```link       a URL or a path (+ "title: …"), opens on tap
 * ```draft      a draft (optional "to:", "subject:" lines, then the text): Copy / Use / Share
 * ```buttons    one button per line: "Label" (sends it), "Label | send: text", "Label | open: url",
 *               "Label | copy: text", "Label | draft: text" (puts the text in the message box)
 * </pre>
 * While an answer is still streaming the block shows as a small "preparing" card; media load in
 * the background (images are cached).
 */
final class ChatBlocks {

    private ChatBlocks() {
    }

    /** What the chat screen does for buttons / drafts. */
    interface Actions {
        void sendMessage(String text);

        void putInComposer(String text);

        /** Every audio track in the chat, in order (the play queue). */
        List<ChatMedia.Track> audioQueue();
    }

    static Actions actions;

    private static final Set<String> IMAGE = new HashSet<>(Arrays.asList("image", "img", "photo", "picture"));
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList("video", "movie"));
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList("audio", "music", "voice", "song"));
    private static final Set<String> FILE = new HashSet<>(Arrays.asList("file", "document", "doc", "pdf", "attachment"));
    private static final Set<String> LINK = new HashSet<>(Arrays.asList("link", "url", "path"));
    private static final Set<String> DRAFT = new HashSet<>(Arrays.asList("draft", "message-draft", "email-draft"));
    private static final Set<String> BUTTONS = new HashSet<>(Arrays.asList("buttons", "button", "actions"));

    private static String kind(Markdown.Block b) {
        if (b.type != Markdown.Block.CODE || b.lang == null) return null;
        String l = b.lang.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("yf-")) l = l.substring(3);
        if (IMAGE.contains(l)) return "image";
        if (VIDEO.contains(l)) return "video";
        if (AUDIO.contains(l)) return "audio";
        if (FILE.contains(l)) return "file";
        if (LINK.contains(l)) return "link";
        if (DRAFT.contains(l)) return "draft";
        if (BUTTONS.contains(l)) return "buttons";
        return null;
    }

    static boolean handles(Markdown.Block b) {
        return kind(b) != null;
    }

    // ------------------------------------------------------------------ parsing

    private static final class Parsed {
        String src = "";
        String caption = "";
        String title = "";
        String to = "", subject = "";
        String body = "";
        final List<String> lines = new ArrayList<>();
    }

    private static Parsed parse(String code, boolean draft) {
        Parsed p = new Parsed();
        String[] lines = code.replace("\r", "").split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean header = true;
        for (String raw : lines) {
            String l = raw.trim();
            String low = l.toLowerCase(Locale.ROOT);
            if (header && draft && (low.startsWith("to:") || low.startsWith("subject:") || low.startsWith("title:") || low.startsWith("cc:"))) {
                String v = l.substring(l.indexOf(':') + 1).trim();
                if (low.startsWith("to:") || low.startsWith("cc:")) p.to = p.to.isEmpty() ? v : p.to + ", " + v;
                else if (low.startsWith("subject:")) p.subject = v;
                else p.title = v;
                continue;
            }
            if (draft) {
                if (header && l.isEmpty()) {
                    header = false;
                    continue;
                }
                header = false;
                if (body.length() > 0) body.append('\n');
                body.append(raw);
                continue;
            }
            if (l.isEmpty()) continue;
            if (low.startsWith("caption:")) p.caption = l.substring(8).trim();
            else if (low.startsWith("title:") || low.startsWith("name:")) p.title = l.substring(l.indexOf(':') + 1).trim();
            else if (p.src.isEmpty() && !low.startsWith("#")) p.src = stripLink(l);
            p.lines.add(l);
        }
        p.body = body.toString().trim();
        return p;
    }

    /** "[x](url)" or "<url>" → url. */
    private static String stripLink(String s) {
        String t = s.trim();
        if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1);
        int a = t.indexOf("]("), b = t.lastIndexOf(')');
        if (t.startsWith("[") && a > 0 && b > a) t = t.substring(a + 2, b);
        return t.trim();
    }

    // ------------------------------------------------------------------ sources

    private static boolean isWeb(String src) {
        String l = src.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /** A local file for the source (absolute path or workspace path), or null. */
    private static File localFile(Context ctx, String src) {
        if (isWeb(src) || src.startsWith("content://")) return null;
        String path = src.startsWith("file://") ? Uri.parse(src).getPath() : src;
        if (path == null) return null;
        try {
            File f = new File(path);
            if (f.isAbsolute() && f.exists()) return f;
            File w = AppGraph.get().workspace.resolve(path);
            if (w.exists()) return w;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A URI another app may open (workspace file → content URI, web → as is). */
    private static Uri shareable(Context ctx, String src) {
        if (isWeb(src) || src.startsWith("content://")) return Uri.parse(src);
        File f = localFile(ctx, src);
        if (f == null) return null;
        try {
            return WorkspaceProvider.uriFor(ctx, f);
        } catch (Exception notInWorkspace) {
            // Outside the workspace: copy it in so it can be shared.
            try {
                File dir = AppGraph.get().workspace.resolve("shared");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File copy = new File(dir, f.getName());
                try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = new java.io.FileOutputStream(copy)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return WorkspaceProvider.uriFor(ctx, copy);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static String nameOf(String src) {
        String s = src;
        int q = s.indexOf('?');
        if (q > 0 && isWeb(s)) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        String n = slash >= 0 ? s.substring(slash + 1) : s;
        try {
            n = Uri.decode(n);
        } catch (RuntimeException ignored) {
        }
        return n.isEmpty() ? src : n;
    }

    private static String mime(String src) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(src.replace(" ", "%20"));
        String m = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        return m == null ? "*/*" : m;
    }

    private static void open(Context ctx, String src, String type) {
        Uri u = shareable(ctx, src);
        if (u == null) {
            Ui.toast(ctx, ctx.getString(R.string.block_not_found, src));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (isWeb(src)) i.setData(u);
        else i.setDataAndType(u, type == null ? mime(src) : type);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Ui.toast(ctx, R.string.block_no_app);
        }
    }

    private static void share(Context ctx, String src, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        if (src != null) {
            Uri u = shareable(ctx, src);
            if (u == null) {
                Ui.toast(ctx, ctx.getString(R.string.block_not_found, src));
                return;
            }
            if (isWeb(src)) {
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, src);
            } else {
                i.setType(mime(src));
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } else {
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
        }
        try {
            ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Ui.toast(ctx, R.string.block_no_app);
        }
    }

    // ------------------------------------------------------------------ views

    /** The view for a block; {@code done} false while the answer is still streaming. */
    static View view(Context ctx, Markdown.Block b, Markdown.Style st, boolean done) {
        String k = kind(b);
        if (!done) return pending(ctx, k, st);
        try {
            switch (k) {
                case "image":
                    return image(ctx, parse(b.code, false), st);
                case "video":
                    return video(ctx, parse(b.code, false), st);
                case "audio":
                    return audio(ctx, parse(b.code, false), st);
                case "file":
                    return file(ctx, parse(b.code, false), st);
                case "link":
                    return link(ctx, parse(b.code, false), st);
                case "draft":
                    return draft(ctx, parse(b.code, true), st);
                default:
                    return buttons(ctx, b.code, st);
            }
        } catch (RuntimeException e) {
            TextView t = new TextView(ctx, null, 0, R.style.YF_Caption);
            t.setText(b.code);
            return t;
        }
    }

    private static LinearLayout card(Context ctx, Markdown.Style st) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(new TonalDrawable(st.codeBackground, Ui.dp(ctx, 20)));
        // No clipping: buttons inside keep their press effect whole; children are spaced by margins.
        return c;
    }

    private static View pending(Context ctx, String k, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        TextView t = new TextView(ctx, null, 0, R.style.YF_Caption);
        t.setText(ctx.getString(R.string.block_preparing, k));
        t.setTextColor(st.muted);
        c.addView(t, margins(ctx, 16, 14, 16, 14));
        return c;
    }

    private static LinearLayout.LayoutParams margins(Context ctx, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(ctx, l), Ui.dp(ctx, t), Ui.dp(ctx, r), Ui.dp(ctx, b));
        return lp;
    }

    private static TextView caption(Context ctx, String text, Markdown.Style st) {
        TextView t = new TextView(ctx, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setTextColor(st.muted);
        return t;
    }

    /**
     * A small capsule button: the capsule carries the background and press; the icon and label
     * inside are spaced with their own margins.
     */
    private static View pill(Context ctx, int icon, CharSequence text, Markdown.Style st, View.OnClickListener l) {
        Palette pal = Palette.of(ctx);
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(new TonalDrawable(Palette.alpha(st.marker, 0.13f), -1).pressable(pal.pressed()));
        if (icon != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(st.marker));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16));
            ip.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8));
            b.addView(iv, ip);
        }
        TextView t = new TextView(ctx, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(st.marker);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMargins(Ui.dp(ctx, icon != 0 ? 6 : 14), Ui.dp(ctx, 7), Ui.dp(ctx, 14), Ui.dp(ctx, 7));
        b.addView(t, tp);
        b.setClickable(true);
        b.setContentDescription(text);
        b.setOnClickListener(l);
        LiquidPress.attach(b, LiquidPress.TEXT);
        return b;
    }

    /** A round / rounded badge: background on the frame, the icon inside placed with margins. */
    private static FrameLayout badge(Context ctx, int icon, int iconTint, int bg, float radiusDp, int sizeDp, int iconDp) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(new TonalDrawable(bg, radiusDp < 0 ? -1 : Ui.dp(ctx, radiusDp)));
        ImageView iv = new ImageView(ctx);
        iv.setTag("badge_icon");
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(iconTint));
        int m = Ui.dp(ctx, (sizeDp - iconDp) / 2f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(ctx, iconDp), Ui.dp(ctx, iconDp), Gravity.CENTER);
        lp.setMargins(m, m, m, m);
        f.addView(iv, lp);
        return f;
    }

    private static ImageView badgeIcon(FrameLayout f) {
        return (ImageView) f.findViewWithTag("badge_icon");
    }

    /** A tinted label chip: background on the frame, the text inside with margins. */
    private static FrameLayout chip(Context ctx, CharSequence text, int color) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(new TonalDrawable(Palette.alpha(color, 0.14f), -1));
        TextView t = new TextView(ctx, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setTextSize(12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 3), Ui.dp(ctx, 10), Ui.dp(ctx, 3));
        f.addView(t, lp);
        return f;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private static void addPill(LinearLayout row, View pill) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(row.getContext(), 8));
        row.addView(pill, lp);
    }

    // ---- image

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "yf-chat-media");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View image(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        c.setClipToOutline(true); // rounded picture corners
        ImageView iv = new ImageView(ctx);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumHeight(Ui.dp(ctx, 120));
        iv.setBackgroundColor(Palette.alpha(st.muted, 0.08f));
        c.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView cap = caption(ctx, p.caption.isEmpty() ? nameOf(p.src) : p.caption, st);
        c.addView(cap, margins(ctx, 14, 8, 14, 10));
        iv.setOnClickListener(v -> open(ctx, p.src, isWeb(p.src) ? null : "image/*"));
        iv.setOnLongClickListener(v -> {
            share(ctx, p.src, null);
            return true;
        });
        loadBitmap(ctx, p.src, iv, cap);
        return c;
    }

    private static void loadBitmap(Context ctx, String src, ImageView iv, TextView status) {
        Bitmap cached = CACHE.get(src);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        String keep = status.getText().toString();
        status.setText(ctx.getString(R.string.block_loading));
        LOADER.execute(() -> {
            Bitmap bm = null;
            String err = null;
            try {
                byte[] data = null;
                if (isWeb(src)) {
                    Http.Response r = Http.get(src, null, 15 * 1024 * 1024, 30_000, false, null);
                    if (r.code >= 400) err = "HTTP " + r.code;
                    else data = r.body;
                } else if (src.startsWith("content://")) {
                    try (java.io.InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(src))) {
                        if (in != null) data = com.suvo.agent.device.ImageUtil.readAll(in, 30 * 1024 * 1024);
                    }
                } else {
                    File f = localFile(ctx, src);
                    if (f == null) err = ctx.getString(R.string.block_not_found, src);
                    else bm = decodeFile(f.getPath());
                }
                if (data != null) bm = decodeBytes(data);
            } catch (Exception e) {
                err = e.getMessage();
            }
            Bitmap fbm = bm;
            String ferr = err;
            MAIN.post(() -> {
                if (fbm != null) {
                    CACHE.put(src, fbm);
                    iv.setImageBitmap(fbm);
                    status.setText(keep);
                } else {
                    status.setText(ctx.getString(R.string.block_failed, ferr == null ? "?" : ferr));
                }
            });
        });
    }

    private static Bitmap decodeBytes(byte[] d) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(d, 0, d.length, o);
    }

    private static Bitmap decodeFile(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, o);
    }

    private static int sample(int w, int h) {
        int s = 1;
        while (w / (s * 2) >= 1280 || h / (s * 2) >= 1280) s *= 2;
        return s;
    }

    // ---- video

    /** Video: thumbnail + play badge; a real tap plays it inline with the app's own controls. */
    private static View video(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(0xFF000000);
        int h = Ui.dp(ctx, 210);
        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        FrameLayout play = badge(ctx, R.drawable.ic_play, 0xFFFFFFFF, 0x66000000, -1, 56, 28);
        frame.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                float r = Ui.dp(ctx, 20);
                // Rounded top only (the caption bar sits below).
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + Math.round(r), r);
            }
        });
        c.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        // The app's own controls over the bottom of the video (hidden until it plays).
        LinearLayout controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        FrameLayout ctlPlay = badge(ctx, R.drawable.ic_pause, 0xFFFFFFFF, 0x33FFFFFF, -1, 36, 18);
        ctlPlay.setClickable(true);
        LiquidPress.attach(ctlPlay, LiquidPress.CIRCLE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 18), 0, Ui.dp(ctx, 8));
        controls.addView(ctlPlay, cp);
        LiquidSlider seek = new LiquidSlider(ctx);
        seek.setValueFrom(0);
        seek.setValueTo(1000);
        seek.setStepSize(0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMargins(Ui.dp(ctx, -18), Ui.dp(ctx, 14), Ui.dp(ctx, -18), Ui.dp(ctx, 4));
        controls.addView(seek, sp);
        TextView time = new TextView(ctx, null, 0, R.style.YF_Caption);
        time.setTextColor(0xFFFFFFFF);
        time.setTextSize(11.5f);
        time.setText("0:00");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, Ui.dp(ctx, 18), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
        controls.addView(time, tp);
        controls.setVisibility(View.GONE);
        frame.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        LinearLayout bar = row(ctx);
        String name = p.caption.isEmpty() ? (p.title.isEmpty() ? nameOf(p.src) : p.title) : p.caption;
        bar.addView(caption(ctx, name, st), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addPill(bar, pill(ctx, R.drawable.ic_upload, ctx.getString(R.string.block_open), st, v -> open(ctx, p.src, isWeb(p.src) ? null : "video/*")));
        c.addView(bar, margins(ctx, 14, 8, 6, 10));

        // Thumbnail in the background.
        LOADER.execute(() -> {
            Bitmap bm = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (isWeb(p.src)) r.setDataSource(p.src, new java.util.HashMap<>());
                else if (p.src.startsWith("content://")) r.setDataSource(ctx, Uri.parse(p.src));
                else {
                    File f = localFile(ctx, p.src);
                    if (f != null) r.setDataSource(f.getPath());
                }
                bm = r.getFrameAtTime(1_000_000);
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            Bitmap fb = bm;
            if (fb != null) MAIN.post(() -> thumb.setImageBitmap(fb));
        });

        final VideoView[] holder = new VideoView[1];
        final ChatMedia.Target[] target = new ChatMedia.Target[1];
        final boolean[] dragging = new boolean[1];
        Runnable[] tick = new Runnable[1];
        Runnable hideLater = () -> {
            if (holder[0] != null && holder[0].isPlaying()) controls.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> controls.setVisibility(View.GONE)).start();
        };
        Runnable showControls = () -> {
            controls.animate().cancel();
            controls.setAlpha(1f);
            controls.setVisibility(View.VISIBLE);
            MAIN.removeCallbacks(hideLater);
            MAIN.postDelayed(hideLater, 3000);
        };
        Runnable paint = () -> {
            VideoView vv = holder[0];
            if (vv == null) return;
            ImageView ic = badgeIcon(ctlPlay);
            ic.setImageResource(vv.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        };
        tick[0] = () -> {
            VideoView vv = holder[0];
            if (vv == null) return;
            try {
                int d = vv.getDuration(), pos = vv.getCurrentPosition();
                if (d > 0 && !dragging[0]) seek.setValue(pos * 1000f / d);
                time.setText(fmt(pos) + " / " + fmt(d));
            } catch (RuntimeException ignored) {
            }
            MAIN.postDelayed(tick[0], 400);
        };
        Runnable start = () -> {
            if (holder[0] != null) return;
            VideoView vv = new VideoView(ctx);
            holder[0] = vv;
            File f = localFile(ctx, p.src);
            if (f != null) vv.setVideoPath(f.getPath());
            else vv.setVideoURI(Uri.parse(p.src));
            target[0] = new ChatMedia.Target() {
                @Override
                public void play() {
                    vv.start();
                    MAIN.post(paint);
                }

                @Override
                public void pause() {
                    vv.pause();
                    MAIN.post(paint);
                    showControls.run();
                }

                @Override
                public void release() {
                    MAIN.post(() -> {
                        vv.stopPlayback();
                        frame.removeView(vv);
                        holder[0] = null;
                        MAIN.removeCallbacks(tick[0]);
                        controls.setVisibility(View.GONE);
                        thumb.setVisibility(View.VISIBLE);
                        play.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public boolean isPlaying() {
                    return vv.isPlaying();
                }

                @Override
                public int position() {
                    return vv.getCurrentPosition();
                }

                @Override
                public int duration() {
                    return vv.getDuration();
                }

                @Override
                public void seekTo(int ms) {
                    vv.seekTo(ms);
                }
            };
            vv.setOnPreparedListener(mp -> {
                thumb.setVisibility(View.GONE);
                play.setVisibility(View.GONE);
                vv.start();
                paint.run();
                showControls.run();
                MAIN.post(tick[0]);
                ChatMedia.start(ctx, p.src, name, false, target[0]);
            });
            vv.setOnCompletionListener(mp -> {
                paint.run();
                showControls.run();
                ChatMedia.changed();
            });
            vv.setOnErrorListener((mp, what, extra) -> {
                Ui.toast(ctx, R.string.block_video_error);
                ChatMedia.stopIf(p.src, target[0]);
                return true;
            });
            frame.addView(vv, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.CENTER));
        };
        ctlPlay.setOnClickListener(v -> {
            VideoView vv = holder[0];
            if (vv == null) return;
            if (vv.isPlaying()) target[0].pause();
            else target[0].play();
            showControls.run();
            ChatMedia.changed();
        });
        seek.addOnSliderTouchListener(new LiquidSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(LiquidSlider s) {
                dragging[0] = true;
                MAIN.removeCallbacks(hideLater);
            }

            @Override
            public void onStopTrackingTouch(LiquidSlider s) {
                dragging[0] = false;
                VideoView vv = holder[0];
                if (vv != null && vv.getDuration() > 0) vv.seekTo((int) (vv.getDuration() * s.getValue() / 1000f));
                showControls.run();
                ChatMedia.changed();
            }
        });
        // Plays only after a real tap on the picture (never by itself when the chat opens or rebinds).
        int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        float[] down = new float[3];
        frame.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getX();
                    down[1] = e.getY();
                    down[2] = e.getEventTime();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    boolean tap = Math.abs(e.getX() - down[0]) < slop && Math.abs(e.getY() - down[1]) < slop
                            && e.getEventTime() - down[2] < 500;
                    if (tap) {
                        if (holder[0] == null) start.run();
                        else if (controls.getVisibility() == View.VISIBLE) hideLater.run();
                        else showControls.run();
                    }
                    return true;
                default:
                    return true;
            }
        });
        c.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (holder[0] != null) ChatMedia.stopIf(p.src, target[0]);
            }
        });
        return c;
    }

    // ---- audio

    /** Every ```audio block in a Markdown text, in order (for the chat's play queue). */
    static List<ChatMedia.Track> audioTracks(Context ctx, String markdown) {
        List<ChatMedia.Track> out = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return out;
        String[] lines = markdown.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("```")) continue;
            String lang = l.substring(3).trim().toLowerCase(Locale.ROOT);
            if (lang.startsWith("yf-")) lang = lang.substring(3);
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().startsWith("```")) {
                body.append(lines[j]).append('\n');
                j++;
            }
            if (AUDIO.contains(lang)) {
                Parsed p = parse(body.toString(), false);
                if (!p.src.isEmpty()) out.add(track(ctx, p));
            }
            i = j;
        }
        return out;
    }

    private static String audioName(Parsed p) {
        return p.title.isEmpty() ? (p.caption.isEmpty() ? nameOf(p.src) : p.caption) : p.title;
    }

    private static ChatMedia.Track track(Context ctx, Parsed p) {
        File f = localFile(ctx, p.src);
        return new ChatMedia.Track(p.src, audioName(p), f == null ? null : f.getPath());
    }

    /** A small icon button for the player (the frame carries the press, the icon has margins). */
    private static FrameLayout iconButton(Context ctx, int icon, int tint, View.OnClickListener l) {
        FrameLayout b = badge(ctx, icon, tint, 0, -1, 38, 20);
        b.setBackground(new TonalDrawable(0, -1).pressable(Palette.of(ctx).pressed()));
        b.setClickable(true);
        b.setOnClickListener(l);
        LiquidPress.attach(b, LiquidPress.ICON);
        return b;
    }

    private static View audio(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        LinearLayout top = row(ctx);
        FrameLayout btnBox = badge(ctx, R.drawable.ic_play, st.onMarker, st.marker, -1, 42, 20);
        ImageView btn = badgeIcon(btnBox);
        btnBox.setClickable(true);
        btnBox.setContentDescription(ctx.getString(R.string.media_play));
        LiquidPress.attach(btnBox, LiquidPress.CIRCLE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMarginEnd(Ui.dp(ctx, 12));
        top.addView(btnBox, bp);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = new TextView(ctx, null, 0, R.style.YF_RowTitle);
        String name = audioName(p);
        nameView.setText(name);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(nameView);
        TextView time = caption(ctx, ctx.getString(R.string.media_tap_to_play), st);
        texts.addView(time);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top, margins(ctx, 12, 12, 12, 12));

        // Only the playing track shows the progress slider and the queue controls.
        LinearLayout player = new LinearLayout(ctx);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setVisibility(View.GONE);
        LiquidSlider seek = new LiquidSlider(ctx);
        seek.setValueFrom(0);
        seek.setValueTo(1000);
        seek.setStepSize(0);
        player.addView(seek, margins(ctx, -14, -8, -14, 0));
        LinearLayout ctl = row(ctx);
        ctl.setGravity(Gravity.CENTER);
        int on = st.marker, off = Palette.alpha(st.muted, 0.9f);
        FrameLayout shuffleB = iconButton(ctx, R.drawable.ic_shuffle, off, v -> ChatMedia.toggleShuffle(ctx));
        FrameLayout prevB = iconButton(ctx, R.drawable.ic_skip_prev, on, v -> ChatMedia.previous());
        FrameLayout nextB = iconButton(ctx, R.drawable.ic_skip_next, on, v -> ChatMedia.next());
        FrameLayout repeatB = iconButton(ctx, R.drawable.ic_repeat, off, v -> ChatMedia.cycleRepeat(ctx));
        TextView queuePos = caption(ctx, "", st);
        for (View b : new View[]{shuffleB, prevB, nextB, repeatB}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0);
            ctl.addView(b, lp);
            if (b == prevB) {
                LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                qp.setMargins(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0);
                ctl.addView(queuePos, qp);
            }
        }
        player.addView(ctl, margins(ctx, 12, 0, 12, 10));
        c.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean[] dragging = new boolean[1];
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            boolean mine = p.src.equals(ChatMedia.current());
            ChatMedia.Target t = ChatMedia.target();
            btn.setImageResource(mine && t != null && t.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            player.setVisibility(mine ? View.VISIBLE : View.GONE);
            if (!mine || t == null) {
                time.setText(ctx.getString(R.string.media_tap_to_play));
                return;
            }
            try {
                int d = t.duration(), pos = t.position();
                if (d > 0 && !dragging[0]) seek.setValue(pos * 1000f / d);
                time.setText(d > 0 ? fmt(pos) + " / " + fmt(d) : ctx.getString(R.string.block_loading));
            } catch (RuntimeException ignored) {
            }
            String qp = ChatMedia.queuePosition();
            boolean list = !qp.isEmpty();
            queuePos.setText(qp);
            for (View b : new View[]{shuffleB, prevB, nextB, repeatB, queuePos}) b.setVisibility(list ? View.VISIBLE : View.GONE);
            ctl.setVisibility(list ? View.VISIBLE : View.GONE);
            badgeIcon(shuffleB).setImageResource(ChatMedia.shuffle() ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off);
            badgeIcon(shuffleB).setImageTintList(android.content.res.ColorStateList.valueOf(ChatMedia.shuffle() ? on : off));
            int rp = ChatMedia.repeat();
            badgeIcon(repeatB).setImageResource(rp == ChatMedia.REPEAT_ONE ? R.drawable.ic_repeat_one : rp == ChatMedia.REPEAT_ALL ? R.drawable.ic_repeat : R.drawable.ic_repeat_off);
            badgeIcon(repeatB).setImageTintList(android.content.res.ColorStateList.valueOf(rp == ChatMedia.REPEAT_OFF ? off : on));
            MAIN.removeCallbacks(tick[0]);
            if (t.isPlaying()) MAIN.postDelayed(tick[0], 400);
        };
        ChatMedia.Listener listener = () -> {
            MAIN.removeCallbacks(tick[0]);
            tick[0].run();
        };
        btnBox.setOnClickListener(v -> {
            if (p.src.equals(ChatMedia.current())) {
                ChatMedia.toggle();
                return;
            }
            // The whole chat's audio becomes the queue, starting at this track.
            List<ChatMedia.Track> tracks = actions != null ? actions.audioQueue() : null;
            int start = -1;
            if (tracks != null) for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).src.equals(p.src)) {
                start = i;
                break;
            }
            if (tracks == null || start < 0) {
                tracks = new ArrayList<>();
                tracks.add(track(ctx, p));
                start = 0;
            }
            time.setText(ctx.getString(R.string.block_loading));
            ChatMedia.playQueue(ctx, tracks, start);
        });
        seek.addOnSliderTouchListener(new LiquidSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(LiquidSlider s) {
                dragging[0] = true;
            }

            @Override
            public void onStopTrackingTouch(LiquidSlider s) {
                dragging[0] = false;
                ChatMedia.Target t = ChatMedia.target();
                if (t != null && p.src.equals(ChatMedia.current()) && t.duration() > 0) {
                    t.seekTo((int) (t.duration() * s.getValue() / 1000f));
                    ChatMedia.changed();
                }
            }
        });
        c.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                ChatMedia.addListener(listener);
                tick[0].run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // The audio keeps playing (notification); only this card stops listening.
                ChatMedia.removeListener(listener);
                MAIN.removeCallbacks(tick[0]);
            }
        });
        return c;
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---- file

    private static View file(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        LinearLayout top = row(ctx);
        FrameLayout icon = badge(ctx, R.drawable.ic_files, st.marker, Palette.alpha(st.marker, 0.13f), 14, 44, 22);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMarginEnd(Ui.dp(ctx, 12));
        top.addView(icon, ip);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx, null, 0, R.style.YF_RowTitle);
        name.setText(p.title.isEmpty() ? nameOf(p.src) : p.title);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        File f = localFile(ctx, p.src);
        String meta = (f != null ? Ui.size(f.length()) + " · " : "") + mime(p.src).replace("*/*", ctx.getString(R.string.block_file));
        if (f == null && !isWeb(p.src) && !p.src.startsWith("content://")) meta = ctx.getString(R.string.block_not_found, p.src);
        texts.addView(caption(ctx, meta, st));
        if (!p.caption.isEmpty()) texts.addView(caption(ctx, p.caption, st));
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top, margins(ctx, 12, 12, 12, 8));
        LinearLayout bar = row(ctx);
        addPill(bar, pill(ctx, R.drawable.ic_eye, ctx.getString(R.string.block_open), st, v -> open(ctx, p.src, null)));
        addPill(bar, pill(ctx, R.drawable.ic_upload, ctx.getString(R.string.block_share), st, v -> share(ctx, p.src, null)));
        c.addView(bar, margins(ctx, 12, 0, 12, 12));
        return c;
    }

    // ---- link / path

    private static View link(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        LinearLayout top = row(ctx);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(isWeb(p.src) ? R.drawable.ic_globe : R.drawable.ic_files);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(st.marker));
        int s = Ui.dp(ctx, 22);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.setMarginEnd(Ui.dp(ctx, 12));
        top.addView(icon, ip);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx, null, 0, R.style.YF_RowTitle);
        String host = isWeb(p.src) ? Uri.parse(p.src).getHost() : null;
        title.setText(!p.title.isEmpty() ? p.title : host != null ? host : nameOf(p.src));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView url = caption(ctx, p.src, st);
        url.setMaxLines(2);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        url.setTypeface(Typeface.MONOSPACE);
        texts.addView(url);
        if (!p.caption.isEmpty()) texts.addView(caption(ctx, p.caption, st));
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top, margins(ctx, 14, 12, 14, 12));
        Palette pal = Palette.of(ctx);
        c.setBackground(new TonalDrawable(st.codeBackground, Ui.dp(ctx, 20)).pressable(pal.pressed()));
        c.setClickable(true);
        c.setOnClickListener(v -> open(ctx, p.src, null));
        c.setOnLongClickListener(v -> {
            Ui.copy(ctx, p.src);
            return true;
        });
        return c;
    }

    // ---- draft

    private static View draft(Context ctx, Parsed p, Markdown.Style st) {
        Palette pal = Palette.of(ctx);
        LinearLayout c = card(ctx, st);
        c.setBackground(new TonalDrawable(Palette.alpha(st.marker, 0.08f), Ui.dp(ctx, 20)));
        LinearLayout head = row(ctx);
        head.addView(chip(ctx, p.title.isEmpty() ? ctx.getString(R.string.block_draft) : p.title, st.marker));
        c.addView(head, margins(ctx, 14, 12, 14, 4));
        if (!p.to.isEmpty()) c.addView(caption(ctx, ctx.getString(R.string.block_draft_to, p.to), st), margins(ctx, 14, 4, 14, 0));
        if (!p.subject.isEmpty()) {
            TextView sub = new TextView(ctx, null, 0, R.style.YF_RowTitle);
            sub.setText(p.subject);
            c.addView(sub, margins(ctx, 14, 6, 14, 0));
        }
        TextView body = new TextView(ctx, null, 0, R.style.YF_Body);
        body.setText(p.body);
        body.setTextColor(pal.onSurface);
        body.setLineSpacing(Ui.dp(ctx, 3), 1f);
        Ui.selectionColors(body);
        body.setTextIsSelectable(true);
        c.addView(body, margins(ctx, 14, 8, 14, 8));
        String all = (p.subject.isEmpty() ? "" : p.subject + "\n\n") + p.body;
        LinearLayout bar = row(ctx);
        addPill(bar, pill(ctx, R.drawable.ic_copy, ctx.getString(R.string.copy), st, v -> {
            Ui.copy(ctx, p.body);
            Ui.toast(ctx, R.string.copied);
        }));
        addPill(bar, pill(ctx, R.drawable.ic_edit, ctx.getString(R.string.block_use), st, v -> {
            if (actions != null) actions.putInComposer(p.body);
        }));
        addPill(bar, pill(ctx, R.drawable.ic_upload, ctx.getString(R.string.block_share), st, v -> share(ctx, null, all)));
        c.addView(bar, margins(ctx, 12, 0, 12, 12));
        return c;
    }

    // ---- buttons

    private static View buttons(Context ctx, String code, Markdown.Style st) {
        FlowRow flow = new FlowRow(ctx, Ui.dp(ctx, 8));
        for (String raw : code.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("- ") || l.startsWith("* ")) l = l.substring(2).trim();
            String label = l, action = "";
            int bar = l.indexOf('|');
            int arrow = l.indexOf("->");
            if (l.startsWith("[") && l.contains("](") && l.endsWith(")")) {
                label = l.substring(1, l.indexOf("]("));
                action = l.substring(l.indexOf("](") + 2, l.length() - 1);
                if (isWeb(action)) action = "open:" + action;
            } else if (bar > 0) {
                label = l.substring(0, bar).trim();
                action = l.substring(bar + 1).trim();
            } else if (arrow > 0) {
                label = l.substring(0, arrow).trim();
                action = l.substring(arrow + 2).trim();
            }
            if (label.isEmpty()) continue;
            String lab = label, act = action;
            flow.addView(pill(ctx, 0, label, st, v -> runAction(ctx, lab, act)));
        }
        return flow;
    }

    private static void runAction(Context ctx, String label, String action) {
        String a = action.trim();
        String low = a.toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            if (actions != null) actions.sendMessage(label);
        } else if (low.startsWith("send:")) {
            if (actions != null) actions.sendMessage(a.substring(5).trim());
        } else if (low.startsWith("open:")) {
            open(ctx, a.substring(5).trim(), null);
        } else if (low.startsWith("copy:")) {
            Ui.copy(ctx, a.substring(5).trim());
            Ui.toast(ctx, R.string.copied);
        } else if (low.startsWith("draft:") || low.startsWith("fill:")) {
            if (actions != null) actions.putInComposer(a.substring(a.indexOf(':') + 1).trim());
        } else if (isWeb(a)) {
            open(ctx, a, null);
        } else {
            if (actions != null) actions.sendMessage(a);
        }
    }

    /** Lays children out left to right, wrapping to new lines. */
    static final class FlowRow extends ViewGroup {
        private final int gap;

        FlowRow(Context ctx, int gap) {
            super(ctx);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int max = MeasureSpec.getSize(wSpec);
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) max = Integer.MAX_VALUE;
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                x += c.getMeasuredWidth() + gap;
                lineH = Math.max(lineH, c.getMeasuredHeight());
            }
            int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? x : MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                c.layout(x, y, x + cw, y + ch);
                x += cw + gap;
                lineH = Math.max(lineH, ch);
            }
        }
    }
}
