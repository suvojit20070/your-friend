package com.suvo.agent.core.llm;

/**
 * Incremental Server-Sent Events parser. Feed it lines (without trailing newline); it emits the
 * accumulated "data" payload of each event when a blank line ends the event.
 */
public final class SseParser {

    public interface EventHandler {
        /** @return false to stop parsing (e.g. on [DONE]) */
        boolean onData(String data);
    }

    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private boolean done;

    /** @return false once the stream signalled completion. */
    public boolean feedLine(String line, EventHandler handler) {
        if (done) return false;
        if (line == null) return true;
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (line.isEmpty()) {
            return dispatch(handler);
        }
        if (line.startsWith(":")) return true; // comment / keep-alive
        String field;
        String value;
        int colon = line.indexOf(':');
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
        }
        if ("data".equals(field)) {
            if (hasData) data.append('\n');
            data.append(value);
            hasData = true;
            // Some servers omit the blank line between events; a complete JSON object or [DONE]
            // on its own line is dispatched immediately.
            String trimmed = value.trim();
            if ("[DONE]".equals(trimmed) || looksComplete(data)) {
                return dispatch(handler);
            }
        }
        return true;
    }

    /** Flush any pending event at end of stream. */
    public void finish(EventHandler handler) {
        if (!done) dispatch(handler);
    }

    public boolean isDone() {
        return done;
    }

    private boolean dispatch(EventHandler handler) {
        if (!hasData) return true;
        String payload = data.toString();
        data.setLength(0);
        hasData = false;
        if ("[DONE]".equals(payload.trim())) {
            done = true;
            return false;
        }
        boolean cont = handler.onData(payload);
        if (!cont) done = true;
        return cont;
    }

    /** True when the buffer holds one balanced JSON object (string-aware brace counting). */
    static boolean looksComplete(CharSequence s) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') {
                depth++;
                started = true;
            } else if (c == '}') depth--;
            else if (!started && !Character.isWhitespace(c)) return false;
        }
        return started && depth == 0 && !inStr;
    }
}
