package com.suvo.agent.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.agent.Team;
import com.suvo.agent.core.office.OfficeScene;
import com.suvo.agent.core.tools.ResourceGuard;
import com.suvo.agent.data.EmployeeRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * The team office: a 2D, game-style, landscape picture of who is doing what right now
 * ({@link OfficeScene}). The boss sits on the left, every member at a desk; a member that uses the
 * phone, the browser or the camera walks to it, one that has to wait queues beside it. Tap a
 * member for its job (and to stop it), tap the boss to go to the chat.
 */
public final class OfficeActivity extends BaseActivity {

    private OfficeView office;
    private TextView stopAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        office = new OfficeView(this);
        root.addView(office, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating controls: back on the left, "Stop all" on the right (each with its own margins).
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setContentDescription(getString(R.string.back));
        back.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(this, 10);
        back.setPadding(pad, pad, pad, pad);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.of(this).onSurface));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.BOTTOM | Gravity.START);
        bl.setMargins(Ui.dp(this, 16), 0, 0, Ui.dp(this, 14));
        root.addView(back, bl);
        Shell.cornerButton(back, null);
        back.setOnClickListener(v -> finish());

        Palette pal = Palette.of(this);
        stopAll = new TextView(this);
        stopAll.setText(R.string.office_stop_all);
        stopAll.setTextSize(13f);
        stopAll.setTypeface(Typeface.DEFAULT_BOLD);
        stopAll.setTextColor(pal.dark ? 0xFF690005 : 0xFFFFFFFF);
        stopAll.setBackground(new TonalDrawable(pal.error, -1));
        stopAll.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        sl.setMargins(Ui.dp(this, 72), 0, 0, Ui.dp(this, 18));
        root.addView(stopAll, sl);
        stopAll.setOnClickListener(v -> Ui.confirm(this, R.string.office_stop_all, getString(R.string.office_stop_all_confirm), () -> {
            g.agent.cancelAll();
            Ui.toast(this, R.string.done);
        }));
        stopAll.setVisibility(View.GONE);

        super.setContentView(root);
        Fx.attachAll(root);
        // A game screen: the system bars step aside (swipe from an edge to see them).
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController wc = getWindow().getInsetsController();
            if (wc != null) {
                wc.hide(WindowInsets.Type.systemBars());
                wc.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    private final Team.Listener teamListener = () -> {
        if (office != null) office.postInvalidateOnAnimation();
    };

    @Override
    protected void onStart() {
        super.onStart();
        Team.addListener(teamListener);
        office.running = true;
        office.invalidate();
    }

    @Override
    protected void onStop() {
        Team.removeListener(teamListener);
        office.running = false;
        super.onStop();
    }

    /** "screen_operation_accessibility" → "using the screen": what a person would say. */
    static String readable(String tool) {
        if (tool == null || tool.isEmpty()) return "";
        switch (tool) {
            case "screen_operation_accessibility":
            case "screen_operation_shell":
                return "using the screen";
            case "screenshot":
                return "looking at the screen";
            case "launch_app":
                return "opening an app";
            case "open_uri":
                return "opening a link";
            case "camera_capture":
                return "taking a photo";
            case "py_web_search":
                return "searching the web";
            case "py_web_read":
                return "reading a page";
            case "py_download_file":
                return "downloading";
            case "browser":
            case "web_session":
                return "in the browser";
            case "shell":
            case "termux_job":
                return "in the terminal";
            case "run_code":
                return "running a script";
            case "file_read":
            case "file_list":
            case "file_search":
            case "file_info":
                return "reading files";
            case "file_write":
            case "file_edit":
            case "file_manage":
                return "writing files";
            case "memory":
                return "checking memory";
            case "wait":
                return "waiting";
            case "thinking":
                return "thinking";
            default:
                String t = tool.startsWith("py_") ? tool.substring(3) : tool;
                return t.replace('_', ' ').toLowerCase(Locale.ROOT);
        }
    }

    private void showMember(long id) {
        EmployeeRepo.Employee e = g.employees.get(id);
        if (e == null) return;
        Team.Job j = Team.latestFor(id);
        StringBuilder sb = new StringBuilder();
        if (!e.role.isEmpty()) sb.append(e.role).append("\n\n");
        if (j == null) {
            sb.append(getString(R.string.office_no_job));
        } else {
            long end = j.finishedAt > 0 ? j.finishedAt : System.currentTimeMillis();
            sb.append(getString(R.string.office_job, j.id)).append(" · ").append(statusLabel(j)).append(" · ")
                    .append(android.text.format.DateUtils.formatElapsedTime((end - j.startedAt) / 1000)).append(" · ")
                    .append(getResources().getQuantityString(R.plurals.office_steps, j.toolCalls, j.toolCalls)).append("\n\n");
            sb.append(j.briefShort(700));
            if (j.running()) {
                sb.append("\n\n").append(getString(R.string.office_now)).append(' ')
                        .append(j.waitingApproval ? getString(R.string.office_needs_ok)
                                : !j.waitingFor.isEmpty() ? getString(R.string.office_waiting, thing(j.waitingFor)) + " (" + j.waitingOn + ")"
                                : readable(j.action));
            } else if (!j.report.trim().isEmpty() || !j.error.isEmpty()) {
                String r = Team.DONE.equals(j.status) ? j.report.trim() : j.error + (j.report.trim().isEmpty() ? "" : "\n\n" + j.report.trim());
                sb.append("\n\n").append(r.length() > 3000 ? r.substring(0, 3000) + "…" : r);
            }
        }
        GlassDialog.Builder b = Ui.dialog(this).setTitle(e.name).setMessage(sb.toString());
        if (j != null && j.running()) {
            final int jobId = j.id;
            b.setPositiveButton(getString(R.string.office_stop_job), (d, w) -> Team.stop(jobId));
            b.setNegativeButton(getString(android.R.string.cancel), null);
        } else {
            b.setPositiveButton(getString(android.R.string.ok), null);
        }
        Ui.show(b);
    }

    private String statusLabel(Team.Job j) {
        switch (j.status) {
            case Team.DONE:
                return getString(R.string.office_done);
            case Team.FAILED:
                return getString(R.string.office_failed);
            case Team.STOPPED:
                return getString(R.string.office_stopped);
            default:
                return getString(R.string.office_working);
        }
    }

    private String thing(String t) {
        return getString(ResourceGuard.SCREEN.equals(t) ? R.string.office_phone : ResourceGuard.BROWSER.equals(t)
                ? R.string.office_browser : R.string.office_camera).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------------- the view

    private final class OfficeView extends View implements OfficeScene.Pen {
        final OfficeScene scene = new OfficeScene();
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();
        final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        Canvas cv;
        boolean running;
        long lastData;

        OfficeView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            Palette p = Palette.of(c);
            OfficeScene.Colors k = scene.c;
            k.dark = p.dark;
            k.wall = p.dark ? mix(p.surfaceHigh, 0xFF000000, p.dim ? 0.45f : 0.1f) : p.surfaceLow;
            k.wallTrim = p.dark ? p.surfaceHighest : mix(p.outlineVariant, p.surfaceHigh, 0.4f);
            k.floorA = p.dark ? (p.dim ? 0xFF000000 : p.background) : p.surfaceHigh;
            k.floorB = p.dark ? (p.dim ? 0xFF070707 : p.surfaceLow) : mix(p.surfaceHigh, p.surfaceHighest, 0.6f);
            k.desk = p.dark ? 0xFF8A6A4C : 0xFFC89B6D;
            k.deskFront = p.dark ? 0xFF6E533B : 0xFFA97F55;
            k.text = p.dark ? 0xFFF3ECE4 : 0xFF2B2018;
            k.textDim = p.onSurfaceVariant;
            k.accent = p.primary;
            k.onAccent = p.onPrimary;
            k.panel = p.dark ? p.surfaceHighest : 0xFFFFFFFF;
            k.panelText = p.onSurface;
            OfficeScene.Labels l = scene.l;
            l.boss = getString(R.string.office_boss);
            l.phone = getString(R.string.office_phone);
            l.browser = getString(R.string.office_browser);
            l.camera = getString(R.string.office_camera);
            l.thinking = getString(R.string.office_thinking);
            l.waiting = getString(R.string.office_waiting);
            l.needsOk = getString(R.string.office_needs_ok);
            l.done = getString(R.string.office_done);
            l.failed = getString(R.string.office_failed);
            l.working = getString(R.string.office_working);
            l.free = getString(R.string.office_free);
            l.board = getString(R.string.office_board);
            l.steps = getString(R.string.office_steps_short);
            l.talking = getString(R.string.office_talking);
            l.reading = getString(R.string.office_reading);
        }

        private int mix(int a, int b, float t) {
            int rr = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
            int gg = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
            int bb = Math.round((a & 255) * (1 - t) + (b & 255) * t);
            return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }

        /** The team's state, read a few times a second (the picture itself moves every frame). */
        private void refresh(long now) {
            if (now - lastData < 300) return;
            lastData = now;
            List<OfficeScene.Member> out = new ArrayList<>();
            int working = 0;
            for (EmployeeRepo.Employee e : g.employees.enabled()) {
                OfficeScene.Member m = new OfficeScene.Member();
                m.id = e.id;
                m.name = e.name;
                m.role = e.role;
                Team.Job j = Team.latestFor(e.id);
                if (j != null) {
                    m.jobId = j.id;
                    m.steps = j.toolCalls;
                    m.finishedAt = j.finishedAt;
                    if (j.running()) {
                        working++;
                        m.holds = ResourceGuard.SHARED.heldBy(j.taskId);
                        if (j.waitingApproval) m.state = OfficeScene.WAITING_OK;
                        else if (!j.waitingFor.isEmpty()) {
                            m.state = OfficeScene.WAITING_THING;
                            m.waitsFor = j.waitingFor;
                            m.waitsOn = j.waitingOn;
                        } else {
                            m.state = OfficeScene.WORKING;
                            m.doing = readable(j.action);
                        }
                    } else if (Team.DONE.equals(j.status)) {
                        m.state = OfficeScene.DONE;
                    } else if (Team.FAILED.equals(j.status)) {
                        m.state = OfficeScene.FAILED;
                    }
                }
                out.add(m);
            }
            scene.bossName = g.characters.active().name;
            scene.update(out, g.agent.isRunning(), "");
            int vis = working > 0 ? View.VISIBLE : View.GONE;
            if (stopAll.getVisibility() != vis) stopAll.setVisibility(vis);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            cv = canvas;
            long now = System.currentTimeMillis();
            refresh(now);
            scene.draw(this, getWidth(), getHeight(), now);
            cv = null;
            if (running) postInvalidateOnAnimation();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                long id = scene.hit(e.getX(), e.getY());
                if (id == -1) {
                    startActivity(new Intent(OfficeActivity.this, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } else if (id != Long.MIN_VALUE) {
                    showMember(id);
                }
            }
            return true;
        }

        // ---- OfficeScene.Pen

        @Override
        public void rect(float x, float y, float w, float h, float radius, int color) {
            fill.setColor(color);
            if (radius <= 0) {
                cv.drawRect(x, y, x + w, y + h, fill);
            } else {
                r.set(x, y, x + w, y + h);
                cv.drawRoundRect(r, radius, radius, fill);
            }
        }

        @Override
        public void oval(float cx, float cy, float rx, float ry, int color) {
            fill.setColor(color);
            r.set(cx - rx, cy - ry, cx + rx, cy + ry);
            cv.drawOval(r, fill);
        }

        @Override
        public void line(float x1, float y1, float x2, float y2, float width, int color) {
            stroke.setColor(color);
            stroke.setStrokeWidth(width);
            cv.drawLine(x1, y1, x2, y2, stroke);
        }

        @Override
        public void tri(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
            fill.setColor(color);
            path.rewind();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
            cv.drawPath(path, fill);
        }

        @Override
        public void text(String s, float x, float y, float size, int color, int align, boolean isBold) {
            text.setColor(color);
            text.setTextSize(size);
            text.setTypeface(isBold ? bold : Typeface.DEFAULT);
            text.setTextAlign(align == 1 ? Paint.Align.CENTER : Paint.Align.LEFT);
            cv.drawText(s, x, y, text);
        }

        @Override
        public float textWidth(String s, float size, boolean isBold) {
            text.setTextSize(size);
            text.setTypeface(isBold ? bold : Typeface.DEFAULT);
            return text.measureText(s);
        }
    }
}
