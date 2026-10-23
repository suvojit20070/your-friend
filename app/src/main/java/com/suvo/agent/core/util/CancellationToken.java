package com.suvo.agent.core.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation shared between the agent loop, LLM streaming and tools.
 * Listeners run immediately on cancel so blocking I/O (HTTP streams, processes) can be torn down.
 */
public final class CancellationToken {

    private volatile boolean cancelled;
    private volatile String reason = "";
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CancellationToken parent;

    public CancellationToken() {
        this(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
        if (parent != null) {
            parent.onCancel(() -> cancel(parent.reason()));
        }
    }

    /** A child token is cancelled when its parent is, but can also be cancelled on its own. */
    public CancellationToken child() {
        return new CancellationToken(this);
    }

    public boolean isCancelled() {
        return cancelled || (parent != null && parent.isCancelled());
    }

    public String reason() {
        return reason;
    }

    public void cancel(String why) {
        if (cancelled) return;
        reason = why == null ? "" : why;
        cancelled = true;
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // Listener failures must not prevent other listeners from running.
            }
        }
    }

    /** Registers a callback; if already cancelled it runs immediately. */
    public void onCancel(Runnable r) {
        listeners.add(r);
        if (isCancelled()) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    public void throwIfCancelled() throws CancelledException {
        if (isCancelled()) throw new CancelledException(reason);
    }

    public static final class CancelledException extends Exception {
        public CancelledException(String reason) {
            super(reason == null || reason.isEmpty() ? "Cancelled" : "Cancelled: " + reason);
        }
    }
}
