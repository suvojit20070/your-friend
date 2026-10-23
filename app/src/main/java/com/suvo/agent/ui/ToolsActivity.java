package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.skills.Skill;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.data.Settings;
import com.suvo.agent.device.ImageUtil;
import com.suvo.agent.plugins.CustomToolAdapter;
import com.suvo.agent.plugins.CustomToolDef;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tool registry browser: built-in tools, custom tools and SKILL.md skills. */
public final class ToolsActivity extends BaseActivity {

    private static final int REQ_IMPORT_TOOLS = 101;
    private static final int REQ_EXPORT_TOOLS = 102;
    private static final int REQ_IMPORT_SKILL = 103;
    private static final int REQ_EXPORT_SKILL = 104;

    private static final String STATE_TAB = "tab";
    private int tab;
    private String exportSkillId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_tools);
        if (savedInstanceState != null) tab = savedInstanceState.getInt(STATE_TAB);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        int[] labels = {R.string.tools_builtin, R.string.tools_custom, R.string.tools_skills};
        for (int i = 0; i < labels.length; i++) {
            TextView t = new TextView(this, null, 0, R.style.YF_Chip);
            t.setText(labels[i]);
            t.setAlpha(i == tab ? 1f : 0.55f);
            final int idx = i;
            t.setOnClickListener(v -> {
                tab = idx;
                build();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            tabs.addView(t, lp);
        }
        box.addView(tabs);
        Rows r = new Rows(this, box);
        if (tab == 0) builtins(r);
        else if (tab == 1) customs(r);
        else skills(r);
        Ui.tintAccent(this, box);
    }

    // ---- Built-in -------------------------------------------------------------------------------

    private void builtins(Rows r) {
        r.note(getString(R.string.tools_builtin_intro));
        List<Tool> all = new ArrayList<>(g.tools.all());
        all.sort(Comparator.comparing(t -> t.spec().name));
        Set<String> ask = g.settings.set(Settings.ALWAYS_ASK_TOOLS);
        Set<String> blocked = g.settings.set(Settings.BLOCKED_TOOLS);
        for (Tool t : all) {
            ToolSpec s = t.spec();
            if (!s.builtin) continue;
            String reason = g.tools.unavailableReason(t, g.capabilities);
            StringBuilder sub = new StringBuilder(riskLabel(s.defaultRisk));
            if (!g.tools.isEnabled(s.name)) sub.append(" · ").append(getString(R.string.tools_disabled));
            if (blocked.contains(s.name)) sub.append(" · ").append(getString(R.string.tools_blocked));
            else if (ask.contains(s.name)) sub.append(" · ").append(getString(R.string.tools_always_ask));
            sub.append('\n').append(reason == null ? getString(R.string.tools_available) : getString(R.string.tools_unavailable, reason));
            r.row(R.drawable.ic_tools, s.name, sub, null, v -> toolDetails(t));
        }
    }

    private String riskLabel(RiskLevel r) {
        switch (r) {
            case READ_ONLY:
                return getString(R.string.risk_read_only);
            case LOW:
                return getString(R.string.risk_low);
            case MEDIUM:
                return getString(R.string.risk_medium);
            default:
                return getString(R.string.risk_high);
        }
    }

    private void toolDetails(Tool t) {
        ToolSpec s = t.spec();
        StringBuilder msg = new StringBuilder(s.description).append("\n\n");
        msg.append(getString(R.string.tools_risk)).append(": ").append(riskLabel(s.defaultRisk)).append('\n');
        if (!s.requires.isEmpty()) {
            msg.append(getString(R.string.tools_requires)).append(": ");
            List<String> caps = new ArrayList<>();
            for (Capability c : s.requires) caps.add(getString(PermissionFlows.capabilityName(c)));
            msg.append(String.join(", ", caps)).append('\n');
        }
        msg.append(getString(R.string.tools_timeout)).append(": ").append(s.timeoutMs / 1000).append(" s\n\n");
        try {
            msg.append(s.inputSchema.toString(2));
        } catch (Exception ignored) {
        }
        Set<String> disabled = g.settings.set(Settings.DISABLED_TOOLS);
        Set<String> ask = g.settings.set(Settings.ALWAYS_ASK_TOOLS);
        Set<String> blocked = g.settings.set(Settings.BLOCKED_TOOLS);
        String[] items = {
                getString(disabled.contains(s.name) ? R.string.tools_enable : R.string.tools_disable),
                getString(ask.contains(s.name) ? R.string.tools_unset_always_ask : R.string.tools_set_always_ask),
                getString(blocked.contains(s.name) ? R.string.tools_unblock : R.string.tools_block),
        };
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView body = new TextView(this, null, 0, R.style.YF_Mono);
        int p = Ui.dp(this, 20);
        body.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        body.setTextIsSelectable(true);
        body.setText(msg);
        sv.addView(body);
        Ui.show(Ui.dialog(this).setTitle(s.name).setView(sv)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.tools_actions, (d, w) -> Ui.show(Ui.dialog(this).setTitle(s.name).setItems(items, (d2, which) -> {
                    String key = which == 0 ? Settings.DISABLED_TOOLS : which == 1 ? Settings.ALWAYS_ASK_TOOLS : Settings.BLOCKED_TOOLS;
                    Set<String> set = new HashSet<>(g.settings.set(key));
                    if (!set.remove(s.name)) set.add(s.name);
                    g.settings.put(key, set);
                    build();
                }))));
    }

    // ---- Custom ---------------------------------------------------------------------------------

    private void customs(Rows r) {
        r.note(getString(R.string.tools_custom_intro));
        r.button(R.string.tools_custom_new, true, v -> startActivity(new Intent(this, CustomToolEditorActivity.class)));
        List<CustomToolDef> list = g.customTools.list();
        if (list.isEmpty()) r.note(getString(R.string.tools_custom_empty));
        for (CustomToolDef d : list) {
            String err = d.validate();
            StringBuilder sub = new StringBuilder(d.kind).append(" · ").append(riskLabel(CustomToolAdapter.minRisk(d)))
                    .append(" · v").append(d.version);
            if (!d.enabled) sub.append(" · ").append(getString(R.string.tools_disabled));
            if (err != null) sub.append('\n').append(getString(R.string.tools_invalid, err));
            else if (!d.lastError.isEmpty()) sub.append('\n').append(getString(R.string.tools_last_error, d.lastError));
            r.row(R.drawable.ic_tools, d.name, sub, null, v ->
                    startActivity(new Intent(this, CustomToolEditorActivity.class).putExtra(CustomToolEditorActivity.EXTRA_NAME, d.name)));
        }
        r.section(R.string.tools_share);
        r.row(R.drawable.ic_download, getString(R.string.tools_import), getString(R.string.tools_import_desc), null, v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json"), REQ_IMPORT_TOOLS));
        if (!list.isEmpty()) {
            r.row(R.drawable.ic_upload, getString(R.string.tools_export), getString(R.string.tools_export_desc), null, v ->
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "yourfriend-tools.json"), REQ_EXPORT_TOOLS));
        }
    }

    // ---- Skills ---------------------------------------------------------------------------------

    private void skills(Rows r) {
        r.note(getString(R.string.tools_skills_intro));
        Set<String> known = new HashSet<>();
        for (Tool t : g.tools.all()) known.add(t.spec().name);
        for (Skill s : g.skills.list()) {
            StringBuilder sub = new StringBuilder(s.description);
            sub.append('\n').append(g.skills.isBuiltin(s.id) ? getString(R.string.skill_builtin) : s.source);
            if (!s.enabled) sub.append(" · ").append(getString(R.string.tools_disabled));
            List<String> missing = new ArrayList<>();
            for (String t : s.requiredTools) if (!known.contains(t)) missing.add(t);
            if (!missing.isEmpty()) sub.append('\n').append(getString(R.string.skill_missing_tools, String.join(", ", missing)));
            r.row(R.drawable.ic_star, s.name, sub, null, v -> skillDetails(s));
        }
        r.section(R.string.tools_share);
        r.row(R.drawable.ic_download, getString(R.string.skill_import), getString(R.string.skill_import_desc), null, v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/markdown", "text/plain", "application/zip", "application/octet-stream"}),
                        REQ_IMPORT_SKILL));
        r.row(R.drawable.ic_edit, getString(R.string.skill_new), getString(R.string.skill_new_desc), null, v ->
                Ui.prompt(this, R.string.skill_new, SKILL_TEMPLATE, true, md -> {
                    try {
                        List<String> warnings = g.skills.install("", md, "local", known);
                        Ui.toast(this, warnings.isEmpty() ? getString(R.string.saved) : String.join("\n", warnings));
                    } catch (Exception e) {
                        Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
                    }
                    build();
                }));
    }

    static final String SKILL_TEMPLATE = "---\nname: My skill\ndescription: What this skill helps with\nversion: 1.0\n"
            + "triggers: [example]\nrequired_tools: []\n---\n\n# Steps\n\n1. ...\n";

    private void skillDetails(Skill s) {
        TextView body = new TextView(this, null, 0, R.style.YF_Mono);
        int p = Ui.dp(this, 20);
        body.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        body.setTextIsSelectable(true);
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(s.version);
        if (!s.author.isEmpty()) sb.append(" · ").append(s.author);
        if (!s.license.isEmpty()) sb.append(" · ").append(s.license);
        sb.append("\n");
        if (!s.triggers.isEmpty()) sb.append("triggers: ").append(String.join(", ", s.triggers)).append('\n');
        if (!s.requiredTools.isEmpty()) sb.append("tools: ").append(String.join(", ", s.requiredTools)).append('\n');
        if (!s.requiredPermissions.isEmpty()) sb.append("permissions: ").append(String.join(", ", s.requiredPermissions)).append('\n');
        if (!s.resources.isEmpty()) sb.append("resources: ").append(String.join(", ", s.resources)).append('\n');
        sb.append('\n').append(s.instructions);
        body.setText(sb);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(body);
        List<String> items = new ArrayList<>();
        items.add(getString(s.enabled ? R.string.tools_disable : R.string.tools_enable));
        items.add(getString(R.string.skill_export));
        if (!g.skills.isBuiltin(s.id)) items.add(getString(R.string.delete));
        Ui.show(Ui.dialog(this).setTitle(s.name).setView(sv)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.tools_actions, (d, w) -> Ui.show(Ui.dialog(this).setItems(items.toArray(new String[0]), (d2, which) -> {
                    if (which == 0) {
                        g.skills.setEnabled(s.id, !s.enabled);
                    } else if (which == 1) {
                        exportSkillId = s.id;
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("text/markdown").putExtra(Intent.EXTRA_TITLE, s.id + ".SKILL.md"), REQ_EXPORT_SKILL);
                    } else {
                        Ui.confirm(this, R.string.delete, getString(R.string.skill_delete_confirm, s.name), () -> {
                            g.skills.remove(s.id);
                            build();
                        });
                    }
                    build();
                }))));
    }

    // ---- Import / export ------------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            switch (requestCode) {
                case REQ_IMPORT_TOOLS: {
                    String text;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) return;
                        text = new String(ImageUtil.readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8).trim();
                    }
                    JSONArray arr = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text).optJSONArray("tools");
                    if (arr == null) arr = new JSONArray().put(new JSONObject(text));
                    int ok = 0;
                    List<String> errors = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        CustomToolDef d = CustomToolDef.fromJson(arr.getJSONObject(i));
                        // Imported tools start disabled so the user reviews code and permissions first.
                        d.enabled = false;
                        String err = d.validate();
                        if (err != null) {
                            errors.add(d.name + ": " + err);
                            continue;
                        }
                        if (g.tools.get(d.name) != null && g.customTools.get(d.name) == null) {
                            errors.add(d.name + ": " + getString(R.string.tools_name_taken));
                            continue;
                        }
                        CustomToolDef existing = g.customTools.get(d.name);
                        if (existing != null) d.id = existing.id;
                        g.customTools.save(d);
                        ok++;
                    }
                    String msg = getString(R.string.tools_imported, ok);
                    if (!errors.isEmpty()) msg += "\n" + String.join("\n", errors);
                    Ui.show(Ui.dialog(this).setTitle(R.string.tools_import).setMessage(msg).setPositiveButton(R.string.close, null));
                    break;
                }
                case REQ_EXPORT_TOOLS: {
                    JSONArray arr = new JSONArray();
                    for (CustomToolDef d : g.customTools.list()) arr.put(d.toJson(false));
                    JSONObject root = new JSONObject().put("format", "yourfriend-tools").put("version", 1).put("tools", arr);
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        if (os != null) os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    Ui.toast(this, R.string.data_exported);
                    break;
                }
                case REQ_IMPORT_SKILL: {
                    Set<String> known = new HashSet<>();
                    for (Tool t : g.tools.all()) known.add(t.spec().name);
                    String name = String.valueOf(uri.getLastPathSegment());
                    List<String> warnings;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) return;
                        byte[] bytes = ImageUtil.readAll(in, 20 * 1024 * 1024);
                        boolean zip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
                        if (zip) {
                            warnings = g.skills.installZip(new java.io.ByteArrayInputStream(bytes), "", "import:" + name, known);
                        } else {
                            warnings = g.skills.install("", new String(bytes, StandardCharsets.UTF_8), "import:" + name, known);
                        }
                    }
                    String msg = getString(R.string.skill_installed);
                    if (!warnings.isEmpty()) msg += "\n\n" + String.join("\n", warnings);
                    Ui.show(Ui.dialog(this).setTitle(R.string.skill_import).setMessage(msg).setPositiveButton(R.string.close, null));
                    break;
                }
                case REQ_EXPORT_SKILL: {
                    if (exportSkillId == null) return;
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        if (os != null) os.write(g.skills.exportMarkdown(exportSkillId).getBytes(StandardCharsets.UTF_8));
                    }
                    Ui.toast(this, R.string.data_exported);
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
        build();
    }
}
