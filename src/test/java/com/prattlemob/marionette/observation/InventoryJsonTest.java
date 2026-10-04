package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class InventoryJsonTest {
    private static InventoryJson.Stack sword(String name, int enchantments) {
        List<InventoryJson.Enchantment> list = new ArrayList<>();
        for (int i = enchantments - 1; i >= 0; i--) list.add(new InventoryJson.Enchantment("minecraft:e" + i, i + 1));
        return new InventoryJson.Stack("minecraft:diamond_sword", 1, 12, 1561, name, list, List.of(), null);
    }

    @Test
    void emptyStacksAreAirWithoutExtras() {
        assertEquals(JsonParser.parseString("{\"item\":\"minecraft:air\",\"count\":0}"),
                InventoryJson.stack(InventoryJson.Stack.empty()));
        // A zero-count stack of a real item is still empty on the wire.
        assertEquals(JsonParser.parseString("{\"item\":\"minecraft:air\",\"count\":0}"),
                InventoryJson.stack(new InventoryJson.Stack("minecraft:stone", 0, 0, 0, "x", List.of(), List.of(), null)));
    }

    @Test
    void plainStacksCarryOnlyIdAndCount() {
        assertEquals(JsonParser.parseString("{\"item\":\"minecraft:bread\",\"count\":5}"),
                InventoryJson.stack(InventoryJson.Stack.of("minecraft:bread", 5)));
    }

    @Test
    void enumeratedExtrasAreSortedAndPresentOnlyWhenKnown() {
        JsonObject stack = InventoryJson.stack(sword("Edge", 2));
        assertEquals(JsonParser.parseString("""
                {"item":"minecraft:diamond_sword","count":1,"damage":12,"maxDamage":1561,"name":"Edge",
                 "enchantments":[{"id":"minecraft:e0","level":1},{"id":"minecraft:e1","level":2}]}
                """), stack);
        JsonObject book = InventoryJson.stack(new InventoryJson.Stack("minecraft:enchanted_book", 1, 0, 0, null,
                List.of(), List.of(new InventoryJson.Enchantment("minecraft:mending", 1)), null));
        assertEquals(1, book.getAsJsonArray("storedEnchantments").size());
        assertFalse(book.has("damage"));
        JsonObject potion = InventoryJson.stack(new InventoryJson.Stack("minecraft:potion", 1, 0, 0, null,
                List.of(), List.of(), "minecraft:healing"));
        assertEquals("minecraft:healing", potion.get("potion").getAsString());
        JsonObject fresh = InventoryJson.stack(new InventoryJson.Stack("minecraft:shield", 1, 0, 336, null,
                List.of(), List.of(), null));
        assertEquals(0, fresh.get("damage").getAsInt(), "damage is present at zero for damageable items");
    }

    @Test
    void namesAndEnchantmentListsAreCapped() {
        JsonObject stack = InventoryJson.stack(sword("n".repeat(100), 12));
        assertEquals(InventoryJson.NAME_LIMIT, stack.get("name").getAsString().length());
        assertTrue(stack.get("nameTruncated").getAsBoolean());
        assertEquals(InventoryJson.ENCHANTMENT_LIMIT, stack.getAsJsonArray("enchantments").size());
        assertTrue(stack.get("enchantmentsTruncated").getAsBoolean());
        assertEquals("minecraft:e0", stack.getAsJsonArray("enchantments").get(0).getAsJsonObject()
                .get("id").getAsString(), "the cap keeps the first entries in id order");
        JsonObject exact = InventoryJson.stack(sword("n".repeat(64), 8));
        assertFalse(exact.has("nameTruncated"));
        assertFalse(exact.has("enchantmentsTruncated"));
    }

    @Test
    void nameCutNeverSplitsSurrogatePairs() {
        String name = "a".repeat(63) + "😀" + "tail";
        String cut = InventoryJson.stack(sword(name, 0)).get("name").getAsString();
        assertEquals(63, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    private static JsonObject menu(int slots, InventoryJson.Stack stack) {
        JsonObject menu = new JsonObject();
        menu.addProperty("type", "modded:big");
        menu.addProperty("containerId", 3);
        menu.addProperty("stateId", 9);
        menu.addProperty("slotCount", slots);
        JsonArray array = new JsonArray();
        for (int i = 0; i < slots; i++) {
            JsonObject entry = InventoryJson.stack(stack);
            entry.addProperty("slot", i);
            array.add(entry);
        }
        menu.add("slots", array);
        menu.add("carried", InventoryJson.stack(stack));
        menu.add("operations", new JsonArray());
        menu.addProperty("refusal", "unsupported_menu");
        return menu;
    }

    @Test
    void smallMenusAreUnchangedAndUnflagged() {
        JsonObject menu = menu(27, sword("Edge", 3));
        JsonObject copy = menu.deepCopy();
        assertSame(menu, InventoryJson.boundMenu(menu));
        assertEquals(copy, menu);
        assertNull(InventoryJson.boundMenu(null));
    }

    @Test
    void oversizedMenusAreReducedBeforeTruncated() {
        JsonObject menu = InventoryJson.boundMenu(menu(300, sword("n".repeat(64), 12)));
        assertTrue(InventoryJson.fits(menu));
        assertTrue(menu.get("reduced").getAsBoolean());
        assertFalse(menu.has("truncated"));
        JsonObject slot = menu.getAsJsonArray("slots").get(5).getAsJsonObject();
        assertEquals(JsonParser.parseString(
                "{\"item\":\"minecraft:diamond_sword\",\"count\":1,\"damage\":12,\"maxDamage\":1561,\"slot\":5}"), slot);
        assertEquals(300, menu.getAsJsonArray("slots").size());
    }

    @Test
    void hugeMenusTruncateTrailingSlotsButKeepSlotCount() {
        JsonObject menu = InventoryJson.boundMenu(menu(5000, sword(null, 0)));
        assertTrue(InventoryJson.fits(menu));
        assertTrue(menu.get("reduced").getAsBoolean());
        assertTrue(menu.get("truncated").getAsBoolean());
        assertEquals(5000, menu.get("slotCount").getAsInt());
        JsonArray slots = menu.getAsJsonArray("slots");
        assertTrue(slots.size() > 100 && slots.size() < 5000);
        assertEquals(slots.size() - 1, slots.get(slots.size() - 1).getAsJsonObject().get("slot").getAsInt(),
                "leading slots are kept in index order");
        assertTrue(InventoryJson.bytes(menu) > InventoryJson.BYTE_LIMIT - 200, "truncation removes only what it must");
    }

    @Test
    void sectionBoundReducesPlayerStacksAndMenuTogether() {
        InventoryJson.Stack heavy = sword("n".repeat(64), 12);
        JsonObject section = new JsonObject();
        section.addProperty("selected", 0);
        section.add("mainHand", InventoryJson.stack(heavy));
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) hotbar.add(InventoryJson.stack(heavy));
        section.add("hotbar", hotbar);
        JsonArray main = new JsonArray();
        for (int i = 0; i < 27; i++) main.add(InventoryJson.stack(heavy));
        section.add("main", main);
        JsonObject armor = new JsonObject();
        for (String key : List.of("head", "chest", "legs", "feet")) armor.add(key, InventoryJson.stack(heavy));
        section.add("armor", armor);
        section.add("offhand", InventoryJson.stack(heavy));
        section.add("menu", menu(250, heavy));
        InventoryJson.boundSection(section);
        assertTrue(InventoryJson.fits(section));
        assertTrue(section.get("reduced").getAsBoolean());
        assertFalse(section.getAsJsonObject("menu").has("reduced"), "the section carries the flag");
        assertFalse(section.getAsJsonObject("mainHand").has("name"));
        assertFalse(section.getAsJsonObject("armor").getAsJsonObject("feet").has("enchantments"));
        assertFalse(section.getAsJsonObject("menu").getAsJsonObject("carried").has("enchantments"));

        JsonObject small = new JsonObject();
        small.add("menu", JsonNull.INSTANCE);
        assertEquals(1, InventoryJson.boundSection(small).size());
    }

    @Test
    void loadedCrossbowIsMarkedChargedOnlyWhenLoaded() {
        JsonObject loaded = InventoryJson.stack(new InventoryJson.Stack("minecraft:crossbow", 1, 0, 465, null,
                List.of(), List.of(), null, true));
        assertTrue(loaded.get("charged").getAsBoolean());
        JsonObject empty = InventoryJson.stack(new InventoryJson.Stack("minecraft:crossbow", 1, 0, 465, null,
                List.of(), List.of(), null));
        assertFalse(empty.has("charged"));
    }
}
