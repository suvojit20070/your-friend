package com.suvo.agent.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.suvo.agent.core.audio.LipSync;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;

/**
 * Plays PCM through AudioTrack and drives the mouth from the ACTUAL playback head position, so lip
 * movement matches what is audible (not what was written to the buffer). Always reports a closed
 * mouth when playback ends, fails or is cancelled.
 */
public final class AudioPlayback {

    public interface MouthCallback {
        void onMouth(float open, float form);

        /** Mouth shape weights a, i, u, e, o for the sound playing right now. */
        default void onVisemes(float a, float i, float u, float e, float o) {
        }
    }

    private volatile AudioTrack current;

    public void stop() {
        AudioTrack t = current;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    /** Blocks until playback completes or is cancelled. */
    public void play(Pcm pcm, LipSync.Track track, CancellationToken cancel, MouthCallback mouth) {
        int rate = pcm.sampleRate;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) minBuf = rate; // fallback ~0.25 s of floats
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(minBuf, 4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        current = t;
        Runnable onCancel = this::stop;
        cancel.onCancel(onCancel);
        final int total = pcm.samples.length;
        Thread ticker = new Thread(() -> {
            try {
                while (!cancel.isCancelled()) {
                    int head;
                    try {
                        head = t.getPlaybackHeadPosition();
                    } catch (IllegalStateException e) {
                        break;
                    }
                    if (head >= total) break;
                    if (track != null && mouth != null) {
                        int f = track.frameAt(head);
                        mouth.onMouth(track.open[f], track.form[f]);
                        float[][] v = track.vis;
                        if (v != null && f < v[0].length) mouth.onVisemes(v[0][f], v[1][f], v[2][f], v[3][f], v[4][f]);
                    }
                    Thread.sleep(16);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (mouth != null) mouth.onMouth(0f, 0.5f);
            }
        }, "lipsync");
        try {
            t.play();
            ticker.start();
            int off = 0;
            while (off < total && !cancel.isCancelled()) {
                int n = Math.min(2048, total - off);
                int w = t.write(pcm.samples, off, n, AudioTrack.WRITE_BLOCKING);
                if (w < 0) break;
                off += w;
            }
            // Wait for the device to finish playing what was written.
            long deadline = System.currentTimeMillis() + 2000 + (long) (1000.0 * total / rate);
            while (!cancel.isCancelled() && System.currentTimeMillis() < deadline) {
                int head;
                try {
                    head = t.getPlaybackHeadPosition();
                } catch (IllegalStateException e) {
                    break;
                }
                if (head >= off) break;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            cancel.removeListener(onCancel);
            ticker.interrupt();
            try {
                ticker.join(200);
            } catch (InterruptedException ignored) {
            }
            if (mouth != null) mouth.onMouth(0f, 0.5f); // null when only previewing a recording
            try {
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
            if (current == t) current = null;
        }
    }
}
