package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.agent.AndroidAgentHost;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.core.agent.PromptAssembler;
import com.suvo.agent.data.Settings;

/**
 * Your Friend — Original project by Suvo.
 *
 * Edit what the agent is told before every conversation:
 * <ul>
 *   <li>your standing instructions (added after the character's personality),</li>
 *   <li>the character's personality (opens the character editor),</li>
 *   <li>the "how to work" guide (on / off, edit, or back to the built-in one),</li>
 *   <li>the fixed safety rules, shown read-only: they keep tool results honest and permissions in
 *   the app's hands, so they cannot be edited,</li>
 *   <li>a preview of the whole prompt as it is assembled now.</li>
 * </ul>
 */
public final class SystemPromptActivity extends BaseActivity {

    private EditText instructions;
    private EditText guide;
    private com.google.android.material.materialswitch.MaterialSwitch useGuide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.sysprompt_title);
        topAction(R.drawable.ic_check, R.string.save, v -> save());
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.sysprompt_intro));

        r.section(R.string.sysprompt_instructions);
        instructions = field(box, g.settings.str(Settings.SYSTEM_INSTRUCTIONS, ""), getString(R.string.sysprompt_instructions_hint), 6);

        r.section(R.string.sysprompt_character);
        CharacterSpec ch = g.characters.active();
        r.row(R.drawable.ic_character, getString(R.string.sysprompt_character_edit, ch.name), getString(R.string.sysprompt_character_desc),
                null, v -> startActivity(new Intent(this, CharacterEditorActivity.class).putExtra(CharacterEditorActivity.EXTRA_ID, ch.id)));

        r.section(R.string.sysprompt_guide);
        useGuide = Widgets.newSwitch(this);
        useGuide.setChecked(g.settings.bool(Settings.INCLUDE_WORK_GUIDE, true));
        r.row(R.drawable.ic_agent, getString(R.string.sysprompt_guide_use), getString(R.string.sysprompt_guide_use_desc), useGuide,
                v -> useGuide.toggle());
        String custom = g.settings.str(Settings.WORK_GUIDE_TEXT, "");
        guide = field(box, custom.isEmpty() ? stripHeading(PromptAssembler.WORK_GUIDE) : custom, "", 8);
        r.button(R.string.sysprompt_guide_reset, false, v -> {
            guide.setText(stripHeading(PromptAssembler.WORK_GUIDE));
            Ui.toast(this, R.string.sysprompt_guide_reset_done);
        });

        r.section(R.string.sysprompt_rules);
        r.note(getString(R.string.sysprompt_rules_desc));
        TextView rules = new TextView(this, null, 0, R.style.YF_Mono);
        rules.setText(PromptAssembler.CORE_RULES);
        rules.setTextIsSelectable(true);
        rules.setBackground(new TonalDrawable(Palette.alpha(Palette.of(this).surfaceHighest, 0.6f), Ui.dp(this, 16)));
        int p = Ui.dp(this, 14);
        rules.setPadding(p, p, p, p);
        rules.setMaxLines(10);
        rules.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        box.addView(rules, rp);
        rules.setOnClickListener(v -> rules.setMaxLines(rules.getMaxLines() == 10 ? Integer.MAX_VALUE : 10));

        r.button(R.string.sysprompt_preview, false, v -> preview());
        r.button(R.string.save, true, v -> save());
    }

    /** A glass multi-line field with its own margins (so its press never clips). */
    private EditText field(LinearLayout box, String text, String hint, int lines) {
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(lines);
        e.setMaxLines(16);
        e.setVerticalScrollBarEnabled(false);
        e.setText(text);
        if (!hint.isEmpty()) e.setHint(hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        box.addView(e, lp);
        Ui.glassField(e, null);
        e.setGravity(Gravity.TOP | Gravity.START);
        return e;
    }

    private static String stripHeading(String s) {
        return s.startsWith("# ") && s.indexOf('\n') > 0 ? s.substring(s.indexOf('\n') + 1) : s;
    }

    private void save() {
        g.settings.put(Settings.SYSTEM_INSTRUCTIONS, instructions.getText().toString().trim());
        String gd = guide.getText().toString().trim();
        // Unchanged built-in guide is stored as "" so app updates of the guide still reach you.
        g.settings.put(Settings.WORK_GUIDE_TEXT, gd.equals(stripHeading(PromptAssembler.WORK_GUIDE).trim()) ? "" : gd);
        g.settings.put(Settings.INCLUDE_WORK_GUIDE, useGuide.isChecked());
        Ui.toast(this, R.string.saved);
    }

    /** The prompt as it would be assembled now (memories, task progress and time are added per request). */
    private void preview() {
        PromptAssembler pa = new PromptAssembler();
        CharacterSpec ch = g.characters.active();
        pa.characterName = ch.name;
        pa.persona = ch.personaPrompt();
        AndroidAgentHost.applyUserPrompt(g, pa);
        pa.userInstructions = instructions.getText().toString();
        String gd = guide.getText().toString().trim();
        pa.workGuideOverride = gd.equals(stripHeading(PromptAssembler.WORK_GUIDE).trim()) ? "" : gd;
        pa.includeWorkGuide = useGuide.isChecked();
        pa.capabilitySummary = g.capabilities.summaryForPrompt();
        pa.skillIndex = g.skills.indexForPrompt();
        pa.nowDescription = getString(R.string.sysprompt_preview_runtime);
        String text = pa.build();
        TextView t = new TextView(this, null, 0, R.style.YF_Mono);
        t.setText(text);
        t.setTextIsSelectable(true);
        Ui.show(Ui.dialog(this).setTitle(getString(R.string.sysprompt_preview_title, text.length())).setView(t)
                .setNegativeButton(R.string.copy, (d, w) -> Ui.copy(this, text))
                .setPositiveButton(R.string.close, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the character editor: the name may have changed.
        if (instructions != null && !isFinishing()) {
            View focus = getCurrentFocus();
            if (focus == null) {
                String ins = instructions.getText().toString();
                String gd = guide.getText().toString();
                boolean on = useGuide.isChecked();
                build();
                instructions.setText(ins);
                guide.setText(gd);
                useGuide.setChecked(on);
            }
        }
    }
}
