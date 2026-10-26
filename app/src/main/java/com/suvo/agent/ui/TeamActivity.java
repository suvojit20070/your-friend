package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.agent.ChildAgent;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.data.EmployeeRepo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The boss agent's team: create employees (child agents), each with its own LLM provider, role,
 * working instructions and allowed tools. With no employees the boss simply works alone.
 * Opened with {@link #EXTRA_ID} it edits one employee; without it, it lists the team.
 */
public final class TeamActivity extends BaseActivity {

    public static final String EXTRA_ID = "employee_id";
    public static final String EXTRA_NEW = "new_employee";
    private static final int[] STEP_CHOICES = {8, 15, 20, 30, 45, 60};

    private EmployeeRepo.Employee editing;
    private boolean editMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        editMode = id >= 0 || getIntent().getBooleanExtra(EXTRA_NEW, false);
        if (editMode) {
            EmployeeRepo.Employee e = id >= 0 ? g.employees.get(id) : null;
            editing = e != null ? e : new EmployeeRepo.Employee();
            setTopBar(editing.id < 0 ? R.string.team_new : R.string.team_edit);
        } else {
            setTopBar(R.string.team_title);
            topAction(R.drawable.ic_add, R.string.team_add, v -> openEditor(-1));
        }
    }

    private void openEditor(long id) {
        Intent i = new Intent(this, TeamActivity.class);
        if (id >= 0) i.putExtra(EXTRA_ID, id);
        else i.putExtra(EXTRA_NEW, true);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (editMode) buildEditor();
        else buildList();
    }

    private LinearLayout box() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        return box;
    }

    // ------------------------------------------------------------------------------------ list

    private void buildList() {
        LinearLayout box = box();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.team_intro));
        List<EmployeeRepo.Employee> team = g.employees.list();
        r.section(R.string.team_members);
        if (team.isEmpty()) {
            r.note(getString(R.string.team_empty));
        }
        for (EmployeeRepo.Employee e : team) {
            ProviderConfig p = ChildAgent.providerFor(g, e);
            StringBuilder sub = new StringBuilder();
            if (!e.role.isEmpty()) sub.append(e.role).append('\n');
            sub.append(p == null ? getString(R.string.team_no_provider)
                    : getString(e.providerId >= 0 ? R.string.team_uses : R.string.team_uses_boss, p.name + (p.model.isEmpty() ? "" : " · " + p.model)));
            sub.append('\n').append(e.tools.isEmpty() ? getString(R.string.team_tools_all)
                    : getString(R.string.team_tools_count, e.tools.size()));
            if (!e.enabled) sub.append('\n').append(getString(R.string.team_off));
            r.row(R.drawable.ic_character, e.name, sub, null, v -> openEditor(e.id));
        }
        r.parent();
        r.button(R.string.team_add, true, v -> openEditor(-1));
        r.section(R.string.team_how);
        r.note(getString(R.string.team_how_text));
        Ui.tintAccent(this, box);
    }

    // ---------------------------------------------------------------------------------- editor

    private void buildEditor() {
        EmployeeRepo.Employee e = editing;
        LinearLayout box = box();
        Rows r = new Rows(this, box);
        r.section(R.string.team_identity);
        r.row(R.drawable.ic_character, getString(R.string.team_name), e.name.isEmpty() ? getString(R.string.not_set) : e.name, null,
                v -> Ui.prompt(this, R.string.team_name, e.name, false, t -> {
                    e.name = t.trim().replaceAll("\\s+", " ");
                    buildEditor();
                }));
        r.row(R.drawable.ic_star, getString(R.string.team_role), e.role.isEmpty() ? getString(R.string.team_role_hint) : e.role, null,
                v -> Ui.prompt(this, R.string.team_role, e.role, false, t -> {
                    e.role = t.trim();
                    buildEditor();
                }));
        r.row(R.drawable.ic_edit, getString(R.string.team_instructions),
                e.instructions.isEmpty() ? getString(R.string.team_instructions_hint) : e.instructions, null,
                v -> Ui.prompt(this, R.string.team_instructions, e.instructions, true, t -> {
                    e.instructions = t.trim();
                    buildEditor();
                }));

        r.section(R.string.team_brain);
        ProviderConfig p = ChildAgent.providerFor(g, e);
        String providerLabel = e.providerId < 0 ? getString(R.string.team_provider_boss)
                : p == null ? getString(R.string.team_no_provider) : p.name + (p.model.isEmpty() ? "" : " · " + p.model);
        r.row(R.drawable.ic_provider, getString(R.string.team_provider), providerLabel, null, v -> pickProvider());
        r.row(R.drawable.ic_agent, getString(R.string.team_steps), String.valueOf(e.maxSteps), null, v -> pickSteps());
        r.row(R.drawable.ic_tools, getString(R.string.team_tools),
                e.tools.isEmpty() ? getString(R.string.team_tools_all) : String.join(", ", e.tools), null, v -> pickTools());
        LiquidSwitch on = Widgets.newSwitch(this);
        on.setChecked(e.enabled);
        on.setOnCheckedChangeListener((b, val) -> e.enabled = val);
        r.row(R.drawable.ic_check, getString(R.string.team_enabled), getString(R.string.team_enabled_desc), on, v -> on.toggle());
        r.parent();

        r.button(R.string.save, true, v -> save());
        if (e.id >= 0) {
            r.button(R.string.team_delete, false, v -> Ui.confirm(this, R.string.team_delete, getString(R.string.team_delete_confirm, e.name), () -> {
                g.employees.delete(e.id);
                finish();
            }));
        }
        r.note(getString(R.string.team_safety));
        Ui.tintAccent(this, box);
    }

    private void save() {
        EmployeeRepo.Employee e = editing;
        if (e.name.isEmpty()) {
            Ui.toast(this, R.string.team_name_needed);
            return;
        }
        for (EmployeeRepo.Employee other : g.employees.list()) {
            if (other.id != e.id && other.name.equalsIgnoreCase(e.name)) {
                Ui.toast(this, R.string.team_name_taken);
                return;
            }
        }
        g.employees.save(e);
        Ui.toast(this, R.string.saved);
        finish();
    }

    private void pickProvider() {
        List<ProviderConfig> list = g.providers.list();
        String[] labels = new String[list.size() + 1];
        labels[0] = getString(R.string.team_provider_boss);
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            ProviderConfig p = list.get(i);
            labels[i + 1] = p.name + (p.model.isEmpty() ? "" : " · " + p.model) + (p.enabled ? "" : " (" + getString(R.string.provider_disabled) + ")");
            if (p.id == editing.providerId) checked = i + 1;
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.team_provider).setSingleChoiceItems(labels, checked, (d, which) -> {
            editing.providerId = which == 0 ? -1 : list.get(which - 1).id;
            d.dismiss();
            buildEditor();
        }).setNeutralButton(R.string.chat_manage_providers, (d, w) -> startActivity(new Intent(this, ProvidersActivity.class)))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void pickSteps() {
        String[] labels = new String[STEP_CHOICES.length];
        int checked = 0;
        for (int i = 0; i < STEP_CHOICES.length; i++) {
            labels[i] = String.valueOf(STEP_CHOICES[i]);
            if (STEP_CHOICES[i] == editing.maxSteps) checked = i;
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.team_steps).setSingleChoiceItems(labels, checked, (d, which) -> {
            editing.maxSteps = STEP_CHOICES[which];
            d.dismiss();
            buildEditor();
        }).setNegativeButton(android.R.string.cancel, null));
    }

    private void pickTools() {
        List<String> names = new ArrayList<>();
        for (Tool t : g.tools.all()) {
            String n = t.spec().name;
            if (!ChildAgent.BOSS_ONLY.contains(n)) names.add(n);
        }
        String[] labels = names.toArray(new String[0]);
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) checked[i] = editing.tools.contains(labels[i]);
        Ui.show(Ui.dialog(this).setTitle(R.string.team_tools)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    LinkedHashSet<String> sel = new LinkedHashSet<>();
                    for (int i = 0; i < labels.length; i++) if (checked[i]) sel.add(labels[i]);
                    editing.tools = sel;
                    buildEditor();
                })
                .setNeutralButton(R.string.team_tools_all, (d, w) -> {
                    editing.tools = new LinkedHashSet<>();
                    buildEditor();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }
}
