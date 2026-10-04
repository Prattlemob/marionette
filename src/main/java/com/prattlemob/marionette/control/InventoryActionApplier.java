package com.prattlemob.marionette.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ProtocolError;
import com.prattlemob.marionette.control.InventoryRules.Rejection;
import com.prattlemob.marionette.mixin.InventoryHoverAccess;
import com.prattlemob.marionette.mixin.MenuDataAccess;
import com.prattlemob.marionette.observation.InventoryJson;
import com.prattlemob.marionette.observation.ItemObservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.prattlemob.marionette.control.InventoryRules.require;

/** Client-thread adapter: inspect actual menus and route intent through vanilla clicks. */
public final class InventoryActionApplier {
    private record Step(int slot, Runnable click) {}
    /** {@code scope} is "player", "storage", or null with the failed rules (protocol/v1.md, Storage support). */
    private record Support(String scope, List<String> reasons) {}
    private static final StorageSupport STORAGE = new StorageSupport(AbstractContainerMenu.class, Slot.class);
    private Pending pending;
    private AbstractContainerScreen<?> cursorScreen;
    private InventoryCursorMotion motion;
    private double cursorX, cursorY;
    private long clickedAt;
    // A broad arrowhead and a two-pixel white stem, with a continuous dark outline.
    // The upper-left pixel is the click hotspot; rows are in GUI pixels.
    private static final float CURSOR_SCALE = 0.7f;
    private static final String[] CURSOR = {
        "#",
        "##",
        "#o#",
        "#oo#",
        "#ooo#",
        "#oooo#",
        "#ooooo#",
        "#oooooo#",
        "#ooooooo#",
        "#oooooooo#",
        "#ooooo#####",
        "#oo#oo#",
        "#o# #oo#",
        "##  #oo#",
        "#    #oo#",
        "     #oo#",
        "      ##"
    };

    private static final class Pending {
        final LocalPlayer player;
        final AbstractContainerScreen<?> screen;
        final AbstractContainerMenu menu;
        final AgentCommand.InventoryAction request;
        final Consumer<String> reply;
        final List<Step> steps;
        final int source;
        final int width, height;
        final ItemStack original;
        List<ItemStack> expectedSlots;
        ItemStack expectedCarried;
        int next;

        Pending(LocalPlayer player, AbstractContainerScreen<?> screen, AgentCommand.InventoryAction request,
                Consumer<String> reply, List<Step> steps, int source) {
            this.player = player;
            this.screen = screen;
            this.menu = screen.getMenu();
            this.request = request;
            this.reply = reply;
            this.steps = steps;
            this.source = source;
            this.width = screen.width;
            this.height = screen.height;
            this.original = menu.slots.get(source).getItem().copy();
            remember();
        }

        void remember() {
            expectedSlots = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            expectedCarried = menu.getCarried().copy();
        }

        boolean unchanged() {
            if (!ItemStack.matches(expectedCarried, menu.getCarried()) || menu.slots.size() != expectedSlots.size()) return false;
            for (int i = 0; i < expectedSlots.size(); i++) {
                if (!ItemStack.matches(expectedSlots.get(i), menu.slots.get(i).getItem())) return false;
            }
            return true;
        }
    }

    public void apply(AgentCommand.InventoryAction request, Consumer<String> reply) {
        try {
            if (pending != null && !request.op().equals("inspect")) {
                throw new ProtocolError(ErrorCode.INVENTORY_BUSY, "an inventory animation is in progress");
            }
            String result = execute(Minecraft.getInstance(), request, reply);
            if (result != null) reply.accept(result);
        } catch (ProtocolError e) {
            reply.accept(Messages.error(e.code(), e.reason(), e.getMessage(), request.id(), request.raw()));
        } catch (Rejection e) {
            reply.accept(Messages.error(ErrorCode.INVENTORY_IMPOSSIBLE, e.reason(), e.getMessage(),
                    request.id(), request.raw()));
        }
    }

    private String execute(Minecraft mc, AgentCommand.InventoryAction request, Consumer<String> reply) {
        LocalPlayer player = mc.player;
        if (player != null && request.op().equals("inspect")) {
            // Observation is broader than mutation: any mode, any container screen.
            return Messages.inventoryResult(request.op(), request.id(), describeVisible(player));
        }
        if (!playerAvailable(mc, player)) {
            throw unavailable("player_unavailable", "inventory requires a live survival/adventure player");
        }
        if (request.op().equals("open")) {
            if (mc.screen != null || player.containerMenu != player.inventoryMenu) {
                throw unavailable("screen_open", "another screen or menu is already open");
            }
            require(player.containerMenu.getCarried().isEmpty(), "cursor_occupied", "cursor is occupied");
            mc.setScreen(new InventoryScreen(player));
        }
        AbstractContainerMenu menu = visibleMenu(mc, player);
        if (menu == null) throw unavailable("no_menu", "no container screen is open");
        Support support = support(menu, player);
        if (support.scope() == null) {
            throw unavailable("unsupported_menu", "menu failed the storage analysis: " + support.reasons());
        }
        if (request.menu() != null && (!request.menu().type().equals(menuType(menu))
                || request.menu().containerId() != menu.containerId
                || request.menu().stateId() != menu.getStateId())) {
            throw new ProtocolError(ErrorCode.STALE_MENU, "menu identity or server state changed; inspect again");
        }
        if (!request.op().equals("open")) {
            require(menu.getCarried().isEmpty(), "cursor_occupied", "cursor is occupied");
            if (request.op().equals("close")) {
                player.closeContainer();
                return Messages.inventoryResult(request.op(), request.id(), describe(visibleMenu(mc, player), player));
            }
            int from = resolve(menu, player, request.from());
            Slot source = usableSlot(menu, player, from);
            InventoryRules.source(new View(source, player));
            List<Step> steps = new ArrayList<>();
            switch (request.op()) {
                case "move" -> planMove(mc, player, menu, from, resolve(menu, player, request.to()), false, steps);
                case "equip" -> {
                    EquipmentSlot equipment = player.getEquipmentSlotForItem(source.getItem());
                    require(equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR,
                            "not_armor", "source is not wearable armor");
                    int target = resolve(menu, player, new AgentCommand.SlotRef(null, "armor." + equipment.getName()));
                    require(source.getItem().getCount() == 1, "armor_count", "equip requires one armor item");
                    planMove(mc, player, menu, from, target, true, steps);
                }
                case "swap" -> {
                    int target = resolve(menu, player, new AgentCommand.SlotRef(null, "hotbar." + request.hotbar()));
                    require(from != target, "same_slot", "source and destination are the same slot");
                    Slot destination = usableSlot(menu, player, target);
                    InventoryRules.swap(new View(source, player), new View(destination, player));
                    ItemStack beforeSource = source.getItem().copy();
                    ItemStack beforeTarget = destination.getItem().copy();
                    steps.add(new Step(from, () -> {
                        InventoryRules.swap(new View(usableSlot(menu, player, from), player),
                                new View(usableSlot(menu, player, target), player));
                        verifiedClick(mc, player, menu, from, request.hotbar(), ClickType.SWAP,
                                Map.of(from, beforeTarget, target, beforeSource), ItemStack.EMPTY);
                    }));
                }
                case "drop" -> {
                    require(player.canDropItems(), "drop_forbidden", "player cannot drop items");
                    ItemStack original = source.getItem().copy();
                    ItemStack remaining = request.all() ? ItemStack.EMPTY
                            : original.copyWithCount(original.getCount() - 1);
                    steps.add(new Step(from, () -> {
                        require(player.canDropItems(), "drop_forbidden", "player cannot drop items");
                        InventoryRules.source(new View(usableSlot(menu, player, from), player));
                        verifiedClick(mc, player, menu, from, request.all() ? 1 : 0, ClickType.THROW,
                                Map.of(from, remaining), ItemStack.EMPTY);
                    }));
                }
                default -> throw new Rejection("unsupported_operation", "unsupported inventory operation");
            }
            if (request.animated()) {
                AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
                if (cursorScreen != screen) {
                    cursorX = screen.width / 2.0;
                    cursorY = screen.height / 2.0;
                }
                cursorScreen = screen;
                pending = new Pending(player, screen, request, reply, List.copyOf(steps), from);
                startLeg(System.nanoTime());
                return null;
            }
            for (int i = 0; i < steps.size(); i++) {
                try {
                    steps.get(i).click().run();
                } catch (Rejection e) {
                    if (i == 0) throw e;
                    // Clicks already happened: whatever the rule, the prediction failed mid-sequence.
                    throw new Rejection("unexpected_click", e.getMessage());
                }
            }
        }
        return Messages.inventoryResult(request.op(), request.id(), describe(menu, player));
    }

    private static void planMove(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu,
                             int from, int to, boolean equip, List<Step> steps) {
        require(from != to, "same_slot", "source and destination are the same slot");
        Slot source = usableSlot(menu, player, from);
        Slot target = usableSlot(menu, player, to);
        if (equip) require(!target.hasItem(), "armor_occupied", "armor slot is occupied");
        InventoryRules.move(new View(source, player), new View(target, player));
        ItemStack original = source.getItem().copy();
        ItemStack expected = original.copyWithCount(original.getCount() + target.getItem().getCount());
        steps.add(new Step(from, () -> {
            InventoryRules.move(new View(usableSlot(menu, player, from), player),
                    new View(usableSlot(menu, player, to), player));
            verifiedClick(mc, player, menu, from, 0, ClickType.PICKUP, Map.of(from, ItemStack.EMPTY), original);
        }));
        steps.add(new Step(to, () -> {
            Slot destination = usableSlot(menu, player, to);
            require(destination.mayPlace(menu.getCarried())
                    && (destination.getItem().isEmpty() || destination.mayPickup(player))
                    && destination.getMaxStackSize(menu.getCarried()) >= expected.getCount(),
                    "unexpected_click", "destination no longer accepts the carried stack");
            verifiedClick(mc, player, menu, to, 0, ClickType.PICKUP, Map.of(to, expected), ItemStack.EMPTY);
        }));
    }

    /**
     * One vanilla click, then a whole-menu check: the listed slots and the cursor hold exactly
     * the predicted stacks and every other slot is unchanged. Anything else stops the sequence;
     * a carried stack stays visible for recovery.
     */
    private static void verifiedClick(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int slot,
                                      int button, ClickType type, Map<Integer, ItemStack> expected,
                                      ItemStack expectedCarried) {
        List<ItemStack> before = menu.slots.stream().map(s -> s.getItem().copy()).toList();
        click(mc, player, menu, slot, button, type);
        require(menu.slots.size() == before.size(), "unexpected_click", "menu layout changed during the click");
        for (int i = 0; i < before.size(); i++) {
            require(ItemStack.matches(expected.getOrDefault(i, before.get(i)), menu.slots.get(i).getItem()),
                    "unexpected_click", "slot " + i + " deviated from the prediction; inspect before recovery");
        }
        require(ItemStack.matches(expectedCarried, menu.getCarried()),
                "unexpected_click", "cursor deviated from the prediction; inspect before recovery");
    }

    /** Tick-side only: at most one click per tick, and never into a different screen/menu. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (cursorScreen != mc.screen) clearCursor();
        Pending action = pending;
        if (action == null) return;
        if (mc.player != action.player || mc.screen != action.screen || action.player.containerMenu != action.menu
                || !action.player.isAlive() || action.player.isSpectator() || action.player.hasInfiniteMaterials()
                || mc.gameMode == null || action.screen.width != action.width || action.screen.height != action.height) {
            cancel("menu_changed", "player, screen or menu changed");
            return;
        }
        if (!action.unchanged()) {
            cancel("contents_changed", "slot or cursor contents changed");
            return;
        }
        if (mc.isPaused() || !motion.ready(System.nanoTime())) return;
        try {
            action.steps.get(action.next).click().run();
            clickedAt = System.nanoTime();
            cursorX = motion.x(clickedAt);
            cursorY = motion.y(clickedAt);
            action.next++;
            action.remember();
            if (action.next == action.steps.size()) {
                pending = null;
                motion = null;
                action.reply.accept(Messages.inventoryResult(action.request.op(), action.request.id(), describe(action.menu, action.player)));
            } else {
                startLeg(clickedAt + 120_000_000L);
            }
        } catch (Rejection e) {
            cancel("unexpected_click", e.getMessage());
        }
    }

    private void startLeg(long start) {
        Slot slot = pending.menu.slots.get(pending.steps.get(pending.next).slot());
        motion = new InventoryCursorMotion(cursorX, cursorY,
                pending.screen.getGuiLeft() + slot.x + 8, pending.screen.getGuiTop() + slot.y + 8, start);
    }

    /** Cancel before human input; recover only our own carried stack into its empty source. */
    public void cancel(String reason, String message) {
        cancel(reason, message, true);
    }

    /** {@code reason} is the wire cancellation reason (protocol/v1.md, Rejection reasons). */
    public void cancel(String reason, String message, boolean recover) {
        Pending action = pending;
        pending = null;
        clearCursor();
        if (action == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (recover && action.next > 0 && mc.player == action.player && action.player.isAlive()
                && !action.player.isSpectator() && !action.player.hasInfiniteMaterials()
                && mc.gameMode != null && mc.screen == action.screen && action.player.containerMenu == action.menu
                && ItemStack.matches(action.menu.getCarried(), action.original)) {
            Slot source = action.menu.slots.get(action.source);
            if (source.isActive() && !source.hasItem() && source.mayPlace(action.original)
                    && source.getMaxStackSize(action.original) >= action.original.getCount()) {
                click(mc, action.player, action.menu, action.source, 0, ClickType.PICKUP);
            }
        }
        action.reply.accept(Messages.error(ErrorCode.INVENTORY_CANCELLED, reason, message,
                action.request.id(), action.request.raw()));
    }

    private void clearCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (cursorScreen != null && mc.screen == cursorScreen) {
            double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
            double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
            InventoryHoverAccess hover = (InventoryHoverAccess) cursorScreen;
            hover.marionette$setHoveredSlot(hover.marionette$findHoveredSlot(x, y));
        }
        cursorScreen = null;
        motion = null;
    }

    public int cursorX(Screen screen, int fallback) {
        return screen == cursorScreen ? (int) Math.round(motion == null ? cursorX : motion.x(System.nanoTime())) : fallback;
    }

    public int cursorY(Screen screen, int fallback) {
        return screen == cursorScreen ? (int) Math.round(motion == null ? cursorY : motion.y(System.nanoTime())) : fallback;
    }

    /** Pixel cursor in GUI coordinates; carried items and hover highlights are rendered by vanilla. */
    public void drawCursor(Screen screen, GuiGraphics graphics) {
        if (screen != cursorScreen) return;
        int x = cursorX(screen, 0), y = cursorY(screen, 0);
        graphics.nextStratum();
        if (System.nanoTime() - clickedAt < 180_000_000L) {
            graphics.fill(x - 10, y - 10, x + 10, y - 9, 0xFF66DDFF);
            graphics.fill(x - 10, y + 9, x + 10, y + 10, 0xFF66DDFF);
            graphics.fill(x - 10, y - 9, x - 9, y + 9, 0xFF66DDFF);
            graphics.fill(x + 9, y - 9, x + 10, y + 9, 0xFF66DDFF);
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(CURSOR_SCALE, CURSOR_SCALE);
        for (int row = 0; row < CURSOR.length; row++) {
            String pixels = CURSOR[row];
            for (int start = 0; start < pixels.length();) {
                char color = pixels.charAt(start);
                int end = start + 1;
                while (end < pixels.length() && pixels.charAt(end) == color) end++;
                if (color != ' ') {
                    graphics.fill(start, row, end, row + 1,
                            color == '#' ? 0xFF111111 : 0xFFFFFFFF);
                }
                start = end;
            }
        }
        graphics.pose().popMatrix();
    }

    private static void click(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu,
                              int slot, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player);
    }

    private static AbstractContainerMenu visibleMenu(Minecraft mc, LocalPlayer player) {
        return mc.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == player.containerMenu ? player.containerMenu : null;
    }

    /** The player's own menu, or the structural storage analysis of any other menu; never its name. */
    private static Support support(AbstractContainerMenu menu, LocalPlayer player) {
        if (menu == player.inventoryMenu) return new Support("player", List.of());
        List<String> reasons = STORAGE.reasons(new StorageSupport.MenuView() {
            public Class<?> menuClass() { return menu.getClass(); }
            public int dataSlots() { return ((MenuDataAccess) menu).marionette$dataSlots().size(); }
            public int slotCount() { return menu.slots.size(); }
            public Class<?> slotClass(int slot) { return menu.slots.get(slot).getClass(); }
            public boolean playerSlot(int slot) { return menu.slots.get(slot).container == player.getInventory(); }
            public Object container(int slot) { return menu.slots.get(slot).container; }
            public int containerSlot(int slot) { return menu.slots.get(slot).getContainerSlot(); }
        });
        return new Support(reasons.isEmpty() ? "storage" : null, reasons);
    }

    private static String menuType(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu ? "minecraft:inventory"
                : BuiltInRegistries.MENU.getKey(menu.getType()).toString();
    }

    private static int resolve(AbstractContainerMenu menu, LocalPlayer player, AgentCommand.SlotRef ref) {
        if (ref.index() != null) {
            require(ref.index() < menu.slots.size(), "slot_out_of_range", "slot index is outside the active menu");
            return ref.index();
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (ref.alias().equals(alias(menu.slots.get(i), player))) return i;
        }
        throw new Rejection("alias_absent", "player slot alias is absent from this menu: " + ref.alias());
    }

    private static Slot usableSlot(AbstractContainerMenu menu, LocalPlayer player, int index) {
        Slot slot = menu.slots.get(index);
        require(slot.isActive(), "slot_refused", "slot is inactive");
        require(!(menu instanceof InventoryMenu) || slot.container == player.getInventory(),
                "slot_refused", "crafting slots are unsupported");
        require(!slot.getItem().has(DataComponents.BUNDLE_CONTENTS), "slot_refused", "bundle clicks are unsupported");
        return slot;
    }

    private static String alias(Slot slot, LocalPlayer player) {
        if (slot.container != player.getInventory()) return null;
        int index = slot.getContainerSlot();
        if (index >= 0 && index < Inventory.SELECTION_SIZE) return "hotbar." + index;
        if (index >= Inventory.SELECTION_SIZE && index < Inventory.INVENTORY_SIZE) {
            return "main." + (index - Inventory.SELECTION_SIZE);
        }
        EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(index);
        if (equipment == EquipmentSlot.OFFHAND) return "offhand";
        if (equipment != null && equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            return "armor." + equipment.getName();
        }
        return null;
    }

    /** The visible container menu's descriptor (protocol/v1.md, Inventory section), or null. */
    public JsonObject describeVisible(LocalPlayer player) {
        return describe(visibleMenu(Minecraft.getInstance(), player), player);
    }

    /** Unbounded descriptor; the caller applies the size bound for its message. */
    private JsonObject describeRaw(AbstractContainerMenu menu, LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        Support support = support(menu, player);
        String refusal = !playerAvailable(mc, player) ? "player_unavailable"
                : support.scope() == null ? "unsupported_menu"
                : pending != null ? "busy"
                : !menu.getCarried().isEmpty() ? "cursor_occupied" : null;
        boolean inScope = refusal == null || refusal.equals("busy") || refusal.equals("cursor_occupied");
        JsonObject result = new JsonObject();
        result.addProperty("type", menuType(menu));
        result.addProperty("containerId", menu.containerId);
        result.addProperty("stateId", menu.getStateId());
        result.addProperty("slotCount", menu.slots.size());
        JsonArray slots = new JsonArray();
        boolean armor = false;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            JsonObject entry = ItemObservation.stack(slot.getItem());
            entry.addProperty("slot", i);
            String alias = alias(slot, player);
            if (alias != null) entry.addProperty("alias", alias);
            armor |= alias != null && alias.startsWith("armor.");
            String refused = !inScope ? null
                    : menu instanceof InventoryMenu && slot.container != player.getInventory() ? "crafting"
                    : !slot.isActive() ? "inactive"
                    : slot.getItem().has(DataComponents.BUNDLE_CONTENTS) ? "bundle" : null;
            if (refused != null) entry.addProperty("refused", refused);
            slots.add(entry);
        }
        result.add("slots", slots);
        result.add("carried", ItemObservation.stack(menu.getCarried()));
        JsonArray operations = new JsonArray();
        if (refusal == null) {
            operations.add("move");
            operations.add("swap");
            if (armor) operations.add("equip");
            operations.add("drop");
            operations.add("close");
        }
        result.add("operations", operations);
        if (refusal == null) result.add("refusal", JsonNull.INSTANCE);
        else result.addProperty("refusal", refusal);
        JsonObject supportJson = new JsonObject();
        if (support.scope() == null) supportJson.add("scope", JsonNull.INSTANCE);
        else supportJson.addProperty("scope", support.scope());
        JsonArray reasons = new JsonArray();
        support.reasons().forEach(reasons::add);
        supportJson.add("reasons", reasons);
        result.add("support", supportJson);
        return result;
    }

    private JsonObject describe(AbstractContainerMenu menu, LocalPlayer player) {
        return menu == null ? null : InventoryJson.boundMenu(describeRaw(menu, player));
    }

    /** For the observation section, which bounds the whole section instead. */
    public JsonObject describeVisibleUnbounded(LocalPlayer player) {
        AbstractContainerMenu menu = visibleMenu(Minecraft.getInstance(), player);
        return menu == null ? null : describeRaw(menu, player);
    }

    private static boolean playerAvailable(Minecraft mc, LocalPlayer player) {
        return player != null && mc.gameMode != null && player.isAlive() && !player.isSpectator()
                && !player.hasInfiniteMaterials();
    }

    private static ProtocolError unavailable(String reason, String message) {
        return ProtocolError.withReason(ErrorCode.INVENTORY_UNAVAILABLE, reason, message);
    }

    private record View(Slot slot, LocalPlayer player) implements InventoryRules.Slot {
        public int count() { return slot.getItem().getCount(); }
        public boolean mayPickup() { return slot.mayPickup(player); }
        public boolean mayPlace(InventoryRules.Slot source) { return slot.mayPlace(((View) source).slot.getItem()); }
        public int capacity(InventoryRules.Slot source) { return slot.getMaxStackSize(((View) source).slot.getItem()); }
        public boolean sameItem(InventoryRules.Slot other) {
            return ItemStack.isSameItemSameComponents(slot.getItem(), ((View) other).slot.getItem());
        }
    }
}
