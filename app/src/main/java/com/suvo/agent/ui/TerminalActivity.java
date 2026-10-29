package com.suvo.agent.ui;

import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
import com.suvo.agent.terminal.TerminalFeed;
import com.suvo.agent.tools.CodeTools;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * A terminal that looks and feels like Termux with a Kali prompt: a black full screen, session
 * tabs for where commands run (app shell, Termux, Shizuku, SSH hosts), coloured output (ANSI
 * colours are understood), the two-line prompt
 * <pre>
 * ┌──(termux㉿yourfriend)-[~/project]
 * └─$ ls -la
 * </pre>
 * and the Termux extra-keys rows (ESC, TAB, CTRL, ALT, arrows, HOME / END, PGUP / PGDN).
 *
 * Each command runs as its own process in the chosen backend; the working directory is carried
 * between commands. Full-screen programs (vim, top) are not supported. The agent's own commands
 * appear here live, under the "agent" user.
 */
public final class TerminalActivity extends BaseActivity {

    private static final String CWD_MARK = "__YF_CWD__";
    private static final int MAX_OUTPUT_CHARS = 200_000;

    // Kali-like colours (fixed: a terminal stays dark whatever the app theme is).
    static final int BG = 0xFF0C0C0C, BAR = 0xFF171717, TEXT = 0xFFD0D0D0, DIM = 0xFF7D8794, WHITE = 0xFFF2F2F2,
            BLUE = 0xFF4D8CF5, GREEN = 0xFF57AB5A, RED = 0xFFE5534B, YELLOW = 0xFFE2C04C, PURPLE = 0xFFB083F0;

    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private TextView run;
    private TextView status;
    private TextView prompt;
    private String backend;     // local | termux | shizuku | ssh:<name>
    private String cwd;
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private volatile CancellationToken running;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private boolean ctrl, alt;
    private TextView ctrlKey, altKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        setTopBar(R.string.section_terminal);
        ImageButton more = topAction(R.drawable.ic_more, R.string.more, v -> menu());
        darkChrome(more);
        output = findViewById(R.id.term_output);
        scroll = findViewById(R.id.term_scroll);
        input = findViewById(R.id.term_input);
        run = findViewById(R.id.term_run);
        status = findViewById(R.id.term_status);
        prompt = findViewById(R.id.term_prompt);
        output.setTextColor(TEXT);
        output.setTypeface(Typeface.MONOSPACE);
        output.setHighlightColor(0x5557AB5A);
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint(R.string.terminal_hint);
        if (Build.VERSION.SDK_INT >= 29) {
            android.graphics.drawable.GradientDrawable cur = new android.graphics.drawable.GradientDrawable();
            cur.setColor(GREEN);
            cur.setSize(Ui.dp(this, 8), Ui.dp(this, 18)); // block cursor, like a terminal
            input.setTextCursorDrawable(cur);
        }
        android.widget.ProgressBar busy = findViewById(R.id.term_busy);
        busy.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        ((TextView) findViewById(R.id.term_dollar)).setText(dollar(false));
        backend = g.settings.str(Settings.SHELL_BACKEND, "local");
        cwd = defaultCwd(backend);
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
        input.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                historyStep(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                historyStep(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_TAB) {
                complete();
                return true;
            }
            if (e.isCtrlPressed() && code == KeyEvent.KEYCODE_C) {
                ctrlChar('c');
                return true;
            }
            return false;
        });
        // CTRL / ALT from the extra keys apply to the next letter typed on the keyboard.
        input.addTextChangedListener(new TextWatcher() {
            private int at = -1;
            private char typed;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                at = -1;
                if ((ctrl || alt) && count == 1 && before == 0) {
                    at = start;
                    typed = s.charAt(start);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (at < 0 || at >= s.length()) return;
                int pos = at;
                at = -1;
                s.delete(pos, pos + 1);
                char c = Character.toLowerCase(typed);
                if (ctrl) ctrlChar(c);
                else altChar(c);
            }
        });
        Ui.selectionColors(output);
        append(getString(R.string.terminal_banner), DIM);
        buildBackends();
        buildKeys();
        setRunning(false);
        replayAgentRuns();
    }

    /** Light status / navigation bar icons and light top-bar buttons on the black screen. */
    private void darkChrome(ImageButton more) {
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        View root = findViewById(R.id.term_root);
        // The terminal colours itself; the app theme pass leaves it alone.
        findViewById(R.id.term_body).setTag(R.id.theme_skip, Boolean.TRUE);
        findViewById(R.id.term_keys).setTag(R.id.theme_skip, Boolean.TRUE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            //noinspection deprecation
            root.setSystemUiVisibility(root.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        TextView title = findViewById(R.id.top_title);
        if (title != null) {
            title.setTextColor(WHITE);
            title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            title.setText("~$ terminal");
        }
        for (View b : new View[]{findViewById(R.id.top_back), more}) {
            if (b instanceof ImageButton) {
                ((ImageButton) b).setImageTintList(android.content.res.ColorStateList.valueOf(WHITE));
                b.setBackground(new TonalDrawable(0x22FFFFFF, -1).pressable(0x22FFFFFF));
            }
        }
    }

    // ---- Prompt ---------------------------------------------------------------------------------

    private String user() {
        if (backend.startsWith("ssh:")) {
            for (SshHostRepo.Host h : g.sshHosts.list()) if (backend.equals("ssh:" + h.name)) return h.username;
            return "ssh";
        }
        switch (backend) {
            case "termux":
                return "termux";
            case "shizuku":
                return "shell";
            default:
                return "app";
        }
    }

    private String host() {
        if (backend.startsWith("ssh:")) {
            for (SshHostRepo.Host h : g.sshHosts.list()) if (backend.equals("ssh:" + h.name)) return h.host;
            return backend.substring(4);
        }
        return "yourfriend";
    }

    /** ┌──(user㉿host)-[path] */
    private CharSequence promptLine(String user, String host, String path, int userColor) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        span(sb, "┌──(", BLUE, false);
        span(sb, user + "㉿" + host, userColor, true);
        span(sb, ")-[", BLUE, false);
        span(sb, path, WHITE, true);
        span(sb, "]", BLUE, false);
        return sb;
    }

    /** └─$ (red # for a stopped / root look while something runs) */
    private CharSequence dollar(boolean busy) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        span(sb, "└─", BLUE, false);
        span(sb, busy ? "#" : "$", busy ? RED : BLUE, true);
        return sb;
    }

    private static void span(SpannableStringBuilder sb, String text, int color, boolean bold) {
        int s = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String path() {
        return backend.startsWith("ssh:") && cwd.isEmpty() ? "~" : shortCwd();
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
        append("── " + getString(R.string.terminal_agent_activity) + " ──\n", DIM);
        for (TerminalFeed.Run r : runs) {
            appendAgentHeader(r);
            if (r.finished) appendAgentResult(r);
        }
        append("──\n", DIM);
    }

    private void appendAgentHeader(TerminalFeed.Run r) {
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(r.startedAt));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(promptLine(getString(R.string.terminal_agent), r.backend, r.source, PURPLE));
        span(sb, "  " + time + "\n", DIM, false);
        sb.append(dollar(false));
        span(sb, " " + r.command + "\n", WHITE, false);
        appendStyled(sb);
    }

    private void appendAgentResult(TerminalFeed.Run r) {
        String out = r.output;
        if (!out.isEmpty()) appendAnsi(out.endsWith("\n") ? out : out + "\n");
        boolean ok = r.error.isEmpty() && r.exitCode == 0;
        if (!ok) append((!r.error.isEmpty() ? "[" + r.error + "]" : "[exit " + r.exitCode + "]") + "\n", r.error.isEmpty() ? YELLOW : RED);
        append("\n", TEXT);
    }

    // ---- Extra keys (Termux) --------------------------------------------------------------------

    private void buildKeys() {
        LinearLayout keys = findViewById(R.id.term_keys);
        keys.removeAllViews();
        String[][] rows = {
                {"ESC", "/", "-", "HOME", "↑", "END", "PGUP"},
                {"TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : row) {
                TextView key = new TextView(this);
                key.setText(k);
                key.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                key.setTextSize(k.length() > 2 ? 12f : 15f);
                key.setTextColor(WHITE);
                key.setGravity(Gravity.CENTER);
                key.setBackground(new TonalDrawable(0, Ui.dp(this, 10)).pressable(0x33FFFFFF));
                key.setClickable(true);
                key.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    onKey(k);
                });
                if (k.equals("↑") || k.equals("↓") || k.equals("←") || k.equals("→")) {
                    // Arrows repeat while held.
                    key.setOnLongClickListener(v -> {
                        repeat(v, k);
                        return true;
                    });
                }
                if (k.equals("CTRL")) ctrlKey = key;
                if (k.equals("ALT")) altKey = key;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
                lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
                line.addView(key, lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            keys.addView(line, rp);
        }
    }

    private void repeat(View v, String k) {
        onKey(k);
        if (v.isPressed()) v.postDelayed(() -> repeat(v, k), 70);
    }

    private void onKey(String k) {
        int sel = Math.max(0, input.getSelectionStart());
        Editable t = input.getText();
        switch (k) {
            case "ESC":
                t.clear();
                historyPos = -1;
                setCtrl(false);
                setAlt(false);
                break;
            case "TAB":
                complete();
                break;
            case "CTRL":
                setCtrl(!ctrl);
                if (ctrl) Ui.toast(this, R.string.terminal_ctrl_hint);
                break;
            case "ALT":
                setAlt(!alt);
                break;
            case "HOME":
                input.setSelection(0);
                break;
            case "END":
                input.setSelection(t.length());
                break;
            case "←":
                input.setSelection(Math.max(0, sel - 1));
                break;
            case "→":
                input.setSelection(Math.min(t.length(), sel + 1));
                break;
            case "↑":
                historyStep(-1);
                break;
            case "↓":
                historyStep(1);
                break;
            case "PGUP":
                scroll.smoothScrollBy(0, -scroll.getHeight() * 4 / 5);
                break;
            case "PGDN":
                scroll.smoothScrollBy(0, scroll.getHeight() * 4 / 5);
                break;
            default:
                if (ctrl || alt) {
                    char c = k.charAt(0);
                    if (ctrl) ctrlChar(c);
                    else altChar(c);
                } else {
                    t.insert(sel, k);
                }
        }
        input.requestFocus();
    }

    private void setCtrl(boolean on) {
        ctrl = on;
        if (on) alt = false;
        paintModifiers();
    }

    private void setAlt(boolean on) {
        alt = on;
        if (on) ctrl = false;
        paintModifiers();
    }

    private void paintModifiers() {
        if (ctrlKey != null) {
            ctrlKey.setTextColor(ctrl ? BG : WHITE);
            ctrlKey.setBackground(new TonalDrawable(ctrl ? GREEN : 0, Ui.dp(this, 10)).pressable(0x33FFFFFF));
        }
        if (altKey != null) {
            altKey.setTextColor(alt ? BG : WHITE);
            altKey.setBackground(new TonalDrawable(alt ? GREEN : 0, Ui.dp(this, 10)).pressable(0x33FFFFFF));
        }
    }

    /** CTRL + letter: C stop, L clear screen, D stop, U clear line, A / E start / end of line. */
    private void ctrlChar(char c) {
        setCtrl(false);
        Editable t = input.getText();
        switch (c) {
            case 'c':
            case 'd': {
                CancellationToken tk = running;
                if (tk != null) {
                    tk.cancel("stopped by user");
                    append("^" + Character.toUpperCase(c) + "\n", RED);
                } else {
                    append(promptText() + t + "^" + Character.toUpperCase(c) + "\n", DIM);
                    t.clear();
                }
                break;
            }
            case 'l':
                clearScreen();
                break;
            case 'u':
                t.delete(0, Math.max(0, input.getSelectionStart()));
                break;
            case 'a':
                input.setSelection(0);
                break;
            case 'e':
                input.setSelection(t.length());
                break;
            case 'w': {
                int end = Math.max(0, input.getSelectionStart());
                int s = end;
                while (s > 0 && t.charAt(s - 1) == ' ') s--;
                while (s > 0 && t.charAt(s - 1) != ' ') s--;
                t.delete(s, end);
                break;
            }
            default:
                break;
        }
    }

    /** ALT + b / f: a word back / forward; other keys are typed as they are. */
    private void altChar(char c) {
        setAlt(false);
        Editable t = input.getText();
        int p = Math.max(0, input.getSelectionStart());
        if (c == 'b') {
            while (p > 0 && t.charAt(p - 1) == ' ') p--;
            while (p > 0 && t.charAt(p - 1) != ' ') p--;
            input.setSelection(p);
        } else if (c == 'f') {
            while (p < t.length() && t.charAt(p) == ' ') p++;
            while (p < t.length() && t.charAt(p) != ' ') p++;
            input.setSelection(p);
        } else {
            t.insert(p, String.valueOf(c));
        }
    }

    /** TAB: completes from history and common commands (the shell itself is not interactive). */
    private void complete() {
        String cur = input.getText().toString();
        if (cur.isEmpty()) return;
        List<String> pool = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) pool.add(history.get(i));
        for (String c : new String[]{"ls -la", "cd ", "pwd", "cat ", "pkg install ", "pkg update", "pip install ", "python3 ",
                "git clone ", "git status", "nano ", "mkdir -p ", "rm -rf ", "chmod +x ", "termux-setup-storage", "df -h", "uname -a",
                "whoami", "ps aux", "top -n 1", "curl -I ", "ping -c 4 "}) {
            pool.add(c);
        }
        List<String> hits = new ArrayList<>();
        for (String p : pool) if (p.startsWith(cur) && !p.equals(cur) && !hits.contains(p)) hits.add(p);
        if (hits.isEmpty()) return;
        if (hits.size() == 1) {
            input.setText(hits.get(0));
        } else {
            // Longest common prefix, and show the choices like a shell does.
            String common = hits.get(0);
            for (String h : hits) {
                int i = 0;
                while (i < common.length() && i < h.length() && common.charAt(i) == h.charAt(i)) i++;
                common = common.substring(0, i);
            }
            if (common.length() > cur.length()) input.setText(common);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.size() && i < 12; i++) sb.append(hits.get(i)).append("    ");
            append(sb.toString().trim() + "\n", DIM);
        }
        input.setSelection(input.getText().length());
    }

    private void historyStep(int dir) {
        if (history.isEmpty()) return;
        if (historyPos < 0) historyPos = history.size();
        historyPos = Math.max(0, Math.min(history.size(), historyPos + dir));
        String v = historyPos >= history.size() ? "" : history.get(historyPos);
        input.setText(v);
        input.setSelection(v.length());
    }

    @Override
    protected void onDestroy() {
        CancellationToken t = running;
        if (t != null) t.cancel("screen closed");
        super.onDestroy();
    }

    // ---- Sessions (backends) --------------------------------------------------------------------

    private void buildBackends() {
        LinearLayout row = findViewById(R.id.term_backends);
        row.removeAllViews();
        int n = 1;
        addBackendTab(row, n++, "local", getString(R.string.terminal_backend_local));
        addBackendTab(row, n++, "termux", "termux");
        addBackendTab(row, n++, "shizuku", "shizuku");
        for (SshHostRepo.Host h : g.sshHosts.list()) addBackendTab(row, n++, "ssh:" + h.name, "ssh:" + h.name);
        updateStatus();
    }

    /** tmux-like tab: "[1:termux]", the current one inverse green. */
    private void addBackendTab(LinearLayout row, int n, String id, String label) {
        TextView tab = new TextView(this);
        boolean sel = id.equals(backend);
        tab.setText(n + ":" + label + (sel ? "*" : ""));
        tab.setTypeface(Typeface.MONOSPACE, sel ? Typeface.BOLD : Typeface.NORMAL);
        tab.setTextSize(12f);
        tab.setTextColor(sel ? BG : DIM);
        tab.setBackground(new TonalDrawable(sel ? GREEN : 0x14FFFFFF, Ui.dp(this, 6)).pressable(0x33FFFFFF));
        tab.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        tab.setClickable(true);
        tab.setOnClickListener(v -> {
            if (id.equals(backend)) return;
            backend = id;
            if (!id.startsWith("ssh:")) g.settings.put(Settings.SHELL_BACKEND, id);
            cwd = defaultCwd(id);
            buildBackends();
            append("[session " + n + ": " + label + "]\n", DIM);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, n == 1 ? 10 : 3), Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 2));
        row.addView(tab, lp);
    }

    private String defaultCwd(String id) {
        return "termux".equals(id) ? com.suvo.agent.terminal.TermuxBackend.HOME
                : "shizuku".equals(id) ? "/data/local/tmp" : id.startsWith("ssh:") ? "" : g.workspace.root().getAbsolutePath();
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
        status.setTextColor(reason == null ? DIM : YELLOW);
        prompt.setText(promptLine(user(), host(), path(), BLUE));
    }

    private String promptText() {
        return "└─$ ";
    }

    // ---- Running --------------------------------------------------------------------------------

    private void submit() {
        String cmd = input.getText().toString();
        if (running != null) return;
        String trimmed = cmd.trim();
        // The command is echoed with the full Kali prompt above it.
        SpannableStringBuilder echo = new SpannableStringBuilder();
        echo.append(promptLine(user(), host(), path(), BLUE)).append('\n');
        echo.append(dollar(false));
        span(echo, " " + cmd + "\n", WHITE, false);
        appendStyled(echo);
        input.setText("");
        historyPos = -1;
        if (trimmed.isEmpty()) return;
        history.remove(cmd);
        history.add(cmd);
        if (history.size() > 200) history.remove(0);
        if (trimmed.equals("clear") || trimmed.equals("cls")) {
            clearScreen();
            return;
        }
        if (trimmed.equals("exit")) {
            finish();
            return;
        }
        CancellationToken token = new CancellationToken();
        running = token;
        setRunning(true);
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
                    if (!r.succeeded()) color = RED;
                } else {
                    ShellBackend sh = shellFor(be);
                    // Colours on, like an interactive terminal (ls, grep, git…).
                    String wrapped = "export TERM=xterm-256color COLORTERM=truecolor CLICOLOR=1 CLICOLOR_FORCE=1 2>/dev/null\n"
                            + "shopt -s expand_aliases 2>/dev/null\n"
                            + "alias ls='ls --color=always' grep='grep --color=always' 2>/dev/null\n"
                            + "cd " + ShizukuManager.shellQuote(dir) + " 2>/dev/null\n" + cmd
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
                    if (!r.jobId.isEmpty()) sb.append("\n[still running in Termux as job ").append(r.jobId).append("]");
                    else if (r.timedOut) sb.append("\n[timed out]");
                    if (r.cancelled) sb.append("\n^C");
                    if (r.ran() && r.exitCode != 0 && r.jobId.isEmpty() && !r.cancelled) sb.append("\n[exit ").append(r.exitCode).append("]");
                    out = sb.toString();
                    if (!r.ran()) color = RED;
                }
            } catch (Exception e) {
                out = "[" + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
                color = RED;
            }
            String text = out;
            int c = color;
            String nc = newCwd;
            runOnUiThread(() -> {
                if (nc != null && !nc.isEmpty()) cwd = nc;
                if (!text.isEmpty()) {
                    String t = text.endsWith("\n") ? text : text + "\n";
                    if (c == 0) appendAnsi(t);
                    else append(t, c);
                }
                running = null;
                setRunning(false);
                updateStatus();
            });
        });
    }

    private void setRunning(boolean on) {
        run.setText(on ? "■ ^C" : "⏎");
        run.setTextColor(on ? RED : GREEN);
        run.setBackground(new TonalDrawable(on ? 0x22E5534B : 0x1A57AB5A, Ui.dp(this, 8)).pressable(0x33FFFFFF));
        findViewById(R.id.term_busy).setVisibility(on ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.term_dollar)).setText(dollar(on));
    }

    private void clearScreen() {
        buffer.clear();
        output.setText("");
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
        show();
    }

    private void appendAnsi(String text) {
        Ansi.append(buffer, text, TEXT);
        show();
    }

    private void appendStyled(CharSequence text) {
        buffer.append(text);
        show();
    }

    private void show() {
        if (buffer.length() > MAX_OUTPUT_CHARS) buffer.delete(0, buffer.length() - MAX_OUTPUT_CHARS);
        output.setText(buffer);
        // Scroll without moving focus (fullScroll would take the keyboard away from the input).
        scroll.post(() -> scroll.scrollTo(0, output.getBottom()));
    }

    // ---- Menu: runtimes, environment, SSH hosts -------------------------------------------------

    private void menu() {
        String[] items = {
                getString(R.string.terminal_history),
                getString(R.string.terminal_runtimes),
                getString(R.string.terminal_env),
                getString(R.string.terminal_ssh_hosts),
                getString(R.string.terminal_copy_output),
                getString(R.string.terminal_clear),
        };
        Ui.show(Ui.dialog(this).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    showHistory();
                    break;
                case 1:
                    runtimes();
                    break;
                case 2:
                    Ui.prompt(this, R.string.terminal_env, g.settings.str(Settings.TERMINAL_ENV, ""), true, v -> {
                        g.settings.put(Settings.TERMINAL_ENV, v);
                        Ui.toast(this, R.string.saved);
                    });
                    break;
                case 3:
                    sshHosts();
                    break;
                case 4:
                    Ui.copy(this, buffer.toString());
                    break;
                default:
                    clearScreen();
            }
        }));
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
            append("$ pkg install " + spec + "\n", GREEN);
            CancellationToken t = new CancellationToken();
            g.io.execute(() -> {
                ExecResult r = g.runtimes.install(spec.trim(), t);
                String text = (r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)
                        + (r.error == null || r.error.isEmpty() ? "" : "\n[" + r.error + "]")
                        + "\n" + (r.ok() ? getString(R.string.terminal_install_ok) : getString(R.string.terminal_install_failed));
                runOnUiThread(() -> {
                    if (r.ok()) appendAnsi(text + "\n");
                    else append(text + "\n", RED);
                });
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
                    h.auth = auth.getSelectedItemPosition() == 1 ? "key" : "password";
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
