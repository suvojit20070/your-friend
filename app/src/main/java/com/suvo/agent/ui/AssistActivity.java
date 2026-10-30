package com.suvo.agent.ui;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.voice.SpeechInput;

/**
 * Your Friend — Original project by Suvo.
 *
 * The phone's assistant: opens when Your Friend is the default digital assistant and you
 * long-press Home, swipe up from a bottom corner, hold the power button (where the phone uses it
 * for the assistant) or press a headset's voice button. A glass card floats over whatever is on
 * screen: it listens right away, sends what you said to the agent (in your current conversation,
 * with the agent's full tools) and shows the answer as it streams. Tap outside or Back to close;
 * the arrow opens the full chat.
 */
public final class AssistActivity extends BaseActivity {

    private static final int REQ_MIC = 71;
    /** Opened by the hands-free wake word (listen at once, a little chime-free). */
    public static final String EXTRA_FROM_WAKE = "from_wake";
    /** Brought back to show the answer of a task it stepped aside for. */
    public static final String EXTRA_SHOW_RESULT = "show_result";
    /**
     * The card stepped aside while the agent works: otherwise it would cover the app the agent
     * reads and taps (it showed up in the screen tree and in screenshots).
     */
    private boolean hiddenForTask;

    private LinearLayout card;
    private TextView answer;
    private TextView status;
    private EditText input;
    private ImageButton mic;
    private ScrollView answerScroll;
    private SpeechInput speech;
    private final StringBuilder stream = new StringBuilder();
    private Markdown.Style md;
    private long conversation;
    private boolean asked;

    // ---------------------------------------------------------------- default assistant helpers

    /** True when Your Friend is the phone's default digital assistant. */
    public static boolean isDefault(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = c.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT);
            }
            String a = Settings.Secure.getString(c.getContentResolver(), "assistant");
            return a != null && a.startsWith(c.getPackageName() + "/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Opens the system screen where the default assistant is chosen. Android does not let an app
     * take the assistant role itself; the user picks "Your Friend" there.
     */
    public static void openDefaultSettings(Context c) {
        String[] actions = {
                Settings.ACTION_VOICE_INPUT_SETTINGS,        // "Digital assistant app" on most phones
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
                Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                c.startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------- screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Float over the other app: transparent window, no theme background.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        Palette pal = Palette.of(this);
        md = Markdown.styleFor(this);
        speech = new SpeechInput(this);

        FrameLayout root = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(Palette.alpha(Color.BLACK, 0.32f));
        scrim.setOnClickListener(v -> finish());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setTag(R.id.fx_press, Boolean.TRUE);
        card.setBackground(new GlassDrawable(this, GlassDrawable.Preset.DIALOG, Ui.dp(this, 32)));
        GlassDrawable.shadow(card, GlassDrawable.Preset.DIALOG);

        // Header: companion name, "open chat", close.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_character);
        mark.setImageTintList(android.content.res.ColorStateList.valueOf(pal.primary));
        mark.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.14f), -1));
        int mp = Ui.dp(this, 8);
        mark.setPadding(mp, mp, mp, mp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        mlp.setMarginEnd(Ui.dp(this, 12));
        head.addView(mark, mlp);
        TextView name = new TextView(this, null, 0, R.style.YF_Title);
        name.setText(companionName());
        name.setTextSize(18f);
        name.setTextColor(pal.onSurface);
        name.setMaxLines(1);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(iconButton(R.drawable.ic_chat, R.string.assist_open_chat, v -> {
            startActivity(new Intent(this, ChatActivity.class).putExtra(ChatActivity.EXTRA_CONVERSATION, conversation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }));
        head.addView(iconButton(R.drawable.ic_close, R.string.close, v -> finish()));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 4));
        card.addView(head, hp);

        // The answer (streams in, Markdown, selectable).
        answer = new TextView(this, null, 0, R.style.YF_Body);
        answer.setTextSize(16.5f);
        answer.setTextColor(pal.onSurface);
        answer.setLinkTextColor(pal.primary);
        answer.setLineSpacing(Ui.dp(this, 4), 1f);
        answer.setVisibility(View.GONE);
        Ui.selectionColors(answer);
        answer.setTextIsSelectable(true);
        answer.setMovementMethod(SelectLinkMovement.get());
        answerScroll = new ScrollView(this);
        answerScroll.setVerticalScrollBarEnabled(false);
        answerScroll.addView(answer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.setMargins(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), 0);
        card.addView(new GlassDialog.MaxHeightFrame(this, maxH, answerScroll), ap);

        // Status: "Listening…", what you said, "Using a tool…".
        status = new TextView(this, null, 0, R.style.YF_Caption);
        status.setTextSize(14f);
        status.setTextColor(pal.onSurfaceVariant);
        status.setText(R.string.assist_hint);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        card.addView(status, sp);

        // Input row: text field, mic, send.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this, null, 0, R.style.YF_Input);
        input.setHint(R.string.chat_input_hint);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Ui.glassField(input, null);
        input.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                ask(input.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.setMargins(0, 0, Ui.dp(this, 8), 0);
        row.addView(input, ip);
        mic = iconButton(R.drawable.ic_mic, R.string.mic, v -> toggleMic());
        mic.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.14f), -1));
        mic.setImageTintList(android.content.res.ColorStateList.valueOf(pal.primary));
        row.addView(mic, sized(48, 6));
        ImageButton send = iconButton(R.drawable.ic_send, R.string.send, v -> {
            if (g.agent.isRunning()) g.agent.cancel();
            else ask(input.getText().toString());
        });
        send.setBackground(new TonalDrawable(pal.primary, -1));
        send.setImageTintList(android.content.res.ColorStateList.valueOf(pal.onPrimary));
        row.addView(send, sized(48, 0));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 16));
        card.addView(row, rp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(card, cp);
        // Insets (system bars, keyboard) go into the card's own margins.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = bars.left;
                r = bars.right;
                b = bars.bottom;
            } else {
                l = in.getSystemWindowInsetLeft();
                r = in.getSystemWindowInsetRight();
                b = in.getSystemWindowInsetBottom();
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            lp.setMargins(l + Ui.dp(this, 10), 0, r + Ui.dp(this, 10), b + Ui.dp(this, 10));
            card.setLayoutParams(lp);
            return in;
        });
        setContentView(root);
        Ui.tintAccent(this, root);

        conversation = g.agent.activeConversation();
        // Kept for the card's whole life (also while it steps aside for a task).
        g.agent.addListener(listener);
        card.setTranslationY(Ui.dp(this, 80));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        // Listen right away, like any assistant; without the microphone, type instead.
        root.post(this::startOrType);
    }

    private void startOrType() {
        boolean voice = g.settings.bool(com.suvo.agent.data.Settings.ALLOW_VOICE_INPUT, true) && SpeechInput.isAvailable(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (voice) {
            toggleMic();
        } else {
            input.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) input.postDelayed(() -> imm.showSoftInput(input, 0), 120);
        }
    }

    private String companionName() {
        try {
            com.suvo.agent.character.CharacterSpec c = g.characters.active();
            if (c != null && c.name != null && !c.name.trim().isEmpty()) return c.name.trim();
        } catch (RuntimeException ignored) {
        }
        return getString(R.string.app_name);
    }

    private ImageButton iconButton(int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.of(this).onSurfaceVariant));
        b.setBackground(new TonalDrawable(0, -1).pressable(Palette.of(this).pressed()));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 11);
        b.setPadding(p, p, p, p);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LiquidPress.attach(b, LiquidPress.CIRCLE);
        return b;
    }

    private LinearLayout.LayoutParams sized(int dp, int marginEndDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, dp), Ui.dp(this, dp));
        lp.setMarginEnd(Ui.dp(this, marginEndDp));
        return lp;
    }

    // ---------------------------------------------------------------------------------- voice

    private void toggleMic() {
        if (speech.isListening()) {
            speech.stop();
            return;
        }
        if (!g.settings.bool(com.suvo.agent.data.Settings.ALLOW_VOICE_INPUT, true)) {
            Ui.toast(this, R.string.voice_input_disabled);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        g.speech.stop();
        mic.setImageResource(R.drawable.ic_stop);
        status.setText(R.string.assist_listening);
        speech.start(g.settings.str(com.suvo.agent.data.Settings.STT_LANGUAGE, ""), new SpeechInput.Callback() {
            @Override
            public void onPartial(String text) {
                status.setText("“" + text + "”");
            }

            @Override
            public void onFinal(String text) {
                mic.setImageResource(R.drawable.ic_mic);
                if (text.trim().isEmpty()) {
                    status.setText(R.string.assist_hint);
                } else {
                    ask(text);
                }
            }

            @Override
            public void onError(int code, String message) {
                mic.setImageResource(R.drawable.ic_mic);
                status.setText(message);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleMic();
        else if (requestCode == REQ_MIC) startOrType();
    }

    // ---------------------------------------------------------------------------------- agent

    private void ask(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        if (speech.isListening()) speech.cancel();
        String err = g.agent.submit(conversation, text, null);
        if (err != null) {
            status.setText(err);
            return;
        }
        asked = true;
        // The card stays while the agent thinks and talks; it steps aside only right before a tool
        // reads or operates the screen (see ScreenGate), and comes back with the answer.
        input.setText("");
        stream.setLength(0);
        status.setText("“" + text + "”  ·  " + getString(R.string.status_thinking));
        answer.setVisibility(View.VISIBLE);
        answer.setText("");
    }

    private final AgentListener listener = new AgentListener() {
        @Override
        public void onTextDelta(TaskState task, String delta) {
            if (!asked || task.conversationId != conversation) return;
            stream.append(delta);
            String[] parts = com.suvo.agent.core.llm.ThinkTags.split(stream.toString());
            if (!parts[1].isEmpty()) {
                answer.setText(Markdown.render(parts[1], md));
                answerScroll.post(() -> answerScroll.scrollTo(0, answer.getBottom()));
            }
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            if (asked && task.conversationId == conversation) status.setText(getString(R.string.status_using_tool, toolName));
        }

        @Override
        public void onAssistantMessage(TaskState task, ChatMessage message) {
            if (!asked || task.conversationId != conversation) return;
            String[] parts = com.suvo.agent.core.llm.ThinkTags.split(message.contentOrEmpty());
            if (!parts[1].trim().isEmpty()) answer.setText(Markdown.render(parts[1].trim(), md));
            stream.setLength(0); // the next step streams fresh text
        }

        @Override
        public void onFinished(TaskState task) {
            if (!asked || task.conversationId != conversation) return;
            if (task.status != TaskState.Status.COMPLETED && !task.error.isEmpty()) status.setText(task.error);
            else status.setText(R.string.assist_done);
            if (hiddenForTask) showAgain();
        }
    };

    /** While the card is showing it hides itself right before a screen tool runs. */
    private final com.suvo.agent.agent.ScreenGate.Hider screenHider = done -> {
        if (!isFinishing() && !hiddenForTask && asked) hideForTask();
        done.run();
    };

    @Override
    protected void onStart() {
        super.onStart();
        com.suvo.agent.agent.ScreenGate.set(screenHider);
    }

    /** Moves the card's own task behind the other app while the agent works. */
    private void hideForTask() {
        if (isFinishing() || !g.agent.isRunning()) return;
        hiddenForTask = true;
        if (!moveTaskToBack(true)) hiddenForTask = false;
    }

    /** The answer is ready: bring the card back (or, where Android blocks that, a notification). */
    private void showAgain() {
        Intent i = new Intent(this, AssistActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SHOW_RESULT, true);
        boolean canOpen = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this);
        if (canOpen) {
            try {
                startActivity(i);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 33, i,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        CharSequence preview = answer.getText();
        android.app.Notification n = Notifications.builder(this, Notifications.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(companionName())
                .setContentText(preview.length() == 0 ? getString(R.string.assist_done) : preview)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(preview))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(Notifications.ID_AGENT + 9, n);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hiddenForTask) {
            hiddenForTask = false;
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.cancel(Notifications.ID_AGENT + 9);
        }
    }

    @Override
    protected void onStop() {
        com.suvo.agent.agent.ScreenGate.clear(screenHider);
        if (speech.isListening()) speech.cancel();
        super.onStop();
        // An assistant card does not stay behind other apps — unless it only stepped aside for a task.
        if (!isChangingConfigurations() && !hiddenForTask) finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_RESULT, false)) return; // just show the answer
        // Assistant gesture again while open: listen again.
        if (!speech.isListening()) toggleMic();
    }

    @Override
    protected void onDestroy() {
        g.agent.removeListener(listener);
        // Hands-free waits while the card is open; it goes back to listening for the wake word now.
        com.suvo.agent.voice.HandsFreeService.assistClosed();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }
}
