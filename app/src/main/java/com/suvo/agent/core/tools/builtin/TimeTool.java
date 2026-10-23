package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simple built-in: current date/time. No permission needed. Good smoke-test tool. */
public class TimeTool implements Tool {

    @Override public String name() { return "get_time"; }

    @Override public ToolSchema schema() {
        return new ToolSchema(name(),
                "Get the current date and time on the device.",
                ToolSchema.emptyParams(), "low");
    }

    @Override public boolean isAvailable() { return true; }

    @Override public String run(JSONObject args) {
        SimpleDateFormat f = new SimpleDateFormat("EEE dd MMM yyyy, HH:mm:ss z", Locale.getDefault());
        return f.format(new Date());
    }
}
