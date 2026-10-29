package com.suvo.agent.voice;

import android.content.Context;

import com.suvo.agent.core.util.CancellationToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Your Friend — Original project by Suvo.
 *
 * The offline wake-word engine's model: Vosk's small English model (Kaldi, C++ — it runs natively
 * on the phone; Java only hands it audio). Downloaded once inside the app (~40 MB, not in the
 * APK), unpacked into private storage, and loaded once while hands-free runs.
 *
 * No voice samples and no training are needed: the model already knows English speech; the
 * detector only restricts it to the one wake word you typed (see {@link WakeDetector}).
 */
public final class WakeModel {

    public static final String URL_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    /** Shown before downloading. */
    public static final int SIZE_MB = 40;

    public interface Progress {
        /** {@code percent} -1 while the size is unknown; {@code stage} "download" or "unpack". */
        void onProgress(String stage, int percent);
    }

    private static volatile org.vosk.Model loaded;
    private static volatile Set<String> vocab;

    private WakeModel() {
    }

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "vosk/en-small");
    }

    public static boolean isReady(Context c) {
        return new File(dir(c), ".ready").exists();
    }

    /** Downloads and unpacks the model (call off the main thread). */
    public static void download(Context c, Progress progress, CancellationToken cancel) throws IOException {
        File target = dir(c);
        File tmpZip = new File(c.getCacheDir(), "vosk-model.zip");
        File staging = new File(target.getParentFile(), "en-small.part");
        deleteRecursive(staging);
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_EN).openConnection();
            con.setConnectTimeout(20_000);
            con.setReadTimeout(30_000);
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            if (code / 100 != 2) throw new IOException("Download failed: HTTP " + code);
            long total = con.getContentLengthLong();
            long done = 0;
            int lastPct = -2;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmpZip)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (cancel != null && cancel.isCancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, r);
                    done += r;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != lastPct && progress != null) {
                        lastPct = pct;
                        progress.onProgress("download", pct);
                    }
                }
            }
            if (progress != null) progress.onProgress("unpack", -1);
            // The zip has one top folder (vosk-model-small-en-us-0.15/…): strip it.
            try (ZipInputStream zin = new ZipInputStream(new FileInputStream(tmpZip))) {
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                String canon = staging.getCanonicalPath() + File.separator;
                while ((e = zin.getNextEntry()) != null) {
                    if (cancel != null && cancel.isCancelled()) throw new IOException("Cancelled");
                    String name = e.getName();
                    int slash = name.indexOf('/');
                    String rel = slash >= 0 ? name.substring(slash + 1) : name;
                    if (rel.isEmpty()) continue;
                    File f = new File(staging, rel);
                    if (!f.getCanonicalPath().startsWith(canon)) throw new IOException("Bad zip entry: " + name);
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.mkdirs();
                        continue;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) out.write(buf, 0, r);
                    }
                }
            }
            if (!new File(staging, "am/final.mdl").exists()) throw new IOException("The model archive is incomplete");
            release();
            deleteRecursive(target);
            if (!staging.renameTo(target)) throw new IOException("Could not move the model into place");
            //noinspection ResultOfMethodCallIgnored
            new File(target, ".ready").createNewFile();
        } finally {
            if (con != null) con.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmpZip.delete();
            deleteRecursive(staging);
        }
    }

    /** Removes the model (frees ~70 MB). */
    public static void delete(Context c) {
        release();
        deleteRecursive(dir(c));
    }

    /** The loaded model (loads it the first time — about a second; call off the main thread). */
    public static synchronized org.vosk.Model model(Context c) throws IOException {
        if (loaded != null) return loaded;
        if (!isReady(c)) throw new IOException("The wake word model is not downloaded");
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        loaded = new org.vosk.Model(dir(c).getAbsolutePath());
        return loaded;
    }

    public static synchronized void release() {
        vocab = null;
        if (loaded != null) {
            try {
                loaded.close();
            } catch (RuntimeException ignored) {
            }
            loaded = null;
        }
    }

    /** Words the model knows (from graph/words.txt); empty when the file is missing. */
    public static synchronized Set<String> vocabulary(Context c) {
        if (vocab != null) return vocab;
        Set<String> out = new HashSet<>();
        File f = new File(dir(c), "graph/words.txt");
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    String w = sp > 0 ? line.substring(0, sp) : line.trim();
                    if (!w.isEmpty()) out.add(w);
                }
            } catch (IOException ignored) {
            }
        }
        vocab = out;
        return out;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
