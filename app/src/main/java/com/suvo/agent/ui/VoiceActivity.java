package com.suvo.agent.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.voice.HandsFreeService;
import com.suvo.agent.voice.OmniVoiceNativeEngine;
import com.suvo.agent.voice.SpeechInput;
import com.suvo.agent.voice.VoiceProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice output (OmniVoice in-process via audio.cpp, OmniVoice server, or Android system TTS),
 * voice cloning reference (ref.wav + ref.txt), voice input language and hands-free mode.
 */
public final class VoiceActivity extends BaseActivity {

    private static final int REQ_LIB = 121;
    private static final int REQ_MODEL = 122;
    private static final int REQ_REF_WAV = 123;
    private static final int REQ_REF_TXT = 124;
    private static final int REQ_MIC = 125;
    private static final int MAX_RECORD_S = 15;

    private volatile boolean recording;
    private volatile String importProgress = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_voice);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onPause() {
        recording = false;
        super.onPause();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);

        r.section(R.string.voice_output);
        r.switchRow(R.drawable.ic_voice, R.string.voice_speak_replies, R.string.voice_speak_replies_desc, Settings.VOICE_OUTPUT, false, null);
        r.choiceRow(R.drawable.ic_voice, R.string.voice_engine, Settings.VOICE_ENGINE, "omnivoice_native",
                new String[]{getString(R.string.voice_engine_space), getString(R.string.voice_engine_native), getString(R.string.voice_engine_server), getString(R.string.voice_engine_system)},
                new String[]{"hf_space", "omnivoice_native", "omnivoice_server", "system"}, v -> build());
        String why = g.speech.engine().notReadyReason();
        r.note(why == null ? getString(R.string.voice_engine_ready) : getString(R.string.voice_engine_not_ready, why));
        r.button(R.string.voice_test, true, v -> test());
        r.switchRow(R.drawable.ic_refresh, R.string.voice_fallback, R.string.voice_fallback_desc,
                com.suvo.agent.voice.SpeechController.FALLBACK_KEY, true, null);

        String engine = g.settings.str(Settings.VOICE_ENGINE, "omnivoice_native");
        if ("omnivoice_native".equals(engine)) nativeSection(r);
        else if ("omnivoice_server".equals(engine)) serverSection(r);
        else if ("hf_space".equals(engine)) spaceSection(r);
        else r.note(getString(R.string.voice_system_note));

        r.section(R.string.voice_reference);
        r.note(getString(R.string.voice_reference_intro));
        VoiceProfile vp = VoiceProfile.fromSettings(g.settings);
        String wavInfo = vp.hasReference() ? new File(vp.refWavPath).getName() + " · " + Ui.size(new File(vp.refWavPath).length()) : getString(R.string.not_set);
        r.row(R.drawable.ic_mic, getString(R.string.voice_ref_wav), wavInfo, null, v -> refWavMenu(vp));
        r.textRow(R.drawable.ic_edit, R.string.voice_ref_txt, Settings.REF_TEXT, "", true, R.string.voice_ref_txt_empty, null);
        r.row(R.drawable.ic_download, getString(R.string.voice_import_ref_txt), null, null, v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQ_REF_TXT));
        if (vp.hasReference() && vp.refText.trim().isEmpty()) r.note(getString(R.string.voice_ref_txt_missing));

        r.section(R.string.voice_style);
        r.textRow(R.drawable.ic_chat, R.string.voice_language, Settings.VOICE_LANGUAGE, "", false, R.string.voice_language_auto, null);
        r.textRow(R.drawable.ic_character, R.string.voice_instruct, Settings.VOICE_INSTRUCT, "", false, R.string.voice_instruct_empty, null);
        g.settings.put("voice_speed_pct", Math.round(g.settings.flt(Settings.VOICE_SPEED, 1.0f) * 100));
        r.sliderRow(R.drawable.ic_play, R.string.voice_speed, "voice_speed_pct", 100, 50, 200, "%", v -> g.settings.put(Settings.VOICE_SPEED, v / 100f));
        r.sliderRow(R.drawable.ic_refresh, R.string.voice_steps, Settings.VOICE_STEPS, 32, 4, 64, "", null);
        r.note(getString(R.string.voice_steps_note));

        r.section(R.string.voice_input);
        r.note(getString(SpeechInput.isAvailable(this) ? R.string.voice_stt_available : R.string.voice_stt_unavailable));
        r.textRow(R.drawable.ic_mic, R.string.voice_stt_language, Settings.STT_LANGUAGE, "", false, R.string.voice_language_auto, null);
        r.switchRow(R.drawable.ic_mic, R.string.voice_hands_free, R.string.voice_hands_free_desc, Settings.HANDS_FREE, false, on -> {
            if (on) {
                if (!g.settings.bool(Settings.ALLOW_BACKGROUND_LISTENING, false)) {
                    g.settings.put(Settings.HANDS_FREE, false);
                    Ui.confirm(this, R.string.voice_hands_free, getString(R.string.voice_hands_free_consent), () -> {
                        g.settings.put(Settings.ALLOW_BACKGROUND_LISTENING, true);
                        g.settings.put(Settings.HANDS_FREE, true);
                        startHandsFree();
                    });
                    build();
                } else {
                    startHandsFree();
                }
            } else {
                HandsFreeService.stop(this);
            }
        });
        r.textRow(R.drawable.ic_voice, R.string.voice_wake_words, Settings.HANDS_FREE_WAKE, "", false, R.string.voice_wake_words_none, null);
        r.note(getString(R.string.voice_wake_words_note));
        handsFreeStatus = r.row(R.drawable.ic_eye, getString(R.string.voice_hands_free_status), statusText(), null, null);
        handsFreeLevel = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        handsFreeLevel.setMax(100);
        handsFreeLevel.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent(this)));
        handsFreeLevel.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.surface_3)));
        LinearLayout.LayoutParams lvl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lvl.setMargins(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 4));
        r.parent().addView(handsFreeLevel, lvl);

        r.section(R.string.voice_license);
        r.note(getString(R.string.voice_license_note));
        Ui.tintAccent(this, box);
    }

    private TextView handsFreeStatus;
    private android.widget.ProgressBar handsFreeLevel;

    private String statusText() {
        if (!HandsFreeService.isRunning()) return getString(R.string.handsfree_stopped);
        String st = HandsFreeService.lastStatus();
        return st.isEmpty() ? getString(R.string.handsfree_listening) : st;
    }

    private final HandsFreeService.StateListener hfListener = new HandsFreeService.StateListener() {
        @Override
        public void onHandsFreeChanged(boolean running, String status) {
            runOnUiThread(() -> {
                if (handsFreeStatus != null) {
                    handsFreeStatus.setText(running ? (status == null || status.isEmpty() ? getString(R.string.handsfree_listening) : status)
                            : getString(R.string.handsfree_stopped));
                    handsFreeStatus.setVisibility(View.VISIBLE);
                }
            });
        }

        @Override
        public void onLevel(float level) {
            runOnUiThread(() -> {
                if (handsFreeLevel != null) handsFreeLevel.setProgress(Math.round(level * 100));
            });
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        HandsFreeService.addListener(hfListener);
    }

    @Override
    protected void onStop() {
        HandsFreeService.removeListener(hfListener);
        super.onStop();
    }

    private void startHandsFree() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            g.settings.put(Settings.HANDS_FREE, false);
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        HandsFreeService.start(this);
    }

    private void nativeSection(Rows r) {
        r.section(R.string.voice_native);
        r.note(getString(R.string.voice_native_intro));
        OmniVoiceNativeEngine ne = g.speech.nativeEngine();
        File[] libs = ne.libDir().listFiles((d, n) -> n.endsWith(".so"));
        List<String> names = new ArrayList<>();
        if (libs != null) for (File f : libs) names.add(f.getName());
        r.row(R.drawable.ic_download, getString(R.string.voice_import_libs),
                names.isEmpty() ? getString(R.string.voice_no_libs) : String.join(", ", names), null, v ->
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_LIB));
        if (!names.isEmpty()) {
            r.row(R.drawable.ic_delete, getString(R.string.voice_remove_libs), null, null, v -> Ui.confirm(this, R.string.voice_remove_libs,
                    getString(R.string.voice_remove_libs_confirm), () -> {
                        ne.close();
                        if (libs != null) for (File f : libs) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        build();
                    }));
        }
        String model = g.settings.str(Settings.OMNI_MODEL_PATH, "");
        File mf = model.isEmpty() ? null : new File(model);
        r.row(R.drawable.ic_files, getString(R.string.voice_model),
                mf != null && mf.isFile() ? mf.getName() + " · " + Ui.size(mf.length()) : getString(R.string.not_set), null, v ->
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MODEL));
        r.sliderRow(R.drawable.ic_settings, R.string.voice_threads, Settings.OMNI_THREADS,
                Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)), 1, Math.max(2, Runtime.getRuntime().availableProcessors()), "", v -> ne.close());
        String st = ne.status();
        if (!st.isEmpty()) r.note(st);
        if (!importProgress.isEmpty()) r.note(importProgress);
    }

    private void serverSection(Rows r) {
        r.section(R.string.voice_server);
        r.note(getString(R.string.voice_server_intro));
        r.textRow(R.drawable.ic_provider, R.string.voice_server_url, Settings.OMNI_SERVER_URL, "", false, R.string.not_set, v -> build());
        r.choiceRow(R.drawable.ic_settings, R.string.voice_server_mode, Settings.OMNI_SERVER_MODE, "clone_multipart",
                new String[]{getString(R.string.voice_server_mode_clone), getString(R.string.voice_server_mode_openai)},
                new String[]{"clone_multipart", "openai_speech"}, v -> build());
        r.textRow(R.drawable.ic_character, R.string.voice_server_voice, Settings.OMNI_SERVER_VOICE, "", false, R.string.voice_server_voice_empty, null);
        TextView health = r.note(getString(R.string.voice_server_health_unknown));
        r.button(R.string.voice_server_check, false, v -> {
            health.setText(R.string.provider_testing);
            g.io.execute(() -> {
                String s;
                try {
                    s = "✓ " + g.speech.serverEngine().health(new CancellationToken());
                } catch (Exception e) {
                    s = "✗ " + e.getMessage();
                }
                String m = s;
                runOnUiThread(() -> health.setText(m));
            });
        });
        if (g.speech.serverEngine().isLocal()) r.note(getString(R.string.voice_server_local));
        else r.note(getString(R.string.voice_server_remote_privacy));
    }

    /** The user's own Hugging Face Space (Gradio), e.g. Suvo75/tanu-voice. */
    private void spaceSection(Rows r) {
        r.section(R.string.voice_space);
        r.note(getString(R.string.voice_space_intro));
        r.textRow(R.drawable.ic_provider, R.string.voice_space_url, com.suvo.agent.voice.HfSpaceEngine.URL_KEY, "", false, R.string.not_set, v -> build());
        secretRow(r, R.string.voice_space_token, com.suvo.agent.voice.HfSpaceEngine.TOKEN_KEY);
        secretRow(r, R.string.voice_space_api_key, com.suvo.agent.voice.HfSpaceEngine.API_KEY_KEY);
        r.textRow(R.drawable.ic_tools, R.string.voice_space_api, com.suvo.agent.voice.HfSpaceEngine.API_NAME_KEY, "tts", false, R.string.not_set, null);
        String base = com.suvo.agent.voice.HfSpaceEngine.baseUrl(g.settings.str(com.suvo.agent.voice.HfSpaceEngine.URL_KEY, ""));
        if (!base.isEmpty()) r.note(base);
        r.note(getString(R.string.voice_space_quota));
    }

    /** A secret stored encrypted (shown only as "stored"). */
    private void secretRow(Rows r, int titleRes, String key) {
        boolean has = !g.settings.str(key, "").isEmpty();
        r.row(R.drawable.ic_lock, getString(titleRes), getString(has ? R.string.secret_stored : R.string.not_set), null,
                v -> Ui.prompt(this, titleRes, "", false, text -> {
                    String t = text.trim();
                    g.settings.put(key, t.isEmpty() ? "" : g.secrets.encrypt(t));
                    build();
                }));
    }

    private void test() {
        Ui.toast(this, R.string.voice_testing);
        String phrase = getString(R.string.voice_test_phrase, g.characters.active().name);
        g.io.execute(() -> {
            String err = g.speech.test(phrase);
            runOnUiThread(() -> {
                if (err != null) Ui.show(Ui.dialog(this).setTitle(R.string.voice_test).setMessage(err).setPositiveButton(R.string.close, null));
                build();
            });
        });
    }

    // ---- Reference audio ------------------------------------------------------------------------

    private File refDir() {
        File d = new File(getFilesDir(), "voice");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private void refWavMenu(VoiceProfile vp) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.voice_ref_import));
        items.add(getString(R.string.voice_ref_record, MAX_RECORD_S));
        if (vp.hasReference()) {
            items.add(getString(R.string.voice_ref_play));
            items.add(getString(R.string.voice_ref_clear));
        }
        Ui.show(Ui.dialog(this).setTitle(R.string.voice_ref_wav).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_REF_WAV);
            } else if (w == 1) {
                record();
            } else if (w == 2) {
                g.io.execute(() -> {
                    try {
                        Pcm p = vp.loadReference();
                        if (p != null) new com.suvo.agent.voice.AudioPlayback().play(p, null, new CancellationToken(), null);
                    } catch (Exception e) {
                        runOnUiThread(() -> Ui.toast(this, getString(R.string.error_prefix, e.getMessage())));
                    }
                });
            } else {
                g.settings.put(Settings.REF_WAV_PATH, "");
                build();
            }
        }));
    }

    /** Records up to MAX_RECORD_S seconds of mono 24 kHz audio into files/voice/ref.wav. */
    private void record() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        TextView msg = new TextView(this, null, 0, R.style.YF_Body);
        int p = Ui.dp(this, 20);
        msg.setPadding(p, Ui.dp(this, 8), p, 0);
        msg.setText(R.string.voice_recording);
        recording = true;
        android.app.AlertDialog dlg = Ui.show(Ui.dialog(this).setTitle(R.string.voice_ref_wav).setView(msg)
                .setPositiveButton(R.string.voice_stop_recording, (d, w) -> recording = false)
                .setCancelable(false));
        g.io.execute(() -> {
            final int rate = 24000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    dlg.dismiss();
                    Ui.toast(this, getString(R.string.error_prefix, e.getMessage()));
                });
                return;
            }
            short[] all = new short[rate * MAX_RECORD_S];
            int n = 0;
            short[] buf = new short[2048];
            try {
                rec.startRecording();
                long start = System.currentTimeMillis();
                while (recording && n < all.length) {
                    int got = rec.read(buf, 0, Math.min(buf.length, all.length - n));
                    if (got <= 0) break;
                    System.arraycopy(buf, 0, all, n, got);
                    n += got;
                    long secs = (System.currentTimeMillis() - start) / 1000;
                    runOnUiThread(() -> msg.setText(getString(R.string.voice_recording_secs, secs, MAX_RECORD_S)));
                }
            } finally {
                try {
                    rec.stop();
                } catch (IllegalStateException ignored) {
                }
                rec.release();
                recording = false;
            }
            float[] f = new float[n];
            for (int i = 0; i < n; i++) f[i] = all[i] / 32768f;
            Pcm pcm = new Pcm(f, rate);
            String result;
            if (pcm.durationSeconds() < 2.0) {
                result = getString(R.string.voice_recording_too_short);
            } else {
                try {
                    File out = new File(refDir(), "ref.wav");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(Wav.encode16(pcm.normalized()));
                    }
                    g.settings.put(Settings.REF_WAV_PATH, out.getAbsolutePath());
                    result = getString(R.string.voice_recording_saved, (int) Math.round(pcm.durationSeconds()));
                } catch (Exception e) {
                    result = getString(R.string.error_prefix, e.getMessage());
                }
            }
            String res = result;
            runOnUiThread(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                Ui.toast(this, res);
                build();
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Ui.toast(this, R.string.permission_granted);
        }
        build();
    }

    // ---- Imports --------------------------------------------------------------------------------

    private String displayName(Uri uri, String fallback) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0).replace('/', '_');
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    /** Streams a document to a file, updating the progress line. */
    private void copy(Uri uri, File dest) throws Exception {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(tmp)) {
            if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
            byte[] buf = new byte[1 << 16];
            long total = 0;
            long lastUi = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    importProgress = getString(R.string.voice_importing, dest.getName(), Ui.size(total));
                    runOnUiThread(this::build);
                }
            }
        }
        if (dest.exists() && !dest.delete()) throw new java.io.IOException("Could not replace " + dest.getName());
        if (!tmp.renameTo(dest)) throw new java.io.IOException("Could not save " + dest.getName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        g.io.execute(() -> {
            String msg;
            try {
                switch (requestCode) {
                    case REQ_LIB: {
                        OmniVoiceNativeEngine ne = g.speech.nativeEngine();
                        ne.close();
                        List<String> done = new ArrayList<>();
                        for (Uri u : uris) {
                            String name = displayName(u, "lib.so");
                            if (!name.endsWith(".so") || !name.matches("[A-Za-z0-9._+-]+")) {
                                done.add("✗ " + name);
                                continue;
                            }
                            copy(u, new File(ne.libDir(), name));
                            done.add("✓ " + name);
                        }
                        msg = String.join("\n", done);
                        break;
                    }
                    case REQ_MODEL: {
                        Uri u = uris.get(0);
                        String name = displayName(u, "omnivoice.gguf");
                        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
                            msg = getString(R.string.voice_model_not_gguf);
                            break;
                        }
                        File dir = new File(getFilesDir(), "omnivoice/models");
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        File dest = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                        g.speech.nativeEngine().close();
                        copy(u, dest);
                        String old = g.settings.str(Settings.OMNI_MODEL_PATH, "");
                        if (!old.isEmpty() && !old.equals(dest.getAbsolutePath()) && old.startsWith(dir.getAbsolutePath())) {
                            //noinspection ResultOfMethodCallIgnored
                            new File(old).delete();
                        }
                        g.settings.put(Settings.OMNI_MODEL_PATH, dest.getAbsolutePath());
                        msg = getString(R.string.voice_model_saved, dest.getName());
                        break;
                    }
                    case REQ_REF_WAV: {
                        Uri u = uris.get(0);
                        byte[] bytes;
                        try (InputStream in = getContentResolver().openInputStream(u)) {
                            if (in == null) throw new java.io.FileNotFoundException(String.valueOf(u));
                            bytes = Wav.readAll(in, 20 * 1024 * 1024);
                        }
                        Pcm p = Wav.decode(bytes); // validates: only PCM/float WAV is accepted
                        if (p.durationSeconds() > 30) throw new java.io.IOException(getString(R.string.voice_ref_too_long));
                        File out = new File(refDir(), "ref.wav");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(bytes);
                        }
                        g.settings.put(Settings.REF_WAV_PATH, out.getAbsolutePath());
                        msg = getString(R.string.voice_ref_saved, String.format(java.util.Locale.ROOT, "%.1f", p.durationSeconds()));
                        break;
                    }
                    case REQ_REF_TXT: {
                        try (InputStream in = getContentResolver().openInputStream(uris.get(0))) {
                            if (in == null) throw new java.io.FileNotFoundException();
                            String t = new String(Wav.readAll(in, 64 * 1024), java.nio.charset.StandardCharsets.UTF_8).trim();
                            g.settings.put(Settings.REF_TEXT, t);
                        }
                        msg = getString(R.string.saved);
                        break;
                    }
                    default:
                        return;
                }
            } catch (Exception e) {
                msg = getString(R.string.error_prefix, e.getMessage());
            }
            importProgress = "";
            String m = msg;
            runOnUiThread(() -> {
                Ui.show(Ui.dialog(this).setMessage(m).setPositiveButton(R.string.close, null));
                build();
            });
        });
    }
}
