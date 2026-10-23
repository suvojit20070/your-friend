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
 * Minimal read-only provider that lets the user open/share workspace files with other apps
 * (content://&lt;package&gt;.workspace/&lt;relative path&gt;). Not exported; access is granted per Intent
 * with FLAG_GRANT_READ_URI_PERMISSION. Paths are confined to the workspace.
 */
public final class WorkspaceProvider extends ContentProvider {

    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".workspace";
    }

    /** Content URI for a workspace file. */
    public static Uri uriFor(Context ctx, File f) throws IOException {
        Workspace ws = new Workspace(ctx);
        String rel = ws.relative(f);
        if (rel == null || rel.equals("/") || rel.startsWith("..")) throw new IOException("Not a workspace file");
        Uri.Builder b = new Uri.Builder().scheme("content").authority(authority(ctx));
        for (String seg : rel.split("/")) if (!seg.isEmpty()) b.appendPath(seg);
        return b.build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        StringBuilder rel = new StringBuilder();
        for (String seg : uri.getPathSegments()) {
            if (seg.equals("..") || seg.equals(".") || seg.contains("/")) throw new FileNotFoundException("bad path");
            rel.append('/').append(seg);
        }
        try {
            File f = new Workspace(ctx).resolve(rel.toString());
            if (!f.isFile()) throw new FileNotFoundException(uri.toString());
            return f;
        } catch (IOException | SecurityException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        return last == null ? "application/octet-stream" : FileAccess.mimeFor(last);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && (mode.contains("w") || mode.contains("t"))) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
