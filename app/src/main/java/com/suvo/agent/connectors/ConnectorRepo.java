package com.suvo.agent.connectors;

import android.content.SharedPreferences;

import com.suvo.agent.data.SecretStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * The connected accounts. Kept in the app's private preferences as JSON; every secret (token,
 * refresh token, client secret, password) is encrypted with the app's Keystore key and only
 * decrypted in memory.
 */
public final class ConnectorRepo {

    private static final String KEY = "connectors_v1";

    public interface Listener {
        void onConnectorsChanged();
    }

    private final SharedPreferences prefs;
    private final SecretStore secrets;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public ConnectorRepo(SharedPreferences prefs, SecretStore secrets) {
        this.prefs = prefs;
        this.secrets = secrets;
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Decrypted list, kept until the next change (decrypting on every tool check would be slow). */
    private List<Connector> cache;

    /** Copies: changing one does nothing until it is saved. */
    public synchronized List<Connector> list() {
        if (cache == null) cache = load();
        List<Connector> out = new ArrayList<>();
        for (Connector c : cache) out.add(c.copy());
        return out;
    }

    private List<Connector> load() {
        List<Connector> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Connector c = Connector.fromJson(o);
                c.token = secrets.decrypt(o.optString("s_token"));
                c.refreshToken = secrets.decrypt(o.optString("s_refresh"));
                c.clientSecret = secrets.decrypt(o.optString("s_client"));
                c.password = secrets.decrypt(o.optString("s_password"));
                out.add(c);
            }
        } catch (Exception ignored) {
            // Damaged data: start from an empty list rather than crash.
        }
        return out;
    }

    /** By name (case-insensitive) or id; null when there is none. */
    public Connector find(String nameOrId) {
        if (nameOrId == null) return null;
        String k = nameOrId.trim();
        for (Connector c : list()) {
            if (c.id.equals(k) || c.name.equalsIgnoreCase(k)) return c;
        }
        return null;
    }

    /** A name not used yet, based on {@code base} ("gmail", "gmail-2", …). */
    public String uniqueName(String base, String exceptId) {
        String b = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (b.isEmpty()) b = "account";
        String n = b;
        int i = 2;
        while (true) {
            boolean used = false;
            for (Connector c : list()) {
                if (c.name.equalsIgnoreCase(n) && !c.id.equals(exceptId)) {
                    used = true;
                    break;
                }
            }
            if (!used) return n;
            n = b + "-" + i++;
        }
    }

    public synchronized void save(Connector c) {
        if (c.id == null || c.id.isEmpty()) c.id = UUID.randomUUID().toString().substring(0, 8);
        if (c.createdAt == 0) c.createdAt = System.currentTimeMillis();
        c.name = uniqueName(c.name.isEmpty() ? c.service : c.name, c.id);
        List<Connector> all = list();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(c.id)) {
                all.set(i, c);
                replaced = true;
            }
        }
        if (!replaced) all.add(c);
        write(all);
    }

    public synchronized void delete(String id) {
        List<Connector> all = list();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        write(all);
    }

    private void write(List<Connector> all) {
        JSONArray a = new JSONArray();
        try {
            for (Connector c : all) {
                JSONObject o = c.toJson();
                o.put("s_token", secrets.encrypt(c.token));
                o.put("s_refresh", secrets.encrypt(c.refreshToken));
                o.put("s_client", secrets.encrypt(c.clientSecret));
                o.put("s_password", secrets.encrypt(c.password));
                a.put(o);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY, a.toString()).apply();
        cache = null;
        for (Listener l : listeners) {
            try {
                l.onConnectorsChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** For the system prompt: one line per account, or "" when none is connected. */
    public String summaryForPrompt() {
        List<Connector> all = list();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Accounts the user connected for you (full access, act as the user; tools: "
                + "connectors to list/check, connector_api for APIs, email for mailboxes, web_session for logged-in websites):\n");
        for (Connector c : all) sb.append("- ").append(c.describe()).append('\n');
        return sb.toString().trim();
    }
}
