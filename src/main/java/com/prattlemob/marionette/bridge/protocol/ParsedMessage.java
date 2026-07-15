package com.prattlemob.marionette.bridge.protocol;

import java.util.List;

import com.google.gson.JsonPrimitive;

/** A validated protocol v1 message from the agent. See protocol/v1.md. */
public sealed interface ParsedMessage permits ParsedMessage.Hello, AgentCommand {
    /**
     * Handshake opener; must be the first message on a connection.
     * {@code role} is already defaulted to "controller" when absent;
     * {@code id} is the envelope id or null.
     */
    record Hello(List<Integer> versions, String role, JsonPrimitive id) implements ParsedMessage {}
}
