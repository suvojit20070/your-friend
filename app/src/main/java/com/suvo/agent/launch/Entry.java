package com.suvo.agent.launch;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.suvo.agent.ui.AssistActivity;

/**
 * Your Friend — Original project by Suvo.
 *
 * Intents for the app's quick entry points (Quick Settings tiles, home-screen widget, launcher
 * shortcuts): the assistant card typing, listening, or asking about the screen right away. They go
 * through the app's own non-exported entry ({@link AssistActivity#INTERNAL}), so "send now" can
 * only come from the app itself, never from another app.
 */
public final class Entry {

    private Entry() {
    }

    public static Intent assist(Context c) {
        return new Intent().setComponent(new ComponentName(c, AssistActivity.INTERNAL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The card, ready to type. */
    public static Intent type(Context c) {
        return assist(c).setAction("com.suvo.agent.TYPE").putExtra(AssistActivity.EXTRA_PROMPT, "");
    }

    /** The card, listening. */
    public static Intent talk(Context c) {
        return assist(c).setAction("com.suvo.agent.TALK").putExtra(AssistActivity.EXTRA_LISTEN, true);
    }

    /** The card asks about what is on the screen right now. */
    public static Intent screen(Context c) {
        return assist(c).setAction("com.suvo.agent.SCREEN")
                .putExtra(AssistActivity.EXTRA_PROMPT, c.getString(com.suvo.agent.R.string.ask_screen_prompt))
                .putExtra(AssistActivity.EXTRA_SEND, true);
    }

    /** A new chat. */
    public static Intent newChat(Context c) {
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("yourfriend://chat?new=1"))
                .setClass(c, com.suvo.agent.ui.ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    public static PendingIntent pending(Context c, int code, Intent i) {
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
