package com.suvo.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 *
 * Shows the stack trace of the last crash so it can be copied or shared.
 *
 * It runs in its own process (":debug", see AndroidManifest.xml), and {@link App} skips all app
 * initialisation in that process. So even a crash inside App.onCreate or AppGraph cannot crash this
 * screen. The UI is built in code with only framework resources, for the same reason.
 */
public final class DebugActivity extends Activity {

    public static final String EXTRA_ERROR = "error";
    private static final String CRASH_FILE = "last_crash.txt";

    private String report = "";

    /** Called from the crash handler: saves the report and opens this screen in the :debug process. */
    static void handleCrash(Context ctx, Thread thread, Throwable throwable) {
        String trace = android.util.Log.getStackTraceString(throwable);
        String report = header(ctx) + "Thread: " + (thread != null ? thread.getName() : "?") + "\n\n" + trace;
        save(ctx, report);
        try {
            Intent intent = new Intent(ctx, DebugActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(EXTRA_ERROR, report);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
            // The report is saved; it is shown on the next launch of this screen.
        }
    }

    /**
     * Native crashes (C/C++, the GPU render thread) and freezes (ANR) never reach the Java crash
     * handler. Android 11+ records why the process died last time; on the next start this turns
     * such an exit into a report and opens this screen. Each exit is reported once.
     */
    static void reportLastExit(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
            if (am == null) return;
            java.util.List<android.app.ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 5);
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("debug", Context.MODE_PRIVATE);
            long seen = prefs.getLong("last_exit_seen", 0);
            android.app.ApplicationExitInfo hit = null;
            for (android.app.ApplicationExitInfo e : exits) {
                if (e.getTimestamp() <= seen) continue;
                if (e.getProcessName() != null && e.getProcessName().endsWith(":debug")) continue;
                int r = e.getReason();
                if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_ANR) {
                    hit = e;
                    break;
                }
            }
            if (!exits.isEmpty()) prefs.edit().putLong("last_exit_seen", exits.get(0).getTimestamp()).apply();
            if (hit == null) return;
            StringBuilder sb = new StringBuilder(header(ctx));
            sb.append("Previous run ended with: ")
                    .append(hit.getReason() == android.app.ApplicationExitInfo.REASON_ANR ? "ANR (app froze)" : "NATIVE CRASH")
                    .append("\nWhen: ").append(new java.util.Date(hit.getTimestamp()))
                    .append("\nProcess: ").append(hit.getProcessName())
                    .append("\nDescription: ").append(hit.getDescription())
                    .append("\nSignal/status: ").append(hit.getStatus())
                    .append("\n\n");
            String trace = readTrace(hit);
            if (!trace.isEmpty()) sb.append(trace);
            else sb.append("(Android kept no trace for this exit.)");
            String report = sb.toString();
            save(ctx, report);
            Intent intent = new Intent(ctx, DebugActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(EXTRA_ERROR, report);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * ANR traces are text. Native tombstones are a binary (protobuf) record; the readable parts —
     * signal, abort message, library and function names of the backtrace — are pulled out as text.
     */
    private static String readTrace(android.app.ApplicationExitInfo e) {
        if (Build.VERSION.SDK_INT < 30) return "";
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4 * 1024 * 1024) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            if (e.getReason() == android.app.ApplicationExitInfo.REASON_ANR) {
                String t = new String(data, StandardCharsets.UTF_8);
                return t.length() > 150_000 ? t.substring(0, 150_000) + "\n…" : t;
            }
            StringBuilder out = new StringBuilder("Readable parts of the native crash record:\n");
            StringBuilder cur = new StringBuilder();
            int lines = 0;
            for (byte b : data) {
                int c = b & 0xff;
                if (c >= 0x20 && c < 0x7f) {
                    cur.append((char) c);
                } else {
                    if (cur.length() >= 6 && lines < 1500) {
                        out.append(cur).append('\n');
                        lines++;
                    }
                    cur.setLength(0);
                }
            }
            if (cur.length() >= 6) out.append(cur).append('\n');
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String header(Context ctx) {
        String version = "?";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        return "Your Friend " + version + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "ABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + "\n"
                + "Time: " + new java.util.Date() + "\n";
    }

    private static void save(Context ctx, String report) {
        try (OutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE))) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static String load(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_FILE);
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_ERROR);
        report = extra != null && !extra.isEmpty() ? extra : load(this);
        if (report.isEmpty()) report = "No crash report saved.";

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 24));
        root.setPadding(pad, pad + (int) (24 * d), pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("Your Friend crashed");
        title.setTextColor(Color.rgb(255, 120, 130));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Copy this report and send it to the developer. Check it first: error messages can contain text from the app.");
        hint.setTextColor(Color.rgb(190, 190, 205));
        hint.setTextSize(13);
        hint.setPadding(0, (int) (6 * d), 0, (int) (12 * d));
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(button("Copy", v -> copy()));
        buttons.addView(button("Share", v -> share()));
        buttons.addView(button("Restart", v -> restart()));
        buttons.addView(button("Close", v -> close()));
        root.addView(buttons);

        TextView trace = new TextView(this);
        trace.setText(report);
        trace.setTextColor(Color.rgb(230, 230, 240));
        trace.setTextSize(12);
        trace.setTypeface(Typeface.MONOSPACE);
        trace.setTextIsSelectable(true);
        trace.setPadding(0, (int) (12 * d), pad, pad);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(trace);
        ScrollView vs = new ScrollView(this);
        vs.addView(hs);
        root.addView(vs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd((int) (6 * getResources().getDisplayMetrics().density));
        b.setLayoutParams(lp);
        return b;
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Your Friend crash", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Your Friend crash report");
        send.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "Share crash report"));
    }

    private void restart() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        close();
    }

    private void close() {
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
    }
}
