package com.suvo.agent.character;

import android.content.Context;

import com.suvo.agent.data.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Characters: two built-ins from assets/characters (Aiko, Haru) plus user characters stored as JSON
 * in files/characters. Editing a built-in saves a user copy.
 */
public final class CharacterRepo {

    public interface Listener {
        void onActiveCharacterChanged(CharacterSpec spec);
    }

    public static final String[] BUILTIN = {"aiko", "haru"};

    private final Context ctx;
    private final File dir;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile CharacterSpec activeCache;

    public CharacterRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(ctx.getFilesDir(), "characters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<CharacterSpec> list() {
        List<CharacterSpec> out = new ArrayList<>();
        for (String id : BUILTIN) {
            CharacterSpec c = load(id);
            if (c != null) out.add(c);
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 5);
                boolean isBuiltinOverride = false;
                for (String b : BUILTIN) if (b.equals(id)) isBuiltinOverride = true;
                if (isBuiltinOverride) continue;
                CharacterSpec c = load(id);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    /** User file wins over the bundled asset for the same id. */
    public CharacterSpec load(String id) {
        File f = new File(dir, id + ".json");
        try {
            String text;
            boolean builtin = false;
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    text = read(in);
                }
            } else {
                try (InputStream in = ctx.getAssets().open("characters/" + id + ".json")) {
                    text = read(in);
                }
                builtin = true;
            }
            CharacterSpec c = CharacterSpec.fromJson(new JSONObject(text));
            c.id = id;
            for (String b : BUILTIN) if (b.equals(id)) c.builtin = true;
            if (builtin) c.builtin = true;
            return c;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public CharacterSpec active() {
        CharacterSpec c = activeCache;
        String want = Settings.get(ctx).str(Settings.ACTIVE_CHARACTER, BUILTIN[0]);
        if (c != null && c.id.equals(want)) return c;
        c = load(want);
        if (c == null) c = load(BUILTIN[0]);
        if (c == null) {
            c = new CharacterSpec();
            c.id = "default";
            c.name = "Friend";
        }
        activeCache = c;
        return c;
    }

    public void setActive(String id) {
        Settings.get(ctx).put(Settings.ACTIVE_CHARACTER, id);
        activeCache = null;
        CharacterSpec c = active();
        for (Listener l : listeners) l.onActiveCharacterChanged(c);
    }

    public CharacterSpec save(CharacterSpec c) throws IOException {
        if (c.id == null || c.id.isEmpty()) c.id = "c_" + UUID.randomUUID().toString().substring(0, 8);
        if (!c.id.matches("[a-z0-9_]{1,40}")) throw new IOException("Invalid character id");
        try (FileOutputStream fos = new FileOutputStream(new File(dir, c.id + ".json"))) {
            fos.write(c.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        activeCache = null;
        CharacterSpec now = active();
        if (now.id.equals(c.id)) for (Listener l : listeners) l.onActiveCharacterChanged(now);
        return c;
    }

    /** Deletes a user character, or resets a built-in to its bundled version. */
    public void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".json").delete();
        activeCache = null;
        boolean builtin = false;
        for (String b : BUILTIN) if (b.equals(id)) builtin = true;
        if (!builtin && Settings.get(ctx).str(Settings.ACTIVE_CHARACTER, BUILTIN[0]).equals(id)) setActive(BUILTIN[0]);
    }

    public CharacterSpec importJson(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            CharacterSpec c = CharacterSpec.fromJson(o);
            c.id = "c_" + UUID.randomUUID().toString().substring(0, 8);
            c.builtin = false;
            if (c.name.trim().isEmpty()) throw new IOException("Character needs a name");
            return save(c);
        } catch (JSONException e) {
            throw new IOException("Not a character file: " + e.getMessage());
        }
    }

    public String exportJson(String id) throws IOException {
        CharacterSpec c = load(id);
        if (c == null) throw new IOException("No character " + id);
        try {
            return c.toJson().toString(2);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
