package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Your Friend — Original project by Suvo.
 *
 * The chat wallpaper: one picture behind every chat, chosen from the gallery (the system photo
 * picker, so no storage permission). The picture is copied into the app, scaled to the screen, so
 * it stays even if the original is deleted. Dim darkens it so the text stays readable; blur
 * softens it (Android 12+).
 */
public final class ChatWallpaper {

    public static final String DIM = "chat_wallpaper_dim";
    public static final String BLUR = "chat_wallpaper_blur";
    private static final String STAMP = "chat_wallpaper_stamp";
    private static final Object TAG = "yf:chat-wallpaper";

    private ChatWallpaper() {
    }

    public static File file(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "wallpaper"), "chat.jpg");
    }

    public static boolean isSet(Context ctx) {
        return file(ctx).isFile();
    }

    /** Opens the photo picker; the result comes back to {@code a.onActivityResult(req, …)}. */
    public static void pick(Activity a, int req) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        }
        try {
            a.startActivityForResult(i, req);
        } catch (Exception e) {
            try {
                a.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), req);
            } catch (Exception e2) {
                Ui.toast(a, R.string.files_no_picker);
            }
        }
    }

    /** Copies the picked picture into the app (scaled to the screen) in the background. */
    public static void save(Activity a, Uri uri, Runnable done) {
        Context app = a.getApplicationContext();
        int maxSide = Math.max(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        AppGraph.get().io.execute(() -> {
            String error = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, o2);
                }
                if (bmp == null) throw new java.io.IOException(app.getString(R.string.files_cannot_open));
                File f = file(app);
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }
                bmp.recycle();
                Settings.get(app).put(STAMP, String.valueOf(System.currentTimeMillis()));
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String err = error;
            a.runOnUiThread(() -> {
                if (err != null) Ui.toast(a, a.getString(R.string.error_prefix, err));
                if (done != null) done.run();
            });
        });
    }

    public static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
        Settings.get(ctx).put(STAMP, String.valueOf(System.currentTimeMillis()));
    }

    /** What the wallpaper looks like now (file + dim + blur): a change means it must be redrawn. */
    public static String stateKey(Context ctx) {
        Settings st = Settings.get(ctx);
        return st.str(STAMP, "") + "/" + isSet(ctx) + "/" + st.integer(DIM, 35) + "/" + st.integer(BLUR, 0);
    }

    /** Puts the wallpaper behind the screen's content (or removes it when none is set). */
    public static void apply(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View old = content.findViewWithTag(TAG);
        if (old != null) content.removeView(old);
        File f = file(a);
        if (!f.isFile()) return;
        Settings st = Settings.get(a);
        int dim = st.integer(DIM, 35), blur = st.integer(BLUR, 0);
        ImageView iv = new ImageView(a);
        iv.setTag(TAG);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(iv, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The dim layer over the picture (in the page colour of a light theme, black otherwise).
        Palette p = Palette.of(a);
        int veil = p.dark ? Color.BLACK : p.background;
        iv.setForeground(new ColorDrawable(Palette.alpha(veil, dim / 100f)));
        if (blur > 0 && Build.VERSION.SDK_INT >= 31) {
            float r = blur * a.getResources().getDisplayMetrics().density;
            iv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP));
        }
        AppGraph.get().io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp != null) a.runOnUiThread(() -> iv.setImageBitmap(bmp));
        });
    }
}
