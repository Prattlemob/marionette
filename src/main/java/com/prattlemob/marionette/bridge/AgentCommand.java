package com.prattlemob.marionette.bridge;

/**
 * A validated protocol v0 message from the agent. Parsed on the network
 * thread, applied on the client tick thread. See protocol/v0-draft.md.
 */
public sealed interface AgentCommand {
    /** Handshake opener; must be the first message on a connection. */
    record Hello(int version) implements AgentCommand {}

    /** Partial set-and-hold update; a null field means "unchanged". */
    record InputUpdate(Boolean forward, Boolean back, Boolean left, Boolean right,
                       Boolean jump, Boolean sneak, Boolean sprint) implements AgentCommand {}

    /** Raw instant camera set (smoothing arrives in M3.2). */
    record Look(float yaw, float pitch) implements AgentCommand {}

    /** Release every held control immediately. */
    record Release() implements AgentCommand {}
}
