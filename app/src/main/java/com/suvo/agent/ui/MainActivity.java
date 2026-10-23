package com.suvo.agent.ui;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    }
}
