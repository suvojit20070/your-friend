package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;

/** Registers every built-in tool. Availability is decided per call by the capability registry. */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static void registerAll(AppGraph g) {
        g.tools.register(new DiscoveryTools.ListTools(g));
        g.tools.register(new DiscoveryTools.DeviceStatus(g));
        g.tools.register(new AppTools.FindInstalledApps(g));
        g.tools.register(new AppTools.LaunchApp(g));
        g.tools.register(new AppTools.OpenUri(g));
        g.tools.register(new AppTools.Notify(g));
        g.tools.register(new ScreenTools.ScreenAccessibility(g));
        g.tools.register(new ScreenTools.ScreenShell(g));
        g.tools.register(new ScreenTools.Screenshot(g));
        g.tools.register(new ScreenTools.ViewImage(g));
        g.tools.register(new ScreenTools.Camera(g));
        g.tools.register(new FileTools.ListFiles(g));
        g.tools.register(new FileTools.ReadFile(g));
        g.tools.register(new FileTools.WriteFile(g));
        g.tools.register(new FileTools.EditFile(g));
        g.tools.register(new FileTools.ManageFile(g));
        g.tools.register(new FileTools.SearchFiles(g));
        g.tools.register(new FileTools.FileInfo(g));
        g.tools.register(new CodeTools.Shell(g));
        g.tools.register(new CodeTools.ExecuteCode(g, "python"));
        g.tools.register(new CodeTools.ExecuteCode(g, "cpp"));
        g.tools.register(new CodeTools.ExecuteCode(g, "java"));
        g.tools.register(new CodeTools.Ssh(g));
        g.tools.register(new WebTools.WebSearch(g));
        g.tools.register(new WebTools.WebRead(g));
        g.tools.register(new WebTools.Download(g));
        g.tools.register(new WebTools.InstallApk(g));
        g.tools.register(new MemorySkillTools.Memory(g));
        g.tools.register(new MemorySkillTools.LoadSkill(g));
        g.tools.register(new MemorySkillTools.MetaTools(g));
    }
}
