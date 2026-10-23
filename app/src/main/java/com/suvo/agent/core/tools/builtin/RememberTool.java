package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.memory.MemoryManager;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Let the model explicitly save a durable fact about the user to long-term memory. */
public class RememberTool implements Tool {

    private final MemoryManager memory;

    public RememberTool(MemoryManager memory) { this.memory = memory; }

    @Override public String name() { return "remember"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject props = new JSONObject();
            props.put("fact", new JSONObject().put("type", "string")
                    .put("description", "A durable fact about the user to remember long-term"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("fact"));
            return new ToolSchema(name(),
                    "Save an important, durable fact about the user to long-term memory so you "
                            + "remember it in future conversations.", params, "low");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Remember a fact.", ToolSchema.emptyParams(), "low");
        }
    }

    @Override public boolean isAvailable() { return true; }

    @Override public String run(JSONObject args) {
        String fact = args.optString("fact", "");
        if (fact.isEmpty()) return "Error: fact required.";
        memory.remember(fact);
        return "Saved to memory.";
    }
}
