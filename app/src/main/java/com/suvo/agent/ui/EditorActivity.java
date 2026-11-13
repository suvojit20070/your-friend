package com.suvo.agent.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.files.FileAccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import org.eclipse.tm4e.core.registry.IThemeSource;

/**
 * Your Friend — Original project by Suvo.
 *
 * The file editor (Sora Editor): line numbers, undo / redo, find & replace (with case and regex),
 * word wrap, and code colours from TextMate grammars when CI fetched them (else plain text, still
 * fully editable). Opens any path the Files screen can reach: workspace, picked folders, Shizuku.
 */
public final class EditorActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";
    private static final String TAG = "YF.Editor";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String WRAP = "editor_word_wrap";

    private static boolean textMateReady;

    private String path;
    private FileAccess fa;
    private CodeEditor editor;
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

        editor = new CodeEditor(this);
        editor.setTypefaceText(Typeface.MONOSPACE);
        editor.setTextSize(14f);
        editor.setWordwrap(g.settings.bool(WRAP, false));
        editor.setEditable(false);
        ((android.widget.FrameLayout) findViewById(R.id.editor_box)).addView(editor,
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        applyColors(p);
        editor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (!loading) dirty = true;
        });

        tools();
        findBar(p);
        load();
    }

    private static String nameOf(String p) {
        String t = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int s = t.lastIndexOf('/');
        return s >= 0 ? t.substring(s + 1) : t;
    }

    // ---- Colours ---------------------------------------------------------------------------------

    private void applyColors(Palette p) {
        String scope = scopeFor(path);
        if (scope != null && prepareTextMate(p)) {
            try {
                editor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
                editor.setEditorLanguage(TextMateLanguage.create(scope, true));
            } catch (Throwable t) {
                Log.w(TAG, "TextMate language failed: " + t);
            }
        }
        // The page colours always follow the app's palette.
        EditorColorScheme cs = editor.getColorScheme();
        cs.setColor(EditorColorScheme.WHOLE_BACKGROUND, p.background);
        cs.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, p.background);
        cs.setColor(EditorColorScheme.LINE_NUMBER, Palette.alpha(p.onSurfaceVariant, 0.6f));
        cs.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, p.primary);
        cs.setColor(EditorColorScheme.CURRENT_LINE, Palette.alpha(p.primary, 0.07f));
        cs.setColor(EditorColorScheme.SELECTION_INSERT, p.primary);
        cs.setColor(EditorColorScheme.SELECTION_HANDLE, p.primary);
        cs.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, Palette.alpha(p.primary, 0.28f));
        cs.setColor(EditorColorScheme.LINE_DIVIDER, Palette.alpha(p.outlineVariant, 0.5f));
        if (scope == null || !textMateReady) cs.setColor(EditorColorScheme.TEXT_NORMAL, p.onSurface);
        editor.invalidate();
    }

    /** The grammar's scope for this file's extension, from assets/textmate/languages.json. */
    private String scopeFor(String p) {
        String name = nameOf(p).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1);
        try (InputStream in = getAssets().open("textmate/languages.json")) {
            JSONObject o = new JSONObject(new String(com.suvo.agent.device.ImageUtil.readAll(in, 1 << 20), StandardCharsets.UTF_8));
            JSONArray langs = o.getJSONArray("languages");
            for (int i = 0; i < langs.length(); i++) {
                JSONObject l = langs.getJSONObject(i);
                JSONArray exts = l.optJSONArray("extensions");
                if (exts == null) continue;
                for (int j = 0; j < exts.length(); j++) if (ext.equals(exts.getString(j))) return l.getString("scopeName");
            }
        } catch (Exception ignored) {
            // No grammars in this build.
        }
        return null;
    }

    /** Loads the grammars once, and a colour theme made from the app's palette. */
    private boolean prepareTextMate(Palette p) {
        try {
            if (!textMateReady) {
                FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(getApplicationContext().getAssets()));
                GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
                textMateReady = true;
            }
            String name = "yf_" + Integer.toHexString(p.primary) + "_" + (p.dark ? "d" : "l");
            File theme = new File(getCacheDir(), name + ".json");
            if (!theme.isFile()) {
                try (FileOutputStream out = new FileOutputStream(theme)) {
                    out.write(themeJson(p).getBytes(StandardCharsets.UTF_8));
                }
            }
            ThemeRegistry reg = ThemeRegistry.getInstance();
            try (InputStream in = new java.io.FileInputStream(theme)) {
                ThemeModel model = new ThemeModel(IThemeSource.fromInputStream(in, theme.getName(), null), name);
                model.setDark(p.dark);
                reg.loadTheme(model);
            }
            reg.setTheme(name);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "TextMate unavailable: " + t);
            return false;
        }
    }

    /** A VS Code-style colour theme built from the app's palette (keywords in the accent colour). */
    private static String themeJson(Palette p) throws org.json.JSONException {
        boolean dark = p.dark;
        String fg = hex(p.onSurface), bg = hex(p.background), accent = hex(p.primary), tertiary = hex(p.tertiary);
        String string = dark ? "#9CCC8C" : "#2E7D32", number = dark ? "#E6A96B" : "#B5651D";
        String comment = hex(Palette.alpha(p.onSurfaceVariant, 0.75f)), type = dark ? "#7FC8E8" : "#0B6E99";
        JSONObject o = new JSONObject();
        o.put("name", "Your Friend");
        o.put("type", dark ? "dark" : "light");
        o.put("colors", new JSONObject().put("editor.background", bg).put("editor.foreground", fg));
        JSONArray tc = new JSONArray();
        tc.put(rule(null, fg, null).put("settings", new JSONObject().put("foreground", fg).put("background", bg)));
        tc.put(rule("comment, punctuation.definition.comment", comment, "italic"));
        tc.put(rule("string, string.quoted, punctuation.definition.string", string, null));
        tc.put(rule("constant.numeric, constant.language, constant.character", number, null));
        tc.put(rule("keyword, storage, storage.type, storage.modifier, keyword.control, keyword.operator.new", accent, null));
        tc.put(rule("entity.name.type, entity.name.class, support.type, support.class", type, null));
        tc.put(rule("entity.name.function, support.function, meta.function-call", tertiary, null));
        tc.put(rule("entity.name.tag, meta.tag", accent, null));
        tc.put(rule("entity.other.attribute-name", type, null));
        tc.put(rule("variable.parameter, variable.other.readwrite", fg, null));
        tc.put(rule("markup.heading, markup.bold", accent, "bold"));
        tc.put(rule("markup.italic", fg, "italic"));
        tc.put(rule("invalid", "#E05555", null));
        o.put("tokenColors", tc);
        return o.toString();
    }

    private static JSONObject rule(String scope, String fg, String style) throws org.json.JSONException {
        JSONObject r = new JSONObject();
        if (scope != null) r.put("scope", scope);
        JSONObject s = new JSONObject().put("foreground", fg);
        if (style != null) s.put("fontStyle", style);
        r.put("settings", s);
        return r;
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
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
                    editor.setEditable(true);
                    loading = false;
                    dirty = false;
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
        tool(row, R.drawable.ic_undo, R.string.editor_undo, v -> {
            if (editor.canUndo()) editor.undo();
        });
        tool(row, R.drawable.ic_redo, R.string.editor_redo, v -> {
            if (editor.canRedo()) editor.redo();
        });
        tool(row, R.drawable.ic_search, R.string.editor_find, v -> openFind(false));
        tool(row, R.drawable.ic_replace, R.string.editor_replace, v -> openFind(true));
        tool(row, R.drawable.ic_wrap, R.string.editor_wrap, v -> {
            boolean on = !g.settings.bool(WRAP, false);
            g.settings.put(WRAP, on);
            editor.setWordwrap(on);
        });
        tool(row, R.drawable.ic_select_all, R.string.files_select_all, v -> editor.selectAll());
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
        if (editor.getCursor().isSelected()) {
            int a = editor.getCursor().getLeft(), b = editor.getCursor().getRight();
            String sel = editor.getText().subSequence(a, b).toString();
            if (!sel.contains("\n")) findInput.setText(sel);
        }
        findInput.requestFocus();
        findInput.selectAll();
        keyboard(findInput, true);
        search();
    }

    private void closeFind() {
        findViewById(R.id.find_bar).setVisibility(View.GONE);
        try {
            editor.getSearcher().stopSearch();
        } catch (RuntimeException ignored) {
        }
        keyboard(findInput, false);
        editor.requestFocus();
    }

    private void search() {
        EditorSearcher s = editor.getSearcher();
        String q = findInput.getText().toString();
        if (q.isEmpty()) {
            s.stopSearch();
            findCount.setText("");
            return;
        }
        try {
            s.search(q, new EditorSearcher.SearchOptions(regex ? EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
                    : EditorSearcher.SearchOptions.TYPE_NORMAL, !matchCase));
            findCount.post(this::updateCount);
        } catch (RuntimeException e) {
            // A half-typed regular expression.
            findCount.setText("!");
        }
    }

    private void updateCount() {
        try {
            int n = editor.getSearcher().getMatchedPositionCount();
            findCount.setText(n == 0 ? getString(R.string.editor_no_match) : String.valueOf(n));
        } catch (RuntimeException e) {
            findCount.setText("");
        }
    }

    private void step(boolean next) {
        EditorSearcher s = editor.getSearcher();
        if (!s.hasQuery()) search();
        try {
            if (next) s.gotoNext();
            else s.gotoPrevious();
        } catch (RuntimeException ignored) {
        }
    }

    private void replace(boolean all) {
        EditorSearcher s = editor.getSearcher();
        if (!s.hasQuery()) search();
        String with = replaceInput.getText().toString();
        try {
            if (all) s.replaceAll(with);
            else {
                if (!editor.getCursor().isSelected()) s.gotoNext();
                s.replaceCurrentMatch(with);
            }
        } catch (RuntimeException e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
        findCount.postDelayed(this::updateCount, 150);
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

    @Override
    protected void onDestroy() {
        if (editor != null) editor.release();
        super.onDestroy();
    }
}
