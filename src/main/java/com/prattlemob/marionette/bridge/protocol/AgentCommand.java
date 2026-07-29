package com.prattlemob.marionette.bridge.protocol;

import java.util.Set;

import com.prattlemob.marionette.control.TapControl;

/**
 * A validated command for the tick thread. Parsed on the network thread,
 * applied on the client tick thread. See protocol/v1.md.
 */
public sealed interface AgentCommand extends ParsedMessage {
    /** Partial set-and-hold update; a null field means "unchanged".
     *  {@code taps} is never null — empty means no one-shot presses. */
    record InputUpdate(Boolean forward, Boolean back, Boolean left, Boolean right,
                       Boolean jump, Boolean sneak, Boolean sprint,
                       Set<TapControl> taps) implements AgentCommand {}

    /** Raw instant camera set (smoothing arrives in M3.2). */
    record Look(float yaw, float pitch) implements AgentCommand {}

    /** Release every held control immediately. */
    record Release() implements AgentCommand {}

    /** Per-session settings; a null field means "unchanged". See protocol/v1.md. */
    record Configure(Integer rateDivisor) implements AgentCommand {}
}
