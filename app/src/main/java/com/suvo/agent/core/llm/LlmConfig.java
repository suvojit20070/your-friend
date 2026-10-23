package com.suvo.agent.core.llm;

/** User-configured OpenAI-compatible provider. Multiple can be saved and switched. */
public class LlmConfig {
    public String name = "default";
    public String baseUrl = "";        // e.g. https://api.provider.ai/v1  (chat/completions is appended)
    public String apiKey = "";
    public String model = "";
    public boolean stream = true;
    public int maxTokens = 0;          // 0 = don't send
    public double temperature = 0.8;

    public LlmConfig() {}

    public LlmConfig(String name, String baseUrl, String apiKey, String model) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String endpoint() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    public boolean isValid() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}
