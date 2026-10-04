package com.prattlemob.marionette.observation;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Minecraft-free item stack and inventory JSON (protocol/v1.md, Inventory
 * section): enumerated, capped stack extras and the 96 KiB size bound.
 */
public final class InventoryJson {
    public static final String AIR = "minecraft:air";
    public static final int NAME_LIMIT = 64;
    public static final int ENCHANTMENT_LIMIT = 8;
    public static final int BYTE_LIMIT = 96 * 1024;
    /** Stack fields kept by the reduced form; everything else is optional detail. */
    private static final Set<String> REDUCED_FIELDS = Set.of("item", "count", "damage", "maxDamage",
            "slot", "alias", "refused");

    private InventoryJson() {}

    public record Enchantment(String id, int level) {}

    /**
     * Client-visible facts of one stack. {@code maxDamage} is 0 for items
     * without durability; {@code name}, {@code potion} are null when absent.
     */
    public record Stack(String item, int count, int damage, int maxDamage, String name,
                        List<Enchantment> enchantments, List<Enchantment> storedEnchantments, String potion) {
        public static Stack empty() { return new Stack(AIR, 0, 0, 0, null, List.of(), List.of(), null); }
        public static Stack of(String item, int count) { return new Stack(item, count, 0, 0, null, List.of(), List.of(), null); }
    }

    public static JsonObject stack(Stack stack) {
        JsonObject result = new JsonObject();
        boolean empty = stack.count() <= 0 || AIR.equals(stack.item());
        result.addProperty("item", empty ? AIR : stack.item());
        result.addProperty("count", empty ? 0 : stack.count());
        if (empty) return result;
        if (stack.maxDamage() > 0) {
            result.addProperty("damage", stack.damage());
            result.addProperty("maxDamage", stack.maxDamage());
        }
        if (stack.name() != null) {
            boolean cut = stack.name().length() > NAME_LIMIT;
            result.addProperty("name", cut ? cutUtf16(stack.name(), NAME_LIMIT) : stack.name());
            if (cut) result.addProperty("nameTruncated", true);
        }
        enchantments(result, "enchantments", stack.enchantments());
        enchantments(result, "storedEnchantments", stack.storedEnchantments());
        if (stack.potion() != null) result.addProperty("potion", stack.potion());
        return result;
    }

    private static void enchantments(JsonObject result, String key, List<Enchantment> list) {
        if (list == null || list.isEmpty()) return;
        JsonArray array = new JsonArray();
        list.stream().sorted(Comparator.comparing(Enchantment::id).thenComparingInt(Enchantment::level))
                .limit(ENCHANTMENT_LIMIT).forEach(enchantment -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", enchantment.id());
                    entry.addProperty("level", enchantment.level());
                    array.add(entry);
                });
        result.add(key, array);
        if (list.size() > ENCHANTMENT_LIMIT) result.addProperty(key + "Truncated", true);
    }

    /** Never split a surrogate pair. */
    private static String cutUtf16(String text, int limit) {
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }

    /** Bound an inspect menu descriptor in place; returns it for chaining. */
    public static JsonObject boundMenu(JsonObject menu) {
        if (menu == null || fits(menu)) return menu;
        reduceMenu(menu);
        menu.addProperty("reduced", true);
        truncateSlots(menu, menu);
        return menu;
    }

    /** Bound an observation {@code inventory} section in place; returns it for chaining. */
    public static JsonObject boundSection(JsonObject section) {
        if (fits(section)) return section;
        for (String key : List.of("hotbar", "main")) {
            if (section.get(key) instanceof JsonArray array) array.forEach(InventoryJson::reduce);
        }
        reduce(section.get("mainHand"));
        reduce(section.get("offhand"));
        if (section.get("armor") instanceof JsonObject armor) armor.entrySet().forEach(e -> reduce(e.getValue()));
        JsonObject menu = section.get("menu") instanceof JsonObject m ? m : null;
        if (menu != null) reduceMenu(menu);
        section.addProperty("reduced", true);
        if (menu != null) truncateSlots(section, menu);
        return section;
    }

    private static void reduceMenu(JsonObject menu) {
        if (menu.get("slots") instanceof JsonArray slots) slots.forEach(InventoryJson::reduce);
        reduce(menu.get("carried"));
    }

    private static void reduce(JsonElement element) {
        if (!(element instanceof JsonObject stack)) return;
        stack.keySet().removeIf(key -> !REDUCED_FIELDS.contains(key));
    }

    /** Drop trailing slots until {@code root} fits; {@code root} may be the menu itself. */
    private static void truncateSlots(JsonObject root, JsonObject menu) {
        if (fits(root) || !(menu.get("slots") instanceof JsonArray slots)) return;
        menu.addProperty("truncated", true);
        long size = bytes(root);
        while (size > BYTE_LIMIT && !slots.isEmpty()) {
            JsonElement removed = slots.remove(slots.size() - 1);
            size -= bytes(removed) + (slots.isEmpty() ? 0 : 1); // its separating comma
        }
    }

    static boolean fits(JsonObject object) { return bytes(object) <= BYTE_LIMIT; }

    static long bytes(JsonElement element) {
        return element.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
