package com.suvo.agent.core.tools;

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
}
