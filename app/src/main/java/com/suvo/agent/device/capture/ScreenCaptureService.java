package com.suvo.agent.device.capture;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.suvo.agent.R;
import com.suvo.agent.ui.Notifications;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds a MediaProjection session after the user approved Android's screen-capture dialog. The
 * session shows a persistent notification and ends when the user stops it, revokes it from the
 * system UI, or the app stops it. Frames are only converted when a screenshot is requested.
 */
public final class ScreenCaptureService extends Service {

    private static final String TAG = "YF.Capture";
    public static final String EXTRA_RESULT_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.suvo.agent.capture.STOP";

    public interface Listener {
        void onCaptureSessionChanged(boolean active);
    }

    private static volatile ScreenCaptureService instance;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private final Object frameLock = new Object();
    private Image held;
    private int width;
    private int height;

    public static boolean isActive() {
        return instance != null && instance.projection != null;
    }

    public static ScreenCaptureService get() {
        return instance;
    }

    public static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent stop = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(this, Notifications.CHANNEL_CAPTURE)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stopPi).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_CAPTURE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(Notifications.ID_CAPTURE, n);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start capture foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || projection != null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("yf-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "Projection stopped by the system or user");
                stopSelf();
            }
        }, handler);
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try {
                img = r.acquireLatestImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (img == null) return;
            synchronized (frameLock) {
                if (held != null) held.close();
                held = img;
            }
        }, handler);
        display = projection.createVirtualDisplay("YourFriendCapture", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        instance = this;
        for (Listener l : listeners) l.onCaptureSessionChanged(true);
        return START_NOT_STICKY;
    }

    /** Converts the most recent frame to a bitmap, or returns null if no frame arrived yet. */
    public Bitmap latestFrame() {
        synchronized (frameLock) {
            if (held == null) return null;
            Image.Plane plane = held.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            return rowPadding == 0 ? bmp : Bitmap.createBitmap(bmp, 0, 0, width, height);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        synchronized (frameLock) {
            if (held != null) held.close();
            held = null;
        }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
        }
        projection = null;
        if (thread != null) thread.quitSafely();
        for (Listener l : listeners) l.onCaptureSessionChanged(false);
        super.onDestroy();
    }
}
