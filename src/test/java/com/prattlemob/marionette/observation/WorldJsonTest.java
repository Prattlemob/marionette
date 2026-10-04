package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WorldJsonTest {
    private static final WorldJson.Light DAYLIGHT = new WorldJson.Light(0, 15, 15, 12);

    @Test
    void weatherUsesThunderBeforeRain() {
        assertEquals("clear", WorldJson.weather(false, false));
        assertEquals("rain", WorldJson.weather(true, false));
        assertEquals("thunder", WorldJson.weather(true, true));
    }

    @Test
    void sectionMatchesTheProtocolExample() {
        assertEquals(JsonParser.parseString("""
                {"dimension":"minecraft:overworld","dayTime":30000,"timeOfDay":6000,"day":1,"weather":"rain",
                 "rainLevel":1.0,"thunderLevel":0.0,"feet":{"x":0,"y":-60,"z":0},
                 "light":{"block":0,"sky":15,"combined":15,"effective":12}}
                """), WorldJson.build("minecraft:overworld", 30000, true, false, 1.0F, 0.0F, 0, -60, 0, DAYLIGHT));
    }

    @Test
    void timeOfDayAndDayFloorForEveryDayTime() {
        JsonObject start = WorldJson.build("minecraft:the_nether", 0, false, false, 0F, 0F, 1, 2, 3, DAYLIGHT);
        assertEquals(0, start.get("timeOfDay").getAsLong());
        assertEquals(0, start.get("day").getAsLong());
        JsonObject late = WorldJson.build("minecraft:overworld", 24000L * 1000 + 18000, false, false, 0F, 0F, 0, 0, 0, DAYLIGHT);
        assertEquals(18000, late.get("timeOfDay").getAsLong());
        assertEquals(1000, late.get("day").getAsLong());
        JsonObject negative = WorldJson.build("minecraft:overworld", -1, false, false, 0F, 0F, 0, 0, 0, DAYLIGHT);
        assertEquals(23999, negative.get("timeOfDay").getAsLong());
        assertEquals(-1, negative.get("day").getAsLong());
    }
}
