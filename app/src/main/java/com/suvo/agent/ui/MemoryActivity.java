package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryRanker;
import com.suvo.agent.core.memory.SecretFilter;
import com.suvo.agent.agent.AndroidAgentHost;
import com.suvo.agent.device.ImageUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Browse, search, edit, pin, delete, export and import long-term memories. */
public final class MemoryActivity extends BaseActivity {

    private static final int REQ_EXPORT = 81;
    private static final int REQ_IMPORT = 82;

    private EditText search;
    private MemoryCategory filter;
    private LinearLayout list;
    private TextView chipAll;
    private final List<TextView> chips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_memory);
        topAction(R.drawable.ic_add, R.string.memory_add, v -> edit(null));
        LinearLayout box = findViewById(R.id.rows);
        Rows r = new Rows(this, box);
        r.note(getString(R.string.memory_intro));

        search = new EditText(this, null, 0, R.style.YF_Input);
        search.setHint(R.string.memory_search_hint);
        search.setSingleLine(true);
        box.addView(search, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        hs.addView(chipRow);
        box.addView(hs);
        chipAll = makeChip(chipRow, getString(R.string.memory_all), null);
        for (MemoryCategory c : MemoryCategory.values()) makeChip(chipRow, categoryLabel(c), c);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        Rows tail = new Rows(this, box);
        tail.section(R.string.memory_manage);
        tail.row(R.drawable.ic_upload, getString(R.string.memory_export), getString(R.string.memory_export_desc), null, v ->
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "yourfriend-memories.json"), REQ_EXPORT));
        tail.row(R.drawable.ic_download, getString(R.string.memory_import), getString(R.string.memory_import_desc), null, v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json"), REQ_IMPORT));
        tail.row(R.drawable.ic_history, getString(R.string.memory_prune), null, null, v -> {
            int n = g.memory.pruneExpired();
            Ui.toast(this, getResources().getQuantityString(R.plurals.memories_removed, n, n));
            refresh();
        });
        tail.row(R.drawable.ic_delete, getString(R.string.memory_delete_all), null, null, v ->
                Ui.confirm(this, R.string.memory_delete_all, getString(R.string.memory_delete_all_confirm), () -> {
                    int n = g.memory.deleteAll();
                    Ui.toast(this, getResources().getQuantityString(R.plurals.memories_removed, n, n));
                    refresh();
                }));
        Ui.tintAccent(this, box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private TextView makeChip(LinearLayout row, String label, MemoryCategory cat) {
        TextView t = new TextView(this, null, 0, R.style.YF_Chip);
        t.setText(label);
        t.setTag(R.id.rows, cat);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 6));
        row.addView(t, lp);
        t.setOnClickListener(v -> {
            filter = cat;
            refresh();
        });
        chips.add(t);
        return t;
    }

    static String categoryLabel(MemoryCategory c) {
        String s = c.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void refresh() {
        if (list == null) return;
        for (TextView c : chips) c.setAlpha(c.getTag(R.id.rows) == filter ? 1f : 0.55f);
        list.removeAllViews();
        Rows r = new Rows(this, list);

        AndroidAgentHost host = g.agent.host();
        if (host != null && !host.lastRetrieved.isEmpty() && search.getText().length() == 0 && filter == null) {
            r.section(R.string.memory_used_last);
            for (MemoryRanker.Scored s : host.lastRetrieved) {
                r.row(R.drawable.ic_eye, TextUtilShort.clip(s.entry.content, 140),
                        getString(R.string.memory_why, s.why, String.format(Locale.ROOT, "%.2f", s.score)), null, v -> edit(s.entry));
            }
        }

        String q = search.getText().toString().trim();
        List<MemoryEntry> items = q.isEmpty() ? g.memory.all(filter, 500) : g.memory.searchCandidates(q, filter, 200);
        r.section(R.string.memory_saved);
        r.note(getResources().getQuantityString(R.plurals.memories_saved, g.memory.count(), g.memory.count()));
        if (items.isEmpty()) r.note(getString(q.isEmpty() ? R.string.memory_empty : R.string.memory_no_match));
        long now = System.currentTimeMillis();
        for (MemoryEntry e : items) {
            ImageView pin = new ImageView(this);
            pin.setImageResource(e.pinned ? R.drawable.ic_star_filled : R.drawable.ic_star);
            pin.setContentDescription(getString(R.string.memory_pin));
            int p = Ui.dp(this, 8);
            pin.setPadding(p, p, p, p);
            pin.setOnClickListener(v -> {
                e.pinned = !e.pinned;
                e.updatedAt = System.currentTimeMillis();
                g.memory.update(e);
                refresh();
            });
            StringBuilder sub = new StringBuilder(categoryLabel(e.category)).append(" · ★").append(e.importance)
                    .append(" · ").append(Ui.ago(e.updatedAt));
            if (!e.tags.isEmpty()) sub.append(" · #").append(String.join(" #", e.tags));
            if (e.useCount > 0) sub.append(" · ").append(getResources().getQuantityString(R.plurals.memories_used, e.useCount, e.useCount));
            if (e.isExpired(now)) sub.append(" · ").append(getString(R.string.memory_expired));
            r.row(R.drawable.ic_memory, TextUtilShort.clip(e.content, 220), sub, pin, v -> edit(e));
        }
        Ui.tintAccent(this, list);
    }

    /** Add (entry == null) or edit a memory. */
    private void edit(MemoryEntry existing) {
        MemoryEntry e = existing != null ? existing : new MemoryEntry();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        form.setPadding(pad, Ui.dp(this, 8), pad, 0);

        EditText content = new EditText(this, null, 0, R.style.YF_Input);
        content.setMinLines(3);
        content.setText(e.content);
        content.setHint(R.string.memory_content_hint);
        form.addView(content);

        TextView catLabel = new TextView(this, null, 0, R.style.YF_Label);
        catLabel.setText(R.string.memory_category);
        form.addView(catLabel);
        Spinner cat = Widgets.styleSpinner(new Spinner(this));
        String[] labels = new String[MemoryCategory.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = categoryLabel(MemoryCategory.values()[i]);
        cat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        cat.setSelection(e.category.ordinal());
        form.addView(cat);

        TextView impLabel = new TextView(this, null, 0, R.style.YF_Label);
        impLabel.setText(getString(R.string.memory_importance, e.importance));
        form.addView(impLabel);
        com.google.android.material.slider.Slider imp = Widgets.newSlider(this, 1, 5, e.importance);
        imp.addOnChangeListener((s, v, u) -> impLabel.setText(getString(R.string.memory_importance, Math.round(v))));
        form.addView(imp);

        TextView tagLabel = new TextView(this, null, 0, R.style.YF_Label);
        tagLabel.setText(R.string.memory_tags);
        form.addView(tagLabel);
        EditText tags = new EditText(this, null, 0, R.style.YF_Input);
        tags.setSingleLine(true);
        tags.setText(e.tagsCsv());
        form.addView(tags);

        CheckBox pinned = new CheckBox(this);
        pinned.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.accent(this)));
        pinned.setTextColor(getColor(R.color.text_primary));
        pinned.setText(R.string.memory_pin);
        pinned.setChecked(e.pinned || existing == null);
        form.addView(pinned);

        if (existing != null) {
            TextView meta = new TextView(this, null, 0, R.style.YF_Caption);
            meta.setText(getString(R.string.memory_meta, Ui.ago(e.createdAt), e.source.isEmpty() ? getString(R.string.memory_source_manual) : e.source));
            form.addView(meta);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(form);
        android.app.AlertDialog.Builder b = Ui.dialog(this)
                .setTitle(existing == null ? R.string.memory_add : R.string.memory_edit)
                .setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String text = content.getText().toString().trim();
                    if (text.isEmpty()) return;
                    if (SecretFilter.rejectReason(text) != null) {
                        Ui.toast(this, R.string.memory_secret_blocked);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    e.content = text;
                    e.category = MemoryCategory.values()[cat.getSelectedItemPosition()];
                    e.importance = Widgets.value(imp);
                    e.tags.clear();
                    for (String t : tags.getText().toString().split(",")) {
                        String s = t.trim().replace("#", "");
                        if (!s.isEmpty()) e.tags.add(s);
                    }
                    e.pinned = pinned.isChecked();
                    e.updatedAt = now;
                    if (existing == null) {
                        e.createdAt = now;
                        g.memory.add(e);
                    } else {
                        g.memory.update(e);
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) {
            b.setNeutralButton(R.string.delete, (d, w) -> {
                g.memory.delete(e.id);
                refresh();
            });
        }
        Ui.show(b);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        g.io.execute(() -> {
            String msg;
            try {
                if (requestCode == REQ_EXPORT) {
                    JSONArray arr = new JSONArray();
                    for (MemoryEntry e : g.memory.all(null, 100000)) arr.put(e.toJson());
                    JSONObject root = new JSONObject().put("format", "yourfriend-memories").put("version", 1).put("memories", arr);
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        if (os != null) os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    msg = getString(R.string.data_exported);
                } else {
                    JSONArray arr;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) return;
                        String s = new String(ImageUtil.readAll(in, 20 * 1024 * 1024), StandardCharsets.UTF_8).trim();
                        arr = s.startsWith("[") ? new JSONArray(s) : new JSONObject(s).getJSONArray("memories");
                    }
                    int n = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        MemoryEntry e = MemoryEntry.fromJson(arr.getJSONObject(i));
                        if (e.content.trim().isEmpty() || SecretFilter.rejectReason(e.content) != null) continue;
                        e.id = -1;
                        if (e.createdAt == 0) e.createdAt = System.currentTimeMillis();
                        if (e.updatedAt == 0) e.updatedAt = e.createdAt;
                        g.memory.add(e);
                        n++;
                    }
                    msg = getResources().getQuantityString(R.plurals.memories_imported, n, n);
                }
            } catch (Exception e) {
                msg = getString(R.string.error_prefix, e.getMessage());
            }
            String m = msg;
            runOnUiThread(() -> {
                Ui.toast(this, m);
                refresh();
            });
        });
    }

    /** Small local helper so the list never shows multi-kilobyte rows. */
    static final class TextUtilShort {
        static String clip(String s, int max) {
            String t = s.replace('\n', ' ').trim();
            return t.length() <= max ? t : t.substring(0, max - 1) + "…";
        }
    }
}
