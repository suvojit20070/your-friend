package com.suvo.agent.voice.stt;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Your Friend — Original project by Suvo.
 *
 * Offline Whisper models for speech recognition (multilingual, ~99 languages), in the sherpa-onnx
 * format (int8): an encoder, a decoder and a tokens file, downloaded once into the app.
 */
public final class SttModels {

    /** Settings: "google" (the phone's recognizer, default) or "whisper" (offline). */
    public static final String ENGINE = "stt_engine";
    /** Settings: tiny | base | small */
    public static final String MODEL = "stt_whisper_model";

    public static final String[] IDS = {"tiny", "base", "small"};
    private static final String HF = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-%s/resolve/main/%s";

    public interface Progress {
        void onProgress(String file, long done, long total);
    }

    private SttModels() {
    }

    public static File dir(Context c, String id) {
        return new File(c.getFilesDir(), "stt/whisper-" + id);
    }

    public static File encoder(Context c, String id) {
        return new File(dir(c, id), id + "-encoder.int8.onnx");
    }

    public static File decoder(Context c, String id) {
        return new File(dir(c, id), id + "-decoder.int8.onnx");
    }

    public static File tokens(Context c, String id) {
        return new File(dir(c, id), id + "-tokens.txt");
    }

    public static boolean isReady(Context c, String id) {
        return encoder(c, id).isFile() && decoder(c, id).isFile() && tokens(c, id).isFile();
    }

    public static long sizeOnDisk(Context c, String id) {
        long n = 0;
        File[] fs = dir(c, id).listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    public static void delete(Context c, String id) {
        File[] fs = dir(c, id).listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir(c, id).delete();
        WhisperStt.release();
    }

    /** Downloads the three files (call off the main thread). Partial files never count as ready. */
    public static void download(Context c, String id, java.util.concurrent.atomic.AtomicBoolean cancel, Progress p) throws IOException {
        File d = dir(c, id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        for (File target : new File[]{tokens(c, id), encoder(c, id), decoder(c, id)}) {
            if (target.isFile() && target.length() > 0) continue;
            fetch(String.format(HF, id, target.getName()), target, cancel, p);
        }
    }

    private static void fetch(String url, File target, java.util.concurrent.atomic.AtomicBoolean cancel, Progress p) throws IOException {
        File tmp = new File(target.getPath() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        try {
            int code = conn.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + target.getName());
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long done = 0, shown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (done - shown > 512 * 1024) {
                        shown = done;
                        if (p != null) p.onProgress(target.getName(), done, total);
                    }
                }
            }
            if (!tmp.renameTo(target)) throw new IOException("Could not save " + target.getName());
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
    }
}
