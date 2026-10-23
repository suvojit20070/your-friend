package com.suvo.agent.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.util.CancellationToken;

import java.util.List;
import java.util.Map;

/** Create/edit a provider, test the connection and list models. */
public final class ProviderEditorActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";

    /** name, base URL, default model, vision. Presets only prefill fields; nothing is assumed. */
    static final String[][] PRESETS = {
            {"Custom", "", "", "0"},
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "1"},
            {"OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "1"},
            {"Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "0"},
            {"Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "0"},
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "0"},
            {"Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "0"},
            {"Google Gemini (OpenAI-compatible)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "1"},
            {"Ollama (this device / LAN)", "http://127.0.0.1:11434/v1", "llama3.2", "0"},
            {"LM Studio (LAN)", "http://192.168.1.10:1234/v1", "local-model", "0"},
    };

    private ProviderConfig cfg;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_editor);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        cfg = id >= 0 ? g.providers.get(id) : null;
        if (cfg == null) {
            cfg = new ProviderConfig();
            cfg.isDefault = g.providers.list().isEmpty();
        }
        setTopBar(cfg.id >= 0 ? cfg.name : getString(R.string.provider_add));
        Spinner preset = findViewById(R.id.pe_preset);
        String[] names = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) names[i] = PRESETS[i][0];
        preset.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading || position == 0) return;
                String[] p = PRESETS[position];
                set(R.id.pe_name, p[0]);
                set(R.id.pe_url, p[1]);
                set(R.id.pe_model, p[2]);
                ((Switch) findViewById(R.id.pe_vision)).setChecked("1".equals(p[3]));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        bind();
        findViewById(R.id.pe_test).setOnClickListener(v -> test());
        findViewById(R.id.pe_fetch_models).setOnClickListener(v -> fetchModels());
        findViewById(R.id.pe_save).setOnClickListener(v -> save());
        findViewById(R.id.pe_delete).setVisibility(cfg.id >= 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.pe_delete).setOnClickListener(v -> Ui.confirm(this, R.string.delete, getString(R.string.provider_delete_confirm, cfg.name), () -> {
            g.providers.delete(cfg.id);
            finish();
        }));
        loading = false;
    }

    private void set(int id, String v) {
        ((EditText) findViewById(id)).setText(v);
    }

    private String get(int id) {
        return ((EditText) findViewById(id)).getText().toString().trim();
    }

    private Switch sw(int id) {
        return findViewById(id);
    }

    private void bind() {
        set(R.id.pe_name, cfg.name);
        set(R.id.pe_url, cfg.baseUrl);
        set(R.id.pe_key, cfg.apiKey);
        set(R.id.pe_model, cfg.model);
        StringBuilder h = new StringBuilder();
        for (Map.Entry<String, String> e : cfg.headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        set(R.id.pe_headers, h.toString().trim());
        set(R.id.pe_timeout, String.valueOf(cfg.timeoutMs / 1000));
        set(R.id.pe_max_tokens, cfg.maxTokens > 0 ? String.valueOf(cfg.maxTokens) : "");
        set(R.id.pe_temperature, String.valueOf(cfg.temperature));
        set(R.id.pe_context, String.valueOf(cfg.contextWindowTokens));
        sw(R.id.pe_stream).setChecked(cfg.stream);
        sw(R.id.pe_tools).setChecked(cfg.supportsTools);
        sw(R.id.pe_parallel).setChecked(cfg.supportsParallelToolCalls);
        sw(R.id.pe_vision).setChecked(cfg.supportsVision);
        sw(R.id.pe_system).setChecked(cfg.supportsSystemRole);
        sw(R.id.pe_send_temp).setChecked(cfg.sendTemperature);
        sw(R.id.pe_usage).setChecked(cfg.supportsStreamUsage);
        sw(R.id.pe_enabled).setChecked(cfg.enabled);
        sw(R.id.pe_default).setChecked(cfg.isDefault);
    }

    /** Reads the form into a copy; returns null and shows the error when invalid. */
    private ProviderConfig read() {
        ProviderConfig p = cfg.copy();
        p.name = get(R.id.pe_name);
        p.baseUrl = get(R.id.pe_url);
        p.apiKey = get(R.id.pe_key);
        p.model = get(R.id.pe_model);
        p.headers.clear();
        for (String line : get(R.id.pe_headers).split("\n")) {
            int c = line.indexOf(':');
            if (c > 0) p.headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        try {
            p.timeoutMs = Integer.parseInt(get(R.id.pe_timeout).isEmpty() ? "120" : get(R.id.pe_timeout)) * 1000;
            p.maxTokens = get(R.id.pe_max_tokens).isEmpty() ? 0 : Integer.parseInt(get(R.id.pe_max_tokens));
            p.temperature = get(R.id.pe_temperature).isEmpty() ? 0.7 : Double.parseDouble(get(R.id.pe_temperature));
            p.contextWindowTokens = get(R.id.pe_context).isEmpty() ? 32000 : Integer.parseInt(get(R.id.pe_context));
        } catch (NumberFormatException e) {
            Ui.toast(this, R.string.provider_invalid_number);
            return null;
        }
        p.stream = sw(R.id.pe_stream).isChecked();
        p.supportsTools = sw(R.id.pe_tools).isChecked();
        p.supportsParallelToolCalls = sw(R.id.pe_parallel).isChecked();
        p.supportsVision = sw(R.id.pe_vision).isChecked();
        p.supportsSystemRole = sw(R.id.pe_system).isChecked();
        p.sendTemperature = sw(R.id.pe_send_temp).isChecked();
        p.supportsStreamUsage = sw(R.id.pe_usage).isChecked();
        p.enabled = sw(R.id.pe_enabled).isChecked();
        p.isDefault = sw(R.id.pe_default).isChecked();
        String err = p.validate();
        if (err != null) {
            ((TextView) findViewById(R.id.pe_test_result)).setText(err);
            return null;
        }
        return p;
    }

    private void save() {
        ProviderConfig p = read();
        if (p == null) return;
        try {
            g.providers.save(p);
        } catch (IllegalStateException e) {
            Ui.toast(this, e.getMessage());
            return;
        }
        Ui.toast(this, R.string.saved);
        finish();
    }

    private void test() {
        ProviderConfig p = read();
        if (p == null) return;
        TextView out = findViewById(R.id.pe_test_result);
        out.setText(R.string.provider_testing);
        g.io.execute(() -> {
            String msg;
            try {
                msg = "✓ " + new OpenAiCompatClient(p).testConnection(new CancellationToken());
            } catch (LlmException e) {
                msg = "✗ " + e.userMessage();
            }
            String m = msg;
            runOnUiThread(() -> out.setText(m));
        });
    }

    private void fetchModels() {
        ProviderConfig p = read();
        if (p == null) return;
        g.io.execute(() -> {
            try {
                List<String> models = new OpenAiCompatClient(p).listModels(new CancellationToken());
                runOnUiThread(() -> {
                    if (models.isEmpty()) {
                        Ui.toast(this, R.string.provider_no_models);
                        return;
                    }
                    String[] arr = models.toArray(new String[0]);
                    Ui.show(Ui.dialog(this).setTitle(R.string.provider_model).setItems(arr, (d, w) -> set(R.id.pe_model, arr[w])));
                });
            } catch (LlmException e) {
                runOnUiThread(() -> Ui.toast(this, e.userMessage()));
            }
        });
    }
}
