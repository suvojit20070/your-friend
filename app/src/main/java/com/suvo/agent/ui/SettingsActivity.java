package com.suvo.agent.ui;

import android.content.Intent;
<<<<<<< HEAD
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;

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

/** Application behaviour, personality, memory, appearance, web and developer settings. */
public final class SettingsActivity extends BaseActivity {

    private static final int REQ_EXPORT = 71;
    private static final int REQ_IMPORT = 72;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_settings);
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

        r.section(R.string.settings_ai);
        r.link(R.drawable.ic_provider, R.string.section_providers, R.string.settings_providers_desc, ProvidersActivity.class);
        r.switchRow(R.drawable.ic_tools, R.string.settings_tool_calling, R.string.settings_tool_calling_desc, Settings.TOOL_CALLING, true, null);
        r.sliderRow(R.drawable.ic_agent, R.string.settings_max_steps, Settings.MAX_STEPS, 25, 1, 100, "", null);
        r.sliderRow(R.drawable.ic_agent, R.string.settings_max_tool_calls, Settings.MAX_TOOL_CALLS, 60, 1, 300, "", null);
        r.sliderRow(R.drawable.ic_history, R.string.settings_max_minutes, Settings.MAX_TASK_MINUTES, 15, 1, 120, " min", null);

        r.section(R.string.settings_personality);
        android.widget.TextView activeName = r.link(R.drawable.ic_character, R.string.settings_active_character, 0, CharactersActivity.class);
        activeName.setText(g.characters.active().name);
        activeName.setVisibility(android.view.View.VISIBLE);
        r.textRow(R.drawable.ic_chat, R.string.settings_response_language, Settings.RESPONSE_LANGUAGE, "", false, R.string.settings_response_language_auto, null);
        r.textRow(R.drawable.ic_chat, R.string.settings_tone, Settings.TONE, "", false, R.string.settings_tone_default, null);
        r.choiceRow(R.drawable.ic_shield, R.string.autonomy_mode, Settings.AUTONOMY, "CONFIRM",
                new String[]{getString(R.string.autonomy_assist_long), getString(R.string.autonomy_confirm_long), getString(R.string.autonomy_autonomous_long)},
                new String[]{"ASSIST", "CONFIRM", "AUTONOMOUS"}, null);

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
        r.link(R.drawable.ic_screen, R.string.section_screen, R.string.settings_screen_desc, ScreenControlActivity.class);
        r.link(R.drawable.ic_shield, R.string.section_permissions, R.string.settings_permissions_desc, PermissionsActivity.class);

        r.section(R.string.settings_appearance);
        r.choiceRow(R.drawable.ic_eye, R.string.settings_theme, Settings.THEME, "system",
                new String[]{getString(R.string.theme_system), getString(R.string.theme_dark), getString(R.string.theme_light)},
                new String[]{"system", "dark", "light"}, v -> restartApp());
        r.choiceRow(R.drawable.ic_star, R.string.settings_accent, Settings.ACCENT, "dynamic",
                new String[]{getString(R.string.accent_dynamic), getString(R.string.accent_violet), getString(R.string.accent_teal),
                        getString(R.string.accent_rose), getString(R.string.accent_amber), getString(R.string.accent_blue)},
                new String[]{"dynamic", "#7C6CFF", "#1FA796", "#E5486B", "#E29A1F", "#3B82F6"}, v -> restartApp());
        r.choiceRow(R.drawable.ic_chat, R.string.settings_language, Settings.UI_LANGUAGE, "",
                new String[]{getString(R.string.lang_system), "English", "Hinglish", "বাংলা", "हिन्दी", "日本語"},
                LocaleHelper.TAGS, v -> {
                    LocaleHelper.setLanguage(this, v);
                    restartApp();
                });
        r.sliderRow(R.drawable.ic_character, R.string.settings_character_size, Settings.CHARACTER_SIZE, 100, 50, 150, "%", null);
        r.choiceRow(R.drawable.ic_character, R.string.settings_animation_quality, Settings.ANIMATION_QUALITY, "high",
                new String[]{getString(R.string.quality_low), getString(R.string.quality_medium), getString(R.string.quality_high)},
                new String[]{"low", "medium", "high"}, null);
        r.switchRow(R.drawable.ic_pause, R.string.settings_reduced_motion, R.string.settings_reduced_motion_desc, Settings.REDUCED_MOTION, false, null);
        r.row(R.drawable.ic_character, getString(R.string.settings_reset_position), null, null, v -> {
            g.settings.put(Settings.CHARACTER_POS_X, 0f);
            g.settings.put(Settings.CHARACTER_POS_Y, 0f);
            Ui.toast(this, R.string.done);
        });
        r.switchRow(R.drawable.ic_character, R.string.settings_floating, R.string.settings_floating_desc, Settings.FLOATING_COMPANION, false, on -> {
            if (on) {
                if (g.capabilities.state(Capability.OVERLAY) != CapabilityState.AVAILABLE) {
                    g.settings.put(Settings.FLOATING_COMPANION, false);
                    PermissionFlows.fix(this, Capability.OVERLAY);
                    build();
                } else {
                    FloatingCompanionService.start(this);
                }
            } else {
                FloatingCompanionService.stop(this);
            }
        });

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
        r.link(R.drawable.ic_info, R.string.section_about, 0, AboutActivity.class);
        Ui.tintAccent(this, box);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                JSONObject o = g.settings.export();
                o.remove(WebTools.BRAVE_KEY_ENC); // never export secrets
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Ui.toast(this, R.string.data_exported);
            } else if (requestCode == REQ_IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) return;
                    JSONObject o = new JSONObject(new String(com.suvo.agent.device.ImageUtil.readAll(in, 1024 * 1024), StandardCharsets.UTF_8));
                    o.remove(WebTools.BRAVE_KEY_ENC);
                    g.settings.importFrom(o);
                }
                Ui.toast(this, R.string.settings_imported);
                restartApp();
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
=======
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.suvo.agent.AgentApp;
import com.suvo.agent.R;
import com.suvo.agent.core.llm.LlmConfig;

/** In-app setup: LLM provider, API key, model, STT key, character style, permissions. */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        AgentApp app = AgentApp.get();
        com.suvo.agent.data.Settings s = app.settings;
        LlmConfig cfg = s.llm();

        EditText base = findViewById(R.id.base);
        EditText key = findViewById(R.id.key);
        EditText model = findViewById(R.id.model);
        EditText tavily = findViewById(R.id.tavilyKey);
        CheckBox stream = findViewById(R.id.stream);
        CheckBox dyn = findViewById(R.id.dynamic);

        base.setText(cfg.baseUrl);
        key.setText(cfg.apiKey);
        model.setText(cfg.model);
        stream.setChecked(cfg.stream);
        dyn.setChecked(s.dynamicColor());
        tavily.setText(s.secrets().get("TAVILY_KEY", ""));

        findViewById(R.id.save).setOnClickListener(v -> {
            cfg.baseUrl = base.getText().toString().trim();
            cfg.apiKey = key.getText().toString().trim();
            cfg.model = model.getText().toString().trim();
            cfg.stream = stream.isChecked();
            s.saveLlm(cfg);
            s.setDynamicColor(dyn.isChecked());
            s.secrets().put("TAVILY_KEY", tavily.getText().toString().trim());
            app.reloadLlmConfig();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        Button charBtn = findViewById(R.id.characterBtn);
        updateCharLabel(charBtn, s.character());
        charBtn.setOnClickListener(v -> {
            String next = s.character().equals("girl") ? "boy" : "girl";
            s.setCharacter(next);
            updateCharLabel(charBtn, next);
        });

        findViewById(R.id.accessBtn).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.overlayBtn).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
    }

    private void updateCharLabel(Button b, String style) {
        b.setText(style.equals("girl") ? "Character: Girl (tap to switch)" : "Character: Boy (tap to switch)");
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
    }
}
