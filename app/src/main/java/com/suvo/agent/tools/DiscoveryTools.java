package com.suvo.agent.tools;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.util.DisplayMetrics;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Tools that let the agent discover what it can do right now. */
public final class DiscoveryTools {

    private DiscoveryTools() {
    }

    public static final class ListTools extends BaseTool {
        public ListTools(AppGraph g) {
            super(g, ToolSpec.builder("list_tools")
                    .description("List the tools you can use right now and, for unavailable ones, exactly what the user must enable. "
                            + "Pass name to get one tool's full parameter schema. Also reports device capabilities and installed language runtimes.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"name\":{\"type\":\"string\",\"description\":\"Tool name to describe in full\"},"
                            + "\"include_unavailable\":{\"type\":\"boolean\",\"default\":true}},\"additionalProperties\":false}")
                    .risk(RiskLevel.READ_ONLY).timeoutMs(20_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String name = inv.str("name");
            if (!name.isEmpty()) {
                Tool t = g.tools.get(name);
                if (t == null) return ToolResult.fail("No tool named " + name);
                JSONObject d = t.spec().describe();
                String why = g.tools.unavailableReason(t, g.capabilities);
                J.put(d, "available", why == null);
                if (why != null) J.put(d, "unavailable_reason", why);
                return ToolResult.ok("Tool " + name, d);
            }
            boolean includeUnavailable = inv.bool("include_unavailable", true);
            JSONArray available = new JSONArray();
            JSONArray unavailable = new JSONArray();
            for (Tool t : g.tools.all()) {
                String why = g.tools.unavailableReason(t, g.capabilities);
                if (why == null) available.put(J.obj("name", t.spec().name, "description", firstSentence(t.spec().description), "risk", t.spec().defaultRisk.name()));
                else if (includeUnavailable) unavailable.put(J.obj("name", t.spec().name, "reason", why));
            }
            JSONObject caps = new JSONObject();
            for (Capability c : Capability.values()) J.put(caps, c.name().toLowerCase(Locale.ROOT), g.capabilities.state(c).name().toLowerCase(Locale.ROOT));
            return ToolResult.ok(available.length() + " tools available", J.obj("available", available, "unavailable", unavailable,
                    "capabilities", caps, "runtimes", g.runtimes.cachedStatus(), "autonomy", g.policy.mode.name()));
        }

        private static String firstSentence(String s) {
            int i = s.indexOf(". ");
            return i > 0 ? s.substring(0, i + 1) : s;
        }
    }

    public static final class DeviceStatus extends BaseTool {
        public DeviceStatus(AppGraph g) {
            super(g, ToolSpec.builder("device_status")
                    .description("Current time, timezone, battery, network, storage, screen size, Android version and the app in the foreground.")
                    .risk(RiskLevel.READ_ONLY).timeoutMs(10_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            Context c = g.ctx;
            JSONObject o = new JSONObject();
            Date now = new Date();
            J.put(o, "time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.US).format(now));
            J.put(o, "timezone", TimeZone.getDefault().getID());
            J.put(o, "locale", Locale.getDefault().toLanguageTag());
            Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                J.put(o, "battery_percent", scale > 0 ? level * 100 / scale : -1);
                J.put(o, "charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
            }
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            String net = "none";
            if (cm != null && cm.getActiveNetwork() != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cellular";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                    else net = "other";
                    if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) net += " (no internet)";
                }
            }
            J.put(o, "network", net);
            StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
            J.put(o, "free_storage_mb", fs.getAvailableBytes() / 1048576);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            J.put(o, "screen", dm.widthPixels + "x" + dm.heightPixels);
            J.put(o, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            J.put(o, "device", Build.MANUFACTURER + " " + Build.MODEL);
            AgentAccessibilityService a = AgentAccessibilityService.get();
            if (a != null) J.put(o, "foreground_app", a.foregroundPackage());
            return ToolResult.ok("Device status", o);
        }
    }
}
