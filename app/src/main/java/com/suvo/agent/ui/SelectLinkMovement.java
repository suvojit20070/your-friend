package com.suvo.agent.ui;

import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * Your Friend — Original project by Suvo.
 *
 * Text that can be selected (long-press, drag the handles, copy) and whose links still open on a
 * tap. Android's LinkMovementMethod turns selection off, and the selection movement method ignores
 * links; this keeps selection and handles a plain tap on a link itself.
 */
public final class SelectLinkMovement extends ArrowKeyMovementMethod {

    private static SelectLinkMovement instance;

    public static SelectLinkMovement get() {
        if (instance == null) instance = new SelectLinkMovement();
        return instance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP
                && Selection.getSelectionStart(buffer) == Selection.getSelectionEnd(buffer)) {
            Layout layout = widget.getLayout();
            if (layout != null) {
                int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
                int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);
                ClickableSpan[] links = buffer.getSpans(off, off, ClickableSpan.class);
                if (links.length > 0 && x <= layout.getLineRight(line)) {
                    links[0].onClick(widget);
                    return true;
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event);
    }
}
