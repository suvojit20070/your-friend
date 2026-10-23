package com.suvo.agent.device;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot still capture with Camera2 (front or rear). Runs only while the app is in the foreground;
 * Android shows its camera-in-use indicator while the camera is open.
 */
public final class CameraCapture {

    private final Context ctx;

    public CameraCapture(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public String cameraIdFor(boolean front) throws CameraAccessException {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : cm.getCameraIdList()) {
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == want) return id;
        }
        return null;
    }

    /** Captures a JPEG; returns the bytes. Caller must hold CAMERA permission and be in the foreground. */
    @SuppressLint("MissingPermission")
    public byte[] capture(boolean front, long timeoutMs) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String id = cameraIdFor(front);
        if (id == null) throw new IllegalStateException("No " + (front ? "front" : "rear") + " camera on this device");
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = choose(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));
        Integer sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);

        HandlerThread t = new HandlerThread("yf-camera");
        t.start();
        Handler h = new Handler(t.getLooper());
        ImageReader jpegReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        ImageReader warm = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
        warm.setOnImageAvailableListener(r -> {
            Image i = r.acquireLatestImage();
            if (i != null) i.close();
        }, h);
        AtomicReference<CameraDevice> device = new AtomicReference<>();
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device.set(camera);
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    error.set("Camera disconnected (another app may be using it)");
                    camera.close();
                    opened.countDown();
                    done.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int err) {
                    error.set("Camera error " + err);
                    camera.close();
                    opened.countDown();
                    done.countDown();
                }
            }, h);
            if (!opened.await(5, TimeUnit.SECONDS) || device.get() == null) {
                throw new IllegalStateException(error.get() == null ? "Camera did not open" : error.get());
            }
            CameraDevice cam = device.get();
            jpegReader.setOnImageAvailableListener(r -> {
                Image img = r.acquireNextImage();
                if (img != null) {
                    ByteBuffer b = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[b.remaining()];
                    b.get(bytes);
                    img.close();
                    result.set(bytes);
                    done.countDown();
                }
            }, h);
            Surface warmS = warm.getSurface();
            Surface jpegS = jpegReader.getSurface();
            CountDownLatch configured = new CountDownLatch(1);
            AtomicReference<CameraCaptureSession> session = new AtomicReference<>();
            //noinspection deprecation
            cam.createCaptureSession(Arrays.asList(warmS, jpegS), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session.set(s);
                    configured.countDown();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    error.set("Camera session configuration failed");
                    configured.countDown();
                }
            }, h);
            configured.await(5, TimeUnit.SECONDS);
            CameraCaptureSession s = session.get();
            if (s == null) throw new IllegalStateException(error.get() == null ? "Camera session failed" : error.get());
            // Let auto-exposure / auto-focus settle on a small preview stream first.
            CaptureRequest.Builder preview = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(warmS);
            preview.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            s.setRepeatingRequest(preview.build(), null, h);
            Thread.sleep(700);
            CaptureRequest.Builder still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(jpegS);
            still.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            still.set(CaptureRequest.JPEG_QUALITY, (byte) 88);
            if (sensor != null) still.set(CaptureRequest.JPEG_ORIENTATION, sensor);
            s.stopRepeating();
            s.capture(still.build(), null, h);
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Capture timed out");
            if (result.get() == null) throw new IllegalStateException(error.get() == null ? "No image captured" : error.get());
            return result.get();
        } finally {
            CameraDevice d = device.get();
            if (d != null) d.close();
            jpegReader.close();
            warm.close();
            t.quitSafely();
        }
    }

    private static Size choose(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = null;
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            if (px <= 2_100_000 && (best == null || px > (long) best.getWidth() * best.getHeight())) best = s;
        }
        return best != null ? best : Collections.min(Arrays.asList(sizes), (a, b) -> Long.compare(
                (long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight()));
    }
}
