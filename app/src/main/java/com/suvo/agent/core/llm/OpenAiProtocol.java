package com.suvo.agent.core.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds chat-completions request bodies according to the provider's declared capabilities and
 * parses non-streamed responses and error bodies.
 */
public final class OpenAiProtocol {

    private OpenAiProtocol() {
    }

    public static JSONObject buildBody(ProviderConfig cfg, ChatRequest req, boolean stream) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", cfg.model);
            body.put("messages", buildMessages(cfg, req.messages));
            boolean sendTools = cfg.supportsTools && req.tools != null && req.tools.length() > 0;
            if (sendTools) {
                body.put("tools", req.tools);
                if (req.toolChoice != null) body.put("tool_choice", req.toolChoice);
                if (!cfg.supportsParallelToolCalls) body.put("parallel_tool_calls", false);
            }
            int maxTokens = req.maxTokensOverride > 0 ? req.maxTokensOverride : cfg.maxTokens;
            if (maxTokens > 0) body.put("max_tokens", maxTokens);
            double temp = Double.isNaN(req.temperatureOverride) ? cfg.temperature : req.temperatureOverride;
            if (cfg.sendTemperature || !Double.isNaN(req.temperatureOverride)) body.put("temperature", temp);
            if (cfg.sendTopP) body.put("top_p", cfg.topP);
            if (stream) {
                body.put("stream", true);
                if (cfg.supportsStreamUsage) body.put("stream_options", new JSONObject().put("include_usage", true));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return body;
    }

    /**
     * Converts messages for the wire. Providers without a system role get the system text merged into
     * the first user message. Providers without tool support get tool turns flattened into text so
     * history from an earlier, tool-capable provider is not lost.
     */
    static JSONArray buildMessages(ProviderConfig cfg, List<ChatMessage> messages) {
        List<ChatMessage> list = new ArrayList<>(messages);
        if (!cfg.supportsSystemRole) {
            StringBuilder sys = new StringBuilder();
            List<ChatMessage> rest = new ArrayList<>();
            for (ChatMessage m : list) {
                if (m.role == ChatMessage.Role.SYSTEM) {
                    if (sys.length() > 0) sys.append("\n\n");
                    sys.append(m.contentOrEmpty());
                } else rest.add(m);
            }
            list = rest;
            if (sys.length() > 0) {
                list.add(0, ChatMessage.user("[Instructions]\n" + sys));
                if (list.size() > 1 && list.get(1).role == ChatMessage.Role.USER) {
                    ChatMessage merged = ChatMessage.user(list.get(0).content + "\n\n[User]\n" + list.get(1).contentOrEmpty(), list.get(1).images);
                    list.remove(0);
                    list.set(0, merged);
                }
            }
        }
        if (isGemini(cfg)) list = withGeminiSignatures(list);
        JSONArray arr = new JSONArray();
        for (ChatMessage m : list) {
            if (!cfg.supportsTools && m.role == ChatMessage.Role.TOOL) {
                arr.put(ChatMessage.user("[Tool result " + (m.name == null ? "" : m.name) + "]\n" + m.contentOrEmpty()).toJson(false));
            } else if (!cfg.supportsTools && m.hasToolCalls()) {
                StringBuilder sb = new StringBuilder(m.contentOrEmpty());
                for (ToolCall c : m.toolCalls) sb.append("\n[Called tool ").append(c.name).append(' ').append(c.argumentsJson).append(']');
                arr.put(ChatMessage.assistant(sb.toString().trim()).toJson(false));
            } else {
                arr.put(m.toJson(cfg.supportsVision));
            }
        }
        return arr;
    }

    /**
     * Placeholder Google documents for function calls that Gemini did not produce itself (history
     * from another model, or from before thought signatures were stored). Gemini 3 rejects tool-call
     * history without a signature, so such calls get this value instead of failing the request.
     */
    static final String GEMINI_SKIP_SIGNATURE = "skip_thought_signature_validator";

    static boolean isGemini(ProviderConfig cfg) {
        String url = cfg.baseUrl == null ? "" : cfg.baseUrl.toLowerCase(Locale.ROOT);
        String model = cfg.model == null ? "" : cfg.model.toLowerCase(Locale.ROOT);
        return url.contains("generativelanguage.googleapis.com") || url.contains("aiplatform.googleapis.com")
                || model.startsWith("gemini") || model.contains("/gemini");
    }

    /**
     * In each assistant tool-call turn that carries no thought signature at all, gives the first call
     * the documented placeholder. Real signatures are always sent back unchanged; later calls of a
     * parallel turn stay without one, as Gemini itself sends them.
     */
    static List<ChatMessage> withGeminiSignatures(List<ChatMessage> list) {
        List<ChatMessage> out = new ArrayList<>(list.size());
        for (ChatMessage m : list) {
            if (m.role != ChatMessage.Role.ASSISTANT || !m.hasToolCalls()) {
                out.add(m);
                continue;
            }
            boolean any = false;
            for (ToolCall c : m.toolCalls) if (!c.thoughtSignature().isEmpty()) any = true;
            if (any) {
                out.add(m);
                continue;
            }
            List<ToolCall> calls = new ArrayList<>(m.toolCalls);
            calls.set(0, calls.get(0).withThoughtSignature(GEMINI_SKIP_SIGNATURE));
            out.add(ChatMessage.assistant(m.content, calls));
        }
        return out;
    }

    public static ChatResponse parseResponse(JSONObject o) throws LlmException {
        StreamAccumulator acc = new StreamAccumulator();
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            if (o.has("error")) throw classify(400, o.toString(), 0);
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE, "Response has no choices");
        }
        acc.accept(o, StreamListener.NONE);
        return acc.build();
    }

    /** Maps an HTTP error to a typed exception using status code and common error-message patterns. */
    public static LlmException classify(int status, String body, long retryAfterMs) {
        String msg = extractErrorMessage(body);
        String low = (msg + " " + (body == null ? "" : body)).toLowerCase(Locale.ROOT);
        LlmException.Kind kind;
        if (low.contains("context_length") || low.contains("context length") || low.contains("maximum context")
                || low.contains("too many tokens") || low.contains("prompt is too long") || low.contains("reduce the length")) {
            kind = LlmException.Kind.CONTEXT_LENGTH;
        } else if (status == 401 || status == 403) {
            kind = LlmException.Kind.AUTH;
        } else if (status == 429) {
            kind = LlmException.Kind.RATE_LIMIT;
        } else if (status == 404) {
            kind = LlmException.Kind.NOT_FOUND;
        } else if (status == 408 || status == 504) {
            kind = LlmException.Kind.TIMEOUT;
        } else if (status >= 500) {
            kind = LlmException.Kind.SERVER;
        } else if (status >= 400 && (low.contains("tool") || low.contains("function")) && (low.contains("not support") || low.contains("unsupported"))) {
            kind = LlmException.Kind.UNSUPPORTED;
        } else {
            kind = LlmException.Kind.BAD_REQUEST;
        }
        return new LlmException(kind, msg.isEmpty() ? ("HTTP " + status) : msg, status, retryAfterMs, null);
    }

    static String extractErrorMessage(String body) {
        if (body == null) return "";
        String b = body.trim();
        if (b.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(b);
                Object err = o.opt("error");
                if (err instanceof JSONObject) return ((JSONObject) err).optString("message", err.toString());
                if (err instanceof String) return (String) err;
                if (o.has("message")) return o.optString("message");
                if (o.has("detail")) return o.optString("detail");
            } catch (JSONException ignored) {
            }
        }
        return b.length() > 300 ? b.substring(0, 300) : b;
    }
}
