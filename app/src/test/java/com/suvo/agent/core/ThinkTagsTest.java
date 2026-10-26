package com.suvo.agent.core;

import com.suvo.agent.core.agent.PromptAssembler;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.ThinkTags;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Thinking left inside the answer text is moved out; user prompt parts land after the rules. */
public class ThinkTagsTest {

    @Test
    public void movesThoughtOutOfTheAnswer() {
        ChatResponse r = new ChatResponse();
        r.content = "<thought>The user said Halo.\\nPlan: greet.</thought>Hello! Ki khobor?";
        ThinkTags.apply(r);
        assertEquals("Hello! Ki khobor?", r.content);
        assertTrue(r.reasoning.contains("The user said Halo"));
    }

    @Test
    public void openTagWhileStreamingIsAllThinking() {
        String[] s = ThinkTags.split("<think>still working on it");
        assertEquals("", s[1]);
        assertEquals("still working on it", s[0]);
    }

    @Test
    public void plainTextIsUntouched() {
        String[] s = ThinkTags.split("I think <b>this</b> works");
        assertEquals("", s[0]);
        assertEquals("I think <b>this</b> works", s[1]);
    }

    @Test
    public void userInstructionsComeAfterCoreRules() {
        PromptAssembler pa = new PromptAssembler();
        pa.userInstructions = "Call me Bro.";
        pa.workGuideOverride = "Be quick.";
        String p = pa.build();
        assertTrue(p.startsWith(PromptAssembler.CORE_RULES));
        assertTrue(p.indexOf("Call me Bro.") > p.indexOf("# Character"));
        assertTrue(p.contains("Be quick."));
        assertFalse(p.contains(PromptAssembler.WORK_GUIDE));
    }
}
