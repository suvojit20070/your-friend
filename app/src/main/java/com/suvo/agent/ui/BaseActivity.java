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
    private boolean retintPending;

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
        View content = findViewById(android.R.id.content);
        Ui.tintAccent(this, content);
        // Views added later (list rows, cards built in code) get the theme and the press too —
        // right before the frame that would show them, so they never flash in the old colours.
        content.getViewTreeObserver().addOnGlobalLayoutListener(() -> retintPending = true);
        content.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (retintPending && !isFinishing()) {
                retintPending = false;
                Ui.tintAccent(this, content);
            }
            return true;
        });
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

    private final java.util.List<Object[]> actions = new java.util.ArrayList<>();
    private ImageButton actionButton;

    /**
     * Adds a top-bar action. The top bar has exactly two slots (back on the left, one action on the
     * right): a second action turns the right button into "more", which opens a glass popover
     * with all of them.
     */
    protected ImageButton topAction(int iconRes, int descRes, View.OnClickListener l) {
        ViewGroup bar = findViewById(R.id.top_actions);
        if (bar == null) return null;
        actions.add(new Object[]{iconRes, descRes, l});
        if (actionButton == null) {
            actionButton = (ImageButton) getLayoutInflater().inflate(R.layout.top_action, bar, false);
            bar.addView(actionButton);
            Shell.cornerButton(actionButton, glassSource);
            Fx.press(actionButton);
        }
        if (actions.size() == 1) {
            actionButton.setImageResource(iconRes);
            actionButton.setContentDescription(getString(descRes));
            actionButton.setOnClickListener(l);
        } else {
            actionButton.setImageResource(R.drawable.ic_more);
            actionButton.setContentDescription(getString(R.string.more));
            actionButton.setOnClickListener(v -> {
                java.util.List<Fx.Item> items = new java.util.ArrayList<>();
                for (Object[] o : actions) items.add(new Fx.Item(getString((Integer) o[1]), (Integer) o[0], false));
                Fx.menu(this, actionButton, null, items, which -> ((View.OnClickListener) actions.get(which)[2]).onClick(actionButton));
            });
        }
        return actionButton;
    }

    /** Back closes the newest dialog / popover / sheet first. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && Overlay.anyOpen(this)) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) Overlay.back(this);
            return true;
        }
        return super.dispatchKeyEvent(e);
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
