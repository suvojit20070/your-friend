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

    /** Gives the next piece of a streamed reply (blocking); null when the reply is over. */
    public interface StreamSource {
        float[] next() throws InterruptedException;
    }

    /**
     * Plays audio that is still arriving (a streamed reply) on one AudioTrack, so there are no gaps
     * between pieces. The mouth follows the loudness of what is playing. Blocks until done or cancelled.
     */
    public void playStream(int rate, StreamSource src, CancellationToken cancel, MouthCallback mouth) throws InterruptedException {
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) minBuf = rate;
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(com.suvo.agent.voice.stt.CallAudio.active() ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT)
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
        // Loudness per written block, so the mouth moves with what is audible (head position).
        final java.util.concurrent.ConcurrentLinkedQueue<long[]> marks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final long[] written = {0};
        Thread ticker = new Thread(() -> {
            try {
                float level = 0f;
                while (!cancel.isCancelled()) {
                    int head;
                    try {
                        head = t.getPlaybackHeadPosition();
                    } catch (IllegalStateException e) {
                        break;
                    }
                    long[] m;
                    while ((m = marks.peek()) != null && m[0] <= head) {
                        marks.poll();
                        level = Float.intBitsToFloat((int) m[1]);
                    }
                    if (mouth != null) mouth.onMouth(Math.min(1f, level * 4f), 0.5f);
                    Thread.sleep(16);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (mouth != null) mouth.onMouth(0f, 0.5f);
            }
        }, "lipsync-stream");
        boolean started = false;
        try {
            while (!cancel.isCancelled()) {
                float[] piece = src.next();
                if (piece == null) break;
                if (!started) {
                    t.play();
                    ticker.start();
                    started = true;
                }
                int off = 0;
                while (off < piece.length && !cancel.isCancelled()) {
                    int n = Math.min(1024, piece.length - off);
                    double sum = 0;
                    for (int i = off; i < off + n; i++) sum += piece[i] * piece[i];
                    float rms = (float) Math.sqrt(sum / Math.max(1, n));
                    marks.add(new long[]{written[0], Float.floatToIntBits(rms)});
                    int w = t.write(piece, off, n, AudioTrack.WRITE_BLOCKING);
                    if (w < 0) break;
                    off += w;
                    written[0] += w;
                }
            }
            if (started) {
                long deadline = System.currentTimeMillis() + 2000 + (long) (1000.0 * written[0] / rate);
                while (!cancel.isCancelled() && System.currentTimeMillis() < deadline) {
                    int head;
                    try {
                        head = t.getPlaybackHeadPosition();
                    } catch (IllegalStateException e) {
                        break;
                    }
                    if (head >= written[0]) break;
                    Thread.sleep(20);
                }
            }
        } finally {
            cancel.removeListener(onCancel);
            ticker.interrupt();
            try {
                ticker.join(200);
            } catch (InterruptedException ignored) {
            }
            if (mouth != null) mouth.onMouth(0f, 0.5f);
            try {
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
            if (current == t) current = null;
        }
    }

    /** Blocks until playback completes or is cancelled. */
    public void play(Pcm pcm, LipSync.Track track, CancellationToken cancel, MouthCallback mouth) {
        int rate = pcm.sampleRate;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) minBuf = rate; // fallback ~0.25 s of floats
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(com.suvo.agent.voice.stt.CallAudio.active() ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT)
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
