package com.suvo.agent.core.tools;

import com.suvo.agent.core.util.CancellationToken;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * Things only one agent can use at a time: the phone's screen, the camera, the agent's browser.
 * When team members work side by side, the first one to use such a thing holds it — for its whole
 * run of steps, not for one call, so another member's taps never land between its own. It lets go
 * when its task ends, or after it has not used the thing for a while. Anyone else who needs it
 * waits a little, and is then told who has it (so it can do other work first) instead of running.
 *
 * Owners are task ids: every agent run has its own, and calls a script makes for it carry the
 * same one. Pure Java.
 */
public final class ResourceGuard {

    /** The app's one guard (set on every tool executor). */
    public static final ResourceGuard SHARED = new ResourceGuard();

    public static final String SCREEN = "screen", CAMERA = "camera", BROWSER = "browser";

    /** A holder that has not used the thing for this long gives it up. */
    public volatile long idleReleaseMs = 30_000;
    /** How long a call waits for the thing before it is refused. */
    public volatile long waitMs = 40_000;

    /** Told when somebody starts or stops waiting (for the team view). */
    public interface Listener {
        void onWaiting(String owner, String resource, String holderName, boolean waiting);
    }

    public volatile Listener listener;

    private static final class Lease {
        String owner = "";
        long lastUsed;
        int active;
    }

    private final Map<String, String> toolResource = new HashMap<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Object lock = new Object();

    public ResourceGuard() {
        for (String t : new String[]{"screen_operation_accessibility", "screen_operation_shell", "screenshot", "launch_app", "open_uri"}) {
            toolResource.put(t, SCREEN);
        }
        toolResource.put("camera_capture", CAMERA);
        toolResource.put("browser", BROWSER);
        toolResource.put("web_session", BROWSER);
    }

    /** The exclusive thing this tool uses, or null. */
    public String resourceOf(String tool) {
        return toolResource.get(tool);
    }

    /** The display name of an owner ("Ozzy"), used in messages to the others. */
    public void name(String owner, String name) {
        if (owner == null) return;
        synchronized (lock) {
            names.put(owner, name == null ? "" : name);
            if (names.size() > 200) names.remove(names.keySet().iterator().next());
        }
    }

    private String nameOf(String owner) {
        String n = names.get(owner);
        return n == null || n.isEmpty() ? "another agent" : n;
    }

    /**
     * Takes {@code resource} for {@code owner}, waiting up to {@link #waitMs} for the current
     * holder. @return null when taken, else what to tell the caller (it did not get it).
     */
    public String acquire(String resource, String owner, CancellationToken cancel) {
        if (resource == null || owner == null) return null;
        long deadline = System.currentTimeMillis() + waitMs;
        boolean told = false;
        String holder;
        try {
            while (true) {
                synchronized (lock) {
                    Lease l = leases.get(resource);
                    long now = System.currentTimeMillis();
                    if (l == null) {
                        l = new Lease();
                        leases.put(resource, l);
                    }
                    boolean free = l.owner.isEmpty() || l.owner.equals(owner) || (l.active == 0 && now - l.lastUsed > idleReleaseMs);
                    if (free) {
                        if (!l.owner.equals(owner)) l.active = 0;
                        l.owner = owner;
                        l.active++;
                        l.lastUsed = now;
                        return null;
                    }
                    holder = nameOf(l.owner);
                }
                if (!told) {
                    told = true;
                    tell(owner, resource, holder, true);
                }
                if (System.currentTimeMillis() > deadline || (cancel != null && cancel.isCancelled())) break;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (told) tell(owner, resource, "", false);
        }
        return "The " + label(resource) + " is being used by " + holder + " right now, and only one agent can use it at a time. "
                + "This call was NOT run. Do the parts of your task that do not need the " + label(resource)
                + " first; if nothing else is left, use the wait tool (30–60 s) and try again. Do not try another way around it.";
    }

    /** One call of {@code owner} on {@code resource} ended (the owner keeps holding it for a while). */
    public void done(String resource, String owner) {
        if (resource == null || owner == null) return;
        synchronized (lock) {
            Lease l = leases.get(resource);
            if (l != null && l.owner.equals(owner)) {
                l.active = Math.max(0, l.active - 1);
                l.lastUsed = System.currentTimeMillis();
            }
        }
    }

    /** The owner's task ended: everything it held is free at once. */
    public void releaseAll(String owner) {
        if (owner == null) return;
        synchronized (lock) {
            for (Lease l : leases.values()) {
                if (l.owner.equals(owner)) {
                    l.owner = "";
                    l.active = 0;
                }
            }
            names.remove(owner);
        }
    }

    /** Who holds what right now: resource → holder's name. */
    public Map<String, String> holders() {
        Map<String, String> out = new LinkedHashMap<>();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Lease> e : leases.entrySet()) {
                Lease l = e.getValue();
                if (!l.owner.isEmpty() && (l.active > 0 || now - l.lastUsed <= idleReleaseMs)) out.put(e.getKey(), nameOf(l.owner));
            }
        }
        return out;
    }

    /** The thing {@code owner} holds right now ("screen"), or "". */
    public String heldBy(String owner) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Lease> e : leases.entrySet()) {
                Lease l = e.getValue();
                if (l.owner.equals(owner) && (l.active > 0 || now - l.lastUsed <= idleReleaseMs)) return e.getKey();
            }
        }
        return "";
    }

    private void tell(String owner, String resource, String holder, boolean waiting) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onWaiting(owner, resource, holder, waiting);
        } catch (RuntimeException ignored) {
        }
    }

    private static String label(String resource) {
        return SCREEN.equals(resource) ? "phone screen" : resource;
    }
}
