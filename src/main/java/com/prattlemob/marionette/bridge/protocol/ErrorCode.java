package com.prattlemob.marionette.bridge.protocol;

/**
 * Protocol v1 error codes (see protocol/v1.md). Inherently fatal codes
 * carry the WebSocket close code sent after the error frame; the others
 * are fatal only when they occur during the handshake (the session
 * decides that, not the code).
 */
public enum ErrorCode {
    INVALID_JSON("invalid_json"),
    UNKNOWN_TYPE("unknown_type"),
    INVALID_FIELD("invalid_field"),
    UNEXPECTED_HELLO("unexpected_hello"),
    HELLO_REQUIRED("hello_required", 1002),
    UNSUPPORTED_VERSION("unsupported_version", 1002),
    UNSUPPORTED_ROLE("unsupported_role", 1002),
    CONTROLLER_ATTACHED("controller_attached", 1013);

    private final String wire;
    private final int closeCode;

    ErrorCode(String wire) {
        this(wire, 0);
    }

    ErrorCode(String wire, int closeCode) {
        this.wire = wire;
        this.closeCode = closeCode;
    }

    /** The machine-readable string that goes on the wire. */
    public String wire() {
        return wire;
    }

    /** WebSocket close code for inherently fatal codes; 0 otherwise. */
    public int closeCode() {
        return closeCode;
    }

    public boolean fatal() {
        return closeCode != 0;
    }
}
