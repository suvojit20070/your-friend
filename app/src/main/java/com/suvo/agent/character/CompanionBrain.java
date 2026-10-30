package com.suvo.agent.character;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.suvo.agent.AppGraph;
import com.suvo.agent.agent.TrackedLlm;
import com.suvo.agent.core.character.CompanionPlan;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.device.ImageUtil;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Your Friend — Original project by Suvo.
 *
 * Makes the floating character behave on its own. Two loops on a background thread:
 * <ul>
 * <li>Every 2 s it reads the screen. When the element the character stands or sits on is gone
 * (another screen, scrolled away), it lets the character fall to whatever is below — so it never
 * sits on something that no longer exists. A changed screen also asks for a new decision soon.</li>
 * <li>Every now and then (the AI says when, 30–240 s) it sends a separate, small request to the
 * AI — not part of any chat — with the screen's elements, the character's size and position,
 * its pose, mood and recent history. The answer (mood, sit/stand/walk/sleep, where to, what to
 * look at, a tiny thought bubble) is applied by {@link Body}.</li>
 * </ul>
 * The AI also gets two tools: it can ask for a screenshot first (vision models, Android 11+) when
 * the element list is not enough, and it can ask the phone agent to do one small harmless thing
 * (e.g. scroll to the next reel) — at most every few minutes, in its own "Companion" chat.
 * Switched off in Settings, or when no AI provider is set up, only the first loop runs.
 */
final class CompanionBrain {

    /** The floating character, as the brain sees it. Methods marked "main" run on the main thread. */
    interface Body {
        /** Main: the current state, or null when it should not be disturbed now (hidden, moving, held). */
        CompanionPlan.State state();

        /** Main: carries out a plan. {@code target}/{@code look} are null when not given or gone. */
        void apply(CompanionPlan plan, Rect target, boolean targetIsBottom, Rect look);

        /** Main: the brain just asked the agent to do {@code text}. */
        void saidToAgent(String text);

        /** Main: what the character rested on is gone. */
        void supportLost();

        /** Any thread: {left, top, right, isElement(0/1), resting(0/1), bodyW, centreX} of where it rests. */
        int[] rest();

        /** Any thread: the overlay is showing. */
        boolean visible();
    }

    static final String SETTING = Settings.COMPANION_BRAIN;
    static final String AGENT_SETTING = Settings.COMPANION_ASKS_AGENT;
    private static final long AGENT_GAP_MS = 3 * 60_000;
    private long lastAgentAsk;
    private static final long WATCH_MS = 2000;

    private final Context ctx;
    private final Body body;
    private final float density;
    private final HandlerThread thread = new HandlerThread("yf-companion");
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile CancellationToken inFlight;
    private int lastSignature;
    private long lastScreenChange = System.currentTimeMillis();
    private long lastThink;
    private boolean changedSinceThink;
    private final ArrayDeque<JSONObject> history = new ArrayDeque<>();
    private final ArrayDeque<Long> historyAt = new ArrayDeque<>();

    CompanionBrain(Context ctx, Body body) {
        this.ctx = ctx.getApplicationContext();
        this.body = body;
        this.density = ctx.getResources().getDisplayMetrics().density;
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    void start() {
        bg.postDelayed(watch, WATCH_MS);
        bg.postDelayed(think, 20_000);
    }

    void stop() {
        stopped = true;
        CancellationToken t = inFlight;
        if (t != null) t.cancel("stopped");
        bg.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    /** The screen changed (another app or window): look again soon. Any thread. */
    void poke() {
        if (stopped) return;
        bg.post(() -> {
            changedSinceThink = true;
            lastScreenChange = System.currentTimeMillis();
            bg.removeCallbacks(watch);
            bg.postDelayed(watch, 600);
            scheduleThinkSoon();
        });
    }

    /** The user moved or touched the character: give them a moment before deciding anything. */
    void userInteracted() {
        if (stopped) return;
        bg.post(() -> {
            record("moved by the user", null);
            bg.removeCallbacks(think);
            bg.postDelayed(think, 25_000);
        });
    }

    static boolean enabled() {
        try {
            AppGraph g = AppGraph.get();
            if (!g.settings.bool(SETTING, true)) return false;
            ProviderConfig p = g.providers.active();
            return p != null && p.validate() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void scheduleThinkSoon() {
        long since = System.currentTimeMillis() - lastThink;
        long delay = Math.max(4000, 20_000 - since);
        bg.removeCallbacks(think);
        bg.postDelayed(think, delay);
    }

    private int minSize() {
        return Math.round(18 * density);
    }

    // ------------------------------------------------------------------ watching the screen

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            try {
                if (body.visible()) check();
            } catch (RuntimeException ignored) {
            }
            if (!stopped) bg.postDelayed(this, WATCH_MS);
        }
    };

    private void check() {
        int[] rest = body.rest();
        boolean onElement = rest != null && rest[3] == 1 && rest[4] == 1;
        if (!onElement && !enabled()) return; // nothing to keep track of: save the battery
        ScreenSurfaces.Snapshot snap = ScreenSurfaces.scan(ctx.getPackageName(), minSize(), 450);
        if (snap.signature != lastSignature) {
            boolean first = lastSignature == 0;
            lastSignature = snap.signature;
            lastScreenChange = System.currentTimeMillis();
            if (!first) {
                changedSinceThink = true;
                scheduleThinkSoon();
            }
        }
        int[] r = body.rest();
        if (r == null || r[3] == 0 || r[4] == 0) return; // on the screen bottom, or moving
        if (!supported(snap, r)) {
            record("fell: what it was on is gone", null);
            main.post(body::supportLost);
        }
    }

    /** Something is still there under the character (same top edge, under its middle). */
    private boolean supported(ScreenSurfaces.Snapshot snap, int[] r) {
        int tol = Math.round(10 * density);
        int cx = r[6];
        for (ScreenSurfaces.Item it : snap.items) {
            if (Math.abs(it.r.top - r[1]) > tol) continue;
            if (it.r.left > cx || it.r.right < cx) continue;
            if (it.r.width() < r[5] * 0.5f) continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ asking the AI

    private final Runnable think = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            long next = 20_000;
            try {
                next = thinkOnce();
            } catch (Exception ignored) {
                next = 60_000;
            }
            if (!stopped) {
                bg.removeCallbacks(this);
                bg.postDelayed(this, next);
            }
        }
    };

    /** One decision; returns the delay until the next one (ms). */
    private long thinkOnce() throws Exception {
        if (!enabled()) return 60_000;
        if (!body.visible()) return 15_000;
        AppGraph g = AppGraph.get();
        if (g.agent.isRunning()) return 20_000;
        CompanionPlan.State st = onMain(body::state);
        if (st == null) return 12_000;

        ScreenSurfaces.Snapshot snap = ScreenSurfaces.scan(ctx.getPackageName(), minSize(), 450);
        Map<String, Rect> ids = new HashMap<>();
        List<CompanionPlan.Surface> list = describe(snap, st, ids);
        st.app = snap.app;
        st.screenChanged = changedSinceThink;
        st.screenIdle = (int) ((System.currentTimeMillis() - lastScreenChange) / 1000);
        Calendar c = Calendar.getInstance();
        st.clock = String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        st.agentBusy = g.agent.isRunning();
        CharacterSpec spec = g.characters.active();
        if (spec != null) {
            st.name = spec.name;
            st.personality = spec.personality;
        }

        ProviderConfig p = g.providers.active();
        if (p == null) return 60_000;
        boolean canShot = p.supportsVision && AgentAccessibilityService.canCaptureScreen() && AgentAccessibilityService.get() != null;
        boolean canAsk = g.settings.bool(AGENT_SETTING, true) && System.currentTimeMillis() - lastAgentAsk > AGENT_GAP_MS;
        ChatRequest req = new ChatRequest();
        req.forceNoStream = true;
        req.tools = new JSONArray();
        req.toolChoice = null;
        req.temperatureOverride = 0.9;
        req.maxTokensOverride = 700;
        req.messages.add(ChatMessage.system(CompanionPlan.systemPrompt(st.name, st.personality, canShot, canAsk)));
        req.messages.add(ChatMessage.user(CompanionPlan.userMessage(st, list, historyJson())));
        LlmClient llm = TrackedLlm.wrap(ctx, new OpenAiCompatClient(p));
        ChatResponse resp = ask(llm, req);
        lastThink = System.currentTimeMillis();
        changedSinceThink = false;
        if (stopped || resp == null) return 60_000;
        CompanionPlan plan = CompanionPlan.parse(resp.content);
        if (plan.wantsScreenshot) {
            // The screenshot tool: capture, then ask again with the picture (once per decision).
            ChatMessage.Image shot = canShot ? screenshot() : null;
            req.messages.add(ChatMessage.assistant(resp.content == null ? "{\"tool\":\"screenshot\"}" : resp.content));
            if (shot != null) {
                req.messages.add(ChatMessage.user("Here is the screenshot. Now answer with the plan JSON (no more tools).",
                        Collections.singletonList(shot)));
            } else {
                req.messages.add(ChatMessage.user("The screenshot is not available. Answer with the plan JSON from the elements."));
            }
            resp = ask(llm, req);
            if (stopped || resp == null) return 60_000;
            plan = CompanionPlan.parse(resp.content);
            if (plan.wantsScreenshot) plan = new CompanionPlan();
            record("looked at a screenshot", null);
        }
        if (!plan.agent.isEmpty()) {
            if (canAsk && !g.agent.isRunning()) {
                lastAgentAsk = System.currentTimeMillis();
                String ask = plan.agent;
                main.post(() -> askAgent(ask));
            } else {
                plan.agent = "";
            }
        }

        // The screen may have changed while the AI was thinking: only use elements still there.
        ScreenSurfaces.Snapshot now = ScreenSurfaces.scan(ctx.getPackageName(), minSize(), 450);
        boolean bottom = "bottom".equalsIgnoreCase(plan.target);
        Rect target = bottom ? null : stillThere(ids.get(plan.target), now);
        Rect look = stillThere(ids.get(plan.look), now);
        if (!bottom && !plan.target.isEmpty() && target == null && ("walk".equals(plan.action) || "sit".equals(plan.action))) {
            plan.action = "stay";
        }
        final Rect fTarget = target, fLook = look;
        final CompanionPlan fPlan = plan;
        main.post(() -> {
            if (!stopped) body.apply(fPlan, fTarget, bottom, fLook);
        });
        record(plan.action, plan);
        return plan.nextCheck * 1000L;
    }

    private ChatResponse ask(LlmClient llm, ChatRequest req) throws Exception {
        CancellationToken tk = new CancellationToken();
        inFlight = tk;
        try {
            return llm.chat(req, StreamListener.NONE, tk);
        } finally {
            inFlight = null;
        }
    }

    /** The screen as a small JPEG for the AI, or null. Runs on the brain's thread. */
    private ChatMessage.Image screenshot() {
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        if (svc == null) return null;
        try {
            android.graphics.Bitmap full = svc.captureScreen(3000);
            android.graphics.Bitmap small = ImageUtil.scaleToMax(full, 768);
            ChatMessage.Image img = ImageUtil.toImage(ImageUtil.jpeg(small, 70), "low");
            if (small != full) small.recycle();
            full.recycle();
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Main thread: hands one small request to the phone agent, in its own "Companion" chat so the
     * user's conversation stays as it was.
     */
    private void askAgent(String text) {
        try {
            AppGraph g = AppGraph.get();
            if (g.agent.isRunning()) return;
            long id = g.settings.raw().getLong("companion_conversation", -1);
            if (id < 0 || g.conversations.get(id) == null) {
                id = g.conversations.create(ctx.getString(com.suvo.agent.R.string.companion_conversation), g.characters.active().id);
                g.settings.raw().edit().putLong("companion_conversation", id).apply();
            }
            long before = g.agent.activeConversation();
            String why = g.agent.submit(id, "[From your floating companion, not typed by the user] " + text, null);
            g.agent.setActiveConversation(before);
            if (why == null) body.saidToAgent(text);
        } catch (RuntimeException ignored) {
        }
    }

    /** Elements for the AI: places to stand first, then labelled things for context. Fills {@code ids}. */
    private List<CompanionPlan.Surface> describe(ScreenSurfaces.Snapshot snap, CompanionPlan.State st, Map<String, Rect> ids) {
        List<ScreenSurfaces.Item> items = new ArrayList<>(snap.items);
        // Smallest first, so of nested wrappers with the same bounds the innermost (labelled) one stays.
        Collections.sort(items, (a, b) -> Integer.compare(a.r.width() * a.r.height(), b.r.width() * b.r.height()));
        List<ScreenSurfaces.Item> kept = new ArrayList<>();
        int same = Math.round(6 * density);
        for (ScreenSurfaces.Item it : items) {
            boolean dup = false;
            for (ScreenSurfaces.Item k : kept) {
                if (Math.abs(k.r.left - it.r.left) <= same && Math.abs(k.r.top - it.r.top) <= same
                        && Math.abs(k.r.right - it.r.right) <= same && Math.abs(k.r.bottom - it.r.bottom) <= same) {
                    dup = true;
                    break;
                }
            }
            if (!dup) kept.add(it);
        }
        int top = Math.round(40 * density);
        List<ScreenSurfaces.Item> stand = new ArrayList<>(), other = new ArrayList<>();
        for (ScreenSurfaces.Item it : kept) {
            boolean holds = it.keyboard || (it.canHold(st.bodyW, minSize()) && it.r.top > top && it.r.top > st.bodyH * 0.45f);
            if (holds) stand.add(it);
            else if (!it.label.isEmpty()) other.add(it);
        }
        java.util.Comparator<ScreenSurfaces.Item> byPos = (a, b) -> a.r.top != b.r.top ? Integer.compare(a.r.top, b.r.top) : Integer.compare(a.r.left, b.r.left);
        Collections.sort(stand, byPos);
        Collections.sort(other, byPos);
        List<CompanionPlan.Surface> out = new ArrayList<>();
        int n = 0;
        for (ScreenSurfaces.Item it : stand) {
            if (n >= 28) break;
            String id = "e" + (++n);
            ids.put(id, it.r);
            out.add(new CompanionPlan.Surface(id, it.label, it.type, it.r.left, it.r.top, it.r.width(), it.r.height(),
                    true, it.clickable, it.editable, it.keyboard));
        }
        int extra = 0;
        for (ScreenSurfaces.Item it : other) {
            if (extra++ >= 22) break;
            String id = "e" + (++n);
            ids.put(id, it.r);
            out.add(new CompanionPlan.Surface(id, it.label, it.type, it.r.left, it.r.top, it.r.width(), it.r.height(),
                    false, it.clickable, it.editable, false));
        }
        return out;
    }

    private Rect stillThere(Rect r, ScreenSurfaces.Snapshot now) {
        if (r == null) return null;
        int tol = Math.round(6 * density);
        for (ScreenSurfaces.Item it : now.items) {
            if (Math.abs(it.r.left - r.left) <= tol && Math.abs(it.r.top - r.top) <= tol
                    && Math.abs(it.r.right - r.right) <= tol && Math.abs(it.r.bottom - r.bottom) <= tol) {
                return new Rect(it.r);
            }
        }
        return null;
    }

    private void record(String what, CompanionPlan plan) {
        try {
            JSONObject h = new JSONObject();
            h.put("did", what);
            if (plan != null) {
                if (!plan.mood.isEmpty()) h.put("mood", plan.mood);
                if (!plan.target.isEmpty()) h.put("target", plan.target);
                if (!"auto".equals(plan.sitStyle)) h.put("sit_style", plan.sitStyle);
                if (!plan.say.isEmpty()) h.put("said", plan.say);
                if (!plan.face.isEmpty()) h.put("face", plan.face);
                if (!plan.gesture.isEmpty()) h.put("gesture", plan.gesture);
                if (!plan.agent.isEmpty()) h.put("asked_agent", plan.agent);
            }
            int[] r = body.rest();
            if (r != null) h.put("x", r[6]).put("rest_y", r[1]);
            history.addLast(h);
            historyAt.addLast(System.currentTimeMillis());
            while (history.size() > 8) {
                history.removeFirst();
                historyAt.removeFirst();
            }
        } catch (Exception ignored) {
        }
    }

    private JSONArray historyJson() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        java.util.Iterator<Long> at = historyAt.iterator();
        for (JSONObject h : history) {
            long t = at.hasNext() ? at.next() : now;
            try {
                a.put(new JSONObject(h.toString()).put("ago_s", (now - t) / 1000));
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    private <T> T onMain(java.util.concurrent.Callable<T> c) throws Exception {
        FutureTask<T> f = new FutureTask<>(c);
        main.post(f);
        return f.get(2, TimeUnit.SECONDS);
    }
}
