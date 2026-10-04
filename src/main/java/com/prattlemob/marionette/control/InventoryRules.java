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
        require(target.count() == 0 || target.sameItem(source), "destination_mismatch",
                "destination contains a different item");
        require(target.count() == 0 || target.mayPickup(), "destination_locked", "destination forbids pickup");
        require(target.mayPlace(source), "destination_rejects", "destination forbids this item");
        require(source.count() <= target.capacity(source) - target.count(), "destination_full",
                "whole stack does not fit");
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
