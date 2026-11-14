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
import android.os.SystemClock;
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
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.character.vrm.Gestures;
import com.suvo.agent.character.vrm.SitStyles;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.character.CompanionPlan;
import com.suvo.agent.ui.MainActivity;
import com.suvo.agent.ui.Notifications;
import com.suvo.agent.voice.SpeechController;

import java.util.Random;

/**
 * Floating companion drawn over other apps (needs "Display over other apps"). It appears when you
 * leave Your Friend and hides while the app is open (the app shows the character itself).
 *
 * Drag it anywhere: it falls onto whatever is below (an element on screen, read through
 * Accessibility, or the bottom of the screen) and sits or stands there. Tap: reaction.
 * Double-tap: a voice call (greets, then listens). With the AI "brain" on ({@link CompanionBrain}) it also changes
 * mood, face and gestures, sits, stands, walks to other places, falls asleep and can ask the
 * agent for small things; whenever what it rests on disappears (another screen, scrolled away) it
 * falls to what is below instead of sitting on nothing.
 *
 * Smoothness: the window and the 3D view inside it never change size (a new size re-creates the
 * GL surface, which flickers), and the character is never shifted inside the window (that and the
 * window's own move land on different frames, which made it jump). The window is wider than the
 * character and has room above its head, so swinging or raised arms and legs are never cut off;
 * it only ever moves. On Android 13+ only the character's own body takes touches. The speech
 * bubble has its own small window.
 *
 * The service is started while the app is in the foreground (Android 15 does not allow starting it
 * from the background) and then stays running; only the overlay's visibility follows the app.
 */
public final class FloatingCompanionService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.companion.STOP";
    private static volatile boolean running;
    private static volatile FloatingCompanionService instance;
    private static volatile boolean appVisible = true;
    /** Width of the 3D view relative to the character's own width (room for swinging limbs). */
    private static final float WIDE = 1.9f;
    /** Room above the head, relative to the character's height (for raised arms). */
    private static final float HEADROOM = 0.24f;

    private WindowManager wm;
    private FrameLayout container;
    private CharacterView character;
    private WindowManager.LayoutParams lp;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private boolean bubbleAdded, bubbleShown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;
    private CompanionBrain brain;

    // Geometry: the character's own box is bodyW × bodyH; the window/view is viewW × viewH with
    // `headroom` px above the body. Placed by the centre x and the window's top (bodyTop).
    private float density;
    private boolean is3d;
    private int bodyW, bodyH, viewW, viewH, headroom;
    private volatile float cx;
    private volatile int bodyTop;

    // What it rests on (top edge, horizontal extent), and whether that is an element on screen.
    private volatile int restTop = -1, restLeft, restRight;
    private volatile boolean restElement;
    private volatile String restLabel = "";
    /** Held, falling, walking or hopping. */
    private volatile boolean moving;
    private boolean sitting;
    private String mood = "calm";
    private ValueAnimator motion;

    // Motion → wind on hair and clothes.
    private float lastWx, lastWy, windVx, windVy;
    private long lastWindNs;

    // Moods from how she is treated: shaken (dizzy, then cross), poked again and again (annoyed),
    // left alone for a long time (sad).
    private static final long IGNORED_MS = 30 * 60_000L;
    private final java.util.ArrayDeque<Long> taps = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> shakes = new java.util.ArrayDeque<>();
    private int shakeTurns;
    private float shakeLastVx;
    private long shakeLastAt;
    private long lastNoticed = SystemClock.uptimeMillis();
    private boolean sadForIgnore;
    private long annoyedAt;
    private Runnable pendingOpen;

    // Dancing to music.
    private MusicWatch music;
    private final com.suvo.agent.core.character.DanceDirector director = new com.suvo.agent.core.character.DanceDirector(new Random());
    private boolean dancing;
    private boolean askingDance;
    private long danceStartNs;
    private String songTitle = "", songArtist = "", songApp = "";

    public static boolean isRunning() {
        return running;
    }

    public static boolean canRun(Context ctx) {
        // The floating companion is the 3D character: without a 3D model it stays off.
        return com.suvo.agent.device.OverlayWindow.available(ctx) && com.suvo.agent.character.vrm.RecommendedVrm.hasModel(ctx);
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
        if (!show) hideBubble();
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
        if (!canRun(this)) {
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

    /** Box sizes for a character size in percent (50–200). */
    private void computeSize(int size) {
        size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        bodyW = (int) ((is3d ? 120 : 110) * density * size / 100f);
        bodyH = (int) ((is3d ? 240 : 132) * density * size / 100f);
        viewW = is3d ? Math.round(bodyW * WIDE) : bodyW;
        headroom = is3d ? Math.round(bodyH * HEADROOM) : 0;
        viewH = bodyH + headroom;
    }

    static final int MIN_SIZE = 50, MAX_SIZE = 200;

    /**
     * Pinched to a new size: the window (and the 3D surface, once) takes the new size; the feet stay
     * where they were, then it lands again.
     */
    private void applySize(int size) {
        if (character == null || container == null) return;
        int feet = bodyTop + viewH;
        computeSize(size);
        AppGraph.get().settings.put(com.suvo.agent.data.Settings.CHARACTER_SIZE, Math.max(MIN_SIZE, Math.min(MAX_SIZE, size)));
        character.setHeadroom(headroom / (float) viewH);
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) character.getLayoutParams();
        cl.width = viewW;
        cl.height = viewH;
        character.setLayoutParams(cl);
        cx = clampCx(cx);
        bodyTop = feet - viewH;
        layoutWindow(false);
        safeUpdate(); // the size changed (layoutWindow only pushes a move)
        container.post(this::limitTouchToBody);
        main.postDelayed(this::land, 250);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void show() {
        running = true;
        instance = this;
        wm = com.suvo.agent.device.OverlayWindow.manager(this);
        AppGraph g = AppGraph.get();
        density = getResources().getDisplayMetrics().density;
        // The 3D character is drawn full-body, so its box is tall; the 2D one is a bust.
        is3d = CharacterView.modelSource(this) != null;
        computeSize(g.settings.integer(com.suvo.agent.data.Settings.CHARACTER_SIZE, 100));
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
        // Always the same size: the GL surface is never re-created (no flicker).
        character.setHeadroom(headroom / (float) viewH);
        container.addView(character, new FrameLayout.LayoutParams(viewW, viewH, Gravity.TOP | Gravity.START));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cx = dm.widthPixels - bodyW / 2f;
        bodyTop = dm.heightPixels / 2;
        lp = new WindowManager.LayoutParams(viewW, viewH,
                com.suvo.agent.device.OverlayWindow.type(this),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        container.setOnTouchListener(new DragTouch());
        layoutWindow(false);
        wm.addView(container, lp);
        container.post(this::limitTouchToBody);
        makeBubble();
        applyVisibility();
        // Drop in from the middle of the screen and land on something.
        main.postDelayed(this::land, 1200);

        agentListener = new AgentListener() {
            @Override
            public void onStatusChanged(TaskState task) {
                if (character == null) return;
                noticed();
                if (!task.status.isTerminal()) {
                    wake(false);
                    stopMumbling();
                }
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
                    stopMumbling();
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
        music = new MusicWatch(this, this::onMusic);
        if (g.settings.bool(com.suvo.agent.data.Settings.COMPANION_DANCE, true)) music.start();
        main.postDelayed(ignoreCheck, 60_000);
    }

    // ------------------------------------------------------------------ window geometry

    /** Moves the window to the character's position (it never changes size). */
    private void layoutWindow(boolean update) {
        if (lp == null || character == null) return;
        int x = Math.round(cx - viewW / 2f), y = bodyTop;
        boolean changed = lp.x != x || lp.y != y;
        lp.x = x;
        lp.y = y;
        lp.width = viewW;
        lp.height = viewH;
        if (moving) motionWind();
        if (bubbleShown) placeBubble();
        if (update && changed) safeUpdate();
    }

    private void placeCharacter() {
        layoutWindow(true);
    }

    /**
     * Android 13+: only the character's body takes touches; the empty room around it (for the
     * limbs) lets taps through where the system allows it.
     */
    private void limitTouchToBody() {
        if (Build.VERSION.SDK_INT < 33 || container == null) return;
        try {
            android.view.AttachedSurfaceControl sc = container.getRootSurfaceControl();
            if (sc == null) return;
            int l = (viewW - bodyW) / 2;
            sc.setTouchableRegion(new android.graphics.Region(l, headroom, l + bodyW, viewH));
        } catch (RuntimeException ignored) {
        }
    }

    /** Moving the character pushes on its hair and clothes (they trail behind). */
    private void motionWind() {
        long now = SystemClock.elapsedRealtimeNanos();
        float dt = (now - lastWindNs) / 1e9f;
        if (lastWindNs != 0 && dt > 0.004f && dt < 0.2f) {
            float vx = (cx - lastWx) / dt, vy = (bodyTop - lastWy) / dt;
            windVx = windVx * 0.6f + vx * 0.4f;
            windVy = windVy * 0.6f + vy * 0.4f;
            character.setWind(-windVx * 0.035f, -windVy * 0.035f);
        }
        lastWx = cx;
        lastWy = bodyTop;
        lastWindNs = now;
    }

    private void stopWind() {
        lastWindNs = 0;
        windVx = windVy = 0;
        if (character != null) character.setWind(0f, 0f);
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

    /** Drag the window; tap = reaction, double-tap = voice call. */
    private final class DragTouch implements View.OnTouchListener {
        float downRawX, downRawY, startCx;
        int startTop;
        boolean moved, wasMoving, onBody;
        long lastTap;
        // Two fingers: pinch to make her bigger or smaller.
        boolean pinching;
        float pinchStart;
        int pinchFromSize, pinchSize;

        private float spread(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0f;
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        private void finishPinch() {
            pinching = false;
            onBody = false; // the finger still down must not drag or tap now
            character.setScaleX(1f);
            character.setScaleY(1f);
            noticed();
            if (Math.abs(pinchSize - pinchFromSize) >= 3) applySize(pinchSize);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(FloatingCompanionService.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!onBody || e.getPointerCount() != 2 || !is3d) return true;
                    pinching = true;
                    pinchStart = Math.max(1f, spread(e));
                    pinchFromSize = AppGraph.get().settings.integer(com.suvo.agent.data.Settings.CHARACTER_SIZE, 100);
                    pinchSize = pinchFromSize;
                    if (moved) {
                        // Was being dragged: put her down where she is first.
                        moved = false;
                        moving = false;
                        character.setAirborne(false);
                        stopWind();
                    }
                    character.setPivotX(viewW / 2f);
                    character.setPivotY(viewH);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (pinching) finishPinch();
                    return true;
                case MotionEvent.ACTION_DOWN:
                    pinching = false;
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    // While gliding the window is bigger than the character: only the character itself reacts.
                    onBody = Math.abs(downRawX - cx) <= bodyW / 2f + dp(8) && downRawY >= bodyTop + headroom - dp(8) && downRawY <= bodyTop + viewH + dp(8);
                    moved = false;
                    wasMoving = false;
                    if (!onBody) return true;
                    // Caught while falling, walking or hopping: it stops there. (Sitting down or
                    // standing up on the spot just finishes.)
                    wasMoving = moving;
                    if (wasMoving) cancelMotion();
                    startCx = cx;
                    startTop = bodyTop;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!onBody) return true;
                    if (pinching) {
                        if (e.getPointerCount() >= 2) {
                            float sc = spread(e) / pinchStart;
                            pinchSize = Math.round(Math.max(MIN_SIZE, Math.min(MAX_SIZE, pinchFromSize * sc)));
                            // Live preview by scaling the view (the window takes the size on release).
                            float k = pinchSize / (float) pinchFromSize;
                            character.setScaleX(k);
                            character.setScaleY(k);
                        }
                        return true;
                    }
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        cancelMotion();
                        startCx = cx;
                        startTop = bodyTop;
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        dx = dy = 0;
                        moving = true;
                        shakeLastAt = 0;
                        shakeTurns = 0;
                        shakeLastVx = 0;
                        shakes.clear();
                        wake(true);
                        stopMumbling();
                        hideBubble();
                        if (brain != null) brain.userInteracted();
                    }
                    if (moved) {
                        // Picked up: legs dangle, and arms, legs, hair and clothes trail behind the motion.
                        sitting = false;
                        character.setSitting(false);
                        character.setAirborne(true);
                        cx = startCx + dx;
                        bodyTop = startTop + (int) dy;
                        layoutWindow(true); // the window follows the finger
                        trackShake(e.getRawX(), e.getEventTime());
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!onBody) return true;
                    if (pinching) {
                        finishPinch();
                        return true;
                    }
                    noticed();
                    if (moved || wasMoving) {
                        stopWind();
                        land();
                        if (moved) afterShake();
                    } else {
                        long now = System.currentTimeMillis();
                        if (poked()) {
                            // Poked again and again: not a double tap any more.
                            if (pendingOpen != null) main.removeCallbacks(pendingOpen);
                            pendingOpen = null;
                            lastTap = 0;
                        } else if (now - lastTap < 320) {
                            lastTap = 0;
                            // A third tap right after means poking, not "open a call": wait a moment.
                            pendingOpen = () -> {
                                pendingOpen = null;
                                openAssistant();
                            };
                            main.postDelayed(pendingOpen, 380);
                        } else {
                            lastTap = now;
                            if (character.isAsleep()) wake(true);
                            else character.animator().touch(CharacterAnimator.TouchRegion.HEAD);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (pinching) {
                        finishPinch();
                        return true;
                    }
                    if (onBody && (moved || wasMoving)) land();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Double tap: a voice call with the companion (it says hello, then listens), over any app. */
    private void openAssistant() {
        wake(false);
        character.react(CharacterAnimator.Expression.HAPPY, 1.2f);
        Intent i = com.suvo.agent.ui.AssistActivity.callIntent(this);
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

    // ------------------------------------------------------------------ speech bubble (own window)

    private void makeBubble() {
        bubble = new TextView(this);
        bubble.setTextColor(getColor(R.color.text_primary));
        bubble.setTextSize(12f);
        bubble.setMaxLines(3);
        bubble.setMaxWidth(dp(220));
        bubble.setBackgroundResource(R.drawable.speech_bubble);
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubbleLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                com.suvo.agent.device.OverlayWindow.type(this),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
    }

    private final Runnable hideBubbleTask = this::hideBubble;

    private void showBubble(String text, long ms) {
        if (bubble == null || text == null || text.isEmpty() || appVisible) return;
        bubble.setText(text);
        bubbleShown = true;
        placeBubble();
        if (!bubbleAdded) {
            try {
                wm.addView(bubble, bubbleLp);
                bubbleAdded = true;
            } catch (RuntimeException e) {
                bubbleShown = false;
                return;
            }
        }
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(0f);
        bubble.setScaleX(0.85f);
        bubble.setScaleY(0.85f);
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        main.removeCallbacks(hideBubbleTask);
        if (ms > 0) main.postDelayed(hideBubbleTask, ms);
    }

    /** Above the character's head, kept on screen. */
    private void placeBubble() {
        if (bubble == null || bubbleLp == null) return;
        int spec = View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST);
        bubble.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
        int x = Math.round(cx - bw / 2f);
        x = Math.max(dp(4), Math.min(screenW() - bw - dp(4), x));
        int y = Math.max(0, bodyTop + headroom + Math.round(bodyH * 0.02f) - bh - dp(2));
        if (bubbleLp.x == x && bubbleLp.y == y) return;
        bubbleLp.x = x;
        bubbleLp.y = y;
        if (bubbleAdded) {
            try {
                wm.updateViewLayout(bubble, bubbleLp);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void hideBubble() {
        main.removeCallbacks(hideBubbleTask);
        if (bubble == null || !bubbleShown) return;
        bubbleShown = false;
        bubble.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!bubbleShown && bubble != null) bubble.setVisibility(View.GONE);
        }).start();
    }

    // ------------------------------------------------------------------ mumbling (mouth moves with a bubble)

    private long mumbleUntil;
    private boolean mumbling;

    private final Runnable mumbleTick = new Runnable() {
        @Override
        public void run() {
            if (character == null) return;
            if (SystemClock.uptimeMillis() >= mumbleUntil) {
                stopMumbling();
                return;
            }
            // Syllables: open, then nearly closed.
            boolean open = rnd.nextFloat() < 0.7f;
            character.setMouth(open ? 0.25f + rnd.nextFloat() * 0.45f : 0.05f, 0.3f + rnd.nextFloat() * 0.5f);
            main.postDelayed(this, 90 + rnd.nextInt(60));
        }
    };

    /** The lips move for about as long as it takes to say {@code text}. */
    private void mumble(String text) {
        AppGraph g = AppGraph.get();
        if (character == null || g.agent.isRunning() || g.speech.isActive()) return;
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        mumbleUntil = SystemClock.uptimeMillis() + Math.max(700, Math.min(3500, words * 280));
        mumbling = true;
        character.setActivity(CharacterAnimator.Activity.SPEAKING);
        main.removeCallbacks(mumbleTick);
        main.post(mumbleTick);
    }

    private void stopMumbling() {
        main.removeCallbacks(mumbleTick);
        if (!mumbling) return;
        mumbling = false;
        if (character != null) {
            character.setMouth(0f, 0.5f);
            AppGraph g = AppGraph.get();
            if (!g.agent.isRunning() && !g.speech.isActive()) character.setActivity(CharacterAnimator.Activity.IDLE);
        }
    }

    /**
     * Shows {@code text} above the head; with voice output on (Settings) it is also spoken aloud
     * (lip-synced), otherwise the lips just move.
     */
    private void say(String text) {
        showBubble(text, Math.max(3000, Math.min(7000, text.length() * 120L)));
        AppGraph g = AppGraph.get();
        if (g.settings.bool(com.suvo.agent.data.Settings.VOICE_OUTPUT, false) && !g.agent.isRunning() && !g.speech.isActive()) {
            g.speech.speak(text);
        } else {
            mumble(text);
        }
    }

    // ------------------------------------------------------------------ outside events

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

    /** The user is typing: glance at the cursor (screen coordinates). Safe from any thread. */
    public static void watchTyping(int x, int y) {
        FloatingCompanionService svc = instance;
        if (svc == null) return;
        svc.main.post(() -> svc.lookAt(x, y, 1.3f));
    }

    private void lookAt(int x, int y, float seconds) {
        if (character == null || container == null || container.getVisibility() != View.VISIBLE || character.isAsleep()) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // Eyes are near the top of the full-body 3D character, lower in the 2D bust.
        float ey = bodyTop + headroom + bodyH * (is3d ? 0.15f : 0.35f);
        float nx = (x - cx) / (dm.widthPixels * 0.5f);
        float ny = (y - ey) / (dm.heightPixels * 0.5f);
        character.animator().lookAt(Math.max(-1f, Math.min(1f, nx)), Math.max(-1f, Math.min(1f, ny)), seconds);
    }

    // ------------------------------------------------------------------ how she is treated

    /** Something happened with her (touch, a task, a call, music): she is not ignored. */
    private void noticed() {
        lastNoticed = SystemClock.uptimeMillis();
        if (sadForIgnore && character != null) {
            sadForIgnore = false;
            character.react(CharacterAnimator.Expression.HAPPY, 2.5f);
            mood = "happy";
            showBubble(getString(R.string.companion_mood_back), 2500);
        }
    }

    /** Left alone for a long time (no touch, no chat, no call): she gets sad and sighs, once. */
    private final Runnable ignoreCheck = new Runnable() {
        @Override
        public void run() {
            if (character == null) return;
            AppGraph g = AppGraph.get();
            boolean busy = g.agent.isRunning() || g.speech.isActive() || com.suvo.agent.voice.CallService.active() || dancing;
            if (busy) lastNoticed = SystemClock.uptimeMillis();
            // The dance switch may have changed in Settings.
            boolean danceOn = g.settings.bool(com.suvo.agent.data.Settings.COMPANION_DANCE, true);
            if (music != null) {
                if (danceOn) music.start();
                else music.stop();
            }
            boolean shown = container != null && container.getVisibility() == View.VISIBLE;
            if (shown && !sadForIgnore && !moving && !character.isAsleep() && SystemClock.uptimeMillis() - lastNoticed > IGNORED_MS) {
                sadForIgnore = true;
                mood = "sad";
                character.animator().setExpression(CharacterAnimator.Expression.SAD);
                character.playGesture(Gestures.SIGH);
                say(getString(R.string.companion_mood_ignored));
            }
            main.postDelayed(this, 60_000);
        }
    };

    /** Taps on her body in the last few seconds: 4 or more is poking. True when she got annoyed now. */
    private boolean poked() {
        long now = SystemClock.uptimeMillis();
        taps.addLast(now);
        while (!taps.isEmpty() && now - taps.peekFirst() > 5000) taps.removeFirst();
        if (taps.size() < 4 || now - annoyedAt < 15_000 || character == null) return false;
        taps.clear();
        annoyedAt = now;
        wake(false);
        character.react(CharacterAnimator.Expression.ANGRY, 4f);
        character.playGesture(Gestures.POUT);
        say(getString(R.string.companion_mood_poked));
        return true;
    }

    /** While dragged: counts quick changes of direction (shaking her). */
    private void trackShake(float rawX, long t) {
        if (shakeLastAt == 0) {
            shakeLastAt = t;
            shakeX = rawX;
            return;
        }
        float dt = (t - shakeLastAt) / 1000f;
        if (dt < 0.012f) return;
        float vx = (rawX - shakeX) / dt;
        shakeX = rawX;
        shakeLastAt = t;
        float fast = bodyH * 1.6f; // px/s
        if (Math.abs(vx) > fast) {
            if (shakeLastVx != 0 && Math.signum(vx) != Math.signum(shakeLastVx)) {
                shakeTurns++;
                shakes.addLast(t);
            }
            shakeLastVx = vx;
        }
        while (!shakes.isEmpty() && t - shakes.peekFirst() > 1800) shakes.removeFirst();
    }

    private float shakeX;

    /** Put down after being dragged: shaken hard means dizzy, then cross. */
    private void afterShake() {
        boolean shaken = shakes.size() >= 4 || shakeTurns >= 6;
        shakes.clear();
        shakeTurns = 0;
        shakeLastVx = 0;
        shakeLastAt = 0;
        if (!shaken || character == null) return;
        character.react(CharacterAnimator.Expression.SURPRISED, 1.2f);
        main.postDelayed(() -> {
            if (character == null) return;
            character.playGesture(Gestures.DIZZY);
            showBubble(getString(R.string.companion_mood_dizzy), 3000);
        }, 900);
        main.postDelayed(() -> {
            if (character == null) return;
            character.react(CharacterAnimator.Expression.ANGRY, 4f);
            character.playGesture(Gestures.POUT);
            say(getString(R.string.companion_mood_shaken));
        }, 5200);
    }

    // ------------------------------------------------------------------ dancing to music

    /** MusicWatch: music started / stopped / the song changed. */
    private void onMusic(boolean playing, String title, String artist, String app) {
        if (character == null) return;
        songTitle = title;
        songArtist = artist;
        songApp = app;
        if (playing) {
            noticed();
            if (!dancing) {
                dancing = true;
                danceStartNs = System.nanoTime();
                director.setBpm(com.suvo.agent.core.character.DanceDirector.DEFAULT_BPM);
                wake(false);
                ensureArmRoom();
                main.removeCallbacks(danceTick);
                main.post(danceTick);
            } else {
                // A new song: plan again at once.
                planDance(true);
            }
        } else {
            dancing = false;
            main.removeCallbacks(danceTick);
            character.setDance(-1, 0, 0, 0);
        }
    }

    private final Runnable danceTick = new Runnable() {
        @Override
        public void run() {
            if (!dancing || character == null) return;
            boolean shown = container != null && container.getVisibility() == View.VISIBLE;
            if (!shown || moving || character.isAsleep()) {
                // Hidden (the app is open), held or asleep: no dancing (and no AI questions).
                character.setDance(-1, 0, 0, 0);
                main.postDelayed(this, 1000);
                return;
            }
            float measured = music != null ? music.bpm() : 0f;
            if (measured > 0) director.setBpm(measured);
            long anchor = music != null ? music.beatAnchorNs() : 0;
            if (anchor == 0) anchor = danceStartNs;
            long now = SystemClock.uptimeMillis();
            if (director.needsPlan(now) && !askingDance) planDance(false);
            float beats = (float) ((System.nanoTime() - anchor) / 1e9 * director.bpm() / 60.0);
            int move = director.moveAt(now, beats);
            character.setDance(move, director.bpm(), director.energy(), anchor);
            main.postDelayed(this, 250);
        }
    };

    /**
     * The next ~30 s of moves: asked from the AI (song, tempo) when that is on, else picked here.
     * Until the AI answers, a picked plan keeps her dancing.
     */
    private void planDance(boolean force) {
        AppGraph g = AppGraph.get();
        long now = SystemClock.uptimeMillis();
        float loud = music != null ? music.loudness() : 0.5f;
        if (director.needsPlan(now) || force) director.setPlan(director.fallbackPlan(loud), -1, now);
        if (!g.settings.bool(com.suvo.agent.data.Settings.COMPANION_DANCE_AI, true) || askingDance) return;
        com.suvo.agent.core.llm.ProviderConfig p = com.suvo.agent.agent.RoleModels.provider(g, com.suvo.agent.agent.RoleModels.BRAIN);
        if (p == null) p = g.providers.active();
        if (p == null || p.validate() != null) return;
        askingDance = true;
        final com.suvo.agent.core.llm.ProviderConfig provider = p;
        final java.util.List<Integer> previous = director.plan();
        final float measured = music != null ? music.bpm() : 0f;
        final String t = songTitle, a = songArtist, app = songApp;
        g.io.execute(() -> {
            com.suvo.agent.core.character.DanceDirector.Answer ans = null;
            try {
                com.suvo.agent.core.llm.ChatRequest req = new com.suvo.agent.core.llm.ChatRequest();
                req.forceNoStream = true;
                req.tools = new org.json.JSONArray();
                req.toolChoice = null;
                req.temperatureOverride = 0.9;
                req.maxTokensOverride = 200;
                req.messages.add(com.suvo.agent.core.llm.ChatMessage.system(com.suvo.agent.core.character.DanceDirector.systemPrompt()));
                req.messages.add(com.suvo.agent.core.llm.ChatMessage.user(
                        com.suvo.agent.core.character.DanceDirector.userMessage(t, a, app, measured, loud, previous)));
                com.suvo.agent.core.llm.LlmClient llm = com.suvo.agent.agent.TrackedLlm.wrap(this,
                        new com.suvo.agent.core.llm.OpenAiCompatClient(provider));
                com.suvo.agent.core.llm.ChatResponse resp = llm.chat(req, com.suvo.agent.core.llm.StreamListener.NONE,
                        new com.suvo.agent.core.util.CancellationToken());
                ans = com.suvo.agent.core.character.DanceDirector.parse(resp == null ? null : resp.content);
            } catch (Exception e) {
                android.util.Log.i("YF.Dance", "AI moves failed: " + e.getMessage());
            }
            final com.suvo.agent.core.character.DanceDirector.Answer fa = ans;
            main.post(() -> {
                askingDance = false;
                if (fa == null || !dancing) return;
                if (!fa.moves.isEmpty()) director.setPlan(fa.moves, fa.energy, SystemClock.uptimeMillis());
                // The AI's tempo only when none could be measured.
                if (fa.bpm > 0 && (music == null || music.bpm() <= 0)) director.setBpm(fa.bpm);
            });
        });
    }

    /**
     * Raised arms need room beside the body: standing right at the screen edge, she first steps in
     * a little so a hand is not cut off by the edge of the screen.
     */
    private void ensureArmRoom() {
        if (character == null || sitting || moving || restTop < 0) return;
        float need = bodyH * 0.36f;
        int w = screenW();
        float target = cx;
        if (cx - need < 0) target = need;
        else if (cx + need > w) target = w - need;
        if (Math.abs(target - cx) < dp(4)) return;
        int tx = Math.round(target);
        if (restElement && (tx < restLeft || tx > restRight)) return; // would walk off the element
        walkTo(tx, null);
    }

    // ------------------------------------------------------------------ mood, face, gesture, sleep

    private static CharacterAnimator.Expression expressionFor(String name) {
        if (name == null) return null;
        switch (name) {
            case "happy":
            case "excited":
                return CharacterAnimator.Expression.HAPPY;
            case "sad":
                return CharacterAnimator.Expression.SAD;
            case "sleepy":
                return CharacterAnimator.Expression.SLEEPY;
            case "shy":
                return CharacterAnimator.Expression.SHY;
            case "surprised":
                return CharacterAnimator.Expression.SURPRISED;
            case "angry":
                return CharacterAnimator.Expression.ANGRY;
            case "neutral":
            case "calm":
            case "curious":
            case "bored":
            case "thoughtful":
                return CharacterAnimator.Expression.NEUTRAL;
            default:
                return null;
        }
    }

    /** Mood (kept) and face: the face wins when given; a surprised face is shown briefly, then the mood's. */
    private void setMoodAndFace(String m, String face) {
        if (character == null) return;
        if (m != null && !m.isEmpty()) mood = m;
        CharacterAnimator.Expression base = expressionFor(mood);
        CharacterAnimator.Expression f = expressionFor(face);
        if (f == CharacterAnimator.Expression.SURPRISED) {
            character.react(f, 1.6f);
        } else if (f != null) {
            base = f;
        }
        if (base != null) character.animator().setExpression(base);
        if (face == null || face.isEmpty()) {
            if ("curious".equals(mood) || "excited".equals(mood)) character.react(CharacterAnimator.Expression.SURPRISED, 0.9f);
        }
        if ("thoughtful".equals(mood) && sitting) character.setSitStyle(SitStyles.CHIN);
    }

    private void gesture(String name) {
        int g = Gestures.parse(name);
        if (g < 0 || character == null || character.isAsleep()) return;
        boolean armsUp = g == Gestures.CHEER || g == Gestures.WAVE || g == Gestures.STRETCH;
        if (armsUp && !sitting && !moving) {
            float need = bodyH * 0.36f;
            if (cx - need < 0 || cx + need > screenW()) {
                ensureArmRoom();
                main.postDelayed(() -> {
                    if (character != null) character.playGesture(g);
                }, 1500);
                return;
            }
        }
        character.playGesture(g);
    }

    private void sleep() {
        if (character == null) return;
        stopMumbling();
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
        int footY = bodyTop + Math.round(an[0] * viewH);
        Surface s = findSurface(footY);
        boolean canSit = an[1] > 0 && s.element;
        final int targetTop = canSit ? s.top - Math.round(an[1] * viewH) : s.top - Math.round(an[0] * viewH);
        final int startTop = bodyTop;
        if (targetTop <= startTop + 2) {
            bodyTop = targetTop;
            layoutWindow(true);
            settle(s, canSit);
            return;
        }
        moving = true;
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
            bodyTop = startTop + Math.round((targetTop - startTop) * (float) v.getAnimatedValue());
            placeCharacter();
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
        stopWind();
        sitting = sit;
        character.setAirborne(false);
        character.setSitting(sit);
        character.setPivotY(character.getHeight());
        character.setScaleY(0.93f);
        character.animate().scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Moves the character's top to {@code top} smoothly (sitting down / standing up on the spot). */
    private void shiftTop(int top, long ms) {
        final int from = bodyTop;
        if (from == top) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        a.addUpdateListener(v -> {
            if (character == null) {
                v.cancel();
                return;
            }
            bodyTop = from + Math.round((top - from) * (float) v.getAnimatedValue());
            placeCharacter();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (character == null || moving) return;
            }
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
        shiftTop(restTop - Math.round(an[1] * viewH), 480);
        return true;
    }

    private void standUp() {
        if (character == null || !sitting || restTop < 0) return;
        float[] an = character.anchors();
        sitting = false;
        character.setSitting(false);
        character.setSitStyle(-1);
        shiftTop(restTop - Math.round(an[0] * viewH), 420);
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
            main.postDelayed(go, 500);
        } else {
            go.run();
        }
    }

    /** Strolls along what it stands on (eases in and out); falls off when the element ends under it. */
    private void walkTo(int tx, Runnable then) {
        if (character == null) return;
        final float from = cx;
        float dist = Math.abs(tx - from);
        if (dist < dp(8)) {
            if (then != null) then.run();
            return;
        }
        cancelMotion();
        moving = true;
        character.setWalking(tx > from ? 1f : -1f);
        float speed = 0.42f * bodyH; // px/s: a calm stroll for its size
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) Math.max(600, dist / speed * 1000 + 300));
        // Gentle start and stop, steady in the middle (like the walk cycle speeding up and slowing).
        a.setInterpolator(new PathInterpolator(0.35f, 0f, 0.65f, 1f));
        int edge = Math.round(bodyW * 0.12f);
        a.addUpdateListener(v -> {
            if (character == null) {
                v.cancel();
                return;
            }
            cx = from + (tx - from) * (float) v.getAnimatedValue();
            placeCharacter();
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
                stopWind();
                if (then != null) main.postDelayed(then, 300);
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
        final int y1 = target.top - Math.round(an[0] * viewH);
        int lift = dp(70);
        final float apex = Math.min(y0, y1) - lift;
        final float ctrl = 2f * apex - (y0 + y1) / 2f; // so the arc's top is about at apex
        moving = true;
        character.setAirborne(true);
        double d = Math.hypot(tx - x0, y1 - y0);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) Math.max(500, Math.min(1200, 420 + d / density * 1.1)));
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
            placeCharacter();
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (character == null || cancelled) return;
                settle(target, false);
                if (then != null) main.postDelayed(then, 350);
            }
        });
        motion = a;
        a.start();
    }

    // ------------------------------------------------------------------ the AI brain's view

    private final class BrainBody implements CompanionBrain.Body {

        @Override
        public CompanionPlan.State state() {
            if (character == null || container == null || container.getVisibility() != View.VISIBLE || moving || restTop < 0 || dancing) return null;
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
            if (!"sleep".equals(plan.action)) {
                if (!"stay".equals(plan.action) || !plan.face.isEmpty() || !plan.gesture.isEmpty()) wake(false);
                setMoodAndFace(plan.mood, plan.face);
                gesture(plan.gesture);
            }
            if (look != null) lookAt(look.centerX(), look.centerY(), 3f);
            if (!plan.say.isEmpty()) say(plan.say);
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
                    if (to != null) goTo(to, plan.x, () -> sitDown(style));
                    else sitDown(style);
                    break;
                case "stand":
                    standUp();
                    break;
                case "walk":
                    if (to != null) goTo(to, plan.x, style >= 0 ? () -> sitDown(style) : null);
                    else if (plan.x >= 0) goTo(new Surface(restTop, restLeft, restRight, restElement, restLabel), plan.x, null);
                    break;
                default: // stay
                    if (sitting && style >= 0 && !character.isAsleep()) character.setSitStyle(style);
                    break;
            }
        }

        @Override
        public void saidToAgent(String text) {
            if (character == null) return;
            gesture("nod");
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
        if (music != null) music.stop();
        music = null;
        dancing = false;
        cancelMotion();
        main.removeCallbacksAndMessages(null);
        try {
            AppGraph g = AppGraph.get();
            if (agentListener != null) g.agent.removeListener(agentListener);
            if (speechListener != null) g.speech.removeListener(speechListener);
        } catch (IllegalStateException ignored) {
        }
        if (wm != null) {
            if (container != null) {
                try {
                    wm.removeView(container);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (bubble != null && bubbleAdded) {
                try {
                    wm.removeView(bubble);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        container = null;
        character = null;
        bubble = null;
        super.onDestroy();
    }
}
