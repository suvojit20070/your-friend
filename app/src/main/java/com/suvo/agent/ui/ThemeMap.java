package com.suvo.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.suvo.agent.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * Applies the runtime seed palette to views that were built from XML resources (which only know
 * the default amber scheme): text colours, icon tints and the app's tonal / glass backgrounds are
 * swapped for the palette's roles and squircle drawables. Runs on every layout pass of a screen
 * (cheap: a tree walk, and every swap is idempotent), so views added later — list rows, cards
 * built in code — get the theme and the right press too.
 */
final class ThemeMap {

    private ThemeMap() {
    }

    private interface Factory {
        void apply(View v, Palette p);
    }

    private static final class Cache {
        final Map<Drawable.ConstantState, Factory> bgs = new HashMap<>();
        final Map<Integer, Integer> colors = new HashMap<>();
        final Palette pal;

        Cache(Context c, Palette p) {
            pal = p;
            float d = c.getResources().getDisplayMetrics().density;
            put(c, R.drawable.glass_card, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceLow, 28 * d)));
            put(c, R.drawable.group_card, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceLow, 28 * d)));
            put(c, R.drawable.tile_bg, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceHigh, 20 * d)));
            put(c, R.drawable.chip, (v, q) -> v.setBackground(new TonalDrawable(Palette.alpha(q.primary, 0.12f), -1)));
            put(c, R.drawable.chip_selected, (v, q) -> v.setBackground(new TonalDrawable(q.secondaryContainer, -1)));
            put(c, R.drawable.circle_tonal, (v, q) -> v.setBackground(new TonalDrawable(Palette.alpha(q.primary, 0.12f), -1)));
            put(c, R.drawable.circle_accent, (v, q) -> v.setBackground(new TonalDrawable(q.primary, -1)));
            put(c, R.drawable.btn_accent, (v, q) -> {
                v.setBackground(new TonalDrawable(q.primary, -1));
                if (v instanceof TextView) ((TextView) v).setTextColor(q.onPrimary);
            });
            put(c, R.drawable.btn_ghost, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceHighest, -1)));
            put(c, R.drawable.btn_danger, (v, q) -> {
                v.setBackground(new TonalDrawable(q.error, -1));
                if (v instanceof TextView) ((TextView) v).setTextColor(q.dark ? 0xFF690005 : 0xFFFFFFFF);
            });
            put(c, R.drawable.nav_selected, (v, q) -> v.setBackground(new TonalDrawable(Palette.alpha(q.primary, 0.12f), -1)));
            put(c, R.drawable.bubble_tool, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceLow, 20 * d)));
            put(c, R.drawable.code_bg, (v, q) -> v.setBackground(new TonalDrawable(Palette.alpha(q.surfaceHighest, 0.6f), 16 * d)));
            put(c, R.drawable.speech_bubble, (v, q) -> v.setBackground(new TonalDrawable(q.surfaceHigh, 24 * d)));
            put(c, R.drawable.glass_pill, (v, q) -> v.setBackground(new GlassDrawable(v.getContext(), GlassDrawable.Preset.PILL)));
            put(c, R.drawable.nav_bar, (v, q) -> v.setBackground(new GlassDrawable(v.getContext(), GlassDrawable.Preset.PILL)));
            put(c, R.drawable.input_pill, (v, q) -> v.setBackground(new GlassDrawable(v.getContext(), GlassDrawable.Preset.COMPOSER)));
            put(c, R.drawable.circle_glass, (v, q) -> Shell.cornerButton(v, null));
            put(c, R.drawable.glass_dialog, (v, q) -> v.setBackground(new GlassDrawable(v.getContext(), GlassDrawable.Preset.DIALOG)));
            put(c, R.drawable.sheet_bg, (v, q) -> v.setBackground(new GlassDrawable(v.getContext(), GlassDrawable.Preset.SHEET).topOnly()));
            color(c, R.color.text_primary, p.onSurface);
            color(c, R.color.text_secondary, p.onSurfaceVariant);
            color(c, R.color.accent, p.primary);
            color(c, R.color.text_on_accent, p.onPrimary);
            color(c, R.color.section_text, Palette.alpha(p.onSurfaceVariant, 0.8f));
            color(c, R.color.bg_top, p.background);
            color(c, R.color.surface, p.surfaceLow);
            color(c, R.color.surface_2, p.surfaceHigh);
            color(c, R.color.surface_3, p.surfaceHighest);
            color(c, R.color.accent_soft, Palette.alpha(p.primary, 0.18f));
        }

        private void put(Context c, int res, Factory f) {
            Drawable dr = c.getDrawable(res);
            if (dr != null && dr.getConstantState() != null) bgs.put(dr.getConstantState(), f);
        }

        private void color(Context c, int res, int to) {
            int from = c.getColor(res);
            if (from != to) colors.put(from, to);
        }
    }

    private static Cache cache;
    private static Palette cachedFor;

    private static Cache cache(Context c) {
        Palette p = Palette.of(c);
        if (cache == null || cachedFor != p) {
            cache = new Cache(c, p);
            cachedFor = p;
        }
        return cache;
    }

    static void apply(View root) {
        if (root == null) return;
        walk(root, cache(root.getContext()));
    }

    /** Lists seen on screen; re-themed as they scroll (rows appear without a layout pass). */
    private static final java.util.WeakHashMap<android.widget.AbsListView, Boolean> LISTS = new java.util.WeakHashMap<>();

    /** Themes the rows of every visible list (called when something scrolled). */
    static void applyLists() {
        java.util.List<android.widget.AbsListView> ls;
        synchronized (LISTS) {
            ls = new java.util.ArrayList<>(LISTS.keySet());
        }
        for (android.widget.AbsListView lv : ls) {
            if (lv != null && lv.isShown()) walk(lv, cache(lv.getContext()));
        }
    }

    private static void walk(View v, Cache c) {
        // A subtree that themes itself in code (the chat) is left alone.
        if (v.getTag(R.id.theme_skip) != null) return;
        {
            Drawable bg = v.getBackground();
            if (bg != null) {
                Drawable.ConstantState cs = bg.getConstantState();
                Factory f = cs == null ? null : c.bgs.get(cs);
                if (f != null && !(v instanceof EditText)) {
                    v.setBackgroundTintList(null);
                    f.apply(v, c.pal);
                } else if (bg instanceof ColorDrawable) {
                    Integer to = c.colors.get(((ColorDrawable) bg).getColor());
                    if (to != null) v.setBackgroundColor(to);
                }
            }
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                Integer to = c.colors.get(t.getCurrentTextColor());
                if (to != null) t.setTextColor(to);
                Integer hint = c.colors.get(t.getCurrentHintTextColor());
                if (hint != null) t.setHintTextColor(Palette.alpha(hint, 0.72f));
                ColorStateList links = t.getLinkTextColors();
                if (links != null) {
                    Integer lk = c.colors.get(links.getDefaultColor());
                    if (lk != null) t.setLinkTextColor(lk);
                }
                // Selection highlight and handles (and the cursor) in the theme's accent.
                if (t.isTextSelectable() || t instanceof EditText) Ui.selectionColors(t);
            }
            if (v instanceof ImageView) {
                ImageView iv = (ImageView) v;
                ColorStateList tl = iv.getImageTintList();
                if (tl != null) {
                    Integer to = c.colors.get(tl.getDefaultColor());
                    if (to != null) iv.setImageTintList(ColorStateList.valueOf(to));
                }
            }
        }
        if (v instanceof android.widget.AbsListView && v.getTag(R.id.theme_list) == null) {
            // Lists: no dividers, and the pressed row gets the 8% tonal highlight instead of a ripple.
            android.widget.AbsListView lv = (android.widget.AbsListView) v;
            lv.setTag(R.id.theme_list, Boolean.TRUE);
            synchronized (LISTS) {
                LISTS.put(lv, Boolean.TRUE);
            }
            // New rows (added while scrolling, with no layout pass of the screen) get the theme at once.
            lv.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    walk(child, cache(child.getContext()));
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
            lv.setSelector(new TonalDrawable(0, 20 * v.getResources().getDisplayMetrics().density).pressable(c.pal.pressed()));
            if (lv instanceof android.widget.ListView) {
                ((android.widget.ListView) lv).setDivider(null);
                ((android.widget.ListView) lv).setDividerHeight(0);
            }
        }
        Drawable bgNow = v.getBackground();
        if (bgNow instanceof android.graphics.drawable.RippleDrawable && v.getTag(R.id.theme_done) != bgNow) {
            v.setTag(R.id.theme_done, bgNow); // once per drawable, not on every layout pass
            android.graphics.drawable.RippleDrawable rd = (android.graphics.drawable.RippleDrawable) bgNow;
            if (rd.getNumberOfLayers() == 0 && v.isClickable()) {
                // A bare system ripple (selectableItemBackground): the 8% row press instead.
                v.setBackground(new TonalDrawable(0, 16 * v.getResources().getDisplayMetrics().density).pressable(c.pal.pressed()));
            } else {
                rd.setColor(ColorStateList.valueOf(c.pal.pressed()));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), c);
        }
    }
}
