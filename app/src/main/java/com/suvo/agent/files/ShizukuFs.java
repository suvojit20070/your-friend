package com.suvo.agent.files;

import android.content.Context;

import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.terminal.ExecResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Any folder of shared storage through Shizuku, with no storage permission for the app: paths look
 * like {@code sh:/sdcard/Download/a.txt}. Listing and changes run as Shizuku's shell; file contents
 * travel through the app's own folder in Android/data (the shell may write there and the app may
 * read it), so even big files never pass through a command line.
 */
public final class ShizukuFs {

    public static final String PREFIX = "sh:";

    private final Context ctx;
    private final ShizukuManager sh;

    public ShizukuFs(Context ctx, ShizukuManager sh) {
        this.ctx = ctx.getApplicationContext();
        this.sh = sh;
    }

    public static boolean isShizuku(String path) {
        return path != null && path.trim().startsWith(PREFIX);
    }

    /** "sh:/sdcard/x/" → "/sdcard/x" (absolute, no "..", no trailing slash). */
    public static String real(String path) throws IOException {
        String p = path.trim();
        if (p.startsWith(PREFIX)) p = p.substring(PREFIX.length());
        if (!p.startsWith("/")) throw new IOException("A Shizuku path must be absolute: " + path);
        StringBuilder out = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new IOException("'..' is not allowed in paths");
            out.append('/').append(seg);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    public boolean ready() {
        return sh.state() == ShizukuManager.State.READY;
    }

    private static String q(String s) {
        return ShizukuManager.shellQuote(s);
    }

    private ExecResult run(String cmd, long timeoutMs) throws IOException {
        if (!ready()) throw new IOException("Shizuku is not running or not allowed");
        ExecResult r = sh.run(cmd, null, timeoutMs, null);
        if (r == null) throw new IOException("Shizuku did not answer");
        return r;
    }

    private void check(String cmd, long timeoutMs) throws IOException {
        ExecResult r = run(cmd, timeoutMs);
        if (r.exitCode != 0 || r.timedOut) {
            String err = (r.stderr + " " + r.error).trim();
            throw new IOException(err.isEmpty() ? (r.timedOut ? "Timed out" : "Failed (" + r.exitCode + ")") : err);
        }
    }

    /** One entry of a folder listing. */
    public static final class Item {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long modified;

        Item(String name, boolean dir, long size, long modified) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    /** "%F/%s/%Y/%n": a name can't hold '/', so it is safe as the separator, with the name last. */
    private static Item parse(String line) {
        String[] p = line.split("/", 4);
        if (p.length < 4 || p[3].isEmpty() || p[3].equals(".") || p[3].equals("..")) return null;
        boolean dir = p[0].contains("directory");
        long size = 0, mod = 0;
        try {
            size = Long.parseLong(p[1].trim());
            mod = Long.parseLong(p[2].trim()) * 1000L;
        } catch (NumberFormatException ignored) {
        }
        return new Item(p[3], dir, dir ? 0 : size, mod);
    }

    public List<Item> list(String path) throws IOException {
        String dir = real(path);
        ExecResult r = run("cd " + q(dir) + " || exit 2; for f in * .[!.]* ..?*; do [ -e \"$f\" ] || [ -L \"$f\" ] || continue; "
                + "stat -L -c '%F/%s/%Y/%n' -- \"$f\" 2>/dev/null || stat -c '%F/%s/%Y/%n' -- \"$f\"; done", 60_000);
        if (r.exitCode == 2) throw new FileNotFoundException("No such folder: " + dir);
        List<Item> out = new ArrayList<>();
        for (String line : r.stdout.split("\n")) {
            Item it = parse(line);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** null when it does not exist. */
    public Item stat(String path) throws IOException {
        String p = real(path);
        ExecResult r = run("stat -L -c '%F/%s/%Y/%n' -- " + q(p), 15_000);
        if (r.exitCode != 0) return null;
        Item it = parse(r.stdout.trim().replace(p, p.substring(p.lastIndexOf('/') + 1)));
        if (it == null) return null;
        return it;
    }

    public void mkdirs(String path) throws IOException {
        check("mkdir -p -- " + q(real(path)), 15_000);
    }

    /** Permanent. */
    public void delete(String path) throws IOException {
        String p = real(path);
        if (p.equals("/") || p.equals("/sdcard") || p.equals("/storage/emulated/0") || p.equals("/storage/emulated")) {
            throw new IOException("Refusing to delete " + p);
        }
        check("rm -rf -- " + q(p), 10 * 60_000);
    }

    public void rename(String path, String newName) throws IOException {
        String p = real(path);
        String dest = join(p.substring(0, Math.max(1, p.lastIndexOf('/'))), newName);
        check("[ -e " + q(dest) + " ] && { echo 'A file named " + newName.replace("'", "") + " already exists' >&2; exit 1; }; mv -- " + q(p) + " " + q(dest), 30_000);
    }

    /** Both on shared storage: the shell copies (or moves) directly. */
    public void copy(String from, String to, boolean move) throws IOException {
        String a = real(from), b = real(to);
        if (b.equals(a) || b.startsWith(a + "/")) throw new IOException("Cannot put a folder inside itself");
        check("mkdir -p -- \"$(dirname -- " + q(b) + ")\" && " + (move ? "mv -- " : "cp -r -- ") + q(a) + " " + q(b), 60 * 60_000);
    }

    /** The folder both the app and the shell can use to pass file contents. */
    private File bridge() throws IOException {
        File dir = ctx.getExternalFilesDir("xfer");
        if (dir == null) throw new IOException("Shared storage is not available");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Opens a file for reading (a copy through the bridge folder, removed on close). */
    public InputStream open(String path) throws IOException {
        String p = real(path);
        File tmp = new File(bridge(), "r_" + System.nanoTime());
        check("cat -- " + q(p) + " > " + q(tmp.getAbsolutePath()) + " && chmod 666 " + q(tmp.getAbsolutePath()), 30 * 60_000);
        if (!tmp.isFile()) throw new FileNotFoundException("Could not read " + p);
        return new FilterInputStream(new FileInputStream(tmp)) {
            @Override
            public void close() throws IOException {
                super.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        };
    }

    /** Writes a file from a stream (through the bridge folder). */
    public void write(String path, InputStream in, boolean append) throws IOException {
        String p = real(path);
        File tmp = new File(bridge(), "w_" + System.nanoTime());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            check("mkdir -p -- \"$(dirname -- " + q(p) + ")\" && cat " + q(tmp.getAbsolutePath()) + (append ? " >> " : " > ") + q(p), 30 * 60_000);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
