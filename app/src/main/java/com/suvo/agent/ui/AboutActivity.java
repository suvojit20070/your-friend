package com.suvo.agent.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.BuildConfig;
import com.suvo.agent.R;
import com.suvo.agent.nativebridge.NativeAudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Version, author credit, licenses and third-party notices. */
public final class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_about);
        LinearLayout box = findViewById(R.id.rows);
        Rows r = new Rows(this, box);
        TextView title = new TextView(this, null, 0, R.style.YF_Title);
        title.setText(R.string.app_name);
        title.setTextSize(28f);
        title.setPadding(0, Ui.dp(this, 16), 0, 0);
        box.addView(title);
        r.note(getString(R.string.about_credit));
        r.row(R.drawable.ic_info, getString(R.string.about_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\n" + getString(NativeAudio.isLoaded() ? R.string.about_native_ok : R.string.about_native_missing), null, null);
        r.section(R.string.about_license);
        r.note(getString(R.string.about_license_text));
        r.section(R.string.about_notices);
        TextView notices = new TextView(this, null, 0, R.style.YF_Mono);
        notices.setBackgroundResource(R.drawable.code_bg);
        int p = Ui.dp(this, 12);
        notices.setPadding(p, p, p, p);
        notices.setText(readAsset("NOTICE.txt") + "\n\n" + readAsset("THIRD_PARTY_NOTICES.txt"));
        box.addView(notices);
        r.button(R.string.about_source, false, v -> PermissionFlows.openUrl(this, getString(R.string.project_repo)));
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
