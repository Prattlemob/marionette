package com.prattlemob.marionette.bridge.protocol;

import com.google.gson.JsonPrimitive;

/**
 * A malformed or invalid agent message. Thrown on the network thread by
 * {@link MessageParser} and turned into an error frame by
 * {@link ProtocolSession}; never crosses to the tick thread.
 */
public class ProtocolError extends RuntimeException {
    private final ErrorCode code;
    private final JsonPrimitive id;
    private final String reason;

    public ProtocolError(ErrorCode code, String message) {
        this(code, message, null);
    }

    /** An inventory rejection with its wire {@code reason} (protocol/v1.md, Rejection reasons). */
    public static ProtocolError withReason(ErrorCode code, String reason, String message) {
        return new ProtocolError(code, message, null, reason);
    }

    public ProtocolError(ErrorCode code, String message, JsonPrimitive id) {
        this(code, message, id, null);
    }

    private ProtocolError(ErrorCode code, String message, JsonPrimitive id, String reason) {
        super(message);
        this.code = code;
        this.id = id;
        this.reason = reason;
    }

    public ErrorCode code() {
        return code;
    }

    /** The offending message's envelope id, when one was readable; else null. */
    public JsonPrimitive id() {
        return id;
    }

    /** Machine-readable rejection reason, or null. */
    public String reason() {
        return reason;
    }
}
