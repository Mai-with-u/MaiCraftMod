// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.PageSwaps;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ExternalKeyWrappers;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.compat.ae2.inscriber.Ae2Inscribers;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Patterns;
import org.maiwithu.maicraft.compat.ae2.Ae2TerminalMenu;
import org.maiwithu.maicraft.compat.ae2.Ae2Terminals;
import org.maiwithu.maicraft.compat.ae2.Ae2Transforms;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads;
import org.maiwithu.maicraft.compat.ae2.inscriber.Ae2Inscribers;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Patterns;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2ToolReads;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 离线测试共用替身：提供可脚本化的 AE2 网格、菜单发包、部件读写端与已打开菜单对象，
 * 不依赖真实 Minecraft 客户端实例、不用反射、不用 Unsafe。
 */
final class PartTestFakes {

    private PartTestFakes() {
    }

    static Ae2Compat compat(Ae2Grid grid, Ae2MenuSlots menuSlots) {
        return compat(grid, menuSlots, ExternalKeyWrappers.NONE);
    }

    static Ae2Compat compat(Ae2Grid grid, Ae2MenuSlots menuSlots, ExternalKeyWrappers externalKeys) {
        Ae2Terminals noTerminals = new Ae2Terminals() {
            @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) { return List.of(); }
            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) { return false; }
        };
        Ae2TerminalMenu noMenu = new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                                               Direction side) {
                return false;
            }
            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public void takeStack(AbstractContainerMenu menu, long serial) {}
            @Override public void takeOne(AbstractContainerMenu menu, long serial) {}
            @Override public void putBack(AbstractContainerMenu menu) {}
        };
        return new Ae2Compat(noTerminals, noMenu, grid, Ae2Patterns.NONE, Ae2Transforms.NONE, Ae2Inscribers.NONE,
                menuSlots, Ae2MachineReads.NONE, Ae2PartControl.NONE, Ae2ToolReads.NONE, Ae2AdvancedReads.NONE,
                externalKeys);
    }

    static Ae2Grid cableGrid(BlockPos pos, Map<Direction, Ae2Grid.PartView> parts) {
        Ae2Grid.GridCell cell = new Ae2Grid.GridCell(true, Optional.of("ae2:fluix_glass_cable"), Set.of(), parts,
                Optional.of(true), Optional.of(true), Optional.empty());
        return new Ae2Grid() {
            @Override public Optional<GridCell> cell(ClientLevel level, BlockPos at) {
                return at.equals(pos) ? Optional.of(cell) : Optional.empty();
            }
            @Override public boolean partItem(String itemId) { return false; }
            @Override public boolean cableItem(String itemId) { return false; }
        };
    }

    static PlayerServices services(Supplier<PlayerContext> context, ServerReads serverReads) {
        Protection nobodyOwns = new Protection((dim, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(context, (target, permissions) -> noopAction("靠近"), new Interactions(null),
                itemId -> Optional.of(noopAction("换主手")), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), serverReads, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    static Action noopAction(String label) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return label; }
        };
    }

    static TickContext dummyTick() {
        return TickContext.of(0L, null);
    }

    /** 立刻返回指定已打开菜单的菜单打开替身。 */
    static MenuOpening immediateOpening(OpenedMenu opened) {
        return new MenuOpening() {
            private boolean done;
            @Override public Optional<OpenedMenu> opened() { return done ? Optional.of(opened) : Optional.empty(); }
            @Override public ActionStatus tick(TickContext tick) {
                done = true;
                return ActionStatus.done();
            }
            @Override public String describe() { return "打开替身界面"; }
        };
    }

    /** 可模拟槽位点击、光标、翻页与实时快照的菜单与控制替身。 */
    static class ScriptedSession implements Ae2MenuSlots, Ae2PartControl, OpenedMenu {

        String menuTypeId;
        final Map<String, String> settings = new LinkedHashMap<>();
        final Map<String, Boolean> keyTypes = new LinkedHashMap<>();
        String connectedTo;
        Long reportingValue;
        Integer priority;
        Integer stockInitial;
        Integer stockMax;
        boolean hasRedstoneCard;
        Integer wirelessBoosters;
        final List<MenuSlotEntry> configSlots = new ArrayList<>();
        final List<MenuSlotEntry> upgradeSlots = new ArrayList<>();
        final List<MenuSlotEntry> storageSlots = new ArrayList<>();
        final List<MenuSlotEntry> cellSlots = new ArrayList<>();
        final List<MenuSlotEntry> inputSlots = new ArrayList<>();
        final List<MenuSlotEntry> outputSlots = new ArrayList<>();

        final Map<Integer, SlotSnapshot> playerSlots = new LinkedHashMap<>();
        SlotSnapshot cursor = SlotSnapshot.empty();
        final List<String> log = new ArrayList<>();
        final Map<String, List<String>> cycleOrders = new LinkedHashMap<>();
        final Map<Direction, ClientPartReading> clientParts = new LinkedHashMap<>();
        int pendingStockSlot = -1;
        String parentMenuId;
        boolean closed;
        boolean swapFollowed;

        ScriptedSession(String menuTypeId) {
            this.menuTypeId = menuTypeId;
            this.parentMenuId = menuTypeId;
            for (int i = 100; i < 136; i++) {
                playerSlots.put(i, SlotSnapshot.empty());
            }
        }

        void putPlayerItem(int menuSlot, ItemStack stack) {
            playerSlots.put(menuSlot, SlotSnapshot.of(stack));
        }

        // --- Ae2MenuSlots ---
        @Override public boolean isAe2MenuOpen() { return !closed; }
        @Override public List<Integer> slotsOf(String semanticId) { return List.of(); }

        @Override public boolean sendConfigButton(String settingName, boolean backwards) {
            log.add("config:" + settingName);
            List<String> order = cycleOrders.get(settingName);
            if (order == null || order.isEmpty()) return true;
            String localKey = localKeyOf(settingName);
            String cur = settings.getOrDefault(localKey, order.getFirst());
            int idx = order.indexOf(cur);
            int next = (idx + 1) % order.size();
            settings.put(localKey, order.get(next));
            return true;
        }

        private static String localKeyOf(String settingName) {
            return switch (settingName) {
                case "redstone_controlled", "redstone_emitter" -> "redstone_mode";
                case "place_block" -> "place_mode";
                default -> settingName;
            };
        }

        @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) {
            String itemId = stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            log.add("setFilter:" + slotIndex + ":" + (itemId == null ? "empty" : itemId));
            for (int i = 0; i < configSlots.size(); i++) {
                MenuSlotEntry entry = configSlots.get(i);
                if (entry.menuIndex() == slotIndex) {
                    if (!entry.enabled()) return false;
                    configSlots.set(i, new MenuSlotEntry(slotIndex, entry.localIndex(), true, itemId,
                            itemId == null ? 0L : 1L));
                    return true;
                }
            }
            return false;
        }

        @Override public boolean sendNoArgAction(String actionName) {
            log.add("action:" + actionName);
            if ("nextCopyMode".equals(actionName)) {
                String cur = settings.getOrDefault("copy_mode", "clear_on_remove");
                settings.put("copy_mode", cur.equals("clear_on_remove") ? "keep_on_remove" : "clear_on_remove");
            }
            return true;
        }

        @Override public boolean sendIntAction(String actionName, int value) {
            log.add("intAction:" + actionName + ":" + value);
            if ("setAmount".equals(actionName)) {
                pendingStockSlot = value;
                menuTypeId = Ae2PartKeys.MENU_SET_STOCK_AMOUNT;
            } else if ("setStockAmount".equals(actionName)) {
                for (int i = 0; i < configSlots.size(); i++) {
                    MenuSlotEntry entry = configSlots.get(i);
                    if (entry.localIndex() == pendingStockSlot) {
                        configSlots.set(i, new MenuSlotEntry(entry.menuIndex(), entry.localIndex(), true,
                                entry.itemId(), value));
                    }
                }
                menuTypeId = Ae2PartKeys.MENU_INTERFACE;
            } else if ("setPriority".equals(actionName)) {
                priority = value;
            }
            return true;
        }

        @Override public boolean sendLongAction(String actionName, long value) {
            log.add("longAction:" + actionName + ":" + value);
            if ("setReportingValue".equals(actionName)) reportingValue = value;
            return true;
        }

        @Override public boolean sendBooleanAction(String actionName, boolean value) {
            log.add("boolAction:" + actionName + ":" + value);
            return true;
        }

        @Override public boolean sendStringAction(String actionName, String value) {
            log.add("strAction:" + actionName + ":" + value);
            return true;
        }

        @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) {
            log.add("jsonAction:" + actionName + ":" + jsonPayload);
            return true;
        }

        @Override public boolean openSubMenu(String subMenuId) {
            log.add("openSub:" + subMenuId);
            menuTypeId = subMenuId;
            return true;
        }

        @Override public boolean returnToParentMenu() {
            log.add("returnParent");
            menuTypeId = parentMenuId;
            return true;
        }

        // --- Ae2PartControl ---
        @Override public Optional<AABB> partBox(ClientLevel level, BlockPos host, Direction side) {
            return Optional.of(new AABB(host));
        }

        @Override public Optional<ClientPartReading> readClientPart(ClientLevel level, BlockPos host, Direction side) {
            return Optional.ofNullable(clientParts.get(side));
        }

        @Override public Optional<OpenMenuSnapshot> readOpenMenu() {
            if (closed) return Optional.empty();
            return Optional.of(new OpenMenuSnapshot(
                    menuTypeId, settings, keyTypes, connectedTo,
                    reportingValue == null ? OptionalLong.empty() : OptionalLong.of(reportingValue),
                    priority == null ? OptionalInt.empty() : OptionalInt.of(priority),
                    stockInitial == null ? OptionalInt.empty() : OptionalInt.of(stockInitial),
                    stockMax == null ? OptionalInt.empty() : OptionalInt.of(stockMax),
                    hasRedstoneCard,
                    wirelessBoosters == null ? OptionalInt.empty() : OptionalInt.of(wirelessBoosters),
                    configSlots, upgradeSlots, storageSlots, cellSlots, inputSlots, outputSlots));
        }

        @Override public boolean selectKeyType(String keyType, boolean enabled) {
            log.add("selectKeyType:" + keyType + ":" + enabled);
            keyTypes.put(keyType, enabled);
            return true;
        }

        @Override public boolean setCellWorkbenchFuzzy(String fuzzyMode) {
            log.add("workbenchFuzzy:" + fuzzyMode);
            settings.put("fuzzy_mode", fuzzyMode);
            return true;
        }

        @Override public Optional<ItemStack> filterStackOf(String targetId) {
            if (targetId == null) return Optional.empty();
            if ("empty".equalsIgnoreCase(targetId)) return Optional.of(ItemStack.EMPTY);
            ResourceLocation key = ResourceLocation.tryParse(targetId);
            if (key == null) return Optional.empty();
            return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new);
        }

        @Override public Optional<String> p2pAttunementOf(ItemStack stack) { return Optional.empty(); }
        @Override public boolean isTransferableCell(ItemStack stack) { return !stack.isEmpty(); }
        @Override public boolean isMemoryCard(ItemStack stack) { return false; }
        @Override public Optional<String> memoryCardP2pType(ItemStack stack) { return Optional.empty(); }
        @Override public OptionalInt memoryCardP2pFrequency(ItemStack stack) { return OptionalInt.empty(); }
        @Override public boolean isMenuType(AbstractContainerMenu menu, String target) { return target.equals(menuTypeId); }
        @Override public boolean isFluidContainerFor(ItemStack stack, String fluidId) { return false; }

        // --- OpenedMenu ---
        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> pIds = new ArrayList<>(playerSlots.keySet());
            List<SlotSnapshot> pSnaps = new ArrayList<>(playerSlots.values());
            List<Integer> invIdx = new ArrayList<>(pIds.size());
            for (int i = 0; i < pIds.size(); i++) invIdx.add(i);
            MenuChannel channel = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
                @Override public SlotSnapshot cursor() { return cursor; }
                @Override public boolean click(int slot, int button) { return ScriptedSession.this.click(slot, button); }
                @Override public boolean quickMove(int slot) { return ScriptedSession.this.quickMove(slot); }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() { closed = true; }
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return menuTypeId; }
                @Override public int slotCount() { return 136; }
                @Override public boolean playerBacked(int slot) { return playerSlots.containsKey(slot); }
                @Override public int playerInventoryIndex(int slot) { return slot - 100; }
            };
            return Optional.of(new MenuContent.Reading(channel, slots, List.of(), pIds, invIdx, List.of(), pSnaps));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() { return cursor; }

        @Override public boolean quickMove(int slotId) {
            log.add("quickMove:" + slotId);
            if (clearFromList(upgradeSlots, slotId) || clearFromList(cellSlots, slotId)
                    || clearFromList(outputSlots, slotId)) {
                return true;
            }
            if (clearFromList(storageSlots, slotId)) {
                wirelessBoosters = 0;
                return true;
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) {
            log.add("click:" + slotId + ":" + button);
            if (playerSlots.containsKey(slotId)) {
                SlotSnapshot prev = playerSlots.get(slotId);
                if (cursor.isEmpty()) {
                    cursor = prev;
                    playerSlots.put(slotId, SlotSnapshot.empty());
                } else if (prev.isEmpty()) {
                    playerSlots.put(slotId, cursor);
                    cursor = SlotSnapshot.empty();
                } else if (prev.key().equals(cursor.key())) {
                    int space = Math.max(0, 64 - prev.count());
                    int move = Math.min(space, cursor.count());
                    if (move > 0) {
                        ItemStack merged = prev.stack().copy();
                        merged.setCount(prev.count() + move);
                        playerSlots.put(slotId, SlotSnapshot.of(merged));
                        int remain = cursor.count() - move;
                        cursor = remain <= 0 ? SlotSnapshot.empty() : snapshotOf(cursor.key(), remain);
                    }
                }
                return true;
            }
            if (applyListClick(upgradeSlots, slotId, button, false)) return true;
            if (applyListClick(cellSlots, slotId, button, false)) return true;
            if (applyListClick(inputSlots, slotId, button, false)) return true;
            if (applyListClick(storageSlots, slotId, button, true)) return true;
            return true;
        }

        private boolean clearFromList(List<MenuSlotEntry> list, int slotId) {
            for (int i = 0; i < list.size(); i++) {
                MenuSlotEntry entry = list.get(i);
                if (entry.menuIndex() == slotId) {
                    if (!entry.isEmpty()) {
                        putIntoFreePlayerSlot(entry.itemId(), (int) entry.count());
                    }
                    list.set(i, new MenuSlotEntry(slotId, entry.localIndex(), entry.enabled(), null, 0L));
                    return true;
                }
            }
            return false;
        }

        private boolean applyListClick(List<MenuSlotEntry> list, int slotId, int button, boolean stackable) {
            for (int i = 0; i < list.size(); i++) {
                MenuSlotEntry entry = list.get(i);
                if (entry.menuIndex() != slotId) continue;
                if (cursor.isEmpty()) {
                    if (!entry.isEmpty()) {
                        cursor = snapshotOf(entry.itemId(), (int) entry.count());
                        list.set(i, new MenuSlotEntry(slotId, entry.localIndex(), entry.enabled(), null, 0L));
                        if (stackable) wirelessBoosters = 0;
                    }
                    return true;
                }
                int move = button == 1 ? 1 : cursor.count();
                int nextCount = (int) entry.count() + move;
                list.set(i, new MenuSlotEntry(slotId, entry.localIndex(), entry.enabled(), cursor.key(), nextCount));
                if (stackable) wirelessBoosters = nextCount;
                int remain = cursor.count() - move;
                cursor = remain <= 0 ? SlotSnapshot.empty() : snapshotOf(cursor.key(), remain);
                return true;
            }
            return false;
        }

        private void putIntoFreePlayerSlot(String itemId, int count) {
            for (Map.Entry<Integer, SlotSnapshot> e : playerSlots.entrySet()) {
                if (e.getValue().isEmpty()) {
                    e.setValue(snapshotOf(itemId, count));
                    return;
                }
            }
        }

        private static SlotSnapshot snapshotOf(String itemId, int count) {
            ResourceLocation key = ResourceLocation.tryParse(itemId);
            if (key == null) return SlotSnapshot.empty();
            var item = BuiltInRegistries.ITEM.getOptional(key);
            if (item.isEmpty()) return SlotSnapshot.empty();
            return SlotSnapshot.of(new ItemStack(item.get(), count));
        }

        @Override public void followPageSwaps(PageSwaps swaps) { swapFollowed = true; }
        @Override public void noteCursorTakenFrom(int slotId) {}
        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "关闭替身界面"; }
            };
        }
        @Override public void abandon() { closed = true; }
    }
}
