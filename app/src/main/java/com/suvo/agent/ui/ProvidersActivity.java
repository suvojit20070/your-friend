package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.core.llm.ProviderConfig;

import java.util.List;

/** Configured OpenAI-compatible providers. */
public final class ProvidersActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_providers);
        topAction(R.drawable.ic_add, R.string.provider_add, v -> startActivity(new Intent(this, ProviderEditorActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.providers_intro));
        List<ProviderConfig> list = g.providers.list();
        if (list.isEmpty()) {
            r.note(getString(R.string.providers_empty));
            r.button(R.string.provider_add, true, v -> startActivity(new Intent(this, ProviderEditorActivity.class)));
        }
        for (ProviderConfig p : list) {
            ImageView star = new ImageView(this);
            star.setImageResource(p.isDefault ? R.drawable.ic_star_filled : R.drawable.ic_star);
            star.setContentDescription(getString(R.string.provider_default));
            star.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            star.setOnClickListener(v -> {
                g.providers.setDefault(p.id);
                onResume();
            });
            String sub = p.model + "\n" + p.baseUrl + (p.enabled ? "" : "\n" + getString(R.string.provider_disabled))
                    + (p.apiKey.isEmpty() ? "\n" + getString(R.string.provider_no_key) : "");
            r.row(R.drawable.ic_provider, p.name, sub, star,
                    v -> startActivity(new Intent(this, ProviderEditorActivity.class).putExtra(ProviderEditorActivity.EXTRA_ID, p.id)));
        }
        Ui.tintAccent(this, box);
    }
}
