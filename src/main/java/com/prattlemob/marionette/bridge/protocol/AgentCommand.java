package com.prattlemob.marionette.bridge.protocol;

import java.util.Set;

import com.google.gson.JsonPrimitive;
import com.prattlemob.marionette.control.TapControl;

/**
 * A validated command for the tick thread. Parsed on the network thread,
 * applied on the client tick thread. See protocol/v1.md.
 */
public sealed interface AgentCommand extends ParsedMessage {
    /** Partial set-and-hold update; a null field means "unchanged".
     *  {@code hotbar} is a one-shot slot select (0–8), not held state.
     *  {@code taps} is never null — empty means no one-shot presses. */
    record InputUpdate(Boolean forward, Boolean back, Boolean left, Boolean right,
                       Boolean jump, Boolean sneak, Boolean sprint,
                       Boolean attack, Boolean use, Integer hotbar,
                       Set<TapControl> taps) implements AgentCommand {}

    /** Raw instant camera set (look mode "instant", the default). */
    record Look(float yaw, float pitch) implements AgentCommand {}

    /** Relative camera offset in degrees, applied next tick (look mode "delta"). */
    record LookDelta(float yaw, float pitch) implements AgentCommand {}

    /** Smoothed pan toward absolute angles (look mode "smooth"); null speed = config default. */
    record LookSmoothAngles(float yaw, float pitch, Float speed) implements AgentCommand {}

    /**
     * Smoothed pan toward a world point (look mode "smooth"); null speed =
     * config default. Carries the envelope id and raw frame so the tick
     * thread can send a spec-complete invalid_field error if the direction
     * is degenerate at apply time (see protocol/v1.md).
     */
    record LookSmoothPoint(double x, double y, double z, Float speed,
                           JsonPrimitive id, String raw) implements AgentCommand {}

    /** Inventory request, validated on the network thread and executed on the client tick. */
    record InventoryAction(String op, MenuRef menu, SlotRef from, SlotRef to,
                           Integer hotbar, boolean all, boolean animated, JsonPrimitive id, String raw) implements AgentCommand {}

    record MenuRef(String type, int containerId, int stateId) {}
    record SlotRef(Integer index, String alias) {}

    /** Release every held control immediately. */
    record Release() implements AgentCommand {}

    /** Per-session settings; a null field means "unchanged". See protocol/v1.md. */
    record Configure(Integer rateDivisor) implements AgentCommand {}
}
