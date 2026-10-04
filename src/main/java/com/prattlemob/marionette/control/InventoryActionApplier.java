package com.prattlemob.marionette.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ProtocolError;
import com.prattlemob.marionette.control.InventoryRules.Rejection;
import com.prattlemob.marionette.mixin.FurnaceMenuAccess;
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
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.prattlemob.marionette.control.InventoryRules.require;

/** Client-thread adapter: inspect actual menus and route intent through vanilla clicks. */
public final class InventoryActionApplier {
    /** One click; {@code ready} false defers it to a later tick (a craft awaiting the server's result). */
    private record Step(int slot, Runnable click, BooleanSupplier ready) {
        Step(int slot, Runnable click) {
            this(slot, click, () -> true);
        }
    }
    /**
     * {@code scope} is "player", "storage", "crafting", "processing", or null with the failed rules
     * (protocol/v1.md, Storage support; Crafting and processing menus). {@code crafting} is set for
     * menus with a crafting grid, {@code processing} for the furnace base.
     */
    private record Support(String scope, List<String> reasons, Crafting crafting, boolean processing) {
        /** Slots the server changes on its own, which only their own clicks check. */
        Set<Integer> workstationSlots() {
            if (processing) return Set.of(FURNACE_INPUT, FURNACE_FUEL, FURNACE_RESULT);
            return crafting == null ? Set.of() : Set.of(crafting.result());
        }
    }
    private record Crafting(int result, List<Integer> grid, int width, int height) {}
    private static final int FURNACE_INPUT = AbstractFurnaceMenu.INGREDIENT_SLOT;
    private static final int FURNACE_FUEL = AbstractFurnaceMenu.FUEL_SLOT;
    private static final int FURNACE_RESULT = AbstractFurnaceMenu.RESULT_SLOT;
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

    /** Ticks a craft waits for the server to re-offer its result (protocol/v1.md, craft). */
    static final int RESULT_WAIT_TICKS = 40;

    /** The stacks an action's clicks picked up; only its own stack is ever returned to the source. */
    private static final class Carry {
        ItemStack picked;
        ItemStack result;
    }

    private static final class Pending {
        final LocalPlayer player;
        final AbstractContainerScreen<?> screen;
        final AbstractContainerMenu menu;
        final AgentCommand.InventoryAction request;
        final Consumer<String> reply;
        final List<Step> steps;
        final int source;
        final Carry carry;
        final Set<Integer> workstationSlots;
        final boolean animated;
        final int width, height;
        List<ItemStack> expectedSlots;
        ItemStack expectedCarried;
        int next;
        int waited;

        Pending(LocalPlayer player, AbstractContainerScreen<?> screen, AgentCommand.InventoryAction request,
                Consumer<String> reply, List<Step> steps, int next, int source, Carry carry,
                Set<Integer> workstationSlots) {
            this.player = player;
            this.screen = screen;
            this.menu = screen.getMenu();
            this.request = request;
            this.reply = reply;
            this.steps = steps;
            this.next = next;
            this.source = source;
            this.carry = carry;
            this.workstationSlots = workstationSlots;
            this.animated = request.animated();
            this.width = screen.width;
            this.height = screen.height;
            remember();
        }

        void remember() {
            expectedSlots = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            expectedCarried = menu.getCarried().copy();
        }

        /** Workstation result/input/fuel slots change with the server's processing; their own clicks re-check them. */
        boolean unchanged() {
            if (!ItemStack.matches(expectedCarried, menu.getCarried()) || menu.slots.size() != expectedSlots.size()) return false;
            for (int i = 0; i < expectedSlots.size(); i++) {
                if (workstationSlots.contains(i)) continue;
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
            List<Step> steps = new ArrayList<>();
            Carry carry = new Carry();
            int from = -1;
            if (request.op().equals("craft")) {
                planCraft(mc, player, menu, support, resolve(menu, player, request.to()),
                        request.count() == null ? 1 : request.count(), carry, steps);
            } else {
                from = resolve(menu, player, request.from());
                Slot source = usableSlot(menu, player, from, support);
                InventoryRules.source(new View(source, player));
                planSourced(mc, player, menu, support, request, from, source, carry, steps);
            }
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
            if (request.animated()) {
                if (cursorScreen != screen) {
                    cursorX = screen.width / 2.0;
                    cursorY = screen.height / 2.0;
                }
                cursorScreen = screen;
                pending = new Pending(player, screen, request, reply, List.copyOf(steps), 0, from, carry,
                        support.workstationSlots());
                startLeg(System.nanoTime());
                return null;
            }
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (!step.ready().getAsBoolean()) {
                    // A later craft waits for the server's re-offered result: continue on the tick.
                    pending = new Pending(player, screen, request, reply, List.copyOf(steps), i, from, carry,
                            support.workstationSlots());
                    return null;
                }
                try {
                    step.click().run();
                } catch (Rejection e) {
                    if (i == 0) throw e;
                    // Clicks already happened: whatever the rule, the prediction failed mid-sequence.
                    throw new Rejection("unexpected_click", e.getMessage());
                }
            }
        }
        return Messages.inventoryResult(request.op(), request.id(), describe(menu, player));
    }

    /** Operations with a source slot: move (optionally counted), equip, swap, drop. */
    private static void planSourced(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Support support,
                                    AgentCommand.InventoryAction request, int from, Slot source, Carry carry,
                                    List<Step> steps) {
        switch (request.op()) {
            case "move" -> {
                int to = resolve(menu, player, request.to());
                require(from != to, "same_slot", "source and destination are the same slot");
                Slot target = usableSlot(menu, player, to, support);
                int count = request.count() == null ? source.getItem().getCount() : request.count();
                InventoryRules.moveCount(new View(source, player), new View(target, player), count,
                        !source.allowModification(player));
                planMove(mc, player, menu, support, from, to, count, carry, steps);
            }
            case "equip" -> {
                EquipmentSlot equipment = player.getEquipmentSlotForItem(source.getItem());
                require(equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR,
                        "not_armor", "source is not wearable armor");
                int target = resolve(menu, player, new AgentCommand.SlotRef(null, "armor." + equipment.getName()));
                require(source.getItem().getCount() == 1, "armor_count", "equip requires one armor item");
                require(from != target, "same_slot", "source and destination are the same slot");
                Slot armor = usableSlot(menu, player, target, support);
                require(!armor.hasItem(), "armor_occupied", "armor slot is occupied");
                InventoryRules.move(new View(source, player), new View(armor, player));
                planMove(mc, player, menu, support, from, target, 1, carry, steps);
            }
            case "swap" -> {
                int target = resolve(menu, player, new AgentCommand.SlotRef(null, "hotbar." + request.hotbar()));
                require(from != target, "same_slot", "source and destination are the same slot");
                Slot destination = usableSlot(menu, player, target, support);
                InventoryRules.swap(new View(source, player), new View(destination, player));
                ItemStack beforeSource = source.getItem().copy();
                ItemStack beforeTarget = destination.getItem().copy();
                steps.add(new Step(from, () -> {
                    InventoryRules.swap(new View(usableSlot(menu, player, from, support), player),
                            new View(usableSlot(menu, player, target, support), player));
                    verifiedClick(mc, player, menu, from, request.hotbar(), ClickType.SWAP,
                            Map.of(from, beforeTarget, target, beforeSource), ItemStack.EMPTY);
                }));
            }
            case "drop" -> {
                require(player.canDropItems(), "drop_forbidden", "player cannot drop items");
                require(request.all() || source.getItem().getCount() == 1 || source.allowModification(player),
                        "whole_stack_only", "source slot gives up only whole stacks");
                ItemStack original = source.getItem().copy();
                ItemStack remaining = request.all() ? ItemStack.EMPTY
                        : original.copyWithCount(original.getCount() - 1);
                steps.add(new Step(from, () -> {
                    require(player.canDropItems(), "drop_forbidden", "player cannot drop items");
                    InventoryRules.source(new View(usableSlot(menu, player, from, support), player));
                    verifiedClick(mc, player, menu, from, request.all() ? 1 : 0, ClickType.THROW,
                            Map.of(from, remaining), ItemStack.EMPTY);
                }));
            }
            default -> throw new Rejection("unsupported_operation", "unsupported inventory operation");
        }
    }

    /**
     * Whole stack: pickup and place. Fewer items: pickup, {@code count} single placements and a
     * return of the rest. Each click predicts from the slots as they are when it runs.
     */
    private static void planMove(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Support support,
                                 int from, int to, int count, Carry carry, List<Step> steps) {
        boolean whole = count == menu.slots.get(from).getItem().getCount();
        steps.add(new Step(from, () -> pickup(mc, player, menu, support, from, carry)));
        if (whole) {
            steps.add(new Step(to, () -> place(mc, player, menu, support, to, false)));
            return;
        }
        for (int i = 0; i < count; i++) {
            steps.add(new Step(to, () -> place(mc, player, menu, support, to, true)));
        }
        steps.add(new Step(from, () -> place(mc, player, menu, support, from, false)));
    }

    /** Primary click on a nonempty slot with an empty cursor: the whole stack moves to the cursor. */
    private static void pickup(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Support support,
                               int slot, Carry carry) {
        require(menu.getCarried().isEmpty(), "unexpected_click", "cursor is no longer empty");
        Slot source = usableSlot(menu, player, slot, support);
        InventoryRules.source(new View(source, player));
        ItemStack stack = source.getItem().copy();
        verifiedClick(mc, player, menu, slot, 0, ClickType.PICKUP, Map.of(slot, ItemStack.EMPTY), stack);
        carry.picked = stack;
    }

    /** Primary click places the whole cursor stack, secondary one item, onto an empty or matching slot. */
    private static void place(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Support support,
                              int slot, boolean one) {
        ItemStack carried = menu.getCarried().copy();
        Slot destination = usableSlot(menu, player, slot, support);
        ItemStack held = destination.getItem();
        int placed = one ? 1 : carried.getCount();
        require(!carried.isEmpty() && destination.mayPlace(carried)
                && (held.isEmpty() || ItemStack.isSameItemSameComponents(held, carried) && destination.mayPickup(player))
                && destination.getMaxStackSize(carried) >= held.getCount() + placed,
                "unexpected_click", "destination no longer accepts the carried stack");
        verifiedClick(mc, player, menu, slot, one ? 1 : 0, ClickType.PICKUP,
                Map.of(slot, carried.copyWithCount(held.getCount() + placed)),
                carried.copyWithCount(carried.getCount() - placed));
    }

    /**
     * {@code crafts} times: take the offered result (the cursor receives it, each nonempty grid slot
     * loses one item, remainders stay) and place it on the destination. Later crafts wait until the
     * server re-offers exactly the first result.
     */
    private static void planCraft(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Support support,
                                  int to, int crafts, Carry carry, List<Step> steps) {
        Crafting crafting = support.crafting();
        require(crafting != null, "not_crafting", "menu has no crafting grid");
        require(to != crafting.result() && !crafting.grid().contains(to), "destination_rejects",
                "crafted items cannot go into the crafting grid or result slot");
        Slot destination = usableSlot(menu, player, to, support);
        Slot result = menu.slots.get(crafting.result());
        List<Integer> filled = crafting.grid().stream().filter(i -> menu.slots.get(i).hasItem()).toList();
        int[] counts = filled.stream().mapToInt(i -> menu.slots.get(i).getItem().getCount()).toArray();
        boolean[] remainders = new boolean[filled.size()];
        for (int i = 0; i < remainders.length; i++) {
            remainders[i] = !menu.slots.get(filled.get(i)).getItem().getCraftingRemainder().isEmpty();
        }
        InventoryRules.craft(result.getItem().getCount(), counts, remainders, crafts);
        InventoryRules.receive(new View(destination, player), new View(result, player),
                crafts * result.getItem().getCount());
        for (int k = 0; k < crafts; k++) {
            boolean first = k == 0;
            steps.add(new Step(crafting.result(), () -> take(mc, player, menu, crafting, carry), () -> {
                if (first) return true;
                ItemStack offered = result.getItem();
                if (offered.isEmpty()) return false;
                require(ItemStack.matches(offered, carry.result), "result_changed",
                        "the server offered a different result");
                return true;
            }));
            steps.add(new Step(to, () -> place(mc, player, menu, support, to, false)));
        }
    }

    /** Primary click on the result slot, predicted as vanilla's client-side craft. */
    private static void take(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Crafting crafting,
                             Carry carry) {
        require(menu.getCarried().isEmpty(), "unexpected_click", "cursor is no longer empty");
        ItemStack offered = menu.slots.get(crafting.result()).getItem().copy();
        require(!offered.isEmpty(), "no_result", "the result slot offers nothing");
        Map<Integer, ItemStack> expected = new HashMap<>();
        expected.put(crafting.result(), ItemStack.EMPTY);
        for (int index : crafting.grid()) {
            ItemStack ingredient = menu.slots.get(index).getItem();
            if (ingredient.isEmpty()) continue;
            ItemStack remainder = ingredient.getCraftingRemainder();
            require(remainder.isEmpty() || ingredient.getCount() == 1, "remainder_unsupported",
                    "an ingredient with a crafting remainder shares its slot");
            expected.put(index, ingredient.getCount() == 1 ? remainder.copy()
                    : ingredient.copyWithCount(ingredient.getCount() - 1));
        }
        verifiedClick(mc, player, menu, crafting.result(), 0, ClickType.PICKUP, expected, offered);
        if (carry.result == null) carry.result = offered;
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

    /**
     * Tick-side only, never into a different screen/menu. Animated: at most one click per tick.
     * Otherwise every ready step runs at once; a step that is not ready (a craft awaiting the
     * server's result) waits up to {@link #RESULT_WAIT_TICKS}.
     */
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
        if (mc.isPaused() || action.animated && !motion.ready(System.nanoTime())) return;
        try {
            do {
                Step step = action.steps.get(action.next);
                if (!step.ready().getAsBoolean()) {
                    if (++action.waited > RESULT_WAIT_TICKS) {
                        throw new Rejection("result_changed", "the server did not re-offer the result");
                    }
                    return;
                }
                action.waited = 0;
                step.click().run();
                action.next++;
                action.remember();
            } while (!action.animated && action.next < action.steps.size());
            if (action.animated) {
                clickedAt = System.nanoTime();
                cursorX = motion.x(clickedAt);
                cursorY = motion.y(clickedAt);
            }
            if (action.next == action.steps.size()) {
                pending = null;
                motion = null;
                action.reply.accept(Messages.inventoryResult(action.request.op(), action.request.id(), describe(action.menu, action.player)));
            } else if (action.animated) {
                boolean sameSlot = action.steps.get(action.next).slot() == action.steps.get(action.next - 1).slot();
                startLeg(clickedAt + (sameSlot ? 50_000_000L : 120_000_000L));
            }
        } catch (Rejection e) {
            cancel(e.reason().equals("result_changed") ? "result_changed" : "unexpected_click", e.getMessage());
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
        ItemStack carried = action.menu.getCarried();
        ItemStack picked = action.carry.picked;
        if (recover && action.source >= 0 && picked != null && mc.player == action.player && action.player.isAlive()
                && !action.player.isSpectator() && !action.player.hasInfiniteMaterials()
                && mc.gameMode != null && mc.screen == action.screen && action.player.containerMenu == action.menu
                && !carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, picked)
                && carried.getCount() <= picked.getCount()) {
            // Our own (possibly partly placed) stack returns only into its empty, accepting source.
            Slot source = action.menu.slots.get(action.source);
            if (source.isActive() && !source.hasItem() && source.mayPlace(carried)
                    && source.getMaxStackSize(carried) >= carried.getCount()) {
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

    /**
     * The player's own menu, the workstation analysis of a vanilla crafting or furnace menu (or a
     * subclass), or the storage analysis of any other menu; never its name.
     */
    private static Support support(AbstractContainerMenu menu, LocalPlayer player) {
        if (menu == player.inventoryMenu) return new Support("player", List.of(), crafting(player.inventoryMenu), false);
        StorageSupport.MenuView view = view(menu, player);
        if (menu instanceof CraftingMenu craftingMenu) {
            Crafting crafting = crafting(craftingMenu);
            Map<Integer, Class<?>> roles = new HashMap<>();
            roles.put(crafting.result(), ResultSlot.class);
            crafting.grid().forEach(i -> roles.put(i, Slot.class));
            List<String> reasons = STORAGE.workstationReasons(view,
                    new StorageSupport.Workstation(CraftingMenu.class, 0, roles));
            return reasons.isEmpty() ? new Support("crafting", reasons, crafting, false)
                    : new Support(null, reasons, null, false);
        }
        if (menu instanceof AbstractFurnaceMenu) {
            List<String> reasons = STORAGE.workstationReasons(view, new StorageSupport.Workstation(
                    AbstractFurnaceMenu.class, AbstractFurnaceMenu.DATA_COUNT, Map.of(FURNACE_INPUT, Slot.class,
                    FURNACE_FUEL, FurnaceFuelSlot.class, FURNACE_RESULT, FurnaceResultSlot.class)));
            return new Support(reasons.isEmpty() ? "processing" : null, reasons, null, reasons.isEmpty());
        }
        List<String> reasons = STORAGE.reasons(view);
        return new Support(reasons.isEmpty() ? "storage" : null, reasons, null, false);
    }

    private static Crafting crafting(AbstractCraftingMenu menu) {
        return new Crafting(menu.getResultSlot().index, menu.getInputGridSlots().stream().map(slot -> slot.index).toList(),
                menu.getGridWidth(), menu.getGridHeight());
    }

    private static StorageSupport.MenuView view(AbstractContainerMenu menu, LocalPlayer player) {
        return new StorageSupport.MenuView() {
            public Class<?> menuClass() { return menu.getClass(); }
            public int dataSlots() { return ((MenuDataAccess) menu).marionette$dataSlots().size(); }
            public int slotCount() { return menu.slots.size(); }
            public Class<?> slotClass(int slot) { return menu.slots.get(slot).getClass(); }
            public boolean playerSlot(int slot) { return menu.slots.get(slot).container == player.getInventory(); }
            public Object container(int slot) { return menu.slots.get(slot).container; }
            public int containerSlot(int slot) { return menu.slots.get(slot).getContainerSlot(); }
        };
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

    private static Slot usableSlot(AbstractContainerMenu menu, LocalPlayer player, int index, Support support) {
        Slot slot = menu.slots.get(index);
        require(slot.isActive(), "slot_refused", "slot is inactive");
        require(support.crafting() == null || index != support.crafting().result(),
                "slot_refused", "only craft takes from the crafting result slot");
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
                    : support.crafting() != null && i == support.crafting().result() ? "result"
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
            if (support.crafting() != null) operations.add("craft");
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
        if (support.crafting() != null) {
            JsonObject crafting = new JsonObject();
            crafting.addProperty("result", support.crafting().result());
            JsonArray grid = new JsonArray();
            support.crafting().grid().forEach(grid::add);
            crafting.add("grid", grid);
            crafting.addProperty("width", support.crafting().width());
            crafting.addProperty("height", support.crafting().height());
            result.add("crafting", crafting);
        }
        if (support.processing()) result.add("processing", processing((AbstractFurnaceMenu) menu));
        return result;
    }

    /** Furnace-base slot roles and synchronized data (protocol/v1.md, Processing descriptor). */
    private static JsonObject processing(AbstractFurnaceMenu menu) {
        List<DataSlot> data = ((MenuDataAccess) menu).marionette$dataSlots();
        int burnTime = data.get(0).get(), cookTime = data.get(2).get();
        JsonObject processing = new JsonObject();
        processing.addProperty("kind", "furnace");
        processing.addProperty("input", FURNACE_INPUT);
        processing.addProperty("fuel", FURNACE_FUEL);
        processing.addProperty("result", FURNACE_RESULT);
        processing.addProperty("burnTime", burnTime);
        processing.addProperty("burnDuration", data.get(1).get());
        processing.addProperty("cookTime", cookTime);
        processing.addProperty("cookDuration", data.get(3).get());
        processing.addProperty("lit", burnTime > 0);
        ItemStack input = menu.slots.get(FURNACE_INPUT).getItem();
        if (input.isEmpty()) processing.add("smeltable", JsonNull.INSTANCE);
        else processing.addProperty("smeltable", ((FurnaceMenuAccess) menu).marionette$canSmelt(input));
        return processing;
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
