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

    public ProtocolError(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ProtocolError(ErrorCode code, String message, JsonPrimitive id) {
        super(message);
        this.code = code;
        this.id = id;
    }

    public ErrorCode code() {
        return code;
    }

    /** The offending message's envelope id, when one was readable; else null. */
    public JsonPrimitive id() {
        return id;
    }
}
