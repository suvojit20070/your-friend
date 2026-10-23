package com.suvo.agent.core.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable description of a tool: identity, schema, permissions, backend, limits. */
public final class ToolSpec {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    public final String id;
    public final String name;
    public final String description;
    public final String version;
    public final JSONObject inputSchema;
    public final JSONObject resultSchema;
    public final Set<Capability> requires;
    public final RiskLevel defaultRisk;
    public final String backend;
    public final long timeoutMs;
    public final boolean cancellable;
    public final boolean builtin;

    private ToolSpec(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.description = b.description;
        this.version = b.version;
        this.inputSchema = b.inputSchema;
        this.resultSchema = b.resultSchema;
        this.requires = Collections.unmodifiableSet(b.requires.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(b.requires));
        this.defaultRisk = b.risk;
        this.backend = b.backend;
        this.timeoutMs = b.timeoutMs;
        this.cancellable = b.cancellable;
        this.builtin = b.builtin;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** OpenAI "tools" entry. */
    public JSONObject toOpenAiTool() {
        try {
            JSONObject fn = new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", inputSchema);
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public JSONObject describe() {
        try {
            org.json.JSONArray caps = new org.json.JSONArray();
            for (Capability c : requires) caps.put(c.name());
            return new JSONObject().put("id", id).put("name", name).put("version", version)
                    .put("description", description).put("parameters", inputSchema)
                    .put("requires", caps).put("risk", defaultRisk.name()).put("backend", backend)
                    .put("timeoutMs", timeoutMs).put("builtin", builtin);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private String id;
        private final String name;
        private String description = "";
        private String version = "1.0";
        private JSONObject inputSchema;
        private JSONObject resultSchema;
        private final Set<Capability> requires = EnumSet.noneOf(Capability.class);
        private RiskLevel risk = RiskLevel.LOW;
        private String backend = "android";
        private long timeoutMs = 60_000;
        private boolean cancellable = true;
        private boolean builtin = true;

        private Builder(String name) {
            this.name = name;
            this.id = "builtin." + name;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder description(String d) {
            this.description = d;
            return this;
        }

        public Builder version(String v) {
            this.version = v;
            return this;
        }

        public Builder schema(JSONObject s) {
            this.inputSchema = s;
            return this;
        }

        public Builder schema(String json) {
            try {
                this.inputSchema = new JSONObject(json);
            } catch (JSONException e) {
                throw new IllegalArgumentException("Invalid schema for " + name + ": " + e.getMessage(), e);
            }
            return this;
        }

        public Builder resultSchema(JSONObject s) {
            this.resultSchema = s;
            return this;
        }

        public Builder requires(Capability... caps) {
            Collections.addAll(requires, caps);
            return this;
        }

        public Builder risk(RiskLevel r) {
            this.risk = r;
            return this;
        }

        public Builder backend(String b) {
            this.backend = b;
            return this;
        }

        public Builder timeoutMs(long t) {
            this.timeoutMs = t;
            return this;
        }

        public Builder cancellable(boolean c) {
            this.cancellable = c;
            return this;
        }

        public Builder builtin(boolean b) {
            this.builtin = b;
            return this;
        }

        public ToolSpec build() {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Tool name must match " + NAME.pattern() + ": " + name);
            }
            if (inputSchema == null) {
                try {
                    inputSchema = new JSONObject().put("type", "object").put("properties", new JSONObject());
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            String err = SchemaValidator.checkSchema(inputSchema);
            if (err != null) throw new IllegalArgumentException("Invalid schema for " + name + ": " + err);
            if (timeoutMs <= 0) throw new IllegalArgumentException("timeout must be > 0");
            return new ToolSpec(this);
        }
    }
}
