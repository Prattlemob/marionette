package com.prattlemob.marionette.bridge.protocol;

import java.util.List;

/**
 * Everything the status HUD shows and a {@code status_result} reports, sampled
 * together on the client thread (protocol/v1.md, status_result).
 *
 * @param state "connected", "idle" or "latched"
 * @param tick observation tick, null outside a world
 * @param controller the attached controller's counters, or null
 * @param session the requesting session's counters; null for the HUD
 */
public record StatusReport(String state, String mode, boolean paused, boolean inWorld, Long tick,
                           List<String> held, boolean panning, int observers,
                           ConnectionStatus controller, ConnectionStatus session) {
    public static final String CONNECTED = "connected";
    public static final String IDLE = "idle";
    public static final String LATCHED = "latched";

    /** The same report as seen by one requesting session. */
    public StatusReport forSession(ConnectionStatus requester) {
        return new StatusReport(state, mode, paused, inWorld, tick, held, panning, observers, controller, requester);
    }
}
