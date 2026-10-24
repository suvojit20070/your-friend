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
 * Floating companion drawn over other apps (needs "Display over other apps"). It appears when you
 * leave Your Friend and hides while the app is open (the app shows the character itself). It can be
 * dragged anywhere and snaps to the nearest screen edge. Tap: reaction. Double-tap: point mode —
 * the next tap anywhere is identified via Accessibility and sent to chat as context. Press and hold:
 * talk (release to send what you said to the agent). Tapping the notification opens the app.
 * The character turns to look wherever the agent or the user taps on screen.
 *
 * The service is started while the app is in the foreground (Android 15 does not allow starting it
 * from the background) and then stays running; only the overlay's visibility follows the app.
 */
public final class FloatingCompanionService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.companion.STOP";
    private static volatile boolean running;
    private static volatile FloatingCompanionService instance;
    private static volatile boolean appVisible = true;

    private WindowManager wm;
    private FrameLayout container;
    private CharacterView character;
    private WindowManager.LayoutParams lp;
    private View pointOverlay;
    private TextView talkBubble;
    private com.suvo.agent.voice.PushToTalk ptt;
    private boolean talking;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;

    public static boolean isRunning() {
        return running;
    }

    public static boolean canRun(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** On by default; the user can switch it off in Settings. */
    public static boolean wanted(Context ctx) {
        try {
            return AppGraph.get().settings.bool(com.suvo.agent.data.Settings.FLOATING_COMPANION, true) && canRun(ctx);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Called by the app when its first screen becomes visible or its last one goes away. Starts the
     * service while the app is visible (allowed), then shows the overlay only when the app is not.
     */
    public static void onAppVisibilityChanged(Context ctx, boolean visible) {
        appVisible = visible;
        if (visible && !running && wanted(ctx)) {
            try {
                start(ctx);
            } catch (RuntimeException ignored) {
                // Foreground-service start refused; the next app start tries again.
            }
        }
        FloatingCompanionService svc = instance;
        if (svc != null) svc.main.post(svc::applyVisibility);
    }

    private void applyVisibility() {
        if (container == null) return;
        container.setVisibility(appVisible ? View.GONE : View.VISIBLE);
        if (appVisible) removePointOverlay();
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
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(this, Notifications.CHANNEL_COMPANION)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.companion_notification_title))
                .setContentText(getString(R.string.companion_notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_hide), stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Hold-to-talk records from the floating character, which needs the microphone type.
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (hasMic()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(Notifications.ID_COMPANION, n, types);
            } else if (Build.VERSION.SDK_INT >= 30 && hasMic()) {
                startForeground(Notifications.ID_COMPANION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(Notifications.ID_COMPANION, n);
            }
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
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AppGraph g = AppGraph.get();
        float density = getResources().getDisplayMetrics().density;
        int size = g.settings.integer(com.suvo.agent.data.Settings.CHARACTER_SIZE, 100);
        // The 3D character is drawn full-body, so its window is tall; the 2D one is a bust.
        boolean is3d = CharacterView.modelSource(this) != null;
        int w = (int) ((is3d ? 120 : 110) * density * size / 100f);
        int h = (int) ((is3d ? 240 : 132) * density * size / 100f);
        // Intercept every touch at the window level; otherwise the character view consumes them
        // and the window can never be dragged.
        container = new FrameLayout(this) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                return true;
            }
        };
        container.setClipChildren(false);
        character = new CharacterView(this);
        character.setDraggable(false);
        character.setCharacter(g.characters.active());
        character.setQuality(g.settings.str(com.suvo.agent.data.Settings.ANIMATION_QUALITY, "medium"),
                g.settings.bool(com.suvo.agent.data.Settings.REDUCED_MOTION, false));
        container.addView(character, new FrameLayout.LayoutParams(w, h));
        talkBubble = new TextView(this);
        talkBubble.setTextColor(getColor(R.color.text_primary));
        talkBubble.setTextSize(12f);
        talkBubble.setMaxLines(3);
        talkBubble.setBackgroundResource(R.drawable.speech_bubble);
        talkBubble.setPadding((int) (10 * density), (int) (6 * density), (int) (10 * density), (int) (6 * density));
        talkBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        container.addView(talkBubble, bp);
        lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = dm.widthPixels - w;
        lp.y = dm.heightPixels / 2;
        container.setOnTouchListener(new DragTouch());
        wm.addView(container, lp);
        applyVisibility();
        // Drop in from the middle of the screen and land on something.
        main.postDelayed(this::land, 1200);

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

            @Override
            public void onVisemes(float a, float i, float u, float e, float o) {
                if (character != null) character.setVisemes(a, i, u, e, o);
            }
        };
        g.agent.addListener(agentListener);
        g.speech.addListener(speechListener);
    }

    /** Drag the window; tap = reaction, double-tap = point mode, press and hold = talk. */
    private final class DragTouch implements View.OnTouchListener {
        float downRawX, downRawY;
        int startX, startY;
        boolean moved, wasFalling;
        float lastX, lastY;
        long lastT;
        float vx, vy;
        long lastTap;
        final Runnable holdToTalk = () -> {
            if (!moved) startTalking();
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
                    wasFalling = fall != null && fall.isRunning();
                    cancelFall();
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    lastT = e.getEventTime();
                    vx = vy = 0;
                    main.postDelayed(holdToTalk, 380);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (talking) return true;
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        main.removeCallbacks(holdToTalk);
                    }
                    long t = e.getEventTime();
                    if (t > lastT) {
                        float k = 1000f / (t - lastT);
                        vx = vx * 0.6f + (e.getRawX() - lastX) * k * 0.4f;
                        vy = vy * 0.6f + (e.getRawY() - lastY) * k * 0.4f;
                    }
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    lastT = t;
                    if (moved) {
                        // Picked up: legs dangle, and arms, legs, hair and clothes trail behind the motion.
                        character.setSitting(false);
                        character.setAirborne(true);
                        character.setWind(-vx * 0.035f, -vy * 0.035f);
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        try {
                            wm.updateViewLayout(container, lp);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(holdToTalk);
                    if (talking) {
                        stopTalking();
                    } else if (moved) {
                        character.setWind(0f, 0f);
                        land();
                    } else if (wasFalling) {
                        land(); // caught in the air by a tap: let it fall again
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
                    main.removeCallbacks(holdToTalk);
                    if (talking) stopTalking();
                    return true;
                default:
                    return false;
            }
        }
    }

    private boolean hasMic() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void startTalking() {
        if (!hasMic()) {
            Toast.makeText(this, R.string.companion_mic_needed, Toast.LENGTH_LONG).show();
            openApp();
            return;
        }
        talking = true;
        AppGraph g = AppGraph.get();
        g.speech.stop();
        if (ptt == null) ptt = new com.suvo.agent.voice.PushToTalk(this);
        character.setActivity(CharacterAnimator.Activity.LISTENING);
        showBubble(getString(R.string.companion_listening));
        try {
            container.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (RuntimeException ignored) {
        }
        ptt.begin(g.settings.str(com.suvo.agent.data.Settings.STT_LANGUAGE, ""), new com.suvo.agent.voice.PushToTalk.Callback() {
            @Override
            public void onPartial(String text) {
                showBubble(text);
            }

            @Override
            public void onText(String text) {
                showBubble(text);
                main.postDelayed(() -> hideBubble(), 2500);
                String err = g.agent.submit(g.agent.activeConversation(), text, null);
                if (err != null) Toast.makeText(FloatingCompanionService.this, err, Toast.LENGTH_LONG).show();
                else if (character != null) character.setActivity(CharacterAnimator.Activity.THINKING);
            }

            @Override
            public void onError(String message) {
                hideBubble();
                if (character != null && !g.agent.isRunning()) character.setActivity(CharacterAnimator.Activity.IDLE);
                if (message != null && !message.isEmpty()) Toast.makeText(FloatingCompanionService.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopTalking() {
        talking = false;
        if (ptt != null) ptt.end();
        showBubble(getString(R.string.companion_understanding));
    }

    private void showBubble(String text) {
        if (talkBubble == null) return;
        talkBubble.setText(text);
        talkBubble.setVisibility(View.VISIBLE);
        // The window is only as big as the character; grow it upward while the bubble shows.
        float density = getResources().getDisplayMetrics().density;
        int extra = (int) (56 * density);
        if (lp.height == character.getLayoutParams().height) {
            lp.height += extra;
            lp.y -= extra;
            ((FrameLayout.LayoutParams) character.getLayoutParams()).topMargin = extra;
            character.requestLayout();
            lp.width = Math.max(lp.width, (int) (180 * density));
            try {
                wm.updateViewLayout(container, lp);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void hideBubble() {
        if (talkBubble == null) return;
        talkBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) character.getLayoutParams();
        if (cp.topMargin > 0) {
            lp.height -= cp.topMargin;
            lp.y += cp.topMargin;
            cp.topMargin = 0;
            lp.width = cp.width;
            character.requestLayout();
            try {
                wm.updateViewLayout(container, lp);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** The character perks up (wake word heard). Safe from any thread. */
    public static void react() {
        FloatingCompanionService svc = instance;
        if (svc == null) return;
        svc.main.post(() -> {
            if (svc.character != null) svc.character.react(CharacterAnimator.Expression.SURPRISED, 1.2f);
        });
    }

    /**
     * Turns the floating character toward a point on screen (where the agent or the user tapped).
     * Safe to call from any thread.
     */
    public static void lookAtScreen(int x, int y) {
        FloatingCompanionService svc = instance;
        if (svc == null) return;
        svc.main.post(() -> {
            if (svc.character == null || svc.container == null || svc.container.getVisibility() != View.VISIBLE) return;
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            float cx = svc.lp.x + svc.lp.width / 2f;
            // Eyes are near the top of the full-body 3D window, lower in the 2D bust.
            float cy = svc.lp.y + svc.lp.height * (svc.lp.height > svc.lp.width * 1.5f ? 0.15f : 0.35f);
            float nx = (x - cx) / (dm.widthPixels * 0.5f);
            float ny = (y - cy) / (dm.heightPixels * 0.5f);
            svc.character.animator().lookAt(Math.max(-1f, Math.min(1f, nx)), Math.max(-1f, Math.min(1f, ny)), 1.8f);
        });
    }

    // ------------------------------------------------------------------ landing on screen objects

    private android.animation.ValueAnimator fall;

    private void cancelFall() {
        if (fall != null) fall.cancel();
        fall = null;
    }

    private static final class Surface {
        final int top;
        final boolean element;

        Surface(int top, boolean element) {
            this.top = top;
            this.element = element;
        }
    }

    /** Bottom of the usable screen (above the navigation bar). */
    private int screenBottom() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics wmx = wm.getCurrentWindowMetrics();
            android.graphics.Insets ins = wmx.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars());
            return wmx.getBounds().height() - ins.bottom;
        }
        return getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * Finds what the character lands on below its feet: the top edge of a visible element on screen
     * (read through the accessibility service, when it is on) or the bottom of the screen.
     */
    private Surface findSurface(int footY, int left, int right) {
        int bottom = screenBottom();
        Surface best = new Surface(bottom, false);
        com.suvo.agent.device.accessibility.AgentAccessibilityService svc =
                com.suvo.agent.device.accessibility.AgentAccessibilityService.get();
        if (svc == null) return best;
        float density = getResources().getDisplayMetrics().density;
        int minW = (int) ((right - left) * 0.6f);
        int minH = (int) (18 * density);
        int cx = (left + right) / 2;
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo win : svc.getWindows()) {
                if (win.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                android.view.accessibility.AccessibilityNodeInfo r = win.getRoot();
                if (r != null) q.add(r);
            }
        } catch (RuntimeException ignored) {
        }
        android.graphics.Rect b = new android.graphics.Rect();
        int visited = 0;
        while (!q.isEmpty() && visited < 500) {
            android.view.accessibility.AccessibilityNodeInfo nd = q.poll();
            visited++;
            if (nd == null) continue;
            if (getPackageName().contentEquals(nd.getPackageName() == null ? "" : nd.getPackageName())) continue;
            nd.getBoundsInScreen(b);
            if (nd.isVisibleToUser() && b.width() >= minW && b.height() >= minH && b.left <= cx && b.right >= cx
                    && b.top >= footY - (int) (14 * density) && b.top < best.top - (int) (24 * density) && b.top > (int) (40 * density)) {
                best = new Surface(b.top, true);
            }
            for (int i = 0; i < nd.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo c = nd.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return best;
    }

    /** Falls (with gravity) onto the nearest surface below and stands or sits there. */
    private void land() {
        if (container == null || character == null) return;
        cancelFall();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = Math.max(0, Math.min(dm.widthPixels - lp.width, lp.x));
        float[] an = character.anchors();
        int footY = lp.y + Math.round(an[0] * lp.height);
        Surface s = findSurface(footY, lp.x, lp.x + lp.width);
        boolean canSit = an[1] > 0 && s.element;
        final int targetY = canSit ? s.top - Math.round(an[1] * lp.height) : s.top - Math.round(an[0] * lp.height);
        final int startY = lp.y;
        if (targetY <= startY + 2) {
            lp.y = targetY;
            safeUpdate();
            touchDown(canSit);
            return;
        }
        float g = 3200f * dm.density / 2.75f; // px/s², similar on all screens
        long ms = (long) (Math.sqrt(2.0 * (targetY - startY) / g) * 1000);
        character.setAirborne(true);
        fall = android.animation.ValueAnimator.ofFloat(0f, 1f);
        fall.setDuration(Math.max(120, Math.min(1400, ms)));
        fall.setInterpolator(new android.view.animation.AccelerateInterpolator(1f));
        final boolean sitAtEnd = canSit;
        fall.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            lp.y = startY + Math.round((targetY - startY) * t);
            // Falling: hair and skirt lift up.
            character.setWind(0f, -t * 60f);
            safeUpdate();
        });
        fall.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                character.setWind(0f, 0f);
                if (!cancelled) touchDown(sitAtEnd);
            }
        });
        fall.start();
    }

    private void touchDown(boolean sit) {
        character.setAirborne(false);
        character.setSitting(sit);
        // A small squash on landing.
        character.setPivotY(character.getHeight());
        character.setScaleY(0.93f);
        character.animate().scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
    }

    private void safeUpdate() {
        try {
            wm.updateViewLayout(container, lp);
        } catch (IllegalArgumentException ignored) {
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
        instance = null;
        if (ptt != null) ptt.cancel();
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
