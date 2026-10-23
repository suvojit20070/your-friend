package com.suvo.agent.core.tools;

<<<<<<< HEAD
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Thread-safe registry of built-in and custom tools with enable/disable state. */
public final class ToolRegistry {

    private final Map<String, Tool> byName = new ConcurrentHashMap<>();
    private final Set<String> disabled = new CopyOnWriteArraySet<>();

    public synchronized void register(Tool tool) {
        ToolSpec s = tool.spec();
        Tool existing = byName.get(s.name);
        if (existing != null && existing.spec().builtin && !s.builtin) {
            throw new IllegalArgumentException("A custom tool cannot replace the built-in tool \"" + s.name + "\"");
        }
        byName.put(s.name, tool);
    }

    public synchronized void unregister(String name) {
        Tool t = byName.get(name);
        if (t != null && t.spec().builtin) throw new IllegalArgumentException("Built-in tools cannot be removed; disable them instead");
        byName.remove(name);
    }

    public Tool get(String name) {
        return byName.get(name);
    }

    public Collection<Tool> all() {
        List<Tool> l = new ArrayList<>(byName.values());
        Collections.sort(l, (a, b) -> a.spec().name.compareTo(b.spec().name));
        return l;
    }

    public void setEnabled(String name, boolean enabled) {
        if (enabled) disabled.remove(name);
        else disabled.add(name);
    }

    public boolean isEnabled(String name) {
        return !disabled.contains(name);
    }

    public void setDisabledSet(Collection<String> names) {
        disabled.clear();
        disabled.addAll(names);
    }

    public Set<String> disabledNames() {
        return Collections.unmodifiableSet(disabled);
    }

    /** Why a tool cannot be offered right now, or null if it can. */
    public String unavailableReason(Tool t, CapabilityProvider caps) {
        if (!isEnabled(t.spec().name)) return "disabled by the user";
        for (Capability c : t.spec().requires) {
            CapabilityState st = caps.state(c);
            if (st != CapabilityState.AVAILABLE) return c.name() + ": " + st.name() + " — " + caps.detail(c);
        }
        try {
            return t.unavailableReason();
        } catch (RuntimeException e) {
            return "availability check failed: " + e.getMessage();
        }
    }

    /** Tools that are enabled and actually usable now; only these are offered to the model. */
    public List<Tool> available(CapabilityProvider caps) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : all()) if (unavailableReason(t, caps) == null) out.add(t);
        return out;
    }

    public JSONArray openAiTools(CapabilityProvider caps) {
        JSONArray arr = new JSONArray();
        for (Tool t : available(caps)) arr.put(t.spec().toOpenAiTool());
        return arr;
    }
=======
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Holds every tool (built-in + user skills). The agent asks it for the active set. */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();

    public synchronized void register(Tool t) {
        tools.put(t.name(), t);
        if (!enabled.containsKey(t.name())) enabled.put(t.name(), true);
    }

    public synchronized void unregister(String name) {
        tools.remove(name);
        enabled.remove(name);
    }

    public synchronized void setEnabled(String name, boolean on) {
        enabled.put(name, on);
    }

    public synchronized boolean isEnabled(String name) {
        Boolean b = enabled.get(name);
        return b == null || b;
    }

    public synchronized Tool get(String name) {
        return tools.get(name);
    }

    public synchronized List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** Tools the model should see this turn: enabled AND available (permission granted). */
    public synchronized List<ToolSchema> activeSchemas() {
        List<ToolSchema> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (isEnabled(t.name()) && t.isAvailable()) out.add(t.schema());
        }
        return out;
    }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
