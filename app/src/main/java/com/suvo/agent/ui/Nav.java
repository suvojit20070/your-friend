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

    public static void build(Activity a, LinearLayout nav, int selected) {
        nav.removeAllViews();
        for (int i = 0; i < PRIMARY.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            if (i == selected) item.setBackgroundResource(R.drawable.nav_selected);
            ImageView iv = new ImageView(a);
            iv.setImageResource(PRIMARY[i][0]);
            if (i == selected) iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.accent(a)));
            item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            TextView t = new TextView(a, null, 0, R.style.YF_Caption);
            t.setText(PRIMARY[i][1]);
            t.setTextSize(11f);
            t.setMaxLines(1);
            item.addView(t);
            item.setContentDescription(a.getString(PRIMARY[i][1]));
            item.setOnClickListener(v -> go(a, idx, selected));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 4));
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

    @SuppressWarnings("unchecked")
    public static void showMore(Activity a) {
        Dialog d = new Dialog(a, R.style.Theme_YourFriend_Dialog);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(3);
        int pad = Ui.dp(a, 16);
        grid.setPadding(pad, pad, pad, pad);
        for (Object[] m : MORE) {
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackgroundResource(R.drawable.row_bg);
            tile.setPadding(Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14));
            ImageView iv = new ImageView(a);
            iv.setImageResource((Integer) m[0]);
            tile.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));
            TextView t = new TextView(a, null, 0, R.style.YF_Caption);
            t.setText((Integer) m[1]);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setPadding(0, Ui.dp(a, 6), 0, 0);
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
        d.setContentView(grid);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.glass_dialog);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(40);
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
