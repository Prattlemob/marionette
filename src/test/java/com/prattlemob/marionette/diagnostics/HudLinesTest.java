package com.prattlemob.marionette.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.prattlemob.marionette.bridge.protocol.ConnectionStatus;
import com.prattlemob.marionette.bridge.protocol.StatusReport;

class HudLinesTest {
    private static final ConnectionStatus WALKER = new ConnectionStatus("controller", "walker", 4000, 20.0, 80, 1, 0,
            0, 0.84, 12.0, 1, List.of("player"), false);

    private static StatusReport report(String state, String mode, boolean paused, List<String> held,
                                       ConnectionStatus controller, int observers) {
        return new StatusReport(state, mode, paused, true, 10L, held, false, observers, controller, null);
    }

    private static List<String> texts(List<HudLines.Line> lines) {
        return lines.stream().map(HudLines.Line::text).toList();
    }

    @Test
    void idleIsOneDimLine() {
        var lines = HudLines.of(report("idle", "human_priority", false, List.of(), null, 0), "F7", "F9");
        assertEquals(List.of(new HudLines.Line("Marionette: idle", HudLines.GRAY)), lines);
        assertEquals(List.of("Marionette: idle +2 observers"),
                texts(HudLines.of(report("idle", "human_priority", false, List.of(), null, 2), "F7", "F9")));
    }

    @Test
    void latchedPanicIsRedAndNamesTheRearmKey() {
        var lines = HudLines.of(report("latched", "panic", false, List.of(), null, 1), "F7", "F9");
        assertEquals(List.of("Marionette: PANIC", "Agent control off (F9 allows it)"), texts(lines));
        assertTrue(lines.stream().allMatch(line -> line.color() == HudLines.RED));
    }

    @Test
    void connectedShowsAgentModeHeldControlsAndCounters() {
        var lines = HudLines.of(report("connected", "human_priority", false, List.of("forward", "sprint"), WALKER, 1),
                "F7", "F9");
        assertEquals(List.of("Marionette: connected", "walker (controller) +1 observer", "Human priority",
                "Held: forward sprint", "Obs 20.0/s  sent 80  drop 1", "Ping 0.8 ms  cmd 12.0 ms"), texts(lines));
        assertEquals(HudLines.GREEN, lines.get(0).color());
    }

    @Test
    void modeIsProminentForLockoutAndPause() {
        var locked = HudLines.of(report("connected", "agent_exclusive", false, List.of(), WALKER, 0), "F7", "F9");
        assertEquals(new HudLines.Line("AGENT EXCLUSIVE: input locked (F7)", HudLines.ORANGE), locked.get(2));
        var paused = HudLines.of(report("connected", "human_priority", true, List.of(), WALKER, 0), "F7", "F9");
        assertEquals(new HudLines.Line("PAUSED by your input", HudLines.YELLOW), paused.get(2));
        assertEquals("Held: none", paused.get(3).text());
    }

    @Test
    void unnamedAgentsAndMissingCountersStayReadable() {
        var anonymous = new ConnectionStatus("controller", null, 0, 0.0, 0, 0, 0, 0, null, null, 1, List.of(), false);
        var lines = HudLines.of(new StatusReport("connected", "human_priority", false, true, 1L, List.of(), true, 0,
                anonymous, null), "F7", "F9");
        assertEquals(List.of("Marionette: connected", "unnamed agent (controller)", "Human priority", "Held: none +pan",
                "Obs 0.0/s  sent 0  drop 0", "Ping -  cmd -"), texts(lines));
    }

    @Test
    void linesStayShortEnoughToClearToasts() {
        var named = new ConnectionStatus("controller", "y".repeat(64), 0, 20.0, 999_999, 99_999, 0, 0, 999.9, 999.9, 1,
                List.of(), false);
        for (var report : List.of(report("connected", "agent_exclusive", false,
                List.of("forward", "back", "left", "right"), named, 8), report("latched", "panic", false, List.of(), null, 8),
                report("idle", "human_priority", false, List.of(), null, 8))) {
            for (var line : HudLines.of(report, "F7", "F9")) {
                assertTrue(line.text().length() <= 42, line.text());
            }
        }
        assertEquals("yyyyyyyyyyyyy... (controller) +8 observers",
                HudLines.of(report("connected", "human_priority", false, List.of(), named, 8), "F7", "F9").get(1).text());
    }
}
