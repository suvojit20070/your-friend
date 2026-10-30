package com.suvo.agent.ui;

import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.suvo.agent.R;
import com.suvo.agent.connectors.Connector;
import com.suvo.agent.connectors.ConnectorPresets;
import com.suvo.agent.connectors.WebSession;

/**
 * Your Friend — Original project by Suvo.
 *
 * Log in to a website for a web connector (Instagram, Facebook, X, WhatsApp Web…). The page's
 * cookies are the app's own, shared with the agent's background browser, so after logging in here
 * the agent works on the site as the user. The check mark (or back) finishes.
 */
public final class ConnectorWebActivity extends BaseActivity {

    public static final String EXTRA_ID = "connector_id";

    private WebView web;
    private Connector connector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connector_web);
        connector = g.connectors.find(getIntent().getStringExtra(EXTRA_ID));
        if (connector == null) {
            finish();
            return;
        }
        setTopBar(getString(R.string.connector_web_title, ConnectorPresets.title(connector.service)));
        topAction(R.drawable.ic_check, R.string.connector_done, v -> done());
        web = findViewById(R.id.connector_web);
        WebSession.configure(web, connector);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String s = req.getUrl().getScheme();
                // Only web pages here (no app links that would leave the login).
                return s == null || !(s.equals("http") || s.equals("https"));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }
        });
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(connector.homeUrl);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    private void done() {
        CookieManager.getInstance().flush();
        Ui.toast(this, WebSession.hasCookies(connector)
                ? getString(R.string.connector_works, connector.name)
                : getString(R.string.connector_web_no_login));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else done();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
