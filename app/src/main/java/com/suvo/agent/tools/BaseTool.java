package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSpec;

/** Common base for built-in tools. */
public abstract class BaseTool implements Tool {

    protected final AppGraph g;
    private final ToolSpec spec;

    protected BaseTool(AppGraph g, ToolSpec spec) {
        this.g = g;
        this.spec = spec;
    }

    @Override
    public final ToolSpec spec() {
        return spec;
    }
}
