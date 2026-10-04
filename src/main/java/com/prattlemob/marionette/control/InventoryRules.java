package com.prattlemob.marionette.control;

/** Minecraft-independent preflight rules. No clicks occur until all checks pass. */
public final class InventoryRules {
    private InventoryRules() {}

    public interface Slot {
        int count();
        boolean mayPickup();
        boolean mayPlace(Slot source);
        int capacity(Slot source);
        boolean sameItem(Slot other);
    }

    /** A rule violation with its wire {@code reason} (protocol/v1.md, Rejection reasons). */
    public static final class Rejection extends IllegalArgumentException {
        private final String reason;

        public Rejection(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public static void source(Slot source) {
        require(source.count() > 0, "source_empty", "source slot is empty");
        require(source.mayPickup(), "source_locked", "source slot forbids pickup");
    }

    public static void move(Slot source, Slot target) {
        source(source);
        receive(target, source, source.count());
    }

    /**
     * {@code move} with {@code count} items (crafting): the whole stack when it equals the source
     * count, otherwise pickup, {@code count} single placements and a return of the rest, so the
     * source must give up part of its stack and take the rest back.
     */
    public static void moveCount(Slot source, Slot target, int count, boolean wholeStackOnly) {
        source(source);
        require(count <= source.count(), "count_exceeds_source", "count is larger than the source stack");
        if (count == source.count()) {
            receive(target, source, count);
            return;
        }
        require(!wholeStackOnly, "whole_stack_only", "source slot gives up only whole stacks");
        receive(target, source, count);
        require(source.mayPlace(source) && source.count() - count <= source.capacity(source), "source_rejects",
                "source slot cannot take the rest back");
    }

    /** {@code target} accepts {@code total} items like {@code item}'s stack, on top of what it holds. */
    public static void receive(Slot target, Slot item, int total) {
        require(target.count() == 0 || target.sameItem(item), "destination_mismatch",
                "destination contains a different item");
        require(target.count() == 0 || target.mayPickup(), "destination_locked", "destination forbids pickup");
        require(target.mayPlace(item), "destination_rejects", "destination forbids this item");
        require(total <= target.capacity(item) - target.count(), "destination_full", "items do not fit");
    }

    /**
     * Craft preflight (crafting): the result slot offers {@code resultCount} items, and each nonempty
     * grid slot holds {@code ingredients[i]} items, {@code remainders[i]} when its item leaves a
     * crafting remainder. Every craft consumes one item from each nonempty grid slot.
     */
    public static void craft(int resultCount, int[] ingredients, boolean[] remainders, int crafts) {
        require(resultCount > 0, "no_result", "the result slot offers nothing");
        for (int i = 0; i < ingredients.length; i++) {
            require(ingredients[i] >= crafts, "missing_ingredients",
                    "a grid slot holds fewer items than the crafts consume");
            require(!remainders[i] || ingredients[i] == 1, "remainder_unsupported",
                    "an ingredient with a crafting remainder shares its slot");
        }
    }

    public static void swap(Slot source, Slot target) {
        source(source);
        require(target.mayPlace(source), "destination_rejects", "hotbar destination forbids source stack");
        require(source.count() <= target.capacity(source), "destination_full",
                "hotbar destination cannot hold source stack");
        if (target.count() > 0) {
            require(target.mayPickup(), "destination_locked", "hotbar slot forbids pickup");
            require(source.mayPlace(target), "source_rejects", "source slot forbids hotbar stack");
            require(target.count() <= source.capacity(target), "source_full", "source slot cannot hold hotbar stack");
        }
    }

    public static void require(boolean condition, String reason, String message) {
        if (!condition) throw new Rejection(reason, message);
    }
}
