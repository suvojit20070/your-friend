package com.suvo.agent.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.files.FileAccess;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Your Friend — Original project by Suvo.
 *
 * The file editor: a plain text field (monospace) with undo / redo, find & replace (with case and
 * regex), and word wrap. Opens any path the Files screen can reach: workspace, picked folders,
 * Shizuku.
 */
public final class EditorActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";
    /** A plain text field gets slow with very big files: larger ones are not opened. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String WRAP = "editor_word_wrap";

    private String path;
    private FileAccess fa;
    private EditText editor;
    private boolean dirty;
    private boolean loading = true;
    private EditText findInput, replaceInput;
    private TextView findCount;
    private boolean matchCase, regex;

    public static void open(Context ctx, String path) {
        ctx.startActivity(new Intent(ctx, EditorActivity.class).putExtra(EXTRA_PATH, path));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        fa = FileAccess.forUser(g.workspace, g.saf);
        setTopBar(nameOf(path));
        Palette p = Palette.of(this);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(14f);
        editor.setTextColor(p.onSurface);
        editor.setHighlightColor(Palette.alpha(p.primary, 0.28f));
        editor.setBackground(null);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setVerticalScrollBarEnabled(true);
        editor.setEnabled(false);
        applyWrap(g.settings.bool(WRAP, false));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        ((android.widget.FrameLayout) findViewById(R.id.editor_box)).addView(editor, lp);
        editor.addTextChangedListener(history);

        tools();
        findBar(p);
        load();
    }

    private static String nameOf(String p) {
        String t = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int s = t.lastIndexOf('/');
        return s >= 0 ? t.substring(s + 1) : t;
    }

    private void applyWrap(boolean on) {
        editor.setHorizontallyScrolling(!on);
        editor.setHorizontalScrollBarEnabled(!on);
    }

    // ---- Undo / redo ------------------------------------------------------------------------------

    /** One edit: at {@code at}, {@code before} was replaced by {@code after}. */
    private static final class Edit {
        final int at;
        final CharSequence before, after;

        Edit(int at, CharSequence before, CharSequence after) {
            this.at = at;
            this.before = before;
            this.after = after;
        }
    }

    private static final int MAX_UNDO = 300;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean applyingHistory;

    private final TextWatcher history = new TextWatcher() {
        private CharSequence before;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (loading || applyingHistory) return;
            before = s.subSequence(start, start + count).toString();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before0, int count) {
            if (loading || applyingHistory) return;
            undo.push(new Edit(start, before == null ? "" : before, s.subSequence(start, start + count).toString()));
            while (undo.size() > MAX_UNDO) undo.removeLast();
            redo.clear();
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (!loading) dirty = true;
        }
    };

    private void undoRedo(boolean isUndo) {
        Edit e = (isUndo ? undo : redo).poll();
        if (e == null) return;
        Editable t = editor.getText();
        CharSequence remove = isUndo ? e.after : e.before, put = isUndo ? e.before : e.after;
        int end = Math.min(t.length(), e.at + remove.length());
        applyingHistory = true;
        try {
            t.replace(Math.min(e.at, t.length()), end, put);
        } finally {
            applyingHistory = false;
        }
        (isUndo ? redo : undo).push(e);
        editor.setSelection(Math.min(t.length(), e.at + put.length()));
        dirty = true;
    }

    // ---- Load / save -----------------------------------------------------------------------------

    private void load() {
        g.io.execute(() -> {
            try {
                FileAccess.Entry e = fa.stat(path);
                if (e.size > MAX_BYTES) throw new java.io.IOException(getString(R.string.editor_too_big, Ui.size(MAX_BYTES)));
                byte[] data = fa.read(path, MAX_BYTES);
                String text = new String(data, StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    loading = true;
                    editor.setText(text);
                    editor.setEnabled(true);
                    editor.setSelection(0);
                    loading = false;
                    dirty = false;
                    undo.clear();
                    redo.clear();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    Ui.toast(this, getString(R.string.error_prefix, ex.getMessage()));
                    finish();
                });
            }
        });
    }

    private void save(Runnable after) {
        String text = editor.getText().toString();
        g.io.execute(() -> {
            try {
                fa.write(path, text.getBytes(StandardCharsets.UTF_8), false);
                runOnUiThread(() -> {
                    dirty = false;
                    Ui.toast(this, R.string.saved);
                    if (after != null) after.run();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, ex.getMessage())));
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (findBarOpen()) {
            closeFind();
            return;
        }
        if (dirty) {
            Ui.show(Ui.dialog(this).setTitle(nameOf(path)).setMessage(R.string.editor_unsaved)
                    .setPositiveButton(R.string.save, (d, w) -> save(this::finish))
                    .setNegativeButton(R.string.editor_discard, (d, w) -> {
                        dirty = false;
                        finish();
                    })
                    .setNeutralButton(android.R.string.cancel, null));
            return;
        }
        super.onBackPressed();
    }

    // ---- Tools row -------------------------------------------------------------------------------

    private void tools() {
        LinearLayout row = findViewById(R.id.editor_tools);
        tool(row, R.drawable.ic_undo, R.string.editor_undo, v -> undoRedo(true));
        tool(row, R.drawable.ic_redo, R.string.editor_redo, v -> undoRedo(false));
        tool(row, R.drawable.ic_search, R.string.editor_find, v -> openFind(false));
        tool(row, R.drawable.ic_replace, R.string.editor_replace, v -> openFind(true));
        tool(row, R.drawable.ic_wrap, R.string.editor_wrap, v -> {
            boolean on = !g.settings.bool(WRAP, false);
            g.settings.put(WRAP, on);
            applyWrap(on);
        });
        tool(row, R.drawable.ic_select_all, R.string.files_select_all, v -> {
            editor.requestFocus();
            editor.selectAll();
        });
        tool(row, R.drawable.ic_copy, R.string.files_copy_path, v -> {
            Ui.copy(this, path);
            Ui.toast(this, R.string.copied);
        });
        tool(row, R.drawable.ic_save, R.string.save, v -> save(null));
    }

    private void tool(LinearLayout row, int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setBackground(ripple());
        b.setColorFilter(Palette.of(this).onSurface);
        int s = Ui.dp(this, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        b.setOnClickListener(l);
        b.setOnLongClickListener(v -> {
            Ui.toast(this, desc);
            return true;
        });
        row.addView(b, lp);
    }

    // ---- Find & replace --------------------------------------------------------------------------

    private LinearLayout replaceRow;

    private void findBar(Palette p) {
        LinearLayout bar = findViewById(R.id.find_bar);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findInput = input(R.string.editor_find_hint);
        r1.addView(findInput, weightLp());
        findCount = new TextView(this, null, 0, R.style.YF_Caption);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 2), 0);
        r1.addView(findCount, cl);
        smallButton(r1, R.drawable.ic_arrow_up, R.string.editor_prev, v -> step(false));
        smallButton(r1, R.drawable.ic_arrow_down, R.string.editor_next, v -> step(true));
        smallButton(r1, R.drawable.ic_close, R.string.close, v -> closeFind());
        bar.addView(r1);

        replaceRow = new LinearLayout(this);
        replaceRow.setGravity(Gravity.CENTER_VERTICAL);
        replaceInput = input(R.string.editor_replace_hint);
        replaceRow.addView(replaceInput, weightLp());
        textButton(replaceRow, R.string.editor_replace_one, v -> replace(false));
        textButton(replaceRow, R.string.editor_replace_all, v -> replace(true));
        bar.addView(replaceRow);

        LinearLayout r3 = new LinearLayout(this);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        TextView caseChip = toggleChip(r3, R.string.editor_match_case, () -> {
            matchCase = !matchCase;
            return matchCase;
        });
        TextView regexChip = toggleChip(r3, R.string.editor_regex, () -> {
            regex = !regex;
            return regex;
        });
        if (caseChip == null || regexChip == null) return;
        bar.addView(r3);

        findInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                search();
            }
        });
        findInput.setOnEditorActionListener((v, id, ev) -> {
            step(true);
            return true;
        });
    }

    private interface Toggle {
        boolean flip();
    }

    private TextView toggleChip(LinearLayout row, int label, Toggle t) {
        Palette p = Palette.of(this);
        TextView c = new TextView(this, null, 0, R.style.YF_Chip);
        c.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 12), Ui.dp(this, 2), 0, Ui.dp(this, 8));
        c.setOnClickListener(v -> {
            boolean on = t.flip();
            c.setBackground(new TonalDrawable(Palette.alpha(p.primary, on ? 0.22f : 0.08f), -1));
            c.setTextColor(on ? p.primary : p.onSurfaceVariant);
            search();
        });
        c.setBackground(new TonalDrawable(Palette.alpha(p.primary, 0.08f), -1));
        c.setTextColor(p.onSurfaceVariant);
        row.addView(c, lp);
        return c;
    }

    private EditText input(int hint) {
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14f);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        return e;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        return lp;
    }

    private void smallButton(LinearLayout row, int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setBackground(ripple());
        b.setColorFilter(Palette.of(this).onSurface);
        int s = Ui.dp(this, 40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        b.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        b.setOnClickListener(l);
        row.addView(b, lp);
    }

    private void textButton(LinearLayout row, int label, View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(this, null, 0, R.style.YF_Button_Ghost);
        b.setText(label);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 8), 0);
        b.setOnClickListener(l);
        row.addView(b, lp);
    }

    private boolean findBarOpen() {
        return findViewById(R.id.find_bar).getVisibility() == View.VISIBLE;
    }

    private void openFind(boolean withReplace) {
        findViewById(R.id.find_bar).setVisibility(View.VISIBLE);
        replaceRow.setVisibility(withReplace ? View.VISIBLE : View.GONE);
        // Start with the selected text, if any.
        int a = editor.getSelectionStart(), b = editor.getSelectionEnd();
        if (a >= 0 && b > a) {
            String sel = editor.getText().subSequence(Math.min(a, b), Math.max(a, b)).toString();
            if (!sel.contains("\n")) findInput.setText(sel);
        }
        findInput.requestFocus();
        findInput.selectAll();
        keyboard(findInput, true);
        search();
    }

    private void closeFind() {
        findViewById(R.id.find_bar).setVisibility(View.GONE);
        keyboard(findInput, false);
        editor.requestFocus();
    }

    /** The search as a pattern (null: nothing to find, or a half-typed regular expression). */
    private Pattern pattern() {
        String q = findInput.getText().toString();
        if (q.isEmpty()) return null;
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return regex ? Pattern.compile(q, flags | Pattern.MULTILINE) : Pattern.compile(Pattern.quote(q), flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Every match: {start, end}. */
    private List<int[]> matches(Pattern pt) {
        List<int[]> out = new ArrayList<>();
        Matcher m = pt.matcher(editor.getText());
        while (m.find() && out.size() < 100_000) {
            if (m.end() == m.start()) { // an empty match (e.g. "^"): step on
                if (m.end() >= editor.length()) break;
                continue;
            }
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    private void search() {
        if (findInput.getText().length() == 0) {
            findCount.setText("");
            return;
        }
        Pattern pt = pattern();
        if (pt == null) {
            findCount.setText("!");
            return;
        }
        int n = matches(pt).size();
        findCount.setText(n == 0 ? getString(R.string.editor_no_match) : String.valueOf(n));
    }

    /** Selects the next (or previous) match after the cursor, wrapping around. */
    private void step(boolean next) {
        Pattern pt = pattern();
        if (pt == null) return;
        List<int[]> all = matches(pt);
        if (all.isEmpty()) {
            findCount.setText(getString(R.string.editor_no_match));
            return;
        }
        int from = next ? Math.max(editor.getSelectionStart(), editor.getSelectionEnd()) : Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
        int[] pick = null;
        int index = 0;
        if (next) {
            for (int i = 0; i < all.size(); i++) if (all.get(i)[0] >= from) {
                pick = all.get(i);
                index = i;
                break;
            }
            if (pick == null) pick = all.get(0);
        } else {
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i)[1] <= from) {
                pick = all.get(i);
                index = i;
                break;
            }
            if (pick == null) {
                index = all.size() - 1;
                pick = all.get(index);
            }
        }
        editor.requestFocus();
        editor.setSelection(pick[0], pick[1]);
        findCount.setText((index + 1) + "/" + all.size());
    }

    private void replace(boolean all) {
        Pattern pt = pattern();
        if (pt == null) return;
        String with = replaceInput.getText().toString();
        Editable t = editor.getText();
        try {
            if (all) {
                Matcher m = pt.matcher(t);
                String result = regex ? m.replaceAll(with) : m.replaceAll(Matcher.quoteReplacement(with));
                if (!result.contentEquals(t)) t.replace(0, t.length(), result); // one step to undo
            } else {
                int a = Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
                int b = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
                Matcher m = pt.matcher(t);
                // The selection is a match: replace it; else go to the next match first.
                if (a >= 0 && b > a && m.find(a) && m.start() == a && m.end() == b) {
                    String rep = regex ? expand(m, with) : with;
                    t.replace(a, b, rep);
                    editor.setSelection(a + rep.length());
                }
                step(true);
            }
        } catch (RuntimeException e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
        search();
    }

    /** "$1"-style groups in a regex replacement, for one match. */
    private static String expand(Matcher m, String with) {
        StringBuffer sb = new StringBuffer();
        Matcher one = m.pattern().matcher(m.group());
        if (!one.matches()) return with;
        one.appendReplacement(sb, with);
        return sb.toString();
    }

    private android.graphics.drawable.Drawable ripple() {
        android.content.res.TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        android.graphics.drawable.Drawable d = ta.getDrawable(0);
        ta.recycle();
        return d;
    }

    private void keyboard(View v, boolean show) {
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm == null) return;
        if (show) v.post(() -> imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        else imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

}
