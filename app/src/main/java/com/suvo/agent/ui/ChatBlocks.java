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
import android.widget.MediaController;
import android.widget.SeekBar;
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
        LinearLayout bar = row(ctx);
        TextView name = caption(ctx, p.caption.isEmpty() ? (p.title.isEmpty() ? nameOf(p.src) : p.title) : p.caption, st);
        bar.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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

        // Plays only after a real tap on the picture (never by itself when the chat opens or rebinds).
        final VideoView[] holder = new VideoView[1];
        Runnable start = () -> {
            if (holder[0] != null) return;
            VideoView vv = new VideoView(ctx);
            holder[0] = vv;
            MediaController mc = new MediaController(ctx);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            File f = localFile(ctx, p.src);
            if (f != null) vv.setVideoPath(f.getPath());
            else vv.setVideoURI(Uri.parse(p.src));
            vv.setOnPreparedListener(mp -> {
                thumb.setVisibility(View.GONE);
                play.setVisibility(View.GONE);
                vv.start();
            });
            vv.setOnErrorListener((mp, what, extra) -> {
                Ui.toast(ctx, R.string.block_video_error);
                return true;
            });
            frame.addView(vv, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.CENTER));
        };
        int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        float[] down = new float[3];
        frame.setOnTouchListener((v, e) -> {
            if (holder[0] != null) return false; // the player's own controls take over
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getX();
                    down[1] = e.getY();
                    down[2] = e.getEventTime();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (Math.abs(e.getX() - down[0]) < slop && Math.abs(e.getY() - down[1]) < slop
                            && e.getEventTime() - down[2] < 500) start.run();
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
                VideoView vv = holder[0];
                if (vv != null) {
                    vv.stopPlayback();
                    frame.removeView(vv);
                    holder[0] = null;
                    thumb.setVisibility(View.VISIBLE);
                    play.setVisibility(View.VISIBLE);
                }
            }
        });
        return c;
    }

    // ---- audio

    private static MediaPlayer player;
    private static String playing;
    private static Runnable stopUi;

    private static View audio(Context ctx, Parsed p, Markdown.Style st) {
        LinearLayout c = card(ctx, st);
        LinearLayout top = row(ctx);
        FrameLayout btnBox = badge(ctx, R.drawable.ic_play, st.onMarker, st.marker, -1, 42, 20);
        ImageView btn = badgeIcon(btnBox);
        btnBox.setClickable(true);
        btnBox.setContentDescription(ctx.getString(R.string.block_open));
        LiquidPress.attach(btnBox, LiquidPress.CIRCLE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMarginEnd(Ui.dp(ctx, 12));
        top.addView(btnBox, bp);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx, null, 0, R.style.YF_RowTitle);
        name.setText(p.title.isEmpty() ? (p.caption.isEmpty() ? nameOf(p.src) : p.caption) : p.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        TextView time = caption(ctx, "0:00", st);
        texts.addView(time);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top, margins(ctx, 12, 12, 12, 4));
        SeekBar seek = new SeekBar(ctx);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(st.marker));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(st.marker));
        c.addView(seek, margins(ctx, 4, 0, 4, 10));

        Runnable[] tick = new Runnable[1];
        Runnable reset = () -> {
            btn.setImageResource(R.drawable.ic_play);
            seek.setProgress(0);
            MAIN.removeCallbacks(tick[0]);
        };
        tick[0] = () -> {
            MediaPlayer mp = player;
            if (mp == null || !p.src.equals(playing)) return;
            try {
                int d = mp.getDuration(), pos = mp.getCurrentPosition();
                if (d > 0) seek.setProgress((int) (pos * 1000L / d));
                time.setText(fmt(pos) + " / " + fmt(d));
            } catch (IllegalStateException ignored) {
            }
            MAIN.postDelayed(tick[0], 300);
        };
        btnBox.setOnClickListener(v -> {
            if (player != null && p.src.equals(playing)) {
                if (player.isPlaying()) {
                    player.pause();
                    btn.setImageResource(R.drawable.ic_play);
                } else {
                    player.start();
                    btn.setImageResource(R.drawable.ic_pause);
                    MAIN.post(tick[0]);
                }
                return;
            }
            stopPlayer();
            MediaPlayer mp = new MediaPlayer();
            try {
                File f = localFile(ctx, p.src);
                if (f != null) mp.setDataSource(f.getPath());
                else mp.setDataSource(ctx, Uri.parse(p.src));
                mp.setOnPreparedListener(m -> {
                    m.start();
                    btn.setImageResource(R.drawable.ic_pause);
                    MAIN.post(tick[0]);
                });
                mp.setOnCompletionListener(m -> reset.run());
                mp.setOnErrorListener((m, w, e) -> {
                    reset.run();
                    Ui.toast(ctx, R.string.block_audio_error);
                    return true;
                });
                player = mp;
                playing = p.src;
                stopUi = reset;
                time.setText(ctx.getString(R.string.block_loading));
                mp.prepareAsync();
            } catch (Exception e) {
                mp.release();
                Ui.toast(ctx, R.string.block_audio_error);
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || player == null || !p.src.equals(playing)) return;
                try {
                    player.seekTo((int) ((long) player.getDuration() * progress / 1000));
                } catch (IllegalStateException ignored) {
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        c.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (p.src.equals(playing)) stopPlayer();
            }
        });
        return c;
    }

    /** Stops the chat's audio player (leaving the chat, or another clip starts). */
    static void stopPlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        player = null;
        playing = null;
        if (stopUi != null) {
            Runnable r = stopUi;
            stopUi = null;
            r.run();
        }
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
