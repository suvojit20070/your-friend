package com.suvo.agent.core.skills;

import android.content.Context;
import android.util.Log;

import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.data.SecretStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads user skills from:
 *   1. bundled assets/skills/*.json  (shipped examples)
 *   2. filesDir/skills/*.json        (user-added at runtime)
 * and registers each as a {@link SkillTool}.
 */
public class SkillLoader {

    private static final String TAG = "SkillLoader";

    public static void loadAll(Context ctx, ToolRegistry registry, SecretStore secrets) {
        loadAssets(ctx, registry, secrets);
        loadUserDir(ctx, registry, secrets);
    }

    private static void loadAssets(Context ctx, ToolRegistry registry, SecretStore secrets) {
        try {
            String[] names = ctx.getAssets().list("skills");
            if (names == null) return;
            for (String n : names) {
                if (!n.endsWith(".json")) continue;
                try (InputStream in = ctx.getAssets().open("skills/" + n)) {
                    String json = readAll(in);
                    register(ctx, registry, secrets, json, "asset:" + n);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "asset skills: " + e.getMessage());
        }
    }

    private static void loadUserDir(Context ctx, ToolRegistry registry, SecretStore secrets) {
        File dir = new File(ctx.getFilesDir(), "skills");
        if (!dir.exists()) { dir.mkdirs(); return; }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                register(ctx, registry, secrets, sb.toString(), f.getName());
            } catch (Exception e) {
                Log.w(TAG, "user skill " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    private static void register(Context ctx, ToolRegistry registry, SecretStore secrets,
                                 String json, String src) {
        try {
            Skill s = Skill.fromJson(new JSONObject(json));
            registry.register(new SkillTool(ctx, s, secrets));
            Log.i(TAG, "loaded skill '" + s.name + "' from " + src);
        } catch (Exception e) {
            Log.w(TAG, "bad skill " + src + ": " + e.getMessage());
        }
    }

    /** Save a new user skill (from the "Add skill" UI) and register it. */
    public static boolean saveUserSkill(Context ctx, ToolRegistry registry, SecretStore secrets,
                                        String json) {
        try {
            Skill s = Skill.fromJson(new JSONObject(json)); // validate
            File dir = new File(ctx.getFilesDir(), "skills");
            dir.mkdirs();
            File f = new File(dir, safe(s.name) + ".json");
            try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(json); }
            registry.register(new SkillTool(ctx, s, secrets));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "saveUserSkill: " + e.getMessage());
            return false;
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String readAll(InputStream in) throws Exception {
        byte[] buf = new byte[in.available() > 0 ? in.available() : 4096];
        StringBuilder sb = new StringBuilder();
        int n;
        byte[] chunk = new byte[4096];
        while ((n = in.read(chunk)) != -1) sb.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
        return sb.toString();
    }
}
