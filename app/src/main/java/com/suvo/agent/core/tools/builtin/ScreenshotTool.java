package com.suvo.agent.core.tools.builtin;

import android.graphics.Bitmap;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capture a screenshot. Returns a short note; the bitmap is stored for the vision
 * pipeline / overlay. (When the LLM provider is multimodal, Phase 3 passes the image.)
 */
public class ScreenshotTool implements Tool {

    private static volatile Bitmap last;
    public static Bitmap last() { return last; }

    @Override public String name() { return "screenshot"; }

    @Override public ToolSchema schema() {
        return new ToolSchema(name(),
                "Capture the current screen as an image. Use read_screen for the text content.",
                ToolSchema.emptyParams(), "low");
    }

    @Override public boolean isAvailable() {
        return AgentAccessibilityService.isConnected()
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R;
    }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled.";
        final AtomicReference<String> err = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        s.screenshot((bmp, e) -> {
            if (bmp != null) last = bmp;
            err.set(e);
            latch.countDown();
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        if (err.get() != null) return "Error: " + err.get();
        return "Screenshot captured (" + (last != null ? last.getWidth() + "x" + last.getHeight() : "?") + ").";
    }
}
