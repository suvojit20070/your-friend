package com.suvo.agent.core.llm;

<<<<<<< HEAD
import com.suvo.agent.core.util.CancellationToken;

/** Abstraction over a chat model, so the agent loop can be tested with a fake. */
public interface LlmClient {

    ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws LlmException;

    /** Provider capabilities used to shape requests (tools, images...). */
    ProviderConfig config();
=======
import androidx.annotation.NonNull;

import com.suvo.agent.core.tools.ToolSchema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OpenAI-compatible chat client. Supports streaming (SSE) and non-streaming,
 * plus tool-calling. Works with any provider that follows the /chat/completions shape.
 *
 * Call {@link #chat} on a background thread.
 */
public class LlmClient {

    public interface StreamSink {
        /** Called as text tokens arrive (streaming only). */
        void onDelta(String delta);
    }

    /** Result of one model turn: final text + any tool calls it requested. */
    public static class Turn {
        public String text = "";
        public final List<ToolCall> toolCalls = new ArrayList<>();
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)      // long: streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public Turn chat(@NonNull LlmConfig cfg,
                     @NonNull List<ChatMessage> messages,
                     List<ToolSchema> tools,
                     StreamSink sink) throws Exception {

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("stream", cfg.stream);
        if (cfg.maxTokens > 0) body.put("max_tokens", cfg.maxTokens);
        body.put("temperature", cfg.temperature);

        JSONArray msgs = new JSONArray();
        for (ChatMessage m : messages) msgs.put(m.toJson());
        body.put("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            JSONArray t = new JSONArray();
            for (ToolSchema s : tools) t.put(s.toJson());
            body.put("tools", t);
            body.put("tool_choice", "auto");
        }

        Request req = new Request.Builder()
                .url(cfg.endpoint())
                .addHeader("Authorization", "Bearer " + (cfg.apiKey == null ? "" : cfg.apiKey))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String err = res.body() != null ? res.body().string() : "";
                throw new RuntimeException("LLM " + res.code() + ": " + clip(err));
            }
            ResponseBody rb = res.body();
            if (rb == null) throw new RuntimeException("Empty LLM response");

            if (cfg.stream) return parseStream(rb, sink);
            return parseOnce(rb.string());
        }
    }

    // ---- streaming (SSE) ----
    private Turn parseStream(ResponseBody rb, StreamSink sink) throws Exception {
        Turn turn = new Turn();
        StringBuilder text = new StringBuilder();
        // tool-call accumulation by index
        List<ToolCall> acc = new ArrayList<>();
        List<StringBuilder> argAcc = new ArrayList<>();

        BufferedReader r = new BufferedReader(
                new InputStreamReader(rb.byteStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) break;
            JSONObject j;
            try { j = new JSONObject(data); } catch (JSONException e) { continue; }

            JSONObject choice = j.optJSONArray("choices") != null && j.getJSONArray("choices").length() > 0
                    ? j.getJSONArray("choices").optJSONObject(0) : null;
            if (choice == null) continue;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) continue;

            String c = delta.optString("content", null);
            if (c != null && !c.isEmpty()) {
                text.append(c);
                if (sink != null) sink.onDelta(c);
            }

            JSONArray tcs = delta.optJSONArray("tool_calls");
            if (tcs != null) {
                for (int i = 0; i < tcs.length(); i++) {
                    JSONObject tc = tcs.getJSONObject(i);
                    int idx = tc.optInt("index", acc.size());
                    while (acc.size() <= idx) { acc.add(new ToolCall()); argAcc.add(new StringBuilder()); }
                    ToolCall call = acc.get(idx);
                    if (tc.has("id")) call.id = tc.optString("id");
                    JSONObject fn = tc.optJSONObject("function");
                    if (fn != null) {
                        if (fn.has("name")) call.name = fn.optString("name");
                        if (fn.has("arguments")) argAcc.get(idx).append(fn.optString("arguments"));
                    }
                }
            }
        }

        for (int i = 0; i < acc.size(); i++) {
            ToolCall call = acc.get(i);
            call.argumentsJson = argAcc.get(i).toString();
            if (call.name != null) turn.toolCalls.add(call);
        }
        turn.text = text.toString();
        return turn;
    }

    // ---- non-streaming ----
    private Turn parseOnce(String raw) throws Exception {
        Turn turn = new Turn();
        JSONObject j = new JSONObject(raw);
        JSONObject msg = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        turn.text = msg.optString("content", "");
        JSONArray tcs = msg.optJSONArray("tool_calls");
        if (tcs != null) {
            for (int i = 0; i < tcs.length(); i++) {
                JSONObject tc = tcs.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                turn.toolCalls.add(new ToolCall(
                        tc.optString("id", "call_" + i),
                        fn.optString("name"),
                        fn.optString("arguments", "{}")));
            }
        }
        return turn;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
