package com.suvo.agent.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;

/** The floating pill navigation and the "More" sheet listing every section. */
public final class Nav {

    private Nav() {
    }

    static final int[][] PRIMARY = {
            {R.drawable.ic_home, R.string.section_home},
            {R.drawable.ic_chat, R.string.section_chat},
            {R.drawable.ic_agent, R.string.section_agent},
            {R.drawable.ic_memory, R.string.section_memory},
            {R.drawable.ic_more, R.string.section_more},
    };

    /**
     * Floating pill navigation: the selected section shows its icon and label on a soft accent
     * pill, the others show only their icon (labels are kept for accessibility).
     */
    public static void build(Activity a, LinearLayout nav, int selected) {
        nav.removeAllViews();
        nav.setBackgroundResource(R.drawable.nav_bar);
        nav.setElevation(Ui.dp(a, 6));
        nav.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        int accent = Ui.accent(a);
        int muted = a.getColor(R.color.text_secondary);
        for (int i = 0; i < PRIMARY.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
            item.setBackgroundResource(sel ? R.drawable.nav_selected : R.drawable.row_bg);
            if (sel) item.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    com.suvo.agent.core.character.Appearance.alpha(accent, 0.18f)));
            ImageView iv = new ImageView(a);
            iv.setImageResource(PRIMARY[i][0]);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(sel ? accent : muted));
            item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            if (sel) {
                TextView t = new TextView(a, null, 0, R.style.YF_Caption);
                t.setText(PRIMARY[i][1]);
                t.setTextColor(accent);
                t.setTextSize(13.5f);
                t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                t.setMaxLines(1);
                t.setPadding(Ui.dp(a, 8), 0, 0, 0);
                item.addView(t);
            }
            item.setContentDescription(a.getString(PRIMARY[i][1]));
            item.setOnClickListener(v -> go(a, idx, selected));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, sel ? 1.9f : 1f);
            lp.setMargins(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
            nav.addView(item, lp);
        }
    }

    private static void go(Activity a, int idx, int selected) {
        if (idx == selected && idx != 4) return;
        switch (idx) {
            case 0:
                a.startActivity(new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            case 1:
                a.startActivity(new Intent(a, ChatActivity.class));
                break;
            case 2:
                a.startActivity(new Intent(a, LiveAgentActivity.class));
                break;
            case 3:
                a.startActivity(new Intent(a, MemoryActivity.class));
                break;
            default:
                showMore(a);
        }
    }

    static final Object[][] MORE = {
            {R.drawable.ic_team, R.string.section_team, TeamActivity.class},
            {R.drawable.ic_schedule, R.string.section_schedule, ScheduleActivity.class},
            {R.drawable.ic_character, R.string.section_characters, CharactersActivity.class},
            {R.drawable.ic_tools, R.string.section_tools, ToolsActivity.class},
            {R.drawable.ic_terminal, R.string.section_terminal, TerminalActivity.class},
            {R.drawable.ic_files, R.string.section_files, FilesActivity.class},
            {R.drawable.ic_voice, R.string.section_voice, VoiceActivity.class},
            {R.drawable.ic_provider, R.string.section_providers, ProvidersActivity.class},
            {R.drawable.ic_screen, R.string.section_screen, ScreenControlActivity.class},
            {R.drawable.ic_shield, R.string.section_permissions, PermissionsActivity.class},
            {R.drawable.ic_settings, R.string.section_settings, SettingsActivity.class},
            {R.drawable.ic_info, R.string.section_about, AboutActivity.class},
    };

    /** Bottom sheet with every section as a tile. */
    @SuppressWarnings("unchecked")
    public static void showMore(Activity a) {
        Dialog d = new Dialog(a, R.style.Theme_YourFriend_Dialog);
        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.sheet_bg);
        sheet.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 24));
        View handle = new View(a);
        handle.setBackgroundResource(R.drawable.sheet_handle);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(a, 14);
        sheet.addView(handle, hp);
        TextView title = new TextView(a, null, 0, R.style.YF_Title);
        title.setText(R.string.section_more);
        title.setPadding(Ui.dp(a, 6), 0, 0, Ui.dp(a, 10));
        sheet.addView(title);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(3);
        for (Object[] m : MORE) {
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackgroundResource(R.drawable.tile_bg);
            tile.setPadding(Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 14));
            ImageView iv = new ImageView(a);
            iv.setImageResource((Integer) m[0]);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.accent(a)));
            tile.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26)));
            TextView t = new TextView(a, null, 0, R.style.YF_Caption);
            t.setText((Integer) m[1]);
            t.setTextColor(a.getColor(R.color.text_primary));
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setPadding(0, Ui.dp(a, 8), 0, 0);
            tile.addView(t);
            tile.setOnClickListener(v -> {
                d.dismiss();
                a.startActivity(new Intent(a, (Class<? extends Activity>) m[2]));
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5));
            grid.addView(tile, lp);
        }
        sheet.addView(grid);
        d.setContentView(sheet);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(32);
            }
        }
        d.show();
    }

    /** Builds a status chip (used on Home). */
    public static TextView chip(Activity a, CharSequence text, int color, View.OnClickListener l) {
        TextView c = new TextView(a, null, 0, R.style.YF_Chip);
        c.setText(text);
        if (color != 0) {
            c.setBackgroundTintList(android.content.res.ColorStateList.valueOf(com.suvo.agent.core.character.Appearance.alpha(color, 0.22f)));
        }
        if (l != null) c.setOnClickListener(l);
        return c;
    }
}
