package com.suvo.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Your Friend — Original project by Suvo.
 *
 * Live tree of who is doing what: the boss agent is the root of each task and every employee the
 * boss delegates to becomes a child node. Each node shows its current action (the tool it is
 * running), its status and, when done, a short result. The Live Agent screen draws this tree.
 * Everything here is what actually happened — nodes are only created and updated by the runtime.
 */
public final class AgentTree {

    public enum State {RUNNING, WAITING, DONE, FAILED, STOPPED}

    public static final class Node {
        public final long id;
        public final long parentId;
        public final String agent;   // display name
        public final String role;    // "Boss" or the employee's role
        public final String task;
        public final long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public volatile State state = State.RUNNING;
        public volatile String action = "";
        public volatile String result = "";
        public volatile int toolCalls;

        Node(long id, long parentId, String agent, String role, String task) {
            this.id = id;
            this.parentId = parentId;
            this.agent = agent;
            this.role = role;
            this.task = task;
        }
    }

    public interface Listener {
        void onTreeChanged();
    }

    private static final AtomicLong IDS = new AtomicLong();
    private static final List<Node> NODES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile long rootId = -1;

    private AgentTree() {
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void changed() {
        for (Listener l : LISTENERS) {
            try {
                l.onTreeChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** A new boss task replaces the previous tree. */
    public static Node startRoot(String agent, String task) {
        NODES.clear();
        Node n = new Node(IDS.incrementAndGet(), -1, agent, "Boss", task);
        rootId = n.id;
        NODES.add(n);
        changed();
        return n;
    }

    public static Node startChild(long parentId, String agent, String role, String task) {
        Node n = new Node(IDS.incrementAndGet(), parentId, agent, role, task);
        NODES.add(n);
        changed();
        return n;
    }

    public static void action(Node n, String action) {
        if (n == null) return;
        n.action = action == null ? "" : action;
        changed();
    }

    public static void toolCall(Node n, String tool) {
        if (n == null) return;
        n.toolCalls++;
        n.action = tool;
        n.state = State.RUNNING;
        changed();
    }

    public static void state(Node n, State s) {
        if (n == null) return;
        n.state = s;
        changed();
    }

    public static void finish(Node n, State s, String result) {
        if (n == null) return;
        n.state = s;
        n.finishedAt = System.currentTimeMillis();
        n.action = "";
        n.result = result == null ? "" : result;
        changed();
    }

    public static Node root() {
        long id = rootId;
        for (Node n : NODES) if (n.id == id) return n;
        return null;
    }

    public static List<Node> childrenOf(long parentId) {
        List<Node> out = new ArrayList<>();
        for (Node n : NODES) if (n.parentId == parentId) out.add(n);
        return out;
    }

    public static List<Node> all() {
        return new ArrayList<>(NODES);
    }
}
