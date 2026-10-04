package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class TargetJsonTest {
    private static final TargetJson.Reach SURVIVAL = new TargetJson.Reach(4.5, 3.0);
    private static final TargetJson.Point EYE = new TargetJson.Point(0.5, -58.38, 0.5);

    @Test
    void noneCarriesOnlyKindAndReach() {
        assertEquals(JsonParser.parseString("{\"kind\":\"none\",\"reach\":{\"block\":4.5,\"entity\":3.0}}"),
                TargetJson.none(SURVIVAL));
    }

    @Test
    void blockReportsPositionIdFaceHitAndEyeDistance() {
        JsonObject block = TargetJson.block(3, -60, 1, "minecraft:oak_log", "north",
                new TargetJson.Point(3.5, -58.38, 1.0), new TargetJson.Point(3.5, -58.38, -2.0), SURVIVAL);
        assertEquals(JsonParser.parseString("""
                {"kind":"block","pos":{"x":3,"y":-60,"z":1},"block":"minecraft:oak_log","face":"north",
                 "hit":{"x":3.5,"y":-58.38,"z":1.0},"distance":3.0,"reach":{"block":4.5,"entity":3.0}}
                """), block);
    }

    @Test
    void entityReportsIdTypeHitAndEyeDistance() {
        JsonObject entity = TargetJson.entity(187, "minecraft:pig",
                new TargetJson.Point(0.5 + 1.2, -58.38 + 1.6, 0.5), EYE, new TargetJson.Reach(5.0, 5.0));
        assertEquals("entity", entity.get("kind").getAsString());
        assertEquals(187, entity.get("id").getAsInt());
        assertEquals("minecraft:pig", entity.get("entity").getAsString());
        assertEquals(2.0, entity.get("distance").getAsDouble(), 1e-9);
        assertEquals(5.0, entity.getAsJsonObject("reach").get("entity").getAsDouble());
        assertFalse(entity.has("pos"));
        assertFalse(entity.has("face"));
    }
}
