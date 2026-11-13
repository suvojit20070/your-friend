package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.plugins.Plugins;

/**
 * Your Friend — Original project by Suvo.
 *
 * Native connector plugins: install the ones you use (only those reach the agent), log in once in
 * the agent's browser, and see whether the login is there.
 */
public final class PluginsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        pullToRefresh(this::build);
        setTopBar(R.string.plugins_title);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(); // the login may have changed in the browser
    }

    private static int iconFor(Plugins.Info p) {
        switch (p.id) {
            case "youtube":
                return R.drawable.ic_play;
            case "instagram":
                return R.drawable.ic_camera;
            case "whatsapp":
                return R.drawable.ic_chat;
            default:
                return R.drawable.ic_globe;
        }
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.plugins_intro));
        for (Plugins.Info p : Plugins.all()) {
            r.section(p.name);
            com.google.android.material.materialswitch.MaterialSwitch sw = Widgets.newSwitch(this);
            sw.setChecked(Plugins.installed(g, p));
            sw.setOnCheckedChangeListener((b, on) -> {
                g.settings.put(Plugins.key(p), on);
                box.postDelayed(this::build, 240);
            });
            r.row(iconFor(p), getString(R.string.plugins_install), p.description, sw, v -> sw.toggle());
            if (!Plugins.installed(g, p)) continue;
            if (!p.loginUrl.isEmpty()) {
                boolean in = Plugins.loggedIn(p);
                Palette pal = Palette.of(this);
                r.row(R.drawable.ic_key, getString(in ? R.string.plugins_logged_in : R.string.plugins_log_in),
                        getString(R.string.plugins_login_desc),
                        r.chip(getString(in ? R.string.plugins_ok : R.string.plugins_needed), in ? pal.primary : pal.error),
                        v -> startActivity(new Intent(this, BrowserActivity.class).setData(Uri.parse(p.loginUrl))));
            } else {
                r.note(getString(R.string.plugins_whatsapp_how));
            }
        }
    }
}
