package com.prattlemob.marionette.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.prattlemob.marionette.bridge.protocol.ConnectionStatus;
import com.prattlemob.marionette.bridge.protocol.StatusReport;

/**
 * The status HUD's text (M5.2), built from the same {@link StatusReport} a
 * {@code status} query returns. Minecraft-free so it is testable headless.
 * Idle is one dim line; a connection adds the agent, the precedence mode
 * (prominent, D25), the held controls and the counters; panic is two red lines.
 */
public final class HudLines {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFAAAAAA;
    public static final int GREEN = 0xFF55FF55;
    public static final int YELLOW = 0xFFFFFF55;
    public static final int ORANGE = 0xFFFFAA00;
    public static final int RED = 0xFFFF5555;
    /** Longest agent name shown on the HUD. */
    static final int AGENT_DISPLAY = 16;

    /** One HUD line and its ARGB color. */
    public record Line(String text, int color) {}

    private HudLines() {
    }

    /**
     * Lines stay short (about 35 characters) so a toast in the top-right corner
     * never covers them, even at 854×480 with the automatic GUI scale.
     *
     * @param lockoutKey display name of the input-lockout key
     * @param rearmKey display name of the re-arm key
     */
    public static List<Line> of(StatusReport report, String lockoutKey, String rearmKey) {
        List<Line> lines = new ArrayList<>();
        if (StatusReport.LATCHED.equals(report.state())) {
            lines.add(new Line("Marionette: PANIC", RED));
            lines.add(new Line("Agent control off (" + rearmKey + " allows it)", RED));
            return lines;
        }
        ConnectionStatus controller = report.controller();
        if (controller == null) {
            lines.add(new Line("Marionette: idle" + observers(report.observers()), GRAY));
            return lines;
        }
        lines.add(new Line("Marionette: connected", GREEN));
        lines.add(new Line((controller.agent() != null ? shorten(controller.agent()) : "unnamed agent") + " (controller)"
                + observers(report.observers()), WHITE));
        if ("agent_exclusive".equals(report.mode())) {
            lines.add(new Line("AGENT EXCLUSIVE: input locked (" + lockoutKey + ")", ORANGE));
        } else if (report.paused()) {
            lines.add(new Line("PAUSED by your input", YELLOW));
        } else {
            lines.add(new Line("Human priority", WHITE));
        }
        String held = report.held().isEmpty() ? "none" : String.join(" ", report.held());
        lines.add(new Line("Held: " + held + (report.panning() ? " +pan" : ""), WHITE));
        lines.add(new Line(String.format(Locale.ROOT, "Obs %.1f/s  sent %d  drop %d", controller.observationRate(),
                controller.observationsSent(), controller.observationsDropped()), GRAY));
        lines.add(new Line("Ping " + millis(controller.rttMillis()) + "  cmd " + millis(controller.commandLatencyMillis()),
                GRAY));
        return lines;
    }

    /** Long agent names (up to 64 characters) are cut for the HUD; status reports them whole. */
    private static String shorten(String agent) {
        return agent.length() <= AGENT_DISPLAY ? agent : agent.substring(0, AGENT_DISPLAY - 3) + "...";
    }

    private static String observers(int count) {
        return count == 0 ? "" : count == 1 ? " +1 observer" : " +" + count + " observers";
    }

    private static String millis(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f ms", value);
    }
}
