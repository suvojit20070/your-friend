package com.suvo.agent.launch;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * The home-screen widget, resizable to three shapes: small (the companion — tap to talk), one row
 * (an "Ask …" bar to type, ask about the screen, talk) and large (name, the last answer, new chat
 * and the bar). Colours follow the system (Material You on Android 12+, light/dark). It refreshes
 * when a task finishes.
 */
public final class AssistantWidget extends AppWidgetProvider {

    static final String LAST_REPLY = "widget_last_reply";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) update(ctx, mgr, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle newOptions) {
        update(ctx, mgr, id);
    }

    /** Redraws every placed widget (after a task, after the companion changed). */
    public static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, AssistantWidget.class));
            for (int id : ids) update(ctx, mgr, id);
        } catch (RuntimeException ignored) {
        }
    }

    /** Remembers the last answer for the large widget and redraws. */
    public static void lastReply(Context ctx, String text) {
        String t = text == null ? "" : text.replaceAll("[*_`#>|]+", "").replaceAll("\\s+", " ").trim();
        if (t.length() > 400) t = t.substring(0, 400) + "…";
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString(LAST_REPLY, t).apply();
        refresh(ctx);
    }

    private static void update(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews small = build(ctx, R.layout.widget_small);
        RemoteViews bar = build(ctx, R.layout.widget_bar);
        RemoteViews large = build(ctx, R.layout.widget_large);
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> m = new HashMap<>();
            m.put(new SizeF(50f, 50f), small);
            m.put(new SizeF(180f, 50f), bar);
            m.put(new SizeF(180f, 130f), large);
            mgr.updateAppWidget(id, new RemoteViews(m));
            return;
        }
        Bundle o = mgr.getAppWidgetOptions(id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60);
        mgr.updateAppWidget(id, w < 180 ? small : h >= 130 ? large : bar);
    }

    private static RemoteViews build(Context ctx, int layout) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        String name = ctx.getString(R.string.app_name);
        try {
            String n = AppGraph.get().characters.active().name;
            if (n != null && !n.isEmpty()) name = n;
        } catch (RuntimeException ignored) {
        }
        if (layout == R.layout.widget_small) {
            v.setOnClickPendingIntent(R.id.widget_root, Entry.pending(ctx, 70, Entry.talk(ctx)));
            v.setOnClickPendingIntent(R.id.widget_mic, Entry.pending(ctx, 70, Entry.talk(ctx)));
            return v;
        }
        v.setTextViewText(R.id.widget_hint, ctx.getString(R.string.widget_hint, name));
        v.setOnClickPendingIntent(R.id.widget_bar, Entry.pending(ctx, 71, Entry.type(ctx)));
        v.setOnClickPendingIntent(R.id.widget_mic, Entry.pending(ctx, 72, Entry.talk(ctx)));
        v.setOnClickPendingIntent(R.id.widget_screen, Entry.pending(ctx, 73, Entry.screen(ctx)));
        Intent home = new Intent(ctx, com.suvo.agent.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget_avatar, Entry.pending(ctx, 74, home));
        if (layout == R.layout.widget_large) {
            v.setTextViewText(R.id.widget_name, name);
            String last = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).getString(LAST_REPLY, "");
            if (!last.isEmpty()) v.setTextViewText(R.id.widget_reply, last);
            v.setOnClickPendingIntent(R.id.widget_reply, Entry.pending(ctx, 75,
                    new Intent(ctx, com.suvo.agent.ui.ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            v.setOnClickPendingIntent(R.id.widget_chat, Entry.pending(ctx, 76, Entry.newChat(ctx)));
        }
        return v;
    }
}
