package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class EntityJsonTest {
    private static EntityJson.Traits traits(boolean player, boolean item, boolean mob, boolean neutral,
                                            boolean enemy, boolean monster) {
        return new EntityJson.Traits(player, item, mob, neutral, enemy, monster);
    }

    @Test
    void hostilityFollowsTheProtocolPrecedence() {
        assertEquals("player", EntityJson.hostility("minecraft:player", traits(true, false, false, false, false, false)));
        assertEquals("item", EntityJson.hostility("minecraft:item", traits(false, true, false, false, false, false)));
        // The vanilla table wins over class markers: spiders are Enemy subclasses, goats plain animals.
        assertEquals("neutral", EntityJson.hostility("minecraft:spider", traits(false, false, true, false, true, true)));
        assertEquals("neutral", EntityJson.hostility("minecraft:goat", traits(false, false, true, false, false, false)));
        // Modded fallbacks.
        assertEquals("neutral", EntityJson.hostility("mod:angry", traits(false, false, true, true, true, true)));
        assertEquals("hostile", EntityJson.hostility("mod:enemy", traits(false, false, true, false, true, false)));
        assertEquals("hostile", EntityJson.hostility("mod:monster", traits(false, false, true, false, false, true)));
        assertEquals("passive", EntityJson.hostility("mod:critter", traits(false, false, true, false, false, false)));
        assertEquals("other", EntityJson.hostility("mod:cart", traits(false, false, false, false, false, true)));
        assertEquals("other", EntityJson.hostility("minecraft:experience_orb", traits(false, false, false, false, false, false)));
    }

    @Test
    void targetingIsUnknownWithoutASynchronizedTarget() {
        assertEquals("unknown", EntityJson.targeting(0, 42));
        assertEquals("yes", EntityJson.targeting(42, 42));
        assertEquals("no", EntityJson.targeting(7, 42));
    }

    private record Candidate(int id, double distance) {}

    @Test
    void nearestKeepsTheClosestByDistanceThenId() {
        List<Candidate> inRange = new ArrayList<>();
        for (int id = 0; id < 100; id++) inRange.add(new Candidate(id, (id * 37) % 50));
        List<Candidate> kept = EntityJson.nearest(inRange, 5, Candidate::distance, Candidate::id);
        assertEquals(List.of(new Candidate(0, 0), new Candidate(50, 0), new Candidate(23, 1),
                new Candidate(73, 1), new Candidate(46, 2)), kept);
        assertEquals(100, EntityJson.nearest(inRange, 256, Candidate::distance, Candidate::id).size());
    }

    @Test
    void sectionFlagsTruncationOnlyWhenTheCapWasExceeded() {
        JsonObject capped = EntityJson.section(16, 2, 3, List.of(new JsonObject(), new JsonObject()));
        assertTrue(capped.get("truncated").getAsBoolean());
        assertEquals(3, capped.get("total").getAsInt());
        assertEquals(2, capped.getAsJsonArray("nearby").size());
        assertFalse(EntityJson.section(16, 2, 2, List.of(new JsonObject(), new JsonObject()))
                .get("truncated").getAsBoolean());
        assertFalse(EntityJson.section(32, 64, 0, List.of()).get("truncated").getAsBoolean());
    }

    @Test
    void sectionMatchesTheProtocolExample() {
        JsonObject zombie = EntityJson.entry(212, "minecraft:zombie", "hostile", 3.5, -60.0, 1.5,
                -0.021, 0.0, 0.034, 3.808);
        EntityJson.addLiving(zombie, 20.0F, 20.0F, "unknown");
        JsonObject item = EntityJson.entry(215, "minecraft:item", "item", -1.2, -60.0, 5.1, 0.0, 0.0, 0.0, 5.239);
        EntityJson.addItem(item, "minecraft:diamond", 3);
        JsonObject cow = EntityJson.entry(213, "minecraft:cow", "passive", -6.5, -60.0, -3.5, 0.0, 0.0, 0.0, 7.382);
        EntityJson.addLiving(cow, 10.0F, 10.0F, "unknown");
        assertEquals(JsonParser.parseString("""
                {"radius":32,"maxCount":64,"total":3,"truncated":false,"nearby":[{"id":212,"type":"minecraft:zombie","hostility":"hostile","x":3.5,"y":-60.0,"z":1.5,"velocity":{"x":-0.021,"y":0.0,"z":0.034},"distance":3.808,"health":20.0,"maxHealth":20.0,"targetingMe":"unknown"},{"id":215,"type":"minecraft:item","hostility":"item","x":-1.2,"y":-60.0,"z":5.1,"velocity":{"x":0.0,"y":0.0,"z":0.0},"distance":5.239,"item":{"item":"minecraft:diamond","count":3}},{"id":213,"type":"minecraft:cow","hostility":"passive","x":-6.5,"y":-60.0,"z":-3.5,"velocity":{"x":0.0,"y":0.0,"z":0.0},"distance":7.382,"health":10.0,"maxHealth":10.0,"targetingMe":"unknown"}]}
                """), EntityJson.section(32, 64, 3, List.of(zombie, item, cow)));
    }
}
