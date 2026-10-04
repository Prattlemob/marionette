package com.prattlemob.marionette.control;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ProtocolError;
import com.prattlemob.marionette.mixin.InventoryHoverAccess;
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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.prattlemob.marionette.control.InventoryRules.require;

/** Client-thread adapter: inspect actual menus and route intent through vanilla clicks. */
public final class InventoryActionApplier {
    private record Step(int slot, Runnable click) {}
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
            reply.accept(Messages.error(e.code(), e.getMessage(), request.id(), request.raw()));
        } catch (IllegalArgumentException e) {
            reply.accept(Messages.error(ErrorCode.INVENTORY_IMPOSSIBLE, e.getMessage(), request.id(), request.raw()));
        }
    }

    private String execute(Minecraft mc, AgentCommand.InventoryAction request, Consumer<String> reply) {
        LocalPlayer player = mc.player;
        if (player != null && request.op().equals("inspect")) {
            // Observation is broader than mutation: any mode, any container screen.
            return Messages.inventoryResult(request.op(), request.id(), describeVisible(player));
        }
        if (!playerAvailable(mc, player)) {
            throw unavailable("inventory requires a live survival/adventure player");
        }
        if (request.op().equals("open")) {
            if (mc.screen != null || player.containerMenu != player.inventoryMenu) {
                throw unavailable("another screen or menu is already open");
            }
            require(player.containerMenu.getCarried().isEmpty(), "cursor is occupied");
            mc.setScreen(new InventoryScreen(player));
        }
        AbstractContainerMenu menu = visibleMenu(mc, player);
        if (menu == null) throw unavailable("no supported container screen is open");
        if (!supported(menu)) throw unavailable("unsupported menu implementation");
        if (request.menu() != null && (!request.menu().type().equals(menuType(menu))
                || request.menu().containerId() != menu.containerId
                || request.menu().stateId() != menu.getStateId())) {
            throw new ProtocolError(ErrorCode.STALE_MENU, "menu identity or server state changed; inspect again");
        }
        if (!request.op().equals("open")) {
            require(menu.getCarried().isEmpty(), "cursor is occupied");
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
                            "source is not wearable armor");
                    int target = resolve(menu, player, new AgentCommand.SlotRef(null, "armor." + equipment.getName()));
                    require(source.getItem().getCount() == 1, "equip requires one armor item");
                    planMove(mc, player, menu, from, target, true, steps);
                }
                case "swap" -> {
                    int target = resolve(menu, player, new AgentCommand.SlotRef(null, "hotbar." + request.hotbar()));
                    require(from != target, "source and destination are the same slot");
                    Slot destination = usableSlot(menu, player, target);
                    InventoryRules.swap(new View(source, player), new View(destination, player));
                    ItemStack beforeSource = source.getItem().copy();
                    ItemStack beforeTarget = destination.getItem().copy();
                    steps.add(new Step(from, () -> {
                        InventoryRules.swap(new View(usableSlot(menu, player, from), player),
                                new View(usableSlot(menu, player, target), player));
                        click(mc, player, menu, from, request.hotbar(), ClickType.SWAP);
                        require(ItemStack.matches(source.getItem(), beforeTarget)
                                && ItemStack.matches(destination.getItem(), beforeSource), "vanilla declined the swap");
                    }));
                }
                case "drop" -> {
                    require(player.canDropItems(), "player cannot drop items");
                    int expected = request.all() ? 0 : source.getItem().getCount() - 1;
                    steps.add(new Step(from, () -> {
                        require(player.canDropItems(), "player cannot drop items");
                        InventoryRules.source(new View(usableSlot(menu, player, from), player));
                        click(mc, player, menu, from, request.all() ? 1 : 0, ClickType.THROW);
                        require(source.getItem().getCount() == expected, "vanilla declined the drop");
                    }));
                }
                default -> throw new IllegalArgumentException("unsupported inventory operation");
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
            for (Step step : steps) step.click().run();
        }
        return Messages.inventoryResult(request.op(), request.id(), describe(menu, player));
    }

    private static void planMove(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu,
                             int from, int to, boolean equip, List<Step> steps) {
        require(from != to, "source and destination are the same slot");
        Slot source = usableSlot(menu, player, from);
        Slot target = usableSlot(menu, player, to);
        if (equip) require(!target.hasItem(), "armor slot is occupied");
        InventoryRules.move(new View(source, player), new View(target, player));
        ItemStack original = source.getItem().copy();
        ItemStack expected = original.copyWithCount(original.getCount() + target.getItem().getCount());
        steps.add(new Step(from, () -> {
            InventoryRules.move(new View(usableSlot(menu, player, from), player),
                    new View(usableSlot(menu, player, to), player));
            click(mc, player, menu, from, 0, ClickType.PICKUP);
            require(source.getItem().isEmpty() && ItemStack.matches(menu.getCarried(), original),
                    "unexpected pickup behavior; inspect cursor before recovery");
        }));
        steps.add(new Step(to, () -> {
            Slot destination = usableSlot(menu, player, to);
            require(destination.mayPlace(menu.getCarried())
                    && (destination.getItem().isEmpty() || destination.mayPickup(player))
                    && destination.getMaxStackSize(menu.getCarried()) >= expected.getCount(),
                    "destination no longer accepts the carried stack");
            click(mc, player, menu, to, 0, ClickType.PICKUP);
            require(menu.getCarried().isEmpty() && ItemStack.matches(target.getItem(), expected),
                    "unexpected placement behavior; inspect cursor before recovery");
        }));
    }

    /** Tick-side only: at most one click per tick, and never into a different screen/menu. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (cursorScreen != mc.screen) clearCursor();
        Pending action = pending;
        if (action == null) return;
        if (mc.player != action.player || mc.screen != action.screen || action.player.containerMenu != action.menu
                || !action.player.isAlive() || action.player.isSpectator() || action.player.hasInfiniteMaterials()
                || mc.gameMode == null || action.screen.width != action.width || action.screen.height != action.height
                || !action.unchanged()) {
            cancel("player, menu, or inventory contents changed");
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
        } catch (IllegalArgumentException e) {
            cancel(e.getMessage());
        }
    }

    private void startLeg(long start) {
        Slot slot = pending.menu.slots.get(pending.steps.get(pending.next).slot());
        motion = new InventoryCursorMotion(cursorX, cursorY,
                pending.screen.getGuiLeft() + slot.x + 8, pending.screen.getGuiTop() + slot.y + 8, start);
    }

    /** Cancel before human input; recover only our own carried stack into its empty source. */
    public void cancel(String reason) {
        cancel(reason, true);
    }

    public void cancel(String reason, boolean recover) {
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
        action.reply.accept(Messages.error(ErrorCode.INVENTORY_CANCELLED, reason,
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

    private static boolean supported(AbstractContainerMenu menu) {
        // Exact classes: arbitrary subclass click behavior is not part of the v1 scope.
        Class<?> type = menu.getClass();
        return type == InventoryMenu.class || type == ChestMenu.class || type == HopperMenu.class
                || type == DispenserMenu.class || type == ShulkerBoxMenu.class;
    }

    private static String menuType(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu ? "minecraft:inventory"
                : BuiltInRegistries.MENU.getKey(menu.getType()).toString();
    }

    private static int resolve(AbstractContainerMenu menu, LocalPlayer player, AgentCommand.SlotRef ref) {
        if (ref.index() != null) {
            require(ref.index() < menu.slots.size(), "slot index is outside the active menu");
            return ref.index();
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (ref.alias().equals(alias(menu.slots.get(i), player))) return i;
        }
        throw new IllegalArgumentException("player slot alias is absent from this menu: " + ref.alias());
    }

    private static Slot usableSlot(AbstractContainerMenu menu, LocalPlayer player, int index) {
        Slot slot = menu.slots.get(index);
        require(slot.isActive(), "slot is inactive");
        require(!(menu instanceof InventoryMenu) || slot.container == player.getInventory(),
                "crafting slots are unsupported");
        require(!slot.getItem().has(DataComponents.BUNDLE_CONTENTS), "bundle clicks are unsupported");
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
        String refusal = !playerAvailable(mc, player) ? "player_unavailable"
                : !supported(menu) ? "unsupported_menu"
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

    private static ProtocolError unavailable(String reason) {
        return new ProtocolError(ErrorCode.INVENTORY_UNAVAILABLE, reason);
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
