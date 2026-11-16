package com.suvo.agent.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.netgate.NetGateService;
import com.suvo.agent.netgate.NetGateStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Net Gate settings: on/off, which apps, the mode, and the proxies. Your Friend is never listed:
 * its internet always works.
 */
public final class NetGateActivity extends BaseActivity {

    private static final int REQ_VPN = 71;
    private NetGateStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.netgate_name);
        store = new NetGateStore(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.netgate_intro));

        r.section(R.string.netgate_name);
        r.switchRow(R.drawable.ic_globe, R.string.netgate_switch, 0, NetGateStore.ON, false, on -> {
            if (on) turnOn();
            else {
                NetGateService.stop(this);
                build();
            }
        });
        String status;
        if (NetGateService.isRunning()) status = getString(R.string.netgate_status_on, modeText(), store.apps().size())
                + (store.useProxy() ? " · " + getString(R.string.netgate_status_proxy, store.proxies().size()) : "");
        else if (!NetGateService.lastError().isEmpty()) status = getString(R.string.error_prefix, NetGateService.lastError());
        else status = getString(R.string.netgate_status_off);
        r.note(status);

        r.choiceRow(R.drawable.ic_tools, R.string.netgate_mode, NetGateStore.MODE, NetGateStore.ALLOW,
                new String[]{getString(R.string.netgate_mode_allow), getString(R.string.netgate_mode_block)},
                new String[]{NetGateStore.ALLOW, NetGateStore.BLOCK}, v -> {
                    restartIfRunning();
                    build();
                });
        int n = store.apps().size();
        r.row(R.drawable.ic_shield, getString(R.string.netgate_apps),
                getResources().getQuantityString(R.plurals.netgate_apps_count, n, n) + " · " + appNames(), null, v -> pickApps());
        r.note(getString(R.string.netgate_self_note));

        r.section(R.string.netgate_proxy);
        boolean engine = hev.htproxy.TProxyService.available() && android.os.Build.VERSION.SDK_INT >= 29;
        if (!engine) {
            r.note(getString(android.os.Build.VERSION.SDK_INT >= 29 ? R.string.netgate_proxy_missing : R.string.netgate_proxy_old_android));
        } else {
            r.switchRow(R.drawable.ic_link, R.string.netgate_proxy_switch, R.string.netgate_proxy_desc, NetGateStore.PROXY_ON, false, v -> {
                restartIfRunning();
                build();
            });
            r.switchRow(R.drawable.ic_link, R.string.netgate_rotate, R.string.netgate_rotate_desc, NetGateStore.ROTATE, true, v -> restartIfRunning());
            List<NetGateStore.Proxy> list = store.proxies();
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                r.row(R.drawable.ic_link, list.get(i).label(), null, null, v -> editProxy(idx));
            }
            r.button(R.string.netgate_add_proxy, false, v -> editProxy(-1));
        }
    }

    private String modeText() {
        return getString(NetGateStore.ALLOW.equals(store.mode()) ? R.string.netgate_mode_allow : R.string.netgate_mode_block);
    }

    // ---- On / off ---------------------------------------------------------------------------------

    private void turnOn() {
        if (NetGateStore.BLOCK.equals(store.mode()) && store.apps().isEmpty() && !store.useProxy()) {
            store.setOn(false);
            Ui.toast(this, R.string.netgate_no_apps);
            build();
            return;
        }
        Intent consent = NetGateService.start(this);
        if (consent != null) {
            // Android asks once: "Your Friend wants to set up a VPN connection".
            store.setOn(false);
            try {
                startActivityForResult(consent, REQ_VPN);
            } catch (RuntimeException e) {
                Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
            }
            return;
        }
        getWindow().getDecorView().postDelayed(this::build, 600);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) {
            NetGateService.start(this);
            getWindow().getDecorView().postDelayed(this::build, 600);
        } else {
            store.setOn(false);
            Ui.toast(this, R.string.netgate_no_consent);
            build();
        }
    }

    private void restartIfRunning() {
        if (NetGateService.isRunning()) NetGateService.start(this);
    }

    // ---- Apps -------------------------------------------------------------------------------------

    private List<String[]> installedApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            // Your Friend is never offered: its internet cannot be blocked.
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(apps, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return apps;
    }

    private String appNames() {
        Set<String> chosen = store.apps();
        if (chosen.isEmpty()) return getString(R.string.netgate_apps_none);
        PackageManager pm = getPackageManager();
        StringBuilder b = new StringBuilder();
        int k = 0;
        for (String p : chosen) {
            if (k++ == 4) {
                b.append(", …");
                break;
            }
            if (b.length() > 0) b.append(", ");
            try {
                b.append(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                b.append(p);
            }
        }
        return b.toString();
    }

    private void pickApps() {
        List<String[]> apps = installedApps();
        Set<String> chosen = store.apps();
        // The chosen ones first, then the rest by name.
        List<String[]> ordered = new ArrayList<>();
        for (String[] a : apps) if (chosen.contains(a[0])) ordered.add(a);
        for (String[] a : apps) if (!chosen.contains(a[0])) ordered.add(a);
        CharSequence[] labels = new CharSequence[ordered.size()];
        boolean[] checked = new boolean[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            labels[i] = ordered.get(i)[1];
            checked[i] = chosen.contains(ordered.get(i)[0]);
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.netgate_apps)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Set<String> out = new LinkedHashSet<>();
                    for (int i = 0; i < ordered.size(); i++) if (checked[i]) out.add(ordered.get(i)[0]);
                    store.setApps(out);
                    restartIfRunning();
                    build();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    // ---- Proxies ----------------------------------------------------------------------------------

    private void editProxy(int index) {
        List<NetGateStore.Proxy> list = store.proxies();
        NetGateStore.Proxy p = index >= 0 && index < list.size() ? list.get(index) : new NetGateStore.Proxy();
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        final String[] type = {p.type};
        TextView typeBtn = new TextView(this, null, 0, R.style.YF_Chip);
        Runnable paintType = () -> typeBtn.setText(getString(R.string.netgate_proxy_type, "http".equals(type[0]) ? "HTTP" : "SOCKS5"));
        paintType.run();
        typeBtn.setOnClickListener(v -> {
            type[0] = "http".equals(type[0]) ? "socks5" : "http";
            paintType.run();
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMargins(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        f.addView(typeBtn, tl);
        EditText host = field(f, R.string.netgate_proxy_host, p.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = field(f, R.string.netgate_proxy_port, p.port > 0 ? String.valueOf(p.port) : "", InputType.TYPE_CLASS_NUMBER);
        EditText user = field(f, R.string.netgate_proxy_user, p.user, InputType.TYPE_CLASS_TEXT);
        EditText pass = field(f, R.string.netgate_proxy_pass, p.pass, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        java.util.function.Supplier<NetGateStore.Proxy> read = () -> {
            String h = host.getText().toString().trim();
            // "user:pass@host:port" pasted into the host field fills everything.
            NetGateStore.Proxy q = h.contains("@") || (h.contains(":") && port.getText().length() == 0)
                    ? NetGateStore.parse(type[0], h) : new NetGateStore.Proxy();
            if (!(h.contains("@") || (h.contains(":") && port.getText().length() == 0))) {
                q.type = type[0];
                q.host = h;
                try {
                    q.port = Integer.parseInt(port.getText().toString().trim());
                } catch (NumberFormatException e) {
                    q.port = -1;
                }
                q.user = user.getText().toString().trim();
                q.pass = pass.getText().toString();
            }
            return q;
        };
        GlassDialog.Builder b = Ui.dialog(this).setTitle(index >= 0 ? R.string.netgate_edit_proxy : R.string.netgate_add_proxy).setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    NetGateStore.Proxy q = read.get();
                    if (!q.valid()) {
                        Ui.toast(this, R.string.netgate_proxy_invalid);
                        return;
                    }
                    List<NetGateStore.Proxy> l = store.proxies();
                    if (index >= 0 && index < l.size()) l.set(index, q);
                    else l.add(q);
                    store.setProxies(l);
                    restartIfRunning();
                    build();
                })
                .setNeutralButton(R.string.netgate_test, (d, w) -> {
                    NetGateStore.Proxy q = read.get();
                    if (!q.valid()) {
                        Ui.toast(this, R.string.netgate_proxy_invalid);
                        return;
                    }
                    Ui.toast(this, R.string.netgate_testing);
                    g.io.execute(() -> {
                        String why = NetGateStore.test(q);
                        runOnUiThread(() -> Ui.toast(this, why == null ? getString(R.string.netgate_test_ok) : getString(R.string.netgate_test_failed, why)));
                    });
                });
        if (index >= 0) {
            b.setNegativeButton(R.string.delete, (d, w) -> {
                List<NetGateStore.Proxy> l = store.proxies();
                if (index < l.size()) l.remove(index);
                store.setProxies(l);
                restartIfRunning();
                build();
            });
        } else {
            b.setNegativeButton(android.R.string.cancel, null);
        }
        Ui.show(b);
    }

    private EditText field(LinearLayout f, int label, String value, int inputType) {
        TextView l = new TextView(this, null, 0, R.style.YF_Label);
        l.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 2));
        f.addView(l, lp);
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        Ui.glassField(e, Overlay.contentRoot(this));
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
        f.addView(e, ep);
        return e;
    }
}
