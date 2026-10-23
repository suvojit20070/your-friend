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
    private static final int MAX_IMAGES = 4;
    private final List<ChatMessage.Image> pendingImages = new ArrayList<>();

    static final int USER = 0, ASSISTANT = 1, TOOL = 2, NOTICE = 3, STREAM = 4, SUMMARY = 5, ERROR = 6;

    static final class Item {
        int type;
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
        speechInput = new SpeechInput(this);
        conversationId = getIntent().getLongExtra(EXTRA_CONVERSATION, g.agent.activeConversation());
        g.agent.setActiveConversation(conversationId);

        topAction(R.drawable.ic_history, R.string.chat_history, v -> showHistory());
        topAction(R.drawable.ic_add, R.string.chat_new, v -> {
            conversationId = g.agent.newConversation();
            reload();
        });
        topAction(R.drawable.ic_character, R.string.chat_switch_character, v -> switchCharacter());
        topAction(R.drawable.ic_camera, R.string.chat_attach_image, v -> {
            if (pendingImages.size() >= MAX_IMAGES) {
                Ui.toast(this, getString(R.string.chat_max_images, MAX_IMAGES));
                return;
            }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE);
        });

        send.setOnClickListener(v -> {
            if (g.agent.isRunning()) g.agent.cancel();
            else submit(input.getText().toString());
        });
        findViewById(R.id.chat_mic).setOnClickListener(v -> toggleMic());
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = items.get(pos);
            if (it.type == TOOL || it.type == SUMMARY) {
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
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) attachImage(data.getData());
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
        if (text.isEmpty() && pendingImages.isEmpty()) return;
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
        setTopBar(conv == null || conv.title.isEmpty() ? getString(R.string.section_chat) : conv.title);
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
                    it.type = toolImage ? NOTICE : USER;
                    it.text = toolImage ? getString(R.string.chat_tool_image, m.images.size()) : m.contentOrEmpty()
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
        adapter.notifyDataSetChanged();
        scrollToEnd();
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

    private void itemMenu(Item it) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.copy));
        if (it.type == ASSISTANT) opts.add(getString(R.string.speak));
        if (it.type == USER || it.type == ERROR) opts.add(getString(R.string.retry));
        Ui.show(Ui.dialog(this).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            if (o.equals(getString(R.string.copy))) Ui.copy(this, it.type == TOOL ? it.detail : it.text);
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

    private void switchCharacter() {
        List<CharacterSpec> chars = g.characters.list();
        String[] names = new String[chars.size()];
        int sel = 0;
        for (int i = 0; i < chars.size(); i++) {
            names[i] = chars.get(i).name;
            if (chars.get(i).id.equals(g.characters.active().id)) sel = i;
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.chat_switch_character).setSingleChoiceItems(names, sel, (d, which) -> {
            g.characters.setActive(chars.get(which).id);
            d.dismiss();
            Ui.toast(this, getString(R.string.character_now_active, chars.get(which).name));
        }));
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
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleMic();
    }

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
            int max = (int) (parent.getWidth() * 0.84f);
            text.setMaxWidth(max > 0 ? max : Integer.MAX_VALUE);
            title.setVisibility(View.GONE);
            detail.setVisibility(View.GONE);
            text.setTextColor(getColor(R.color.text_primary));
            row.setGravity(Gravity.START);
            switch (it.type) {
                case USER:
                    row.setGravity(Gravity.END);
                    bubble.setBackgroundResource(R.drawable.bubble_user);
                    bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.accent(ChatActivity.this)));
                    text.setTextColor(getColor(R.color.text_on_accent));
                    break;
                case ASSISTANT:
                case STREAM:
                    bubble.setBackgroundResource(R.drawable.bubble_assistant);
                    bubble.setBackgroundTintList(null);
                    break;
                case TOOL:
                    bubble.setBackgroundResource(R.drawable.bubble_tool);
                    bubble.setBackgroundTintList(null);
                    title.setVisibility(View.VISIBLE);
                    title.setText(getString(R.string.chat_tool_title, it.title, statusLabel(it.status)));
                    title.setTextColor(statusColor(it.status));
                    if (it.expanded) {
                        detail.setVisibility(View.VISIBLE);
                        detail.setText(it.detail);
                    }
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
            text.setText(it.type == STREAM ? it.text + " ▍" : it.text);
            bubble.setAlpha(it.dim ? 0.55f : 1f);
            bubble.setLayoutParams(lp);
            time.setText(it.time == 0 ? "" : DateFormat.getTimeFormat(ChatActivity.this).format(new Date(it.time))
                    + (it.dim ? " · " + getString(R.string.chat_summarized) : ""));
            time.setGravity(it.type == USER ? Gravity.END : Gravity.START);
            return v;
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
