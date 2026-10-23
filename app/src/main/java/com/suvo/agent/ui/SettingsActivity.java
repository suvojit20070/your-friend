package com.suvo.agent.ui;

import android.content.Intent;
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
    }
}
