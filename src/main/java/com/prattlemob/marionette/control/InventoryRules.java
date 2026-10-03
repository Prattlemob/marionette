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

    public static void source(Slot source) {
        require(source.count() > 0, "source slot is empty");
        require(source.mayPickup(), "source slot forbids pickup");
    }

    public static void move(Slot source, Slot target) {
        source(source);
        require(target.count() == 0 || target.sameItem(source), "destination contains a different item");
        require(target.count() == 0 || target.mayPickup(), "destination forbids pickup");
        require(target.mayPlace(source), "destination forbids this item");
        require(source.count() <= target.capacity(source) - target.count(), "whole stack does not fit");
    }

    public static void swap(Slot source, Slot target) {
        source(source);
        require(target.mayPlace(source) && source.count() <= target.capacity(source),
                "hotbar destination cannot hold source stack");
        if (target.count() > 0) {
            require(target.mayPickup(), "hotbar slot forbids pickup");
            require(source.mayPlace(target) && target.count() <= source.capacity(target),
                    "source slot cannot hold hotbar stack");
        }
    }

    public static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
