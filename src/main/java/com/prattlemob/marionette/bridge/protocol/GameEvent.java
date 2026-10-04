package com.prattlemob.marionette.bridge.protocol;

import com.google.gson.JsonObject;

/**
 * One recorded one-shot event before per-connection sequencing. See the
 * Events section of protocol/v1.md. {@code fields} holds the kind's own
 * fields; unknown values are JSON null, never omitted.
 */
public record GameEvent(String kind, String worldSession, long tick, Basis basis, JsonObject fields) {
    /** How the client knows the event happened. */
    public enum Basis {
        /** Reported to this client by a server packet. */
        SERVER("server"),
        /** Predicted locally; the server may reject or correct it. */
        CLIENT("client");

        private final String wire;

        Basis(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    public GameEvent {
        fields = fields.deepCopy();
    }
}
