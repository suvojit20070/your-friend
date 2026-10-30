package com.suvo.agent.core.character;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * The floating character's "brain" step: what the AI is told (the screen's elements, the
 * character's size, where it is, how it felt, what it did lately) and the small plan it answers
 * with (mood, pose, where to go, what to look at, an optional thought bubble). Pure Java so it can
 * be unit-tested; the Android side scans the screen and applies the plan.
 *
 * The request is separate from the chat: no history, no tools, nothing is saved.
 */
public final class CompanionPlan {

    public static final Set<String> MOODS = new HashSet<>(Arrays.asList(
            "happy", "calm", "curious", "excited", "sad", "sleepy", "shy", "bored", "thoughtful"));
    public static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "stay", "sit", "stand", "walk", "sleep", "wake"));
    public static final Set<String> FACES = new HashSet<>(Arrays.asList(
            "neutral", "happy", "sad", "surprised", "angry", "shy", "sleepy"));
    public static final Set<String> GESTURES = new HashSet<>(Arrays.asList(
            "none", "wave", "nod", "shake_head", "cheer", "think", "look_around", "touch_hair", "hands_together",
            "stretch", "sway", "glance"));
    public static final Set<String> SIT_STYLES = new HashSet<>(Arrays.asList(
            "auto", "kick", "crossed", "lean_back", "hug_knees", "chin"));
    /** Seconds until the next decision. */
    public static final int MIN_NEXT = 30, MAX_NEXT = 240, DEFAULT_NEXT = 75;

    public String mood = "";
    /** Facial expression ("" = from the mood). */
    public String face = "";
    /** Body gesture ("" = none). */
    public String gesture = "";
    /** Something to ask the phone agent to do ("" = nothing). */
    public String agent = "";
    /** The AI wants to see the screen first (the answer is a screenshot request, not a plan). */
    public boolean wantsScreenshot;
    public String action = "stay";
    /** Surface id to go to ("bottom" = the bottom of the screen), or "". */
    public String target = "";
    /** Horizontal position (screen px) on the target, or -1 = its middle. */
    public int x = -1;
    public String sitStyle = "auto";
    /** Element id to look at, or "". */
    public String look = "";
    /** Short thought bubble, usually "". */
    public String say = "";
    public int nextCheck = DEFAULT_NEXT;

    /** One element on screen as the AI sees it. */
    public static final class Surface {
        public final String id, label, type;
        public final int x, y, w, h;
        /** Wide enough to stand or sit on. */
        public final boolean stand;
        public final boolean tappable, editable, keyboard;

        public Surface(String id, String label, String type, int x, int y, int w, int h,
                       boolean stand, boolean tappable, boolean editable, boolean keyboard) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.type = type == null ? "" : type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.stand = stand;
            this.tappable = tappable;
            this.editable = editable;
            this.keyboard = keyboard;
        }
    }

    /** The character right now. */
    public static final class State {
        public String name = "";
        public String personality = "";
        public int screenW, screenH, bodyW, bodyH;
        /** Body centre x and feet y in screen px. */
        public int x, feetY;
        /** "sit", "stand" or "sleep". */
        public String pose = "stand";
        public String mood = "calm";
        /** What it stands/sits on ("bottom of the screen" or the element's label). */
        public String on = "";
        public String app = "";
        /** Seconds since the screen last changed. */
        public int screenIdle;
        public boolean screenChanged;
        public boolean agentBusy;
        /** Local time "HH:mm". */
        public String clock = "";
    }

    public static String systemPrompt(String name, String personality) {
        return systemPrompt(name, personality, false, false);
    }

    /**
     * @param canScreenshot offer the screenshot tool (vision model and Android 11+)
     * @param canAskAgent   allow asking the phone agent to do something
     */
    public static String systemPrompt(String name, String personality, boolean canScreenshot, boolean canAskAgent) {
        String who = name == null || name.isEmpty() ? "a small animated 3D companion" : name + ", a small animated 3D companion";
        return "You are the body-language brain of " + who + " who lives on top of the user's phone screen, over other apps. "
                + (personality == null || personality.isEmpty() ? "" : "Personality: " + personality.trim() + " ")
                + "Every so often you decide what the character does next so it feels alive: change mood, sit, stand, walk "
                + "somewhere, sleep, look at something, or simply stay. You get the screen's elements (screen pixels, y grows "
                + "downward) and the character's size, position, pose, mood and recent history.\n"
                + "Rules:\n"
                + "- Only stand or sit on elements with \"stand\":true, or on \"bottom\" (the bottom of the screen). Nothing else exists.\n"
                + "- Don't cover what the user is using: avoid the focused text field, the keyboard, video, and the middle of "
                + "the content they are reading. Prefer edges, toolbars, tops of cards and buttons near the sides.\n"
                + "- Be natural and calm: most of the time stay or make a small change; move only now and then. Don't repeat the "
                + "same action again and again; vary it using the history.\n"
                + "- Sleep only when the screen has been idle for a long time or it is late at night; wake up when things change.\n"
                + "- \"say\" is a tiny thought bubble (max 6 words, the user's language if obvious, otherwise English); leave it "
                + "empty most of the time.\n"
                + "- When the agent is busy, keep still (stay).\n"
                + "- Be expressive: she has a face and hands. React to what is on screen with \"face\" and \"gesture\" "
                + "(a funny or cute video: happy + cheer; something sad: sad; waiting long: bored + sway or stretch; reading "
                + "something: think; greeting after a while: wave). Change the face more often than the place.\n"
                + (canScreenshot ? "- Tool: if the element list is not enough to understand the screen (a video, a picture, a game, "
                + "what exactly is playing), answer only {\"tool\":\"screenshot\"} and you will get a screenshot (your own "
                + "character is visible in it), then answer with the plan. Use it only when it really helps.\n" : "")
                + (canAskAgent ? "- You may ask the phone agent (it can tap, scroll, go back, open apps) to do ONE small, harmless, "
                + "reversible thing that clearly helps the user right now — e.g. the user has watched the same reel for a long "
                + "time: \"scroll to the next reel\". Put it in \"agent\" as a short instruction. Never ask it to send or post "
                + "anything, buy, pay, delete, change settings or read private data. Leave \"agent\" empty almost always.\n" : "")
                + "Answer with JSON only, no other text:\n"
                + "{\"mood\":\"happy|calm|curious|excited|sad|sleepy|shy|bored|thoughtful\","
                + "\"face\":\"neutral|happy|sad|surprised|angry|shy|sleepy\","
                + "\"gesture\":\"none|wave|nod|shake_head|cheer|think|look_around|touch_hair|hands_together|stretch|sway|glance\","
                + "\"action\":\"stay|sit|stand|walk|sleep|wake\","
                + "\"target\":\"element id or bottom or empty\",\"x\":optional px centre on the target,"
                + "\"sit_style\":\"auto|kick|crossed|lean_back|hug_knees|chin\","
                + "\"look\":\"element id or empty\",\"say\":\"\","
                + (canAskAgent ? "\"agent\":\"\"," : "")
                + "\"next_check_s\":" + MIN_NEXT + "-" + MAX_NEXT + "}";
    }

    /** The user message: the character's state, its recent history and the screen's elements. */
    public static String userMessage(State st, List<Surface> surfaces, JSONArray history) {
        try {
            JSONObject o = new JSONObject();
            JSONObject screen = new JSONObject();
            screen.put("w", st.screenW).put("h", st.screenH).put("app", st.app)
                    .put("changed_since_last_time", st.screenChanged).put("idle_s", st.screenIdle).put("local_time", st.clock);
            o.put("screen", screen);
            JSONObject me = new JSONObject();
            me.put("body_w", st.bodyW).put("body_h", st.bodyH).put("x", st.x).put("feet_y", st.feetY)
                    .put("pose", st.pose).put("mood", st.mood).put("on", st.on);
            o.put("character", me);
            o.put("agent_busy", st.agentBusy);
            o.put("history", history == null ? new JSONArray() : history);
            JSONArray els = new JSONArray();
            for (Surface s : surfaces) {
                JSONObject e = new JSONObject();
                e.put("id", s.id);
                if (!s.label.isEmpty()) e.put("label", s.label);
                e.put("type", s.type).put("x", s.x).put("y", s.y).put("w", s.w).put("h", s.h);
                if (s.stand) e.put("stand", true);
                if (s.tappable) e.put("tap", true);
                if (s.editable) e.put("input", true);
                if (s.keyboard) e.put("keyboard", true);
                els.put(e);
            }
            o.put("elements", els);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Reads the AI's answer; unknown values fall back to "stay". Never null. */
    public static CompanionPlan parse(String text) {
        CompanionPlan p = new CompanionPlan();
        if (text == null) return p;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return p;
        JSONObject o;
        try {
            o = new JSONObject(text.substring(a, b + 1));
        } catch (Exception e) {
            return p;
        }
        if ("screenshot".equalsIgnoreCase(o.optString("tool", "").trim())) {
            p.wantsScreenshot = true;
            return p;
        }
        String face = o.optString("face", "").trim().toLowerCase(Locale.ROOT);
        if (FACES.contains(face)) p.face = face;
        String gesture = o.optString("gesture", "").trim().toLowerCase(Locale.ROOT);
        if (GESTURES.contains(gesture) && !"none".equals(gesture)) p.gesture = gesture;
        String agent = o.optString("agent", "").replace('\n', ' ').trim();
        if (agent.length() > 200) agent = agent.substring(0, 200);
        p.agent = agent;
        String mood = o.optString("mood", "").trim().toLowerCase(Locale.ROOT);
        if (MOODS.contains(mood)) p.mood = mood;
        String act = o.optString("action", "stay").trim().toLowerCase(Locale.ROOT);
        if ("move".equals(act) || "go".equals(act)) act = "walk";
        p.action = ACTIONS.contains(act) ? act : "stay";
        p.target = clean(o.optString("target", ""));
        if (o.has("x") && !o.isNull("x")) {
            double x = o.optDouble("x", -1);
            p.x = Double.isNaN(x) || x < 0 ? -1 : (int) Math.round(x);
        }
        String style = o.optString("sit_style", "auto").trim().toLowerCase(Locale.ROOT);
        p.sitStyle = SIT_STYLES.contains(style) ? style : "auto";
        p.look = clean(o.optString("look", ""));
        String say = o.optString("say", "").replace('\n', ' ').trim();
        if (say.length() > 48) say = say.substring(0, 47).trim() + "…";
        p.say = say;
        int next = o.optInt("next_check_s", DEFAULT_NEXT);
        p.nextCheck = Math.max(MIN_NEXT, Math.min(MAX_NEXT, next <= 0 ? DEFAULT_NEXT : next));
        return p;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        if ("null".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)) return "";
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
