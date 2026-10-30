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
import android.widget.ScrollView;
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
    private static final int MAX_IMAGES = 8;
    /** Biggest text placed directly in the message (per file / all files together). */
    private static final int INLINE_MAX = 60_000, INLINE_TOTAL = 120_000;

    /** Something attached to the next message: images for the model to see and/or a note / inline text. */
    static final class Pending {
        String label;
        final List<ChatMessage.Image> images = new ArrayList<>();
        String note;
        int inlineChars;
    }

    private final List<Pending> pending = new ArrayList<>();
    private android.widget.LinearLayout chips;

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
        /** Stable list id (so a row being selected is kept by the list while it updates). */
        long id;
        /** First answer of a turn: shows the companion's name above it. */
        boolean header;

        /** Everything the row shows; a row bound to an item with the same signature is left alone. */
        int signature() {
            int h = java.util.Objects.hash(type, key, title, text, detail, status, expanded, dim, time, header);
            for (Item c : children) h = 31 * h + java.util.Objects.hash(c.key, c.title, c.text, c.status, c.expanded, c.detail.length());
            return h;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private Adapter adapter;
    /** The conversation: a scrolling column of message rows (rows are kept, never recycled). */
    private ScrollView list;
    private LinearLayout listBox;
    /** Rows shown from the end of the conversation; "Show earlier" adds more. */
    private int shownLimit = 80;
    /** Scroll to the end on the next layout of the column (content height changes after binding). */
    private boolean followPending;
    /** Text size factor from Settings > Markdown scale. */
    private float textScale = -1f;
    private EditText input;
    private ImageButton send;
    private long conversationId;
    private Item streamItem;
    private SpeechInput speechInput;
    /** Accordion state that survives list rebuilds while streaming. */
    private final java.util.Set<String> openKeys = new java.util.HashSet<>();
    /** True while the user selects text in the list: rebuilds and auto-scroll wait until they finish. */
    private boolean selecting;
    private boolean reloadPending;
    /** The last run's error, kept as a card under the conversation until the next message. */
    private String lastError;
    private long lastErrorAt;
    private long lastErrorConversation = -1;
    private final MarkdownViews.SelectionHook selectionHook = active -> {
        selecting = active;
        if (!active && reloadPending) {
            reloadPending = false;
            reloadSoon();
        }
    };

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
                streamItem.id = Long.MAX_VALUE - 1;
                streamItem.header = needsHeader(items.size());
                items.add(streamItem);
                streamJustStarted = true;
            }
            streamItem.text += delta;
            scheduleStreamRender();
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            updateRunState(getString(R.string.status_using_tool, toolName));
        }

        @Override
        public void onAssistantMessage(TaskState task, ChatMessage message) {
            if (task.conversationId == conversationId) reloadSoon();
        }

        @Override
        public void onToolProgress(TaskState task, ToolCall call, String message) {
            updateRunState(call.name + ": " + message);
        }

        @Override
        public void onToolResult(TaskState task, ToolCall call, ToolResult result) {
            if (task.conversationId == conversationId) reloadSoon();
        }

        @Override
        public void onNotice(TaskState task, String notice) {
            updateRunState(notice);
        }

        @Override
        public void onFinished(TaskState task) {
            if (task.conversationId != conversationId) return;
            if (task.status != TaskState.Status.COMPLETED && !task.error.isEmpty()) {
                lastError = task.error;
                lastErrorAt = System.currentTimeMillis();
                lastErrorConversation = conversationId;
            }
            reload();
            updateRunState(null);
            showMemoryChip();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        list = findViewById(R.id.chat_scroll);
        listBox = findViewById(R.id.chat_list);
        ((android.view.ViewGroup) list.getParent()).setClipChildren(true); // never draw over the top bar
        // The rows colour themselves from the palette; the screen-wide re-theming after every
        // layout pass skips them (it used to swap colours back and forth while scrolling).
        listBox.setTag(R.id.theme_skip, Boolean.TRUE);
        listBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (followPending) list.scrollTo(0, listBox.getHeight());
        });
        input = findViewById(R.id.chat_input);
        send = findViewById(R.id.chat_send);
        adapter = new Adapter();
        speechInput = new SpeechInput(this);
        conversationId = getIntent().getLongExtra(EXTRA_CONVERSATION, g.agent.activeConversation());
        g.agent.setActiveConversation(conversationId);

        findViewById(R.id.chat_history).setOnClickListener(v -> showHistory());
        findViewById(R.id.chat_menu).setOnClickListener(v -> showMenu());
        findViewById(R.id.chat_model).setOnClickListener(v -> pickProvider());
        findViewById(R.id.chat_plus).setOnClickListener(v -> showAttachSheet());
        setUpChips();
        // Liquid glass composer over the scrolling conversation.
        View composer = findViewById(R.id.chat_composer);
        BackdropDrawable.apply(composer, list, GlassDrawable.Preset.COMPOSER);
        GlassDrawable.shadow(composer, GlassDrawable.Preset.COMPOSER);
        LiquidPress.attach(composer, LiquidPress.COMPOSER);
        // Attach and send are glass action buttons with primary icons (no solid fill).
        for (int id : new int[]{R.id.chat_plus, R.id.chat_send}) {
            View b = findViewById(id);
            b.setTag(null);
            Shell.cornerButton(b, list);
            ((android.widget.ImageButton) b).setImageTintList(android.content.res.ColorStateList.valueOf(Ui.accent(this)));
            LiquidPress.attach(b, LiquidPress.CORNER);
        }
        setUpScrollToBottom();
        setUpExpandingComposer();
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
        if (stream != null) attachAny(stream);
    }

    private int pendingImageCount() {
        int n = 0;
        for (Pending p : pending) n += p.images.size();
        return n;
    }

    private int pendingInlineChars() {
        int n = 0;
        for (Pending p : pending) n += p.inlineChars;
        return n;
    }

    private boolean modelSeesImages() {
        com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
        return p == null || p.supportsVision;
    }

    private void addPending(Pending p) {
        runOnUiThread(() -> {
            pending.add(p);
            updateAttachmentHint();
        });
    }

    /** Zafiro-style message box: text gets the full width above the buttons once it needs a second line. */
    private void setUpExpandingComposer() {
        ExpandingComposer.install(findViewById(R.id.chat_composer), input,
                new View[]{findViewById(R.id.chat_plus)}, new View[]{findViewById(R.id.chat_mic), send});
    }

    /** Chips above the composer show what goes with the next message; tap one to remove it. */
    /** Glass 40dp circle above the composer while the chat is not at the bottom. */
    private void setUpScrollToBottom() {
        android.widget.ImageButton down = new android.widget.ImageButton(this);
        down.setImageResource(R.drawable.ic_expand);
        down.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.of(this).onSurface));
        down.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 9);
        down.setPadding(p, p, p, p);
        down.setContentDescription(getString(R.string.chat_scroll_bottom));
        BackdropDrawable.apply(down, list, GlassDrawable.Preset.SCROLL);
        GlassDrawable.shadow(down, GlassDrawable.Preset.SCROLL);
        LiquidPress.attach(down, LiquidPress.CAPSULE);
        down.setAlpha(0f);
        down.setVisibility(View.GONE);
        down.setOnClickListener(v -> list.smoothScrollTo(0, listBox.getHeight()));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ViewGroup frame = (ViewGroup) list.getParent();
        frame.addView(down, lp);
        View bottom = findViewById(R.id.chat_bottom);
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            android.widget.FrameLayout.LayoutParams dl = (android.widget.FrameLayout.LayoutParams) down.getLayoutParams();
            int m = (b - t) + Ui.dp(this, 8);
            if (dl.bottomMargin != m) {
                dl.bottomMargin = m;
                down.setLayoutParams(dl);
            }
        });
        final boolean[] shown = {false};
        android.view.ViewTreeObserver.OnScrollChangedListener watch = () -> {
            boolean away = list.canScrollVertically(1);
            if (away == shown[0]) return;
            shown[0] = away;
            if (away) {
                down.setVisibility(View.VISIBLE);
                down.animate().alpha(1f).setDuration(160).start();
            } else {
                down.animate().alpha(0f).setDuration(100).withEndAction(() -> down.setVisibility(View.GONE)).start();
            }
        };
        list.getViewTreeObserver().addOnScrollChangedListener(watch);
        list.getViewTreeObserver().addOnGlobalLayoutListener(watch::onScrollChanged);
    }

    private void updateAttachmentHint() {
        int imgs = pendingImageCount();
        input.setHint(imgs == 0 ? getString(R.string.chat_input_hint)
                : getResources().getQuantityString(R.plurals.chat_images_pending, imgs, imgs));
        if (chips == null) return;
        chips.removeAllViews();
        for (Pending p : pending) {
            TextView c = new TextView(this);
            c.setText(p.label + "  ✕");
            c.setTextColor(getColor(R.color.text_primary));
            c.setTextSize(13);
            c.setMaxLines(1);
            c.setMaxWidth(Ui.dp(this, 220));
            c.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            c.setBackgroundResource(R.drawable.row_bg);
            int ph = Ui.dp(this, 12), pv = Ui.dp(this, 7);
            c.setPadding(ph, pv, ph, pv);
            c.setContentDescription(getString(R.string.chat_remove_attachment, p.label));
            c.setOnClickListener(v -> {
                pending.remove(p);
                updateAttachmentHint();
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 6));
            chips.addView(c, lp);
        }
        ((View) chips.getParent()).setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setUpChips() {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        int pad = Ui.dp(this, 16);
        scroll.setPadding(pad, Ui.dp(this, 4), pad, 0);
        chips = new android.widget.LinearLayout(this);
        chips.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        scroll.addView(chips);
        scroll.setVisibility(View.GONE);
        android.view.ViewGroup bottom = findViewById(R.id.chat_bottom);
        bottom.addView(scroll, bottom.indexOfChild(findViewById(R.id.chat_composer)));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_CAMERA) {
            attachAny(android.net.Uri.fromFile(com.suvo.agent.files.CaptureProvider.file(this)));
        } else if ((requestCode == REQ_IMAGE || requestCode == REQ_FILE) && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) attachAny(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                attachAny(data.getData());
            }
        } else if (requestCode == REQ_FOLDER && data != null && data.getData() != null) {
            attachFolder(data.getData());
        }
    }

    // ---------------------------------------------------------------- "Add to conversation" sheet

    private void showAttachSheet() {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        final Overlay[] sheet = new Overlay[1];
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
        int slots = MAX_IMAGES - pendingImageCount();
        if (image && slots <= 0) {
            Ui.toast(this, getString(R.string.chat_max_images, MAX_IMAGES));
            return;
        }
        try {
            switch (which) {
                case 0:
                    startActivityForResult(mediaPicker(slots), REQ_IMAGE);
                    break;
                case 1:
                    openCamera();
                    break;
                case 2:
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FILE);
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

    /** Photos and videos: the system photo picker where available (no storage permission needed), else the file picker. */
    private Intent mediaPicker(int slots) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES); // images and videos by default
            int max = Math.min(slots, android.provider.MediaStore.getPickImagesMaxLimit());
            if (max > 1) i.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, max);
            if (i.resolveActivity(getPackageManager()) != null) return i;
        }
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, slots > 1);
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

    /**
     * Attaches anything the user picked, natively, to the next request:
     * images → sent as images; videos → a few frames as images; PDFs → first pages as images;
     * text / code → its content placed in the message; everything is also copied to the workspace
     * ("uploads/") so the file tools can use the whole file.
     */
    private void attachAny(android.net.Uri uri) {
        final boolean vision = modelSeesImages();
        g.io.execute(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                String name = displayName(uri);
                if (mime == null || mime.equals("application/octet-stream")) {
                    String guess = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            android.webkit.MimeTypeMap.getFileExtensionFromUrl(name.replace(' ', '_')).toLowerCase(java.util.Locale.ROOT));
                    if (guess != null) mime = guess;
                }
                String m = mime == null ? "" : mime;
                if (m.startsWith("image/") && vision) {
                    attachImage(uri, name);
                    return;
                }
                java.io.File dest = copyToWorkspace(uri, name);
                String rel = g.workspace.relative(dest);
                Pending p = new Pending();
                if (m.startsWith("video/")) {
                    p.label = "🎬 " + name;
                    videoFrames(dest, name, rel, vision, p);
                } else if (m.equals("application/pdf")) {
                    p.label = "📄 " + name;
                    pdfPages(dest, name, rel, vision, p);
                } else if (dest.length() <= 1024 * 1024 && (com.suvo.agent.core.util.Attachments.looksTextual(name, m) || isTextFile(dest))) {
                    p.label = "📝 " + name;
                    int room = Math.min(INLINE_MAX, INLINE_TOTAL - pendingInlineChars());
                    String text = readText(dest);
                    if (room < 2000) {
                        p.note = com.suvo.agent.core.util.Attachments.workspaceFile(name, rel, dest.length(), m);
                    } else {
                        boolean cut = text.length() > room;
                        p.inlineChars = Math.min(text.length(), room);
                        p.note = com.suvo.agent.core.util.Attachments.inline(name, rel, cut ? text.substring(0, room) : text, text.length(), cut);
                    }
                } else {
                    p.label = "📎 " + name;
                    p.note = com.suvo.agent.core.util.Attachments.workspaceFile(name, rel, dest.length(), m);
                }
                addPending(p);
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, e.getMessage())));
            }
        });
    }

    /** Runs on the io thread. */
    private void attachImage(android.net.Uri uri, String name) throws java.io.IOException {
        if (pendingImageCount() >= MAX_IMAGES) {
            runOnUiThread(() -> Ui.toast(this, getString(R.string.chat_max_images, MAX_IMAGES)));
            return;
        }
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
            android.graphics.Bitmap b = com.suvo.agent.device.ImageUtil.decodeStream(in, 1600);
            Pending p = new Pending();
            p.label = "🖼 " + name;
            p.images.add(com.suvo.agent.device.ImageUtil.toImage(com.suvo.agent.device.ImageUtil.jpeg(b, 85), "auto"));
            addPending(p);
        }
    }

    private void videoFrames(java.io.File f, String name, String rel, boolean vision, Pending p) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        double seconds = 0;
        int w = 0, h = 0;
        List<Double> times = new ArrayList<>();
        try {
            r.setDataSource(f.getAbsolutePath());
            seconds = parse(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            w = (int) parse(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = (int) parse(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int n = vision ? Math.min(4, MAX_IMAGES - pendingImageCount()) : 0;
            for (int i = 0; i < n; i++) {
                double t = seconds <= 0 ? 0 : seconds * (i + 0.5) / n;
                long us = (long) (t * 1_000_000L);
                android.graphics.Bitmap b = android.os.Build.VERSION.SDK_INT >= 27
                        ? r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
                        : r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) continue;
                b = com.suvo.agent.device.ImageUtil.scaleToMax(b, 1024);
                p.images.add(com.suvo.agent.device.ImageUtil.toImage(com.suvo.agent.device.ImageUtil.jpeg(b, 80), "low"));
                times.add(t);
            }
        } catch (RuntimeException e) {
            // Unknown codec: the agent still gets the file in the workspace.
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        double[] at = new double[times.size()];
        for (int i = 0; i < at.length; i++) at[i] = times.get(i);
        p.note = com.suvo.agent.core.util.Attachments.video(name, rel, seconds, w, h, at);
    }

    private void pdfPages(java.io.File f, String name, String rel, boolean vision, Pending p) {
        int pages = 0;
        int shown = 0;
        try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
             android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(fd)) {
            pages = pr.getPageCount();
            int n = vision ? Math.min(Math.min(3, pages), MAX_IMAGES - pendingImageCount()) : 0;
            for (int i = 0; i < n; i++) {
                try (android.graphics.pdf.PdfRenderer.Page page = pr.openPage(i)) {
                    float scale = 1400f / Math.max(page.getWidth(), page.getHeight());
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)),
                            Math.max(1, (int) (page.getHeight() * scale)), android.graphics.Bitmap.Config.ARGB_8888);
                    b.eraseColor(android.graphics.Color.WHITE);
                    page.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    p.images.add(com.suvo.agent.device.ImageUtil.toImage(com.suvo.agent.device.ImageUtil.jpeg(b, 85), "high"));
                    shown++;
                }
            }
        } catch (Exception e) {
            // Protected or broken PDF: the file is still in the workspace.
        }
        p.note = com.suvo.agent.core.util.Attachments.pdf(name, rel, pages, shown);
    }

    private static double parse(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String displayName(android.net.Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // file:// uris (camera) have no provider
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "file";
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private java.io.File copyToWorkspace(android.net.Uri uri, String name) throws java.io.IOException {
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
        } catch (java.io.IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw e;
        }
        return dest;
    }

    private static boolean isTextFile(java.io.File f) {
        byte[] head = new byte[4096];
        int n;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            n = in.read(head);
        } catch (java.io.IOException e) {
            return false;
        }
        if (n <= 0) return false;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int c = head[i] & 0xff;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32)) bad++;
        }
        return bad * 20 < n;
    }

    private static String readText(java.io.File f) throws java.io.IOException {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** Shares a folder with the agent (persistent access, used by the file tools). */
    private void attachFolder(android.net.Uri tree) {
        try {
            g.saf.persistGrant(tree);
            String name = tree.getLastPathSegment();
            for (com.suvo.agent.files.SafStore.Grant gr : g.saf.grants()) if (gr.treeUri.equals(tree)) name = gr.name;
            Pending p = new Pending();
            p.label = "📁 " + name;
            p.note = com.suvo.agent.core.util.Attachments.folder(name, devicePath(tree));
            pending.add(p);
            updateAttachmentHint();
        } catch (RuntimeException e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
    }

    /** Real path of a folder picked from the phone's own storage (null for cloud / other providers). */
    private static String devicePath(android.net.Uri tree) {
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        String base = "primary".equals(vol) ? "/storage/emulated/0" : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
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
        // Markdown scale changed in settings: rebuild every row at the new size.
        float k = MarkdownScaleActivity.scale(this);
        if (k != textScale) {
            textScale = k;
            listBox.removeAllViews();
        }
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
        List<ChatMessage.Image> pendingImages = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (Pending p : pending) {
            pendingImages.addAll(p.images);
            if (p.note != null) notes.append("\n\n").append(p.note);
        }
        if (text.isEmpty() && pending.isEmpty()) {
            Fx.pill(this, getString(R.string.chat_empty_message), findViewById(R.id.chat_composer));
            return;
        }
        if (text.isEmpty()) text = getString(notes.length() == 0 ? R.string.chat_describe_image : R.string.chat_check_attachment);
        text = text + notes;
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
        pending.clear();
        updateAttachmentHint();
        lastError = null;
        streamItem = null;
        reload();
        scrollToEnd();
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
        uiHandler.removeCallbacks(reloadRunnable);
        if (selecting) {
            // Rebuilding now would end the selection the user is making; do it when they finish.
            reloadPending = true;
            return;
        }
        uiHandler.removeCallbacks(streamRender);
        streamRenderQueued = false;
        // Stay where the user is reading if they scrolled up; follow the end only when already there.
        final boolean follow = !list.canScrollVertically(1);
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
                    it.text = appCheck ? getString(R.string.chat_follow_through) : toolImage ? getString(R.string.chat_tool_image, m.images.size())
                            : com.suvo.agent.core.util.Attachments.forDisplay(m.contentOrEmpty(), getString(R.string.chat_file_attached), getString(R.string.chat_folder_attached))
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
        if (lastError != null && lastErrorConversation == conversationId) {
            Item e = new Item();
            e.type = ERROR;
            e.text = lastError;
            e.time = lastErrorAt;
            items.add(e);
        }
        assignIds();
        adapter.notifyDataSetChanged();
        if (follow) scrollToEnd();
    }

    /** Stable ids from what each row is, and the companion's name on the first answer of each turn. */
    private void assignIds() {
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            long id = ((long) it.type << 58) ^ (it.time * 1_000_003L) ^ (it.key.hashCode() * 31L);
            while (!used.add(id)) id++;
            it.id = id;
            it.header = it.type == ASSISTANT && needsHeader(i);
        }
    }

    /** True when no answer was shown since the last message of yours before {@code index}. */
    private boolean needsHeader(int index) {
        for (int i = index - 1; i >= 0; i--) {
            int t = items.get(i).type;
            if (t == USER) return true;
            if (t == ASSISTANT || t == STREAM) return false;
        }
        return true;
    }

    private String companionName() {
        try {
            CharacterSpec c = g.characters.active();
            if (c != null && c.name != null && !c.name.trim().isEmpty()) return c.name.trim();
        } catch (RuntimeException ignored) {
        }
        return getString(R.string.app_name);
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

    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reloadRunnable = this::reload;
    private boolean streamRenderQueued;
    private boolean streamJustStarted;
    private long lastStreamRender;
    private static final long STREAM_FRAME_MS = 60;

    /** Tool results and messages arrive in bursts: rebuild the list once per burst, not once per event. */
    private void reloadSoon() {
        uiHandler.removeCallbacks(reloadRunnable);
        uiHandler.postDelayed(reloadRunnable, 120);
    }

    /** Streamed text is drawn at most ~16 times a second, however fast the tokens arrive. */
    private void scheduleStreamRender() {
        if (streamRenderQueued) return;
        streamRenderQueued = true;
        long wait = Math.max(0, lastStreamRender + STREAM_FRAME_MS - android.os.SystemClock.uptimeMillis());
        uiHandler.postDelayed(streamRender, wait);
    }

    private final Runnable streamRender = new Runnable() {
        @Override
        public void run() {
            streamRenderQueued = false;
            lastStreamRender = android.os.SystemClock.uptimeMillis();
            boolean follow = !selecting && (streamJustStarted || !list.canScrollVertically(1));
            streamJustStarted = false;
            adapter.notifyDataSetChanged();
            if (follow) scrollToEnd();
        }
    };

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Shows the end of the conversation (after the rows have been laid out). */
    private void scrollToEnd() {
        followPending = true;
        list.post(() -> {
            list.scrollTo(0, listBox.getHeight());
            // Keep following through the next layout passes (images, code cards measuring late).
            list.postDelayed(() -> followPending = false, 250);
        });
    }

    /** The current item with this id (rows keep their listeners across rebuilds). */
    private Item itemById(long id) {
        for (Item it : items) if (it.id == id) return it;
        return null;
    }

    private static String toolsText(Item group) {
        StringBuilder sb = new StringBuilder();
        for (Item c : group.children) sb.append(c.title).append(" — ").append(c.text).append('\n').append(c.detail).append("\n\n");
        return sb.toString().trim();
    }

    private void itemMenu(Item it) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.copy));
        if (it.type == ASSISTANT) opts.add(getString(R.string.speak));
        if (it.type == USER || it.type == ERROR) opts.add(getString(R.string.retry));
        Ui.show(Ui.dialog(this).setItems(opts.toArray(new String[0]), (d, which) -> {
            String o = opts.get(which);
            if (o.equals(getString(R.string.copy))) Ui.copy(this, it.type == TOOL ? it.detail : it.type == TOOLS ? toolsText(it) : it.text);
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
        Overlay d = Ui.show(Ui.dialog(this).setTitle(R.string.chat_history).setView(box).setNegativeButton(android.R.string.cancel, null));
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

    /**
     * The side drawer (glass, like the dialogs): this chat's actions on top, then every section
     * of the app. It replaced the "more" popover menu.
     */
    private void showMenu() {
        com.suvo.agent.core.llm.ProviderConfig p = g.providers.active();
        GlassDrawer d = Nav.drawer(this);
        d.section(getString(R.string.drawer_this_chat));
        d.item(R.drawable.ic_add, getString(R.string.chat_new), false, () -> {
            conversationId = g.agent.newConversation();
            lastError = null;
            reload();
        });
        d.item(R.drawable.ic_history, getString(R.string.chat_history), false, this::showHistory);
        d.item(R.drawable.ic_character, getString(R.string.chat_switch_character), false, this::switchCharacter);
        d.item(R.drawable.ic_lesson, getString(R.string.chat_thinking) + (p == null ? "" : ": " + thinkingLabel(p.reasoningEffort)),
                false, this::pickThinking);
        d.item(R.drawable.ic_provider, getString(R.string.section_providers), false, this::pickProvider);
        Nav.addSections(this, d).show();
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
    /** Parsed answers by text, so scrolling the list does not parse Markdown again. */
    private final android.util.LruCache<String, List<Markdown.Block>> mdCache = new android.util.LruCache<>(64);

    private List<Markdown.Block> blocksFor(String src) {
        List<Markdown.Block> b = mdCache.get(src);
        if (b == null) {
            b = Markdown.blocks(src, mdStyle);
            mdCache.put(src, b);
        }
        return b;
    }

    /** A streaming answer: the same blocks (no syntax colours yet) with a caret after the last text. */
    private List<Markdown.Block> streamBlocks(String src) {
        List<Markdown.Block> blocks = Markdown.blocks(src, mdStyle, false);
        Markdown.Block caret = new Markdown.Block();
        caret.type = Markdown.Block.TEXT;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).type == Markdown.Block.TEXT) {
            sb.append(blocks.remove(blocks.size() - 1).text);
            sb.append(' ');
        }
        int s0 = sb.length();
        sb.append('\u258D');
        sb.setSpan(new android.text.style.ForegroundColorSpan(mdStyle.marker), s0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caret.text = new android.text.SpannedString(sb);
        blocks.add(caret);
        return blocks;
    }

    /**
     * Keeps one row view per item in the column, matched by the item's stable id. A row whose
     * content did not change is left completely alone, so a selection in it survives updates
     * (streaming, tool results). New rows are inserted, gone ones removed; nothing is recycled.
     */
    private final class Adapter {
        private View earlier;

        int getCount() {
            return items.size();
        }

        void notifyDataSetChanged() {
            int start = Math.max(0, items.size() - shownLimit);
            java.util.Set<Long> want = new java.util.HashSet<>();
            for (int i = start; i < items.size(); i++) want.add(items.get(i).id);
            // 1. Drop rows that are no longer shown (the others keep their order).
            java.util.Map<Long, View> byId = new java.util.HashMap<>();
            for (int i = listBox.getChildCount() - 1; i >= 0; i--) {
                View c = listBox.getChildAt(i);
                if (c == earlier) continue;
                Object t = c.getTag(R.id.chat_bound);
                long id = t instanceof long[] ? ((long[]) t)[0] : Long.MIN_VALUE;
                if (!want.contains(id) || byId.containsKey(id)) listBox.removeViewAt(i);
                else byId.put(id, c);
            }
            // 2. "Show earlier" at the top when older messages are hidden.
            int idx = 0;
            if (start > 0) {
                if (earlier == null) earlier = earlierChip();
                if (earlier.getParent() == null) listBox.addView(earlier, 0);
                idx = 1;
            } else if (earlier != null && earlier.getParent() != null) {
                listBox.removeView(earlier);
            }
            // 3. Bind / insert in order.
            for (int i = start; i < items.size(); i++) {
                Item it = items.get(i);
                View old = byId.get(it.id);
                View v = getView(i, old, listBox);
                if (listBox.getChildAt(idx) != v) {
                    if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    // Each row keeps its own side margins (no padding on the column).
                    lp.setMarginStart(Ui.dp(ChatActivity.this, 16));
                    lp.setMarginEnd(Ui.dp(ChatActivity.this, 16));
                    listBox.addView(v, idx, lp);
                }
                idx++;
            }
            while (listBox.getChildCount() > idx) listBox.removeViewAt(listBox.getChildCount() - 1);
        }

        /** Loads older messages above, keeping what you were reading in place. */
        private View earlierChip() {
            Palette pal = Palette.of(ChatActivity.this);
            TextView c = new TextView(ChatActivity.this, null, 0, R.style.YF_Body);
            c.setText(R.string.chat_show_earlier);
            c.setTextSize(14f);
            c.setTextColor(pal.primary);
            c.setGravity(Gravity.CENTER);
            c.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.12f), -1).pressable(pal.pressed()));
            int ph = Ui.dp(ChatActivity.this, 18), pv = Ui.dp(ChatActivity.this, 9);
            c.setPadding(ph, pv, ph, pv);
            c.setClickable(true);
            c.setOnClickListener(x -> {
                int before = listBox.getHeight(), y = list.getScrollY();
                shownLimit += 80;
                notifyDataSetChanged();
                listBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        listBox.removeOnLayoutChangeListener(this);
                        list.scrollTo(0, y + (listBox.getHeight() - before));
                    }
                });
            });
            LiquidPress.attach(c, LiquidPress.TEXT);
            LinearLayout wrap = new LinearLayout(ChatActivity.this);
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(ChatActivity.this, 8), 0, Ui.dp(ChatActivity.this, 8));
            wrap.addView(c, lp);
            wrap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrap;
        }

        View getView(int position, View convertView, ViewGroup parent) {
            Item it = items.get(position);
            if (convertView != null) {
                Object bound = convertView.getTag(R.id.chat_bound);
                if (bound instanceof long[] && ((long[]) bound)[0] == it.id && ((long[]) bound)[1] == it.signature()) {
                    // Same row, same content: leave it as it is (keeps a text selection and scroll).
                    return convertView;
                }
            }
            View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_message, parent, false);
            v.setTag(R.id.chat_bound, new long[]{it.id, it.signature()});
            Palette pal = Palette.of(ChatActivity.this);
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
            LinearLayout mdBox = v.findViewById(R.id.msg_md);
            mdBox.setVisibility(View.GONE);
            chevron.setVisibility(View.GONE);
            chevron.animate().cancel();
            toolsBox.setVisibility(View.GONE);
            toolsBox.removeAllViews();
            bubble.setOnClickListener(null);
            bubble.setOnLongClickListener(null);
            bubble.setClickable(false);
            bubble.setLongClickable(false);
            text.setVisibility(View.VISIBLE);
            text.setMaxLines(Integer.MAX_VALUE);
            title.setTextSize(13f);
            text.setTextColor(pal.onSurface);
            text.setTextSize(16f);
            text.setLineSpacing(Ui.dp(ChatActivity.this, 5), 1f);
            row.setGravity(Gravity.START);
            // Spacing: 24dp between turns (before your message), 12dp between blocks.
            row.setPadding(0, Ui.dp(ChatActivity.this, it.type == USER ? 18 : 6), 0, Ui.dp(ChatActivity.this, 6));
            int padH = Ui.dp(ChatActivity.this, 16), padV = Ui.dp(ChatActivity.this, 11);
            bubble.setPadding(padH, padV, padH, padV);
            bubble.setBackgroundTintList(null);
            switch (it.type) {
                case USER:
                    // Soft primary-tinted bubble on the right with a small tail at the bottom-end.
                    row.setGravity(Gravity.END);
                    bubble.setBackground(TonalDrawable.userBubble(ChatActivity.this));
                    bubble.setPadding(Ui.dp(ChatActivity.this, 18), Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 23), Ui.dp(ChatActivity.this, 12));
                    text.setTextSize(16f * Math.max(0.5f, textScale));
                    text.setLineSpacing(Ui.dp(ChatActivity.this, 5) * Math.max(0.5f, textScale), 1f);
                    break;
                case ASSISTANT:
                case STREAM:
                    // Answers are plain text on the page: full width, no bubble, 17sp / 26sp.
                    bubble.setBackground(null);
                    bubble.setPadding(Ui.dp(ChatActivity.this, 2), Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 2), Ui.dp(ChatActivity.this, 2));
                    text.setTextSize(17f);
                    text.setLineSpacing(Ui.dp(ChatActivity.this, 6.5f), 1f);
                    break;
                case TOOL:
                    bubble.setBackground(new TonalDrawable(pal.surfaceLow, Ui.dp(ChatActivity.this, 20)));
                    bubble.setPadding(Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 8), Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 8));
                    text.setTextSize(13.5f);
                    text.setTextColor(Palette.of(ChatActivity.this).onSurfaceVariant);
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
                    bubble.setBackground(new TonalDrawable(pal.surfaceLow, Ui.dp(ChatActivity.this, 20)));
                    title.setVisibility(View.VISIBLE);
                    title.setText(it.title);
                    title.setTextColor(Palette.of(ChatActivity.this).onSurfaceVariant);
                    if (it.expanded) {
                        detail.setVisibility(View.VISIBLE);
                        detail.setText(it.detail);
                    }
                    break;
                case ERROR:
                    bubble.setBackground(new TonalDrawable(Palette.alpha(Palette.of(ChatActivity.this).error, 0.12f), Ui.dp(ChatActivity.this, 20)));
                    title.setVisibility(View.VISIBLE);
                    title.setText(R.string.chat_error_tap_retry);
                    title.setTextColor(Palette.of(ChatActivity.this).error);
                    break;
                default: // NOTICE
                    row.setGravity(Gravity.CENTER_HORIZONTAL);
                    bubble.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.12f), -1));
                    text.setTextSize(13.5f);
                    text.setTextColor(pal.onSurfaceVariant);
                    break;
            }
            boolean prose = it.type == ASSISTANT || it.type == STREAM;
            if (prose) {
                if (mdStyle == null) mdStyle = Markdown.styleFor(ChatActivity.this);
                if (it.header) {
                    // The companion's name above the first answer of a turn, in the theme's primary.
                    title.setVisibility(View.VISIBLE);
                    title.setText("\u2726  " + companionName());
                    title.setTextColor(pal.primary);
                    title.setTextSize(13f);
                }
                // Thinking a proxy left inside the text (<thought>…</thought>) is not part of the answer.
                String[] parts = com.suvo.agent.core.llm.ThinkTags.split(it.text);
                if (it.type == STREAM && parts[1].isEmpty() && !parts[0].isEmpty()) {
                    text.setText(getString(R.string.status_thinking));
                    text.setTextColor(pal.onSurfaceVariant);
                } else {
                    // Text, code cards and tables as views; a still-streaming answer uses the same
                    // views (updated in place), so nothing jumps when it finishes.
                    List<Markdown.Block> blocks = it.type == STREAM ? streamBlocks(parts[1]) : blocksFor(parts[1]);
                    text.setText("");
                    text.setVisibility(View.GONE);
                    mdBox.setVisibility(View.VISIBLE);
                    float k = Math.max(0.5f, textScale);
                    MarkdownViews.fill(mdBox, blocks, mdStyle, 17f * k, Ui.dp(ChatActivity.this, 6.5f) * k, pal.onSurface,
                            it.type != STREAM, selectionHook);
                }
            } else if (it.type != TOOLS) {
                text.setText(it.text);
            }
            // Message text (answers and your own messages) can be selected and copied; links in
            // answers still open on a tap.
            boolean selectable = (it.type == USER || it.type == NOTICE) && text.getVisibility() == View.VISIBLE;
            text.setLinkTextColor(pal.primary);
            text.setHighlightColor(Palette.alpha(pal.primary, 0.28f));
            if (selectable) {
                MarkdownViews.makeSelectable(text, true, selectionHook);
            } else {
                text.setTextIsSelectable(false);
                text.setMovementMethod(null);
                text.setFocusable(false);
            }
            if (detail.getVisibility() == View.VISIBLE) {
                detail.setBackground(new TonalDrawable(Palette.alpha(pal.surfaceHighest, 0.7f), Ui.dp(ChatActivity.this, 16)));
                detail.setTextColor(pal.onSurface);
                MarkdownViews.makeSelectable(detail, true, selectionHook);
            } else {
                detail.setTextIsSelectable(false);
            }
            // Taps and long-presses on the bubble (outside its text, which selects): look the item up
            // by id, since a kept row outlives the Item objects of a rebuild.
            final long itemId = it.id;
            if (it.type == SUMMARY || it.type == ERROR) {
                bubble.setClickable(true);
                bubble.setOnClickListener(x -> {
                    Item cur = itemById(itemId);
                    if (cur == null) return;
                    if (cur.type == SUMMARY) {
                        cur.expanded = !cur.expanded;
                        adapter.notifyDataSetChanged();
                    } else {
                        retry();
                    }
                });
            }
            if (it.type != TOOLS) {
                bubble.setOnLongClickListener(x -> {
                    Item cur = itemById(itemId);
                    if (cur != null) itemMenu(cur);
                    return true;
                });
            }
            // An expanded tool detail would otherwise shrink to the title's width (one word per line).
            lp.width = detail.getVisibility() == View.VISIBLE || prose || it.type == TOOLS ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            bubble.setAlpha(it.dim ? 0.55f : 1f);
            bubble.setLayoutParams(lp);
            time.setText(it.time == 0 ? "" : DateFormat.getTimeFormat(ChatActivity.this).format(new Date(it.time))
                    + (it.dim ? " · " + getString(R.string.chat_summarized) : ""));
            time.setGravity(it.type == USER ? Gravity.END : Gravity.START);
            time.setTextColor(pal.tertiary);
            ImageView chev = v.findViewById(R.id.msg_chevron);
            chev.setImageTintList(android.content.res.ColorStateList.valueOf(pal.onSurfaceVariant));
            // Copy / more icons under your messages and finished answers (28dp, squish 0.88).
            LinearLayout acts = v.findViewById(R.id.msg_actions);
            boolean showActs = it.type == USER || it.type == ASSISTANT;
            acts.setVisibility(showActs ? View.VISIBLE : View.GONE);
            if (showActs) {
                ((LinearLayout.LayoutParams) acts.getLayoutParams()).gravity = it.type == USER ? Gravity.END : Gravity.START;
                View copy = v.findViewById(R.id.msg_copy), more = v.findViewById(R.id.msg_more);
                ((ImageView) copy).setImageTintList(android.content.res.ColorStateList.valueOf(pal.onSurfaceVariant));
                ((ImageView) more).setImageTintList(android.content.res.ColorStateList.valueOf(pal.onSurfaceVariant));
                copy.setOnClickListener(x -> {
                    Item cur = itemById(itemId);
                    Ui.copy(ChatActivity.this, (cur != null ? cur : it).text);
                });
                more.setOnClickListener(x -> {
                    Item cur = itemById(itemId);
                    itemMenu(cur != null ? cur : it);
                });
                LiquidPress.attach(copy, LiquidPress.ICON);
                LiquidPress.attach(more, LiquidPress.ICON);
            }
            return v;
        }

        /** "N tools" accordion: the header opens the list; each tool opens its own code box. */
        private void bindTools(Item it, LinearLayout bubble, TextView title, TextView text, ImageView chevron, LinearLayout box) {
            // Thinking / tool block: tonal container; "breathes" (16dp collapsed, 12dp open).
            bubble.setBackground(new TonalDrawable(Palette.of(ChatActivity.this).surfaceLow, Ui.dp(ChatActivity.this, 24)));
            int inset = Ui.dp(ChatActivity.this, it.expanded ? 12 : 16);
            bubble.setPadding(inset, Ui.dp(ChatActivity.this, 12), inset, Ui.dp(ChatActivity.this, 12));
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
            title.setTextColor(running > 0 ? Ui.accent(ChatActivity.this) : failed > 0 ? getColor(R.color.warning) : Palette.of(ChatActivity.this).onSurfaceVariant);
            title.setTextSize(14f);
            Item last = it.children.get(it.children.size() - 1);
            text.setText(last.title + " — " + last.text);
            text.setTextSize(13.5f);
            text.setTextColor(Palette.of(ChatActivity.this).onSurfaceVariant);
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
                arrow.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.of(ChatActivity.this).onSurfaceVariant));
                arrow.setRotation(c.expanded ? 90f : 0f);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, 16), Ui.dp(ChatActivity.this, 16));
                ap.setMarginStart(Ui.dp(ChatActivity.this, 6));
                line.addView(arrow, ap);
                row.addView(line);
                TextView code = new TextView(ChatActivity.this, null, 0, R.style.YF_Mono);
                code.setBackground(new TonalDrawable(Palette.alpha(Palette.of(ChatActivity.this).surfaceHighest, 0.7f), Ui.dp(ChatActivity.this, 14)));
                code.setTextColor(Palette.of(ChatActivity.this).onSurface);
                MarkdownViews.makeSelectable(code, true, selectionHook);
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
            if ("…".equals(s) || s.isEmpty()) return Palette.of(ChatActivity.this).onSurfaceVariant;
            if ("denied".equals(s) || "cancelled".equals(s)) return getColor(R.color.warning);
            return Palette.of(ChatActivity.this).error;
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
