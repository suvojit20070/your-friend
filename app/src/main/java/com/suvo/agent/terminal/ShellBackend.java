package com.suvo.agent.terminal;

import com.suvo.agent.core.util.CancellationToken;

/** A place where shell commands can run. */
public interface ShellBackend {

    String id();

    /** Null when usable, otherwise why not (shown to the user and the model). */
    String unavailableReason();

    ExecResult run(String command, String stdin, long timeoutMs, CancellationToken cancel);
}
