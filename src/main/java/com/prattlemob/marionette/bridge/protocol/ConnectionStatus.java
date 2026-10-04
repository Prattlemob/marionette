package com.prattlemob.marionette.bridge.protocol;

import java.util.List;

/**
 * One connection's diagnostics counters at one moment ({@code status}; see
 * protocol/v1.md, status_result). Minecraft-free.
 *
 * @param rttMillis latest ping round trip, null before the first pong
 * @param commandLatencyMillis receipt-to-apply time of the latest applied command, null before the first
 */
public record ConnectionStatus(String role, String agent, long connectedMillis, double observationRate,
                               long observationsSent, long observationsDropped, long eventsSent,
                               int queuedCommands, Double rttMillis, Double commandLatencyMillis,
                               int rateDivisor, List<String> sections, boolean events) {
}
