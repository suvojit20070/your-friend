package com.suvo.agent.files;

import com.suvo.agent.data.Settings;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Which of the user's folders the agent may use. The user adds folders in Files (through the
 * system picker, or by path with Shizuku) and switches each one on for the agent; only those reach
 * it. A newly added folder starts off. Folders are named by their root path: "ext:Name" for picked
 * folders and "sh:/abs/path" for Shizuku folders.
 */
public final class FolderAccess {

    private static final String AGENT = "agent_folders";
    private static final String SHIZUKU = "shizuku_folders";
    private static final String MIGRATED = "agent_folders_v1";

    private FolderAccess() {
    }

    private static Set<String> read(Settings st, String key) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(st.str(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Settings st, String key, Set<String> v) {
        JSONArray a = new JSONArray();
        for (String s : v) a.put(s);
        st.put(key, a.toString());
    }

    /** Folders picked before this setting existed stay with the agent (it was using them). */
    public static void migrate(Settings st, SafStore saf) {
        if (st.bool(MIGRATED, false)) return;
        Set<String> on = read(st, AGENT);
        for (SafStore.Grant g : saf.grants()) on.add("ext:" + g.name);
        write(st, AGENT, on);
        st.put(MIGRATED, true);
    }

    public static boolean forAgent(Settings st, String root) {
        return read(st, AGENT).contains(root);
    }

    public static void setForAgent(Settings st, String root, boolean on) {
        Set<String> s = read(st, AGENT);
        if (on) s.add(root);
        else s.remove(root);
        write(st, AGENT, s);
    }

    /** Shizuku folders the user added (absolute paths). */
    public static List<String> shizukuFolders(Settings st) {
        return new ArrayList<>(read(st, SHIZUKU));
    }

    public static void addShizukuFolder(Settings st, String abs) {
        Set<String> s = read(st, SHIZUKU);
        s.add(abs);
        write(st, SHIZUKU, s);
    }

    public static void removeShizukuFolder(Settings st, String abs) {
        Set<String> s = read(st, SHIZUKU);
        s.remove(abs);
        write(st, SHIZUKU, s);
        setForAgent(st, ShizukuFs.PREFIX + abs, false);
    }

    /** The picked folders the agent may use. */
    public static List<SafStore.Grant> agentGrants(Settings st, SafStore saf) {
        Set<String> on = read(st, AGENT);
        List<SafStore.Grant> out = new ArrayList<>();
        for (SafStore.Grant g : saf.grants()) if (on.contains("ext:" + g.name)) out.add(g);
        return out;
    }

    /** The Shizuku folders the agent may use ("sh:/abs"). */
    public static List<String> agentShizuku(Settings st) {
        Set<String> on = read(st, AGENT);
        List<String> out = new ArrayList<>();
        for (String abs : read(st, SHIZUKU)) if (on.contains(ShizukuFs.PREFIX + abs)) out.add(ShizukuFs.PREFIX + abs);
        return out;
    }

    /** Every root the agent may use, for its instructions ("ext:Name", "sh:/abs"). */
    public static List<String> agentRoots(Settings st, SafStore saf) {
        List<String> out = new ArrayList<>();
        for (SafStore.Grant g : agentGrants(st, saf)) out.add("ext:" + g.name);
        out.addAll(agentShizuku(st));
        return out;
    }

    /** True when the agent may use this path (inside a folder switched on for it). */
    public static boolean agentMay(Settings st, String path) {
        String p = path.trim();
        if (p.startsWith("ext:")) {
            String rest = p.substring(4);
            while (rest.startsWith("/")) rest = rest.substring(1);
            String name = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            return forAgent(st, "ext:" + name);
        }
        if (ShizukuFs.isShizuku(p)) {
            String real;
            try {
                real = ShizukuFs.real(p);
            } catch (java.io.IOException e) {
                return false;
            }
            for (String root : agentShizuku(st)) {
                String r = root.substring(ShizukuFs.PREFIX.length());
                if (real.equals(r) || real.startsWith(r.endsWith("/") ? r : r + "/")) return true;
            }
            return false;
        }
        return true; // the private workspace
    }
}
