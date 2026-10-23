package com.suvo.agent.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.device.ImageUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Built-in and custom characters: choose, edit, duplicate, import, export, delete. */
public final class CharactersActivity extends BaseActivity {

    private static final int REQ_IMPORT = 91;
    private static final int REQ_EXPORT = 92;
    private String exportId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_characters);
        topAction(R.drawable.ic_add, R.string.character_new, v -> startActivity(new Intent(this, CharacterEditorActivity.class)));
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
        r.note(getString(R.string.characters_intro));
        String active = g.characters.active().id;
        for (CharacterSpec c : g.characters.list()) {
            TextView trailing = null;
            if (c.id.equals(active)) trailing = r.chip(getString(R.string.character_active), Ui.accent(this));
            String sub = (c.description.isEmpty() ? c.personality : c.description)
                    + "\n" + (c.builtin ? getString(R.string.character_builtin) : getString(R.string.character_custom));
            if (trailing != null && trailing.getParent() != null) ((LinearLayout) trailing.getParent()).removeView(trailing);
            r.row(R.drawable.ic_character, c.name, sub, trailing, v -> actions(c));
        }
        r.section(R.string.character_share);
        r.row(R.drawable.ic_download, getString(R.string.character_import), getString(R.string.character_import_desc), null, v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json"), REQ_IMPORT));
        r.note(getString(R.string.character_rig_note));
        Ui.tintAccent(this, box);
    }

    private void actions(CharacterSpec c) {
        String[] items = {
                getString(R.string.character_use),
                getString(R.string.character_edit),
                getString(R.string.character_duplicate),
                getString(R.string.character_export),
                getString(c.builtin ? R.string.character_reset : R.string.delete)
        };
        Ui.show(Ui.dialog(this).setTitle(c.name).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    g.characters.setActive(c.id);
                    Ui.toast(this, getString(R.string.character_now_active, c.name));
                    build();
                    break;
                case 1:
                    startActivity(new Intent(this, CharacterEditorActivity.class).putExtra(CharacterEditorActivity.EXTRA_ID, c.id));
                    break;
                case 2:
                    try {
                        CharacterSpec copy = c.copy();
                        copy.id = "";
                        copy.builtin = false;
                        copy.name = getString(R.string.character_copy_name, c.name);
                        g.characters.save(copy);
                        build();
                    } catch (Exception e) {
                        Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
                    }
                    break;
                case 3:
                    exportId = c.id;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, c.id + ".character.json"), REQ_EXPORT);
                    break;
                default:
                    Ui.confirm(this, c.builtin ? R.string.character_reset : R.string.delete,
                            getString(c.builtin ? R.string.character_reset_confirm : R.string.character_delete_confirm, c.name), () -> {
                                g.characters.delete(c.id);
                                build();
                            });
            }
        }));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) return;
                    CharacterSpec c = g.characters.importJson(new String(ImageUtil.readAll(in, 512 * 1024), StandardCharsets.UTF_8));
                    Ui.toast(this, getString(R.string.character_imported, c.name));
                }
            } else if (requestCode == REQ_EXPORT && exportId != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) os.write(g.characters.exportJson(exportId).getBytes(StandardCharsets.UTF_8));
                }
                Ui.toast(this, R.string.data_exported);
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
        build();
    }
}
