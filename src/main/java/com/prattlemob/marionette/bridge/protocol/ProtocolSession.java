package com.prattlemob.marionette.bridge.protocol;

import java.util.List;

import com.google.gson.JsonPrimitive;

/**
 * Per-connection protocol v1 state machine: AWAITING_HELLO → ACTIVE →
 * CLOSED. Transport-neutral: consumes raw text frames, returns
 * instructions for the transport to execute in order. Confined to the
 * single network thread; never touches game state.
 */
public final class ProtocolSession {
    public static final int PROTOCOL_VERSION = 1;

    /** One transport instruction; execute in returned order. */
    public sealed interface Action {
        record Send(String json) implements Action {}
        record Enqueue(AgentCommand command) implements Action {}
        record Close(int code, String reason) implements Action {}
    }

    private enum State { AWAITING_HELLO, ACTIVE, CLOSED }

    private final String modVersion;
    private State state = State.AWAITING_HELLO;

    public ProtocolSession(String modVersion) {
        this.modVersion = modVersion;
    }

    /** True once the hello handshake completed; the connection counts as the controller. */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public List<Action> onFrame(String text) {
        if (state == State.CLOSED) {
            return List.of(); // the transport is already closing this connection
        }
        ParsedMessage message;
        try {
            message = MessageParser.parse(text);
        } catch (ProtocolError e) {
            return errorActions(e.code(), e.getMessage(), e.id(), text);
        }
        return switch (message) {
            case ParsedMessage.Hello hello -> onHello(hello, text);
            case AgentCommand command -> onCommand(command, text);
        };
    }

    private List<Action> onHello(ParsedMessage.Hello hello, String text) {
        if (state == State.ACTIVE) {
            return errorActions(ErrorCode.UNEXPECTED_HELLO, "hello already completed",
                    hello.id(), text);
        }
        // Single supported version; "highest shared" degenerates to contains().
        if (!hello.versions().contains(PROTOCOL_VERSION)) {
            state = State.CLOSED;
            return List.of(
                    new Action.Send(Messages.unsupportedVersionError(
                            List.of(PROTOCOL_VERSION), hello.id(), text)),
                    new Action.Close(1002, ErrorCode.UNSUPPORTED_VERSION.wire()));
        }
        if (!"controller".equals(hello.role())) {
            state = State.CLOSED;
            return List.of(
                    new Action.Send(Messages.error(ErrorCode.UNSUPPORTED_ROLE,
                            "unsupported role: " + hello.role(), hello.id(), text)),
                    new Action.Close(1002, ErrorCode.UNSUPPORTED_ROLE.wire()));
        }
        state = State.ACTIVE;
        return List.of(new Action.Send(
                Messages.helloReply(PROTOCOL_VERSION, modVersion, hello.id())));
    }

    private List<Action> onCommand(AgentCommand command, String text) {
        if (state != State.ACTIVE) {
            return errorActions(ErrorCode.HELLO_REQUIRED, "hello required first", null, text);
        }
        return List.of(new Action.Enqueue(command));
    }

    /** Every pre-hello error is fatal (close 1002); after that, only inherently fatal codes close. */
    private List<Action> errorActions(ErrorCode code, String message, JsonPrimitive id, String text) {
        Action.Send send = new Action.Send(Messages.error(code, message, id, text));
        if (state == State.AWAITING_HELLO || code.fatal()) {
            state = State.CLOSED;
            int closeCode = code.fatal() ? code.closeCode() : 1002;
            return List.of(send, new Action.Close(closeCode, code.wire()));
        }
        return List.of(send);
    }
}
