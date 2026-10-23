package com.suvo.agent.character;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.ui.ChatActivity;
import com.suvo.agent.ui.MainActivity;
import com.suvo.agent.ui.Notifications;
import com.suvo.agent.voice.SpeechController;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Optional floating companion drawn over other apps (needs "Display over other apps"). It can be
 * dragged anywhere and snaps to the nearest screen edge. Tap: reaction. Double-tap: point mode —
 * the next tap anywhere is identified via Accessibility and sent to chat as context. Long-press:
 * open the app. The overlay never reads or controls other apps by itself.
 */
public final class FloatingCompanionService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.companion.STOP";
    private static volatile boolean running;

    private WindowManager wm;
    private FrameLayout container;
    private CharacterView character;
    private WindowManager.LayoutParams lp;
    private View pointOverlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;

    public static boolean isRunning() {
        return running;
    }

    public static boolean canRun(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, FloatingCompanionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, FloatingCompanionService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppGraph.get().settings.put(com.suvo.agent.data.Settings.FLOATING_COMPANION, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent stop = PendingIntent.getService(this, 40, new Intent(this, FloatingCompanionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(this, Notifications.CHANNEL_COMPANION)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.companion_notification_title))
                .setContentText(getString(R.string.companion_notification_text))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_hide), stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.ID_COMPANION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(Notifications.ID_COMPANION, n);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (container == null) show();
        return START_NOT_STICKY;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void show() {
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AppGraph g = AppGraph.get();
        float density = getResources().getDisplayMetrics().density;
        int size = g.settings.integer(com.suvo.agent.data.Settings.CHARACTER_SIZE, 100);
        int w = (int) (110 * density * size / 100f);
        int h = (int) (132 * density * size / 100f);
        container = new FrameLayout(this);
        character = new CharacterView(this);
        character.setDraggable(false);
        character.setCharacter(g.characters.active());
        character.setQuality(g.settings.str(com.suvo.agent.data.Settings.ANIMATION_QUALITY, "medium"),
                g.settings.bool(com.suvo.agent.data.Settings.REDUCED_MOTION, false));
        container.addView(character, new FrameLayout.LayoutParams(w, h));
        lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = dm.widthPixels - w;
        lp.y = dm.heightPixels / 2;
        container.setOnTouchListener(new DragTouch());
        wm.addView(container, lp);

        agentListener = new AgentListener() {
            @Override
            public void onStatusChanged(TaskState task) {
                if (character == null) return;
                character.setActivity(task.status.isTerminal() ? CharacterAnimator.Activity.IDLE : CharacterAnimator.Activity.THINKING);
            }

            @Override
            public void onFinished(TaskState task) {
                if (character == null) return;
                character.setActivity(CharacterAnimator.Activity.IDLE);
                if (task.status == TaskState.Status.COMPLETED) character.react(CharacterAnimator.Expression.HAPPY, 1.5f);
                else if (task.status == TaskState.Status.FAILED) character.react(CharacterAnimator.Expression.SAD, 2f);
            }
        };
        speechListener = new SpeechController.Listener() {
            @Override
            public void onSpeechState(SpeechController.State state, String detail) {
                if (character == null) return;
                if (state == SpeechController.State.SPEAKING) character.setActivity(CharacterAnimator.Activity.SPEAKING);
                else if (!g.agent.isRunning()) character.setActivity(CharacterAnimator.Activity.IDLE);
            }

            @Override
            public void onMouth(float open, float form) {
                if (character != null) character.setMouth(open, form);
            }
        };
        g.agent.addListener(agentListener);
        g.speech.addListener(speechListener);
    }

    /** Drag the window; tap/double-tap/long-press dispatch. */
    private final class DragTouch implements View.OnTouchListener {
        float downRawX, downRawY;
        int startX, startY;
        boolean moved;
        long lastTap;
        final Runnable longPress = () -> {
            if (!moved) openApp();
        };

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(FloatingCompanionService.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 200);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        main.removeCallbacks(longPress);
                    }
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        wm.updateViewLayout(container, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (moved) {
                        snapToEdge();
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastTap < 350) {
                            lastTap = 0;
                            startPointMode();
                        } else {
                            lastTap = now;
                            character.animator().touch(CharacterAnimator.TouchRegion.HEAD);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void snapToEdge() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int center = lp.x + lp.width / 2;
        lp.x = center < dm.widthPixels / 2 ? 0 : dm.widthPixels - lp.width;
        lp.y = Math.max(0, Math.min(dm.heightPixels - lp.height, lp.y));
        wm.updateViewLayout(container, lp);
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    /** Captures one tap anywhere on screen and turns it into chat context. */
    @SuppressLint("ClickableViewAccessibility")
    private void startPointMode() {
        if (pointOverlay != null) return;
        TextView hint = new TextView(this);
        hint.setText(R.string.point_mode_hint);
        hint.setTextColor(0xFFFFFFFF);
        hint.setBackgroundColor(0x33000000);
        hint.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        hint.setPadding(32, 120, 32, 32);
        WindowManager.LayoutParams full = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        hint.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int x = (int) e.getRawX();
                int y = (int) e.getRawY();
                removePointOverlay();
                character.animator().lookAt(x < getResources().getDisplayMetrics().widthPixels / 2f ? -1f : 1f, 0f, 1.5f);
                main.postDelayed(() -> describePoint(x, y), 250);
            }
            return true;
        });
        pointOverlay = hint;
        wm.addView(hint, full);
        main.postDelayed(this::removePointOverlay, 10_000);
    }

    private void removePointOverlay() {
        if (pointOverlay != null) {
            try {
                wm.removeView(pointOverlay);
            } catch (IllegalArgumentException ignored) {
            }
            pointOverlay = null;
        }
    }

    private void describePoint(int x, int y) {
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        String context;
        if (svc == null) {
            context = getString(R.string.point_context_no_accessibility, x, y);
        } else {
            AccessibilityNodeInfo node = nodeAt(svc.root(), x, y);
            String label = "";
            if (node != null) {
                CharSequence t = node.getText() != null && node.getText().length() > 0 ? node.getText() : node.getContentDescription();
                label = t == null ? "" : t.toString();
                if (node.isPassword()) label = "(password field)";
            }
            context = getString(R.string.point_context, svc.foregroundPackage(), label.isEmpty() ? "(unlabelled element)" : label, x, y);
        }
        Intent i = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ChatActivity.EXTRA_PREFILL, context);
        startActivity(i);
        Toast.makeText(this, R.string.point_captured, Toast.LENGTH_SHORT).show();
    }

    /** Deepest visible node whose bounds contain the point. */
    static AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo root, int x, int y) {
        if (root == null) return null;
        AccessibilityNodeInfo best = null;
        int bestArea = Integer.MAX_VALUE;
        Deque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(root);
        Rect r = new Rect();
        while (!st.isEmpty()) {
            AccessibilityNodeInfo n = st.pop();
            if (n == null || !n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            boolean labelled = (n.getText() != null && n.getText().length() > 0) || (n.getContentDescription() != null && n.getContentDescription().length() > 0);
            int area = r.width() * r.height();
            if (labelled && area < bestArea) {
                bestArea = area;
                best = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
        }
        return best;
    }

    @Override
    public void onDestroy() {
        running = false;
        removePointOverlay();
        try {
            AppGraph g = AppGraph.get();
            if (agentListener != null) g.agent.removeListener(agentListener);
            if (speechListener != null) g.speech.removeListener(speechListener);
        } catch (IllegalStateException ignored) {
        }
        if (container != null && wm != null) {
            try {
                wm.removeView(container);
            } catch (IllegalArgumentException ignored) {
            }
        }
        container = null;
        character = null;
        super.onDestroy();
    }
}
