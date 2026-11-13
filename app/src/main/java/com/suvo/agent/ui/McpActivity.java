package com.suvo.agent.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.mcp.Mcp;

import org.json.JSONObject;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Settings → MCP servers: add any Model Context Protocol server (URL + optional login header),
 * see and switch its tools, refresh, remove. Its tools reach the agent as mcp_&lt;server&gt;_&lt;tool&gt;.
 */
public final class McpActivity extends BaseActivity {

    public static final String EXTRA_ID = "mcp_id";
    private String serverId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        pullToRefresh(this::build);
        serverId = getIntent().getStringExtra(EXTRA_ID);
        setTopBar(R.string.mcp_title);
        if (serverId == null) topAction(R.drawable.ic_add, R.string.mcp_add, v -> edit(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private Mcp.Server find(String id) {
        for (Mcp.Server s : Mcp.servers(g)) if (s.id.equals(id)) return s;
        return null;
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        Palette pal = Palette.of(this);
        if (serverId != null) {
            Mcp.Server s = find(serverId);
            if (s == null) {
                finish();
                return;
            }
            details(r, s, pal);
            return;
        }
        r.note(getString(R.string.mcp_intro));
        List<Mcp.Server> all = Mcp.servers(g);
        if (all.isEmpty()) r.note(getString(R.string.mcp_empty));
        for (Mcp.Server s : all) {
            String sub = s.url + "\n" + (s.error.isEmpty() ? getResources().getQuantityString(R.plurals.mcp_tools, s.tools.length(), s.tools.length())
                    : getString(R.string.mcp_error, s.error));
            r.row(R.drawable.ic_plug, s.name, sub, r.chip(getString(!s.enabled ? R.string.ar_off : s.error.isEmpty() ? R.string.plugins_ok
                    : R.string.mcp_problem), !s.enabled ? pal.onSurfaceVariant : s.error.isEmpty() ? pal.primary : pal.error),
                    v -> startActivity(new android.content.Intent(this, McpActivity.class).putExtra(EXTRA_ID, s.id)));
        }
        r.button(R.string.mcp_add, true, v -> edit(null));
    }

    private void details(Rows r, Mcp.Server s, Palette pal) {
        r.row(R.drawable.ic_plug, s.name, s.url + (s.serverName.isEmpty() ? "" : "\n" + s.serverName)
                + (s.refreshedAt > 0 ? "\n" + getString(R.string.mcp_refreshed, Ui.ago(s.refreshedAt)) : ""), null, null);
        if (!s.error.isEmpty()) r.note(getString(R.string.mcp_error, s.error));
        com.google.android.material.materialswitch.MaterialSwitch sw = Widgets.newSwitch(this);
        sw.setChecked(s.enabled);
        sw.setOnCheckedChangeListener((b, on) -> {
            s.enabled = on;
            Mcp.save(g, s);
        });
        r.row(R.drawable.ic_power, getString(R.string.mcp_enabled), getString(R.string.mcp_enabled_desc), sw, v -> sw.toggle());
        r.button(R.string.mcp_refresh, true, v -> refresh(s.id));
        r.button(R.string.mcp_edit, false, v -> edit(s));
        r.button(R.string.delete, false, v -> Ui.confirm(this, R.string.delete, getString(R.string.mcp_delete_confirm, s.name), () -> {
            Mcp.delete(g, s.id);
            finish();
        }));
        r.section(getResources().getQuantityString(R.plurals.mcp_tools, s.tools.length(), s.tools.length()));
        if (s.tools.length() == 0) r.note(getString(R.string.mcp_no_tools));
        for (int i = 0; i < s.tools.length(); i++) {
            JSONObject t = s.tools.optJSONObject(i);
            if (t == null) continue;
            String name = t.optString("name");
            String d = t.optString("description").replaceAll("\\s+", " ").trim();
            if (d.length() > 160) d = d.substring(0, 160) + "…";
            com.google.android.material.materialswitch.MaterialSwitch ts = Widgets.newSwitch(this);
            ts.setChecked(!s.off.contains(name));
            ts.setOnCheckedChangeListener((b, on) -> {
                Mcp.Server cur = find(s.id);
                if (cur == null) return;
                cur.off.remove(name);
                if (!on) cur.off.add(name);
                Mcp.save(g, cur);
            });
            r.row(R.drawable.ic_tools, name, Mcp.toolName(s, name) + (d.isEmpty() ? "" : "\n" + d), ts, v -> ts.toggle());
        }
    }

    private void refresh(String id) {
        Ui.toast(this, R.string.mcp_refreshing);
        g.io.execute(() -> {
            String err = Mcp.refresh(g, id);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Ui.toast(this, err == null ? getString(R.string.mcp_refresh_ok) : getString(R.string.mcp_error, err));
                build();
            });
        });
    }

    private EditText field(LinearLayout box, int hint, String value, int type) {
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        Ui.glassField(e, null);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.addView(e, lp);
        return e;
    }

    /** Add or change a server: name, URL, one login header (optional). */
    private void edit(Mcp.Server existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String hName = "", hValue = "";
        if (existing != null && !existing.headers.isEmpty()) {
            java.util.Map.Entry<String, String> h = existing.headers.entrySet().iterator().next();
            hName = h.getKey();
            hValue = h.getValue();
        }
        EditText name = field(box, R.string.mcp_name, existing == null ? "" : existing.name, InputType.TYPE_CLASS_TEXT);
        EditText url = field(box, R.string.mcp_url, existing == null ? "https://" : existing.url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText header = field(box, R.string.mcp_header_name, hName.isEmpty() ? "Authorization" : hName, InputType.TYPE_CLASS_TEXT);
        EditText value = field(box, R.string.mcp_header_value, hValue, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.show(Ui.dialog(this).setTitle(existing == null ? R.string.mcp_add : R.string.mcp_edit).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Ui.toast(this, R.string.mcp_bad_url);
                        return;
                    }
                    Mcp.Server s = existing != null ? existing : new Mcp.Server();
                    s.name = name.getText().toString().trim().isEmpty() ? android.net.Uri.parse(u).getHost() : name.getText().toString().trim();
                    s.url = u;
                    s.headers.clear();
                    String hn = header.getText().toString().trim(), hv = value.getText().toString().trim();
                    if (!hn.isEmpty() && !hv.isEmpty()) s.headers.put(hn, hv);
                    Mcp.save(g, s);
                    refresh(s.id);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }
}
