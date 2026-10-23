package com.suvo.agent.core.llm;

/** Classified provider failure so callers can retry, compact context, or report cleanly. */
public final class LlmException extends Exception {

    public enum Kind {
        NETWORK, TIMEOUT, AUTH, RATE_LIMIT, CONTEXT_LENGTH, BAD_REQUEST, NOT_FOUND, SERVER,
        MALFORMED_RESPONSE, CANCELLED, UNSUPPORTED, NOT_CONFIGURED
    }

    public final Kind kind;
    public final int httpStatus;
    public final long retryAfterMs;

    public LlmException(Kind kind, String message) {
        this(kind, message, 0, 0, null);
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, 0, cause);
    }

    public LlmException(Kind kind, String message, int httpStatus, long retryAfterMs, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
    }

    /** Transient errors that are worth retrying with backoff. */
    public boolean isRetryable() {
        return kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.RATE_LIMIT || kind == Kind.SERVER;
    }

    /** A short, user-facing description (no secrets, no raw bodies). */
    public String userMessage() {
        switch (kind) {
            case NETWORK:
                return "Network error while contacting the provider";
            case TIMEOUT:
                return "The provider did not respond in time";
            case AUTH:
                return "The provider rejected the API key (HTTP " + httpStatus + ")";
            case RATE_LIMIT:
                return "Rate limited by the provider";
            case CONTEXT_LENGTH:
                return "The conversation is too long for this model";
            case BAD_REQUEST:
                return "The provider rejected the request: " + getMessage();
            case NOT_FOUND:
                return "Endpoint or model not found (HTTP 404). Check the base URL and model ID";
            case SERVER:
                return "Provider server error (HTTP " + httpStatus + ")";
            case MALFORMED_RESPONSE:
                return "The provider returned a response that could not be parsed";
            case CANCELLED:
                return "Cancelled";
            case UNSUPPORTED:
                return "Not supported by this provider: " + getMessage();
            case NOT_CONFIGURED:
            default:
                return getMessage();
        }
    }
}
