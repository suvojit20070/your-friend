package com.suvo.agent.core;

import com.suvo.agent.core.agent.CompletionCheck;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompletionCheckTest {

    private static LlmClient answering(String text) {
        return new LlmClient() {
            @Override
            public ChatResponse chat(ChatRequest request, com.suvo.agent.core.llm.StreamListener l,
                                     com.suvo.agent.core.util.CancellationToken c) {
                assertTrue(request.messages.get(1).content.contains("web_session"));
                ChatResponse r = new ChatResponse();
                r.content = text;
                return r;
            }

            @Override
            public com.suvo.agent.core.llm.ProviderConfig config() {
                return null;
            }
        };
    }

    @Test
    public void notDoneComesBackWithWhatIsMissing() {
        CompletionCheck cc = new CompletionCheck();
        cc.record("web_session", "{\"action\":\"open\",\"url\":\"https://m.youtube.com/results?search_query=song\"}", "loaded");
        CompletionCheck.Verdict v = cc.check(answering("```json\n{\"done\": false, \"missing\": \"Tap a result and confirm it plays.\"}\n```"),
                "yt pe song play karo", "Done, song is playing", null);
        assertFalse(v.done);
        assertEquals("Tap a result and confirm it plays.", v.missing);
        assertTrue(CompletionCheck.reminder(v.missing).startsWith(CompletionCheck.MARKER));
    }

    @Test
    public void unreadableAnswerNeverBlocks() {
        CompletionCheck cc = new CompletionCheck();
        cc.record("web_session", "{}", "ok");
        assertTrue(cc.check(answering("I think it's fine"), "x", "done", null).done);
        assertTrue(cc.check(answering("{\"done\": true}"), "x", "done", null).done);
    }
}
