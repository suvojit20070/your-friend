package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.data.AuditLog;
import com.suvo.agent.data.ConversationRepo;
import com.suvo.agent.data.Settings;
import com.suvo.agent.files.SafStore;
import com.suvo.agent.tools.J;
import com.suvo.agent.voice.HandsFreeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** Real capability status, privacy switches, approval policy, audit history and data controls. */
public final class PermissionsActivity extends BaseActivity {

    private static final int REQ_EXPORT = 61;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_permissions);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);

        r.section(R.string.perm_capabilities);
        r.note(getString(R.string.perm_capabilities_note));
        for (Capability c : Capability.values()) {
            CapabilityState s = g.capabilities.state(c);
            String detail = s == CapabilityState.AVAILABLE ? null : g.capabilities.detail(c);
            r.row(0, getString(PermissionFlows.capabilityName(c)), detail,
                    r.chip(PermissionFlows.stateLabel(this, s), PermissionFlows.stateColor(this, s)),
                    s == CapabilityState.AVAILABLE ? null : v -> PermissionFlows.fix(this, c));
        }

        r.section(R.string.perm_privacy);
        r.switchRow(R.drawable.ic_mic, R.string.privacy_voice_input, 0, Settings.ALLOW_VOICE_INPUT, true, v -> build());
        r.switchRow(R.drawable.ic_voice, R.string.privacy_background_listening, R.string.privacy_background_listening_desc,
                Settings.ALLOW_BACKGROUND_LISTENING, false, v -> {
                    if (!v && HandsFreeService.isRunning()) HandsFreeService.stop(this);
                });
        r.switchRow(R.drawable.ic_camera, R.string.privacy_camera, 0, Settings.ALLOW_CAMERA, true, v -> build());
        r.switchRow(R.drawable.ic_eye, R.string.privacy_screenshot, R.string.privacy_screenshot_desc, Settings.ALLOW_SCREENSHOT, true, v -> build());
        r.switchRow(R.drawable.ic_screen, R.string.privacy_screen_control, R.string.privacy_screen_control_desc, Settings.ALLOW_SCREEN_CONTROL, true, v -> build());
        r.switchRow(R.drawable.ic_terminal, R.string.privacy_shizuku, R.string.privacy_shizuku_desc, Settings.ALLOW_SHIZUKU, true, v -> build());
        r.switchRow(R.drawable.ic_memory, R.string.privacy_auto_memory, R.string.privacy_auto_memory_desc, Settings.AUTO_MEMORY, true, null);
        r.switchRow(R.drawable.ic_agent, R.string.privacy_auto_tools, R.string.privacy_auto_tools_desc, Settings.AUTO_TOOL_EXECUTION, true, null);
        r.switchRow(R.drawable.ic_provider, R.string.privacy_lan, R.string.privacy_lan_desc, Settings.ALLOW_LAN_WEB, false, null);

        r.section(R.string.perm_approval);
        r.choiceRow(R.drawable.ic_shield, R.string.autonomy_mode,
                Settings.AUTONOMY, "CONFIRM",
                new String[]{getString(R.string.autonomy_assist_long), getString(R.string.autonomy_confirm_long), getString(R.string.autonomy_autonomous_long)},
                new String[]{"ASSIST", "CONFIRM", "AUTONOMOUS"}, null);
        r.note(getString(R.string.autonomy_note));
        r.link(R.drawable.ic_tools, R.string.perm_per_tool, R.string.perm_per_tool_desc, ToolsActivity.class);

        r.section(R.string.perm_audit);
        List<AuditLog.Row> audit = g.audit.recent(null, 40);
        if (audit.isEmpty()) r.note(getString(R.string.perm_audit_empty));
        for (AuditLog.Row a : audit) {
            if (!a.phase.equals("SUCCEEDED") && !a.phase.equals("FAILED") && !a.phase.equals("DENIED") && !a.phase.equals("CANCELLED")) continue;
            r.row(0, a.tool + " · " + a.phase.toLowerCase(), DateFormat.getTimeFormat(this).format(new Date(a.time)) + " · " + a.detail, null, null);
        }
        r.button(R.string.perm_clear_audit, false, v -> Ui.confirm(this, R.string.perm_clear_audit, getString(R.string.are_you_sure), () -> {
            g.audit.clear();
            build();
        }));

        r.section(R.string.perm_data);
        r.button(R.string.data_export, true, v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "yourfriend-export.json");
            startActivityForResult(i, REQ_EXPORT);
        });
        r.button(R.string.data_clear_conversations, false, v -> Ui.confirm(this, R.string.data_clear_conversations, getString(R.string.data_clear_conversations_confirm), () -> {
            g.conversations.deleteAll();
            g.agent.newConversation();
            Ui.toast(this, R.string.done);
        }));
        r.button(R.string.data_delete_memories, false, v -> Ui.confirm(this, R.string.data_delete_memories, getString(R.string.data_delete_memories_confirm), () -> {
            g.memory.deleteAll();
            Ui.toast(this, R.string.done);
        }));
        r.button(R.string.data_wipe_workspace, false, v -> Ui.confirm(this, R.string.data_wipe_workspace, getString(R.string.data_wipe_workspace_confirm), () -> {
            g.workspace.wipe();
            Ui.toast(this, R.string.done);
        }));
        r.button(R.string.data_revoke_folders, false, v -> {
            for (SafStore.Grant gr : g.saf.grants()) g.saf.revoke(gr.treeUri);
            Ui.toast(this, R.string.done);
            build();
        });
        Ui.tintAccent(this, box);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) exportTo(data.getData());
    }

    /** Exports settings, memories and conversations. Secrets (API keys, passwords) are never included. */
    private void exportTo(Uri uri) {
        g.io.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                root.put("format", "yourfriend-export/1");
                root.put("exportedAt", System.currentTimeMillis());
                root.put("note", "API keys, SSH credentials and custom-tool secrets are not included.");
                root.put("settings", g.settings.export());
                JSONArray mem = new JSONArray();
                for (MemoryEntry e : g.memory.all(null, 100_000)) mem.put(e.toJson());
                root.put("memories", mem);
                JSONArray convs = new JSONArray();
                for (ConversationRepo.Conversation c : g.conversations.list(null, 100_000)) {
                    JSONArray msgs = new JSONArray();
                    for (ConversationRepo.StoredMessage m : g.conversations.messages(c.id, true)) {
                        JSONObject mo = m.message.toStorageJson();
                        mo.remove("images");
                        mo.put("time", m.createdAt);
                        msgs.put(mo);
                    }
                    convs.put(J.obj("title", c.title, "summary", c.summary, "createdAt", c.createdAt, "messages", msgs));
                }
                root.put("conversations", convs);
                JSONArray providers = new JSONArray();
                for (com.suvo.agent.core.llm.ProviderConfig p : g.providers.list()) providers.put(p.toJson(false));
                root.put("providers", providers);
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new java.io.IOException("Cannot write");
                    os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> Ui.toast(this, R.string.data_exported));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, e.getMessage())));
            }
        });
    }
}
