package com.suvo.agent.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;

/** JSON building helpers without checked exceptions (keys/values here are always valid). */
public final class J {

    private J() {
    }

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                if (v instanceof Collection) v = new JSONArray((Collection<?>) v);
                o.put(String.valueOf(kv[i]), v == null ? JSONObject.NULL : v);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object o : items) a.put(o);
        return a;
    }

    public static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JSONObject parse(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
