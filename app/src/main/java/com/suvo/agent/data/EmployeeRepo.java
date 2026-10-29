package com.suvo.agent.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * The boss agent's team. The user creates each employee (child agent) with a name, a role, its
 * own LLM provider, working instructions and the tools it may use. Stored as JSON in private
 * preferences; no secrets live here (provider keys stay encrypted in the provider table).
 */
public final class EmployeeRepo {

    public static final class Employee {
        public long id = -1;
        public String name = "";
        public String role = "";
        /** -1 = use the boss's provider. */
        public long providerId = -1;
        public String instructions = "";
        /** Empty = every tool except delegation. */
        public Set<String> tools = new LinkedHashSet<>();
        public boolean enabled = true;
        public int maxSteps = 20;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("role", role);
            o.put("provider", providerId);
            o.put("instructions", instructions);
            o.put("tools", new JSONArray(tools));
            o.put("enabled", enabled);
            o.put("max_steps", maxSteps);
            return o;
        }

        static Employee fromJson(JSONObject o) {
            Employee e = new Employee();
            e.id = o.optLong("id", -1);
            e.name = o.optString("name", "");
            e.role = o.optString("role", "");
            e.providerId = o.optLong("provider", -1);
            e.instructions = o.optString("instructions", "");
            JSONArray t = o.optJSONArray("tools");
            if (t != null) for (int i = 0; i < t.length(); i++) e.tools.add(t.optString(i));
            e.enabled = o.optBoolean("enabled", true);
            int ms = o.optInt("max_steps", 20);
            e.maxSteps = ms <= 0 ? 0 : Math.max(3, Math.min(1000, ms)); // 0 = no limit of its own
            return e;
        }

        public String label() {
            return role.isEmpty() ? name : name + " — " + role;
        }
    }

    private static final String KEY = "employees";
    private final SharedPreferences prefs;

    public EmployeeRepo(Context ctx) {
        prefs = ctx.getSharedPreferences("team", Context.MODE_PRIVATE);
        seedBuiltins();
    }

    private static final String SEEDED = "builtin_seeded_v1";

    /**
     * A ready-made team, added once (they use the boss's provider; edit, switch off or delete them
     * in Team like any other employee — a deleted one does not come back).
     */
    private synchronized void seedBuiltins() {
        if (prefs.getBoolean(SEEDED, false)) return;
        List<Employee> all = list();
        long max = 0;
        for (Employee x : all) max = Math.max(max, x.id);
        String[][] team = {
                {"Rhea", "Researcher",
                        "You research on the web. Search with several queries, read the best sources, compare them and answer with "
                                + "the facts that matter plus the links you used. Say clearly what you could not verify.",
                        "py_web_search,py_web_read,py_download_file,memory,file_write,file_read"},
                {"Kai", "Coder",
                        "You write, run and fix code. Work in Termux (shell backend=termux, execute_python / execute_cpp / execute_java). "
                                + "Run what you write, read the errors, fix them and run again until it works. Long builds keep running as "
                                + "background jobs: follow them with termux_job. Report what you changed and how to run it.",
                        "shell,execute_python,execute_cpp,execute_java,termux_job,file_read,file_write,file_edit,file_list,file_search,file_manage,ssh"},
                {"Mira", "Writer",
                        "You write and edit text: messages, posts, emails, summaries, translations, stories. Match the language and "
                                + "tone you are asked for, keep it clear and natural, and give the finished text first.",
                        "memory,file_read,file_write"},
                {"Ozzy", "Phone operator",
                        "You operate the phone: open apps, read the screen, tap, type and scroll until the job is done. Look at the "
                                + "screen after every action, never guess where something is, and stop to report if a step needs the user.",
                        "find_installed_apps,launch_app,open_uri,screen_operation_accessibility,screen_operation_shell,screenshot,view_image,device_status,notify"},
                {"Vera", "Reviewer",
                        "You check work before it is shown to the user: facts, code, plans and texts. Point out concrete mistakes and "
                                + "risks with the fix for each, and say plainly when something is fine.",
                        "py_web_search,py_web_read,file_read,file_list,file_search,shell"},
        };
        for (String[] t : team) {
            boolean exists = false;
            for (Employee x : all) if (x.name.equalsIgnoreCase(t[0])) exists = true;
            if (exists) continue;
            Employee e = new Employee();
            e.id = ++max;
            e.name = t[0];
            e.role = t[1];
            e.instructions = t[2];
            for (String n : t[3].split(",")) e.tools.add(n.trim());
            e.providerId = -1;
            e.enabled = true;
            e.maxSteps = 30;
            all.add(e);
        }
        write(all);
        prefs.edit().putBoolean(SEEDED, true).apply();
    }

    public synchronized List<Employee> list() {
        List<Employee> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Employee.fromJson(o));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public List<Employee> enabled() {
        List<Employee> out = new ArrayList<>();
        for (Employee e : list()) if (e.enabled) out.add(e);
        return out;
    }

    public Employee get(long id) {
        for (Employee e : list()) if (e.id == id) return e;
        return null;
    }

    /** Finds an enabled employee by exact id, then by name (case-insensitive), then by name prefix. */
    public Employee find(String nameOrId) {
        String q = nameOrId == null ? "" : nameOrId.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return null;
        List<Employee> all = enabled();
        for (Employee e : all) if (String.valueOf(e.id).equals(q)) return e;
        for (Employee e : all) if (e.name.toLowerCase(Locale.ROOT).equals(q)) return e;
        for (Employee e : all) if (e.name.toLowerCase(Locale.ROOT).startsWith(q)) return e;
        return null;
    }

    public synchronized void save(Employee e) {
        List<Employee> all = list();
        if (e.id < 0) {
            long max = 0;
            for (Employee x : all) max = Math.max(max, x.id);
            e.id = max + 1;
            all.add(e);
        } else {
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id == e.id) {
                    all.set(i, e);
                    found = true;
                }
            }
            if (!found) all.add(e);
        }
        write(all);
    }

    public synchronized void delete(long id) {
        List<Employee> all = list();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == id) all.remove(i);
        write(all);
    }

    private void write(List<Employee> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Employee x : all) arr.put(x.toJson());
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
