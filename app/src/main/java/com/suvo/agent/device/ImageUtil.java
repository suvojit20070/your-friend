package com.suvo.agent.device;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;

import com.suvo.agent.core.llm.ChatMessage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Downscaling, JPEG encoding and data-URL conversion for images sent to vision models. */
public final class ImageUtil {

    private ImageUtil() {
    }

    public static Bitmap scaleToMax(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) return src;
        float s = (float) maxSide / longest;
        return Bitmap.createScaledBitmap(src, Math.round(w * s), Math.round(h * s), true);
    }

    public static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return bos.toByteArray();
    }

    public static ChatMessage.Image toImage(byte[] jpeg, String detail) {
        return new ChatMessage.Image("data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP), detail);
    }

    public static File save(byte[] data, File dir, String prefix, String ext) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, prefix + "_" + System.currentTimeMillis() + "." + ext);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    /** Decodes a file with subsampling so huge photos do not exhaust memory, honoring EXIF rotation. */
    public static Bitmap decodeFile(File f, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) throw new IOException("Not a supported image: " + f.getName());
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide);
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) throw new IOException("Could not decode " + f.getName());
        try {
            int orient = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
        } catch (IOException ignored) {
        }
        return scaleToMax(b, maxSide);
    }

    public static Bitmap decodeStream(InputStream in, int maxSide) throws IOException {
        byte[] all = readAll(in, 30 * 1024 * 1024);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(all, 0, all.length, o);
        if (o.outWidth <= 0) throw new IOException("Not a supported image");
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide);
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeByteArray(all, 0, all.length, o);
        if (b == null) throw new IOException("Could not decode image");
        return scaleToMax(b, maxSide);
    }

    private static int sample(int w, int h, int maxSide) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= maxSide) s *= 2;
        return s;
    }

    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > max) throw new IOException("File too large");
        }
        return bos.toByteArray();
    }
}
