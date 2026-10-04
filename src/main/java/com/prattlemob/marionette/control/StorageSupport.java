package com.prattlemob.marionette.control;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft-independent storage analysis (protocol/v1.md, Storage support).
 * A menu qualifies by what its classes override, never by their names: only
 * behavior whose click outcome the mod predicts from the slots' own rules.
 */
public final class StorageSupport {
    public static final String CLICK_BEHAVIOR = "click_behavior";
    public static final String MENU_DATA = "menu_data";
    public static final String SLOT_BEHAVIOR = "slot_behavior";
    public static final String SHARED_SLOTS = "shared_slots";

    /** Menu methods the mod's PICKUP/SWAP/THROW clicks never call: shift-click, validity, close, pick-all, drag. */
    public static final Set<String> MENU_OVERRIDES = Set.of(
            "quickMoveStack/2", "stillValid/1", "removed/1", "canTakeItemForPickAll/2", "canDragTo/1");
    /** Slot rules the preflight evaluates itself, plus appearance. */
    public static final Set<String> SLOT_OVERRIDES = Set.of(
            "mayPlace/1", "mayPickup/1", "getMaxStackSize/0", "getMaxStackSize/1", "isActive/0",
            "getNoItemIcon/0", "isHighlightable/0");
    /** Player-inventory slots may also keep vanilla's equipment hook (armor, offhand). */
    public static final Set<String> PLAYER_SLOT_OVERRIDES = union(SLOT_OVERRIDES, Set.of("setByPlayer/2"));

    /** The facts of one live menu the analysis needs. */
    public interface MenuView {
        Class<?> menuClass();
        int dataSlots();
        int slotCount();
        Class<?> slotClass(int slot);
        boolean playerSlot(int slot);
        Object container(int slot);
        int containerSlot(int slot);
    }

    private final Class<?> menuBase;
    private final Class<?> slotBase;
    private final Map<Class<?>, Set<String>> overrides = new ConcurrentHashMap<>();

    public StorageSupport(Class<?> menuBase, Class<?> slotBase) {
        this.menuBase = menuBase;
        this.slotBase = slotBase;
    }

    /** Every failed rule, sorted; empty when the menu is a storage menu. */
    public List<String> reasons(MenuView menu) {
        Set<String> reasons = new TreeSet<>();
        if (!MENU_OVERRIDES.containsAll(overrides(menu.menuClass(), menuBase))) reasons.add(CLICK_BEHAVIOR);
        if (menu.dataSlots() > 0) reasons.add(MENU_DATA);
        Map<Object, Set<Integer>> positions = new IdentityHashMap<>();
        for (int i = 0; i < menu.slotCount(); i++) {
            Set<String> allowed = menu.playerSlot(i) ? PLAYER_SLOT_OVERRIDES : SLOT_OVERRIDES;
            if (!allowed.containsAll(overrides(menu.slotClass(i), slotBase))) reasons.add(SLOT_BEHAVIOR);
            if (!positions.computeIfAbsent(menu.container(i), c -> new HashSet<>()).add(menu.containerSlot(i))) {
                reasons.add(SHARED_SLOTS);
            }
        }
        return List.copyOf(reasons);
    }

    /** {@code base}'s overridable methods that {@code type} or a class between them redeclares, as name/arity. */
    Set<String> overrides(Class<?> type, Class<?> base) {
        if (!base.isAssignableFrom(type)) throw new IllegalArgumentException(type + " is not a " + base);
        return overrides.computeIfAbsent(type, t -> {
            Set<String> found = new TreeSet<>();
            for (Class<?> c = t; c != base; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    try {
                        Method inherited = base.getDeclaredMethod(method.getName(), method.getParameterTypes());
                        int modifiers = inherited.getModifiers();
                        if (!Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers)) {
                            found.add(method.getName() + "/" + method.getParameterCount());
                        }
                    } catch (NoSuchMethodException notAnOverride) {
                        // A method of the subclass's own; only callable from its overrides.
                    }
                }
            }
            return Set.copyOf(found);
        });
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
