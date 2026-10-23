package com.suvo.agent.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Your Friend — Original project by Suvo.
 *
 * Gives the camera app one writable file for "Add to conversation → Camera": the photo is taken by
 * the user's own camera app (with its preview), saved into our cache, and attached to the message.
 * Only that single file is served, and only to apps we grant it to for this one capture.
 */
public final class CaptureProvider extends ContentProvider {

    private static final String NAME = "capture.jpg";

    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".capture";
    }

    public static File file(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "capture");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, NAME);
    }

    /** A fresh empty capture file and its content URI. */
    public static Uri newCapture(Context ctx) throws IOException {
        File f = file(ctx);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!f.createNewFile()) throw new IOException("Cannot create the capture file");
        return new Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(NAME).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (!NAME.equals(uri.getLastPathSegment()) || getContext() == null) throw new FileNotFoundException(String.valueOf(uri));
        return file(getContext());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m = mode != null && mode.contains("w")
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = fileFor(uri);
            c.addRow(new Object[]{NAME, f.length()});
        } catch (FileNotFoundException ignored) {
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
