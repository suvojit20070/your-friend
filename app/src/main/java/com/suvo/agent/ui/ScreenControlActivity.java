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
        pullToRefresh(this::build);
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

        unlockSection(r);
        Ui.tintAccent(this, box);
    }

    // ---- Phone unlock for the agent --------------------------------------------------------------

    private void unlockSection(Rows r) {
        r.section(R.string.unlock_section);
        r.note(getString(R.string.unlock_intro));
        String m = com.suvo.agent.device.lock.UnlockSecret.method(this);
        String label = getString("pin".equals(m) ? R.string.unlock_method_pin : "pattern".equals(m) ? R.string.unlock_method_pattern
                : R.string.unlock_method_none);
        r.row(R.drawable.ic_shield, getString(R.string.unlock_method), label, null, v -> chooseUnlock());
        if (!"none".equals(m)) {
            r.switchRow(R.drawable.ic_shield, R.string.unlock_only_charging, R.string.unlock_only_charging_desc,
                    com.suvo.agent.device.lock.UnlockSecret.ONLY_CHARGING, false, null);
            if (g.settings.integer(com.suvo.agent.device.lock.UnlockSecret.FAILS, 0) >= 2) r.note(getString(R.string.unlock_paused));
            r.button(R.string.unlock_test, false, v -> testUnlock());
        }
        r.note(getString(R.string.unlock_note));
    }

    private void chooseUnlock() {
        String[] items = {getString(R.string.unlock_method_none), getString(R.string.unlock_method_pin), getString(R.string.unlock_method_pattern)};
        Ui.show(Ui.dialog(this).setTitle(R.string.unlock_method).setItems(items, (d, w) -> {
            if (w == 0) {
                com.suvo.agent.device.lock.UnlockSecret.clear(this);
                build();
            } else {
                // Warn first: anyone who can talk to the companion could get the phone opened.
                Ui.show(Ui.dialog(this).setTitle(R.string.unlock_warning_title).setMessage(R.string.unlock_warning)
                        .setPositiveButton(R.string.unlock_warning_ok, (d2, w2) -> {
                            if (w == 1) askPin();
                            else askPattern(null);
                        })
                        .setNegativeButton(android.R.string.cancel, null));
            }
        }));
    }

    private void askPin() {
        android.widget.EditText e = new android.widget.EditText(this, null, 0, R.style.YF_Input);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setHint(R.string.unlock_pin_hint);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        wrap.addView(e, lp);
        Ui.show(Ui.dialog(this).setTitle(R.string.unlock_method_pin).setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String pin = e.getText().toString();
                    if (pin.length() < 4) {
                        Ui.toast(this, R.string.unlock_pin_short);
                        return;
                    }
                    saveSecret("pin", pin);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Draw the pattern twice (the second time confirms it). */
    private void askPattern(String first) {
        PatternPadView pad = new PatternPadView(this);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(this, 28), Ui.dp(this, 8), Ui.dp(this, 28), Ui.dp(this, 8));
        wrap.addView(pad, lp);
        final Overlay[] dlg = new Overlay[1];
        pad.setListener(order -> {
            if (order.isEmpty()) {
                Ui.toast(this, R.string.unlock_pattern_short);
                return;
            }
            if (dlg[0] != null) dlg[0].dismiss();
            if (first == null) {
                askPattern(order);
            } else if (first.equals(order)) {
                saveSecret("pattern", order);
            } else {
                Ui.toast(this, R.string.unlock_pattern_mismatch);
                askPattern(null);
            }
        });
        int title = first == null ? R.string.unlock_pattern_draw : R.string.unlock_pattern_again;
        dlg[0] = Ui.show(Ui.dialog(this).setTitle(title)
                .setView(wrap).setNegativeButton(android.R.string.cancel, null));
    }

    private void saveSecret(String method, String secret) {
        try {
            com.suvo.agent.device.lock.UnlockSecret.save(this, method, secret);
            Ui.toast(this, R.string.unlock_saved);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
        }
        build();
    }

    /** Lock the phone, wait, then unlock with the saved method (tells how it went). */
    private void testUnlock() {
        Ui.show(Ui.dialog(this).setTitle(R.string.unlock_test).setMessage(R.string.unlock_test_desc)
                .setPositiveButton(R.string.unlock_test_go, (d, w) -> g.io.execute(() -> {
                    com.suvo.agent.device.lock.ScreenUnlocker.lock(this);
                    String msg;
                    try {
                        Thread.sleep(4000);
                        msg = com.suvo.agent.device.lock.ScreenUnlocker.unlock(this).message;
                    } catch (InterruptedException e) {
                        msg = "Stopped";
                    }
                    String m = msg;
                    runOnUiThread(() -> Ui.show(Ui.dialog(this).setTitle(R.string.unlock_test).setMessage(m).setPositiveButton(android.R.string.ok, null)));
                }))
                .setNegativeButton(android.R.string.cancel, null));
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
