package com.suvo.agent.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.agent.AgentRuntime;
import com.suvo.agent.agent.ApprovalCenter;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Current task, its controls, the approval queue and the real execution log. */
public final class LiveAgentActivity extends BaseActivity {

    private final List<AgentRuntime.LogLine> lines = new ArrayList<>();
    private BaseAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderTask();
            renderTree();
            main.postDelayed(this, 1000);
        }
    };

    private final AgentListener listener = new AgentListener() {
        @Override
        public void onStatusChanged(TaskState task) {
            refresh();
        }

        @Override
        public void onToolExecuting(TaskState task, ToolCall call) {
            refresh();
        }

        @Override
        public void onToolResult(TaskState task, ToolCall call, ToolResult result) {
            refresh();
        }

        @Override
        public void onNotice(TaskState task, String notice) {
            refresh();
        }

        @Override
        public void onToolProgress(TaskState task, ToolCall call, String message) {
            refresh();
        }

        @Override
        public void onFinished(TaskState task) {
            refresh();
        }
    };

    private final com.suvo.agent.agent.AgentTree.Listener treeListener = () -> main.post(this::renderTree);

    private final ApprovalCenter.Listener approvals = pending -> main.post(() -> renderApprovals(pending));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_agent);
        setTopBar(R.string.section_agent);
        topAction(R.drawable.ic_history, R.string.agent_history, v -> showHistory());
        ListView lv = findViewById(R.id.agent_log);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return lines.size();
            }

            @Override
            public Object getItem(int p) {
                return lines.get(p);
            }

            @Override
            public long getItemId(int p) {
                return p;
            }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.item_message, parent, false);
                AgentRuntime.LogLine l = lines.get(p);
                LinearLayout bubble = v.findViewById(R.id.msg_bubble);
                bubble.setBackgroundResource(R.drawable.bubble_tool);
                bubble.setBackgroundTintList(null);
                TextView title = v.findViewById(R.id.msg_title);
                title.setVisibility(View.VISIBLE);
                title.setText(kindLabel(l.kind));
                title.setTextColor(kindColor(l.kind));
                ((TextView) v.findViewById(R.id.msg_text)).setText(l.text);
                TextView d = v.findViewById(R.id.msg_detail);
                d.setVisibility(l.detail.isEmpty() ? View.GONE : View.VISIBLE);
                d.setText(l.detail);
                d.setTextIsSelectable(!l.detail.isEmpty());
                // Full width: a wrap_content bubble would squeeze the detail box to one word per line.
                ViewGroup.LayoutParams blp = bubble.getLayoutParams();
                blp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                bubble.setLayoutParams(blp);
                ((TextView) v.findViewById(R.id.msg_time)).setText(DateFormat.getTimeFormat(LiveAgentActivity.this).format(new Date(l.time)));
                return v;
            }
        };
        lv.setAdapter(adapter);
        Button stop = findViewById(R.id.agent_stop);
        stop.setOnClickListener(v -> g.agent.cancel());
        Button pause = findViewById(R.id.agent_pause);
        pause.setOnClickListener(v -> {
            if (g.agent.isPaused()) g.agent.resume();
            else g.agent.pause();
            renderTask();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        g.agent.addListener(listener);
        g.approvals.addListener(approvals);
        com.suvo.agent.agent.AgentTree.addListener(treeListener);
        refresh();
        main.post(ticker);
    }

    @Override
    protected void onStop() {
        g.agent.removeListener(listener);
        g.approvals.removeListener(approvals);
        com.suvo.agent.agent.AgentTree.removeListener(treeListener);
        main.removeCallbacks(ticker);
        super.onStop();
    }

    private void refresh() {
        lines.clear();
        lines.addAll(g.agent.log());
        adapter.notifyDataSetChanged();
        findViewById(R.id.agent_empty).setVisibility(lines.isEmpty() ? View.VISIBLE : View.GONE);
        renderTask();
        renderTree();
    }

    // ------------------------------------------------------------------ live tree (boss → employees)

    private static final int MAX_TREE_ROWS = 9;

    private void renderTree() {
        LinearLayout box = findViewById(R.id.agent_tree);
        View title = findViewById(R.id.agent_tree_title);
        com.suvo.agent.agent.AgentTree.Node root = com.suvo.agent.agent.AgentTree.root();
        boolean show = root != null && (!g.employees.list().isEmpty() || !com.suvo.agent.agent.AgentTree.childrenOf(root.id).isEmpty());
        box.setVisibility(show ? View.VISIBLE : View.GONE);
        title.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        box.removeAllViews();
        List<Object[]> rows = new ArrayList<>();
        collect(root, 0, rows);
        int n = 0;
        for (Object[] row : rows) {
            if (n++ >= MAX_TREE_ROWS) {
                TextView more = new TextView(this, null, 0, R.style.YF_Caption);
                more.setText(getString(R.string.tree_more, rows.size() - MAX_TREE_ROWS));
                box.addView(more);
                break;
            }
            box.addView(treeRow((com.suvo.agent.agent.AgentTree.Node) row[0], (Integer) row[1]));
        }
    }

    private void collect(com.suvo.agent.agent.AgentTree.Node node, int depth, List<Object[]> out) {
        out.add(new Object[]{node, depth});
        for (com.suvo.agent.agent.AgentTree.Node c : com.suvo.agent.agent.AgentTree.childrenOf(node.id)) collect(c, depth + 1, out);
    }

    private View treeRow(com.suvo.agent.agent.AgentTree.Node node, int depth) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 22 * depth), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        if (depth > 0) {
            TextView branch = new TextView(this, null, 0, R.style.YF_Caption);
            branch.setText("└ ");
            row.addView(branch);
        }
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(stateColor(node.state));
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.topMargin = Ui.dp(this, 6);
        dlp.setMarginEnd(Ui.dp(this, 10));
        row.addView(dot, dlp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this, null, 0, R.style.YF_Body);
        name.setText(node.agent + " · " + (depth == 0 ? getString(R.string.tree_boss) : node.role));
        android.graphics.Typeface customFont = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.anthropic_serif);
        name.setTypeface(customFont, android.graphics.Typeface.BOLD);
        texts.addView(name);
        TextView what = new TextView(this, null, 0, R.style.YF_Caption);
        String line;
        if (node.state == com.suvo.agent.agent.AgentTree.State.RUNNING || node.state == com.suvo.agent.agent.AgentTree.State.WAITING) {
            String act = node.action.isEmpty() ? getString(R.string.tree_thinking) : node.action;
            line = (node.state == com.suvo.agent.agent.AgentTree.State.WAITING ? getString(R.string.tree_waiting) + " · " : "") + act;
        } else {
            line = treeStateLabel(node.state) + (node.result.isEmpty() ? "" : " — " + node.result);
        }
        long end = node.finishedAt > 0 ? node.finishedAt : System.currentTimeMillis();
        long secs = (end - node.startedAt) / 1000;
        what.setText(Com.clip(node.task, 90) + "\n" + line + "  ·  " + getString(R.string.tree_stats, node.toolCalls, secs / 60, secs % 60));
        what.setMaxLines(4);
        what.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(what);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static final class Com {
        static String clip(String s, int n) {
            String t = s == null ? "" : s.replace('\n', ' ').trim();
            return t.length() > n ? t.substring(0, n) + "…" : t;
        }
    }

    private int stateColor(com.suvo.agent.agent.AgentTree.State s) {
        switch (s) {
            case RUNNING:
                return Ui.accent(this);
            case WAITING:
                return getColor(R.color.warning);
            case DONE:
                return getColor(R.color.success);
            case FAILED:
                return getColor(R.color.danger);
            default:
                return getColor(R.color.text_secondary);
        }
    }

    private String treeStateLabel(com.suvo.agent.agent.AgentTree.State s) {
        switch (s) {
            case DONE:
                return getString(R.string.tree_done);
            case FAILED:
                return getString(R.string.tree_failed);
            case STOPPED:
                return getString(R.string.tree_stopped);
            case WAITING:
                return getString(R.string.tree_waiting);
            default:
                return getString(R.string.tree_thinking);
        }
    }

    private void renderTask() {
        TaskState t = g.agent.current();
        TextView status = findViewById(R.id.agent_status);
        TextView goal = findViewById(R.id.agent_goal);
        TextView counters = findViewById(R.id.agent_counters);
        boolean running = g.agent.isRunning();
        findViewById(R.id.agent_stop).setEnabled(running);
        Button pause = findViewById(R.id.agent_pause);
        pause.setEnabled(running);
        pause.setText(g.agent.isPaused() ? R.string.action_resume : R.string.action_pause);
        if (t == null) {
            status.setText(R.string.agent_status_idle);
            goal.setText(R.string.agent_no_task);
            counters.setText("");
            return;
        }
        status.setText(statusLabel(t.status));
        goal.setText(t.goal);
        long end = t.finishedAt > 0 ? t.finishedAt : System.currentTimeMillis();
        long secs = t.startedAt > 0 ? (end - t.startedAt) / 1000 : 0;
        counters.setText(getString(R.string.agent_counters, t.llmTurns, t.toolCalls, secs / 60, secs % 60)
                + (t.error.isEmpty() ? "" : "\n" + t.error));
    }

    private String statusLabel(TaskState.Status s) {
        switch (s) {
            case RUNNING:
                return getString(R.string.agent_status_running);
            case PAUSED:
                return getString(R.string.status_paused);
            case WAITING_APPROVAL:
                return getString(R.string.status_waiting_approval);
            case COMPLETED:
                return getString(R.string.agent_status_completed);
            case FAILED:
                return getString(R.string.agent_status_failed);
            case CANCELLED:
                return getString(R.string.agent_status_cancelled);
            case LIMIT_REACHED:
                return getString(R.string.agent_status_limit);
            default:
                return getString(R.string.agent_status_idle);
        }
    }

    private void renderApprovals(List<ApprovalCenter.Pending> pending) {
        LinearLayout box = findViewById(R.id.agent_approvals);
        box.removeAllViews();
        for (ApprovalCenter.Pending p : pending) {
            LinearLayout card = new LinearLayout(this, null, 0, R.style.YF_Glass);
            card.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this, null, 0, R.style.YF_Body);
            title.setText(getString(R.string.approval_card_title, p.request.toolName));
            android.graphics.Typeface customFont = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.anthropic_serif);
            title.setTypeface(customFont, android.graphics.Typeface.BOLD);
            card.addView(title);
            TextView body = new TextView(this, null, 0, R.style.YF_Caption);
            body.setText(p.request.summary + "\n" + getString(R.string.approval_risk, p.request.risk.name().toLowerCase()));
            card.addView(body);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
            Button approve = new Button(this, null, 0, R.style.YF_Button);
            approve.setTag("accent");
            approve.setText(R.string.action_approve);
            approve.setOnClickListener(v -> g.approvals.answer(p.id, true));
            Button deny = new Button(this, null, 0, R.style.YF_Button_Ghost);
            deny.setText(R.string.action_deny);
            deny.setOnClickListener(v -> g.approvals.answer(p.id, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            buttons.addView(approve, lp);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp2.setMarginStart(Ui.dp(this, 10));
            buttons.addView(deny, lp2);
            card.addView(buttons);
            box.addView(card);
            Ui.tintAccent(this, card);
        }
    }

    private void showHistory() {
        List<TaskState> recent = g.tasks.recent(50);
        if (recent.isEmpty()) {
            Ui.toast(this, R.string.agent_no_history);
            return;
        }
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            TaskState t = recent.get(i);
            labels[i] = statusLabel(t.status) + " · " + Ui.ago(t.startedAt) + "\n" + t.goal;
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.agent_history).setItems(labels, (d, which) -> {
            TaskState t = recent.get(which);
            StringBuilder sb = new StringBuilder(t.progressSummary(60));
            if (!t.finalText.isEmpty()) sb.append("\n\n").append(t.finalText);
            if (!t.error.isEmpty()) sb.append("\n\n").append(t.error);
            Ui.show(Ui.dialog(this).setTitle(statusLabel(t.status)).setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null));
        }).setNegativeButton(android.R.string.cancel, null));
    }

    private String kindLabel(String kind) {
        switch (kind) {
            case "tool":
                return getString(R.string.log_tool);
            case "result":
                return getString(R.string.log_result);
            case "error":
                return getString(R.string.log_error);
            case "notice":
                return getString(R.string.log_notice);
            default:
                return getString(R.string.log_status);
        }
    }

    private int kindColor(String kind) {
        switch (kind) {
            case "result":
                return getColor(R.color.success);
            case "error":
                return getColor(R.color.danger);
            case "tool":
                return Ui.accent(this);
            default:
                return getColor(R.color.text_secondary);
        }
    }
}
