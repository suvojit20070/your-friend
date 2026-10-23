package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;

/**
 * Registers every built-in tool. Availability is decided per call by the capability registry.
 * A tool whose construction fails (for example an invalid schema) is skipped and logged instead of
 * stopping the whole app from starting; {@link #failures()} lists them for the Tools screen.
 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    private interface Maker {
        com.suvo.agent.core.tools.Tool make();
    }

    private static final java.util.List<String> FAILURES = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static java.util.List<String> failures() {
        return FAILURES;
    }

    private static void add(AppGraph g, Maker m) {
        try {
            g.tools.register(m.make());
        } catch (RuntimeException e) {
            FAILURES.add(String.valueOf(e.getMessage()));
            android.util.Log.e("YF.Tools", "Built-in tool skipped", e);
        }
    }

    public static void registerAll(AppGraph g) {
        add(g, () -> new DiscoveryTools.ListTools(g));
        add(g, () -> new DiscoveryTools.DeviceStatus(g));
        add(g, () -> new AppTools.FindInstalledApps(g));
        add(g, () -> new AppTools.LaunchApp(g));
        add(g, () -> new AppTools.OpenUri(g));
        add(g, () -> new AppTools.Notify(g));
        add(g, () -> new ScreenTools.ScreenAccessibility(g));
        add(g, () -> new ScreenTools.ScreenShell(g));
        add(g, () -> new ShizukuTools.DeviceAdmin(g));
        add(g, () -> new ScreenTools.Screenshot(g));
        add(g, () -> new ScreenTools.ViewImage(g));
        add(g, () -> new ScreenTools.Camera(g));
        add(g, () -> new FileTools.ListFiles(g));
        add(g, () -> new FileTools.ReadFile(g));
        add(g, () -> new FileTools.WriteFile(g));
        add(g, () -> new FileTools.EditFile(g));
        add(g, () -> new FileTools.ManageFile(g));
        add(g, () -> new FileTools.SearchFiles(g));
        add(g, () -> new FileTools.FileInfo(g));
        add(g, () -> new CodeTools.Shell(g));
        add(g, () -> new CodeTools.ExecuteCode(g, "python"));
        add(g, () -> new CodeTools.ExecuteCode(g, "cpp"));
        add(g, () -> new CodeTools.ExecuteCode(g, "java"));
        add(g, () -> new CodeTools.Ssh(g));
        add(g, () -> new WebTools.WebSearch(g));
        add(g, () -> new WebTools.WebRead(g));
        add(g, () -> new WebTools.Download(g));
        add(g, () -> new WebTools.InstallApk(g));
        add(g, () -> new MemorySkillTools.Memory(g));
        add(g, () -> new MemorySkillTools.LoadSkill(g));
        add(g, () -> new MemorySkillTools.MetaTools(g));
    }
}
