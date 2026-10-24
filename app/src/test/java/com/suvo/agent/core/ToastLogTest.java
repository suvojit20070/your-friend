package com.suvo.agent.core;

import com.suvo.agent.core.util.ToastLog;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Toasts shown during a screen action are reported once, with their timing. */
public class ToastLogTest {

    @Test
    public void reportsEachToastOnceAndDropsRepeats() {
        ToastLog.clear();
        long t0 = 1_000_000L;
        ToastLog.record(ToastLog.TOAST, "com.whatsapp", "Message sent", t0 + 300);
        ToastLog.record(ToastLog.TOAST, "com.whatsapp", "Message  sent ", t0 + 500); // duplicate event
        List<ToastLog.Entry> got = ToastLog.takeUnreported(t0, t0 + 900);
        assertEquals(1, got.size());
        String note = ToastLog.describe(got, t0);
        assertTrue(note, note.contains("toast \"Message sent\"") && note.contains("com.whatsapp") && note.contains("0.3 s after"));
        assertTrue(ToastLog.takeUnreported(t0, t0 + 1000).isEmpty());
        assertNull(ToastLog.describe(ToastLog.takeUnreported(t0, t0 + 1000), t0));
    }

    @Test
    public void lateToastFromEarlierActionIsMarked() {
        ToastLog.clear();
        long t0 = 5_000_000L;
        ToastLog.record(ToastLog.SNACKBAR, "app", "No internet", t0 - 2000);
        String note = ToastLog.describe(ToastLog.takeUnreported(t0 - 20_000, t0 + 100), t0);
        assertTrue(note, note.contains("before this action"));
    }

    @Test
    public void oldEntriesExpireAndTextIsCapped() {
        ToastLog.clear();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append("long text ");
        ToastLog.record(ToastLog.TOAST, "a", big, 0);
        assertTrue(ToastLog.recent(10_000, 1).get(0).text.length() <= 301);
        assertTrue(ToastLog.takeUnreported(0, 10 * 60_000L).isEmpty());
    }
}
