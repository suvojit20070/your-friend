package com.suvo.agent.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.agent.AndroidAgentHost;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.memory.MemoryRanker;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.data.ConversationRepo;
import com.suvo.agent.data.Settings;
import com.suvo.agent.voice.SpeechInput;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full conversation view with streaming, tool-call details, history and voice input. */
public final class ChatActivity extends BaseActivity {

    public static final String EXTRA_PREFILL = "prefill";
    public static final String EXTRA_CONVERSATION = "conversation";
    private static final int REQ_MIC = 21;
    private static final int REQ_IMAGE = 22;
    private static final int REQ_CAMERA = 23;
    private static final int REQ_FILE = 24;
    private static final int REQ_FOLDER = 25;
    private static final int REQ_CAMERA_PERMISSION = 26;
    private static final int MAX_IMAGES = 4;
    private final List<ChatMessage.Image> pendingImages = new ArrayList<>();

    static final int USER = 0, ASSISTANT = 1, TOOL = 2, NOTICE = 3, STREAM = 4, SUMMARY = 5, ERROR = 6, TOOLS = 7;

    static final class Item {
        int type;
        /** Tool call id (TOOL) or the first child's id (TOOLS); keeps open/closed state across reloads. */
        String key = "";
        /** The tools of one step (TOOLS). */
        List<Item> children = new ArrayList<>();
        String title = "";
        String text = "";
        String detail = "";
        long time;
        boolean expanded;
        boolean dim;
        String status = "";
    }

    private final List<Item> items = new ArrayList<>();
    private Adapter adapter;
    private ListView list;
    private EditText input;
    private ImageButton send;
    private long conversationId;
    private Item streamItem;
    private SpeechInput speechInput;
    /** Accordion state that survives list rebuilds while streaming. */
    private final java.util.Set<String> openKeys = new java.util.HashSet<>();

    private final AgentListener agentListener = new AgentListener() {
        @Override
        public void onStatusChanged(TaskState task) {
            updateRunState(statusText(task));
        }

        @Override
        public void onTextDelta(TaskState task, String delta) {
            if (task.conversationId != conversationId) return;
            if (streamItem == null) {
                streamItem = new Item();
                streamItem.type = STREAM;
                streamItem.time = System.currentTimeMillis();
                items.add(streamItem);
            }
            streamItem.text += delta;
            adapter.notifyDataSetChanged();
            scrollToEnd();
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            updateRunState(getString(R.string.status_using_tool, toolName));
        }

        @Override
        public void onAssistantMessage(TaskState task, ChatMessage message) {
            if (task.conversationId == conversationId) reload();
        }

        @Override
        public void onToolProgress(TaskState task, ToolCall call, String message) {
            updateRunState(call.name + ": " + message);
        }

        @Override
        public void onToolResult(TaskState task, ToolCall call, ToolResult result) {
            if (task.conversationId == conversationId) reload();
        }

        @Override
        public void onNotice(TaskState task, String notice) {
            updateRunState(notice);
        }

        @Override
        public void onFinished(TaskState task) {
            if (task.conversationId != conversationId) return;
            reload();
            if (task.status != TaskState.Status.COMPLETED && !task.error.isEmpty()) {
                Item e = new Item();
                e.type = ERROR;
                e.text = task.error;
                e.time = System.currentTimeMillis();
                items.add(e);
                adapter.notifyDataSetChanged();
                scrollToEnd();
            }
            updateRunState(null);
            showMemoryChip();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        list = findViewById(R.id.chat_list);
        input = findViewById(R.id.chat_input);
        send = findViewById(R.id.chat_send);
        adapter = new Adapter();
        list.setAdapter(adapter);
        // Rows must be able to take focus, or selectable message text cannot start a selection.
        list.setItemsCanFocus(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        speechInput = new SpeechInput(this);
        conversationId = getIntent().getLongExtra(EXTRA_CONVERSATION, g.agent.activeConversation());
        g.agent.setActiveConversation(conversationId);

        findViewById(R.id.chat_history).setOnClickListener(v -> showHistory());
        findViewById(R.id.chat_menu).setOnClickListener(v -> showMenu());
        findViewById(R.id.chat_model).setOnClickListener(v -> pickProvider());
        findViewById(R.id.chat_plus).setOnClickListener(v -> showAttachSheet());
        // Liquid glass composer over the scrolling conversation.
        View composer = findViewById(R.id.chat_composer);
        BackdropDrawable.apply(composer, list, Ui.dp(this, 30));
        View bottom = findViewById(R.id.chat_bottom);
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int pad = (b - t) + Ui.dp(this, 8);
            if (list.getPaddingBottom() != pad) list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), pad);
        });
        updateModelTitle();

        send.setOnClickListener(v -> {
            if (g.agent.isRunning()) g.agent.cancel();
            else submit(input.getText().toString());
        });
        findViewById(R.id.chat_mic).setOnClickListener(v -> toggleMic());
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = items.get(pos);
            if (it.type == SUMMARY) {
                it.expanded = !it.expanded;
                adapter.notifyDataSetChanged();
            } else if (it.type == ERROR) {
                retry();
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(items.get(pos));
            return true;
        });
        String prefill = getIntent().getStringExtra(EXTRA_PREFILL);
        if (prefill != null) input.setText(prefill + "\n");
        handleShare(getIntent());
    }

    /** Text or an image shared from another app lands in the input box; nothing is sent automatically. */
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) {
            input.setText(text);
            input.setSelection(input.getText().length());
        }
        android.net.Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null && intent.getType() != null && intent.getType().startsWith("image/")) attachImage(stream);
    }

    private void attachImage(android.net.Uri uri) {
        g.io.execute(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
                android.graphics.Bitmap b = com.suvo.agent.device.ImageUtil.decodeStream(in, 1600);
                ChatMessage.Image img = com.suvo.agent.device.ImageUtil.toImage(com.suvo.agent.device.ImageUtil.jpeg(b, 85), "auto");
                runOnUiThread(() -> {
                    pendingImages.add(img);
                    updateAttachmentHint();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, e.getMessage())));
            }
        });
    }

    private void updateAttachmentHint() {
        input.setHint(pendingImages.isEmpty() ? getString(R.string.chat_input_hint)
                : getResources().getQuantityString(R.plurals.chat_images_pending, pendingImages.size(), pendingImages.size()));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_IMAGE && data != null && data.getData() != null) attachImage(data.getData());
        else if (requestCode == REQ_CAMERA) attachImage(android.net.Uri.fromFile(com.suvo.agent.files.CaptureProvider.file(this)));
        else if (requestCode == REQ_FILE && data != null && data.getData() != null) attachFile(data.getData());
        else if (requestCode == REQ_FOLDER && data != null && data.getData() != null) attachFolder(data.getData());
    }

    // ---------------------------------------------------------------- "Add to conversation" sheet

    private void showAttachSheet() {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        int[][] tiles = {
                {R.drawable.ic_eye, R.string.chat_add_photos},
                {R.drawable.ic_camera, R.string.chat_add_camera},
                {R.drawable.ic_files, R.string.chat_add_file},
                {R.drawable.ic_download, R.string.chat_add_folder}};
        for (int i = 0; i < tiles.length; i++) {
            final int which = i;
            View tile = Fx.tile(this, tiles[i][0], getString(tiles[i][1]), v -> {
                if (sheet[0] != null) sheet[0].dismiss();
                Fx.later(() -> attachAction(which), 180);
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            int m = Ui.dp(this, 5);
            lp.setMargins(m, m, m, m);
            grid.addView(tile, lp);
        }
        sheet[0] = Fx.sheet(this, getString(R.string.chat_add_title), grid);
    }

    private void attachAction(int which) {
        boolean image = which == 0 || which == 1;
        if (image && pendingImages.size() >= MAX_IMAGES) {
            Ui.toast(this, getString(R.string.chat_max_images, MAX_IMAGES));
            return;
        }
        try {
            switch (which) {
                case 0:
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE);
                    break;
                case 1:
                    openCamera();
                    break;
                case 2:
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE);
                    break;
                default:
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_FOLDER);
            }
        } catch (android.content.ActivityNotFoundException e) {
            Ui.toast(this, R.string.chat_no_app);
        }
    }

    /** The phone's own camera app takes the photo (with its preview); it is saved into our cache. */
    private void openCamera() {
        // An app that declares the camera permission must hold it to use the camera app.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            return;
        }
        try {
            android.net.Uri out = com.suvo.agent.files.CaptureProvider.newCapture(this);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(android.content.ClipData.newRawUri("capture", out));
            startActivityForResult(i, REQ_CAMERA);
        } catch (java.io.IOException | android.content.ActivityNotFoundException e) {
            Ui.toast(this, R.string.chat_no_app);
        }
    }

    /** Images are attached for the model to see; other files are copied into the workspace for the file tools. */
    private void attachFile(android.net.Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("image/") && pendingImages.size() < MAX_IMAGES) {
            attachImage(uri);
            return;
        }
        g.io.execute(() -> {
            try {
                String name = "file";
                try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                }
                name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                java.io.File dir = g.workspace.dir("uploads");
                java.io.File dest = new java.io.File(dir, name);
                for (int n = 2; dest.exists(); n++) dest = new java.io.File(dir, n + "_" + name);
                long total = 0;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                    if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        total += r;
                        if (total > 200L * 1024 * 1024) throw new java.io.IOException("larger than 200 MB");
                        out.write(buf, 0, r);
                    }
                }
                String rel = g.workspace.relative(dest);
                runOnUiThread(() -> appendToInput(getString(R.string.chat_file_attached, rel)));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, e.getMessage())));
            }
        });
    }

    /** Shares a folder with the agent (persistent access, used by the file tools). */
    private void attachFolder(android.net.Uri tree) {
        try {
            g.saf.persistGrant(tree);
            String name = tree.getLastPathSegment();
            for (com.suvo.agent.files.SafStore.Grant gr : g.saf.grants()) if (gr.treeUri.equals(tree)) name = gr.name;
            appendToInput(getString(R.string.chat_folder_attached, name));
        } catch (RuntimeException e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
    }

    private void appendToInput(String line) {
        String cur = input.getText().toString();
        input.setText(cur.isEmpty() ? line + "\n" : cur + (cur.endsWith("\n") ? "" : "\n") + line + "\n");
        input.setSelection(input.getText().length());
        input.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String prefill = intent.getStringExtra(EXTRA_PREFILL);
        if (prefill != null) {
            input.setText(prefill + "\n");
            input.setSelection(input.getText().length());
        }
        handleShare(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        g.agent.addListener(agentListener);
        reload();
        TaskState t = g.agent.current();
        updateRunState(g.agent.isRunning() && t != null ? statusText(t) : null);
    }

    @Override
    protected void onStop() {
        g.agent.removeListener(agentListener);
        if (speechInput.isListening()) speechInput.cancel();
        super.onStop();
    }

    private String statusText(TaskState t) {
        switch (t.status) {
            case WAITING_APPROVAL:
                return getString(R.string.status_waiting_approval);
            case PAUSED:
                return getString(R.string.status_paused);
            case RUNNING:
                return t.toolCalls == 0 ? getString(R.string.status_thinking) : getString(R.string.status_working_steps, t.toolCalls);
            default:
                return null;
        }
    }

    private void updateRunState(String text) {
        boolean running = g.agent.isRunning();
        View status = findViewById(R.id.chat_status);
        status.setVisibility(running || findViewById(R.id.chat_memory_chip).getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        findViewById(R.id.chat_progress).setVisibility(running ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.chat_status_text)).setText(running && text != null ? text : "");
        send.setImageResource(running ? R.drawable.ic_stop : R.drawable.ic_send);
        send.setContentDescription(getString(running ? R.string.action_stop : R.string.send));
    }

    private void showMemoryChip() {
        AndroidAgentHost host = g.agent.host();
        TextView chip = findViewById(R.id.chat_memory_chip);
        if (host == null || host.lastRetrieved.isEmpty()) {
            chip.setVisibility(View.GONE);
            return;
        }
        List<MemoryRanker.Scored> used = host.lastRetrieved;
        chip.setText(getResources().getQuantityString(R.plurals.memories_used, used.size(), used.size()));
        chip.setVisibility(View.VISIBLE);
        findViewById(R.id.chat_status).setVisibility(View.VISIBLE);
        chip.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (MemoryRanker.Scored s : used) sb.append("• ").append(s.entry.content).append("\n   ").append(s.why).append("\n\n");
            Ui.show(Ui.dialog(this).setTitle(R.string.memories_used_title).setMessage(sb.toString().trim())
                    .setPositiveButton(android.R.string.ok, null));
        });
    }

    private void submit(String raw) {
        String text = raw.trim();
        if (text.isEmpty() && pendingImages.isEmpty()) {
            Fx.pill(this, getString(R.string.chat_empty_message), findViewById(R.id.chat_composer));
            return;
        }
        if (text.isEmpty()) text = getString(R.string.chat_describe_image);
        if (!pendingImages.isEmpty()) {
            com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
            if (p != null && !p.supportsVision) {
                Ui.toast(this, R.string.chat_no_vision);
                return;
            }
        }
        String err = g.agent.submit(conversationId, text, pendingImages.isEmpty() ? null : new ArrayList<>(pendingImages));
        if (err != null) {
            Ui.toast(this, err);
            if (g.providers.active() == null) open(ProvidersActivity.class);
            return;
        }
        input.setText("");
        pendingImages.clear();
        updateAttachmentHint();
        streamItem = null;
        reload();
        updateRunState(getString(R.string.status_thinking));
    }

    private void retry() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).type == USER) {
                submit(items.get(i).text);
                return;
            }
        }
    }

    /** Rebuilds the list from the database (the single source of truth). */
    private void reload() {
        items.clear();
        streamItem = null;
        ConversationRepo.Conversation conv = g.conversations.get(conversationId);
        if (conv == null) {
            conversationId = g.agent.newConversation();
            conv = g.conversations.get(conversationId);
        }
        ((TextView) findViewById(R.id.chat_conv_title)).setText(conv == null || conv.title.isEmpty() ? getString(R.string.chat_untitled) : conv.title);
        updateModelTitle();
        if (conv != null && !conv.summary.isEmpty()) {
            Item s = new Item();
            s.type = SUMMARY;
            s.title = getString(R.string.chat_summary_title);
            s.text = getString(R.string.chat_summary_hint);
            s.detail = conv.summary;
            s.time = conv.updatedAt;
            items.add(s);
        }
        Map<String, Item> tools = new HashMap<>();
        for (ConversationRepo.StoredMessage sm : g.conversations.messages(conversationId, true)) {
            ChatMessage m = sm.message;
            switch (m.role) {
                case USER: {
                    Item it = new Item();
                    boolean toolImage = m.contentOrEmpty().startsWith("[Image output from");
                    boolean appCheck = m.contentOrEmpty().startsWith(com.suvo.agent.core.agent.FollowThrough.MARKER);
                    it.type = toolImage || appCheck ? NOTICE : USER;
                    it.text = appCheck ? getString(R.string.chat_follow_through) : toolImage ? getString(R.string.chat_tool_image, m.images.size()) : m.contentOrEmpty()
                            + (m.images.isEmpty() ? "" : "\n" + getString(R.string.chat_images_attached, m.images.size()));
                    it.time = sm.createdAt;
                    it.dim = sm.compacted;
                    items.add(it);
                    break;
                }
                case ASSISTANT: {
                    if (!m.contentOrEmpty().trim().isEmpty()) {
                        Item it = new Item();
                        it.type = ASSISTANT;
                        it.text = m.contentOrEmpty().trim();
                        it.time = sm.createdAt;
                        it.dim = sm.compacted;
                        items.add(it);
                    }
                    for (ToolCall c : m.toolCalls) {
                        Item t = new Item();
                        t.type = TOOL;
                        t.key = c.id;
                        t.title = c.name;
                        t.status = "…";
                        t.text = getString(R.string.chat_tool_running);
                        t.detail = getString(R.string.chat_tool_arguments) + "\n" + pretty(com.suvo.agent.core.tools.ToolExecutor.redact(c.argumentsJson));
                        t.time = sm.createdAt;
                        t.dim = sm.compacted;
                        tools.put(c.id, t);
                        items.add(t);
                    }
                    break;
                }
                case TOOL: {
                    Item t = tools.get(m.toolCallId);
                    if (t == null) break;
                    try {
                        JSONObject r = new JSONObject(m.contentOrEmpty());
                        t.status = r.optString("status", "");
                        t.text = r.optString("message", t.status);
                    } catch (JSONException e) {
                        t.status = "";
                        t.text = m.contentOrEmpty();
                    }
                    t.detail += "\n\n" + getString(R.string.chat_tool_result) + "\n" + pretty(m.contentOrEmpty());
                    break;
                }
                default:
                    break;
            }
        }
        groupTools();
        adapter.notifyDataSetChanged();
        scrollToEnd();
    }

    /** Consecutive tool calls become one "N tools" card that opens like an accordion. */
    private void groupTools() {
        List<Item> out = new ArrayList<>();
        Item group = null;
        for (Item it : items) {
            if (it.type == TOOL) {
                if (group == null) {
                    group = new Item();
                    group.type = TOOLS;
                    group.key = "g:" + it.key;
                    group.time = it.time;
                    group.dim = it.dim;
                    group.expanded = openKeys.contains(group.key);
                    out.add(group);
                }
                it.expanded = openKeys.contains(it.key);
                group.children.add(it);
            } else {
                group = null;
                out.add(it);
            }
        }
        items.clear();
        items.addAll(out);
    }

    private static String pretty(String json) {
        try {
            Object v = new org.json.JSONTokener(json).nextValue();
            if (v instanceof JSONObject) {
                String s = ((JSONObject) v).toString(2);
                return s.length() > 6000 ? s.substring(0, 6000) + "\n…" : s;
            }
        } catch (JSONException ignored) {
        }
        return json.length() > 6000 ? json.substring(0, 6000) + "\n…" : json;
    }

    private void scrollToEnd() {
        list.post(() -> list.setSelection(Math.max(0, adapter.getCount() - 1)));
    }

    /** The whole message in a sheet where any part can be selected and copied. */
    private void selectTextSheet(Item it) {
        TextView t = new TextView(this, null, 0, R.style.YF_Body);
        if (it.type == ASSISTANT) {
            if (mdStyle == null) mdStyle = Markdown.styleFor(this);
            t.setText(Markdown.render(it.text, mdStyle));
        } else {
            t.setText(it.text);
        }
        t.setTextSize(16f);
        t.setTextIsSelectable(true);
        t.setMovementMethod(SelectLinkMovement.get());
        t.setLinkTextColor(Ui.accent(this));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dp(this, 460), (int) (getResources().getDisplayMetrics().heightPixels * 0.6f))));
        Fx.sheet(this, getString(R.string.chat_select_text), box);
    }

    private static String toolsText(Item group) {
        StringBuilder sb = new StringBuilder();
        for (Item c : group.children) sb.append(c.title).append(" — ").append(c.text).append('\n').append(c.detail).append("\n\n");
        return sb.toString().trim();
    }

    private void itemMenu(Item it) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.copy));
        if (it.type == ASSISTANT || it.type == USER) opts.add(getString(R.string.chat_select_text));
        if (it.type == ASSISTANT) opts.add(getString(R.string.speak));
        if (it.type == USER || it.type == ERROR) opts.add(getString(R.string.retry));
        Ui.show(Ui.dialog(this).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            if (o.equals(getString(R.string.copy))) Ui.copy(this, it.type == TOOL ? it.detail : it.type == TOOLS ? toolsText(it) : it.text);
            else if (o.equals(getString(R.string.chat_select_text))) selectTextSheet(it);
            else if (o.equals(getString(R.string.speak))) g.speech.speak(it.text);
            else if (o.equals(getString(R.string.retry))) {
                if (it.type == USER) submit(it.text);
                else retry();
            }
        }));
    }

    private void showHistory() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        EditText search = new EditText(this, null, 0, R.style.YF_Input);
        search.setHint(R.string.search);
        box.addView(search);
        ListView lv = new ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));
        List<ConversationRepo.Conversation> convs = new ArrayList<>();
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<String>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ConversationRepo.Conversation c = convs.get(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText((c.pinned ? "★ " : "") + (c.title.isEmpty() ? getString(R.string.chat_untitled) : c.title));
                ((TextView) v.findViewById(android.R.id.text2)).setText(Ui.ago(c.updatedAt) + " · " + getResources().getQuantityString(R.plurals.messages_count, c.messageCount, c.messageCount));
                return v;
            }
        };
        lv.setAdapter(ad);
        Runnable load = () -> {
            convs.clear();
            convs.addAll(g.conversations.list(search.getText().toString(), 200));
            ad.clear();
            for (ConversationRepo.Conversation c : convs) ad.add(c.title);
            ad.notifyDataSetChanged();
        };
        load.run();
        search.addTextChangedListener(new SimpleWatcher(load));
        android.app.AlertDialog d = Ui.show(Ui.dialog(this).setTitle(R.string.chat_history).setView(box).setNegativeButton(android.R.string.cancel, null));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            conversationId = convs.get(pos).id;
            g.agent.setActiveConversation(conversationId);
            d.dismiss();
            reload();
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            ConversationRepo.Conversation c = convs.get(pos);
            String[] opts = {getString(R.string.rename), getString(c.pinned ? R.string.unpin : R.string.pin), getString(R.string.delete)};
            Ui.show(Ui.dialog(this).setItems(opts, (dd, which) -> {
                if (which == 0) {
                    Ui.prompt(this, R.string.rename, c.title, false, t -> {
                        g.conversations.rename(c.id, t.trim());
                        load.run();
                        if (c.id == conversationId) reload();
                    });
                } else if (which == 1) {
                    g.conversations.setPinned(c.id, !c.pinned);
                    load.run();
                } else {
                    Ui.confirm(this, R.string.delete_conversation_title, getString(R.string.delete_conversation_message), () -> {
                        g.conversations.delete(c.id);
                        load.run();
                        if (c.id == conversationId) {
                            conversationId = g.agent.newConversation();
                            reload();
                        }
                    });
                }
            }));
            return true;
        });
    }

    /** Centre of the top bar: the active provider and model; tap to switch. */
    private void updateModelTitle() {
        com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
        TextView t = findViewById(R.id.chat_model_title);
        t.setText(p == null ? getString(R.string.chat_no_provider) + "  ▾" : p.name + "  ▾");
    }

    private void pickProvider() {
        List<com.suvo.agent.core.llm.ProviderConfig> ps = g.providers.list();
        com.suvo.agent.core.llm.ProviderConfig active = g.providers.active();
        List<Fx.Item> menu = new ArrayList<>();
        for (com.suvo.agent.core.llm.ProviderConfig p : ps) {
            menu.add(new Fx.Item(p.name + "\n" + p.model, R.drawable.ic_provider, active != null && p.id == active.id));
        }
        menu.add(new Fx.Item(getString(R.string.chat_manage_providers), R.drawable.ic_settings, false));
        Fx.menu(this, findViewById(R.id.chat_model), getString(R.string.section_providers), menu, which -> {
            if (which == ps.size()) {
                open(ProvidersActivity.class);
                return;
            }
            g.providers.setDefault(ps.get(which).id);
            updateModelTitle();
        });
    }

    private void showMenu() {
        com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
        String[] items = {
                getString(R.string.chat_new),
                getString(R.string.chat_switch_character),
                getString(R.string.chat_thinking) + (p == null ? "" : ": " + thinkingLabel(p.reasoningEffort)),
                getString(R.string.section_agent),
                getString(R.string.section_settings)};
        int[] icons = {R.drawable.ic_add, R.drawable.ic_character, R.drawable.ic_lesson, R.drawable.ic_agent, R.drawable.ic_settings};
        List<Fx.Item> menu = new ArrayList<>();
        for (int i = 0; i < items.length; i++) menu.add(new Fx.Item(items[i], icons[i], false));
        Fx.menu(this, findViewById(R.id.chat_menu), null, menu, which -> {
            switch (which) {
                case 0:
                    conversationId = g.agent.newConversation();
                    reload();
                    break;
                case 1:
                    switchCharacter();
                    break;
                case 2:
                    pickThinking();
                    break;
                case 3:
                    open(LiveAgentActivity.class);
                    break;
                default:
                    open(SettingsActivity.class);
            }
        });
    }

    static final String[] THINKING_VALUES = {"", "none", "low", "medium", "high"};

    private String thinkingLabel(String v) {
        int[] res = {R.string.thinking_default, R.string.thinking_off, R.string.thinking_low, R.string.thinking_medium, R.string.thinking_high};
        for (int i = 0; i < THINKING_VALUES.length; i++) if (THINKING_VALUES[i].equals(v)) return getString(res[i]);
        return v;
    }

    /** Thinking on/off per provider (sent as reasoning_effort). */
    private void pickThinking() {
        com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
        if (p == null) {
            Ui.toast(this, R.string.chat_no_provider);
            return;
        }
        String[] labels = new String[THINKING_VALUES.length];
        int sel = 0;
        for (int i = 0; i < THINKING_VALUES.length; i++) {
            labels[i] = thinkingLabel(THINKING_VALUES[i]);
            if (THINKING_VALUES[i].equals(p.reasoningEffort)) sel = i;
        }
        List<Fx.Item> menu = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) menu.add(new Fx.Item(labels[i], 0, i == sel));
        Fx.menu(this, findViewById(R.id.chat_menu), getString(R.string.chat_thinking), menu, which -> {
            p.reasoningEffort = THINKING_VALUES[which];
            g.providers.save(p);
            Ui.toast(this, getString(R.string.chat_thinking) + ": " + labels[which]);
        });
    }

    private void switchCharacter() {
        List<CharacterSpec> chars = g.characters.list();
        String[] names = new String[chars.size()];
        int sel = 0;
        for (int i = 0; i < chars.size(); i++) {
            names[i] = chars.get(i).name;
            if (chars.get(i).id.equals(g.characters.active().id)) sel = i;
        }
        List<Fx.Item> menu = new ArrayList<>();
        for (int i = 0; i < names.length; i++) menu.add(new Fx.Item(names[i], R.drawable.ic_character, i == sel));
        Fx.menu(this, findViewById(R.id.chat_menu), getString(R.string.chat_switch_character), menu, which -> {
            g.characters.setActive(chars.get(which).id);
            Ui.toast(this, getString(R.string.character_now_active, chars.get(which).name));
        });
    }

    private void toggleMic() {
        if (speechInput.isListening()) {
            speechInput.stop();
            return;
        }
        if (!g.settings.bool(Settings.ALLOW_VOICE_INPUT, true)) {
            Ui.toast(this, R.string.voice_input_disabled);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        ImageButton mic = findViewById(R.id.chat_mic);
        mic.setImageResource(R.drawable.ic_stop);
        g.speech.stop();
        speechInput.start(g.settings.str(Settings.STT_LANGUAGE, ""), new SpeechInput.Callback() {
            @Override
            public void onPartial(String text) {
                input.setText(text);
                input.setSelection(text.length());
            }

            @Override
            public void onFinal(String text) {
                mic.setImageResource(R.drawable.ic_mic);
                if (!text.trim().isEmpty()) submit(text);
            }

            @Override
            public void onError(int code, String message) {
                mic.setImageResource(R.drawable.ic_mic);
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC && ok) toggleMic();
        else if (requestCode == REQ_CAMERA_PERMISSION && ok) openCamera();
    }

    private Markdown.Style mdStyle;

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_message, parent, false);
            Item it = items.get(position);
            LinearLayout row = v.findViewById(R.id.msg_row);
            LinearLayout bubble = v.findViewById(R.id.msg_bubble);
            TextView title = v.findViewById(R.id.msg_title);
            TextView text = v.findViewById(R.id.msg_text);
            TextView detail = v.findViewById(R.id.msg_detail);
            TextView time = v.findViewById(R.id.msg_time);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            int max = (int) (parent.getWidth() * 0.82f);
            text.setMaxWidth(it.type == ASSISTANT || it.type == STREAM ? Integer.MAX_VALUE : (max > 0 ? max : Integer.MAX_VALUE));
            title.setVisibility(View.GONE);
            detail.setVisibility(View.GONE);
            ImageView chevron = v.findViewById(R.id.msg_chevron);
            LinearLayout toolsBox = v.findViewById(R.id.msg_tools);
            chevron.setVisibility(View.GONE);
            chevron.animate().cancel();
            toolsBox.setVisibility(View.GONE);
            toolsBox.removeAllViews();
            bubble.setOnClickListener(null);
            bubble.setOnLongClickListener(null);
            bubble.setClickable(false);
            bubble.setLongClickable(false);
            text.setVisibility(View.VISIBLE);
            text.setTextColor(getColor(R.color.text_primary));
            text.setTextSize(16f);
            row.setGravity(Gravity.START);
            int padH = Ui.dp(ChatActivity.this, 16), padV = Ui.dp(ChatActivity.this, 11);
            bubble.setPadding(padH, padV, padH, padV);
            switch (it.type) {
                case USER:
                    row.setGravity(Gravity.END);
                    bubble.setBackgroundResource(R.drawable.bubble_user);
                    bubble.setBackgroundTintList(null);
                    break;
                case ASSISTANT:
                case STREAM:
                    // Answers read like a document: full width, no bubble.
                    bubble.setBackgroundResource(R.drawable.bubble_assistant);
                    bubble.setBackgroundTintList(null);
                    bubble.setPadding(Ui.dp(ChatActivity.this, 2), Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 2), Ui.dp(ChatActivity.this, 2));
                    break;
                case TOOL:
                    bubble.setBackgroundResource(R.drawable.bubble_tool);
                    bubble.setBackgroundTintList(null);
                    bubble.setPadding(Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 8), Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 8));
                    text.setTextSize(13.5f);
                    text.setTextColor(getColor(R.color.text_secondary));
                    title.setVisibility(View.VISIBLE);
                    title.setText(getString(R.string.chat_tool_title, it.title, statusLabel(it.status)));
                    title.setTextColor(statusColor(it.status));
                    if (it.expanded) {
                        detail.setVisibility(View.VISIBLE);
                        detail.setText(it.detail);
                    }
                    break;
                case TOOLS:
                    bindTools(it, bubble, title, text, chevron, toolsBox);
                    break;
                case SUMMARY:
                    bubble.setBackgroundResource(R.drawable.bubble_tool);
                    bubble.setBackgroundTintList(null);
                    title.setVisibility(View.VISIBLE);
                    title.setText(it.title);
                    title.setTextColor(getColor(R.color.text_secondary));
                    if (it.expanded) {
                        detail.setVisibility(View.VISIBLE);
                        detail.setText(it.detail);
                    }
                    break;
                case ERROR:
                    bubble.setBackgroundResource(R.drawable.bubble_tool);
                    bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.danger)));
                    title.setVisibility(View.VISIBLE);
                    title.setText(R.string.chat_error_tap_retry);
                    title.setTextColor(getColor(R.color.danger));
                    break;
                default: // NOTICE
                    row.setGravity(Gravity.CENTER_HORIZONTAL);
                    bubble.setBackgroundResource(R.drawable.chip);
                    bubble.setBackgroundTintList(null);
                    break;
            }
            boolean prose = it.type == ASSISTANT || it.type == STREAM;
            if (prose) {
                if (mdStyle == null) mdStyle = Markdown.styleFor(ChatActivity.this);
                CharSequence md = Markdown.render(it.text, mdStyle);
                text.setText(it.type == STREAM ? new android.text.SpannableStringBuilder(md).append(" ▍") : md);
            } else if (it.type != TOOLS) {
                text.setText(it.text);
            }
            // Message text (answers and your own messages) can be selected and copied; links in
            // answers still open on a tap.
            boolean selectable = (prose || it.type == USER || it.type == NOTICE) && it.type != STREAM;
            text.setTextIsSelectable(selectable);
            if (selectable) {
                text.setLinkTextColor(Ui.accent(ChatActivity.this));
                text.setMovementMethod(SelectLinkMovement.get());
                text.setFocusable(true);
                text.setFocusableInTouchMode(true);
                text.setLongClickable(true);
            } else {
                text.setMovementMethod(null);
                text.setFocusable(false);
            }
            detail.setTextIsSelectable(detail.getVisibility() == View.VISIBLE);
            // An expanded tool detail would otherwise shrink to the title's width (one word per line).
            lp.width = detail.getVisibility() == View.VISIBLE || prose || it.type == TOOLS ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            bubble.setAlpha(it.dim ? 0.55f : 1f);
            bubble.setLayoutParams(lp);
            time.setText(it.time == 0 ? "" : DateFormat.getTimeFormat(ChatActivity.this).format(new Date(it.time))
                    + (it.dim ? " · " + getString(R.string.chat_summarized) : ""));
            time.setGravity(it.type == USER ? Gravity.END : Gravity.START);
            return v;
        }

        /** "N tools" accordion: the header opens the list; each tool opens its own code box. */
        private void bindTools(Item it, LinearLayout bubble, TextView title, TextView text, ImageView chevron, LinearLayout box) {
            bubble.setBackgroundResource(R.drawable.bubble_tool);
            bubble.setBackgroundTintList(null);
            bubble.setPadding(Ui.dp(ChatActivity.this, 16), Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 12));
            int running = 0, failed = 0;
            for (Item c : it.children) {
                if ("…".equals(c.status) || c.status.isEmpty()) running++;
                else if (!"succeeded".equals(c.status)) failed++;
            }
            title.setVisibility(View.VISIBLE);
            String head = getResources().getQuantityString(R.plurals.chat_tools_count, it.children.size(), it.children.size());
            if (running > 0) head += " · " + getString(R.string.tool_status_running);
            else if (failed > 0) head += " · " + getString(R.string.chat_tools_failed, failed);
            title.setText(head);
            title.setTextColor(running > 0 ? Ui.accent(ChatActivity.this) : failed > 0 ? getColor(R.color.warning) : getColor(R.color.text_secondary));
            title.setTextSize(14f);
            Item last = it.children.get(it.children.size() - 1);
            text.setText(last.title + " — " + last.text);
            text.setTextSize(13.5f);
            text.setTextColor(getColor(R.color.text_secondary));
            text.setMaxLines(2);
            chevron.setVisibility(View.VISIBLE);
            chevron.setRotation(it.expanded ? 90f : 0f);
            if (it.expanded) {
                fillTools(it, box);
                box.setVisibility(View.VISIBLE);
            }
            bubble.setClickable(true);
            bubble.setOnClickListener(x -> {
                it.expanded = !it.expanded;
                if (it.expanded) openKeys.add(it.key);
                else openKeys.remove(it.key);
                Fx.chevron(chevron, it.expanded);
                if (it.expanded) {
                    fillTools(it, box);
                    Fx.expand(box);
                } else {
                    Fx.collapse(box);
                }
            });
            bubble.setOnLongClickListener(x -> {
                itemMenu(it);
                return true;
            });
            Fx.press(bubble);
        }

        private void fillTools(Item group, LinearLayout box) {
            box.removeAllViews();
            for (Item c : group.children) {
                LinearLayout row = new LinearLayout(ChatActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(ChatActivity.this, 10), Ui.dp(ChatActivity.this, 9), Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 9));
                LinearLayout line = new LinearLayout(ChatActivity.this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                View dot = new View(ChatActivity.this);
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d.setColor(statusColor(c.status));
                dot.setBackground(d);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, 8), Ui.dp(ChatActivity.this, 8));
                dp.setMarginEnd(Ui.dp(ChatActivity.this, 10));
                line.addView(dot, dp);
                TextView name = new TextView(ChatActivity.this, null, 0, R.style.YF_Body);
                name.setText(c.title);
                name.setTextSize(14.5f);
                line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView st = new TextView(ChatActivity.this, null, 0, R.style.YF_Caption);
                st.setText(statusLabel(c.status));
                st.setTextColor(statusColor(c.status));
                line.addView(st);
                ImageView arrow = new ImageView(ChatActivity.this);
                arrow.setImageResource(R.drawable.ic_chevron);
                arrow.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary)));
                arrow.setRotation(c.expanded ? 90f : 0f);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, 16), Ui.dp(ChatActivity.this, 16));
                ap.setMarginStart(Ui.dp(ChatActivity.this, 6));
                line.addView(arrow, ap);
                row.addView(line);
                TextView code = new TextView(ChatActivity.this, null, 0, R.style.YF_Mono);
                code.setBackgroundResource(R.drawable.code_bg);
                int pad = Ui.dp(ChatActivity.this, 10);
                code.setPadding(pad, pad, pad, pad);
                code.setTextIsSelectable(true);
                code.setText(c.text + "\n\n" + c.detail);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.topMargin = Ui.dp(ChatActivity.this, 8);
                code.setVisibility(c.expanded ? View.VISIBLE : View.GONE);
                row.addView(code, cp);
                line.setClickable(true);
                line.setOnClickListener(x -> {
                    c.expanded = !c.expanded;
                    if (c.expanded) openKeys.add(c.key);
                    else openKeys.remove(c.key);
                    Fx.chevron(arrow, c.expanded);
                    if (c.expanded) Fx.expand(code);
                    else Fx.collapse(code);
                });
                Fx.press(line);
                box.addView(row);
            }
        }

        private String statusLabel(String s) {
            switch (s) {
                case "succeeded":
                    return getString(R.string.tool_status_succeeded);
                case "failed":
                    return getString(R.string.tool_status_failed);
                case "denied":
                    return getString(R.string.tool_status_denied);
                case "unavailable":
                    return getString(R.string.tool_status_unavailable);
                case "cancelled":
                    return getString(R.string.tool_status_cancelled);
                case "invalid_arguments":
                    return getString(R.string.tool_status_invalid);
                case "timed_out":
                    return getString(R.string.tool_status_timeout);
                default:
                    return getString(R.string.tool_status_running);
            }
        }

        private int statusColor(String s) {
            if ("succeeded".equals(s)) return getColor(R.color.success);
            if ("…".equals(s) || s.isEmpty()) return getColor(R.color.text_secondary);
            if ("denied".equals(s) || "cancelled".equals(s)) return getColor(R.color.warning);
            return getColor(R.color.danger);
        }
    }

    /** TextWatcher that runs a callback after each change. */
    static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable r;

        SimpleWatcher(Runnable r) {
            this.r = r;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            r.run();
        }
    }
}
