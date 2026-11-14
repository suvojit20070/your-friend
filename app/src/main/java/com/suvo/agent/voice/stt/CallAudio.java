package com.suvo.agent.voice.stt;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * Your Friend — Original project by Suvo.
 *
 * During a call the phone is put in "communication" mode, like a voice call or game chat: the
 * companion's voice plays as call audio (so the echo canceller knows what to remove from the
 * mic) on the loudspeaker — or the headset when one is connected. Restored when the call ends.
 */
public final class CallAudio {

    private static volatile boolean active;
    private static int previousMode = AudioManager.MODE_NORMAL;

    private CallAudio() {
    }

    /** True while a call uses communication mode (the voice then plays as call audio). */
    public static boolean active() {
        return active;
    }

    public static synchronized void enter(Context ctx) {
        if (active) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            previousMode = am.getMode();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean headset = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                    headset = true;
                }
            }
            if (!headset) {
                if (Build.VERSION.SDK_INT >= 31) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            am.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else {
                    //noinspection deprecation
                    am.setSpeakerphoneOn(true);
                }
            }
            active = true;
        } catch (RuntimeException ignored) {
        }
    }

    public static synchronized void exit(Context ctx) {
        if (!active) return;
        active = false;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else //noinspection deprecation
                am.setSpeakerphoneOn(false);
            am.setMode(previousMode);
        } catch (RuntimeException ignored) {
        }
    }
}
