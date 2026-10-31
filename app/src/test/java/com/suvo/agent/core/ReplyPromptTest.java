package com.suvo.agent.core;

import com.suvo.agent.core.autoreply.ReplyPrompt;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReplyPromptTest {

    @Test
    public void readsTheReply() {
        ReplyPrompt.Result r = ReplyPrompt.parse("<why>friend asks</why>\n<reply>Haan bhai, kal pakka aa raha hoon</reply>", 40);
        assertEquals(ReplyPrompt.Kind.REPLY, r.kind);
        assertEquals("Haan bhai, kal pakka aa raha hoon", r.text);
        assertEquals("friend asks", r.why);
    }

    @Test
    public void emptyOrSkipWordsAreSkips() {
        assertEquals(ReplyPrompt.Kind.SKIP, ReplyPrompt.parse("<reply></reply>", 40).kind);
        assertEquals(ReplyPrompt.Kind.SKIP, ReplyPrompt.parse("<reply>[S K I P]</reply>", 40).kind);
        assertEquals(ReplyPrompt.Kind.SKIP, ReplyPrompt.parse("<reply>[S_KIP]</reply>", 40).kind);
        assertEquals(ReplyPrompt.Kind.SKIP, ReplyPrompt.parse("<reply>no reply</reply>", 40).kind);
        assertEquals(ReplyPrompt.Kind.SKIP, ReplyPrompt.parse("<reply>(no answer needed)</reply>", 40).kind);
    }

    @Test
    public void failsClosedWithoutTags() {
        assertEquals(ReplyPrompt.Kind.BAD_FORMAT, ReplyPrompt.parse("Sure, here is my reply: hello", 40).kind);
        assertEquals(ReplyPrompt.Kind.BAD_FORMAT, ReplyPrompt.parse(null, 40).kind);
        // A tag only inside the thinking does not count.
        assertEquals(ReplyPrompt.Kind.BAD_FORMAT, ReplyPrompt.parse("<think><reply>x</reply></think> ok", 40).kind);
    }

    @Test
    public void rejectsVeryLongReplies() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("word ");
        assertEquals(ReplyPrompt.Kind.TOO_LONG, ReplyPrompt.parse("<reply>" + sb + "</reply>", 20).kind);
    }

    @Test
    public void stripsQuotes() {
        assertEquals("hello", ReplyPrompt.parse("<reply>\"hello\"</reply>", 40).text);
    }

    @Test
    public void noContentMessages() {
        assertTrue(ReplyPrompt.noContent("Reacted 😅 to your message."));
        assertTrue(ReplyPrompt.noContent("(pov.div): Al Majid sent a message"));
        assertTrue(ReplyPrompt.noContent("Al Majid sent an attachment."));
        assertTrue(ReplyPrompt.noContent("📷 Photo"));
        assertTrue(ReplyPrompt.noContent("Sticker"));
        assertTrue(ReplyPrompt.noContent("3 new messages"));
        assertTrue(ReplyPrompt.noContent("This message was deleted"));
        assertTrue(ReplyPrompt.noContent("  "));
        assertFalse(ReplyPrompt.noContent("Notes dakh 😂"));
        assertFalse(ReplyPrompt.noContent("bhai photo bhej na"));
        assertFalse(ReplyPrompt.noContent("Gn"));
    }

    @Test
    public void blocklistMatchesTextAndNames() {
        String list = "otp, password, cvv, pin, verification code, code:, tanu, pov";
        assertEquals("otp", ReplyPrompt.blocked(list, "Your OTP is 4455", "Bank"));
        assertEquals("pov", ReplyPrompt.blocked(list, "hi", "Pov"));
        assertNull(ReplyPrompt.blocked(list, "kal milte hain", "Rahul"));
        assertNull(ReplyPrompt.blocked("", "otp"));
    }

    @Test
    public void promptCarriesEverything() {
        ReplyPrompt.Input in = new ReplyPrompt.Input();
        in.agentName = "Aiko";
        in.personality = "cheerful";
        in.ownerName = "Suvo";
        in.ownerInstructions = "Hinglish mein baat karo";
        in.appLabel = "WhatsApp";
        in.pkg = "com.whatsapp";
        in.appContext = "Personal chats";
        in.sender = "Rahul";
        in.chat = "Rahul";
        in.latest = Arrays.asList("Rahul: kal aa raha hai na?");
        in.thread = Arrays.asList("Rahul: oye", "Rahul: kal aa raha hai na?");
        in.history = Arrays.asList("Rahul: kaha hai", "Me: ghar pe");
        in.memories = Arrays.asList("Rahul is Suvo's college friend");
        String sys = ReplyPrompt.system(in), user = ReplyPrompt.user(in);
        assertTrue(sys.contains("Aiko") && sys.contains("Suvo") && sys.contains("Hinglish mein baat karo"));
        assertTrue(sys.contains("APP CONTEXT (WhatsApp, com.whatsapp): Personal chats"));
        assertTrue(sys.contains("<reply></reply>"));
        assertTrue(user.contains("FROM: Rahul") && user.contains("(direct)"));
        assertTrue(user.contains("kal aa raha hai na?") && user.contains("Me: ghar pe") && user.contains("college friend"));
    }
}
