package com.suvo.agent.core.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges streamed chat-completion chunks into a {@link ChatResponse}. Handles the variations seen
 * across OpenAI-compatible servers: tool-call deltas keyed by index, tool calls sent whole without an
 * index, ids only on the first delta, and reasoning text in a separate field.
 */
public final class StreamAccumulator {

    private static final class PartialCall {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
        final JSONObject extra = new JSONObject();
        boolean announced;
    }

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PartialCall> calls = new TreeMap<>();
    private String finishReason = "";
    private String model = "";
    private int promptTokens = -1;
    private int completionTokens = -1;
    private int chunks;

    public void accept(JSONObject chunk, StreamListener listener) {
        chunks++;
        if (chunk.has("model")) model = chunk.optString("model", model);
        JSONObject usage = chunk.optJSONObject("usage");
        if (usage != null) {
            promptTokens = usage.optInt("prompt_tokens", promptTokens);
            completionTokens = usage.optInt("completion_tokens", completionTokens);
        }
        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return;
        String fr = choice.optString("finish_reason", "");
        if (!fr.isEmpty() && !"null".equals(fr)) finishReason = fr;
        // Some servers send a full "message" object in the final chunk instead of deltas.
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) delta = choice.optJSONObject("message");
        if (delta == null) return;

        Object c = delta.opt("content");
        if (c instanceof String && !((String) c).isEmpty()) {
            content.append((String) c);
            listener.onTextDelta((String) c);
        }
        String r = delta.optString("reasoning_content", delta.optString("reasoning", ""));
        if (!r.isEmpty() && !"null".equals(r)) reasoning.append(r);

        JSONArray tcs = delta.optJSONArray("tool_calls");
        if (tcs != null) {
            for (int i = 0; i < tcs.length(); i++) {
                JSONObject tc = tcs.optJSONObject(i);
                if (tc == null) continue;
                int index = tc.has("index") ? tc.optInt("index", i) : indexForId(tc.optString("id", ""), i);
                PartialCall pc = calls.get(index);
                if (pc == null) {
                    pc = new PartialCall();
                    calls.put(index, pc);
                }
                String id = tc.optString("id", "");
                if (!id.isEmpty() && !"null".equals(id)) pc.id = id;
                mergeExtra(pc.extra, tc);
                JSONObject fn = tc.optJSONObject("function");
                if (fn != null) {
                    String n = fn.optString("name", "");
                    if (!n.isEmpty() && !"null".equals(n)) {
                        // Names normally arrive once. Some servers repeat the full name in every
                        // delta, a few stream it in fragments.
                        if (pc.name.isEmpty()) pc.name = n;
                        else if (n.equals(pc.name)) { /* repeated full name */ }
                        else if (n.startsWith(pc.name)) pc.name = n;
                        else pc.name = pc.name + n;
                    }
                    Object a = fn.opt("arguments");
                    if (a instanceof String) pc.args.append((String) a);
                    else if (a instanceof JSONObject) pc.args.append(a.toString());
                }
                if (!pc.announced && !pc.name.isEmpty()) {
                    pc.announced = true;
                    listener.onToolCallStarted(index, pc.name);
                }
            }
        }
    }

    /**
     * Keeps provider-specific fields of a tool-call delta (e.g. Gemini's extra_content with the
     * thought signature). Objects are merged key by key; other values replace earlier ones.
     */
    static void mergeExtra(JSONObject into, JSONObject tc) {
        java.util.Iterator<String> keys = tc.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (ToolCall.KNOWN_KEYS.contains(k)) continue;
            Object v = tc.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            try {
                Object old = into.opt(k);
                if (old instanceof JSONObject && v instanceof JSONObject) deepMerge((JSONObject) old, (JSONObject) v);
                else into.put(k, v);
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    private static void deepMerge(JSONObject into, JSONObject from) throws org.json.JSONException {
        java.util.Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = from.get(k);
            Object old = into.opt(k);
            if (old instanceof JSONObject && v instanceof JSONObject) deepMerge((JSONObject) old, (JSONObject) v);
            else into.put(k, v);
        }
    }

    private int indexForId(String id, int fallback) {
        if (!id.isEmpty()) {
            for (Map.Entry<Integer, PartialCall> e : calls.entrySet()) {
                if (id.equals(e.getValue().id)) return e.getKey();
            }
            return calls.size();
        }
        return fallback;
    }

    public int chunkCount() {
        return chunks;
    }

    public ChatResponse build() {
        ChatResponse r = new ChatResponse();
        r.content = content.toString();
        r.reasoning = reasoning.toString();
        r.finishReason = finishReason;
        r.model = model;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        List<ToolCall> out = new ArrayList<>();
        int n = 0;
        for (PartialCall pc : calls.values()) {
            if (pc.name.isEmpty()) continue;
            String id = pc.id.isEmpty() ? "call_" + System.nanoTime() + "_" + n : pc.id;
            out.add(new ToolCall(id, pc.name, pc.args.toString(), pc.extra));
            n++;
        }
        r.toolCalls.addAll(out);
        return r;
    }
}
