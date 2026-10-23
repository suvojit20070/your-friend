package com.suvo.agent.skills;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import com.suvo.agent.core.skills.Skill;
import com.suvo.agent.core.skills.SkillParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill storage. Built-in skills ship in assets/skills/&lt;id&gt;/SKILL.md; user skills live in
 * files/skills/&lt;id&gt;/. Installing validates the skill; nothing in a skill is ever executed.
 */
public final class SkillRepo {

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;

    public SkillRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(ctx.getFilesDir(), "skills");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.prefs = ctx.getSharedPreferences("skills", Context.MODE_PRIVATE);
    }

    private Set<String> disabled() {
        return new HashSet<>(prefs.getStringSet("disabled", new HashSet<>()));
    }

    public void setEnabled(String id, boolean enabled) {
        Set<String> d = disabled();
        if (enabled) d.remove(id);
        else d.add(id);
        prefs.edit().putStringSet("disabled", d).apply();
    }

    public List<Skill> list() {
        List<Skill> out = new ArrayList<>();
        Set<String> off = disabled();
        AssetManager am = ctx.getAssets();
        try {
            String[] ids = am.list("skills");
            if (ids != null) {
                for (String id : ids) {
                    if (new File(dir, id).exists()) continue; // user override wins
                    try (InputStream in = am.open("skills/" + id + "/SKILL.md")) {
                        Skill s = SkillParser.parse(id, read(in));
                        s.source = "builtin";
                        String[] res = am.list("skills/" + id);
                        if (res != null) for (String r : res) if (!r.equals("SKILL.md")) s.resources.add(r);
                        s.enabled = !off.contains(id);
                        out.add(s);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        File[] user = dir.listFiles();
        if (user != null) {
            for (File d : user) {
                File md = new File(d, "SKILL.md");
                if (!md.isFile()) continue;
                try (InputStream in = new java.io.FileInputStream(md)) {
                    Skill s = SkillParser.parse(d.getName(), read(in));
                    s.source = readSource(d);
                    List<String> res = new ArrayList<>();
                    listResources(d, d, res);
                    s.resources.clear();
                    s.resources.addAll(res);
                    s.enabled = !off.contains(s.id);
                    out.add(s);
                } catch (IOException ignored) {
                }
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void listResources(File root, File cur, List<String> out) {
        File[] kids = cur.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) listResources(root, k, out);
            else if (!k.getName().equals("SKILL.md") && !k.getName().equals(".source")) {
                out.add(k.getAbsolutePath().substring(root.getAbsolutePath().length() + 1));
            }
        }
    }

    private static String readSource(File d) {
        File f = new File(d, ".source");
        if (!f.isFile()) return "local";
        try (InputStream in = new java.io.FileInputStream(f)) {
            return read(in).trim();
        } catch (IOException e) {
            return "local";
        }
    }

    public Skill get(String id) {
        for (Skill s : list()) if (s.id.equals(id)) return s;
        return null;
    }

    public boolean isBuiltin(String id) {
        try {
            String[] ids = ctx.getAssets().list("skills");
            if (ids != null) for (String s : ids) if (s.equals(id)) return true;
        } catch (IOException ignored) {
        }
        return false;
    }

    /** Installs or updates a skill from SKILL.md text. Returns validation problems (empty = installed). */
    public List<String> install(String id, String markdown, String source, Set<String> knownTools) throws IOException {
        if (id == null || id.isEmpty()) id = slug(SkillParser.parse("", markdown).name);
        Skill s = SkillParser.parse(id, markdown);
        List<String> problems = SkillParser.validate(s, knownTools);
        if (!problems.isEmpty()) return problems;
        File d = new File(dir, id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        write(new File(d, "SKILL.md"), markdown.getBytes(StandardCharsets.UTF_8));
        write(new File(d, ".source"), (source == null ? "local" : source).getBytes(StandardCharsets.UTF_8));
        return problems;
    }

    /**
     * Imports a skill ZIP (SKILL.md at the root or in one top folder, plus resources). Entries that
     * would escape the skill folder are rejected; total size is capped at 20 MB.
     */
    public List<String> installZip(InputStream zip, String fallbackId, String source, Set<String> knownTools) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "skill_import_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.contains("..") || name.startsWith("/")) throw new IOException("Unsafe path in archive: " + name);
                File out = new File(tmp, name);
                if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) throw new IOException("Unsafe path: " + name);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        total += n;
                        if (total > 20L * 1024 * 1024) throw new IOException("Skill archive is larger than 20 MB");
                        fos.write(buf, 0, n);
                    }
                }
            }
        }
        File root = new File(tmp, "SKILL.md").isFile() ? tmp : null;
        String id = fallbackId;
        if (root == null) {
            File[] kids = tmp.listFiles();
            if (kids != null && kids.length == 1 && new File(kids[0], "SKILL.md").isFile()) {
                root = kids[0];
                id = kids[0].getName().toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
            }
        }
        if (root == null) {
            deleteRecursive(tmp);
            List<String> p = new ArrayList<>();
            p.add("The archive does not contain SKILL.md");
            return p;
        }
        String md;
        try (InputStream in = new java.io.FileInputStream(new File(root, "SKILL.md"))) {
            md = read(in);
        }
        if (id == null || id.isEmpty()) id = slug(SkillParser.parse("", md).name);
        Skill s = SkillParser.parse(id, md);
        List<String> problems = SkillParser.validate(s, knownTools);
        if (problems.isEmpty()) {
            File d = new File(dir, id);
            deleteRecursive(d);
            if (!root.renameTo(d)) {
                deleteRecursive(tmp);
                throw new IOException("Could not install skill files");
            }
            write(new File(d, ".source"), (source == null ? "import" : source).getBytes(StandardCharsets.UTF_8));
        }
        deleteRecursive(tmp);
        return problems;
    }

    /** Folder id derived from a skill name: lowercase, [a-z0-9._-], at most 48 chars. */
    public static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 48) s = s.substring(0, 48);
        return s.isEmpty() ? "skill-" + Long.toString(System.currentTimeMillis(), 36) : s;
    }

    public boolean remove(String id) {
        File d = new File(dir, id);
        if (!d.exists()) return false;
        deleteRecursive(d);
        return true;
    }

    /** Reads a resource file inside a skill (text, max 100 KB). */
    public String readResource(String id, String resource) throws IOException {
        if (resource.contains("..") || resource.startsWith("/")) throw new IOException("Invalid resource path");
        File d = new File(dir, id);
        if (d.isDirectory()) {
            File f = new File(d, resource);
            if (!f.getCanonicalPath().startsWith(d.getCanonicalPath() + File.separator)) throw new IOException("Invalid resource path");
            try (InputStream in = new java.io.FileInputStream(f)) {
                return limit(read(in));
            }
        }
        try (InputStream in = ctx.getAssets().open("skills/" + id + "/" + resource)) {
            return limit(read(in));
        }
    }

    private static String limit(String s) {
        return s.length() > 100_000 ? s.substring(0, 100_000) + "\n…[truncated]" : s;
    }

    public String indexForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Skill s : list()) if (s.enabled) sb.append(s.indexLine()).append('\n');
        return sb.toString();
    }

    public String exportMarkdown(String id) throws IOException {
        File md = new File(new File(dir, id), "SKILL.md");
        if (md.isFile()) {
            try (InputStream in = new java.io.FileInputStream(md)) {
                return read(in);
            }
        }
        try (InputStream in = ctx.getAssets().open("skills/" + id + "/SKILL.md")) {
            return read(in);
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
