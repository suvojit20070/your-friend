package com.suvo.agent;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.suvo.agent.core.agent.AgentCore;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.memory.Memory;
import com.suvo.agent.core.memory.MemoryManager;
import com.suvo.agent.core.persona.Persona;
import com.suvo.agent.core.skills.SkillLoader;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.core.tools.builtin.FileTool;
import com.suvo.agent.core.tools.builtin.GlobalActionTool;
import com.suvo.agent.core.tools.builtin.InputTextTool;
import com.suvo.agent.core.tools.builtin.RememberTool;
import com.suvo.agent.core.tools.builtin.ScreenReadTool;
import com.suvo.agent.core.tools.builtin.ScreenshotTool;
import com.suvo.agent.core.tools.builtin.ShellTool;
import com.suvo.agent.core.tools.builtin.SwipeTool;
import com.suvo.agent.core.tools.builtin.TapTool;
import com.suvo.agent.core.tools.builtin.TimeTool;
import com.suvo.agent.data.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** App-wide singletons: one brain shared by the UI and the foreground service. */
public class AgentApp extends Application {

    private static AgentApp INSTANCE;

    public Settings settings;
    public ToolRegistry tools;
    public Memory memory;
    public MemoryManager memoryManager;
    public AgentCore agent;
    public Persona persona;

    public static AgentApp get() { return INSTANCE; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;

        // Material You dynamic color (wallpaper-based accent) when enabled
        settings = new Settings(this);
        if (settings.dynamicColor()) DynamicColors.applyToActivitiesIfAvailable(this);

        memory = new Memory(this);
        memoryManager = new MemoryManager(memory, new LlmClient());
        tools = new ToolRegistry();

        // built-in tools
        tools.register(new TimeTool());
        tools.register(new RememberTool(memoryManager));
        tools.register(new FileTool(this));
        // device control (available only when accessibility / Shizuku are granted)
        tools.register(new ScreenReadTool());
        tools.register(new TapTool());
        tools.register(new SwipeTool());
        tools.register(new InputTextTool());
        tools.register(new GlobalActionTool());
        tools.register(new ScreenshotTool());
        tools.register(new ShellTool());

        // user skills (assets + filesDir)
        SkillLoader.loadAll(this, tools, settings.secrets());

        // apply saved enable/disable
        for (var t : tools.all())
            tools.setEnabled(t.name(), settings.toolEnabled(t.name(), true));

        // agent
        agent = new AgentCore(tools, memoryManager, 20);
        agent.setConfig(settings.llm());
        loadActivePersona();
    }

    public void reloadLlmConfig() { agent.setConfig(settings.llm()); }

    public Persona loadActivePersona() {
        String id = settings.activePersonaId();
        try {
            String json = readAsset("characters/" + id + "/persona.json");
            Persona p = Persona.fromJson(new JSONObject(json));
            persona = p;
            agent.setPersona(p);
            return p;
        } catch (Exception e) {
            Persona p = new Persona();
            persona = p;
            agent.setPersona(p);
            return p;
        }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
