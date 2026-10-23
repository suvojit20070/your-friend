package com.suvo.agent.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.suvo.agent.R;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.device.accessibility.ScreenReader;
import com.suvo.agent.device.capture.ScreenCaptureService;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.terminal.ExecResult;

/** Screen awareness and control backends: Accessibility, screen capture and Shizuku. */
public final class ScreenControlActivity extends BaseActivity {

    private final AgentAccessibilityService.Listener a11y = c -> runOnUiThread(this::build);
    private final ScreenCaptureService.Listener capture = c -> runOnUiThread(this::build);
    private final ShizukuManager.Listener shizuku = s -> runOnUiThread(this::build);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AgentAccessibilityService.addListener(a11y);
        ScreenCaptureService.addListener(capture);
        g.shizuku.addListener(shizuku);
        build();
    }

    @Override
    protected void onPause() {
        AgentAccessibilityService.removeListener(a11y);
        ScreenCaptureService.removeListener(capture);
        g.shizuku.removeListener(shizuku);
        super.onPause();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.screen_intro));

        r.section(R.string.screen_accessibility_section);
        CapabilityState acc = g.capabilities.state(Capability.ACCESSIBILITY);
        r.row(R.drawable.ic_screen, getString(R.string.cap_accessibility), g.capabilities.detail(Capability.ACCESSIBILITY),
                r.chip(PermissionFlows.stateLabel(this, acc), PermissionFlows.stateColor(this, acc)),
                v -> PermissionFlows.fix(this, Capability.ACCESSIBILITY));
        r.switchRow(R.drawable.ic_shield, R.string.privacy_screen_control, R.string.privacy_screen_control_desc,
                Settings.ALLOW_SCREEN_CONTROL, true, v -> build());
        if (AgentAccessibilityService.isConnected()) {
            r.button(R.string.screen_test_read, false, v -> testRead());
        }
        r.note(getString(R.string.screen_supported_actions));

        r.section(R.string.screen_capture_section);
        CapabilityState cap = g.capabilities.state(Capability.SCREEN_CAPTURE);
        String how = AgentAccessibilityService.isConnected() && AgentAccessibilityService.canCaptureScreen()
                ? getString(R.string.screen_capture_via_accessibility)
                : ScreenCaptureService.isActive() ? getString(R.string.screen_capture_session_active) : getString(R.string.screen_capture_needs_consent);
        r.row(R.drawable.ic_camera, getString(R.string.cap_screen_capture), how,
                r.chip(PermissionFlows.stateLabel(this, cap), PermissionFlows.stateColor(this, cap)), null);
        r.switchRow(R.drawable.ic_eye, R.string.privacy_screenshot, R.string.privacy_screenshot_desc, Settings.ALLOW_SCREENSHOT, true, v -> build());
        if (ScreenCaptureService.isActive()) {
            r.button(R.string.screen_capture_stop, false, v -> {
                ScreenCaptureService.stop(this);
                build();
            });
        } else {
            r.button(R.string.screen_capture_start, true, v -> PermissionFlows.fix(this, Capability.SCREEN_CAPTURE));
        }

        r.section(R.string.screen_shizuku_section);
        ShizukuManager.State st = g.shizuku.state();
        CapabilityState sz = g.capabilities.state(Capability.SHIZUKU);
        String detail = st == ShizukuManager.State.READY ? getString(R.string.shizuku_ready_as, g.shizuku.identity()) : g.capabilities.detail(Capability.SHIZUKU);
        r.row(R.drawable.ic_terminal, getString(R.string.cap_shizuku), detail,
                r.chip(PermissionFlows.stateLabel(this, sz), PermissionFlows.stateColor(this, sz)), v -> PermissionFlows.fix(this, Capability.SHIZUKU));
        r.switchRow(R.drawable.ic_shield, R.string.privacy_shizuku, R.string.privacy_shizuku_desc, Settings.ALLOW_SHIZUKU, true, v -> build());
        if (st == ShizukuManager.State.READY) r.button(R.string.shizuku_test, false, v -> testShizuku());
        r.note(getString(R.string.shizuku_explain));
        Ui.tintAccent(this, box);
    }

    private void testRead() {
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        if (svc == null) return;
        g.io.execute(() -> {
            String msg;
            try {
                DisplayMetrics dm = new DisplayMetrics();
                ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
                ScreenReader.Snapshot s = ScreenReader.read(svc, dm, 300, false);
                msg = getString(R.string.screen_test_result, s.json.optJSONArray("elements").length(), svc.foregroundPackage());
            } catch (Exception e) {
                msg = e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> Ui.show(Ui.dialog(this).setTitle(R.string.screen_test_read).setMessage(m).setPositiveButton(android.R.string.ok, null)));
        });
    }

    private void testShizuku() {
        g.io.execute(() -> {
            ExecResult r = g.shizuku.run("id; getprop ro.build.version.release", null, 15_000, new CancellationToken());
            String m = r.ran() ? r.stdout.trim() + (r.stderr.isEmpty() ? "" : "\n" + r.stderr.trim()) : r.error;
            runOnUiThread(() -> Ui.show(Ui.dialog(this).setTitle(R.string.shizuku_test).setMessage(m).setPositiveButton(android.R.string.ok, null)));
        });
    }
}
