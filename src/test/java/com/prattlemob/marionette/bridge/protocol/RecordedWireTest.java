package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Frames recorded from a rendered client (shared with the Python replay test). */
class RecordedWireTest {
    @Test
    void recordedEventsAreUnsolicitedOrderedAndNeverReplies() throws Exception {
        var fixture = JsonParser.parseString(Files.readString(
                Path.of("python/tests/fixtures/events-wire.json"))).getAsJsonObject();
        List<JsonObject> frames = new ArrayList<>();
        fixture.getAsJsonArray("frames").forEach(raw -> frames.add(JsonParser.parseString(raw.getAsString()).getAsJsonObject()));
        assertEquals("hello", frames.getFirst().get("type").getAsString());
        assertTrue(frames.getFirst().getAsJsonObject("capabilities").get("events").getAsBoolean());
        long seq = 0;
        long lastObservation = -1;
        int results = 0;
        for (JsonObject frame : frames) {
            switch (frame.get("type").getAsString()) {
                case "event" -> {
                    assertFalse(frame.has("id"), "an event can never answer a request");
                    assertEquals(++seq, frame.get("seq").getAsLong());
                    assertTrue(lastObservation < frame.get("tick").getAsLong(),
                            "an event follows only observations of earlier ticks");
                    String basis = frame.get("basis").getAsString();
                    assertEquals(frame.get("event").getAsString().equals("block_broken") ? "client" : "server", basis);
                }
                case "observation" -> lastObservation = frame.get("tick").getAsLong();
                case "inventory_result" -> assertEquals("inventory-" + ++results, frame.get("id").getAsString());
                case "hello" -> { }
                default -> fail("unexpected frame " + frame);
            }
        }
        assertEquals(30, seq);
        assertEquals(3, results);
    }
}
