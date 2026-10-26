package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.TextView;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;

/**
 * Base for all screens: applies the chosen UI language and theme, draws edge-to-edge behind the
 * system bars with correct insets, and wires the shared glass top bar.
 */
public abstract class BaseActivity extends Activity {

    protected AppGraph g;
    /** The scrolling content the top-bar glass blurs (null when the screen keeps its own layout). */
    protected View glassSource;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        g = AppGraph.get();
        Ui.edgeToEdge(getWindow());
    }

    /** Sets the content and pads the root for status/navigation bars and the keyboard. */
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        View root = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (root != null) {
            // Content scrolls under a floating glass top bar where the layout allows it.
            glassSource = Shell.install(this, root);
            if (glassSource == null) applyInsets(root);
        }
        View back = findViewById(R.id.top_back);
        if (back != null) back.setOnClickListener(v -> onBackPressed());
        Ui.tintAccent(this, findViewById(android.R.id.content));
    }

    protected void applyInsets(View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(l + left, t + top, r + right, b + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    protected void setTopBar(int titleRes) {
        setTopBar(getString(titleRes));
    }

    protected void setTopBar(CharSequence title) {
        TextView t = findViewById(R.id.top_title);
        if (t != null) t.setText(title);
    }

    protected ImageButton topAction(int iconRes, int descRes, View.OnClickListener l) {
        ViewGroup actions = findViewById(R.id.top_actions);
        if (actions == null) return null;
        ImageButton b = (ImageButton) getLayoutInflater().inflate(R.layout.top_action, actions, false);
        b.setImageResource(iconRes);
        b.setContentDescription(getString(descRes));
        b.setOnClickListener(l);
        actions.addView(b);
        Shell.cornerButton(b, glassSource);
        Fx.press(b);
        return b;
    }

    protected void open(Class<? extends Activity> cls) {
        startActivity(new Intent(this, cls));
    }

    private boolean reducedMotion() {
        return g != null && g.settings.bool(com.suvo.agent.data.Settings.REDUCED_MOTION, false);
    }

    /** Direction-aware navigation: forward pushes slide in from the right (360 ms, FastOutSlowIn). */
    @Override
    public void startActivity(Intent intent, Bundle options) {
        super.startActivity(intent, options);
        if (isOwn(intent)) {
            if (reducedMotion()) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            else overridePendingTransition(R.anim.yf_push_in, R.anim.yf_push_out);
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (reducedMotion()) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        else overridePendingTransition(R.anim.yf_pop_in, R.anim.yf_pop_out);
    }

    private boolean isOwn(Intent i) {
        return i != null && i.getComponent() != null && getPackageName().equals(i.getComponent().getPackageName());
    }

    /** Re-creates the screen after a language or theme change. */
    protected void restartApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
