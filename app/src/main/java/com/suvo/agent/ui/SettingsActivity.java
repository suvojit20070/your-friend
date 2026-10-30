package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.character.FloatingCompanionService;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.data.Settings;
import com.suvo.agent.tools.WebTools;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Application behaviour, personality, memory, appearance, web and developer settings. */
public final class SettingsActivity extends BaseActivity {

    private static final int REQ_EXPORT = 71;
    private static final int REQ_IMPORT = 72;
    private static final int REQ_VRM = 73;
    private String vrmInfo = "";
    private EditText search;
    private LinearLayout screensBox;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_settings);
        search = new EditText(this, null, 0, R.style.YF_Input);
        search.setHint(R.string.settings_search_hint);
        search.setSingleLine(true);
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawablePadding(Ui.dp(this, 10));
        search.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Palette.of(this).onSurfaceVariant));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString();
                applyFilter();
            }
        });
        screensBox = new LinearLayout(this);
        screensBox.setOrientation(LinearLayout.VERTICAL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    /** Hides every row that does not contain all words of the search text, and lists matching screens of the app. */
    private void applyFilter() {
        LinearLayout box = findViewById(R.id.rows);
        if (box == null || search == null) return;
        String q = query.trim().toLowerCase(Locale.ROOT);
        boolean filtering = !q.isEmpty();
        String[] words = q.split("\\s+");
        java.util.ArrayList<View> heads = new java.util.ArrayList<>();
        int shown = 0;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c == search || c == screensBox) continue;
            if (!(c instanceof LinearLayout)) {
                // A section title or a note: only shown together with a card that has matches.
                heads.add(c);
                c.setVisibility(filtering ? View.GONE : View.VISIBLE);
                continue;
            }
            LinearLayout card = (LinearLayout) c;
            int visibleRows = 0;
            View divider = null;
            for (int k = 0; k < card.getChildCount(); k++) {
                View v = card.getChildAt(k);
                if (!(v instanceof ViewGroup)) {
                    divider = v;
                    continue;
                }
                boolean match = !filtering || matchesAll(textOf(v).toLowerCase(Locale.ROOT), words);
                v.setVisibility(match ? View.VISIBLE : View.GONE);
                if (divider != null) {
                    divider.setVisibility(match && (visibleRows > 0 || !filtering) ? View.VISIBLE : View.GONE);
                    divider = null;
                }
                if (match) visibleRows++;
            }
            boolean show = !filtering || visibleRows > 0;
            card.setVisibility(show ? View.VISIBLE : View.GONE);
            if (filtering && visibleRows > 0) for (View h : heads) h.setVisibility(View.VISIBLE);
            shown += filtering ? visibleRows : 0;
            heads.clear();
        }
        // Screens of the app that match (Voice, Memory, Providers…), each opens that screen.
        screensBox.removeAllViews();
        if (!filtering) return;
        List<AppScreens.Screen> found = AppScreens.match(this, q);
        Rows rr = new Rows(this, screensBox);
        int count = 0;
        for (AppScreens.Screen s : found) {
            if (s.cls == SettingsActivity.class || count >= 6) continue;
            if (count == 0) rr.section(R.string.settings_search_screens);
            final Class<? extends android.app.Activity> target = s.cls;
            rr.row(R.drawable.ic_search, getString(s.titleRes), s.where, null, v -> open(target));
            count++;
        }
        if (shown == 0 && count == 0) rr.note(getString(R.string.settings_search_none));
    }

    private static boolean matchesAll(String hay, String[] words) {
        for (String w : words) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    /** All visible text of a row (title and summary). */
    private static String textOf(View v) {
        StringBuilder sb = new StringBuilder();
        collect(v, sb);
        return sb.toString();
    }

    private static void collect(View v, StringBuilder sb) {
        if (v instanceof TextView && !(v instanceof EditText)) sb.append(((TextView) v).getText()).append(' ');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), sb);
        }
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        // Search box first (it stays the same view, so typing is not interrupted), then screen results.
        box.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(screensBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Rows r = new Rows(this, box);

        r.section(R.string.settings_ai);
        r.link(R.drawable.ic_provider, R.string.section_providers, R.string.settings_providers_desc, ProvidersActivity.class);
        r.link(R.drawable.ic_edit, R.string.sysprompt_title, R.string.sysprompt_settings_desc, SystemPromptActivity.class);
        r.switchRow(R.drawable.ic_screen, R.string.settings_keep_screen_on, R.string.settings_keep_screen_on_desc, Settings.KEEP_SCREEN_ON, false, null);
        r.switchRow(R.drawable.ic_tools, R.string.settings_tool_calling, R.string.settings_tool_calling_desc, Settings.TOOL_CALLING, true, null);
        // Each limit has its own "no limit" switch; the slider is only shown while the limit is on.
        r.switchRow(R.drawable.ic_agent, R.string.settings_no_limit_steps, R.string.settings_no_limit_desc, Settings.NO_LIMIT_STEPS, false, on -> build());
        if (!g.settings.bool(Settings.NO_LIMIT_STEPS, false))
            r.sliderRow(R.drawable.ic_agent, R.string.settings_max_steps, Settings.MAX_STEPS, 25, 1, 200, "", null);
        r.switchRow(R.drawable.ic_tools, R.string.settings_no_limit_tool_calls, R.string.settings_no_limit_desc, Settings.NO_LIMIT_TOOL_CALLS, false, on -> build());
        if (!g.settings.bool(Settings.NO_LIMIT_TOOL_CALLS, false))
            r.sliderRow(R.drawable.ic_tools, R.string.settings_max_tool_calls, Settings.MAX_TOOL_CALLS, 60, 1, 500, "", null);
        r.switchRow(R.drawable.ic_history, R.string.settings_no_limit_time, R.string.settings_no_limit_desc, Settings.NO_LIMIT_TIME, false, on -> build());
        if (!g.settings.bool(Settings.NO_LIMIT_TIME, false))
            r.sliderRow(R.drawable.ic_history, R.string.settings_max_minutes, Settings.MAX_TASK_MINUTES, 15, 1, 240, " min", null);

        r.section(R.string.settings_personality);
        android.widget.TextView activeName = r.link(R.drawable.ic_character, R.string.settings_active_character, 0, CharactersActivity.class);
        activeName.setText(g.characters.active().name);
        activeName.setVisibility(android.view.View.VISIBLE);
        r.textRow(R.drawable.ic_chat, R.string.settings_response_language, Settings.RESPONSE_LANGUAGE, "", false, R.string.settings_response_language_auto, null);
        r.textRow(R.drawable.ic_chat, R.string.settings_tone, Settings.TONE, "", false, R.string.settings_tone_default, null);
        r.choiceRow(R.drawable.ic_shield, R.string.autonomy_mode, Settings.AUTONOMY, "CONFIRM",
                new String[]{getString(R.string.autonomy_assist_long), getString(R.string.autonomy_confirm_long), getString(R.string.autonomy_autonomous_long), getString(R.string.autonomy_never_ask_long)},
                new String[]{"ASSIST", "CONFIRM", "AUTONOMOUS", "NEVER_ASK"}, null);

        r.section(R.string.settings_voice);
        r.link(R.drawable.ic_voice, R.string.section_voice, R.string.settings_voice_desc, VoiceActivity.class);

        r.section(R.string.settings_memory);
        r.switchRow(R.drawable.ic_memory, R.string.privacy_auto_memory, R.string.privacy_auto_memory_desc, Settings.AUTO_MEMORY, true, null);
        r.switchRow(R.drawable.ic_search, R.string.settings_memory_retrieval, R.string.settings_memory_retrieval_desc, Settings.MEMORY_RETRIEVAL, true, null);
        r.sliderRow(R.drawable.ic_memory, R.string.settings_memory_max, Settings.MEMORY_MAX_INJECTED, 8, 0, 30, "", null);
        r.switchRow(R.drawable.ic_history, R.string.settings_summaries, R.string.settings_summaries_desc, Settings.SUMMARIES, true, null);
        r.sliderRow(R.drawable.ic_history, R.string.settings_context_trigger, Settings.CONTEXT_TRIGGER, 70, 30, 95, "%", null);
        r.link(R.drawable.ic_memory, R.string.section_memory, R.string.settings_memory_manage, MemoryActivity.class);

        r.section(R.string.settings_device);
        // Default digital assistant (long-press Home / assistant gesture opens Your Friend).
        boolean isAssist = AssistActivity.isDefault(this);
        r.row(R.drawable.ic_agent, getString(R.string.assist_default),
                getString(isAssist ? R.string.assist_default_on : R.string.assist_default_off),
                r.chip(isAssist ? "✓" : "Set", isAssist ? getColor(R.color.success) : Ui.accent(this)),
                v -> AssistActivity.openDefaultSettings(this));
        r.link(R.drawable.ic_screen, R.string.section_screen, R.string.settings_screen_desc, ScreenControlActivity.class);
        r.link(R.drawable.ic_shield, R.string.section_permissions, R.string.settings_permissions_desc, PermissionsActivity.class);
        // The current screen goes along with every request (only while another app is on screen).
        r.switchRow(R.drawable.ic_screen, R.string.settings_attach_tree, R.string.settings_attach_tree_desc, Settings.ATTACH_SCREEN_TREE, false, null);
        r.switchRow(R.drawable.ic_eye, R.string.settings_attach_shot, R.string.settings_attach_shot_desc, Settings.ATTACH_SCREENSHOT, false, null);

        r.section(R.string.settings_appearance);
        r.choiceRow(R.drawable.ic_eye, R.string.settings_theme, Settings.THEME, "dark",
                new String[]{getString(R.string.theme_system), getString(R.string.theme_dark), getString(R.string.theme_light)},
                new String[]{"system", "dark", "light"}, v -> restartApp());
        seedRow(r);
        // Chat text size, with a live Markdown preview.
        r.row(R.drawable.ic_chat, getString(R.string.md_scale), MarkdownScaleActivity.scalePercent(this) + "%", null,
                v -> open(MarkdownScaleActivity.class));
        r.choiceRow(R.drawable.ic_chat, R.string.settings_language, Settings.UI_LANGUAGE, "",
                new String[]{getString(R.string.lang_system), "English", "Hinglish", "বাংলা", "हिन्दी", "日本語"},
                LocaleHelper.TAGS, v -> {
                    LocaleHelper.setLanguage(this, v);
                    restartApp();
                });
        r.sliderRow(R.drawable.ic_character, R.string.settings_character_size, Settings.CHARACTER_SIZE, 100, 50, 150, "%", null);
        characterStyle(r);
        r.choiceRow(R.drawable.ic_character, R.string.settings_animation_quality, Settings.ANIMATION_QUALITY, "high",
                new String[]{getString(R.string.quality_low), getString(R.string.quality_medium), getString(R.string.quality_high)},
                new String[]{"low", "medium", "high"}, null);
        r.switchRow(R.drawable.ic_pause, R.string.settings_reduced_motion, R.string.settings_reduced_motion_desc, Settings.REDUCED_MOTION, false, null);
        r.row(R.drawable.ic_character, getString(R.string.settings_reset_position), null, null, v -> {
            g.settings.put(Settings.CHARACTER_POS_X, 0f);
            g.settings.put(Settings.CHARACTER_POS_Y, 0f);
            Ui.toast(this, R.string.done);
        });
        r.switchRow(R.drawable.ic_character, R.string.settings_floating, R.string.settings_floating_desc, Settings.FLOATING_COMPANION, true, on -> {
            if (on) {
                if (g.capabilities.state(Capability.OVERLAY) != CapabilityState.AVAILABLE) {
                    g.settings.put(Settings.FLOATING_COMPANION, false);
                    PermissionFlows.fix(this, Capability.OVERLAY);
                    build();
                } else {
                    FloatingCompanionService.onAppVisibilityChanged(this, true);
                }
            } else {
                FloatingCompanionService.stop(this);
            }
        });
        r.switchRow(R.drawable.ic_character, R.string.settings_companion_brain, R.string.settings_companion_brain_desc,
                Settings.COMPANION_BRAIN, true, null);
        r.switchRow(R.drawable.ic_character, R.string.settings_companion_agent, R.string.settings_companion_agent_desc,
                Settings.COMPANION_ASKS_AGENT, true, null);
        r.switchRow(R.drawable.ic_screen, R.string.settings_tap_cursor, R.string.settings_tap_cursor_desc,
                com.suvo.agent.device.TapIndicator.SETTING, true, null);

        r.section(R.string.settings_web);
        r.choiceRow(R.drawable.ic_search, R.string.settings_search_provider, Settings.SEARCH_PROVIDER, "duckduckgo",
                new String[]{"DuckDuckGo", "SearXNG", "Brave Search"}, new String[]{"duckduckgo", "searxng", "brave"}, null);
        r.textRow(R.drawable.ic_provider, R.string.settings_searxng_url, Settings.SEARXNG_URL, "", false, R.string.not_set, null);
        boolean hasBrave = !g.settings.str(WebTools.BRAVE_KEY_ENC, "").isEmpty();
        r.row(R.drawable.ic_lock, getString(R.string.settings_brave_key), getString(hasBrave ? R.string.secret_stored : R.string.not_set), null,
                v -> Ui.prompt(this, R.string.settings_brave_key, "", false, key -> {
                    g.settings.put(WebTools.BRAVE_KEY_ENC, key.trim().isEmpty() ? "" : g.secrets.encrypt(key.trim()));
                    build();
                }));

        r.section(R.string.settings_developer);
        r.link(R.drawable.ic_terminal, R.string.section_terminal, R.string.settings_runtime_desc, TerminalActivity.class);
        r.row(R.drawable.ic_files, getString(R.string.settings_workspace), g.workspace.root().getAbsolutePath() + "\n" + Ui.size(g.workspace.sizeBytes()), null,
                v -> open(FilesActivity.class));
        r.link(R.drawable.ic_tools, R.string.section_tools, R.string.settings_tools_desc, ToolsActivity.class);
        r.row(R.drawable.ic_history, getString(R.string.settings_logs), getString(R.string.settings_logs_desc), null, v -> open(LiveAgentActivity.class));
        r.row(R.drawable.ic_upload, getString(R.string.settings_export_config), getString(R.string.settings_export_config_desc), null, v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "yourfriend-settings.json");
            startActivityForResult(i, REQ_EXPORT);
        });
        r.row(R.drawable.ic_download, getString(R.string.settings_import_config), null, null, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            startActivityForResult(i, REQ_IMPORT);
        });
        r.link(R.drawable.ic_files, R.string.storage_title, R.string.settings_storage_desc, StorageActivity.class);
        r.link(R.drawable.ic_info, R.string.section_about, 0, AboutActivity.class);
        Ui.tintAccent(this, box);
        applyFilter();
    }

    /** Encrypted values (API keys, tokens) only work on this phone and must never leave it. */
    private static void stripSecrets(JSONObject o) {
        java.util.List<String> drop = new java.util.ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.endsWith("_enc") || k.contains("token") || k.contains("secret")) drop.add(k);
        }
        for (String k : drop) o.remove(k);
        o.remove(WebTools.BRAVE_KEY_ENC);
        o.remove(Settings.VRM_PATH);
    }

    // ------------------------------------------------------------------ 2D / 3D character style

    private java.io.File vrmFile() {
        java.io.File dir = new java.io.File(getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new java.io.File(dir, "avatar.vrm");
    }

    /** Seed colour: one colour makes the whole tonal palette. Shown as swatches in a sheet. */
    private void seedRow(Rows r) {
        String cur = g.settings.str(Settings.ACCENT, "dynamic");
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Material You: the whole palette from the device wallpaper.
            com.google.android.material.materialswitch.MaterialSwitch wall = Widgets.newSwitch(this);
            wall.setChecked("dynamic".equals(cur));
            wall.setOnCheckedChangeListener((btn, on) -> {
                g.settings.put(Settings.ACCENT, on ? "dynamic" : Palette.DEFAULT_SEED);
                Fx.later(this::restartApp, 260);
            });
            r.row(R.drawable.ic_eye, getString(R.string.settings_wallpaper_colors), getString(R.string.settings_wallpaper_colors_desc),
                    wall, v -> wall.toggle());
        }
        View sw = swatch(Palette.seedColor(this, cur), Ui.dp(this, 28));
        r.row(R.drawable.ic_star, getString(R.string.settings_accent), seedLabel(cur), sw, v -> {
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(4);
            final Overlay[] sheet = new Overlay[1];
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add("dynamic");
            java.util.Collections.addAll(values, Palette.SEEDS);
            for (String val : values) {
                android.widget.LinearLayout cell = new android.widget.LinearLayout(this);
                cell.setOrientation(android.widget.LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER);
                int pad = Ui.dp(this, 6);
                cell.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 8));
                boolean selected = val.equalsIgnoreCase(cur);
                View dot = swatch(Palette.seedColor(this, val), Ui.dp(this, 44));
                if (selected) {
                    dot.setForeground(getDrawable(R.drawable.ic_check));
                    dot.setForegroundTintList(android.content.res.ColorStateList.valueOf(0xFF1F1B13));
                }
                cell.addView(dot, new android.widget.LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
                android.widget.TextView t = new android.widget.TextView(this, null, 0, R.style.YF_Caption);
                t.setText(seedLabel(val));
                t.setGravity(android.view.Gravity.CENTER);
                t.setMaxLines(1);
                t.setPadding(0, Ui.dp(this, 6), 0, 0);
                cell.addView(t);
                cell.setClickable(true);
                cell.setOnClickListener(x -> {
                    if (sheet[0] != null) sheet[0].dismiss();
                    g.settings.put(Settings.ACCENT, val);
                    Fx.later(this::restartApp, 260);
                });
                android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                lp.width = 0;
                lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
                grid.addView(cell, lp);
            }
            sheet[0] = Fx.sheet(this, getString(R.string.settings_accent), grid);
        });
    }

    private View swatch(int color, int size) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return v;
    }

    private String seedLabel(String value) {
        if ("dynamic".equals(value)) return getString(R.string.accent_dynamic);
        for (int i = 0; i < Palette.SEEDS.length; i++) {
            if (Palette.SEEDS[i].equalsIgnoreCase(value)) {
                int id = getResources().getIdentifier("accent_" + Palette.SEED_NAMES[i], "string", getPackageName());
                return id == 0 ? Palette.SEED_NAMES[i] : getString(id);
            }
        }
        return value;
    }

    private void characterStyle(Rows r) {
        String path = g.settings.str(Settings.VRM_PATH, "");
        boolean custom = !path.isEmpty() && new java.io.File(path).isFile();
        String bundled = com.suvo.agent.character.vrm.VrmModel.bundledAsset(this);
        String sub;
        if (custom) {
            java.io.File f = new java.io.File(path);
            sub = getString(R.string.settings_vrm_custom, vrmInfo.isEmpty() ? f.getName() : vrmInfo) + " · " + Ui.size(f.length());
        } else if (bundled != null) {
            sub = getString(R.string.settings_vrm_builtin, bundled);
        } else {
            sub = getString(R.string.settings_vrm_none);
        }
        String err = com.suvo.agent.character.CharacterView.lastVrmError();
        if (!err.isEmpty()) sub += "\n" + getString(R.string.settings_vrm_error, err);
        r.row(R.drawable.ic_character, getString(R.string.settings_vrm_model), sub, null, v -> pickVrm());
        r.row(R.drawable.ic_download, getString(R.string.settings_vrm_import), getString(R.string.settings_vrm_import_desc), null, v -> pickVrm());
        if (custom || bundled != null) {
            r.choiceRow(R.drawable.ic_eye, R.string.settings_vrm_framing, Settings.VRM_FRAMING, "full",
                    new String[]{getString(R.string.vrm_full_body), getString(R.string.vrm_upper_body)}, new String[]{"full", "upper"}, null);
        }
        if (custom) {
            r.row(R.drawable.ic_refresh, getString(R.string.settings_vrm_use_builtin), null, null, v ->
                    Ui.confirm(this, R.string.settings_vrm_use_builtin, getString(R.string.settings_vrm_remove_confirm), () -> {
                        //noinspection ResultOfMethodCallIgnored
                        new java.io.File(path).delete();
                        g.settings.put(Settings.VRM_PATH, "");
                        vrmInfo = "";
                        build();
                    }));
        }
        r.note(getString(R.string.settings_vrm_note));
    }

    private void pickVrm() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/gltf-binary", "application/octet-stream", "*/*"});
        startActivityForResult(i, REQ_VRM);
    }

    private void importVrm(Uri uri) {
        Ui.toast(this, R.string.settings_vrm_importing);
        g.io.execute(() -> {
            java.io.File tmp = new java.io.File(getCacheDir(), "avatar_import.vrm");
            String result;
            boolean ok = false;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    if (in == null) throw new java.io.IOException("Cannot open the file");
                    byte[] buf = new byte[1 << 16];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 200L * 1024 * 1024) throw new java.io.IOException("The model is larger than 200 MB");
                        out.write(buf, 0, n);
                    }
                }
                String info = com.suvo.agent.character.vrm.VrmModel.probe(tmp);
                java.io.File dest = vrmFile();
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                if (!tmp.renameTo(dest)) throw new java.io.IOException("Could not save the model");
                vrmInfo = info;
                g.settings.put(Settings.VRM_PATH, dest.getAbsolutePath());
                result = getString(R.string.settings_vrm_imported, info);
                ok = true;
            } catch (Exception | OutOfMemoryError e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                result = getString(R.string.settings_vrm_invalid, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String msg = result;
            final boolean success = ok;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Ui.show(Ui.dialog(this).setTitle(success ? R.string.settings_vrm_model : R.string.error_title).setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null));
                build();
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_VRM) {
            importVrm(uri);
            return;
        }
        try {
            if (requestCode == REQ_EXPORT) {
                JSONObject o = g.settings.export();
                stripSecrets(o); // never export secrets
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Ui.toast(this, R.string.data_exported);
            } else if (requestCode == REQ_IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) return;
                    JSONObject o = new JSONObject(new String(com.suvo.agent.device.ImageUtil.readAll(in, 1024 * 1024), StandardCharsets.UTF_8));
                    stripSecrets(o);
                    g.settings.importFrom(o);
                }
                Ui.toast(this, R.string.settings_imported);
                restartApp();
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
    }
}
