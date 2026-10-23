package com.suvo.agent.files;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Folders the user granted through the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
 * The app can only reach these folders; there is no broad filesystem access.
 * Paths look like "ext:&lt;folder name&gt;/sub/file.txt".
 */
public final class SafStore {

    public static final class Grant {
        public final Uri treeUri;
        public final String name;

        Grant(Uri treeUri, String name) {
            this.treeUri = treeUri;
            this.name = name;
        }
    }

    public static final class Doc {
        public final Uri uri;
        public final String name;
        public final String mime;
        public final long size;
        public final long modified;

        Doc(Uri uri, String name, String mime, long size, long modified) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
        }

        public boolean isDir() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    private final Context ctx;
    private final ContentResolver cr;

    public SafStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.cr = this.ctx.getContentResolver();
    }

    /** Persists a tree permission returned by ACTION_OPEN_DOCUMENT_TREE. */
    public void persistGrant(Uri tree) {
        cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    public void revoke(Uri tree) {
        cr.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    public List<Grant> grants() {
        List<Grant> out = new ArrayList<>();
        for (UriPermission p : cr.getPersistedUriPermissions()) {
            if (!p.isReadPermission()) continue;
            Uri tree = p.getUri();
            if (!DocumentsContract.isTreeUri(tree)) continue;
            out.add(new Grant(tree, displayName(tree)));
        }
        return out;
    }

    private String displayName(Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        int colon = docId.lastIndexOf(':');
        String n = colon >= 0 ? docId.substring(colon + 1) : docId;
        return n.isEmpty() ? docId : n.substring(n.lastIndexOf('/') + 1);
    }

    /** Resolves "ext:Name/a/b" to a document. Returns null if any segment is missing. */
    public Doc resolve(String path) throws FileNotFoundException {
        String p = path.substring("ext:".length());
        while (p.startsWith("/")) p = p.substring(1);
        String grantName = p.contains("/") ? p.substring(0, p.indexOf('/')) : p;
        String rest = p.contains("/") ? p.substring(p.indexOf('/') + 1) : "";
        Grant g = null;
        for (Grant gr : grants()) if (gr.name.equals(grantName)) g = gr;
        if (g == null) throw new FileNotFoundException("No granted folder named \"" + grantName + "\". Grant it in Files > Add folder.");
        String docId = DocumentsContract.getTreeDocumentId(g.treeUri);
        Doc cur = query(DocumentsContract.buildDocumentUriUsingTree(g.treeUri, docId));
        if (rest.isEmpty()) return cur;
        for (String seg : rest.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new SecurityException("'..' is not allowed in paths");
            Doc next = null;
            for (Doc d : list(g.treeUri, cur.uri)) {
                if (d.name.equals(seg)) {
                    next = d;
                    break;
                }
            }
            if (next == null) return null;
            cur = next;
        }
        return cur;
    }

    public Uri treeFor(String path) {
        String p = path.substring("ext:".length());
        while (p.startsWith("/")) p = p.substring(1);
        String grantName = p.contains("/") ? p.substring(0, p.indexOf('/')) : p;
        for (Grant g : grants()) if (g.name.equals(grantName)) return g.treeUri;
        return null;
    }

    public List<Doc> list(Uri tree, Uri dirDoc) {
        List<Doc> out = new ArrayList<>();
        String parentId = DocumentsContract.getDocumentId(dirDoc);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                out.add(new Doc(u, c.getString(1), c.getString(2), c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
            }
        }
        return out;
    }

    private Doc query(Uri doc) throws FileNotFoundException {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new FileNotFoundException(doc.toString());
            return new Doc(doc, c.getString(0), c.getString(1), c.isNull(2) ? 0 : c.getLong(2), c.isNull(3) ? 0 : c.getLong(3));
        }
    }

    public InputStream open(Uri doc) throws FileNotFoundException {
        InputStream in = cr.openInputStream(doc);
        if (in == null) throw new FileNotFoundException(doc.toString());
        return in;
    }

    public OutputStream openWrite(Uri doc, boolean append) throws FileNotFoundException {
        OutputStream os = cr.openOutputStream(doc, append ? "wa" : "wt");
        if (os == null) throw new FileNotFoundException(doc.toString());
        return os;
    }

    public Uri createFile(Uri parentDoc, String mime, String name) throws FileNotFoundException {
        Uri u = DocumentsContract.createDocument(cr, parentDoc, mime, name);
        if (u == null) throw new FileNotFoundException("Could not create " + name);
        return u;
    }

    public Uri createDir(Uri parentDoc, String name) throws FileNotFoundException {
        return createFile(parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name);
    }

    public boolean delete(Uri doc) throws FileNotFoundException {
        return DocumentsContract.deleteDocument(cr, doc);
    }

    public Uri rename(Uri doc, String newName) throws FileNotFoundException {
        return DocumentsContract.renameDocument(cr, doc, newName);
    }
}
