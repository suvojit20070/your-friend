package com.suvo.agent;

import android.content.Context;

import com.suvo.agent.agent.AgentRuntime;
import com.suvo.agent.agent.ApprovalCenter;
import com.suvo.agent.character.CharacterRepo;
import com.suvo.agent.core.tools.ApprovalPolicy;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.data.AuditLog;
import com.suvo.agent.data.ConversationRepo;
import com.suvo.agent.data.Db;
import com.suvo.agent.data.ProviderRepo;
import com.suvo.agent.data.SecretStore;
import com.suvo.agent.data.Settings;
import com.suvo.agent.data.SqlMemoryStore;
import com.suvo.agent.data.SshHostRepo;
import com.suvo.agent.data.TaskRepo;
import com.suvo.agent.device.CapabilityRegistry;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.files.SafStore;
import com.suvo.agent.files.Workspace;
import com.suvo.agent.plugins.CustomToolRepo;
import com.suvo.agent.skills.SkillRepo;
import com.suvo.agent.terminal.LocalShellBackend;
import com.suvo.agent.terminal.RuntimeManager;
import com.suvo.agent.terminal.ShellBackend;
import com.suvo.agent.terminal.TermuxBackend;
import com.suvo.agent.tools.BuiltinTools;
import com.suvo.agent.voice.SpeechController;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual dependency graph (no DI framework). Created once in {@link App#onCreate()}; every
 * component receives its collaborators explicitly, which keeps the core testable.
 */
public final class AppGraph {

    private static volatile AppGraph instance;

    public final Context ctx;
    public final Settings settings;
    public final Db db;
    public final SecretStore secrets;
    public final ConversationRepo conversations;
    public final SqlMemoryStore memory;
    public final TaskRepo tasks;
    public final AuditLog audit;
    public final ProviderRepo providers;
    public final SshHostRepo sshHosts;
    /** Accounts the user connected for the agent (Gmail, YouTube, Telegram, Instagram…). */
    public final com.suvo.agent.connectors.ConnectorRepo connectors;
    public final Workspace workspace;
    public final SafStore saf;
    public final TermuxBackend termux;
    /** Termux for the user's own terminal (agent commands use {@link #termux}, kept inside ~/yf). */
    public final TermuxBackend termuxUser;
    public final ShizukuManager shizuku;
    public final CapabilityRegistry capabilities;
    public final ToolRegistry tools;
    public final ApprovalPolicy policy;
    public final ApprovalCenter approvals;
    public final ToolExecutor executor;
    public final SkillRepo skills;
    public final CustomToolRepo customTools;
    public final CharacterRepo characters;
    public final RuntimeManager runtimes;
    public final SpeechController speech;
    public final AgentRuntime agent;
    public final com.suvo.agent.data.EmployeeRepo employees;
    public final com.suvo.agent.agent.Scheduler scheduler;
    public final ExecutorService io = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "yf-io");
        t.setDaemon(true);
        return t;
    });

    public static AppGraph get() {
        AppGraph g = instance;
        if (g == null) throw new IllegalStateException("AppGraph not initialized");
        return g;
    }

    static synchronized AppGraph init(Context ctx) {
        if (instance == null) instance = new AppGraph(ctx.getApplicationContext());
        return instance;
    }

    private AppGraph(Context ctx) {
        this.ctx = ctx;
        settings = Settings.get(ctx);
        db = Db.get(ctx);
        SecretStore.attach(ctx);
        secrets = SecretStore.get();
        conversations = new ConversationRepo(db);
        memory = new SqlMemoryStore(db);
        tasks = new TaskRepo(db);
        audit = new AuditLog(db);
        providers = new ProviderRepo(db, secrets);
        // Decrypting the API keys (Android Keystore) is slow: done once, early, off the main thread.
        io.execute(() -> {
            try {
                providers.list();
            } catch (RuntimeException ignored) {
                // read again when a screen asks
            }
        });
        sshHosts = new SshHostRepo(db, secrets);
        connectors = new com.suvo.agent.connectors.ConnectorRepo(
                ctx.getSharedPreferences("connectors", Context.MODE_PRIVATE), secrets);
        workspace = new Workspace(ctx);
        saf = new SafStore(ctx);
        termux = new TermuxBackend(ctx);
        termuxUser = termux.forUser();
        shizuku = new ShizukuManager(ctx);
        capabilities = new CapabilityRegistry(ctx, settings, shizuku, termux, saf);
        tools = new ToolRegistry();
        policy = new ApprovalPolicy();
        settings.applyTo(policy);
        approvals = new ApprovalCenter(ctx);
        executor = new ToolExecutor(tools, capabilities, policy, approvals, audit);
        executor.decorator = new com.suvo.agent.device.accessibility.ToastAttach(ctx.getPackageName());
        executor.guard = com.suvo.agent.core.tools.ResourceGuard.SHARED;
        skills = new SkillRepo(ctx);
        customTools = new CustomToolRepo(db, secrets);
        characters = new CharacterRepo(ctx);
        runtimes = new RuntimeManager(this);
        speech = new SpeechController(ctx, settings);
        employees = new com.suvo.agent.data.EmployeeRepo(ctx);
        scheduler = new com.suvo.agent.agent.Scheduler(ctx);
        agent = new AgentRuntime(this);
        BuiltinTools.registerAll(this);
        // MCP servers the user added: their tools, from the kept lists (no network here).
        io.execute(() -> {
            try {
                com.suvo.agent.mcp.Mcp.registerAll(this);
            } catch (RuntimeException e) {
                android.util.Log.w("YF.Mcp", "MCP tools not registered", e);
            }
        });
        customTools.registerEnabled(this);
        // The agent manages files with commands (shell: the app's own shell in the workspace, Termux
        // in its home), like other agents. The separate file tools stay, switched off once here; the
        // user can turn any of them on again in Tools.
        if (!settings.bool(FILE_TOOLS_OFF_ONCE, false)) {
            java.util.Set<String> off = new java.util.LinkedHashSet<>(settings.set(Settings.DISABLED_TOOLS));
            off.addAll(SHELL_REPLACES);
            settings.put(Settings.DISABLED_TOOLS, off);
            settings.put(FILE_TOOLS_OFF_ONCE, true);
        }
        tools.setDisabledSet(settings.set(Settings.DISABLED_TOOLS));
        settings.addListener(key -> {
            if (Settings.AUTONOMY.equals(key) || Settings.AUTO_TOOL_EXECUTION.equals(key)
                    || Settings.ALWAYS_ASK_TOOLS.equals(key) || Settings.BLOCKED_TOOLS.equals(key)) {
                settings.applyTo(policy);
            } else if (Settings.DISABLED_TOOLS.equals(key)) {
                tools.setDisabledSet(settings.set(Settings.DISABLED_TOOLS));
            }
        });
    }

    /** Shell backend chosen in Settings, falling back to the local shell when unavailable. */
    public ShellBackend shell(String preferred) {
        String id = preferred == null || preferred.isEmpty() ? settings.str(Settings.SHELL_BACKEND, "local") : preferred;
        if ("termux".equals(id) && termux.unavailableReason() == null) return termux;
        if ("shizuku".equals(id) && shizuku.unavailableReason() == null) return shizuku;
        if ("termux".equals(id)) return termux;
        if ("shizuku".equals(id)) return shizuku;
        return localShell();
    }

    private static final String FILE_TOOLS_OFF_ONCE = "file_tools_off_v1";
    /** The file tools that commands replace (file_transfer stays: shared folders and Termux need it). */
    public static final java.util.List<String> SHELL_REPLACES = java.util.Arrays.asList(
            "file_read", "file_write", "file_edit", "file_list", "file_search", "file_info", "file_manage");

    public LocalShellBackend localShell() {
        return new LocalShellBackend(workspace.root(), ctx.getCacheDir(), terminalEnv());
    }

    public Map<String, String> terminalEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        String raw = settings.str(Settings.TERMINAL_ENV, "");
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).trim();
                if (k.matches("[A-Za-z_][A-Za-z0-9_]*")) env.put(k, line.substring(eq + 1).trim());
            }
        }
        return env;
    }

    public File cacheDir() {
        return ctx.getCacheDir();
    }
}
