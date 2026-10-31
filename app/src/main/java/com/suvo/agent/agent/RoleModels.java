package com.suvo.agent.agent;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;

/**
 * Your Friend — Original project by Suvo.
 *
 * Which model does the background work. The agent itself uses the main (default) provider; the
 * floating character's brain and the memory work (saving memories, summarising long chats) can
 * use another one from the user's providers — typically a cheaper, faster model — set in
 * Settings → AI. Not set, or that provider is gone / incomplete: the main provider.
 */
public final class RoleModels {

    private RoleModels() {
    }

    /** Settings keys (value = provider id, "" = same as the main provider). */
    public static final String BRAIN = "provider_for_brain", MEMORY = "provider_for_memory";

    public static ProviderConfig provider(AppGraph g, String role) {
        String v = g.settings.str(role, "").trim();
        if (!v.isEmpty()) {
            try {
                ProviderConfig p = g.providers.get(Long.parseLong(v));
                if (p != null && p.validate() == null) return p;
            } catch (RuntimeException ignored) {
                // Not a number any more: fall back.
            }
        }
        return g.providers.active();
    }

    /** A client for {@code role} (usage counted per provider), or null when no provider is set up. */
    public static LlmClient client(AppGraph g, String role) {
        ProviderConfig p = provider(g, role);
        return p == null ? null : TrackedLlm.wrap(g.ctx, new OpenAiCompatClient(p));
    }

    /** {@code role}'s client when the user chose one, else {@code main}. */
    public static LlmClient clientOr(AppGraph g, String role, LlmClient main) {
        if (g.settings.str(role, "").trim().isEmpty()) return main;
        LlmClient c = client(g, role);
        return c == null ? main : c;
    }
}
