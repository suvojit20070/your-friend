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
        pull = findViewById(R.id.browser_refresh);
        pull.setChildScrollCheck(() -> {
            AgentWebView w = WebSession.current();
            return w == null || w.getScrollY() > 0 || w.canScrollVertically(-1);
        });
        pull.setOnRefreshListener(() -> {
            AgentWebView w = WebSession.current();
            if (w == null) {
                pull.setRefreshing(false);
                return;
            }
            w.reload();
            // Never spin forever (a page that never reports 100%).
            pull.postDelayed(() -> {
                if (pull.isRefreshing()) pull.setRefreshing(false);
            }, 15_000);
        });
        tabsButton = findViewById(R.id.browser_tabs);
        // The top bar's back arrow leaves the browser (the phone's back button goes back in the page).
        View topBack = findViewById(R.id.top_back);
        if (topBack != null) topBack.setOnClickListener(v -> finish());
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
    protected void onDestroy() {
        WebSession.releaseScreen(this);
        super.onDestroy();
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

    /** A page asks something while the user browses here: shown as a dialog (the agent can answer it too). */
    @Override
    public boolean showPageDialog(AgentWebView tab, AgentWebView.PageDialog d) {
        if (isFinishing()) return false;
        android.widget.EditText input = null;
        View body = null;
        if ("prompt".equals(d.type)) {
            input = new android.widget.EditText(this, null, 0, R.style.YF_Input);
            input.setText(d.defaultValue == null ? "" : d.defaultValue);
            FrameLayout wrap = new FrameLayout(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 8), 0, 0);
            wrap.addView(input, lp);
            body = wrap;
        }
        final android.widget.EditText field = input;
        String host = tab.getUrl() == null ? "" : String.valueOf(Uri.parse(tab.getUrl()).getHost());
        GlassDialog.Builder b = Ui.dialog(this).setTitle(host).setMessage(d.message)
                .setPositiveButton(android.R.string.ok, (x, w) -> d.answer(true, field == null ? null : field.getText().toString()))
                .setOnDismissListener(x -> d.answer(false, null));
        if (!"alert".equals(d.type)) b.setNegativeButton(android.R.string.cancel, (x, w) -> d.answer(false, null));
        if (body != null) b.setView(body);
        Ui.show(b);
        return true;
    }

    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILES = 41;

    /** A page wants files and the agent armed none: the system picker, for the user. */
    @Override
    public boolean pickFiles(android.webkit.ValueCallback<Uri[]> cb, android.webkit.WebChromeClient.FileChooserParams params) {
        if (isFinishing()) return false;
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = cb;
        try {
            Intent i = params != null ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            if (params != null && params.getMode() == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(i, PICK_FILES);
            return true;
        } catch (RuntimeException e) {
            fileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || fileCallback == null) return;
        Uri[] picked = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                picked = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    @Override
    public void onTabsChanged() {
        if (!isFinishing()) refresh();
    }

    private com.suvo.agent.ui.widgets.FasterPullToRefreshLayout pull;

    private void refresh() {
        AgentWebView w = WebSession.current();
        List<AgentWebView> tabs = WebSession.tabList();
        tabsButton.setText(String.valueOf(tabs.size()));
        if (w == null) {
            progress.setVisibility(View.INVISIBLE);
            if (pull != null && pull.isRefreshing()) pull.setRefreshing(false);
            return;
        }
        if (!address.hasFocus()) address.setText(w.getUrl() == null ? "" : w.getUrl());
        setTopBar(TextUtils.isEmpty(w.getTitle()) ? getString(R.string.browser_title) : w.getTitle());
        progress.setVisibility(w.progress < 100 ? View.VISIBLE : View.INVISIBLE);
        if (w.progress >= 100 && pull != null && pull.isRefreshing()) pull.setRefreshing(false);
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
                getString(R.string.browser_service_close),
        };
        GlassSheet.options(this, getString(R.string.browser_settings), items, -1, null).setOnPick(which -> {
            AgentWebView w = WebSession.current();
            switch (which) {
                case 0:
                    g.settings.put(DESKTOP, !desktop);
                    for (AgentWebView t : WebSession.tabList()) WebSession.configure(t, Connector.openWeb());
                    // Load the address again (not reload(): that can keep the old mobile page and user agent).
                    if (w != null && w.getUrl() != null) w.loadUrl(w.getUrl());
                    Ui.toast(this, !desktop ? R.string.browser_desktop_on : R.string.browser_desktop_off);
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
                case 6:
                    // Close every tab: the browser service stops with the last one.
                    WebSession.closeAllTabs();
                    finish();
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
