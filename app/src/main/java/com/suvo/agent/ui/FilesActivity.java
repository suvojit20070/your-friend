package com.suvo.agent.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.files.FileOpsService;
import com.suvo.agent.files.FolderAccess;
import com.suvo.agent.files.SafStore;
import com.suvo.agent.files.ShizukuFs;
import com.suvo.agent.files.Workspace;
import com.suvo.agent.ui.widgets.FasterPullToRefreshLayout;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File manager for the agent workspace (app-private), its trash, folders the user picked (Storage
 * Access Framework) and — with Shizuku — any folder of the phone by path. Each of the user's folders
 * has a switch: only the folders switched on reach the agent.
 *
 * Long press selects (then tap more); copy / move / delete run in {@link FileOpsService}, so a job
 * keeps going when this screen closes. Pull down to refresh.
 */
public final class FilesActivity extends BaseActivity {

    public static final String EXTRA_ADD_FOLDER = "add_folder";
    public static final String EXTRA_PATH = "path";

    private static final int REQ_TREE = 111;
    private static final int REQ_IMPORT = 112;
    private static final int MAX_TEXT_EDIT = 8 * 1024 * 1024;

    /** Copy / move waiting for "Paste here" (kept while the app lives, across screens). */
    private static final List<String> CLIP = new ArrayList<>();
    private static boolean clipMove;

    /** null = roots screen; "/" = workspace root; "ext:Name/..." = picked folder; "sh:/abs" = Shizuku. */
    private String path;
    private FileAccess fa;
    private FasterPullToRefreshLayout refresh;

    /** Selection mode: the selected paths, and the check boxes of the rows on screen. */
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private final Map<String, FileAccess.Entry> shown = new LinkedHashMap<>();
    private boolean selecting;

    private final FileOpsService.Listener opsListener = (job, error) -> {
        if (isFinishing()) return;
        if (error != null) Ui.toast(this, getString(R.string.error_prefix, error));
        build();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        fa = FileAccess.forUser(g.workspace, g.saf);
        path = savedInstanceState != null ? savedInstanceState.getString(EXTRA_PATH) : getIntent().getStringExtra(EXTRA_PATH);
        setTopBar(R.string.section_files);
        topAction(R.drawable.ic_add, R.string.files_new, v -> createMenu());
        refresh = findViewById(R.id.files_refresh);
        refresh.setOnRefreshListener(this::build);
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
        FileOpsService.addListener(opsListener);
        build();
    }

    @Override
    protected void onPause() {
        FileOpsService.removeListener(opsListener);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            endSelection();
            return;
        }
        if (path != null) {
            path = parentOf(path);
            build();
            return;
        }
        super.onBackPressed();
    }

    static String parentOf(String p) {
        if (p == null || p.equals("/")) return null;
        if (ShizukuFs.isShizuku(p)) {
            String real;
            try {
                real = ShizukuFs.real(p);
            } catch (java.io.IOException e) {
                return null;
            }
            if (real.equals("/")) return null;
            int s = real.lastIndexOf('/');
            return s <= 0 ? ShizukuFs.PREFIX + "/" : ShizukuFs.PREFIX + real.substring(0, s);
        }
        String t = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int slash = t.lastIndexOf('/');
        if (FileAccess.isExternal(t)) {
            if (slash < 0) return null; // "ext:Name" → roots
            return t.substring(0, slash);
        }
        if (slash <= 0) return "/";
        return t.substring(0, slash);
    }

    /** Rows shown at first in a folder, and added per "Show more" tap. */
    private static final int PAGE = 120;
    /** Rows built per frame, so even a huge folder never freezes the screen. */
    private static final int ROWS_PER_FRAME = 30;
    /** Bumped on every rebuild; work for an older screen is dropped. */
    private int generation;
    private final Map<String, Integer> scrollByPath = new java.util.HashMap<>();
    private String shownPath;

    private void build() {
        int gen = ++generation;
        android.widget.ScrollView scroll = findViewById(R.id.rows_scroll);
        if (scroll != null && shownPath != null) scrollByPath.put(shownPath, scroll.getScrollY());
        String key = path == null ? "" : path;
        boolean samePath = key.equals(shownPath);
        if (!samePath) {
            selected.clear();
            selecting = false;
        }
        shownPath = key;
        boxes.clear();
        shown.clear();
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        if (path == null) {
            roots(r, gen);
            refresh.setRefreshing(false);
        } else {
            folder(r, gen, box);
        }
        updateBar();
        Ui.tintAccent(this, box);
        if (scroll != null && !samePath) {
            Integer y = scrollByPath.get(key);
            scroll.post(() -> scroll.scrollTo(0, y == null ? 0 : y));
        }
    }

    // ---- Roots -----------------------------------------------------------------------------------

    private boolean shizukuReady() {
        return g.shizuku.state() == ShizukuManager.State.READY;
    }

    private void roots(Rows r, int gen) {
        r.note(getString(R.string.files_intro));
        // The workspace size walks every file: measured in the background, shown when ready.
        TextView wsSub = r.row(R.drawable.ic_files, getString(R.string.files_workspace),
                g.workspace.root().getAbsolutePath() + "\n…", null, v -> go("/"));
        g.io.execute(() -> {
            long size = g.workspace.sizeBytes();
            runOnUiThread(() -> {
                if (gen == generation) wsSub.setText(g.workspace.root().getAbsolutePath() + "\n" + Ui.size(size));
            });
        });
        r.row(R.drawable.ic_delete, getString(R.string.files_trash), getString(R.string.files_trash_desc), null, v -> go("/.trash"));

        r.section(R.string.files_granted);
        List<SafStore.Grant> grants = g.saf.grants();
        List<String> shFolders = FolderAccess.shizukuFolders(g.settings);
        if (grants.isEmpty() && shFolders.isEmpty()) r.note(getString(R.string.files_no_grants));
        for (SafStore.Grant gr : grants) {
            String root = "ext:" + gr.name;
            folderRow(r, gr.name, root, () -> Ui.confirm(this, R.string.files_revoke, getString(R.string.files_revoke_confirm, gr.name), () -> {
                g.saf.revoke(gr.treeUri);
                FolderAccess.setForAgent(g.settings, root, false);
                build();
            }));
        }
        for (String abs : shFolders) {
            String root = ShizukuFs.PREFIX + abs;
            folderRow(r, abs, root, () -> Ui.confirm(this, R.string.files_remove_folder, getString(R.string.files_remove_folder_confirm, abs), () -> {
                FolderAccess.removeShizukuFolder(g.settings, abs);
                build();
            }));
        }
        if (!grants.isEmpty() || !shFolders.isEmpty()) r.note(getString(R.string.files_agent_note));

        r.button(R.string.files_add_folder, true, v -> pickTree());
        r.note(getString(R.string.files_saf_note));

        r.section(R.string.files_phone);
        if (shizukuReady()) {
            r.row(R.drawable.ic_files, getString(R.string.files_phone_storage), "/sdcard", null, v -> go(ShizukuFs.PREFIX + "/sdcard"));
            r.row(R.drawable.ic_path, getString(R.string.files_go_to_path), getString(R.string.files_go_to_path_desc), null, v -> goToPath());
        } else {
            r.row(R.drawable.ic_shield, getString(R.string.files_phone_storage), getString(R.string.files_phone_needs_shizuku), null,
                    v -> PermissionFlows.shizuku(this, g.shizuku));
        }
    }

    /** A user folder: tap opens it, the switch gives it to the agent, long press removes it. */
    private void folderRow(Rows r, String title, String root, Runnable remove) {
        com.google.android.material.materialswitch.MaterialSwitch sw = Widgets.newSwitch(this);
        sw.setChecked(FolderAccess.forAgent(g.settings, root));
        sw.setContentDescription(getString(R.string.files_give_agent));
        TextView sub = r.row(R.drawable.ic_files, title, agentLabel(sw.isChecked()), sw, v -> go(root));
        sw.setOnCheckedChangeListener((b, on) -> {
            FolderAccess.setForAgent(g.settings, root, on);
            sub.setText(agentLabel(on));
        });
        View row = (View) sub.getParent().getParent();
        row.setOnLongClickListener(v -> {
            remove.run();
            return true;
        });
    }

    private String agentLabel(boolean on) {
        return getString(on ? R.string.files_agent_on : R.string.files_agent_off);
    }

    private void goToPath() {
        Ui.prompt(this, R.string.files_go_to_path, "/sdcard/", false, text -> {
            String t = text.trim();
            if (t.startsWith("~")) t = "/sdcard" + t.substring(1);
            if (!t.startsWith("/")) t = "/sdcard/" + t;
            try {
                go(ShizukuFs.PREFIX + ShizukuFs.real(t));
            } catch (java.io.IOException e) {
                Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
            }
        });
    }

    private void go(String p) {
        path = p;
        build();
    }

    // ---- A folder --------------------------------------------------------------------------------

    private void folder(Rows r, int gen, LinearLayout box) {
        TextView crumb = r.note(path);
        crumb.setTextIsSelectable(true);
        r.row(R.drawable.ic_back, "..", null, null, v -> {
            path = parentOf(path);
            build();
        });
        boolean inTrash = !FileAccess.isExternal(path) && (path.equals("/.trash") || path.startsWith("/.trash/"));
        if (inTrash) {
            r.button(R.string.files_empty_trash, false, v -> Ui.confirm(this, R.string.files_empty_trash, getString(R.string.files_empty_trash_confirm), () -> {
                run(() -> {
                    g.workspace.emptyTrash();
                    return null;
                });
            }));
        }
        if (ShizukuFs.isShizuku(path)) {
            String abs = path.substring(ShizukuFs.PREFIX.length());
            if (!FolderAccess.shizukuFolders(g.settings).contains(abs) && !abs.equals("/")) {
                r.button(R.string.files_keep_folder, false, v -> {
                    FolderAccess.addShizukuFolder(g.settings, abs);
                    Ui.toast(this, R.string.files_folder_added);
                    build();
                });
            }
        }
        // Listing (a picked folder through the Storage Access Framework, or Shizuku) is slow:
        // never on the main thread.
        android.view.View spin = Progress.loading(this, 40, true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.setMargins(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        if (!refresh.isRefreshing()) r.parent().addView(spin, sp);
        final String at = path;
        g.io.execute(() -> {
            List<FileAccess.Entry> list;
            Exception error = null;
            try {
                list = fa.list(at);
            } catch (Exception ex) {
                list = null;
                error = ex;
            }
            final List<FileAccess.Entry> got = list;
            final Exception err = error;
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) return;
                box.removeView(spin);
                refresh.setRefreshing(false);
                if (err != null) {
                    r.note(getString(R.string.error_prefix, err.getMessage()));
                } else if (got.isEmpty()) {
                    r.note(getString(R.string.files_empty));
                } else {
                    for (FileAccess.Entry e : got) shown.put(e.path, e);
                    addEntries(r, box, got, 0, gen);
                }
            });
        });
    }

    private void addEntries(Rows r, LinearLayout box, List<FileAccess.Entry> list, int from, int gen) {
        int end = Math.min(list.size(), from + PAGE);
        addChunk(r, box, list, from, end, gen);
    }

    private void addChunk(Rows r, LinearLayout box, List<FileAccess.Entry> list, int from, int end, int gen) {
        if (gen != generation || isFinishing()) return;
        int stop = Math.min(end, from + ROWS_PER_FRAME);
        for (int i = from; i < stop; i++) entryRow(r, list.get(i));
        if (stop < end) {
            box.postOnAnimation(() -> addChunk(r, box, list, stop, end, gen));
            return;
        }
        if (end < list.size()) {
            int left = list.size() - end;
            android.widget.Button more = r.button(R.string.files_show_more, false, null);
            more.setText(getString(R.string.files_show_more_count, left));
            more.setOnClickListener(v -> {
                box.removeView(more);
                addEntries(r, box, list, end, gen);
            });
        }
        Ui.tintAccent(this, box);
    }

    private void entryRow(Rows r, FileAccess.Entry e) {
        String sub = e.dir ? getString(R.string.files_folder) : Ui.size(e.size) + " · " + Ui.ago(e.modified);
        CheckBox cb = new CheckBox(this);
        cb.setClickable(false);
        cb.setFocusable(false);
        cb.setButtonTintList(ColorStateList.valueOf(Palette.of(this).primary));
        cb.setChecked(selected.contains(e.path));
        cb.setVisibility(selecting ? View.VISIBLE : View.GONE);
        boxes.put(e.path, cb);
        TextView row = r.row(e.dir ? R.drawable.ic_files : iconFor(e), e.name, sub, cb, v -> {
            if (selecting) toggle(e.path);
            else if (e.dir) go(e.path);
            else openFile(e);
        });
        View parentRow = (View) row.getParent().getParent();
        parentRow.setOnLongClickListener(v -> {
            if (!selecting) startSelection();
            toggle(e.path);
            return true;
        });
    }

    private int iconFor(FileAccess.Entry e) {
        if (e.mime.startsWith("image/")) return R.drawable.ic_eye;
        if (e.mime.startsWith("audio/")) return R.drawable.ic_voice;
        if (e.name.endsWith(".apk")) return R.drawable.ic_download;
        return R.drawable.ic_edit;
    }

    private static boolean isTextLike(FileAccess.Entry e) {
        return e.mime.startsWith("text/") || e.mime.equals("application/json") || e.mime.equals("application/xml")
                || e.mime.equals("application/javascript") || e.mime.equals("application/x-sh")
                || e.name.matches("(?i).*\\.(md|txt|log|py|java|kt|kts|cpp|cc|c|h|hpp|js|mjs|ts|sh|bash|yaml|yml|toml|ini|csv|json|xml|svg"
                + "|html|htm|css|gradle|properties|cfg|conf|rs|go|rb|php|lua|sql|smali|env|gitignore)$")
                || !e.name.contains(".");
    }

    // ---- Selection -------------------------------------------------------------------------------

    private void startSelection() {
        selecting = true;
        for (CheckBox cb : boxes.values()) cb.setVisibility(View.VISIBLE);
        updateBar();
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        for (CheckBox cb : boxes.values()) {
            cb.setChecked(false);
            cb.setVisibility(View.GONE);
        }
        updateBar();
    }

    private void toggle(String p) {
        if (!selected.remove(p)) selected.add(p);
        CheckBox cb = boxes.get(p);
        if (cb != null) cb.setChecked(selected.contains(p));
        if (selected.isEmpty()) endSelection();
        else updateBar();
    }

    private void selectAll() {
        boolean all = selected.size() == shown.size();
        selected.clear();
        if (!all) selected.addAll(shown.keySet());
        for (Map.Entry<String, CheckBox> b : boxes.entrySet()) b.getValue().setChecked(selected.contains(b.getKey()));
        if (selected.isEmpty()) endSelection();
        else updateBar();
    }

    /** The bar under the top bar: selection actions, or "Paste here" while copy / move waits. */
    private void updateBar() {
        LinearLayout bar = findViewById(R.id.files_bar);
        bar.removeAllViews();
        Palette p = Palette.of(this);
        if (selecting && !selected.isEmpty()) {
            bar.setVisibility(View.VISIBLE);
            bar.setBackground(new TonalDrawable(p.surfaceHigh, -1));
            barButton(bar, R.drawable.ic_close, R.string.close, v -> endSelection());
            TextView count = new TextView(this, null, 0, R.style.YF_RowTitle);
            count.setText(String.valueOf(selected.size()));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            bar.addView(count, cl);
            barButton(bar, R.drawable.ic_select_all, R.string.files_select_all, v -> selectAll());
            barButton(bar, R.drawable.ic_copy, R.string.files_copy, v -> clip(false));
            barButton(bar, R.drawable.ic_move, R.string.files_move, v -> clip(true));
            barButton(bar, R.drawable.ic_delete, R.string.delete, v -> deleteSelected());
            barButton(bar, R.drawable.ic_more, R.string.more, v -> moreForSelection(v));
            return;
        }
        if (!CLIP.isEmpty() && path != null) {
            bar.setVisibility(View.VISIBLE);
            bar.setBackground(new TonalDrawable(p.primaryContainer, -1));
            barButton(bar, R.drawable.ic_close, android.R.string.cancel, v -> {
                CLIP.clear();
                updateBar();
            });
            TextView t = new TextView(this, null, 0, R.style.YF_Caption);
            t.setTextColor(p.onPrimaryContainer);
            t.setText(getResources().getQuantityString(clipMove ? R.plurals.files_to_move : R.plurals.files_to_copy, CLIP.size(), CLIP.size()));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            bar.addView(t, tl);
            android.widget.Button paste = new android.widget.Button(this, null, 0, R.style.YF_Button);
            paste.setText(R.string.files_paste_here);
            paste.setAllCaps(false);
            paste.setBackground(new TonalDrawable(p.primary, -1));
            paste.setTextColor(p.onPrimary);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
            pl.setMargins(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            paste.setOnClickListener(v -> paste());
            bar.addView(paste, pl);
            return;
        }
        bar.setVisibility(View.GONE);
    }

    private void barButton(LinearLayout bar, int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setBackground(null);
        b.setColorFilter(Palette.of(this).onSurface);
        int s = Ui.dp(this, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        b.setOnClickListener(l);
        b.setOnLongClickListener(v -> {
            Ui.toast(this, desc);
            return true;
        });
        Fx.press(b);
        bar.addView(b, lp);
    }

    private void clip(boolean move) {
        CLIP.clear();
        CLIP.addAll(selected);
        clipMove = move;
        endSelection();
        Ui.toast(this, R.string.files_pick_destination);
    }

    private void paste() {
        if (path == null || CLIP.isEmpty()) return;
        FileOpsService.start(this, new FileOpsService.Job(clipMove ? FileOpsService.Kind.MOVE : FileOpsService.Kind.COPY, CLIP, path));
        CLIP.clear();
        updateBar();
        Ui.toast(this, R.string.files_op_started);
    }

    private void deleteSelected() {
        List<String> items = new ArrayList<>(selected);
        boolean permanent = false;
        for (String s : items) if (FileAccess.isExternal(s) || s.startsWith("/.trash")) permanent = true;
        String msg = getResources().getQuantityString(permanent ? R.plurals.files_delete_many_forever : R.plurals.files_delete_many,
                items.size(), items.size());
        Ui.confirm(this, R.string.delete, msg, () -> {
            FileOpsService.start(this, new FileOpsService.Job(FileOpsService.Kind.DELETE, items, null));
            endSelection();
            Ui.toast(this, R.string.files_op_started);
        });
    }

    /** One item: open with, share, rename, copy path. Several: share (files only). */
    private void moreForSelection(View anchor) {
        List<String> items = new ArrayList<>(selected);
        if (items.size() == 1) {
            FileAccess.Entry e = shown.get(items.get(0));
            if (e != null) {
                endSelection();
                actions(e);
            }
            return;
        }
        Ui.toast(this, R.string.files_one_at_a_time);
    }

    // ---- Viewer / editor -------------------------------------------------------------------------

    private void openFile(FileAccess.Entry e) {
        if (e.mime.startsWith("image/")) {
            showImage(e);
        } else if (isTextLike(e) && e.size <= MAX_TEXT_EDIT) {
            EditorActivity.open(this, e.path);
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

    // ---- Actions (one item) ----------------------------------------------------------------------

    private void actions(FileAccess.Entry e) {
        List<String> items = new ArrayList<>();
        List<Runnable> does = new ArrayList<>();
        if (!e.dir && isTextLike(e)) {
            items.add(getString(R.string.files_edit));
            does.add(() -> EditorActivity.open(this, e.path));
        }
        items.add(getString(R.string.files_open_with));
        does.add(() -> share(e, false));
        items.add(getString(R.string.files_share));
        does.add(() -> share(e, true));
        items.add(getString(R.string.files_rename));
        does.add(() -> Ui.prompt(this, R.string.files_rename, e.name, false, n -> run(() -> fa.rename(e.path, n.trim()))));
        items.add(getString(R.string.files_copy));
        does.add(() -> {
            selected.clear();
            selected.add(e.path);
            clip(false);
        });
        items.add(getString(R.string.files_move));
        does.add(() -> {
            selected.clear();
            selected.add(e.path);
            clip(true);
        });
        items.add(getString(R.string.files_copy_path));
        does.add(() -> {
            Ui.copy(this, ShizukuFs.isShizuku(e.path) ? e.path.substring(ShizukuFs.PREFIX.length()) : e.path);
            Ui.toast(this, R.string.copied);
        });
        items.add(getString(R.string.delete));
        does.add(() -> Ui.confirm(this, R.string.delete, getString(FileAccess.isExternal(e.path) ? R.string.files_delete_external_confirm
                : R.string.files_delete_confirm, e.name), () -> {
            FileOpsService.start(this, new FileOpsService.Job(FileOpsService.Kind.DELETE, java.util.Collections.singletonList(e.path), null));
            Ui.toast(this, R.string.files_op_started);
        }));
        Ui.show(Ui.dialog(this).setTitle(e.name).setItems(items.toArray(new CharSequence[0]), (d, w) -> does.get(w).run()));
    }

    /** Open with / share: workspace and picked folders directly; a Shizuku file through a copy in the workspace. */
    private void share(FileAccess.Entry e, boolean send) {
        if (e.dir) {
            Ui.toast(this, R.string.files_folder_not_shareable);
            return;
        }
        g.io.execute(() -> {
            Uri u = null;
            try {
                if (ShizukuFs.isShizuku(e.path)) {
                    File dir = new File(g.workspace.root(), ".open");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) Workspace.deleteRecursive(f);
                    File f = new File(dir, e.name);
                    try (InputStream in = fa.open(e.path); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    u = com.suvo.agent.files.WorkspaceProvider.uriFor(this, f);
                } else {
                    u = contentUri(e);
                }
            } catch (Exception ex) {
                u = null;
            }
            final Uri uri = u;
            runOnUiThread(() -> {
                if (uri == null) {
                    Ui.toast(this, R.string.files_cannot_open);
                    return;
                }
                Intent i = send
                        ? new Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri)
                        : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, e.name));
                } catch (Exception ex) {
                    Ui.toast(this, R.string.files_no_app);
                }
            });
        });
    }

    private Uri contentUri(FileAccess.Entry e) {
        try {
            if (e.path.startsWith("ext:")) {
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
            if (shizukuReady()) {
                Ui.show(Ui.dialog(this).setItems(new CharSequence[]{getString(R.string.files_add_folder), getString(R.string.files_go_to_path)},
                        (d, w) -> {
                            if (w == 0) pickTree();
                            else goToPath();
                        }));
            } else {
                pickTree();
            }
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
                Ui.toast(this, R.string.files_folder_added_off);
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
                    return fa.writeStream(dest, in, false);
                }
            });
        }
    }

    /** Workspace size helper for other screens. */
    static String workspaceSummary(Workspace ws) {
        return ws.root().getAbsolutePath() + " · " + Ui.size(ws.sizeBytes());
    }
}
