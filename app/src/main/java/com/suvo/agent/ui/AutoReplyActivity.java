package com.suvo.agent.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.agent.RoleModels;
import com.suvo.agent.autoreply.AutoReplyStore;
import com.suvo.agent.autoreply.Drafts;
import com.suvo.agent.autoreply.ReplyListener;
import com.suvo.agent.character.CharacterSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Settings → Auto reply. The agent answers chat notifications (WhatsApp, Telegram, Instagram…)
 * through their inline reply box, as the active character, following the owner's instructions:
 * master switch and notification access, totals, how the agent talks (name, instructions, length,
 * memories, model), the safety blocklist, the watched apps (context key + Auto / Draft / Off each)
 * and the recent activity, where drafts can be sent, edited or discarded.
 */
public final class AutoReplyActivity extends BaseActivity implements AutoReplyStore.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private AutoReplyStore store;
    private boolean dirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.ar_title);
        store = AutoReplyStore.get(this);
        store.addListener(this);
        build();
    }

    @Override
    protected void onDestroy() {
        store.removeListener(this);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onAutoReplyChanged() {
        // Coalesce bursts of log writes into one rebuild.
        if (dirty) return;
        dirty = true;
        main.postDelayed(() -> {
            dirty = false;
            if (!isFinishing()) build();
        }, 400);
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        if (box == null) return;
        box.removeAllViews();
        Rows r = new Rows(this, box);
        Palette pal = Palette.of(this);
        boolean access = AutoReplyStore.hasAccess(this);

        r.note(getString(R.string.ar_intro));
        r.switchRow(R.drawable.ic_chat, R.string.ar_enable, R.string.ar_enable_desc, AutoReplyStore.ENABLED, false, on -> {
            if (on && !AutoReplyStore.hasAccess(this)) openAccess();
            build();
        });
        if (!access) {
            r.row(R.drawable.ic_shield, getString(R.string.ar_access), getString(R.string.ar_access_desc),
                    r.chip(getString(R.string.ar_off), pal.error), v -> openAccess());
        } else if (!ReplyListener.isConnected() && g.settings.bool(AutoReplyStore.ENABLED, false)) {
            r.row(R.drawable.ic_shield, getString(R.string.ar_access), getString(R.string.ar_access_waiting), null, v -> openAccess());
        }
        r.row(R.drawable.ic_history, getString(R.string.ar_totals, store.total("sent"), store.total("draft"), store.total("skipped")),
                getString(R.string.ar_chats_remembered, store.chats()), null, null);

        // ---- How the agent talks
        r.section(R.string.ar_section_talk);
        CharacterSpec spec = g.characters.active();
        r.note(getString(R.string.ar_persona_note, spec == null ? "Your Friend" : spec.name));
        r.textRow(R.drawable.ic_character, R.string.ar_owner, AutoReplyStore.OWNER, "", false, R.string.ar_owner_empty, null);
        r.textRow(R.drawable.ic_edit, R.string.ar_instructions, AutoReplyStore.INSTRUCTIONS, "", true, R.string.ar_instructions_empty, null);
        r.sliderRow(R.drawable.ic_chat, R.string.ar_max_words, AutoReplyStore.MAX_WORDS, 40, 10, 120, "", null);
        r.switchRow(R.drawable.ic_memory, R.string.ar_use_memory, R.string.ar_use_memory_desc, AutoReplyStore.USE_MEMORY, true, null);
        List<com.suvo.agent.core.llm.ProviderConfig> provs = g.providers.list();
        String[] pl = new String[provs.size() + 1], pv = new String[provs.size() + 1];
        pl[0] = getString(R.string.same_as_main_model);
        pv[0] = "";
        for (int i = 0; i < provs.size(); i++) {
            com.suvo.agent.core.llm.ProviderConfig p = provs.get(i);
            pl[i + 1] = p.name + (p.model == null || p.model.isEmpty() ? "" : " · " + p.model);
            pv[i + 1] = String.valueOf(p.id);
        }
        r.choiceRow(R.drawable.ic_provider, R.string.ar_model, RoleModels.REPLY, "", pl, pv, null);

        // ---- Safety
        r.section(R.string.ar_section_safety);
        r.textRow(R.drawable.ic_shield, R.string.ar_blocklist, AutoReplyStore.BLOCKLIST, AutoReplyStore.DEFAULT_BLOCKLIST, true,
                R.string.ar_blocklist_empty, null);
        r.note(getString(R.string.ar_safety_note));

        // ---- Apps
        r.section(R.string.ar_section_apps);
        List<AutoReplyStore.Rule> rules = store.rules();
        if (rules.isEmpty()) r.note(getString(R.string.ar_no_apps));
        for (AutoReplyStore.Rule rule : rules) {
            r.row(R.drawable.ic_chat, rule.label.isEmpty() ? rule.pkg : rule.label,
                    rule.context.isEmpty() ? rule.pkg : rule.context, r.chip(modeLabel(rule.mode), modeColor(pal, rule.mode)), v -> editRule(rule, false));
        }
        r.button(R.string.ar_add_app, false, v -> pickApp());

        // ---- Activity
        r.section(R.string.ar_section_activity);
        List<AutoReplyStore.Entry> logs = store.logs();
        if (logs.isEmpty()) r.note(getString(R.string.ar_no_activity));
        int shown = 0;
        for (AutoReplyStore.Entry e : logs) {
            if (shown++ >= 40) break;
            String title = (e.sender.isEmpty() ? e.chat : e.sender) + " · " + e.app;
            StringBuilder sub = new StringBuilder(cut(e.message, 160));
            if (!e.reply.isEmpty()) sub.append("\n↳ ").append(cut(e.reply, 200));
            sub.append("\n").append(Ui.ago(e.time));
            r.row(0, title, sub, r.chip(statusLabel(e.status), statusColor(pal, e.status)), v -> showEntry(e));
        }
        if (!logs.isEmpty()) {
            r.row(R.drawable.ic_delete, getString(R.string.ar_clear_activity), null, null,
                    v -> Ui.confirm(this, R.string.ar_clear_activity, getString(R.string.ar_clear_activity_confirm), () -> {
                        store.clearLogs();
                        store.resetTotals();
                    }));
        }
        r.row(R.drawable.ic_delete, getString(R.string.ar_forget_chats), getString(R.string.ar_forget_chats_desc), null,
                v -> Ui.confirm(this, R.string.ar_forget_chats, getString(R.string.ar_forget_chats_confirm), store::clearHistory));
    }

    // ------------------------------------------------------------------ notification access

    private void openAccess() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, ReplyListener.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (RuntimeException ignored) {
                Ui.toast(this, R.string.ar_access_desc);
            }
        }
        Ui.toast(this, R.string.ar_access_hint);
    }

    // ------------------------------------------------------------------ apps

    private static final String[][] KNOWN = {
            {"com.whatsapp", "Personal WhatsApp chats with friends, family and acquaintances. Keep it casual. In groups reply only when directly addressed."},
            {"com.whatsapp.w4b", "WhatsApp Business. Senders are customers asking about products or services. Polite, clear, helpful."},
            {"org.telegram.messenger", "Telegram. Mix of personal chats and groups. In groups reply only when directly addressed."},
            {"org.telegram.plus", "Telegram client. Mix of personal chats and groups. In groups reply only when directly addressed."},
            {"org.thunderdog.challegram", "Telegram X. Mix of personal chats and groups. In groups reply only when directly addressed."},
            {"uz.unnarsx.cherrygram", "Telegram client (Cherrygram). Mix of personal chats and groups. In groups reply only when directly addressed."},
            {"tw.nekomimi.nekogram", "Telegram client (Nekogram). Mix of personal chats and groups. In groups reply only when directly addressed."},
            {"com.instagram.android", "Instagram DMs. Casual, friendly, short replies."},
            {"com.facebook.orca", "Facebook Messenger chats. Casual and friendly."},
            {"org.thoughtcrime.securesms", "Signal chats with close contacts. Casual."},
            {"com.google.android.apps.messaging", "SMS / RCS messages. Many are automated (OTP, bank, offers): skip those. Reply only to real people."},
            {"com.discord", "Discord DMs and servers. In servers reply only when mentioned."},
            {"com.snapchat.android", "Snapchat chats. Very casual, short."},
            {"com.linkedin.android", "LinkedIn messages. Professional and polite."},
    };

    private static String knownContext(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k[1];
        return "";
    }

    private static int knownRank(String pkg) {
        for (int i = 0; i < KNOWN.length; i++) if (KNOWN[i][0].equals(pkg)) return i;
        return Integer.MAX_VALUE;
    }

    private void pickApp() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> have = new HashSet<>();
        for (AutoReplyStore.Rule rr : store.rules()) have.add(rr.pkg);
        List<String[]> apps = new ArrayList<>();
        Set<String> seenPkg = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || have.contains(pkg) || !seenPkg.add(pkg)) continue;
            apps.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(apps, (a, b) -> {
            int ra = knownRank(a[0]), rb = knownRank(b[0]);
            if (ra != rb) return Integer.compare(ra, rb);
            return a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT));
        });
        if (apps.isEmpty()) {
            Ui.toast(this, R.string.ar_no_more_apps);
            return;
        }
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[1] + (knownRank(apps.get(i)[0]) != Integer.MAX_VALUE ? "  ·  " + getString(R.string.ar_chat_app) : "");
        GlassSheet.options(this, getString(R.string.ar_add_app), labels, -1, null).setOnPick(which -> {
            AutoReplyStore.Rule rule = new AutoReplyStore.Rule();
            rule.pkg = apps.get(which)[0];
            rule.label = apps.get(which)[1];
            rule.context = knownContext(rule.pkg);
            rule.mode = AutoReplyStore.DRAFT;
            editRule(rule, true);
        }).show();
    }

    private void editRule(AutoReplyStore.Rule rule, boolean isNew) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        caption(f, rule.pkg, 0);
        label(f, R.string.ar_rule_name);
        EditText name = field(f, rule.label, false);
        label(f, R.string.ar_rule_context);
        EditText ctx = field(f, rule.context, true);
        ctx.setHint(R.string.ar_rule_context_hint);
        label(f, R.string.ar_rule_mode);
        final String[] mode = {rule.mode};
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        String[] modes = {AutoReplyStore.AUTO, AutoReplyStore.DRAFT, AutoReplyStore.OFF};
        TextView[] btn = new TextView[3];
        Palette pal = Palette.of(this);
        Runnable paint = () -> {
            for (int i = 0; i < 3; i++) {
                boolean on = modes[i].equals(mode[0]);
                btn[i].setBackground(new TonalDrawable(on ? pal.primary : pal.surfaceHighest, -1));
                btn[i].setTextColor(on ? pal.onPrimary : pal.onSurface);
            }
        };
        for (int i = 0; i < 3; i++) {
            TextView b = new TextView(this, null, 0, R.style.YF_RowTitle);
            b.setText(modeLabel(modes[i]));
            b.setGravity(Gravity.CENTER);
            final int k = i;
            b.setOnClickListener(v -> {
                mode[0] = modes[k];
                paint.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
            lp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), 0, i == 2 ? 0 : Ui.dp(this, 4), 0);
            seg.addView(b, lp);
            btn[i] = b;
        }
        paint.run();
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 4));
        f.addView(seg, sp);
        caption(f, getString(R.string.ar_rule_mode_desc), 4);

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        GlassDialog.Builder b = Ui.dialog(this).setTitle(isNew ? R.string.ar_add_app : R.string.ar_rule_title).setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    rule.label = name.getText().toString().trim();
                    rule.context = ctx.getText().toString().trim();
                    rule.mode = mode[0];
                    store.saveRule(rule);
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!isNew) {
            b.setNeutralButton(R.string.ar_remove, (d, w) -> Ui.confirm(this, R.string.ar_remove,
                    getString(R.string.ar_remove_confirm, rule.label.isEmpty() ? rule.pkg : rule.label), () -> store.removeRule(rule.pkg)));
        }
        Ui.show(b);
    }

    private void label(LinearLayout f, int res) {
        TextView l = new TextView(this, null, 0, R.style.YF_Label);
        l.setText(res);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 2));
        f.addView(l, lp);
    }

    private void caption(LinearLayout f, CharSequence text, int top) {
        TextView t = new TextView(this, null, 0, R.style.YF_Caption);
        t.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 6), Ui.dp(this, top), Ui.dp(this, 6), Ui.dp(this, 2));
        f.addView(t, lp);
    }

    private EditText field(LinearLayout f, String value, boolean multiline) {
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        Ui.glassField(e, Overlay.contentRoot(this));
        e.setText(value);
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setMaxLines(7);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setSingleLine(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
        f.addView(e, lp);
        return e;
    }

    // ------------------------------------------------------------------ activity entries

    private void showEntry(AutoReplyStore.Entry e) {
        StringBuilder m = new StringBuilder();
        m.append(e.app).append(" · ").append(e.chat.isEmpty() ? e.sender : e.chat).append("\n\n");
        m.append(e.sender).append(": ").append(e.message);
        if (!e.reply.isEmpty()) m.append("\n\n↳ ").append(e.reply);
        if (!e.detail.isEmpty()) m.append("\n\n").append(e.detail);
        GlassDialog.Builder b = Ui.dialog(this).setTitle(statusLabel(e.status)).setMessage(m);
        if (AutoReplyStore.S_DRAFT.equals(e.status) && Drafts.isPending(e.id)) {
            b.setPositiveButton(R.string.ar_send, (d, w) -> afterSend(Drafts.send(this, e.id, null)));
            b.setNeutralButton(R.string.ar_edit, (d, w) -> Ui.prompt(this, R.string.ar_edit, e.reply, true,
                    t -> afterSend(Drafts.send(this, e.id, t))));
            b.setNegativeButton(R.string.ar_discard, (d, w) -> Drafts.discard(this, e.id));
        } else {
            if (AutoReplyStore.S_DRAFT.equals(e.status)) b.setMessage(m.append("\n\n").append(getString(R.string.ar_draft_expired)));
            if (!e.reply.isEmpty()) b.setNeutralButton(R.string.copy, (d, w) -> Ui.copy(this, e.reply));
            b.setPositiveButton(android.R.string.ok, null);
        }
        Ui.show(b);
    }

    private void afterSend(String err) {
        if (err == null) Ui.toast(this, R.string.ar_sent_toast);
        else Ui.toast(this, err);
    }

    private String modeLabel(String mode) {
        switch (mode) {
            case AutoReplyStore.AUTO:
                return getString(R.string.ar_auto);
            case AutoReplyStore.OFF:
                return getString(R.string.ar_off);
            default:
                return getString(R.string.ar_draft);
        }
    }

    private static int modeColor(Palette pal, String mode) {
        switch (mode) {
            case AutoReplyStore.AUTO:
                return pal.tertiary;
            case AutoReplyStore.OFF:
                return pal.onSurfaceVariant;
            default:
                return pal.primary;
        }
    }

    private String statusLabel(String s) {
        switch (s) {
            case AutoReplyStore.S_SENT:
                return getString(R.string.ar_st_sent);
            case AutoReplyStore.S_APPROVED:
                return getString(R.string.ar_st_approved);
            case AutoReplyStore.S_DRAFT:
                return getString(R.string.ar_st_draft);
            case AutoReplyStore.S_SKIPPED:
                return getString(R.string.ar_st_skipped);
            case AutoReplyStore.S_IGNORED:
                return getString(R.string.ar_st_ignored);
            case AutoReplyStore.S_BLOCKED:
                return getString(R.string.ar_st_blocked);
            case AutoReplyStore.S_BAD:
                return getString(R.string.ar_st_bad);
            case AutoReplyStore.S_LONG:
                return getString(R.string.ar_st_long);
            case AutoReplyStore.S_NO_PROVIDER:
                return getString(R.string.ar_st_no_provider);
            case AutoReplyStore.S_DISCARDED:
                return getString(R.string.ar_st_discarded);
            default:
                return getString(R.string.ar_st_error);
        }
    }

    private static int statusColor(Palette pal, String s) {
        switch (s) {
            case AutoReplyStore.S_SENT:
            case AutoReplyStore.S_APPROVED:
                return pal.tertiary;
            case AutoReplyStore.S_DRAFT:
                return pal.primary;
            case AutoReplyStore.S_SKIPPED:
            case AutoReplyStore.S_IGNORED:
            case AutoReplyStore.S_DISCARDED:
                return pal.onSurfaceVariant;
            default:
                return pal.error;
        }
    }

    private static String cut(String s, int n) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
