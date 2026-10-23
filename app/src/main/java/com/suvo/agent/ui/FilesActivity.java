package com.suvo.agent.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.files.SafStore;
import com.suvo.agent.files.Workspace;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * File manager for the agent workspace (app-private), its trash and user-granted folders
 * (Storage Access Framework). The agent can only reach what is listed here.
 */
public final class FilesActivity extends BaseActivity {

    public static final String EXTRA_ADD_FOLDER = "add_folder";
    public static final String EXTRA_PATH = "path";

    private static final int REQ_TREE = 111;
    private static final int REQ_IMPORT = 112;
    private static final int MAX_TEXT_EDIT = 1024 * 1024;

    /** null = roots screen; "/" = workspace root; "ext:Name/..." = granted folder. */
    private String path;
    private FileAccess fa;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        fa = new FileAccess(g.workspace, g.saf);
        path = savedInstanceState != null ? savedInstanceState.getString(EXTRA_PATH) : getIntent().getStringExtra(EXTRA_PATH);
        setTopBar(R.string.section_files);
        topAction(R.drawable.ic_add, R.string.files_new, v -> createMenu());
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_ADD_FOLDER, false)) pickTree();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_PATH, path);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onBackPressed() {
        if (path != null) {
            path = parentOf(path);
            build();
            return;
        }
        super.onBackPressed();
    }

    private static String parentOf(String p) {
        if (p == null || p.equals("/")) return null;
        String t = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int slash = t.lastIndexOf('/');
        if (FileAccess.isExternal(t)) {
            if (slash < 0) return null; // "ext:Name" → roots
            return t.substring(0, slash);
        }
        if (slash <= 0) return "/";
        return t.substring(0, slash);
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        if (path == null) {
            roots(r);
        } else {
            folder(r);
        }
        Ui.tintAccent(this, box);
    }

    private void roots(Rows r) {
        r.note(getString(R.string.files_intro));
        r.row(R.drawable.ic_files, getString(R.string.files_workspace),
                g.workspace.root().getAbsolutePath() + "\n" + Ui.size(g.workspace.sizeBytes()), null, v -> go("/"));
        r.row(R.drawable.ic_delete, getString(R.string.files_trash), getString(R.string.files_trash_desc), null, v -> go("/.trash"));
        r.section(R.string.files_granted);
        List<SafStore.Grant> grants = g.saf.grants();
        if (grants.isEmpty()) r.note(getString(R.string.files_no_grants));
        for (SafStore.Grant gr : grants) {
            TextView revoke = new TextView(this, null, 0, R.style.YF_Chip);
            revoke.setText(R.string.files_revoke);
            revoke.setOnClickListener(v -> Ui.confirm(this, R.string.files_revoke, getString(R.string.files_revoke_confirm, gr.name), () -> {
                g.saf.revoke(gr.treeUri);
                build();
            }));
            r.row(R.drawable.ic_files, gr.name, "ext:" + gr.name, revoke, v -> go("ext:" + gr.name));
        }
        r.button(R.string.files_add_folder, true, v -> pickTree());
        r.note(getString(R.string.files_saf_note));
    }

    private void go(String p) {
        path = p;
        build();
    }

    private void folder(Rows r) {
        TextView crumb = r.note(path);
        crumb.setTextIsSelectable(true);
        r.row(R.drawable.ic_back, "..", null, null, v -> {
            path = parentOf(path);
            build();
        });
        boolean inTrash = !FileAccess.isExternal(path) && (path.equals("/.trash") || path.startsWith("/.trash/"));
        if (inTrash) {
            r.button(R.string.files_empty_trash, false, v -> Ui.confirm(this, R.string.files_empty_trash, getString(R.string.files_empty_trash_confirm), () -> {
                g.workspace.emptyTrash();
                build();
            }));
        }
        try {
            List<FileAccess.Entry> list = fa.list(path);
            if (list.isEmpty()) r.note(getString(R.string.files_empty));
            for (FileAccess.Entry e : list) {
                String sub = e.dir ? getString(R.string.files_folder) : Ui.size(e.size) + " · " + Ui.ago(e.modified);
                TextView row = r.row(e.dir ? R.drawable.ic_files : iconFor(e), e.name, sub, null, v -> {
                    if (e.dir) go(e.path);
                    else openFile(e);
                });
                View parentRow = (android.view.View) row.getParent().getParent();
                parentRow.setOnLongClickListener(v -> {
                    actions(e);
                    return true;
                });
            }
        } catch (Exception ex) {
            r.note(getString(R.string.error_prefix, ex.getMessage()));
        }
    }

    private int iconFor(FileAccess.Entry e) {
        if (e.mime.startsWith("image/")) return R.drawable.ic_eye;
        if (e.mime.startsWith("audio/")) return R.drawable.ic_voice;
        if (e.name.endsWith(".apk")) return R.drawable.ic_download;
        return R.drawable.ic_edit;
    }

    private static boolean isTextLike(FileAccess.Entry e) {
        return e.mime.startsWith("text/") || e.mime.equals("application/json") || e.mime.equals("application/xml")
                || e.name.matches("(?i).*\\.(md|txt|log|py|java|kt|cpp|c|h|js|ts|sh|yaml|yml|toml|ini|csv|json|xml|html|css|gradle|properties|cfg|conf)$");
    }

    // ---- Viewer / editor ------------------------------------------------------------------------

    private void openFile(FileAccess.Entry e) {
        if (e.mime.startsWith("image/")) {
            showImage(e);
        } else if (isTextLike(e) && e.size <= MAX_TEXT_EDIT) {
            editText(e);
        } else {
            actions(e);
        }
    }

    private void showImage(FileAccess.Entry e) {
        g.io.execute(() -> {
            try {
                byte[] data = fa.read(e.path, 30 * 1024 * 1024);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                runOnUiThread(() -> {
                    if (bmp == null) {
                        Ui.toast(this, R.string.files_cannot_open);
                        return;
                    }
                    ImageView iv = new ImageView(this);
                    iv.setAdjustViewBounds(true);
                    iv.setImageBitmap(bmp);
                    iv.setContentDescription(e.name);
                    Ui.show(Ui.dialog(this).setTitle(e.name).setView(iv).setPositiveButton(R.string.close, null)
                            .setNeutralButton(R.string.tools_actions, (d, w) -> actions(e)));
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, ex.getMessage())));
            }
        });
    }

    private void editText(FileAccess.Entry e) {
        g.io.execute(() -> {
            try {
                String text = new String(fa.read(e.path, MAX_TEXT_EDIT), StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    EditText et = new EditText(this, null, 0, R.style.YF_Input);
                    et.setTypeface(android.graphics.Typeface.MONOSPACE);
                    et.setTextSize(13f);
                    et.setText(text);
                    et.setHorizontallyScrolling(false);
                    et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
                    LinearLayout box = new LinearLayout(this);
                    int pad = Ui.dp(this, 16);
                    box.setPadding(pad, Ui.dp(this, 8), pad, 0);
                    box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    android.widget.ScrollView sv = new android.widget.ScrollView(this);
                    sv.addView(box);
                    Ui.show(Ui.dialog(this).setTitle(e.name).setView(sv)
                            .setPositiveButton(R.string.save, (d, w) -> g.io.execute(() -> {
                                try {
                                    fa.write(e.path, et.getText().toString().getBytes(StandardCharsets.UTF_8), false);
                                    runOnUiThread(() -> {
                                        Ui.toast(this, R.string.saved);
                                        build();
                                    });
                                } catch (Exception ex) {
                                    runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, ex.getMessage())));
                                }
                            }))
                            .setNegativeButton(R.string.close, null)
                            .setNeutralButton(R.string.tools_actions, (d, w) -> actions(e)));
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, ex.getMessage())));
            }
        });
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void actions(FileAccess.Entry e) {
        String[] items = {
                getString(R.string.files_open_with),
                getString(R.string.files_share),
                getString(R.string.files_rename),
                getString(R.string.files_copy_path),
                getString(R.string.delete),
        };
        Ui.show(Ui.dialog(this).setTitle(e.name).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                case 1:
                    if (e.dir) {
                        Ui.toast(this, R.string.files_folder_not_shareable);
                        return;
                    }
                    Uri u = contentUri(e);
                    if (u == null) {
                        Ui.toast(this, R.string.files_cannot_open);
                        return;
                    }
                    Intent i = w == 0
                            ? new Intent(Intent.ACTION_VIEW).setDataAndType(u, e.mime)
                            : new Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(i, e.name));
                    } catch (Exception ex) {
                        Ui.toast(this, R.string.files_no_app);
                    }
                    break;
                case 2:
                    Ui.prompt(this, R.string.files_rename, e.name, false, n -> run(() -> fa.rename(e.path, n.trim())));
                    break;
                case 3:
                    Ui.copy(this, e.path);
                    Ui.toast(this, R.string.copied);
                    break;
                default:
                    Ui.confirm(this, R.string.delete, getString(FileAccess.isExternal(e.path) ? R.string.files_delete_external_confirm
                            : R.string.files_delete_confirm, e.name), () -> run(() -> fa.delete(e.path)));
            }
        }));
    }

    private Uri contentUri(FileAccess.Entry e) {
        try {
            if (FileAccess.isExternal(e.path)) {
                SafStore.Doc d = g.saf.resolve(e.path);
                return d == null ? null : d.uri;
            }
            File f = g.workspace.resolve(e.path);
            return com.suvo.agent.files.WorkspaceProvider.uriFor(this, f);
        } catch (Exception ex) {
            return null;
        }
    }

    private interface IoAction {
        Object run() throws Exception;
    }

    private void run(IoAction a) {
        g.io.execute(() -> {
            try {
                a.run();
                runOnUiThread(this::build);
            } catch (Exception ex) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, ex.getMessage())));
            }
        });
    }

    private void createMenu() {
        if (path == null) {
            pickTree();
            return;
        }
        String[] items = {getString(R.string.files_new_folder), getString(R.string.files_new_file), getString(R.string.files_import)};
        Ui.show(Ui.dialog(this).setItems(items, (d, w) -> {
            String base = path.endsWith("/") ? path : path + "/";
            if (w == 0) {
                Ui.prompt(this, R.string.files_new_folder, "", false, n -> run(() -> fa.mkdirs(base + n.trim())));
            } else if (w == 1) {
                Ui.prompt(this, R.string.files_new_file, "notes.md", false, n -> run(() -> fa.write(base + n.trim(), new byte[0], false)));
            } else {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT);
            }
        }));
    }

    private void pickTree() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_TREE);
        } catch (Exception e) {
            Ui.toast(this, R.string.files_no_picker);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_TREE) {
            try {
                g.saf.persistGrant(uri);
                Ui.toast(this, R.string.files_folder_added);
            } catch (SecurityException e) {
                Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
            }
            build();
        } else if (requestCode == REQ_IMPORT && path != null) {
            String name = "imported";
            try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0).replace('/', '_');
            } catch (RuntimeException ignored) {
            }
            String base = path.endsWith("/") ? path : path + "/";
            String dest = base + name;
            run(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
                    byte[] bytes = com.suvo.agent.device.ImageUtil.readAll(in, 200 * 1024 * 1024);
                    return fa.write(dest, bytes, false);
                }
            });
        }
    }

    /** Workspace size helper for other screens. */
    static String workspaceSummary(Workspace ws) {
        return ws.root().getAbsolutePath() + " · " + Ui.size(ws.sizeBytes());
    }
}
