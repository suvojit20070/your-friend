package com.suvo.agent.character;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.character.vrm.SitStyles;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.character.CompanionPlan;
import com.suvo.agent.ui.MainActivity;
import com.suvo.agent.ui.Notifications;
import com.suvo.agent.voice.SpeechController;

/**
 * Floating companion drawn over other apps (needs "Display over other apps"). It appears when you
 * leave Your Friend and hides while the app is open (the app shows the character itself).
 *
 * Drag it anywhere: it falls onto whatever is below (an element on screen, read through
 * Accessibility, or the bottom of the screen) and sits or stands there. Tap: reaction.
 * Double-tap: the assistant card. With the AI "brain" on ({@link CompanionBrain}) it also changes
 * mood, sits, stands, walks to other places and falls asleep on its own; and whenever what it
 * rests on disappears (another screen, scrolled away) it falls to what is below instead of sitting
 * on nothing. The character turns to look wherever the agent or the user taps on screen.
 *
 * The window is as narrow as the character while it rests (so it covers little of the app below)
 * and widens around it while it moves, so swinging arms and legs are never cut off at the sides.
 *
 * The service is started while the app is in the foreground (Android 15 does not allow starting it
 * from the background) and then stays running; only the overlay's visibility follows the app.
 */
public final class FloatingCompanionService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.companion.STOP";
    private static volatile boolean running;
    private static volatile FloatingCompanionService instance;
    private static volatile boolean appVisible = true;
    /** Window width while moving, relative to the character's own width. */
    private static final float WIDE = 2.1f;

    private WindowManager wm;
    private FrameLayout container;
    private CharacterView character;
    private WindowManager.LayoutParams lp;
    private TextView bubble;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;
    private CompanionBrain brain;

    // Geometry: the character's own box (bodyW × bodyH), placed by its centre x and its top.
    private float density;
    private boolean is3d;
    private int bodyW, bodyH;
    private volatile float cx;
    private volatile int bodyTop;
    private boolean wide, bubbleOn;
    private int bubbleExtra;

    // What it rests on (top edge, horizontal extent), and whether that is an element on screen.
    private volatile int restTop = -1, restLeft, restRight;
    private volatile boolean restElement;
    private volatile String restLabel = "";
    /** Held, falling, walking or hopping. */
    private volatile boolean moving;
    private boolean sitting;
    private String mood = "calm";
    private ValueAnimator motion;

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

    /** Another app or window came to the front (from the accessibility service). Any thread. */
    public static void screenChanged() {
        FloatingCompanionService svc = instance;
        if (svc != null && svc.brain != null) svc.brain.poke();
    }

    private void applyVisibility() {
        if (container == null) return;
        boolean show = !appVisible;
        container.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) main.postDelayed(this::land, 400); // the screen below may be different now
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
                startForeground(Notifications.ID_COMPANION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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

    private int dp(float v) {
        return Math.round(v * density);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void show() {
        running = true;
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AppGraph g = AppGraph.get();
        density = getResources().getDisplayMetrics().density;
        int size = g.settings.integer(com.suvo.agent.data.Settings.CHARACTER_SIZE, 100);
        // The 3D character is drawn full-body, so its box is tall; the 2D one is a bust.
        is3d = CharacterView.modelSource(this) != null;
        bodyW = (int) ((is3d ? 120 : 110) * density * size / 100f);
        bodyH = (int) ((is3d ? 240 : 132) * density * size / 100f);
        bubbleExtra = dp(56);
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
        container.addView(character, new FrameLayout.LayoutParams(bodyW, bodyH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        bubble = new TextView(this);
        bubble.setTextColor(getColor(R.color.text_primary));
        bubble.setTextSize(12f);
        bubble.setMaxLines(3);
        bubble.setBackgroundResource(R.drawable.speech_bubble);
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubble.setVisibility(View.GONE);
        container.addView(bubble, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cx = dm.widthPixels - bodyW / 2f;
        bodyTop = dm.heightPixels / 2;
        lp = new WindowManager.LayoutParams(bodyW, bodyH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        container.setOnTouchListener(new DragTouch());
        layoutWindow(false);
        wm.addView(container, lp);
        applyVisibility();
        // Drop in from the middle of the screen and land on something.
        main.postDelayed(this::land, 1200);

        agentListener = new AgentListener() {
            @Override
            public void onStatusChanged(TaskState task) {
                if (character == null) return;
                if (!task.status.isTerminal()) wake(false);
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
                if (state == SpeechController.State.SPEAKING) {
                    wake(false);
                    character.setActivity(CharacterAnimator.Activity.SPEAKING);
                } else if (!g.agent.isRunning()) {
                    character.setActivity(CharacterAnimator.Activity.IDLE);
                }
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
        brain = new CompanionBrain(this, new BrainBody());
        brain.start();
    }

    // ------------------------------------------------------------------ window geometry

    /** Places the window around the character (wider while moving, taller with a bubble). */
    private void layoutWindow(boolean update) {
        if (lp == null || character == null) return;
        int winW = Math.round(bodyW * (wide ? WIDE : 1f));
        if (bubbleOn) winW = Math.max(winW, dp(190));
        int extra = bubbleOn ? bubbleExtra : 0;
        lp.width = winW;
        lp.height = bodyH + extra;
        lp.x = Math.round(cx - winW / 2f);
        lp.y = bodyTop - extra;
        // The character view spans the whole width (its camera keeps the same size, it only sees
        // more to the sides), so nothing swinging out is cut off.
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) character.getLayoutParams();
        if (cp.width != winW || cp.height != bodyH) {
            cp.width = winW;
            cp.height = bodyH;
            character.setLayoutParams(cp);
        }
        if (update) safeUpdate();
    }

    private void setWide(boolean w) {
        main.removeCallbacks(narrow);
        if (wide == w) return;
        wide = w;
        layoutWindow(true);
    }

    private final Runnable narrow = () -> {
        if (!moving) setWide(false);
    };

    /** Back to the narrow window once arms and legs have settled. */
    private void narrowSoon() {
        main.removeCallbacks(narrow);
        main.postDelayed(narrow, 1400);
    }

    private void safeUpdate() {
        if (container == null || wm == null) return;
        try {
            wm.updateViewLayout(container, lp);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** Bottom of the usable screen (above the navigation bar). */
    private int screenBottom() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics wmx = wm.getCurrentWindowMetrics();
            android.graphics.Insets ins = wmx.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars());
            return wmx.getBounds().height() - ins.bottom;
        }
        return getResources().getDisplayMetrics().heightPixels;
    }

    private float clampCx(float x) {
        return Math.max(bodyW / 2f, Math.min(screenW() - bodyW / 2f, x));
    }

    // ------------------------------------------------------------------ touch

    /** Drag the window; tap = reaction, double-tap = assistant card. */
    private final class DragTouch implements View.OnTouchListener {
        float downRawX, downRawY, startCx;
        int startTop;
        boolean moved, wasMoving;
        float lastX, lastY;
        long lastT;
        float vx, vy;
        long lastTap;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(FloatingCompanionService.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    // Caught while falling, walking or hopping: it stops there. (Sitting down or
                    // standing up on the spot just finishes.)
                    wasMoving = moving;
                    if (wasMoving) cancelMotion();
                    startCx = cx;
                    startTop = bodyTop;
                    moved = false;
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    lastT = e.getEventTime();
                    vx = vy = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        cancelMotion();
                        startCx = cx;
                        startTop = bodyTop;
                        moving = true;
                        wake(true);
                        hideBubble();
                        setWide(true);
                        if (brain != null) brain.userInteracted();
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
                        sitting = false;
                        character.setSitting(false);
                        character.setAirborne(true);
                        character.setWind(-vx * 0.035f, -vy * 0.035f);
                        cx = startCx + dx;
                        bodyTop = startTop + (int) dy;
                        layoutWindow(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved || wasMoving) {
                        character.setWind(0f, 0f);
                        land();
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastTap < 320) {
                            lastTap = 0;
                            openAssistant();
                        } else {
                            lastTap = now;
                            if (character.isAsleep()) wake(true);
                            else character.animator().touch(CharacterAnimator.TouchRegion.HEAD);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (moved || wasMoving) land();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** The assistant card (same as the system assistant gesture), over whatever app is open. */
    private void openAssistant() {
        wake(false);
        character.react(CharacterAnimator.Expression.HAPPY, 1.2f);
        Intent i = new Intent(this, com.suvo.agent.ui.AssistActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            openApp();
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ bubble

    private final Runnable hideBubbleTask = this::hideBubble;

    private void showBubble(String text, long ms) {
        if (bubble == null || text == null || text.isEmpty()) return;
        bubble.setText(text);
        bubble.setVisibility(View.VISIBLE);
        if (!bubbleOn) {
            bubbleOn = true;
            layoutWindow(true);
        }
        main.removeCallbacks(hideBubbleTask);
        if (ms > 0) main.postDelayed(hideBubbleTask, ms);
    }

    private void hideBubble() {
        if (bubble == null) return;
        main.removeCallbacks(hideBubbleTask);
        bubble.setVisibility(View.GONE);
        if (bubbleOn) {
            bubbleOn = false;
            layoutWindow(true);
        }
    }

    /** The character perks up (wake word heard). Safe from any thread. */
    public static void react() {
        FloatingCompanionService svc = instance;
        if (svc == null) return;
        svc.main.post(() -> {
            if (svc.character == null) return;
            svc.wake(false);
            svc.character.react(CharacterAnimator.Expression.SURPRISED, 1.2f);
        });
    }

    /**
     * Turns the floating character toward a point on screen (where the agent or the user tapped).
     * Safe to call from any thread.
     */
    public static void lookAtScreen(int x, int y) {
        FloatingCompanionService svc = instance;
        if (svc == null) return;
        svc.main.post(() -> svc.lookAt(x, y, 1.8f));
    }

    private void lookAt(int x, int y, float seconds) {
        if (character == null || container == null || container.getVisibility() != View.VISIBLE || character.isAsleep()) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // Eyes are near the top of the full-body 3D character, lower in the 2D bust.
        float ey = bodyTop + bodyH * (is3d ? 0.15f : 0.35f);
        float nx = (x - cx) / (dm.widthPixels * 0.5f);
        float ny = (y - ey) / (dm.heightPixels * 0.5f);
        character.animator().lookAt(Math.max(-1f, Math.min(1f, nx)), Math.max(-1f, Math.min(1f, ny)), seconds);
    }

    // ------------------------------------------------------------------ mood and sleep

    private void setMood(String m) {
        if (m == null || m.isEmpty() || character == null) return;
        mood = m;
        CharacterAnimator.Expression e;
        switch (m) {
            case "happy":
            case "excited":
                e = CharacterAnimator.Expression.HAPPY;
                break;
            case "sad":
                e = CharacterAnimator.Expression.SAD;
                break;
            case "sleepy":
                e = CharacterAnimator.Expression.SLEEPY;
                break;
            case "shy":
                e = CharacterAnimator.Expression.SHY;
                break;
            default:
                e = CharacterAnimator.Expression.NEUTRAL;
                break;
        }
        character.animator().setExpression(e);
        if ("curious".equals(m) || "excited".equals(m)) character.react(CharacterAnimator.Expression.SURPRISED, 0.9f);
        if ("thoughtful".equals(m) && sitting) character.setSitStyle(SitStyles.CHIN);
    }

    private void sleep() {
        if (character == null) return;
        character.setAsleep(true);
        mood = "sleepy";
        character.animator().setExpression(CharacterAnimator.Expression.SLEEPY);
        if (!sitDown(SitStyles.SLEEP)) character.setSitStyle(-1); // standing: just dozes
        showBubble("Zzz…", 5000);
    }

    /** Wakes up (if asleep); {@code startled} = woken by a touch. */
    private void wake(boolean startled) {
        if (character == null || !character.isAsleep()) return;
        character.setAsleep(false);
        character.setSitStyle(-1);
        character.animator().setExpression(CharacterAnimator.Expression.NEUTRAL);
        mood = "calm";
        hideBubble();
        if (startled) character.react(CharacterAnimator.Expression.SURPRISED, 1.4f);
    }

    // ------------------------------------------------------------------ landing, sitting, walking

    private void cancelMotion() {
        ValueAnimator a = motion;
        motion = null;
        if (a != null) a.cancel();
        if (character != null) character.setWalking(0f);
    }

    private static final class Surface {
        final int top, left, right;
        final boolean element;
        final String label;

        Surface(int top, int left, int right, boolean element, String label) {
            this.top = top;
            this.left = left;
            this.right = right;
            this.element = element;
            this.label = label;
        }
    }

    private Surface bottomSurface() {
        return new Surface(screenBottom(), 0, screenW(), false, "bottom of the screen");
    }

    /**
     * Finds what the character lands on below its feet: the top edge of a visible element on screen
     * (read through the accessibility service, when it is on) or the bottom of the screen.
     */
    private Surface findSurface(int footY) {
        Surface best = bottomSurface();
        ScreenSurfaces.Snapshot snap = ScreenSurfaces.scan(getPackageName(), dp(18), 500);
        int x = Math.round(cx);
        for (ScreenSurfaces.Item it : snap.items) {
            Rect b = it.r;
            if (!it.canHold(bodyW, dp(18)) && !it.keyboard) continue;
            if (b.left > x || b.right < x) continue;
            if (b.top >= footY - dp(14) && b.top < best.top - dp(24) && b.top > dp(40)) {
                best = new Surface(b.top, b.left, b.right, true, it.label.isEmpty() ? it.type : it.label);
            }
        }
        return best;
    }

    /** Falls (with gravity) onto the nearest surface below and sits (on an element) or stands there. */
    private void land() {
        if (container == null || character == null) return;
        cancelMotion();
        cx = clampCx(cx);
        float[] an = character.anchors();
        int footY = bodyTop + Math.round(an[0] * bodyH);
        Surface s = findSurface(footY);
        boolean canSit = an[1] > 0 && s.element;
        final int targetTop = canSit ? s.top - Math.round(an[1] * bodyH) : s.top - Math.round(an[0] * bodyH);
        final int startTop = bodyTop;
        if (targetTop <= startTop + 2) {
            bodyTop = targetTop;
            layoutWindow(true);
            settle(s, canSit);
            return;
        }
        moving = true;
        setWide(true);
        float g = 3200f * density / 2.75f; // px/s², similar on all screens
        long ms = (long) (Math.sqrt(2.0 * (targetTop - startTop) / g) * 1000);
        character.setSitting(false);
        sitting = false;
        character.setAirborne(true);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.max(120, Math.min(1400, ms)));
        a.setInterpolator(new AccelerateInterpolator(1f));
        a.addUpdateListener(v -> {
            // The service may have been stopped mid-fall: the window and character are gone then.
            if (character == null || container == null) {
                v.cancel();
                return;
            }
            float t = (float) v.getAnimatedValue();
            bodyTop = startTop + Math.round((targetTop - startTop) * t);
            // Falling: hair and skirt lift up.
            character.setWind(0f, -t * 60f);
            layoutWindow(true);
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (character == null) return;
                character.setWind(0f, 0f);
                if (!cancelled) settle(s, canSit);
            }
        });
        motion = a;
        a.start();
    }

    /** Arrived on {@code s}: stand or sit there, with a small squash. */
    private void settle(Surface s, boolean sit) {
        if (character == null) return;
        restTop = s.top;
        restLeft = s.left;
        restRight = s.right;
        restElement = s.element;
        restLabel = s.label == null ? "" : s.label;
        moving = false;
        sitting = sit;
        character.setAirborne(false);
        character.setSitting(sit);
        character.setPivotY(character.getHeight());
        character.setScaleY(0.93f);
        character.animate().scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
        narrowSoon();
    }

    /** Moves the character's top to {@code top} smoothly (sitting down / standing up on the spot). */
    private void shiftTop(int top, long ms) {
        final int from = bodyTop;
        if (from == top) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(v -> {
            if (character == null) {
                v.cancel();
                return;
            }
            bodyTop = from + Math.round((top - from) * (float) v.getAnimatedValue());
            layoutWindow(true);
        });
        motion = a;
        a.start();
    }

    /** Sits down where it stands (only on an element); false when it cannot sit here. */
    private boolean sitDown(int style) {
        if (character == null || moving || !restElement || restTop < 0) return false;
        float[] an = character.anchors();
        if (an[1] <= 0) return false;
        character.setSitStyle(style);
        if (sitting) return true;
        sitting = true;
        character.setSitting(true);
        shiftTop(restTop - Math.round(an[1] * bodyH), 420);
        return true;
    }

    private void standUp() {
        if (character == null || !sitting || restTop < 0) return;
        float[] an = character.anchors();
        sitting = false;
        character.setSitting(false);
        character.setSitStyle(-1);
        shiftTop(restTop - Math.round(an[0] * bodyH), 380);
    }

    /** Goes to {@code target} (walking along the same surface, hopping to another), then runs {@code then}. */
    private void goTo(Surface target, int x, Runnable then) {
        if (character == null || moving) return;
        int margin = Math.round(bodyW * 0.3f);
        int lo = Math.max(target.left + margin, bodyW / 2), hi = Math.min(target.right - margin, screenW() - bodyW / 2);
        if (lo > hi) lo = hi = (target.left + target.right) / 2;
        int tx = Math.max(lo, Math.min(hi, x >= 0 ? x : (target.left + target.right) / 2));
        boolean sameSurface = restTop >= 0 && Math.abs(target.top - restTop) <= dp(8)
                && Math.min(target.right, restRight) > Math.max(target.left, restLeft);
        Runnable go = () -> {
            if (sameSurface) walkTo(tx, then);
            else hopTo(target, tx, then);
        };
        if (sitting) {
            standUp();
            main.postDelayed(go, 450);
        } else {
            go.run();
        }
    }

    /** Strolls along what it stands on; falls off when the element ends under it. */
    private void walkTo(int tx, Runnable then) {
        if (character == null) return;
        float from = cx;
        float dist = Math.abs(tx - from);
        if (dist < dp(8)) {
            if (then != null) then.run();
            return;
        }
        cancelMotion();
        moving = true;
        setWide(true);
        character.setWalking(tx > from ? 1f : -1f);
        float speed = 0.42f * bodyH; // px/s: a calm stroll for its size
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) Math.max(400, dist / speed * 1000));
        a.setInterpolator(new LinearInterpolator());
        int edge = Math.round(bodyW * 0.12f);
        a.addUpdateListener(v -> {
            if (character == null) {
                v.cancel();
                return;
            }
            cx = from + (tx - from) * (float) v.getAnimatedValue();
            layoutWindow(true);
            if (restElement && (cx < restLeft + edge || cx > restRight - edge)) {
                // Walked off the end of the element: fall to what is below.
                v.cancel();
                main.post(this::land);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (character == null) return;
                character.setWalking(0f);
                if (cancelled) return;
                moving = false;
                narrowSoon();
                if (then != null) main.postDelayed(then, 250);
            }
        });
        motion = a;
        a.start();
    }

    /** Jumps in an arc onto another surface. */
    private void hopTo(Surface target, int tx, Runnable then) {
        if (character == null) return;
        cancelMotion();
        float[] an = character.anchors();
        final float x0 = cx;
        final int y0 = bodyTop;
        final int y1 = target.top - Math.round(an[0] * bodyH);
        int apex = Math.min(y0, y1) - dp(70);
        final float ctrl = 2f * apex - (y0 + y1) / 2f; // so the arc's top is about at apex
        moving = true;
        setWide(true);
        character.setAirborne(true);
        double d = Math.hypot(tx - x0, y1 - y0);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) Math.max(450, Math.min(1100, 380 + d / density * 1.1)));
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            if (character == null) {
                v.cancel();
                return;
            }
            float t = (float) v.getAnimatedValue();
            float u = 1f - t;
            cx = x0 + (tx - x0) * t;
            bodyTop = Math.round(u * u * y0 + 2f * u * t * ctrl + t * t * y1);
            character.setWind((x0 - tx) * 0.02f, t < 0.5f ? 20f : -40f);
            layoutWindow(true);
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (character == null) return;
                character.setWind(0f, 0f);
                if (cancelled) return;
                settle(target, false);
                if (then != null) main.postDelayed(then, 300);
            }
        });
        motion = a;
        a.start();
    }

    // ------------------------------------------------------------------ the AI brain's view

    private final class BrainBody implements CompanionBrain.Body {

        @Override
        public CompanionPlan.State state() {
            if (character == null || container == null || container.getVisibility() != View.VISIBLE || moving || restTop < 0) return null;
            CompanionPlan.State st = new CompanionPlan.State();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            st.screenW = dm.widthPixels;
            st.screenH = screenBottom();
            st.bodyW = bodyW;
            st.bodyH = bodyH;
            st.x = Math.round(cx);
            st.feetY = restTop;
            st.pose = character.isAsleep() ? "sleep" : sitting ? "sit" : "stand";
            st.mood = mood;
            st.on = restElement ? (restLabel.isEmpty() ? "an element" : restLabel) : "bottom of the screen";
            return st;
        }

        @Override
        public void apply(CompanionPlan plan, Rect target, boolean targetIsBottom, Rect look) {
            if (character == null || moving) return;
            setMood(plan.mood);
            if (look != null) lookAt(look.centerX(), look.centerY(), 3f);
            if (!plan.say.isEmpty()) showBubble(plan.say, 4500);
            Surface to = targetIsBottom ? bottomSurface()
                    : target != null ? new Surface(target.top, target.left, target.right, true, "") : null;
            int style = SitStyles.parse(plan.sitStyle);
            switch (plan.action) {
                case "sleep":
                    if (to != null) goTo(to, plan.x, FloatingCompanionService.this::sleep);
                    else sleep();
                    break;
                case "wake":
                    wake(false);
                    break;
                case "sit":
                    wake(false);
                    if (to != null) goTo(to, plan.x, () -> sitDown(style));
                    else sitDown(style);
                    break;
                case "stand":
                    wake(false);
                    standUp();
                    break;
                case "walk":
                    wake(false);
                    if (to != null) goTo(to, plan.x, style >= 0 ? () -> sitDown(style) : null);
                    else if (plan.x >= 0) goTo(new Surface(restTop, restLeft, restRight, restElement, restLabel), plan.x, null);
                    break;
                default: // stay
                    if (sitting && style >= 0 && !character.isAsleep()) character.setSitStyle(style);
                    break;
            }
        }

        @Override
        public void supportLost() {
            if (character == null || moving) return;
            boolean wasAsleep = character.isAsleep();
            restTop = -1;
            wake(wasAsleep);
            land();
        }

        @Override
        public int[] rest() {
            int top = restTop;
            if (top < 0) return null;
            return new int[]{restLeft, top, restRight, restElement ? 1 : 0, moving ? 0 : 1, bodyW, Math.round(cx)};
        }

        @Override
        public boolean visible() {
            return !appVisible && container != null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (brain != null) brain.stop();
        brain = null;
        cancelMotion();
        main.removeCallbacksAndMessages(null);
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
