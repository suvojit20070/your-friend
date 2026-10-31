package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.autoreply.AutoReplyStore;
import com.suvo.agent.autoreply.Drafts;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Your Friend — Original project by Suvo.
 *
 * auto_reply: lets the agent see and manage the chat auto reply (Settings → Auto reply) —
 * status, recent activity with what came in and what was answered, a chat's saved history,
 * pending drafts (send / discard), switching it on/off and an app's mode. Changes need the
 * user's approval like any other medium-risk action.
 */
public final class AutoReplyTool extends BaseTool {

    public AutoReplyTool(AppGraph g) {
        super(g, ToolSpec.builder("auto_reply")
                .description("Chat auto reply (the agent answers WhatsApp/Telegram/Instagram… notifications for the user; "
                        + "Settings → Auto reply). status: on/off, notification access, apps with their mode, totals. "
                        + "activity: recent messages and what was replied/skipped (use to tell the user who wrote). "
                        + "history: saved lines of one chat (app package + chat name). send_draft / discard_draft: a pending "
                        + "draft by id. enable / disable: the master switch. set_mode: an app's mode AUTO | DRAFT | OFF.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"status\",\"activity\",\"history\",\"send_draft\",\"discard_draft\",\"enable\",\"disable\",\"set_mode\"]},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"default\":20},"
                        + "\"app\":{\"type\":\"string\",\"description\":\"package or app name (set_mode, history)\"},"
                        + "\"chat\":{\"type\":\"string\",\"description\":\"chat / contact name (history)\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"AUTO\",\"DRAFT\",\"OFF\"]},"
                        + "\"id\":{\"type\":\"integer\",\"description\":\"activity entry id of a draft\"},"
                        + "\"text\":{\"type\":\"string\",\"description\":\"send_draft: send this instead of the draft\"}"
                        + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                .risk(RiskLevel.MEDIUM).timeoutMs(20_000).build());
    }

    @Override
    public RiskLevel riskFor(JSONObject args) {
        switch (args.optString("action")) {
            case "status":
            case "activity":
            case "history":
                return RiskLevel.READ_ONLY;
            case "discard_draft":
                return RiskLevel.LOW;
            default:
                return RiskLevel.MEDIUM;
        }
    }

    @Override
    public String describeCall(JSONObject args) {
        String a = args.optString("action");
        switch (a) {
            case "set_mode":
                return "Auto reply: set " + args.optString("app") + " to " + args.optString("mode");
            case "send_draft":
                return "Auto reply: send draft " + args.optLong("id") + (args.optString("text").isEmpty() ? "" : ": " + args.optString("text"));
            default:
                return "Auto reply: " + a;
        }
    }

    private AutoReplyStore.Rule findRule(AutoReplyStore store, String app) {
        String k = app == null ? "" : app.trim();
        for (AutoReplyStore.Rule r : store.rules()) if (r.pkg.equalsIgnoreCase(k) || r.label.equalsIgnoreCase(k)) return r;
        for (AutoReplyStore.Rule r : store.rules()) if (!k.isEmpty() && r.label.toLowerCase().contains(k.toLowerCase())) return r;
        return null;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) {
        AutoReplyStore store = AutoReplyStore.get(g.ctx);
        switch (inv.str("action")) {
            case "status": {
                JSONArray apps = new JSONArray();
                for (AutoReplyStore.Rule r : store.rules()) apps.put(J.obj("app", r.label, "package", r.pkg, "mode", r.mode, "context", r.context));
                return ToolResult.ok("Auto reply " + (g.settings.bool(AutoReplyStore.ENABLED, false) ? "on" : "off"),
                        J.obj("enabled", g.settings.bool(AutoReplyStore.ENABLED, false),
                                "notification_access", AutoReplyStore.hasAccess(g.ctx),
                                "apps", apps, "sent", store.total("sent"), "drafts", store.total("draft"), "skipped", store.total("skipped")));
            }
            case "activity": {
                int limit = (int) inv.num("limit", 20);
                JSONArray arr = new JSONArray();
                for (AutoReplyStore.Entry e : store.logs()) {
                    if (arr.length() >= limit) break;
                    JSONObject o = J.obj("id", e.id, "time", e.time, "app", e.app, "from", e.sender, "chat", e.chat,
                            "message", e.message, "status", e.status);
                    if (!e.reply.isEmpty()) J.put(o, "reply", e.reply);
                    if (!e.detail.isEmpty()) J.put(o, "detail", e.detail);
                    if (AutoReplyStore.S_DRAFT.equals(e.status)) J.put(o, "draft_pending", Drafts.isPending(e.id));
                    arr.put(o);
                }
                return ToolResult.ok(arr.length() + " entries", J.obj("activity", arr));
            }
            case "history": {
                AutoReplyStore.Rule r = findRule(store, inv.str("app"));
                String pkg = r == null ? inv.str("app") : r.pkg;
                JSONArray lines = new JSONArray();
                for (String l : store.history(AutoReplyStore.chatKey(pkg, inv.str("chat")))) lines.put(l);
                return ToolResult.ok(lines.length() + " lines", J.obj("app", pkg, "chat", inv.str("chat"), "lines", lines));
            }
            case "send_draft": {
                String err = Drafts.send(g.ctx, inv.num("id", 0), inv.str("text"));
                return err == null ? ToolResult.ok("Draft sent") : ToolResult.fail(err);
            }
            case "discard_draft":
                Drafts.discard(g.ctx, inv.num("id", 0));
                return ToolResult.ok("Draft discarded");
            case "enable":
            case "disable": {
                boolean on = inv.str("action").equals("enable");
                g.settings.put(AutoReplyStore.ENABLED, on);
                String note = on && !AutoReplyStore.hasAccess(g.ctx)
                        ? " Notification access is not allowed yet: ask the user to open Settings → Auto reply and allow it." : "";
                return ToolResult.ok("Auto reply " + (on ? "on." : "off.") + note);
            }
            case "set_mode": {
                AutoReplyStore.Rule r = findRule(store, inv.str("app"));
                if (r == null) return ToolResult.fail("No auto reply rule for " + inv.str("app") + ". The user adds apps in Settings → Auto reply.");
                String mode = inv.str("mode");
                if (!mode.equals(AutoReplyStore.AUTO) && !mode.equals(AutoReplyStore.DRAFT) && !mode.equals(AutoReplyStore.OFF)) {
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "mode must be AUTO, DRAFT or OFF");
                }
                r.mode = mode;
                store.saveRule(r);
                return ToolResult.ok((r.label.isEmpty() ? r.pkg : r.label) + " → " + mode);
            }
            default:
                return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action");
        }
    }
}
