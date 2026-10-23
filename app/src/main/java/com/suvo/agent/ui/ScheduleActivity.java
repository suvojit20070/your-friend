package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.agent.Scheduler;
import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Self-active: what the agent will start on its own (tasks it scheduled with schedule_task and
 * the optional self check-in), the exact-alarm permission, and the lessons it recorded with
 * record_mistake. Everything here can be cancelled or removed by the user.
 */
public final class ScheduleActivity extends BaseActivity {

    private static final int[] CHECKIN_HOURS = {0, 2, 4, 8, 12, 24};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.schedule_title);
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
        r.note(getString(R.string.schedule_intro));

        r.section(R.string.schedule_self);
        Scheduler.Job ci = g.scheduler.checkin();
        int hours = ci == null ? 0 : ci.everyMinutes / 60;
        r.row(R.drawable.ic_refresh, getString(R.string.schedule_checkin),
                hours == 0 ? getString(R.string.schedule_checkin_off) : getString(R.string.schedule_checkin_every, hours), null, v -> pickCheckin());
        boolean exact = g.scheduler.exactAllowed();
        r.row(R.drawable.ic_schedule, getString(R.string.schedule_exact),
                getString(exact ? R.string.schedule_exact_on : R.string.schedule_exact_off), null, exact || Build.VERSION.SDK_INT < 31 ? null : v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    }
                });
        r.parent();

        r.section(R.string.schedule_upcoming);
        List<Scheduler.Job> jobs = g.scheduler.list();
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        int shown = 0;
        for (Scheduler.Job j : jobs) {
            if ("checkin".equals(j.kind)) continue;
            shown++;
            ImageView del = new ImageView(this);
            del.setImageResource(R.drawable.ic_delete);
            del.setContentDescription(getString(R.string.schedule_cancel));
            int pad = Ui.dp(this, 8);
            del.setPadding(pad, pad, pad, pad);
            del.setOnClickListener(v -> Ui.confirm(this, R.string.schedule_cancel, j.prompt, () -> {
                g.scheduler.cancel(j.id);
                build();
            }));
            String sub = df.format(new Date(j.at)) + ("none".equals(j.repeat) ? "" : " · " + repeatLabel(j.repeat))
                    + (j.lastStatus.isEmpty() ? "" : "\n" + getString(R.string.schedule_last, j.lastStatus));
            r.row(R.drawable.ic_schedule, j.prompt, sub, del, null);
        }
        if (shown == 0) r.note(getString(R.string.schedule_none));
        r.parent();

        r.section(R.string.lessons_title);
        List<MemoryEntry> lessons = g.memory.all(MemoryCategory.LESSON, 100);
        if (lessons.isEmpty()) r.note(getString(R.string.lessons_none));
        for (MemoryEntry e : lessons) {
            ImageView del = new ImageView(this);
            del.setImageResource(R.drawable.ic_delete);
            del.setContentDescription(getString(R.string.lessons_forget));
            int pad = Ui.dp(this, 8);
            del.setPadding(pad, pad, pad, pad);
            del.setOnClickListener(v -> Ui.confirm(this, R.string.lessons_forget, e.content, () -> {
                g.memory.delete(e.id);
                build();
            }));
            r.row(R.drawable.ic_lesson, e.content, Ui.ago(e.createdAt), del, null);
        }
        r.parent();
        r.note(getString(R.string.schedule_note));
        Ui.tintAccent(this, box);
    }

    private String repeatLabel(String repeat) {
        switch (repeat) {
            case "hourly":
                return getString(R.string.repeat_hourly);
            case "daily":
                return getString(R.string.repeat_daily);
            case "weekly":
                return getString(R.string.repeat_weekly);
            default:
                return repeat;
        }
    }

    private void pickCheckin() {
        String[] labels = new String[CHECKIN_HOURS.length];
        Scheduler.Job ci = g.scheduler.checkin();
        int current = ci == null ? 0 : ci.everyMinutes / 60;
        int checked = 0;
        for (int i = 0; i < CHECKIN_HOURS.length; i++) {
            labels[i] = CHECKIN_HOURS[i] == 0 ? getString(R.string.schedule_checkin_off) : getString(R.string.schedule_checkin_every, CHECKIN_HOURS[i]);
            if (CHECKIN_HOURS[i] == current) checked = i;
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.schedule_checkin).setSingleChoiceItems(labels, checked, (d, which) -> {
            g.scheduler.setCheckin(CHECKIN_HOURS[which]);
            d.dismiss();
            build();
        }).setNegativeButton(android.R.string.cancel, null));
    }
}
