package com.suvo.agent.ui;

import com.suvo.agent.terminal.TerminalFeed;

import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.data.SshHostRepo;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.RuntimeManager;
import com.suvo.agent.terminal.ShellBackend;
import com.suvo.agent.tools.CodeTools;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A private, non-interactive terminal. Each command runs as a separate process in the chosen
 * backend (app sandbox shell, Termux, Shizuku or a configured SSH host); the working directory is
 * carried between commands. Interactive full-screen programs (vim, top) are not supported.
 */
public final class TerminalActivity extends BaseActivity {

    private static final String CWD_MARK = "__YF_CWD__";
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private ImageButton run;
    private TextView status;
    private String backend;     // local | termux | shizuku | ssh:<name>
    private String cwd;
    private final List<String> history = new ArrayList<>();
    private volatile CancellationToken running;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        setTopBar(R.string.section_terminal);
        topAction(R.drawable.ic_more, R.string.more, v -> menu());
        output = findViewById(R.id.term_output);
        scroll = findViewById(R.id.term_scroll);
        input = findViewById(R.id.term_input);
        run = findViewById(R.id.term_run);
        status = findViewById(R.id.term_status);
        backend = g.settings.str(Settings.SHELL_BACKEND, "local");
        cwd = g.workspace.root().getAbsolutePath();
        run.setOnClickListener(v -> {
            if (running != null) running.cancel("stopped by user");
            else submit();
        });
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                submit();
                return true;
            }
            return false;
        });
        append(getString(R.string.terminal_welcome) + "\n", getColor(R.color.term_dim));
        buildBackends();
        buildKeys();
        replayAgentRuns();
    }

    // ---- Live view of the agent's commands ------------------------------------------------------

    private final TerminalFeed.Listener feedListener = new TerminalFeed.Listener() {
        @Override
        public void onStarted(TerminalFeed.Run run) {
            runOnUiThread(() -> {
                appendAgentHeader(run);
                findViewById(R.id.term_busy).setVisibility(View.VISIBLE);
            });
        }

        @Override
        public void onFinished(TerminalFeed.Run run) {
            runOnUiThread(() -> {
                appendAgentResult(run);
                if (running == null) findViewById(R.id.term_busy).setVisibility(View.GONE);
            });
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        TerminalFeed.addListener(feedListener);
    }

    @Override
    protected void onStop() {
        TerminalFeed.removeListener(feedListener);
        super.onStop();
    }

    /** Shows what the agent ran before this screen was opened. */
    private void replayAgentRuns() {
        List<TerminalFeed.Run> runs = TerminalFeed.recent();
        if (runs.isEmpty()) return;
        append("── " + getString(R.string.terminal_agent_activity) + " ──\n", getColor(R.color.term_dim));
        for (TerminalFeed.Run r : runs) {
            appendAgentHeader(r);
            if (r.finished) appendAgentResult(r);
        }
        append("──\n", getColor(R.color.term_dim));
    }

    private void appendAgentHeader(TerminalFeed.Run r) {
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(r.startedAt));
        append("🤖 " + getString(R.string.terminal_agent) + " · " + r.source + "@" + r.backend + "  " + time + "\n", getColor(R.color.term_agent));
        append("$ " + r.command + "\n", getColor(R.color.term_blue));
    }

    private void appendAgentResult(TerminalFeed.Run r) {
        String out = r.output;
        if (!out.isEmpty()) append(out.endsWith("\n") ? out : out + "\n", getColor(R.color.term_text));
        boolean ok = r.error.isEmpty() && r.exitCode == 0;
        String tail = !r.error.isEmpty() ? "[" + r.error + "]" : "[exit " + r.exitCode + "]";
        append(tail + "\n", getColor(ok ? R.color.term_green : R.color.term_red));
    }

    // ---- Quick keys -----------------------------------------------------------------------------

    private void buildKeys() {
        LinearLayout row = findViewById(R.id.term_keys);
        row.removeAllViews();
        addKey(row, "↑ " + getString(R.string.terminal_history), v -> showHistory());
        addKey(row, "Ctrl+C", v -> {
            CancellationToken t = running;
            if (t != null) t.cancel("stopped by user");
        });
        addKey(row, "clear", v -> {
            buffer.clear();
            output.setText("");
        });
        for (String cmd : new String[]{"ls -la", "pwd", "cd ..", "pkg list-installed", "df -h"}) {
            addKey(row, cmd, v -> {
                input.setText(cmd);
                input.setSelection(cmd.length());
                submit();
            });
        }
    }

    private void addKey(LinearLayout row, String label, View.OnClickListener l) {
        TextView k = new TextView(this, null, 0, R.style.YF_Chip);
        k.setText(label);
        k.setTypeface(android.graphics.Typeface.MONOSPACE);
        k.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        row.addView(k, lp);
    }

    @Override
    protected void onDestroy() {
        CancellationToken t = running;
        if (t != null) t.cancel("screen closed");
        super.onDestroy();
    }

    // ---- Backends -------------------------------------------------------------------------------

    private void buildBackends() {
        LinearLayout row = findViewById(R.id.term_backends);
        row.removeAllViews();
        addBackendChip(row, "local", getString(R.string.terminal_backend_local));
        addBackendChip(row, "termux", "Termux");
        addBackendChip(row, "shizuku", "Shizuku");
        for (SshHostRepo.Host h : g.sshHosts.list()) addBackendChip(row, "ssh:" + h.name, "SSH · " + h.name);
        updateStatus();
    }

    private void addBackendChip(LinearLayout row, String id, String label) {
        TextView chip = new TextView(this, null, 0, R.style.YF_Chip);
        chip.setText(label);
        boolean sel = id.equals(backend);
        chip.setBackgroundResource(sel ? R.drawable.chip_selected : R.drawable.chip);
        chip.setTextColor(sel ? Ui.accent(this) : getColor(R.color.text_secondary));
        chip.setOnClickListener(v -> {
            backend = id;
            if (!id.startsWith("ssh:")) g.settings.put(Settings.SHELL_BACKEND, id);
            cwd = "termux".equals(id) ? com.suvo.agent.terminal.TermuxBackend.HOME
                    : "shizuku".equals(id) ? "/data/local/tmp" : id.startsWith("ssh:") ? "" : g.workspace.root().getAbsolutePath();
            buildBackends();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 6));
        row.addView(chip, lp);
    }

    private ShellBackend shellFor(String id) {
        switch (id) {
            case "termux":
                return g.termux;
            case "shizuku":
                return g.shizuku;
            default:
                return g.localShell();
        }
    }

    private void updateStatus() {
        String reason;
        if (backend.startsWith("ssh:")) {
            reason = null;
            status.setText(getString(R.string.terminal_status_ssh, backend.substring(4)));
        } else {
            reason = shellFor(backend).unavailableReason();
            status.setText(reason == null ? getString(R.string.terminal_status_ready, backend, cwd) : getString(R.string.terminal_status_unavailable, reason));
        }
        status.setOnClickListener(reason == null ? null : v -> {
            if ("termux".equals(backend)) PermissionFlows.fix(this, Capability.TERMUX);
            else if ("shizuku".equals(backend)) PermissionFlows.fix(this, Capability.SHIZUKU);
        });
        status.setTextColor(getColor(reason == null ? R.color.term_dim : R.color.term_yellow));
        ((TextView) findViewById(R.id.term_prompt)).setText((backend.startsWith("ssh:") ? backend.substring(4) : shortCwd()) + " $");
    }

    // ---- Running --------------------------------------------------------------------------------

    private void submit() {
        String cmd = input.getText().toString();
        if (cmd.trim().isEmpty() || running != null) return;
        input.setText("");
        history.remove(cmd);
        history.add(cmd);
        if (history.size() > 100) history.remove(0);
        String trimmed = cmd.trim();
        if (trimmed.equals("clear")) {
            buffer.clear();
            output.setText("");
            return;
        }
        append((backend.startsWith("ssh:") ? backend.substring(4) : shortCwd()) + " $ " + cmd + "\n", getColor(R.color.term_green));
        CancellationToken token = new CancellationToken();
        running = token;
        run.setImageResource(R.drawable.ic_stop);
        findViewById(R.id.term_busy).setVisibility(View.VISIBLE);
        String be = backend;
        String dir = cwd;
        g.io.execute(() -> {
            String out;
            int color = 0;
            String newCwd = null;
            try {
                if (be.startsWith("ssh:")) {
                    JSONObject args = new JSONObject().put("host", be.substring(4)).put("command", cmd).put("timeout_s", 600);
                    ToolResult r = new CodeTools.Ssh(g).execute(new ToolInvocation("term", "terminal", args, token, null));
                    out = r.toModelContent(MAX_OUTPUT_CHARS);
                    if (!r.succeeded()) color = getColor(R.color.term_red);
                } else {
                    ShellBackend sh = shellFor(be);
                    String wrapped = "cd " + ShizukuManager.shellQuote(dir) + " 2>/dev/null\n" + cmd
                            + "\n__yf_rc=$?\nprintf '\\n" + CWD_MARK + "%s' \"$(pwd)\"\nexit $__yf_rc";
                    ExecResult r = sh.run(wrapped, null, 30 * 60_000L, token);
                    String stdout = r.stdout == null ? "" : r.stdout;
                    int mark = stdout.lastIndexOf(CWD_MARK);
                    if (mark >= 0) {
                        newCwd = stdout.substring(mark + CWD_MARK.length()).trim();
                        stdout = stdout.substring(0, mark);
                        if (stdout.endsWith("\n")) stdout = stdout.substring(0, stdout.length() - 1);
                    }
                    StringBuilder sb = new StringBuilder(stdout);
                    if (r.stderr != null && !r.stderr.isEmpty()) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                        sb.append(r.stderr);
                    }
                    if (r.error != null && !r.error.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("[").append(r.error).append("]");
                    if (r.timedOut) sb.append("\n[timed out]");
                    if (r.cancelled) sb.append("\n[stopped]");
                    if (r.ran() && r.exitCode != 0) sb.append("\n[exit ").append(r.exitCode).append("]");
                    out = sb.toString();
                    if (!r.ok()) color = getColor(R.color.term_yellow);
                }
            } catch (Exception e) {
                out = "[" + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
                color = getColor(R.color.term_red);
            }
            String text = out;
            int c = color == 0 ? getColor(R.color.term_text) : color;
            String nc = newCwd;
            runOnUiThread(() -> {
                if (nc != null && !nc.isEmpty()) cwd = nc;
                if (!text.isEmpty()) append(text.endsWith("\n") ? text : text + "\n", c);
                running = null;
                run.setImageResource(R.drawable.ic_send);
                findViewById(R.id.term_busy).setVisibility(View.GONE);
                updateStatus();
            });
        });
    }

    private String shortCwd() {
        String root = g.workspace.root().getAbsolutePath();
        if (cwd.startsWith(root)) return "~ws" + cwd.substring(root.length());
        if (cwd.startsWith(com.suvo.agent.terminal.TermuxBackend.HOME)) return "~" + cwd.substring(com.suvo.agent.terminal.TermuxBackend.HOME.length());
        return cwd;
    }

    private void append(String text, int color) {
        int start = buffer.length();
        buffer.append(text);
        if (color != 0) buffer.setSpan(new ForegroundColorSpan(color), start, buffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (buffer.length() > MAX_OUTPUT_CHARS) buffer.delete(0, buffer.length() - MAX_OUTPUT_CHARS);
        output.setText(buffer);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showHistory() {
        if (history.isEmpty()) {
            Ui.toast(this, R.string.terminal_no_history);
            return;
        }
        List<String> rev = new ArrayList<>(history);
        java.util.Collections.reverse(rev);
        String[] items = rev.toArray(new String[0]);
        Ui.show(Ui.dialog(this).setTitle(R.string.terminal_history).setItems(items, (d, w) -> {
            input.setText(items[w]);
            input.setSelection(items[w].length());
        }));
    }

    // ---- Menu: runtimes, environment, SSH hosts -------------------------------------------------

    private void menu() {
        String[] items = {
                getString(R.string.terminal_runtimes),
                getString(R.string.terminal_env),
                getString(R.string.terminal_ssh_hosts),
                getString(R.string.terminal_copy_output),
                getString(R.string.terminal_clear),
        };
        Ui.show(Ui.dialog(this).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    runtimes();
                    break;
                case 1:
                    Ui.prompt(this, R.string.terminal_env, g.settings.str(Settings.TERMINAL_ENV, ""), true, v -> {
                        g.settings.put(Settings.TERMINAL_ENV, v);
                        Ui.toast(this, R.string.saved);
                    });
                    break;
                case 2:
                    sshHosts();
                    break;
                case 3:
                    Ui.copy(this, buffer.toString());
                    Ui.toast(this, R.string.copied);
                    break;
                default:
                    buffer.clear();
                    output.setText("");
            }
        }));
    }

    private void runtimes() {
        TextView body = new TextView(this, null, 0, R.style.YF_Mono);
        int p = Ui.dp(this, 20);
        body.setPadding(p, Ui.dp(this, 8), p, 0);
        body.setText(R.string.terminal_checking);
        Overlay dlg = Ui.show(Ui.dialog(this).setTitle(R.string.terminal_runtimes).setView(body)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.terminal_install, (d, w) -> installPrompt()));
        g.io.execute(() -> {
            Map<String, RuntimeManager.Runtime> map = g.runtimes.refresh(new CancellationToken());
            StringBuilder sb = new StringBuilder();
            if (map.isEmpty()) sb.append(g.runtimes.lastError().isEmpty() ? getString(R.string.terminal_runtimes_none) : g.runtimes.lastError());
            for (RuntimeManager.Runtime r : map.values()) {
                sb.append(r.installed ? "✓ " : "✗ ").append(r.label).append(" [").append(r.backend).append("]");
                if (r.installed && !r.version.isEmpty()) sb.append("  ").append(r.version);
                if (!r.installed && !r.installHint.isEmpty()) sb.append("\n    → ").append(r.installHint);
                sb.append('\n');
            }
            runOnUiThread(() -> {
                if (dlg.isShowing()) body.setText(sb);
            });
        });
    }

    private void installPrompt() {
        Ui.prompt(this, R.string.terminal_install, "", false, spec -> {
            if (spec.trim().isEmpty()) return;
            append("install " + spec + "\n", Ui.accent(this));
            CancellationToken t = new CancellationToken();
            g.io.execute(() -> {
                ExecResult r = g.runtimes.install(spec.trim(), t);
                String text = (r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)
                        + (r.error == null || r.error.isEmpty() ? "" : "\n[" + r.error + "]")
                        + "\n" + (r.ok() ? getString(R.string.terminal_install_ok) : getString(R.string.terminal_install_failed));
                runOnUiThread(() -> append(text + "\n", getColor(r.ok() ? R.color.term_text : R.color.term_red)));
            });
        });
    }

    private void sshHosts() {
        List<SshHostRepo.Host> hosts = g.sshHosts.list();
        List<String> items = new ArrayList<>();
        for (SshHostRepo.Host h : hosts) items.add(h.name + "  (" + h.username + "@" + h.host + ":" + h.port + ")");
        items.add("+ " + getString(R.string.terminal_ssh_add));
        Ui.show(Ui.dialog(this).setTitle(R.string.terminal_ssh_hosts).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == hosts.size()) editHost(null);
            else editHost(hosts.get(w));
        }));
    }

    private void editHost(SshHostRepo.Host existing) {
        SshHostRepo.Host h = existing != null ? existing : new SshHostRepo.Host();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        form.setPadding(pad, Ui.dp(this, 8), pad, 0);
        EditText name = formField(form, R.string.ssh_name, h.name, InputType.TYPE_CLASS_TEXT);
        EditText host = formField(form, R.string.ssh_host, h.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = formField(form, R.string.ssh_port, String.valueOf(h.port), InputType.TYPE_CLASS_NUMBER);
        EditText user = formField(form, R.string.ssh_user, h.username, InputType.TYPE_CLASS_TEXT);
        TextView authLabel = new TextView(this, null, 0, R.style.YF_Label);
        authLabel.setText(R.string.ssh_auth);
        form.addView(authLabel);
        Spinner auth = Widgets.styleSpinner(new Spinner(this));
        auth.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.ssh_auth_password), getString(R.string.ssh_auth_key)}));
        auth.setSelection("key".equals(h.auth) ? 1 : 0);
        form.addView(auth);
        EditText secret = formField(form, R.string.ssh_secret, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint(existing != null && !h.secret.isEmpty() ? getString(R.string.ssh_secret_keep) : getString(R.string.ssh_secret_hint));
        TextView fp = new TextView(this, null, 0, R.style.YF_Caption);
        fp.setText(h.knownHostKey.isEmpty() ? getString(R.string.ssh_key_unpinned) : getString(R.string.ssh_key_pinned, h.knownHostKey));
        form.addView(fp);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(form);
        GlassDialog.Builder b = Ui.dialog(this).setTitle(existing == null ? R.string.terminal_ssh_add : R.string.terminal_ssh_edit)
                .setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    h.name = name.getText().toString().trim();
                    h.host = host.getText().toString().trim();
                    h.username = user.getText().toString().trim();
                    try {
                        h.port = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        h.port = 22;
                    }
                    String newAuth = auth.getSelectedItemPosition() == 1 ? "key" : "password";
                    h.auth = newAuth;
                    String s = secret.getText().toString();
                    if (!s.isEmpty()) h.secret = s;
                    if (!h.name.matches("[A-Za-z0-9_.-]{1,40}") || h.host.isEmpty() || h.username.isEmpty() || h.port < 1 || h.port > 65535) {
                        Ui.toast(this, R.string.ssh_invalid);
                        return;
                    }
                    if (h.host.toLowerCase(java.util.Locale.ROOT).contains("://")) {
                        Ui.toast(this, R.string.ssh_invalid);
                        return;
                    }
                    g.sshHosts.save(h);
                    buildBackends();
                })
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) {
            b.setNeutralButton(R.string.delete, (d, w) -> {
                g.sshHosts.delete(h.id);
                if (backend.equals("ssh:" + h.name)) backend = "local";
                buildBackends();
            });
        }
        Ui.show(b);
    }

    private EditText formField(LinearLayout form, int labelRes, String value, int inputType) {
        TextView l = new TextView(this, null, 0, R.style.YF_Label);
        l.setText(labelRes);
        form.addView(l);
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        e.setInputType(inputType);
        e.setText(value);
        form.addView(e);
        return e;
    }
}
