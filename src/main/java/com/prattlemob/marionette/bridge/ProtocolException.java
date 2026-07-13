package com.prattlemob.marionette.bridge;

/**
 * A malformed or invalid agent message. Thrown on the network thread and
 * answered with a protocol error frame; never crosses to the tick thread.
 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
