package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.suvo.agent.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * The screens of the app and what is on each of them, in one place. Used for three things:
 * <ul>
 *   <li>the {@code load_my_app} tool, which gives the agent this guide so it can tell the user where a
 *       setting is;</li>
 *   <li>tappable links in chat: {@code [Open Voice](yf://open/voice)} becomes a button that opens
 *       that screen ({@link #open});</li>
 *   <li>the search box in Settings, which also finds screens and what they contain.</li>
 * </ul>
 */
public final class AppScreens {

    public static final String SCHEME = "yf://open/";

    public static final class Screen {
        public final String key;
        public final int titleRes;
        public final String title;
        public final Class<? extends Activity> cls;
        /** How to get there, in English. */
        public final String where;
        /** What the screen contains, in English (also used for search). */
        public final String contains;

        Screen(String key, int titleRes, String title, Class<? extends Activity> cls, String where, String contains) {
            this.key = key;
            this.titleRes = titleRes;
            this.title = title;
            this.cls = cls;
            this.where = where;
            this.contains = contains;
        }

        public String link() {
            return "[Open " + title + "](" + SCHEME + key + ")";
        }
    }

    public static final List<Screen> ALL = new ArrayList<>();

    private static void add(String key, int titleRes, String title, Class<? extends Activity> cls, String where, String contains) {
        ALL.add(new Screen(key, titleRes, title, cls, where, contains));
    }

    static {
        add("settings", R.string.section_settings, "Settings", SettingsActivity.class, "Home → gear icon, or More → Settings",
                "Search box at the top (finds any setting). AI: providers link, system prompt link, keep screen on, tool calling, "
                        + "no-limit switches and limits for steps / tool calls / minutes, attach screen tree in every request, "
                        + "attach screenshot in every request. Personality: active character, response language, tone, autonomy mode "
                        + "(Assist / Confirm / Autonomous / Never ask). Voice link. Memory: auto memory, memory retrieval, max memories, "
                        + "summaries, context trigger %. Device: screen control and permissions links. Appearance: theme, accent colour, "
                        + "language, character size, character style (2D / 3D VRM), animation quality, reduced motion, reset position, "
                        + "floating companion, tap cursor. Web: search provider (DuckDuckGo / SearXNG / Brave), SearXNG URL, Brave key. "
                        + "Developer: terminal, workspace, tools, logs, export / import config, storage, about.");
        add("providers", R.string.section_providers, "Providers", ProvidersActivity.class, "More → Providers, or Settings → AI → Providers",
                "Add the AI model provider: name, base URL, API key (stored encrypted), model, vision and tools switches, Test and Fetch models. "
                        + "Pick the default provider. Needed before the agent can answer.");
        add("system_prompt", R.string.sysprompt_title, "System prompt", SystemPromptActivity.class, "Settings → AI → System prompt",
                "Edit the instructions the agent always receives (your own rules and style).");
        add("voice", R.string.section_voice, "Voice", VoiceActivity.class, "More → Voice, or Settings → Voice",
                "Speak replies on/off, voice engine (Hugging Face Space / OmniVoice server / phone voice), reference voice (record or import "
                        + "ref.wav and ref.txt for cloning), language, style, speed, quality steps, server URL and mode, Space name / token / API key, "
                        + "fallback to the phone voice, test button. Voice input: speech language, hands-free mode and wake words.");
        add("memory", R.string.section_memory, "Memory", MemoryActivity.class, "Bottom bar → Memory",
                "Everything the agent remembered: search, add, edit, pin, delete, export, import, delete all. Each memory shows why it was used.");
        add("team", R.string.section_team, "Team", TeamActivity.class, "More → Team",
                "Create employees: name, role, instructions, own AI provider, allowed tools, step limit. The boss agent hands work to them.");
        add("schedule", R.string.section_schedule, "Self-active", ScheduleActivity.class, "More → Self-active",
                "Tasks the agent scheduled for itself (reminders, follow-ups), self check-in every few hours, lessons it recorded.");
        add("characters", R.string.section_characters, "Characters", CharactersActivity.class, "More → Characters",
                "Choose the companion (Aiko, Haru or your own), duplicate, import, export, edit.");
        add("tools", R.string.section_tools, "Tools & skills", ToolsActivity.class, "More → Tools & skills",
                "Built-in tools (risk level, always ask, disable), custom tools (Python / HTTP), skills (SKILL.md) import / export.");
        add("terminal", R.string.section_terminal, "Terminal", TerminalActivity.class, "More → Terminal",
                "Run commands, see detected runtimes (local shell, Termux, Shizuku), SSH hosts.");
        add("files", R.string.section_files, "Files", FilesActivity.class, "More → Files",
                "The agent's private workspace, trash, and folders you allowed.");
        add("screen", R.string.section_screen, "Screen control", ScreenControlActivity.class, "More → Screen control, or Settings → Device",
                "Accessibility service (reads and controls the screen), screen capture session, Shizuku, test read.");
        add("permissions", R.string.section_permissions, "Permissions & privacy", PermissionsActivity.class, "More → Permissions & privacy",
                "Autonomy mode, capability states with fix buttons, per-tool approvals, privacy switches (auto memory, screenshots, LAN access, "
                        + "background listening), audit log, data export / delete.");
        add("storage", R.string.storage_title, "Storage", StorageActivity.class, "Settings → Developer → Storage",
                "How much space the app uses (workspace, database, cache, trash) and buttons to clear cache or trash.");
        add("agent", R.string.section_agent, "Agent", LiveAgentActivity.class, "Bottom bar → Agent",
                "Live view of the running task: steps, tool calls, approvals, team tree, terminal view, logs.");
        add("chat", R.string.section_chat, "Chat", ChatActivity.class, "Bottom bar → Chat", "The conversation with the agent.");
        add("about", R.string.section_about, "About", AboutActivity.class, "Settings → Developer → About",
                "Version, licenses, credits, native engine status.");
    }

    private AppScreens() {
    }

    public static Screen find(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Screen s : ALL) if (s.key.equals(k)) return s;
        return null;
    }

    /** Opens a screen by key; false when the key is unknown. */
    public static boolean open(Context ctx, String key) {
        Screen s = find(key);
        if (s == null) return false;
        Intent i = new Intent(ctx, s.cls);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    /** Words of the query (lower case, 2+ letters). */
    private static List<String> words(String q) {
        List<String> w = new ArrayList<>();
        if (q == null) return w;
        for (String p : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (p.length() >= 2) w.add(p);
        return w;
    }

    /** Screens whose name or contents mention every word of the query (localised title included). */
    public static List<Screen> match(Context ctx, String query) {
        List<String> w = words(query);
        List<Screen> out = new ArrayList<>();
        if (w.isEmpty()) return out;
        for (Screen s : ALL) {
            String hay = (s.key + " " + s.title + " " + ctx.getString(s.titleRes) + " " + s.where + " " + s.contains).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String x : w) if (!hay.contains(x)) {
                all = false;
                break;
            }
            if (all) out.add(s);
        }
        return out;
    }

    /** The guide returned by the {@code load_my_app} tool: all screens, or only the ones matching the query. */
    public static String guide(Context ctx, String query) {
        List<Screen> list = new ArrayList<>(ALL);
        List<String> w = words(query);
        boolean filtered = !w.isEmpty();
        if (filtered) {
            list = new ArrayList<>();
            for (Screen s : ALL) {
                String hay = (s.key + " " + s.title + " " + s.where + " " + s.contains).toLowerCase(Locale.ROOT);
                int hits = 0;
                for (String x : w) if (hay.contains(x)) hits++;
                if (hits > 0) list.add(s);
            }
            if (list.isEmpty()) list = new ArrayList<>(ALL);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Your Friend — app guide\n");
        sb.append("Your Friend is an Android personal AI agent: chat, tools, memory, voice and an animated companion. ")
                .append("Main bottom bar: Home, Chat, Agent, Memory, More. More opens the other screens. Settings is the gear icon on Home.\n\n");
        sb.append("## How to point the user to a screen\n");
        sb.append("When the user asks where something is, tell them the path AND add a tappable button by writing a Markdown link with the ")
                .append(SCHEME).append("<key> address, for example: [Open Voice](").append(SCHEME).append("voice). ")
                .append("The chat shows it as a button and tapping it opens that screen. Use only the keys listed below. ")
                .append("The Settings screen also has a search box at the top.\n\n");
        sb.append("## Screens").append(filtered ? " (matching your query)" : "").append('\n');
        for (Screen s : list) {
            sb.append("\n### ").append(s.title).append(" — key `").append(s.key).append("`\n");
            sb.append("Where: ").append(s.where).append('\n');
            sb.append("Contains: ").append(s.contains).append('\n');
            sb.append("Button: ").append(s.link().replace("[Open ", "[Open ")).append('\n');
        }
        return sb.toString();
    }
}
