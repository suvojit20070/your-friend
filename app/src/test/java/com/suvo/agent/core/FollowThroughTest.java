package com.suvo.agent.core;

import com.suvo.agent.core.agent.FollowThrough;
import com.suvo.agent.core.agent.TaskState;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FollowThroughTest {

    @Test
    public void announcementsWithoutActionAreCaught() {
        assertTrue(FollowThrough.isEmptyPromise("Sure! I'll create the Android project now."));
        assertTrue(FollowThrough.isEmptyPromise("Thik hai, main abhi bana raha hu."));
        assertTrue(FollowThrough.isEmptyPromise("আচ্ছা, আমি এখনই করছি।"));
        assertTrue(FollowThrough.isEmptyPromise("Let me set up the build environment first."));
        assertTrue(FollowThrough.isEmptyPromise("Ok ami korchi"));
    }

    @Test
    public void questionsAndReportsAreNotCaught() {
        assertFalse(FollowThrough.isEmptyPromise("Which API key should the chat bot use?"));
        assertFalse(FollowThrough.isEmptyPromise("Done! The APK is saved to Download/ChatBot.apk."));
        assertFalse(FollowThrough.isEmptyPromise("App bana diya, Download folder mein hai."));
        assertFalse(FollowThrough.isEmptyPromise("The weather in Kolkata is 31°C and humid."));
        assertFalse(FollowThrough.isEmptyPromise(""));
    }

    @Test
    public void refusalWithoutCheckingIsCaught() {
        TaskState t = new TaskState(1, "make an android chat bot apk");
        t.addStep("file_write", "SUCCEEDED", "Created README.md");
        String reply = "I must be clear: I cannot generate, compile, or sign a final .apk file directly. My environment does not include the build tools.";
        assertTrue(FollowThrough.isUncheckedRefusal(reply, t));
        assertTrue(FollowThrough.isUncheckedRefusal("Main APK nahi bana sakta, mere paas build tools nahi hai.", t));
        t.addStep("shell", "FAILED", "pkg: command not found");
        assertFalse(FollowThrough.isUncheckedRefusal(reply, t)); // it checked; a real failure may be reported
        assertFalse(FollowThrough.isUncheckedRefusal("Here is the APK in Download.", new TaskState(1, "x")));
    }

    @Test
    public void writingLimitsAndOffersAreNotCaught() {
        TaskState t = new TaskState(1, "Write a paragraph in 10k tokens about humans");
        String reply = "I can't write to a token count — tokens aren't something I can measure while writing. Humans are the "
                + "strangest animal we know of. If you want a genuinely huge piece, I can write that properly, with sections. "
                + "Just say how long in words and what angle.";
        assertFalse(FollowThrough.isUncheckedRefusal(reply, t));
        assertFalse(FollowThrough.isEmptyPromise(reply));
        assertFalse(FollowThrough.isEmptyPromise("If you want, I'll make it longer — just tell me."));
    }
}
