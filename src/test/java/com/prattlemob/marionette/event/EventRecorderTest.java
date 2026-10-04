package com.prattlemob.marionette.event;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.GameEvent;

class EventRecorderTest {
    private final List<GameEvent> events = new ArrayList<>();
    private long tick = 1;
    private EventRecorder recorder;

    @BeforeEach
    void start() {
        recorder = new EventRecorder(() -> tick, events::add);
        recorder.startWorldSession("w1");
        recorder.healthUpdated(20f, true); // the player's first server health update
    }

    private static JsonObject generic() {
        return EventRecorder.source("minecraft:generic", null, null);
    }

    private List<String> kinds() {
        return events.stream().map(GameEvent::kind).toList();
    }

    @Test
    void damagePairsTheServerReportWithTheNextHealthLoss() {
        recorder.damageReported(EventRecorder.source("minecraft:mob_attack", "minecraft:zombie", "minecraft:zombie"));
        assertTrue(events.isEmpty(), "the amount is not known yet");
        tick = 2;
        recorder.healthUpdated(17f, false);
        GameEvent damage = events.getFirst();
        assertEquals("damage", damage.kind());
        assertEquals(GameEvent.Basis.SERVER, damage.basis());
        assertEquals("w1", damage.worldSession());
        assertEquals(2, damage.tick(), "stamped when the pairing completes");
        assertEquals(3.0, damage.fields().get("amount").getAsDouble());
        assertEquals(17.0, damage.fields().get("health").getAsDouble());
        assertEquals("minecraft:zombie", damage.fields().getAsJsonObject("source").get("attacker").getAsString());
        assertTrue(damage.fields().getAsJsonObject("source").get("type").isJsonPrimitive());
    }

    @Test
    void unknownHalvesAreNullNotZero() {
        recorder.healthObserved(18f);
        assertTrue(events.getFirst().fields().get("source").isJsonNull(), "health loss without a report");
        recorder.damageReported(generic());
        tick += EventRecorder.DAMAGE_WINDOW_TICKS - 1;
        recorder.tick();
        assertEquals(1, events.size(), "window still open");
        tick++;
        recorder.tick();
        GameEvent absorbed = events.get(1);
        assertTrue(absorbed.fields().get("amount").isJsonNull());
        assertTrue(absorbed.fields().get("health").isJsonNull());
        assertFalse(absorbed.fields().get("source").isJsonNull());
        recorder.tick();
        assertEquals(2, events.size(), "reported exactly once");
    }

    @Test
    void secondReportFlushesTheFirstWithUnknownAmount() {
        recorder.damageReported(EventRecorder.source("minecraft:arrow", null, null));
        recorder.damageReported(generic());
        recorder.healthUpdated(15f, false);
        assertEquals(2, events.size());
        assertTrue(events.get(0).fields().get("amount").isJsonNull());
        assertEquals("minecraft:arrow", events.get(0).fields().getAsJsonObject("source").get("type").getAsString());
        assertEquals(5.0, events.get(1).fields().get("amount").getAsDouble());
    }

    @Test
    void healingAndInitialSyncAreNotDamage() {
        recorder.healthObserved(20f);
        recorder.healthUpdated(20f, false);
        recorder.playerReplaced(false, "minecraft:overworld", "minecraft:the_nether");
        events.clear();
        recorder.healthObserved(20f);  // an unsynced new player: no baseline yet
        recorder.healthObserved(12f);
        recorder.healthUpdated(5f, true);
        recorder.healthObserved(9f);   // healing raises the baseline
        assertTrue(events.isEmpty());
        recorder.healthObserved(8f);
        assertEquals(1.0, events.getFirst().fields().get("amount").getAsDouble());
    }

    @Test
    void entityDataAndThePacketReportTheSameLossOnce() {
        recorder.damageReported(generic());
        recorder.healthObserved(17f);      // entity data synced first (tick sample)
        recorder.healthUpdated(17f, false); // then the health packet with the same value
        recorder.healthObserved(17f);
        assertEquals(1, events.size());
        assertEquals(3.0, events.getFirst().fields().get("amount").getAsDouble());
        assertFalse(events.getFirst().fields().get("source").isJsonNull());
    }

    @Test
    void anUnreportedDeathStillReportsTheLossWithUnknownSource() {
        recorder.healthObserved(14f);
        events.clear();
        recorder.death("Dev was killed");
        assertEquals(List.of("damage", "death"), kinds());
        assertTrue(events.getFirst().fields().get("source").isJsonNull());
        assertEquals(14.0, events.getFirst().fields().get("amount").getAsDouble());
        assertEquals(0.0, events.getFirst().fields().get("health").getAsDouble());
    }

    @Test
    void deathPairsAWaitingReportSoDamagePrecedesDeathThenRespawn() {
        recorder.damageReported(EventRecorder.source("minecraft:generic_kill", null, null));
        recorder.death("Player was killed");
        recorder.healthUpdated(0f, false); // late health update after death
        recorder.healthObserved(0f);
        tick += 40;
        recorder.tick();
        recorder.damageReported(generic()); // nothing about a dead player
        recorder.playerReplaced(false, "minecraft:overworld", "minecraft:overworld");
        assertEquals(List.of("damage", "death", "respawn"), kinds());
        assertEquals(20.0, events.get(0).fields().get("amount").getAsDouble());
        assertEquals(0.0, events.get(0).fields().get("health").getAsDouble());
        assertEquals("Player was killed", events.get(1).fields().get("message").getAsString());
        assertFalse(events.get(1).fields().get("truncated").getAsBoolean());
        assertEquals("minecraft:overworld", events.get(2).fields().get("dimension").getAsString());
        recorder.healthUpdated(20f, true);
        recorder.healthUpdated(19f, false);
        assertEquals("damage", events.getLast().kind(), "the new player can be hurt again");
    }

    @Test
    void respawnElsewhereReportsRespawnThenDimensionChange() {
        recorder.death("");
        events.removeFirst(); // the unseen loss of the remaining 20 health
        assertTrue(events.getFirst().fields().get("message").isJsonNull(), "an empty message was not sent");
        recorder.playerReplaced(false, "minecraft:the_nether", "minecraft:overworld");
        recorder.playerReplaced(false, "minecraft:overworld", "minecraft:the_end");
        recorder.playerReplaced(true, "minecraft:the_end", "minecraft:the_end");
        assertEquals(List.of("death", "respawn", "dimension_change", "dimension_change", "respawn"), kinds());
        assertEquals("minecraft:the_nether", events.get(2).fields().get("from").getAsString());
        assertEquals("minecraft:the_end", events.get(3).fields().get("to").getAsString());
    }

    @Test
    void chatPickupAndBlockFieldsWithBasisAndTruncation() {
        recorder.chat("chat", "<Dev> hi", "00000000-0000-0000-0000-000000000001", "Dev", "minecraft:chat");
        recorder.chat("system", "x".repeat(EventRecorder.TEXT_LIMIT + 5), null, null, null);
        recorder.itemPickup("minecraft:diamond", 3);
        recorder.itemPickup(null, 1);
        recorder.blockBroken("minecraft:glass", 0, -59, 3);
        assertEquals(List.of("chat", "chat", "item_pickup", "item_pickup", "block_broken"), kinds());
        JsonObject longChat = events.get(1).fields();
        assertEquals(EventRecorder.TEXT_LIMIT, longChat.get("text").getAsString().length());
        assertTrue(longChat.get("truncated").getAsBoolean());
        assertTrue(longChat.get("sender").isJsonNull());
        assertTrue(longChat.get("chatType").isJsonNull());
        assertTrue(events.get(3).fields().get("item").isJsonNull());
        assertEquals(GameEvent.Basis.CLIENT, events.get(4).basis(), "a break is a client prediction");
        assertEquals(-59, events.get(4).fields().getAsJsonObject("pos").get("y").getAsInt());
        assertTrue(events.stream().limit(4).allMatch(e -> e.basis() == GameEvent.Basis.SERVER));
    }

    @Test
    void nothingIsRecordedOutsideAWorldAndSessionsDiffer() {
        recorder.damageReported(generic());
        recorder.endWorldSession();
        recorder.healthUpdated(10f, false);
        recorder.chat("system", "x", null, null, null);
        recorder.tick();
        assertTrue(events.isEmpty(), "an unpaired report is discarded with its world");
        String a = EventRecorder.newWorldSessionId(), b = EventRecorder.newWorldSessionId();
        assertNotEquals(a, b);
        assertEquals(16, a.length());
        recorder.startWorldSession(b);
        recorder.chat("system", "x", null, null, null);
        assertEquals(b, events.getFirst().worldSession());
    }

    @Test
    void playerIdentityOnChatAndDamage() {
        recorder.chat("chat", "<Dev> hi", "00000000-0000-0000-0000-000000000001", "Dev", "minecraft:chat");
        JsonObject chat = events.getFirst().fields();
        assertEquals("Dev", chat.get("senderName").getAsString());
        assertEquals("00000000-0000-0000-0000-000000000001", chat.get("sender").getAsString());
        recorder.chat("system", "x", null, null, null);
        assertTrue(events.get(1).fields().get("senderName").isJsonNull(), "present as null, never absent");
        JsonObject byPlayer = EventRecorder.source("minecraft:player_attack", "minecraft:player", "minecraft:player",
                "00000000-0000-0000-0000-000000000002", "Alex");
        assertEquals("Alex", byPlayer.getAsJsonObject("attackerPlayer").get("name").getAsString());
        assertEquals("00000000-0000-0000-0000-000000000002",
                byPlayer.getAsJsonObject("attackerPlayer").get("uuid").getAsString());
        assertTrue(EventRecorder.source("minecraft:mob_attack", "minecraft:zombie", "minecraft:zombie")
                .get("attackerPlayer").isJsonNull());
        assertTrue(EventRecorder.source("minecraft:player_attack", "minecraft:player", null, "u", null)
                .get("attackerPlayer").isJsonNull(), "an incomplete identity is unknown");
    }

    @Test
    void controlEventsCarryModePauseCauseAndInputs() {
        recorder.control("human_priority", true, "human_input", List.of("movement", "look"));
        GameEvent event = events.getLast();
        assertEquals("control", event.kind());
        assertEquals(GameEvent.Basis.CLIENT, event.basis());
        assertEquals("human_priority", event.fields().get("mode").getAsString());
        assertTrue(event.fields().get("paused").getAsBoolean());
        assertEquals("human_input", event.fields().get("cause").getAsString());
        assertEquals("[\"movement\",\"look\"]", event.fields().get("inputs").toString());
        recorder.control("agent_exclusive", false, "lockout_engaged", List.of());
        assertEquals("[]", events.getLast().fields().get("inputs").toString());
    }

    @Test
    void controlEventsNeedAWorld() {
        recorder.endWorldSession();
        recorder.control("panic", false, "panic", List.of());
        assertTrue(events.isEmpty());
    }
}
