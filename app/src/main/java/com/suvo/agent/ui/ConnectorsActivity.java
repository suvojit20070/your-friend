package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.connectors.Connector;
import com.suvo.agent.connectors.ConnectorPresets;
import com.suvo.agent.connectors.ConnectorRepo;
import com.suvo.agent.connectors.OAuthFlow;
import com.suvo.agent.connectors.WebSession;
import com.suvo.agent.tools.ConnectorTools;

import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Settings → Connectors: connect the user's accounts so the agent can use them with full access —
 * mailboxes (Gmail, Outlook, Yahoo, any), Google sign-in (YouTube, Gmail API, Drive, Calendar),
 * logged-in websites (Instagram, Facebook, X, WhatsApp Web, Telegram Web…), bots and APIs
 * (Telegram bot, GitHub, Notion, any key). Connected accounts are listed on top: check, log in
 * again, edit, rename or disconnect.
 */
public final class ConnectorsActivity extends BaseActivity implements ConnectorRepo.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.connectors_title);
        g.connectors.addListener(this);
        build();
    }

    @Override
    protected void onDestroy() {
        g.connectors.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onConnectorsChanged() {
        main.post(() -> {
            if (!isFinishing()) build();
        });
    }

    private static int iconFor(String type) {
        switch (type) {
            case Connector.EMAIL:
                return R.drawable.ic_mail;
            case Connector.WEB:
                return R.drawable.ic_globe;
            case Connector.OAUTH:
                return R.drawable.ic_link;
            default:
                return R.drawable.ic_key;
        }
    }

    private int groupTitle(String group) {
        switch (group) {
            case ConnectorPresets.G_MAIL:
                return R.string.connectors_group_mail;
            case ConnectorPresets.G_GOOGLE:
                return R.string.connectors_group_google;
            case ConnectorPresets.G_SOCIAL:
                return R.string.connectors_group_social;
            default:
                return R.string.connectors_group_api;
        }
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.connectors_intro));

        List<Connector> mine = g.connectors.list();
        r.section(R.string.connectors_connected);
        if (mine.isEmpty()) r.note(getString(R.string.connectors_none));
        for (Connector c : mine) {
            String sub = c.name + (c.account.isEmpty() ? "" : " · " + c.account)
                    + (c.isConnected() ? "" : " · " + getString(R.string.connector_not_signed_in));
            r.row(iconFor(c.type), ConnectorPresets.title(c.service), sub, null, v -> manage(c));
        }

        String group = null;
        for (ConnectorPresets.Preset p : ConnectorPresets.all()) {
            if (!p.group.equals(group)) {
                group = p.group;
                r.section(groupTitle(group));
            }
            r.row(iconFor(p.type), p.title, p.blurb, null, v -> add(p));
        }
    }

    // ------------------------------------------------------------------ connected account menu

    private void manage(Connector c) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add(getString(R.string.connector_check));
        acts.add(() -> check(c));
        if (Connector.WEB.equals(c.type)) {
            items.add(getString(R.string.connector_open));
            acts.add(() -> openWeb(c));
        }
        if (Connector.OAUTH.equals(c.type)) {
            items.add(getString(R.string.connector_reconnect));
            acts.add(() -> startOAuth(c));
        }
        items.add(getString(R.string.connector_edit));
        acts.add(() -> edit(c));
        items.add(getString(R.string.connector_rename));
        acts.add(() -> Ui.prompt(this, R.string.connector_rename, c.name, false, t -> {
            if (t.trim().isEmpty()) return;
            c.name = t.trim();
            g.connectors.save(c);
        }));
        items.add(getString(R.string.connector_disconnect));
        acts.add(() -> Ui.confirm(this, R.string.connector_disconnect, getString(R.string.connector_disconnect_confirm, c.name), () -> {
            if (Connector.WEB.equals(c.type)) WebSession.forget(c);
            g.connectors.delete(c.id);
        }));
        Ui.show(Ui.dialog(this).setTitle(ConnectorPresets.title(c.service) + " · " + c.name)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()));
    }

    private void check(Connector c) {
        Ui.toast(this, R.string.connector_checking);
        new Thread(() -> {
            String problem = ConnectorTools.check(g, c);
            main.post(() -> {
                if (isFinishing()) return;
                if (problem == null) Ui.toast(this, getString(R.string.connector_works, c.name));
                else Ui.show(Ui.dialog(this).setTitle(R.string.connector_failed).setMessage(problem)
                        .setPositiveButton(android.R.string.ok, null));
            });
        }, "yf-connector-check").start();
    }

    private void add(ConnectorPresets.Preset p) {
        Connector c = p.create();
        edit(c);
    }

    private void edit(Connector c) {
        switch (c.type) {
            case Connector.EMAIL:
                editEmail(c);
                break;
            case Connector.OAUTH:
                editOAuth(c);
                break;
            case Connector.WEB:
                editWeb(c);
                break;
            default:
                editToken(c);
                break;
        }
    }

    // ------------------------------------------------------------------ forms

    private LinearLayout form() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        f.setPadding(pad, Ui.dp(this, 8), pad, 0);
        return f;
    }

    private EditText field(LinearLayout form, int labelRes, String value, int inputType) {
        TextView l = new TextView(this, null, 0, R.style.YF_Label);
        l.setText(labelRes);
        form.addView(l);
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        e.setInputType(inputType);
        e.setText(value);
        form.addView(e);
        return e;
    }

    private EditText secretField(LinearLayout form, int labelRes, boolean hasValue) {
        EditText e = field(form, labelRes, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (hasValue) e.setHint(R.string.connector_keep_secret);
        return e;
    }

    private void help(LinearLayout form, CharSequence text) {
        TextView t = new TextView(this, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setAutoLinkMask(Linkify.WEB_URLS);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        form.addView(t, lp);
    }

    private android.widget.ScrollView scroll(LinearLayout form) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(form);
        return sv;
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(text(e));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Hosts from a URL ("https://api.example.com/v1" → "api.example.com"). */
    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Mail

    private void editEmail(Connector c) {
        boolean custom = "custom_email".equals(c.service);
        LinearLayout f = form();
        if ("gmail".equals(c.service)) help(f, getString(R.string.connector_help_gmail));
        else help(f, getString(R.string.connector_help_mail));
        EditText user = field(f, R.string.connector_email, c.username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = secretField(f, R.string.connector_app_password, !c.password.isEmpty());
        EditText imap = null, imapPort = null, smtp = null, smtpPort = null;
        Spinner sec = null;
        if (custom) {
            imap = field(f, R.string.connector_imap_host, c.imapHost, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            imapPort = field(f, R.string.connector_imap_port, String.valueOf(c.imapPort), InputType.TYPE_CLASS_NUMBER);
            smtp = field(f, R.string.connector_smtp_host, c.smtpHost, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            smtpPort = field(f, R.string.connector_smtp_port, String.valueOf(c.smtpPort), InputType.TYPE_CLASS_NUMBER);
            TextView l = new TextView(this, null, 0, R.style.YF_Label);
            l.setText(R.string.connector_smtp_security);
            f.addView(l);
            sec = Widgets.styleSpinner(new Spinner(this));
            sec.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"SSL / TLS (465)", "STARTTLS (587)"}));
            sec.setSelection(c.smtpSsl ? 0 : 1);
            f.addView(sec);
        }
        final EditText fImap = imap, fImapPort = imapPort, fSmtp = smtp, fSmtpPort = smtpPort;
        final Spinner fSec = sec;
        Ui.show(Ui.dialog(this).setTitle(ConnectorPresets.title(c.service)).setView(scroll(f))
                .setPositiveButton(R.string.connector_connect, (d, w) -> {
                    c.username = text(user);
                    String p = pass.getText().toString().replace(" ", "");
                    if (!p.isEmpty()) c.password = p;
                    if (custom) {
                        c.imapHost = text(fImap);
                        c.imapPort = num(fImapPort, 993);
                        c.smtpHost = text(fSmtp);
                        c.smtpPort = num(fSmtpPort, 465);
                        c.smtpSsl = fSec.getSelectedItemPosition() == 0;
                    }
                    if (c.username.isEmpty() || c.password.isEmpty() || c.imapHost.isEmpty() || c.smtpHost.isEmpty()) {
                        Ui.toast(this, R.string.connector_invalid);
                        return;
                    }
                    c.account = c.username;
                    if (c.name.isEmpty() || c.name.equals(c.service.replace('_', '-'))) c.name = c.service.replace('_', '-');
                    testThenSave(c);
                })
                .setNegativeButton(R.string.cancel, null));
    }

    // ---- API key / bot token

    private void editToken(Connector c) {
        boolean custom = "custom_api".equals(c.service);
        LinearLayout f = form();
        switch (c.service) {
            case "telegram_bot":
                help(f, getString(R.string.connector_help_telegram));
                break;
            case "github":
                help(f, getString(R.string.connector_help_github));
                break;
            case "notion":
                help(f, getString(R.string.connector_help_notion));
                break;
            default:
                help(f, getString(R.string.connector_help_api));
                break;
        }
        EditText base = null, auth = null;
        Spinner style = null;
        if (custom) {
            base = field(f, R.string.connector_base_url, c.baseUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            TextView l = new TextView(this, null, 0, R.style.YF_Label);
            l.setText(R.string.connector_auth_style);
            f.addView(l);
            style = Widgets.styleSpinner(new Spinner(this));
            style.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"Authorization: Bearer", "Header", "Query parameter", "None"}));
            String[] styles = {"bearer", "header", "query", "none"};
            for (int i = 0; i < styles.length; i++) if (styles[i].equals(c.authStyle)) style.setSelection(i);
            f.addView(style);
            auth = field(f, R.string.connector_auth_name, c.authName, InputType.TYPE_CLASS_TEXT);
        }
        EditText token = secretField(f, R.string.connector_token, !c.token.isEmpty());
        final EditText fBase = base, fAuth = auth;
        final Spinner fStyle = style;
        Ui.show(Ui.dialog(this).setTitle(ConnectorPresets.title(c.service)).setView(scroll(f))
                .setPositiveButton(R.string.connector_connect, (d, w) -> {
                    String t = token.getText().toString().trim();
                    if (!t.isEmpty()) c.token = t;
                    if (custom) {
                        c.baseUrl = text(fBase);
                        if (!c.baseUrl.isEmpty() && !c.baseUrl.endsWith("/")) c.baseUrl += "/";
                        c.hosts = hostOf(c.baseUrl);
                        c.authStyle = new String[]{"bearer", "header", "query", "none"}[fStyle.getSelectedItemPosition()];
                        c.authName = text(fAuth);
                        if (!c.baseUrl.startsWith("https://") || c.hosts.isEmpty()) {
                            Ui.toast(this, R.string.connector_invalid);
                            return;
                        }
                        if (c.name.equals("custom-api")) c.name = c.hosts.split("\\.").length > 1
                                ? c.hosts.split("\\.")[c.hosts.split("\\.").length - 2] : c.hosts;
                    }
                    if (c.token.isEmpty() && !"none".equals(c.authStyle)) {
                        Ui.toast(this, R.string.connector_invalid);
                        return;
                    }
                    testThenSave(c);
                })
                .setNegativeButton(R.string.cancel, null));
    }

    // ---- OAuth (Google and custom)

    private void editOAuth(Connector c) {
        boolean custom = "custom_oauth".equals(c.service);
        LinearLayout f = form();
        help(f, getString(custom ? R.string.connector_help_oauth : R.string.connector_help_google));
        EditText name = null, authUrl = null, tokenUrl = null, base = null;
        if (custom) {
            name = field(f, R.string.connector_name, c.name, InputType.TYPE_CLASS_TEXT);
            authUrl = field(f, R.string.connector_auth_url, c.authUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            tokenUrl = field(f, R.string.connector_token_url, c.tokenUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            base = field(f, R.string.connector_base_url, c.baseUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        EditText id = field(f, R.string.connector_client_id, c.clientId, InputType.TYPE_CLASS_TEXT);
        EditText secret = secretField(f, R.string.connector_client_secret, !c.clientSecret.isEmpty());
        EditText scopes = field(f, R.string.connector_scopes, c.scopes, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText fName = name, fAuth = authUrl, fToken = tokenUrl, fBase = base;
        Ui.show(Ui.dialog(this).setTitle(ConnectorPresets.title(c.service)).setView(scroll(f))
                .setPositiveButton(R.string.connector_sign_in, (d, w) -> {
                    c.clientId = text(id);
                    String s = secret.getText().toString().trim();
                    if (!s.isEmpty()) c.clientSecret = s;
                    c.scopes = text(scopes).replaceAll("\\s+", " ");
                    if (custom) {
                        c.name = text(fName).isEmpty() ? c.name : text(fName);
                        c.authUrl = text(fAuth);
                        c.tokenUrl = text(fToken);
                        c.baseUrl = text(fBase);
                        if (!c.baseUrl.isEmpty() && !c.baseUrl.endsWith("/")) c.baseUrl += "/";
                        c.hosts = hostOf(c.baseUrl);
                    }
                    if (c.clientId.isEmpty() || !c.authUrl.startsWith("https://") || !c.tokenUrl.startsWith("https://")) {
                        Ui.toast(this, R.string.connector_invalid);
                        return;
                    }
                    startOAuth(c);
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void startOAuth(Connector c) {
        Ui.toast(this, R.string.connector_browser);
        OAuthFlow.start(this, c, (done, error) -> main.post(() -> {
            if (isFinishing()) return;
            if (error == null) {
                Ui.toast(this, getString(R.string.connector_works, done.name));
                build();
            } else {
                Ui.show(Ui.dialog(this).setTitle(R.string.connector_failed).setMessage(error)
                        .setPositiveButton(android.R.string.ok, null));
            }
        }));
    }

    // ---- Website login

    private void editWeb(Connector c) {
        if (!"custom_web".equals(c.service) && !c.id.isEmpty()) {
            openWeb(c);
            return;
        }
        if (!"custom_web".equals(c.service)) {
            g.connectors.save(c);
            openWeb(c);
            return;
        }
        LinearLayout f = form();
        help(f, getString(R.string.connector_help_web));
        EditText url = field(f, R.string.connector_site_url, c.homeUrl.isEmpty() ? "https://" : c.homeUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Ui.show(Ui.dialog(this).setTitle(ConnectorPresets.title(c.service)).setView(scroll(f))
                .setPositiveButton(R.string.connector_log_in, (d, w) -> {
                    String u = text(url);
                    if (!u.startsWith("http")) u = "https://" + u;
                    String host = hostOf(u);
                    if (host.isEmpty()) {
                        Ui.toast(this, R.string.connector_invalid);
                        return;
                    }
                    c.homeUrl = u;
                    String bare = host.startsWith("www.") ? host.substring(4) : host;
                    c.hosts = bare;
                    if (c.name.equals("custom-web")) c.name = bare.split("\\.")[0];
                    g.connectors.save(c);
                    openWeb(c);
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void openWeb(Connector c) {
        Connector saved = g.connectors.find(c.name);
        String id = saved != null ? saved.id : c.id;
        startActivity(new Intent(this, ConnectorWebActivity.class).putExtra(ConnectorWebActivity.EXTRA_ID, id));
    }

    // ---- Save after a test

    private void testThenSave(Connector c) {
        Ui.toast(this, R.string.connector_checking);
        new Thread(() -> {
            String problem = ConnectorTools.check(g, c);
            if (problem == null) fillAccount(c);
            main.post(() -> {
                if (isFinishing()) return;
                if (problem == null) {
                    g.connectors.save(c);
                    Ui.toast(this, getString(R.string.connector_works, c.name));
                } else {
                    Ui.show(Ui.dialog(this).setTitle(R.string.connector_failed).setMessage(problem)
                            .setPositiveButton(R.string.connector_fix, (d, w) -> edit(c))
                            .setNeutralButton(R.string.connector_save_anyway, (d, w) -> g.connectors.save(c))
                            .setNegativeButton(R.string.cancel, null));
                }
            });
        }, "yf-connector-test").start();
    }

    /** Who the token belongs to (Telegram bot @name, GitHub login). Best effort, background thread. */
    private void fillAccount(Connector c) {
        try {
            if ("telegram_bot".equals(c.service)) {
                JSONObject o = new JSONObject(apiGet(c, "getMe"));
                String u = o.optJSONObject("result") == null ? "" : o.optJSONObject("result").optString("username");
                if (!u.isEmpty()) c.account = "@" + u;
            } else if ("github".equals(c.service)) {
                String login = new JSONObject(apiGet(c, "user")).optString("login");
                if (!login.isEmpty()) c.account = login;
            }
        } catch (Exception ignored) {
        }
    }

    private static String apiGet(Connector c, String path) throws Exception {
        String url = c.baseUrl.replace("{token}", c.token) + path;
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(15_000);
            h.setReadTimeout(15_000);
            if ("bearer".equals(c.authStyle)) h.setRequestProperty("Authorization", "Bearer " + c.token);
            h.setRequestProperty("Accept", "application/json");
            java.io.InputStream in = h.getResponseCode() >= 400 ? h.getErrorStream() : h.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
