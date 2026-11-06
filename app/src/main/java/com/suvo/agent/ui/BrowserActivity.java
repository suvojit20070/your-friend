package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.suvo.agent.R;
import com.suvo.agent.connectors.AgentWebView;
import com.suvo.agent.connectors.Connector;
import com.suvo.agent.connectors.WebSession;

import java.net.URLEncoder;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The agent's browser as a full screen. It shows the very same tabs the agent works in (the
 * floating browser moves in here while this screen is open and back out when it closes): address
 * bar, back / forward / reload, new tab, the tab list (switch, close), and settings (desktop site,
 * home page, clear cookies / site data / cache, open in another browser). The user can browse and
 * log in here; the agent can then continue in the same tab, and switch tabs with its browser tool.
 */
public final class BrowserActivity extends BaseActivity implements WebSession.Host, WebSession.TabsListener {

    public static final String HOME = "browser_home";
    public static final String DESKTOP = "browser_desktop";

    private EditText address;
    private FrameLayout slot;
    private TextView tabsButton;
    private LinearProgressIndicator progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        setTopBar(R.string.browser_title);
        topAction(R.drawable.ic_settings, R.string.browser_settings, v -> settings());
        address = findViewById(R.id.browser_address);
        slot = findViewById(R.id.browser_slot);
        tabsButton = findViewById(R.id.browser_tabs);
        slot.setTag(R.id.theme_skip, Boolean.TRUE);
        slot.setClipToOutline(true);
        slot.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(BrowserActivity.this, 18));
            }
        });
        progress = Progress.linear(this, false);
        ((FrameLayout) findViewById(R.id.browser_progress)).addView(progress,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.glassField(address, slot);

        address.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || e != null) {
                go(address.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.browser_back).setOnClickListener(v -> {
            AgentWebView w = WebSession.current();
            if (w != null && w.canGoBack()) w.goBack();
        });
        findViewById(R.id.browser_forward).setOnClickListener(v -> {
            AgentWebView w = WebSession.current();
            if (w != null && w.canGoForward()) w.goForward();
        });
        findViewById(R.id.browser_reload).setOnClickListener(v -> {
            AgentWebView w = WebSession.current();
            if (w == null) return;
            if (w.progress < 100) w.stopLoading();
            else w.reload();
        });
        findViewById(R.id.browser_new).setOnClickListener(v -> {
            AgentWebView t = WebSession.newTab(this);
            t.loadUrl(home());
            address.requestFocus();
        });
        tabsButton.setOnClickListener(v -> showTabs());

        String url = getIntent() == null ? null : getIntent().getDataString();
        if (WebSession.current() == null) {
            AgentWebView t = WebSession.newTab(this);
            t.loadUrl(url != null ? url : home());
        } else if (url != null) {
            WebSession.current().loadUrl(url);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        WebSession.addTabsListener(this);
        WebSession.setScreen(this);
        refresh();
    }

    @Override
    protected void onStop() {
        WebSession.clearScreen(this);
        WebSession.removeTabsListener(this);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        AgentWebView w = WebSession.current();
        if (w != null && w.canGoBack() && !Overlay.anyOpen(this)) {
            w.goBack();
            return;
        }
        super.onBackPressed();
    }

    private String home() {
        return g.settings.str(HOME, "https://www.google.com");
    }

    /** An address, or a search. */
    private void go(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            url = s;
        } else if (!s.contains(" ") && s.contains(".") && !s.endsWith(".")) {
            url = "https://" + s;
        } else {
            try {
                url = "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                return;
            }
        }
        AgentWebView w = WebSession.current();
        if (w == null) w = WebSession.newTab(this);
        w.loadUrl(url);
        address.clearFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ WebSession.Host

    @Override
    public ViewGroup browserSlot() {
        return slot;
    }

    @Override
    public void showBrowser(String title) {
        refresh();
    }

    @Override
    public void hideBrowser() {
        refresh();
    }

    @Override
    public void onTabsChanged() {
        if (!isFinishing()) refresh();
    }

    private void refresh() {
        AgentWebView w = WebSession.current();
        List<AgentWebView> tabs = WebSession.tabList();
        tabsButton.setText(String.valueOf(tabs.size()));
        if (w == null) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        if (!address.hasFocus()) address.setText(w.getUrl() == null ? "" : w.getUrl());
        setTopBar(TextUtils.isEmpty(w.getTitle()) ? getString(R.string.browser_title) : w.getTitle());
        progress.setVisibility(w.progress < 100 ? View.VISIBLE : View.INVISIBLE);
        progress.setProgressCompat(w.progress, true);
        findViewById(R.id.browser_back).setAlpha(w.canGoBack() ? 1f : 0.4f);
        findViewById(R.id.browser_forward).setAlpha(w.canGoForward() ? 1f : 0.4f);
        ((ImageView) findViewById(R.id.browser_reload)).setImageResource(w.progress < 100 ? R.drawable.ic_close : R.drawable.ic_refresh);
    }

    // ------------------------------------------------------------------ tabs

    private void showTabs() {
        List<AgentWebView> tabs = WebSession.tabList();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Palette pal = Palette.of(this);
        AgentWebView cur = WebSession.current();
        final Overlay[] dlg = new Overlay[1];
        for (AgentWebView t : tabs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(new TonalDrawable(t == cur ? Palette.alpha(pal.primary, 0.14f) : pal.surfaceHighest, Ui.dp(this, 16))
                    .pressable(pal.pressed()));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this, null, 0, R.style.YF_RowTitle);
            title.setText(TextUtils.isEmpty(t.getTitle()) ? getString(R.string.browser_new_tab) : t.getTitle());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView sub = new TextView(this, null, 0, R.style.YF_Caption);
            String host = t.getUrl() == null ? "" : Uri.parse(t.getUrl()).getHost();
            sub.setText((host == null ? "" : host) + ("browser".equals(t.owner) ? "" : " · " + t.owner) + "  #" + t.tabId);
            sub.setSingleLine(true);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
            row.addView(texts, tp);
            ImageView close = new ImageView(this);
            close.setImageResource(R.drawable.ic_close);
            close.setImageTintList(android.content.res.ColorStateList.valueOf(pal.onSurfaceVariant));
            close.setContentDescription(getString(R.string.browser_close_tab));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
            cp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
            row.addView(close, cp);
            row.setOnClickListener(v -> {
                WebSession.switchTab(t.tabId);
                if (dlg[0] != null) dlg[0].dismiss();
            });
            close.setOnClickListener(v -> {
                WebSession.closeTab(t.tabId);
                if (dlg[0] != null) dlg[0].dismiss();
                if (WebSession.tabList().isEmpty()) {
                    AgentWebView n = WebSession.newTab(this);
                    n.loadUrl(home());
                } else {
                    showTabs();
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            box.addView(row, rp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = Ui.show(Ui.dialog(this).setTitle(getString(R.string.browser_tabs_count, tabs.size())).setView(sv)
                .setPositiveButton(R.string.browser_new_tab, (d, w) -> {
                    AgentWebView n = WebSession.newTab(this);
                    n.loadUrl(home());
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    // ------------------------------------------------------------------ settings

    private void settings() {
        boolean desktop = g.settings.bool(DESKTOP, false);
        CharSequence[] items = {
                getString(desktop ? R.string.browser_mobile_site : R.string.browser_desktop_site),
                getString(R.string.browser_set_home),
                getString(R.string.browser_open_external),
                getString(R.string.browser_close_all),
                getString(R.string.browser_clear_cache),
                getString(R.string.browser_clear_cookies),
        };
        GlassSheet.options(this, getString(R.string.browser_settings), items, -1, null).setOnPick(which -> {
            AgentWebView w = WebSession.current();
            switch (which) {
                case 0:
                    g.settings.put(DESKTOP, !desktop);
                    for (AgentWebView t : WebSession.tabList()) WebSession.configure(t, Connector.openWeb());
                    if (w != null) w.reload();
                    break;
                case 1:
                    Ui.prompt(this, R.string.browser_set_home, w != null && w.getUrl() != null ? w.getUrl() : home(), false, s -> {
                        if (s != null && !s.trim().isEmpty()) g.settings.put(HOME, s.trim());
                    });
                    break;
                case 2:
                    if (w != null && w.getUrl() != null) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(w.getUrl())));
                        } catch (RuntimeException e) {
                            Ui.toast(this, R.string.block_no_app);
                        }
                    }
                    break;
                case 3:
                    for (AgentWebView t : WebSession.tabList()) WebSession.closeTab(t.tabId);
                    WebSession.newTab(this).loadUrl(home());
                    break;
                case 4:
                    if (w != null) w.clearCache(true);
                    for (AgentWebView t : WebSession.tabList()) t.clearHistory();
                    Ui.toast(this, R.string.browser_cleared);
                    break;
                default:
                    Ui.confirm(this, R.string.browser_clear_cookies, getString(R.string.browser_clear_cookies_confirm), () -> {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        Ui.toast(this, R.string.browser_cleared);
                    });
                    break;
            }
        }).show();
    }

}
