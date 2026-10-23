package com.suvo.agent.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.character.CharacterView;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.data.Settings;

import java.util.Arrays;
import java.util.Locale;

/**
 * Edits a character's identity, personality, appearance and animation. Every change re-renders
 * the live preview. Saving a built-in creates/updates its user override (reset restores it).
 */
public final class CharacterEditorActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";

    private static final int[] SWATCHES = {
            0xFF1E1A24, 0xFF3B2A4D, 0xFF5A3A2A, 0xFF8B5A3C, 0xFFD9B26F, 0xFFF2E6C9, 0xFFE8E8F0, 0xFF9AA3B5,
            0xFFE5486B, 0xFFFF8FB1, 0xFF7B5CFF, 0xFF3B82F6, 0xFF1FA796, 0xFF4CAF50, 0xFFE29A1F, 0xFFB23A3A,
            0xFF2B3A67, 0xFF20242E, 0xFFFFFFFF, 0xFFFFE3D3, 0xFFF5D0B5, 0xFFE0B08C, 0xFFB98160, 0xFF7A4E33,
    };

    private CharacterSpec spec;
    private CharacterView preview;
    private boolean isNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_character_editor);
        String id = getIntent().getStringExtra(EXTRA_ID);
        CharacterSpec loaded = id != null ? g.characters.load(id) : null;
        isNew = loaded == null;
        spec = isNew ? newSpec() : loaded.copy();
        setTopBar(isNew ? getString(R.string.character_new) : spec.name);
        topAction(R.drawable.ic_check, R.string.save, v -> save());

        preview = findViewById(R.id.ce_preview);
        preview.setAllow3d(false);
        preview.setQuality(g.settings.str(Settings.ANIMATION_QUALITY, "high"), g.settings.bool(Settings.REDUCED_MOTION, false));
        preview.setDraggable(false);
        preview.setListener(new CharacterView.Listener() {
        });
        preview.setCharacter(spec);

        LinearLayout ex = findViewById(R.id.ce_expressions);
        for (CharacterAnimator.Expression e : CharacterAnimator.Expression.values()) {
            TextView chip = new TextView(this, null, 0, R.style.YF_Chip);
            chip.setText(e.name().toLowerCase(Locale.ROOT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 6));
            chip.setOnClickListener(v -> preview.react(e, 2.5f));
            ex.addView(chip, lp);
        }
        TextView talk = new TextView(this, null, 0, R.style.YF_Chip);
        talk.setText(R.string.character_test_talk);
        talk.setOnClickListener(v -> testTalk());
        ex.addView(talk);

        build();
    }

    private CharacterSpec newSpec() {
        CharacterSpec c = g.characters.active().copy();
        c.id = "";
        c.builtin = false;
        c.name = "";
        c.greeting = "";
        return c;
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);

        r.section(R.string.character_identity);
        text(box, R.string.character_name, spec.name, false, v -> {
            spec.name = v;
        });
        text(box, R.string.character_description, spec.description, true, v -> spec.description = v);
        text(box, R.string.character_personality, spec.personality, true, v -> spec.personality = v);
        text(box, R.string.character_greeting, spec.greeting, false, v -> spec.greeting = v);
        text(box, R.string.character_languages, String.join(", ", spec.languages), false, v -> {
            spec.languages.clear();
            for (String s : v.split(",")) if (!s.trim().isEmpty()) spec.languages.add(s.trim());
        });
        text(box, R.string.character_system_prompt, spec.systemPrompt, true, v -> spec.systemPrompt = v);
        r.note(getString(R.string.character_prompt_note));

        r.section(R.string.character_appearance);
        choice(r, R.string.character_base, new String[]{"girl", "boy"}, spec.base, v -> {
            spec.base = v;
            if ("boy".equals(v) && Arrays.asList("long", "twintails", "bob").contains(spec.hairStyle)) spec.hairStyle = "short";
            if ("girl".equals(v) && "spiky".equals(spec.hairStyle)) spec.hairStyle = "long";
        });
        choice(r, R.string.character_hair_style, new String[]{"long", "twintails", "bob", "short", "spiky"}, spec.hairStyle, v -> spec.hairStyle = v);
        choice(r, R.string.character_eye_shape, new String[]{"round", "sharp"}, spec.eyeShape, v -> spec.eyeShape = v);
        choice(r, R.string.character_outfit, new String[]{"sailor", "hoodie", "blouse", "jacket"}, spec.outfit, v -> spec.outfit = v);
        choice(r, R.string.character_accessory, new String[]{"ribbon", "clip", "glasses", "none"}, spec.accessory, v -> spec.accessory = v);
        color(r, R.string.character_hair_color, spec.hairColor, c -> spec.hairColor = c);
        color(r, R.string.character_eye_color, spec.eyeColor, c -> spec.eyeColor = c);
        color(r, R.string.character_skin_color, spec.skinColor, c -> spec.skinColor = c);
        color(r, R.string.character_outfit_color, spec.outfitColor, c -> spec.outfitColor = c);
        color(r, R.string.character_accent_color, spec.accentColor, c -> spec.accentColor = c);

        r.section(R.string.character_animation);
        choice(r, R.string.character_default_expression, new String[]{"neutral", "happy", "shy", "sleepy", "surprised", "sad", "angry"},
                spec.defaultExpression, v -> spec.defaultExpression = v);
        slider(box, R.string.character_idle_intensity, spec.idleIntensity, 0.2f, 1.5f, v -> spec.idleIntensity = v);
        slider(box, R.string.character_blink_rate, spec.blinkRate, 0.3f, 2.0f, v -> spec.blinkRate = v);
        slider(box, R.string.character_expressiveness, spec.expressiveness, 0.3f, 1.5f, v -> spec.expressiveness = v);
        Switch wander = Widgets.styleSwitch(new Switch(this));
        wander.setTextColor(getColor(R.color.text_primary));
        wander.setTextSize(15f);
        wander.setText(R.string.character_wander);
        wander.setChecked(spec.wander);
        wander.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        wander.setOnCheckedChangeListener((b, on) -> spec.wander = on);
        box.addView(wander);

        r.section(R.string.character_voice);
        text(box, R.string.character_voice_note, spec.voiceNote, false, v -> spec.voiceNote = v);
        r.note(getString(R.string.character_voice_hint));

        r.button(R.string.save, true, v -> save());
        Ui.tintAccent(this, box);
    }

    private interface StrCb {
        void set(String v);
    }

    private interface ColorCb {
        void set(int c);
    }

    private interface FloatCb {
        void set(float v);
    }

    private void text(LinearLayout box, int labelRes, String value, boolean multi, StrCb cb) {
        TextView label = new TextView(this, null, 0, R.style.YF_Label);
        label.setText(labelRes);
        box.addView(label);
        EditText et = new EditText(this, null, 0, R.style.YF_Input);
        et.setText(value);
        if (multi) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setMinLines(2);
            et.setMaxLines(8);
        } else {
            et.setSingleLine(true);
        }
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                cb.set(s.toString());
            }
        });
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void choice(Rows r, int titleRes, String[] values, String current, StrCb cb) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].substring(0, 1).toUpperCase(Locale.ROOT) + values[i].substring(1);
        TextView[] sub = new TextView[1];
        sub[0] = r.row(0, getString(titleRes), Rows.labelFor(current, labels, values), null, v -> {
            int sel = Math.max(0, Arrays.asList(values).indexOf(currentOf(titleRes, current)));
            Ui.show(Ui.dialog(this).setTitle(titleRes).setSingleChoiceItems(labels, sel, (d, w) -> {
                cb.set(values[w]);
                d.dismiss();
                refreshPreview();
                build();
            }));
        });
    }

    /** Reads the live value for a choice (the captured "current" can be stale after a rebuild). */
    private String currentOf(int titleRes, String fallback) {
        if (titleRes == R.string.character_base) return spec.base;
        if (titleRes == R.string.character_hair_style) return spec.hairStyle;
        if (titleRes == R.string.character_eye_shape) return spec.eyeShape;
        if (titleRes == R.string.character_outfit) return spec.outfit;
        if (titleRes == R.string.character_accessory) return spec.accessory;
        if (titleRes == R.string.character_default_expression) return spec.defaultExpression;
        return fallback;
    }

    private void color(Rows r, int titleRes, int current, ColorCb cb) {
        View sw = swatch(current, 28);
        r.row(0, getString(titleRes), CharacterSpec.hex(current), sw, v -> pickColor(titleRes, current, cb));
    }

    private View swatch(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(this, 1), 0x55FFFFFF);
        v.setBackground(d);
        int s = Ui.dp(this, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        v.setLayoutParams(lp);
        return v;
    }

    private void pickColor(int titleRes, int current, ColorCb cb) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 8), pad, 0);
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        LinearLayout line = null;
        for (int i = 0; i < SWATCHES.length; i++) {
            if (i % 8 == 0) {
                line = new LinearLayout(this);
                root.addView(line);
            }
            int c = SWATCHES[i];
            View s = swatch(c, 30);
            s.setContentDescription(CharacterSpec.hex(c));
            s.setOnClickListener(v -> {
                cb.set(c);
                if (dlg[0] != null) dlg[0].dismiss();
                refreshPreview();
                build();
            });
            line.addView(s);
        }
        EditText hex = new EditText(this, null, 0, R.style.YF_Input);
        hex.setSingleLine(true);
        hex.setText(CharacterSpec.hex(current));
        root.addView(hex);
        dlg[0] = Ui.show(Ui.dialog(this).setTitle(titleRes).setView(root)
                .setPositiveButton(R.string.apply, (d, w) -> {
                    int c = CharacterSpec.color(hex.getText().toString().trim(), Integer.MIN_VALUE);
                    if (c == Integer.MIN_VALUE) {
                        Ui.toast(this, R.string.character_bad_color);
                        return;
                    }
                    cb.set(c | 0xFF000000);
                    refreshPreview();
                    build();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void slider(LinearLayout box, int labelRes, float value, float min, float max, FloatCb cb) {
        TextView label = new TextView(this, null, 0, R.style.YF_Label);
        label.setText(getString(labelRes) + "  " + String.format(Locale.ROOT, "%.2f", value));
        box.addView(label);
        SeekBar sb = Widgets.styleSeekBar(new SeekBar(this));
        sb.setMax(100);
        sb.setProgress(Math.round((value - min) / (max - min) * 100f));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = min + (max - min) * p / 100f;
                label.setText(getString(labelRes) + "  " + String.format(Locale.ROOT, "%.2f", v));
                cb.set(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                refreshPreview();
            }
        });
        box.addView(sb);
    }

    private void refreshPreview() {
        preview.setCharacter(spec);
    }

    /** Animated mouth test without needing a TTS engine: a few syllable-like pulses. */
    private void testTalk() {
        preview.setActivity(CharacterAnimator.Activity.SPEAKING);
        final long start = System.currentTimeMillis();
        preview.post(new Runnable() {
            @Override
            public void run() {
                long t = System.currentTimeMillis() - start;
                if (t > 2400) {
                    preview.setMouth(0f, 0f);
                    preview.setActivity(CharacterAnimator.Activity.IDLE);
                    return;
                }
                double ph = t / 1000.0;
                float open = (float) Math.max(0, Math.sin(ph * 2 * Math.PI * 3.2) * 0.8 + Math.sin(ph * 7.1) * 0.2);
                float form = (float) Math.sin(ph * 1.7) * 0.6f;
                preview.setMouth(open, form);
                preview.postDelayed(this, 33);
            }
        });
    }

    private void save() {
        if (spec.name.trim().isEmpty()) {
            Ui.toast(this, R.string.character_name_required);
            return;
        }
        spec.name = spec.name.trim();
        try {
            CharacterSpec saved = g.characters.save(spec);
            if (isNew) {
                Ui.confirm(this, R.string.character_use, getString(R.string.character_use_now, saved.name), () -> {
                    g.characters.setActive(saved.id);
                    finish();
                });
                isNew = false;
                spec = saved;
                return;
            }
            Ui.toast(this, R.string.saved);
            finish();
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
    }
}
