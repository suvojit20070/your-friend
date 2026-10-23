package com.suvo.agent.files;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * The agent's private workspace inside app storage. Every path is resolved against the root and
 * canonicalized, so "../" or symlinks cannot escape it. Deletions go to a recoverable trash.
 */
public final class Workspace {

    private final File root;
    private final File trash;

    public Workspace(Context ctx) {
        this(new File(ctx.getFilesDir(), "workspace"));
    }

    public Workspace(File root) {
        this.root = root;
        this.trash = new File(root, ".trash");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        for (String d : new String[]{"downloads", "screenshots", "camera", "code", "notes"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(root, d).mkdirs();
        }
    }

    public File root() {
        return root;
    }

    public File dir(String name) {
        File f = new File(root, name);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** Resolves a workspace-relative path; throws SecurityException if it escapes the workspace. */
    public File resolve(String path) throws IOException {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("workspace:")) p = p.substring("workspace:".length());
        while (p.startsWith("/")) p = p.substring(1);
        File f = p.isEmpty() ? root : new File(root, p);
        String canon = f.getCanonicalPath();
        String rootCanon = root.getCanonicalPath();
        if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
            throw new SecurityException("Path is outside the workspace: " + path);
        }
        return new File(canon);
    }

    /** Path relative to the workspace root, for display and tool results. */
    public String relative(File f) {
        try {
            String rootCanon = root.getCanonicalPath();
            String c = f.getCanonicalPath();
            if (c.equals(rootCanon)) return "/";
            if (c.startsWith(rootCanon + File.separator)) return c.substring(rootCanon.length());
        } catch (IOException ignored) {
        }
        return f.getName();
    }

    /** Moves a file or folder to .trash (recoverable). Returns the trash location. */
    public File moveToTrash(File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        trash.mkdirs();
        File dest = new File(trash, System.currentTimeMillis() + "_" + f.getName());
        if (!f.renameTo(dest)) throw new IOException("Could not move to trash: " + relative(f));
        return dest;
    }

    public boolean isInTrash(File f) throws IOException {
        return f.getCanonicalPath().startsWith(trash.getCanonicalPath());
    }

    public void emptyTrash() {
        deleteRecursive(trash);
        //noinspection ResultOfMethodCallIgnored
        trash.mkdirs();
    }

    public long sizeBytes() {
        return size(root);
    }

    public static long size(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += size(k);
        return s;
    }

    public static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Deletes everything in the workspace (Privacy > Delete workspace data). */
    public void wipe() {
        File[] kids = root.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        new Workspace(root); // recreate standard folders
    }
}
