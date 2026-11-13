package com.suvo.agent.files;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One file API over three storage areas:
 * <ul>
 *   <li>workspace paths ("notes/todo.md", "/code/main.py", "workspace:/x") — private app storage;</li>
 *   <li>"ext:&lt;Folder&gt;/..." — folders the user granted through the system picker;</li>
 *   <li>"sh:/abs/path" — shared storage through Shizuku ({@link ShizukuFs}).</li>
 * </ul>
 * Made for the agent (the default), it reaches only the folders the user switched on for it
 * ({@link FolderAccess}); the Files screen makes one for the user, which reaches all of them.
 */
public final class FileAccess {

    public static final class Entry {
        public final String path;
        public final String name;
        public final boolean dir;
        public final long size;
        public final long modified;
        public final String mime;

        Entry(String path, String name, boolean dir, long size, long modified, String mime) {
            this.path = path;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
            this.mime = mime;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("path", path).put("name", name).put("type", dir ? "dir" : "file");
                if (!dir) o.put("size", size).put("mime", mime);
                o.put("modified", modified);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return o;
        }
    }

    private final Workspace ws;
    private final SafStore saf;
    /** false: the agent's view (only folders switched on for it). */
    private final boolean forUser;
    private ShizukuFs shz;

    /** The agent's view: only the folders the user switched on for it. */
    public FileAccess(Workspace ws, SafStore saf) {
        this(ws, saf, false);
    }

    public FileAccess(Workspace ws, SafStore saf, boolean forUser) {
        this.ws = ws;
        this.saf = saf;
        this.forUser = forUser;
    }

    /** For the Files screen: every folder the user added. */
    public static FileAccess forUser(Workspace ws, SafStore saf) {
        return new FileAccess(ws, saf, true);
    }

    /** A picked folder ("ext:") or a Shizuku path ("sh:"): outside the workspace, deletions are permanent. */
    public static boolean isExternal(String path) {
        return path != null && (path.trim().startsWith("ext:") || ShizukuFs.isShizuku(path));
    }

    private static boolean isSaf(String path) {
        return path != null && path.trim().startsWith("ext:");
    }

    private ShizukuFs shizuku() {
        if (shz == null) {
            com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
            shz = new ShizukuFs(g.ctx, g.shizuku);
        }
        return shz;
    }

    /** The agent may only touch folders the user switched on for it. */
    private void guard(String path) {
        if (forUser || path == null) return;
        if (!FolderAccess.agentMay(com.suvo.agent.AppGraph.get().settings, path)) {
            throw new SecurityException("The user has not given this folder to the agent: " + path.trim()
                    + ". Folders the agent may use: " + FolderAccess.agentRoots(com.suvo.agent.AppGraph.get().settings, saf));
        }
    }

    private Entry shEntry(String dirPath, ShizukuFs.Item it) {
        String path = ShizukuFs.PREFIX + ShizukuFs.join(dirPath, it.name);
        return new Entry(path, it.name, it.dir, it.size, it.modified, it.dir ? "inode/directory" : mimeFor(it.name));
    }

    public static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        switch (ext) {
            case "md":
            case "txt":
            case "log":
            case "py":
            case "java":
            case "kt":
            case "cpp":
            case "c":
            case "h":
            case "js":
            case "ts":
            case "sh":
            case "yaml":
            case "yml":
            case "toml":
            case "ini":
            case "csv":
                return "text/plain";
            case "json":
                return "application/json";
            default:
                return "application/octet-stream";
        }
    }

    public List<Entry> list(String path) throws IOException {
        guard(path);
        List<Entry> out = new ArrayList<>();
        if (ShizukuFs.isShizuku(path)) {
            String dir = ShizukuFs.real(path);
            for (ShizukuFs.Item it : shizuku().list(path)) out.add(shEntry(dir, it));
        } else if (isSaf(path)) {
            SafStore.Doc d = saf.resolve(path.trim());
            if (d == null) throw new FileNotFoundException(path);
            if (!d.isDir()) throw new IOException("Not a folder: " + path);
            Uri tree = saf.treeFor(path.trim());
            String base = path.trim().endsWith("/") ? path.trim() : path.trim() + "/";
            for (SafStore.Doc c : saf.list(tree, d.uri)) out.add(new Entry(base + c.name, c.name, c.isDir(), c.size, c.modified, c.mime));
        } else {
            File dir = ws.resolve(path);
            if (!dir.exists()) throw new FileNotFoundException("No such folder: " + path);
            if (!dir.isDirectory()) throw new IOException("Not a folder: " + path);
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.getName().equals(".trash") || k.getName().equals(".open")) continue;
                    out.add(new Entry(ws.relative(k), k.getName(), k.isDirectory(), k.length(), k.lastModified(), mimeFor(k.getName())));
                }
            }
        }
        out.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public Entry stat(String path) throws IOException {
        guard(path);
        if (ShizukuFs.isShizuku(path)) {
            String real = ShizukuFs.real(path);
            ShizukuFs.Item it = shizuku().stat(path);
            if (it == null) throw new FileNotFoundException("No such file: " + path);
            return shEntry(real.substring(0, Math.max(1, real.lastIndexOf('/'))), it);
        }
        if (isSaf(path)) {
            SafStore.Doc d = saf.resolve(path.trim());
            if (d == null) throw new FileNotFoundException(path);
            return new Entry(path.trim(), d.name, d.isDir(), d.size, d.modified, d.mime);
        }
        File f = ws.resolve(path);
        if (!f.exists()) throw new FileNotFoundException("No such file: " + path);
        return new Entry(ws.relative(f), f.getName(), f.isDirectory(), f.length(), f.lastModified(), mimeFor(f.getName()));
    }

    public boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    public InputStream open(String path) throws IOException {
        guard(path);
        if (ShizukuFs.isShizuku(path)) return shizuku().open(path);
        if (isSaf(path)) {
            SafStore.Doc d = saf.resolve(path.trim());
            if (d == null || d.isDir()) throw new FileNotFoundException(path);
            return saf.open(d.uri);
        }
        File f = ws.resolve(path);
        if (!f.isFile()) throw new FileNotFoundException("No such file: " + path);
        return new FileInputStream(f);
    }

    public byte[] read(String path, int maxBytes) throws IOException {
        try (InputStream in = open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, Math.min(n, maxBytes - bos.size()));
                if (bos.size() >= maxBytes) break;
            }
            return bos.toByteArray();
        }
    }

    /** Writes (creating parent folders in the workspace). Returns the normalized path. */
    public String write(String path, byte[] data, boolean append) throws IOException {
        return writeStream(path, new java.io.ByteArrayInputStream(data), append);
    }

    /** Writes from a stream (creating parent folders in the workspace). Returns the normalized path. */
    public String writeStream(String path, InputStream data, boolean append) throws IOException {
        guard(path);
        if (ShizukuFs.isShizuku(path)) {
            shizuku().write(path, data, append);
            return ShizukuFs.PREFIX + ShizukuFs.real(path);
        }
        if (isSaf(path)) {
            String p = path.trim();
            SafStore.Doc existing = saf.resolve(p);
            Uri target;
            if (existing != null) {
                if (existing.isDir()) throw new IOException("Is a folder: " + path);
                target = existing.uri;
            } else {
                int slash = p.lastIndexOf('/');
                if (slash < 0) throw new IOException("Path must include a file name");
                SafStore.Doc parent = saf.resolve(p.substring(0, slash));
                if (parent == null || !parent.isDir()) throw new FileNotFoundException("Parent folder does not exist: " + p.substring(0, slash));
                String name = p.substring(slash + 1);
                target = saf.createFile(parent.uri, mimeFor(name), name);
            }
            try (OutputStream os = saf.openWrite(target, append)) {
                pipe(data, os);
            }
            return p;
        }
        File f = ws.resolve(path);
        if (f.isDirectory()) throw new IOException("Is a folder: " + path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create folder for " + path);
        try (FileOutputStream fos = new FileOutputStream(f, append)) {
            pipe(data, fos);
        }
        return ws.relative(f);
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public String mkdirs(String path) throws IOException {
        guard(path);
        if (ShizukuFs.isShizuku(path)) {
            shizuku().mkdirs(path);
            return ShizukuFs.PREFIX + ShizukuFs.real(path);
        }
        if (isSaf(path)) {
            String p = path.trim();
            String prefix = p.contains("/") ? p.substring(0, p.indexOf('/')) : p;
            String rest = p.contains("/") ? p.substring(p.indexOf('/') + 1) : "";
            SafStore.Doc cur = saf.resolve(prefix);
            if (cur == null) throw new FileNotFoundException(prefix);
            Uri tree = saf.treeFor(p);
            String built = prefix;
            for (String seg : rest.split("/")) {
                if (seg.isEmpty()) continue;
                built += "/" + seg;
                SafStore.Doc next = saf.resolve(built);
                if (next == null) {
                    Uri u = saf.createDir(cur.uri, seg);
                    next = saf.resolve(built);
                    if (next == null) throw new IOException("Could not create " + built + " (" + u + ")");
                }
                cur = next;
            }
            return p;
        }
        File f = ws.resolve(path);
        if (!f.exists() && !f.mkdirs()) throw new IOException("Could not create " + path);
        return ws.relative(f);
    }

    /** Workspace deletions go to the recoverable trash; external deletions are permanent. */
    public String delete(String path) throws IOException {
        guard(path);
        if (ShizukuFs.isShizuku(path)) {
            shizuku().delete(path);
            return "deleted permanently";
        }
        if (isSaf(path)) {
            SafStore.Doc d = saf.resolve(path.trim());
            if (d == null) throw new FileNotFoundException(path);
            if (!saf.delete(d.uri)) throw new IOException("The storage provider refused to delete " + path);
            return "deleted permanently";
        }
        File f = ws.resolve(path);
        if (!f.exists()) throw new FileNotFoundException(path);
        if (f.equals(ws.root().getCanonicalFile())) throw new SecurityException("Refusing to delete the whole workspace");
        if (ws.isInTrash(f)) {
            Workspace.deleteRecursive(f);
            return "deleted permanently from trash";
        }
        File t = ws.moveToTrash(f);
        return "moved to trash (" + ws.relative(t) + ")";
    }

    public String rename(String path, String newName) throws IOException {
        if (newName.contains("/") || newName.contains("\\") || newName.equals("..") || newName.equals(".")) {
            throw new IOException("New name must be a plain file name");
        }
        guard(path);
        if (ShizukuFs.isShizuku(path)) {
            shizuku().rename(path, newName);
            String p = ShizukuFs.real(path);
            return ShizukuFs.PREFIX + p.substring(0, p.lastIndexOf('/') + 1) + newName;
        }
        if (isSaf(path)) {
            SafStore.Doc d = saf.resolve(path.trim());
            if (d == null) throw new FileNotFoundException(path);
            Uri u = saf.rename(d.uri, newName);
            if (u == null) throw new IOException("Rename failed");
            String p = path.trim();
            return p.substring(0, p.lastIndexOf('/') + 1) + newName;
        }
        File f = ws.resolve(path);
        if (!f.exists()) throw new FileNotFoundException(path);
        File dest = new File(f.getParentFile(), newName);
        if (dest.exists()) throw new IOException("A file named " + newName + " already exists");
        if (!f.renameTo(dest)) throw new IOException("Rename failed");
        return ws.relative(dest);
    }

    public String copy(String from, String to, boolean overwrite) throws IOException {
        guard(from);
        guard(to);
        if (ShizukuFs.isShizuku(from) && ShizukuFs.isShizuku(to)) {
            if (!overwrite && exists(to)) throw new IOException("Destination exists: " + to + " (set overwrite=true)");
            shizuku().copy(from, to, false);
            return ShizukuFs.PREFIX + ShizukuFs.real(to);
        }
        Entry src = stat(from);
        if (src.dir) throw new IOException("Copying folders is not supported; copy files individually");
        if (!overwrite && exists(to)) throw new IOException("Destination exists: " + to + " (set overwrite=true)");
        try (InputStream in = open(from)) {
            return writeStream(to, in, false);
        }
    }

    public String move(String from, String to, boolean overwrite) throws IOException {
        guard(from);
        guard(to);
        if (ShizukuFs.isShizuku(from) && ShizukuFs.isShizuku(to)) {
            if (!overwrite && exists(to)) throw new IOException("Destination exists: " + to + " (set overwrite=true)");
            shizuku().copy(from, to, true);
            return ShizukuFs.PREFIX + ShizukuFs.real(to);
        }
        if (!isExternal(from) && !isExternal(to)) {
            File a = ws.resolve(from);
            File b = ws.resolve(to);
            if (!a.exists()) throw new FileNotFoundException(from);
            if (b.exists() && !overwrite) throw new IOException("Destination exists: " + to + " (set overwrite=true)");
            File parent = b.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create destination folder");
            if (b.exists()) ws.moveToTrash(b);
            if (!a.renameTo(b)) throw new IOException("Move failed");
            return ws.relative(b);
        }
        String dest = copy(from, to, overwrite);
        remove(from);
        return dest;
    }

    /** Deletes for good (after a move: the data now lives at the destination). */
    public void remove(String path) throws IOException {
        guard(path);
        if (isExternal(path)) {
            delete(path);
            return;
        }
        File f = ws.resolve(path);
        if (!f.exists()) return;
        if (f.equals(ws.root().getCanonicalFile())) throw new SecurityException("Refusing to delete the whole workspace");
        Workspace.deleteRecursive(f);
    }

    /** For copy / move in Files: copies a file or a whole folder; ShizukuFs does folders on its own. */
    public boolean bothShizuku(String a, String b) {
        return ShizukuFs.isShizuku(a) && ShizukuFs.isShizuku(b);
    }

    public void shizukuCopy(String from, String to, boolean move) throws IOException {
        guard(from);
        guard(to);
        shizuku().copy(from, to, move);
    }

    /** Recursive search by name pattern and/or content (text files up to 2 MB). */
    public List<JSONObject> search(String root, String namePattern, String contains, int limit) throws IOException {
        List<JSONObject> out = new ArrayList<>();
        String np = namePattern == null ? "" : namePattern.toLowerCase(Locale.ROOT);
        String needle = contains == null ? "" : contains;
        walk(root, np, needle, out, limit, 0);
        return out;
    }

    private void walk(String dir, String np, String needle, List<JSONObject> out, int limit, int depth) throws IOException {
        if (out.size() >= limit || depth > 12) return;
        for (Entry e : list(dir)) {
            if (out.size() >= limit) return;
            if (e.dir) {
                walk(e.path, np, needle, out, limit, depth + 1);
                continue;
            }
            if (!np.isEmpty() && !globMatch(np, e.name.toLowerCase(Locale.ROOT))) continue;
            if (needle.isEmpty()) {
                out.add(e.toJson());
                continue;
            }
            if (e.size > 2 * 1024 * 1024) continue;
            String text = new String(read(e.path, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length && out.size() < limit; i++) {
                if (lines[i].contains(needle)) {
                    JSONObject m = e.toJson();
                    try {
                        m.put("line", i + 1).put("text", lines[i].length() > 200 ? lines[i].substring(0, 200) : lines[i]);
                    } catch (JSONException ex) {
                        throw new IllegalStateException(ex);
                    }
                    out.add(m);
                }
            }
        }
    }

    static boolean globMatch(String glob, String name) {
        if (!glob.contains("*") && !glob.contains("?")) return name.contains(glob);
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return name.matches(re.toString());
    }

    public static String documentId(Uri u) {
        return DocumentsContract.getDocumentId(u);
    }
}
