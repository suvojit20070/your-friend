package com.suvo.agent.character.vrm;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import com.suvo.agent.core.character.RigParams;

import java.io.File;

/**
 * Your Friend — Original project by Suvo.
 *
 * Shows the 3D VRM character. A TextureView (so the background stays transparent and other views
 * can draw over it, in the app and in the floating overlay) with its own EGL/GLES 3 render thread.
 * The pose comes from {@link #setParams}, called by the owning CharacterView every frame.
 * Rendering stops while the view is hidden; the parsed model is cached so coming back is quick.
 */
public final class VrmView extends TextureView implements TextureView.SurfaceTextureListener {

    public interface Callback {
        void onLoaded(VrmModel model);

        void onError(String message);
    }

    private final RigParams params = new RigParams();
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String source;
    private String framing = "full";
    private int fps = 30;
    private Callback callback;
    private volatile boolean active = true;
    private SurfaceTexture surface;
    private int surfW, surfH;
    private RenderThread thread;
    private volatile VrmRenderer renderer;
    private volatile boolean sitting;
    private volatile float windX, windY;
    private volatile boolean airborne;
    private volatile float walking;
    private volatile int sitStyle = -1;

    /** Walk toward the left (-1) or right (+1), or stand still (0). */
    public void setWalking(float dir) {
        walking = dir;
    }

    /** Plays a {@link Gestures} gesture (wave, nod, cheer…). */
    public void playGesture(int g) {
        VrmRenderer r = renderer;
        if (r != null) r.playGesture(g);
    }

    /** Sitting style ({@link SitStyles} constant, -1 = by mood). */
    public void setSitStyle(int style) {
        sitStyle = style;
    }

    /** Held in the air or falling: legs hang down and the limbs swing with the motion. */
    public void setAirborne(boolean a) {
        airborne = a;
    }

    /** Sit on an edge (floating mode) or stand. */
    public void setSitting(boolean s) {
        sitting = s;
    }

    /** Force on hair and clothes from moving the window, in screen directions (x right, y down). */
    public void setWind(float x, float y) {
        windX = x;
        windY = y;
    }

    /** {foot, seat} positions as fractions of the view height (seat &lt; 0 = cannot sit), or null before loading. */
    public float[] anchors() {
        VrmRenderer r = renderer;
        return r == null ? null : new float[]{r.footFrac, r.seatFrac};
    }

    public VrmView(Context ctx) {
        super(ctx);
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    /** @param source "asset:name.vrm" for a bundled model, or a file path. */
    public void setModel(String source, String framing, Callback cb) {
        this.source = source;
        this.framing = framing == null ? "full" : framing;
        this.callback = cb;
        restart();
    }

    public void setFps(int fps) {
        this.fps = Math.max(15, Math.min(60, fps));
    }

    /** Called by the owner every frame with the current animation parameters. */
    public void setParams(RigParams p) {
        synchronized (lock) {
            params.copyFrom(p);
        }
    }

    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (on) restart();
        else stopThread();
    }

    /** Tap region ("HEAD", "FACE", "BODY") at view coordinates, or null before the model is ready. */
    public String regionAt(float x, float y) {
        VrmRenderer r = renderer;
        return r == null ? null : r.regionAt(x, y);
    }

    private void restart() {
        stopThread();
        if (!active || surface == null || source == null) return;
        thread = new RenderThread(surface, source, framing);
        thread.setSize(surfW, surfH);
        thread.start();
    }

    private void stopThread() {
        RenderThread t = thread;
        thread = null;
        if (t != null) {
            t.quit();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = st;
        surfW = w;
        surfH = h;
        restart();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        surfW = w;
        surfH = h;
        RenderThread t = thread;
        if (t != null) t.setSize(w, h);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stopThread();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void postMain(Runnable r) {
        main.post(r);
    }

    // ------------------------------------------------------------------------------------------

    private final class RenderThread extends Thread {
        private final SurfaceTexture st;
        private final String src;
        private final String frame;
        private volatile boolean running = true;
        private volatile int w, h;
        private volatile boolean sizeChanged = true;
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        RenderThread(SurfaceTexture st, String src, String frame) {
            super("yf-vrm");
            this.st = st;
            this.src = src;
            this.frame = frame;
        }

        void setSize(int w, int h) {
            this.w = w;
            this.h = h;
            sizeChanged = true;
        }

        void quit() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            VrmRenderer r = null;
            try {
                VrmModel model = VrmModel.load(getContext(), src);
                if (!running) return;
                initEgl();
                r = new VrmRenderer(model, frame);
                r.init();
                renderer = r;
                final VrmModel loaded = model;
                postMain(() -> {
                    if (callback != null) callback.onLoaded(loaded);
                });
                RigParams local = new RigParams();
                long start = System.nanoTime();
                while (running) {
                    long t0 = System.nanoTime();
                    if (sizeChanged) {
                        sizeChanged = false;
                        r.resize(w, h);
                    }
                    synchronized (lock) {
                        local.copyFrom(params);
                    }
                    r.setSitting(sitting);
                    r.setAirborne(airborne);
                    r.setWalking(walking);
                    r.setSitStyle(sitStyle);
                    r.setWind(windX, -windY, 0f);
                    r.draw(local, (t0 - start) / 1e9f);
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        int err = EGL14.eglGetError();
                        if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) break;
                    }
                    long frameNs = 1_000_000_000L / fps;
                    long sleep = (frameNs - (System.nanoTime() - t0)) / 1_000_000L;
                    if (sleep > 0) {
                        try {
                            Thread.sleep(sleep);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                Log.e("YF.Vrm", "3D character failed", e);
                final String msg = e instanceof OutOfMemoryError ? "Not enough memory for this 3D model"
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                postMain(() -> {
                    if (callback != null) callback.onError(msg);
                });
            } finally {
                renderer = null;
                if (r != null && context != EGL14.EGL_NO_CONTEXT) {
                    try {
                        r.release();
                    } catch (RuntimeException ignored) {
                    }
                }
                releaseEgl();
            }
        }

        private void initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new IllegalStateException("EGL init failed");
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw new IllegalStateException("This phone has no OpenGL ES 3 support for the 3D character");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("Could not create the GL context");
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], st, new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("Could not create the GL surface");
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new IllegalStateException("eglMakeCurrent failed");
        }

        private void releaseEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return;
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // No eglTerminate: the default display is shared with the app's own UI renderer.
            EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}
