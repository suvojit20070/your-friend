package com.suvo.agent.ui;

<<<<<<< HEAD
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.character.CharacterView;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.data.Settings;
import com.suvo.agent.voice.SpeechController;
import com.suvo.agent.voice.SpeechInput;

import java.util.Random;

/** Home / Companion: the animated character, quick voice/text input and live status. */
public final class MainActivity extends BaseActivity {

    public static final String EXTRA_LISTEN = "listen";
    private static final int REQ_MIC = 11;

    private CharacterView character;
    private CompanionBinder binder;
    private TextView bubble;
    private EditText input;
    private ImageButton mic;
    private SpeechInput speechInput;
    private final StringBuilder streaming = new StringBuilder();
    private final Random rnd = new Random();

    private final AgentListener agentListener = new AgentListener() {
        @Override
        public void onStatusChanged(TaskState task) {
            updateStatus();
        }

        @Override
        public void onTextDelta(TaskState task, String delta) {
            streaming.append(delta);
            showBubble(streaming.toString());
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            showBubble(getString(R.string.status_using_tool, toolName));
        }

        @Override
        public void onToolExecuting(TaskState task, com.suvo.agent.core.llm.ToolCall call) {
            streaming.setLength(0);
            showBubble(getString(R.string.status_using_tool, call.name));
        }

        @Override
        public void onFinished(TaskState task) {
            streaming.setLength(0);
            if (task.status == TaskState.Status.COMPLETED) showBubble(task.finalText);
            else if (!task.error.isEmpty()) showBubble(task.error);
            updateStatus();
        }
    };

    private final SpeechController.Listener speechListener = new SpeechController.Listener() {
        @Override
        public void onSpeechState(SpeechController.State state, String detail) {
            if (state == SpeechController.State.ERROR && detail != null && !detail.isEmpty()) {
                Ui.toast(MainActivity.this, getString(R.string.voice_error, detail));
            }
        }
    };
=======
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.suvo.agent.AgentApp;
import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.service.AgentService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 1 UI: a working text chat with the agent (streaming + tools).
 * Glass theme applied via the layout. Character overlay, voice and the other
 * screens (Characters/Skills/Settings) attach in later phases.
 */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private CharacterView character;

    @Override
    protected void onResume() {
        super.onResume();
        if (character != null) {
            character.setStyle("boy".equals(AgentApp.get().settings.character())
                    ? CharacterView.Style.BOY : CharacterView.Style.GIRL);
        }
    }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
<<<<<<< HEAD
        if (!g.settings.bool(Settings.ONBOARDED, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        character = findViewById(R.id.character);
        bubble = findViewById(R.id.speech_bubble);
        input = findViewById(R.id.home_input);
        mic = findViewById(R.id.home_mic);
        binder = new CompanionBinder(g, character);
        speechInput = new SpeechInput(this);
        Nav.build(this, findViewById(R.id.nav), 0);

        findViewById(R.id.home_settings).setOnClickListener(v -> open(SettingsActivity.class));
        findViewById(R.id.setup_banner_button).setOnClickListener(v -> open(ProvidersActivity.class));
        findViewById(R.id.home_send).setOnClickListener(v -> send());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                send();
                return true;
            }
            return false;
        });
        mic.setOnClickListener(v -> toggleListening());
        bubble.setOnClickListener(v -> open(ChatActivity.class));

        int size = g.settings.integer(Settings.CHARACTER_SIZE, 100);
        character.getLayoutParams().width = Ui.dp(this, 300 * size / 100f);
        character.getLayoutParams().height = Ui.dp(this, 360 * size / 100f);
        character.setListener(new CharacterView.Listener() {
            @Override
            public void onCharacterTapped(CharacterAnimator.TouchRegion region) {
                CharacterSpec spec = character.spec();
                String line = spec != null && !spec.touchReactions.isEmpty()
                        ? spec.touchReactions.get(rnd.nextInt(spec.touchReactions.size()))
                        : getString(R.string.touch_default);
                showBubble(line);
                if (g.settings.bool(Settings.VOICE_OUTPUT, false) && !g.agent.isRunning()) g.speech.speak(line);
            }

            @Override
            public void onCharacterMoved(float tx, float ty) {
                View area = findViewById(R.id.companion_area);
                g.settings.put(Settings.CHARACTER_POS_X, tx / Math.max(1f, area.getWidth()));
                g.settings.put(Settings.CHARACTER_POS_Y, ty / Math.max(1f, area.getHeight()));
            }
        });
        View area = findViewById(R.id.companion_area);
        area.post(() -> {
            character.setTranslationX(g.settings.flt(Settings.CHARACTER_POS_X, 0f) * area.getWidth());
            character.setTranslationY(g.settings.flt(Settings.CHARACTER_POS_Y, 0f) * area.getHeight());
        });
        if (getIntent().getBooleanExtra(EXTRA_LISTEN, false)) area.post(this::toggleListening);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binder == null) return;
        binder.attach();
        g.agent.addListener(agentListener);
        g.speech.addListener(speechListener);
        CharacterSpec spec = g.characters.active();
        ((TextView) findViewById(R.id.home_name)).setText(spec.name);
        if (bubble.getVisibility() != View.VISIBLE && !spec.greeting.isEmpty()) showBubble(spec.greeting);
        updateStatus();
    }

    @Override
    protected void onStop() {
        if (binder != null) {
            binder.detach();
            g.agent.removeListener(agentListener);
            g.speech.removeListener(speechListener);
            if (speechInput.isListening()) speechInput.cancel();
            binder.setListening(false);
        }
        super.onStop();
    }

    private void showBubble(String text) {
        if (text == null || text.trim().isEmpty()) {
            bubble.setVisibility(View.GONE);
            return;
        }
        bubble.setText(SpeechController.forSpeech(text));
        bubble.setVisibility(View.VISIBLE);
    }

    private void updateStatus() {
        ProviderConfig p = g.providers.active();
        findViewById(R.id.setup_banner).setVisibility(p == null ? View.VISIBLE : View.GONE);
        TextView status = findViewById(R.id.home_status);
        TaskState t = g.agent.current();
        if (g.agent.isRunning() && t != null) {
            status.setText(t.status == TaskState.Status.WAITING_APPROVAL ? getString(R.string.status_waiting_approval)
                    : getString(R.string.status_working_steps, t.toolCalls));
        } else {
            status.setText(p == null ? getString(R.string.status_no_provider) : getString(R.string.status_ready_with, p.name, p.model));
        }
        LinearLayout chips = findViewById(R.id.home_chips);
        chips.removeAllViews();
        chips.addView(Nav.chip(this, getString(R.string.chip_autonomy, autonomyLabel()), 0, v -> open(PermissionsActivity.class)));
        if (g.capabilities.state(Capability.ACCESSIBILITY) != CapabilityState.AVAILABLE) {
            chips.addView(Nav.chip(this, getString(R.string.chip_screen_control_off), getColor(R.color.warning), v -> open(ScreenControlActivity.class)));
        }
        chips.addView(Nav.chip(this, g.settings.bool(Settings.VOICE_OUTPUT, false) ? getString(R.string.chip_voice_on) : getString(R.string.chip_voice_off),
                0, v -> open(VoiceActivity.class)));
        if (g.agent.isRunning()) {
            chips.addView(Nav.chip(this, getString(R.string.action_stop), getColor(R.color.danger), v -> g.agent.cancel()));
        }
        if (!g.approvals.pending().isEmpty()) {
            chips.addView(Nav.chip(this, getString(R.string.chip_approval_needed), getColor(R.color.danger), v -> open(LiveAgentActivity.class)));
        }
    }

    private String autonomyLabel() {
        switch (g.policy.mode) {
            case ASSIST:
                return getString(R.string.autonomy_assist);
            case AUTONOMOUS:
                return getString(R.string.autonomy_autonomous);
            default:
                return getString(R.string.autonomy_confirm);
        }
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        String err = g.agent.submit(g.agent.activeConversation(), text, (java.util.List<ChatMessage.Image>) null);
        if (err != null) {
            showBubble(err);
            return;
        }
        input.setText("");
        streaming.setLength(0);
        showBubble(getString(R.string.status_thinking));
        updateStatus();
    }

    private void toggleListening() {
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
        if (!SpeechInput.isAvailable(this)) {
            Ui.toast(this, R.string.no_speech_recognizer);
            return;
        }
        g.speech.stop();
        binder.setListening(true);
        mic.setImageResource(R.drawable.ic_stop);
        showBubble(getString(R.string.status_listening));
        speechInput.start(g.settings.str(Settings.STT_LANGUAGE, ""), new SpeechInput.Callback() {
            @Override
            public void onLevel(float rmsDb) {
                binder.setMicLevel(rmsDb);
            }

            @Override
            public void onPartial(String text) {
                input.setText(text);
                input.setSelection(text.length());
            }

            @Override
            public void onFinal(String text) {
                stopListeningUi();
                input.setText(text);
                if (!text.trim().isEmpty()) send();
            }

            @Override
            public void onError(int code, String message) {
                stopListeningUi();
                showBubble(message);
            }
        });
    }

    private void stopListeningUi() {
        binder.setListening(false);
        mic.setImageResource(R.drawable.ic_mic);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleListening();
        else if (requestCode == REQ_MIC) Ui.toast(this, R.string.mic_permission_denied);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) toggleListening();
    }

    @SuppressWarnings("unused")
    private static boolean atLeast(int api) {
        return Build.VERSION.SDK_INT >= api;
=======
        setContentView(R.layout.activity_main);

        // keep agent alive
        AgentService.start(this);

        list = findViewById(R.id.chatList);
        input = findViewById(R.id.input);
        character = findViewById(R.id.character);
        ImageButton send = findViewById(R.id.sendBtn);

        character.setStyle("boy".equals(AgentApp.get().settings.character())
                ? CharacterView.Style.BOY : CharacterView.Style.GIRL);

        adapter = new ChatAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.settingsBtn).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        send.setOnClickListener(v -> onSend());
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); return true; }
            return false;
        });

        if (!AgentApp.get().settings.llm().isValid()) {
            adapter.add(new ChatMsg(ChatMsg.TOOL,
                    "Set your LLM provider (base URL, key, model) in Settings to start."));
        }
    }

    private void onSend() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        input.setText("");

        adapter.add(new ChatMsg(ChatMsg.USER, text));
        adapter.add(new ChatMsg(ChatMsg.AURA, "…"));
        scroll();

        final StringBuilder streamed = new StringBuilder();

        exec.execute(() -> AgentApp.get().agent.send(text, new AgentListener() {
            @Override public void onReplyDelta(String delta) {
                streamed.append(delta);
                ui.post(() -> { adapter.updateLastAura(streamed.toString()); scroll(); });
            }
            @Override public void onSentence(String sentence) {
                // animate the character "talking" (Phase 3 drives this from real voice amplitude)
                ui.post(() -> character.pulseTalk());
                // Phase 3: hand `sentence` to the voice layer (OmniVoice) to speak.
            }
            @Override public void onReplyDone(String fullText) {
                ui.post(() -> { adapter.updateLastAura(fullText.isEmpty() ? "(…)" : fullText); scroll(); });
            }
            @Override public void onToolStart(String toolName) {
                ui.post(() -> { adapter.add(new ChatMsg(ChatMsg.TOOL, "⚙ " + toolName + "…")); scroll(); });
            }
            @Override public void onToolResult(String toolName, String result) {
                ui.post(() -> { adapter.add(new ChatMsg(ChatMsg.TOOL, "✓ " + toolName)); scroll(); });
                // start a fresh bubble for the model's continued reply
                ui.post(() -> { adapter.add(new ChatMsg(ChatMsg.AURA, "…")); streamed.setLength(0); scroll(); });
            }
            @Override public boolean confirmDangerous(String toolName, String argsPreview) {
                final AtomicBoolean ok = new AtomicBoolean(false);
                final CountDownLatch latch = new CountDownLatch(1);
                ui.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Allow " + toolName + "?")
                        .setMessage(argsPreview)
                        .setPositiveButton("Allow", (d, w) -> { ok.set(true); latch.countDown(); })
                        .setNegativeButton("Deny", (d, w) -> { ok.set(false); latch.countDown(); })
                        .setOnCancelListener(d -> latch.countDown())
                        .show());
                try { latch.await(); } catch (InterruptedException ignore) {}
                return ok.get();
            }
            @Override public void onError(String message) {
                ui.post(() -> { adapter.updateLastAura("⚠ " + message); scroll(); });
            }
        }));
    }

    private void scroll() {
        if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1);
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
    }
}
