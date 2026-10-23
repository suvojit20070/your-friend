package com.suvo.agent.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.plugins.CustomToolAdapter;
import com.suvo.agent.plugins.CustomToolDef;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * Creates or edits a custom tool (Python via Termux, or an HTTP request). Saving validates the
 * definition and registers it; "Test" runs it through the normal executor, including approval.
 */
public final class CustomToolEditorActivity extends BaseActivity {

    public static final String EXTRA_NAME = "name";

    static final String DEFAULT_SCHEMA = "{\n  \"type\": \"object\",\n  \"properties\": {\n    \"text\": {\"type\": \"string\", \"description\": \"Input text\"}\n  },\n  \"required\": [\"text\"]\n}";
    static final String DEFAULT_CODE = "# Arguments arrive as JSON on stdin (already validated against the schema).\n"
            + "# Print plain text, or JSON {\"status\": \"ok\"|\"error\", \"message\": ..., \"data\": {...}}.\n"
            + "import json, sys\n\nargs = json.load(sys.stdin)\ntext = args.get(\"text\", \"\")\n"
            + "print(json.dumps({\"status\": \"ok\", \"message\": text.upper()}))\n";

    private CustomToolDef def;
    private String originalName;
    private EditText name, description, version, schema, code, url, headers, body, secretHeader, secret, timeout;
    private Spinner kind, method, risk;
    private com.google.android.material.materialswitch.MaterialSwitch enabled;
    private LinearLayout pythonBox, httpBox;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        String n = getIntent().getStringExtra(EXTRA_NAME);
        def = n != null ? g.customTools.get(n) : null;
        if (def == null) {
            def = new CustomToolDef();
            def.code = DEFAULT_CODE;
            try {
                def.schema = new JSONObject(DEFAULT_SCHEMA);
            } catch (Exception ignored) {
            }
        }
        originalName = def.id >= 0 ? def.name : null;
        setTopBar(originalName != null ? originalName : getString(R.string.tools_custom_new));
        topAction(R.drawable.ic_check, R.string.save, v -> save());
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        Rows r = new Rows(this, box);
        r.note(getString(R.string.custom_tool_intro));

        name = field(box, R.string.custom_tool_name, def.name, false);
        name.setHint("my_tool");
        description = field(box, R.string.custom_tool_description, def.description, true);
        version = field(box, R.string.custom_tool_version, def.version, false);

        label(box, R.string.custom_tool_kind);
        kind = spinner(box, new String[]{"python", "http"}, def.kind);
        label(box, R.string.custom_tool_risk);
        String[] risks = {"READ_ONLY", "LOW", "MEDIUM", "HIGH"};
        risk = spinner(box, risks, def.risk.name());

        schema = field(box, R.string.custom_tool_schema, def.schema == null ? DEFAULT_SCHEMA : pretty(def.schema), true);
        schema.setTypeface(android.graphics.Typeface.MONOSPACE);

        pythonBox = new LinearLayout(this);
        pythonBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(pythonBox);
        code = field(pythonBox, R.string.custom_tool_code, def.code, true);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setMinLines(8);
        TextView pyNote = new TextView(this, null, 0, R.style.YF_Caption);
        pyNote.setText(R.string.custom_tool_python_note);
        pythonBox.addView(pyNote);

        httpBox = new LinearLayout(this);
        httpBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(httpBox);
        label(httpBox, R.string.custom_tool_method);
        method = spinner(httpBox, new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"}, def.method);
        url = field(httpBox, R.string.custom_tool_url, def.url, false);
        url.setHint("https://api.example.com/search?q={{text}}");
        headers = field(httpBox, R.string.custom_tool_headers, def.headers.length() == 0 ? "{}" : pretty(def.headers), true);
        body = field(httpBox, R.string.custom_tool_body, def.bodyTemplate, true);
        body.setHint("{\"query\": {{text}}}");
        secretHeader = field(httpBox, R.string.custom_tool_secret_header, def.secretHeader, false);
        secretHeader.setHint("Authorization");
        secret = field(httpBox, R.string.custom_tool_secret, def.secret, false);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView httpNote = new TextView(this, null, 0, R.style.YF_Caption);
        httpNote.setText(R.string.custom_tool_http_note);
        httpBox.addView(httpNote);

        timeout = field(box, R.string.custom_tool_timeout, String.valueOf(def.timeoutS), false);
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);

        enabled = Widgets.newSwitch(this);
        enabled.setTextColor(getColor(R.color.text_primary));
        enabled.setTextSize(15f);
        enabled.setText(R.string.custom_tool_enabled);
        enabled.setChecked(def.enabled);
        enabled.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        box.addView(enabled);

        status = new TextView(this, null, 0, R.style.YF_Mono);
        status.setTextIsSelectable(true);
        StringBuilder last = new StringBuilder();
        if (!def.lastResult.isEmpty()) last.append(getString(R.string.custom_tool_last_result)).append('\n').append(def.lastResult);
        if (!def.lastError.isEmpty()) last.append(getString(R.string.custom_tool_last_error)).append('\n').append(def.lastError);
        status.setText(last);
        box.addView(status);

        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                updateKind();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        updateKind();

        r.button(R.string.custom_tool_test, false, v -> test());
        r.button(R.string.save, true, v -> save());
        if (originalName != null) {
            r.button(R.string.copy_json, false, v -> {
                CustomToolDef d = read(false);
                if (d != null) {
                    Ui.copy(this, pretty(d.toJson(false)));
                    Ui.toast(this, R.string.copied);
                }
            });
            Button del = r.button(R.string.delete, false, v -> Ui.confirm(this, R.string.delete, getString(R.string.custom_tool_delete_confirm, originalName), () -> {
                try {
                    g.tools.unregister(originalName);
                } catch (IllegalArgumentException ignored) {
                }
                g.customTools.delete(originalName);
                finish();
            }));
            del.setBackgroundResource(R.drawable.btn_danger);
        }
        Ui.tintAccent(this, box);
    }

    private void updateKind() {
        boolean py = "python".equals(kind.getSelectedItem());
        pythonBox.setVisibility(py ? View.VISIBLE : View.GONE);
        httpBox.setVisibility(py ? View.GONE : View.VISIBLE);
    }

    private static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private void label(LinearLayout box, int res) {
        TextView t = new TextView(this, null, 0, R.style.YF_Label);
        t.setText(res);
        box.addView(t);
    }

    private EditText field(LinearLayout box, int labelRes, String value, boolean multi) {
        label(box, labelRes);
        EditText e = new EditText(this, null, 0, R.style.YF_Input);
        e.setText(value);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(3);
            e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Spinner spinner(LinearLayout box, String[] values, String current) {
        Spinner s = Widgets.styleSpinner(new Spinner(this));
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(current)) s.setSelection(i);
        box.addView(s);
        return s;
    }

    /** Reads the form; shows the problem and returns null when invalid. */
    private CustomToolDef read(boolean forSave) {
        CustomToolDef d = new CustomToolDef();
        d.id = def.id;
        d.name = name.getText().toString().trim().toLowerCase(Locale.ROOT);
        d.description = description.getText().toString().trim();
        d.version = version.getText().toString().trim().isEmpty() ? "1.0" : version.getText().toString().trim();
        d.kind = String.valueOf(kind.getSelectedItem());
        d.risk = RiskLevel.valueOf(String.valueOf(risk.getSelectedItem()));
        try {
            d.schema = new JSONObject(schema.getText().toString());
        } catch (Exception e) {
            status.setText(getString(R.string.custom_tool_bad_json, getString(R.string.custom_tool_schema), e.getMessage()));
            return null;
        }
        d.code = code.getText().toString();
        d.method = String.valueOf(method.getSelectedItem());
        d.url = url.getText().toString().trim();
        try {
            String h = headers.getText().toString().trim();
            d.headers = h.isEmpty() ? new JSONObject() : new JSONObject(h);
        } catch (Exception e) {
            status.setText(getString(R.string.custom_tool_bad_json, getString(R.string.custom_tool_headers), e.getMessage()));
            return null;
        }
        d.bodyTemplate = body.getText().toString();
        d.secretHeader = secretHeader.getText().toString().trim();
        d.secret = secret.getText().toString();
        try {
            d.timeoutS = Integer.parseInt(timeout.getText().toString().trim());
        } catch (NumberFormatException e) {
            d.timeoutS = 60;
        }
        d.enabled = enabled.isChecked();
        d.lastResult = def.lastResult;
        d.lastError = def.lastError;
        String err = d.validate();
        if (err != null) {
            status.setText(err);
            return null;
        }
        Tool existing = g.tools.get(d.name);
        if (existing != null && existing.spec().builtin) {
            status.setText(getString(R.string.tools_name_taken));
            return null;
        }
        if (forSave && !d.name.equals(originalName) && g.customTools.get(d.name) != null) {
            status.setText(getString(R.string.tools_name_taken));
            return null;
        }
        return d;
    }

    private boolean persist(CustomToolDef d) {
        try {
            if (originalName != null && !originalName.equals(d.name)) {
                try {
                    g.tools.unregister(originalName);
                } catch (IllegalArgumentException ignored) {
                }
                g.customTools.delete(originalName);
                d.id = -1;
            }
            g.customTools.save(d);
            if (d.enabled) g.tools.register(new CustomToolAdapter(g, d));
            else {
                try {
                    g.tools.unregister(d.name);
                } catch (IllegalArgumentException ignored) {
                }
            }
            def = d;
            originalName = d.name;
            return true;
        } catch (Exception e) {
            status.setText(getString(R.string.error_prefix, e.getMessage()));
            return false;
        }
    }

    private void save() {
        CustomToolDef d = read(true);
        if (d == null || !persist(d)) return;
        Ui.toast(this, R.string.saved);
        finish();
    }

    /** Saves (registers), then runs the tool once through the executor with user-supplied arguments. */
    private void test() {
        CustomToolDef d = read(true);
        if (d == null) return;
        if (!d.enabled) {
            status.setText(R.string.custom_tool_enable_to_test);
            return;
        }
        if (!persist(d)) return;
        Ui.prompt(this, R.string.custom_tool_test_args, sampleArgs(d.schema), true, json -> {
            status.setText(R.string.custom_tool_running);
            String argsJson = json.trim().isEmpty() ? "{}" : json.trim();
            g.io.execute(() -> {
                ToolResult res = g.executor.execute(new ToolCall("test_" + UUID.randomUUID().toString().substring(0, 8), d.name, argsJson),
                        "manual-test", new CancellationToken(), null);
                String text = res.status + "\n" + res.toModelContent(6000);
                runOnUiThread(() -> status.setText(text));
            });
        });
    }

    /** Builds {"prop": ""} placeholders from the schema so the user only fills values. */
    static String sampleArgs(JSONObject schema) {
        JSONObject out = new JSONObject();
        JSONObject props = schema == null ? null : schema.optJSONObject("properties");
        if (props != null) {
            java.util.Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject p = props.optJSONObject(k);
                String type = p == null ? "string" : p.optString("type", "string");
                try {
                    switch (type) {
                        case "integer":
                        case "number":
                            out.put(k, 0);
                            break;
                        case "boolean":
                            out.put(k, false);
                            break;
                        case "array":
                            out.put(k, new org.json.JSONArray());
                            break;
                        case "object":
                            out.put(k, new JSONObject());
                            break;
                        default:
                            out.put(k, "");
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return pretty(out);
    }
}
