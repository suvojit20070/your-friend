package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.character.CharacterView;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.data.Settings;

/** First-run setup: meet the companion, language, AI provider, autonomy, permissions. */
public final class OnboardingActivity extends BaseActivity {

    private static final int STEPS = 5;
    private static final String STATE_STEP = "step";
    private int step;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.onboarding_title);
        if (savedInstanceState != null) step = savedInstanceState.getInt(STATE_STEP);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_STEP, step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) {
            step--;
            build();
        } else {
            super.onBackPressed();
        }
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        TextView progress = new TextView(this, null, 0, R.style.YF_Caption);
        progress.setText(getString(R.string.onboarding_step, step + 1, STEPS));
        box.addView(progress);
        switch (step) {
            case 0:
                welcome(box, r);
                break;
            case 1:
                language(r);
                break;
            case 2:
                provider(r);
                break;
            case 3:
                autonomy(r);
                break;
            default:
                permissions(r);
        }
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(0, Ui.dp(this, 20), 0, 0);
        if (step > 0) {
            android.widget.Button back = new android.widget.Button(this, null, 0, R.style.YF_Button_Ghost);
            back.setText(R.string.back);
            back.setOnClickListener(v -> {
                step--;
                build();
            });
            nav.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        android.widget.Button next = new android.widget.Button(this, null, 0, R.style.YF_Button);
        next.setTag("accent");
        next.setText(step == STEPS - 1 ? R.string.onboarding_finish : R.string.next);
        next.setOnClickListener(v -> {
            if (step == STEPS - 1) finishOnboarding();
            else {
                step++;
                build();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Ui.dp(this, 10));
        nav.addView(next, lp);
        box.addView(nav);
        if (step < STEPS - 1) {
            TextView skip = new TextView(this, null, 0, R.style.YF_Caption);
            skip.setText(R.string.onboarding_skip);
            skip.setPadding(0, Ui.dp(this, 16), 0, 0);
            skip.setOnClickListener(v -> finishOnboarding());
            box.addView(skip);
        }
        Ui.tintAccent(this, box);
    }

    private void welcome(LinearLayout box, Rows r) {
        CharacterView cv = new CharacterView(this);
        cv.setCharacter(g.characters.active());
        cv.setQuality(g.settings.str(Settings.ANIMATION_QUALITY, "high"), g.settings.bool(Settings.REDUCED_MOTION, false));
        cv.setDraggable(false);
        cv.setListener(new CharacterView.Listener() {
            @Override
            public void onCharacterTapped(CharacterAnimator.TouchRegion region) {
                cv.react(CharacterAnimator.Expression.HAPPY, 2f);
            }
        });
        box.addView(cv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 300)));
        cv.post(() -> cv.react(CharacterAnimator.Expression.HAPPY, 2.5f));
        TextView title = new TextView(this, null, 0, R.style.YF_Title);
        title.setText(R.string.onboarding_welcome);
        box.addView(title);
        r.note(getString(R.string.onboarding_welcome_body));
        r.section(R.string.onboarding_choose_character);
        String active = g.characters.active().id;
        for (CharacterSpec c : g.characters.list()) {
            r.row(R.drawable.ic_character, c.name + (c.id.equals(active) ? "  ✓" : ""), c.description, null, v -> {
                g.characters.setActive(c.id);
                build();
            });
        }
        r.note(getString(R.string.about_credit));
    }

    private void language(Rows r) {
        r.section(R.string.settings_language);
        r.choiceRow(R.drawable.ic_chat, R.string.settings_language, Settings.UI_LANGUAGE, "",
                new String[]{getString(R.string.lang_system), "English", "Hinglish", "বাংলা", "हिन्दी", "日本語"},
                LocaleHelper.TAGS, v -> {
                    LocaleHelper.setLanguage(this, v);
                    recreate();
                });
        r.textRow(R.drawable.ic_chat, R.string.settings_response_language, Settings.RESPONSE_LANGUAGE, "", false, R.string.settings_response_language_auto, null);
        r.note(getString(R.string.onboarding_language_note));
    }

    private void provider(Rows r) {
        r.section(R.string.onboarding_provider);
        r.note(getString(R.string.onboarding_provider_body));
        boolean has = g.providers.active() != null;
        r.row(R.drawable.ic_provider, getString(has ? R.string.onboarding_provider_ready : R.string.provider_add),
                has ? g.providers.active().name + " · " + g.providers.active().model : getString(R.string.onboarding_provider_missing), null,
                v -> startActivity(new Intent(this, has ? ProvidersActivity.class : ProviderEditorActivity.class)));
        r.note(getString(R.string.onboarding_provider_privacy));
    }

    private void autonomy(Rows r) {
        r.section(R.string.autonomy_mode);
        r.note(getString(R.string.onboarding_autonomy_body));
        r.choiceRow(R.drawable.ic_shield, R.string.autonomy_mode, Settings.AUTONOMY, "CONFIRM",
                new String[]{getString(R.string.autonomy_assist_long), getString(R.string.autonomy_confirm_long), getString(R.string.autonomy_autonomous_long)},
                new String[]{"ASSIST", "CONFIRM", "AUTONOMOUS"}, null);
        r.switchRow(R.drawable.ic_memory, R.string.privacy_auto_memory, R.string.privacy_auto_memory_desc, Settings.AUTO_MEMORY, true, null);
        r.note(getString(R.string.onboarding_autonomy_note));
    }

    private void permissions(Rows r) {
        r.section(R.string.onboarding_permissions);
        r.note(getString(R.string.onboarding_permissions_body));
        Capability[] caps = {Capability.NOTIFICATIONS, Capability.MICROPHONE, Capability.ACCESSIBILITY, Capability.SCREEN_CAPTURE,
                Capability.OVERLAY, Capability.TERMUX, Capability.SHIZUKU};
        for (Capability c : caps) {
            CapabilityState st = g.capabilities.state(c);
            TextView chip = r.chip(PermissionFlows.stateLabel(this, st), PermissionFlows.stateColor(this, st));
            r.row(R.drawable.ic_shield, getString(PermissionFlows.capabilityName(c)), getString(PermissionFlows.capabilityWhy(c)), chip,
                    st == CapabilityState.AVAILABLE ? null : v -> PermissionFlows.fix(this, c));
        }
        r.note(getString(R.string.onboarding_permissions_note));
    }

    private void finishOnboarding() {
        g.settings.put(Settings.ONBOARDED, true);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
