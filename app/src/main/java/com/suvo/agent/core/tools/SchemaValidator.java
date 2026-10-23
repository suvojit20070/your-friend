package com.suvo.agent.core.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates model-produced arguments against the JSON-Schema subset tools use: type, properties,
 * required, enum, const, minimum/maximum, minLength/maxLength, pattern, items, minItems/maxItems,
 * additionalProperties (boolean), default (applied for missing optional properties).
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    /** Validation result. When valid, {@link #value} holds arguments with defaults applied. */
    public static final class Result {
        public final List<String> errors;
        public final JSONObject value;

        Result(List<String> errors, JSONObject value) {
            this.errors = errors;
            this.value = value;
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        public String message() {
            return String.join("; ", errors);
        }
    }

    public static Result validate(JSONObject schema, JSONObject args) {
        List<String> errors = new ArrayList<>();
        JSONObject copy;
        try {
            copy = args == null ? new JSONObject() : new JSONObject(args.toString());
        } catch (JSONException e) {
            copy = new JSONObject();
        }
        Object fixed = check(schema, copy, "$", errors);
        return new Result(errors, fixed instanceof JSONObject ? (JSONObject) fixed : copy);
    }

    private static Object check(JSONObject schema, Object value, String path, List<String> errors) {
        if (schema == null) return value;
        try {
            if (schema.has("const")) {
                Object c = schema.get("const");
                if (!jsonEquals(c, value)) errors.add(path + " must equal " + c);
            }
            if (schema.has("enum")) {
                JSONArray en = schema.getJSONArray("enum");
                boolean found = false;
                for (int i = 0; i < en.length(); i++) if (jsonEquals(en.get(i), value)) found = true;
                if (!found) errors.add(path + " must be one of " + en);
            }
            Object typeObj = schema.opt("type");
            String type = typeObj instanceof String ? (String) typeObj : null;
            if (typeObj instanceof JSONArray) {
                JSONArray ta = (JSONArray) typeObj;
                boolean match = false;
                for (int i = 0; i < ta.length(); i++) if (matchesType(ta.optString(i), value)) match = true;
                if (!match) errors.add(path + " must be of type " + ta);
                return value;
            }
            if (type == null) return value;
            // Lenient coercion of common model mistakes: numbers/booleans sent as strings.
            value = coerce(type, value);
            if (!matchesType(type, value)) {
                errors.add(path + " must be " + type + " (got " + typeName(value) + ")");
                return value;
            }
            switch (type) {
                case "object":
                    return checkObject(schema, (JSONObject) value, path, errors);
                case "array":
                    JSONArray arr = (JSONArray) value;
                    if (schema.has("minItems") && arr.length() < schema.getInt("minItems"))
                        errors.add(path + " must have at least " + schema.getInt("minItems") + " items");
                    if (schema.has("maxItems") && arr.length() > schema.getInt("maxItems"))
                        errors.add(path + " must have at most " + schema.getInt("maxItems") + " items");
                    JSONObject items = schema.optJSONObject("items");
                    if (items != null) {
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < arr.length(); i++) out.put(check(items, arr.get(i), path + "[" + i + "]", errors));
                        return out;
                    }
                    return arr;
                case "string":
                    String s = (String) value;
                    if (schema.has("minLength") && s.length() < schema.getInt("minLength"))
                        errors.add(path + " must have at least " + schema.getInt("minLength") + " characters");
                    if (schema.has("maxLength") && s.length() > schema.getInt("maxLength"))
                        errors.add(path + " must have at most " + schema.getInt("maxLength") + " characters");
                    if (schema.has("pattern")) {
                        try {
                            if (!Pattern.compile(schema.getString("pattern")).matcher(s).find())
                                errors.add(path + " does not match pattern " + schema.getString("pattern"));
                        } catch (PatternSyntaxException ignored) {
                        }
                    }
                    return s;
                case "integer":
                case "number":
                    double d = ((Number) value).doubleValue();
                    if (schema.has("minimum") && d < schema.getDouble("minimum"))
                        errors.add(path + " must be >= " + schema.get("minimum"));
                    if (schema.has("maximum") && d > schema.getDouble("maximum"))
                        errors.add(path + " must be <= " + schema.get("maximum"));
                    if ("integer".equals(type)) return ((Number) value).longValue();
                    return value;
                default:
                    return value;
            }
        } catch (JSONException e) {
            errors.add(path + ": invalid schema (" + e.getMessage() + ")");
            return value;
        }
    }

    private static JSONObject checkObject(JSONObject schema, JSONObject obj, String path, List<String> errors) throws JSONException {
        JSONObject props = schema.optJSONObject("properties");
        JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                String k = required.getString(i);
                if (!obj.has(k) || obj.isNull(k)) errors.add(path + "." + k + " is required");
            }
        }
        Object addl = schema.opt("additionalProperties");
        Iterator<String> it = obj.keys();
        List<String> keys = new ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) {
            JSONObject ps = props == null ? null : props.optJSONObject(k);
            if (ps == null) {
                if (Boolean.FALSE.equals(addl)) errors.add(path + "." + k + " is not an allowed property");
                continue;
            }
            if (obj.isNull(k)) {
                obj.remove(k); // treat explicit null as "not provided"
                continue;
            }
            obj.put(k, check(ps, obj.get(k), path + "." + k, errors));
        }
        if (props != null) {
            Iterator<String> pk = props.keys();
            while (pk.hasNext()) {
                String k = pk.next();
                JSONObject ps = props.optJSONObject(k);
                if (ps != null && !obj.has(k) && ps.has("default")) obj.put(k, ps.get("default"));
            }
        }
        return obj;
    }

    private static Object coerce(String type, Object v) {
        if (v instanceof String) {
            String s = ((String) v).trim();
            try {
                if ("integer".equals(type) && s.matches("-?\\d+")) return Long.parseLong(s);
                if ("number".equals(type) && s.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
            if ("boolean".equals(type)) {
                if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
            }
            if (("object".equals(type) && s.startsWith("{")) || ("array".equals(type) && s.startsWith("["))) {
                try {
                    return new org.json.JSONTokener(s).nextValue();
                } catch (JSONException ignored) {
                }
            }
        }
        if ("integer".equals(type) && v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d)) return (long) d;
        }
        return v;
    }

    static boolean matchesType(String type, Object v) {
        switch (type) {
            case "object":
                return v instanceof JSONObject;
            case "array":
                return v instanceof JSONArray;
            case "string":
                return v instanceof String;
            case "boolean":
                return v instanceof Boolean;
            case "integer":
                return (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                        || v instanceof java.math.BigInteger)
                        || (v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue()));
            case "number":
                return v instanceof Number;
            case "null":
                return v == null || v == JSONObject.NULL;
            default:
                return true;
        }
    }

    private static String typeName(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) return "object";
        if (v instanceof JSONArray) return "array";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        return v.getClass().getSimpleName();
    }

    private static boolean jsonEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        if (a == null) return b == null || b == JSONObject.NULL;
        return a.equals(b) || a.toString().equals(String.valueOf(b));
    }

    /** Sanity check of a tool's own schema at registration time. Returns an error or null. */
    public static String checkSchema(JSONObject schema) {
        if (schema == null) return "schema is null";
        if (!"object".equals(schema.optString("type"))) return "top-level type must be \"object\"";
        Object props = schema.opt("properties");
        if (props != null && !(props instanceof JSONObject)) return "properties must be an object";
        JSONArray req = schema.optJSONArray("required");
        if (req != null) {
            JSONObject p = schema.optJSONObject("properties");
            for (int i = 0; i < req.length(); i++) {
                String k = req.optString(i, "");
                if (p == null || !p.has(k)) return "required property \"" + k + "\" is not declared";
            }
        }
        return null;
    }
}
