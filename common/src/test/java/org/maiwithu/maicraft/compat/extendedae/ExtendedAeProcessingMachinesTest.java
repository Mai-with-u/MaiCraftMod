// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 批次 1 其余四台加工与维护机离线单元测试：
 * 覆盖电路切片器连网面/状态/配置、扩展压印器 4 页择优调度/切页同步/模板仅收回自放/堆叠上限配置、
 * 扩展充能器 4 槽并发读数与批大小计算、水晶修复器仅背面连网/母岩提示/FuelUse.FUEL 声明。
 */
class ExtendedAeProcessingMachinesTest {

    private static final BlockPos POS = new BlockPos(4, 64, 8);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ShownRecipe logicCircuitRecipe() {
        JsonObject def = JsonParser.parseString("""
                {
                  "mode": "inscribe",
                  "ingredients": {
                    "top": { "item": "ae2:logic_processor_press" },
                    "middle": { "item": "minecraft:gold_ingot" }
                  },
                  "result": { "id": "ae2:printed_logic_processor", "count": 1 }
                }
                """).getAsJsonObject();
        return new ShownRecipe(
                "ae2:inscriber/logic_processor_print", "ae2:inscriber", "压印",
                List.of(ShownStack.item("extendedae:ex_inscriber", "扩展压印器", 1)),
                List.of(ShownIngredient.of(ShownStack.item("minecraft:gold_ingot", "金锭", 1))),
                List.of(ShownIngredient.of(ShownStack.item("ae2:logic_processor_press", "逻辑模板", 1))),
                List.of(ShownStack.item("ae2:printed_logic_processor", "逻辑电路板", 1)),
                def);
    }

    @Test
    void circuitCutterStateFourSidesAndSetSideConfigurationWork() {
        List<String> sent = new ArrayList<>();
        List<String> outputSides = new ArrayList<>();
        ExtendedAeReads reads = new ExtendedAeReads() {
            @Override public Optional<CircuitCutterMenuView> openCircuitCutterMenu() {
                return Optional.of(new CircuitCutterMenuView(20, 200, "no", List.copyOf(outputSides), List.of()));
            }
            @Override public boolean sendGenericAction(String action, Object... args) {
                sent.add(action + ":" + args[0] + ":" + args[1]);
                if ("set_side".equals(action) && Boolean.TRUE.equals(args[1])) outputSides.add(String.valueOf(args[0]));
                return true;
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(reads);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);
        FakeSimpleMenu fakeMenu = new FakeSimpleMenu("extendedae:circuit_cutter");
        CircuitCutterOps ops = new CircuitCutterOps(compat, readings, (at, perm) -> instantOpening(fakeMenu));

        // 除前后两面外 4 面连网，且 outputVisible=false 兼容 wait=false。
        List<ExchangePoint> points = ops.exchangePoints(null, POS, null).orElseThrow();
        assertEquals(4, points.size());
        assertTrue(points.stream().noneMatch(p -> p.side() == Direction.NORTH || p.side() == Direction.SOUTH));
        assertFalse(ops.outputVisible(POS));

        // 状态含输入槽与渲染产物。
        MachineState state = CircuitCutterOps.buildState(
                new ExtendedAeReads.CircuitCutterView(true, true, true, Direction.NORTH, Direction.SOUTH,
                        new MachineState.Shown("minecraft:gold_block", 1),
                        new MachineState.Shown("ae2:printed_logic_processor", 9)),
                Optional.empty(), Optional.empty());
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals("minecraft:gold_block ×1", state.readings().get("input"));
        assertEquals("ae2:printed_logic_processor ×9", state.readings().get("render_output"));

        // output_side.up=true 经 CEAEGenericPacket("set_side", "up", true) 生效。
        Action change = ops.settings(POS, null).orElseThrow()
                .change(POS, "output_side.up", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(change));
        assertEquals(List.of("set_side:up:true"), sent);
    }

    @Test
    void exInscriberPlannerPrioritizesMatchedPlateThenEmptyThenLeastConflict() {
        ShownRecipe recipe = logicCircuitRecipe();
        // 1. 第 2 页已有匹配的 logic_processor_press，应优先选第 2 页且模板投料数为 0。
        ExtendedAeReads.ExInscriberView matchedView = new ExtendedAeReads.ExInscriberView(
                Direction.NORTH, true, true, false, 64, List.of(
                new ExtendedAeReads.InscriberPageView(0, null, null, null, null),
                new ExtendedAeReads.InscriberPageView(1, new MachineState.Shown("ae2:silicon_press", 1), null, null, null),
                new ExtendedAeReads.InscriberPageView(2, new MachineState.Shown("ae2:logic_processor_press", 1), null, null, null),
                new ExtendedAeReads.InscriberPageView(3, null, null, null, null)));
        ExInscriberPlanner.PagePlan matchedPlan = ExInscriberPlanner.plan(recipe, 4, Optional.of(matchedView));
        assertEquals(2, matchedPlan.targetPage());
        assertTrue(matchedPlan.conflictingSlots().isEmpty());
        assertEquals(1, matchedPlan.entries().size());
        assertEquals("minecraft:gold_ingot", matchedPlan.entries().getFirst().item());
        assertEquals(4, matchedPlan.entries().getFirst().count());

        // 2. 没有匹配页时优先选全空页（第 1 页），且模板排在主料前并标 collectBack=true。
        ExtendedAeReads.ExInscriberView emptyPageView = new ExtendedAeReads.ExInscriberView(
                Direction.NORTH, true, true, false, 64, List.of(
                new ExtendedAeReads.InscriberPageView(0, new MachineState.Shown("ae2:silicon_press", 1), null, null, null),
                new ExtendedAeReads.InscriberPageView(1, null, null, null, null),
                new ExtendedAeReads.InscriberPageView(2, new MachineState.Shown("ae2:engineering_processor_press", 1), null, null, null),
                new ExtendedAeReads.InscriberPageView(3, new MachineState.Shown("ae2:calculation_processor_press", 1), null, null, null)));
        ExInscriberPlanner.PagePlan emptyPlan = ExInscriberPlanner.plan(recipe, 2, Optional.of(emptyPageView));
        assertEquals(1, emptyPlan.targetPage());
        assertEquals("ae2:logic_processor_press", emptyPlan.entries().getFirst().item());
        assertTrue(emptyPlan.entries().getFirst().collectBack());

        // 3. 四页全占冲突时挑冲突槽位最少的一页（第 3 页仅 1 个冲突槽），并返回需腾空的槽位列表。
        ExtendedAeReads.ExInscriberView allBusy = new ExtendedAeReads.ExInscriberView(
                Direction.NORTH, true, true, false, 64, List.of(
                new ExtendedAeReads.InscriberPageView(0, new MachineState.Shown("ae2:silicon_press", 1),
                        null, new MachineState.Shown("minecraft:diamond", 1), null),
                new ExtendedAeReads.InscriberPageView(1, new MachineState.Shown("ae2:engineering_processor_press", 1),
                        null, new MachineState.Shown("minecraft:iron_ingot", 1), null),
                new ExtendedAeReads.InscriberPageView(2, new MachineState.Shown("ae2:calculation_processor_press", 1),
                        null, new MachineState.Shown("minecraft:redstone", 1), null),
                new ExtendedAeReads.InscriberPageView(3, new MachineState.Shown("ae2:silicon_press", 1),
                        null, null, null)));
        ExInscriberPlanner.PagePlan leastConflict = ExInscriberPlanner.plan(recipe, 1, Optional.of(allBusy));
        assertEquals(3, leastConflict.targetPage());
        assertEquals(List.of(0), leastConflict.conflictingSlots());
        assertTrue(leastConflict.conflict().isPresent());
    }

    @Test
    void exInscriberPageSwitchTakeOnlyOwnPlateAndInputCapacityConfigWork() {
        List<String> sent = new ArrayList<>();
        int[] currentPage = {0};
        int[] stackMode = {0};
        FakeInscriberMenu fakeMenu = new FakeInscriberMenu();
        ExtendedAeReads reads = new ExtendedAeReads() {
            @Override public boolean isMenuOpen() { return true; }
            @Override public List<Integer> enabledSlotsOf(String semanticId) {
                return switch (semanticId) {
                    case "EX_1" -> List.of(10); // 第 1 页顶模板
                    case "EX_4" -> List.of(11); // 第 1 页底模板
                    case "EX_7" -> List.of(12); // 第 1 页中间输入
                    case "EX_10" -> List.of(13); // 第 1 页输出
                    default -> List.of();
                };
            }
            @Override public Optional<ExInscriberMenuView> openExInscriberMenu() {
                return Optional.of(new ExInscriberMenuView(
                        currentPage[0], "no", "no", stackMode[0], 0, 100, List.of(), true));
            }
            @Override public boolean sendUpdatePage(int page) {
                sent.add("page:" + page);
                currentPage[0] = page;
                return true;
            }
            @Override public boolean sendGenericAction(String action, Object... args) {
                if ("show".equals(action)) sent.add("show");
                if ("stack".equals(action)) {
                    int size = (Integer) args[0];
                    sent.add("stack:" + size);
                    stackMode[0] = ExInscriberOps.stackModeOf(size);
                }
                return true;
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(reads);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);

        // 1. 取产物动作：切到第 1 页（发 CUpdatePage(1) + show），取出输出槽产物且仅收回本会话放入的顶模板，不动原有底模板。
        Set<String> ownPlates = new LinkedHashSet<>(Set.of("minecraft:diamond"));
        ExInscriberActions.TakeAction take = new ExInscriberActions.TakeAction(
                1, "minecraft:gold_ingot", ownPlates, instantOpening(fakeMenu), compat, () -> null);
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(take));
        assertEquals(List.of("page:1", "show"), sent);
        assertEquals(2, take.taken());
        assertTrue(fakeMenu.topSlot.isEmpty(), "本会话自放的顶模板必须收回");
        assertFalse(fakeMenu.bottomSlot.isEmpty(), "机内原有的底模板绝不可动");

        // 2. 配置 input_capacity=4 经 CEAEGenericPacket("stack", 4) 生效并映射到 getStackMode()==2。
        ExInscriberOps ops = new ExInscriberOps(compat, readings, (at, perm) -> instantOpening(fakeMenu));
        Action capAction = ops.settings(POS, null).orElseThrow()
                .change(POS, "input_capacity", "4", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(capAction));
        assertTrue(sent.contains("stack:4"));
        assertEquals("4", readings.exInscriber(POS).orElseThrow().inputCapacity());
    }

    @Test
    void exChargerFourSlotBatchAndStateReadingsWork() {
        ExtendedAeReads.ExChargerView view = new ExtendedAeReads.ExChargerView(
                Direction.NORTH, true, true, List.of(
                new ExtendedAeReads.ChargerSlotView(0, "ae2:charged_certus_quartz_crystal", 1, false, 0, 0),
                new ExtendedAeReads.ChargerSlotView(1, "ae2:portable_item_cell_1k", 1, true, 12000, 20000),
                new ExtendedAeReads.ChargerSlotView(2, "", 0, false, 0, 0),
                new ExtendedAeReads.ChargerSlotView(3, "", 0, false, 0, 0)));
        assertEquals(2, ExChargerFullRun.batchSizeFor(5, view), "最多并发放入 min(剩余 5, 空槽 2) = 2 件");
        assertEquals(1, ExChargerFullRun.batchSizeFor(1, view));

        MachineState state = ExChargerOps.buildState(view);
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals("ae2:charged_certus_quartz_crystal", state.readings().get("slot_0"));
        assertEquals(12000.0, state.readings().get("slot_1_energy"));
        assertEquals(20000.0, state.readings().get("slot_1_energy_max"));
        assertEquals("empty", state.readings().get("slot_2"));
        assertEquals("empty", state.readings().get("slot_3"));

        ExChargerOps ops = new ExChargerOps(new ExtendedAeCompat(ExtendedAeReads.NONE));
        assertEquals(5, ops.exchangePoints(null, POS, null).orElseThrow().size());
    }

    @Test
    void crystalFixerBackSideOnlyBuddingHintAndFuelUseWork() {
        ExtendedAeCompat compat = new ExtendedAeCompat(new ExtendedAeReads() {
            @Override public Optional<CrystalFixerView> crystalFixerAt(ClientLevel level, BlockPos at) {
                return Optional.of(new CrystalFixerView(Direction.EAST, Direction.WEST, true,
                        "ae2:charged_certus_quartz_crystal", 16));
            }
        });
        CrystalFixerOps ops = new CrystalFixerOps(compat);

        // 仅背面一面连 ME 网。
        List<ExchangePoint> points = ops.exchangePoints(null, POS, null).orElseThrow();
        assertEquals(1, points.size());
        assertEquals(Direction.SOUTH, points.getFirst().side());

        // 对准可修复母岩与未对准母岩时的状态提示。
        ExtendedAeReads.CrystalFixerView view = new ExtendedAeReads.CrystalFixerView(
                Direction.EAST, Direction.WEST, true, "ae2:charged_certus_quartz_crystal", 16);
        MachineState onBudding = CrystalFixerOps.buildState(view, "ae2:damaged_budding_quartz");
        assertEquals(MachineState.Activity.RUNNING, onBudding.activity());
        assertEquals(true, onBudding.readings().get("facing_repairable_budding"));
        MachineState offBudding = CrystalFixerOps.buildState(view, "minecraft:stone");
        assertEquals(MachineState.Activity.IDLE, offBudding.activity());
        assertEquals(false, offBudding.readings().get("facing_repairable_budding"));
        assertTrue(offBudding.note().contains("minecraft:stone"));

        // FuelUse 必须声明为 FUEL（不拦截加燃料）。
        FuelUse fuelUse = ops.feeding(POS, null).orElseThrow().fuelUse(POS);
        assertEquals(FuelUse.Kind.FUEL, fuelUse.kind());
    }

    @Test
    void circuitCutterSettingsFallbackToServerOutputSides() {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject r = new JsonObject();
        JsonArray sides = new JsonArray();
        sides.add("up");
        sides.add("south");
        r.add("output_sides", sides);
        reply.add("readings", r);

        // 未开窗缓存时，电路切片器设置列表必须回落读取服务端 output_sides。
        List<MachineSetting> settings = CircuitCutterOps.cutterSettings(Optional.empty(), Optional.of(reply));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.up") && s.value().equals("true")));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.south") && s.value().equals("true")));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.west") && s.value().equals("false")));
    }

    @Test
    void exChargerFullRunRespectsRunRequestWaitForOutputFalse() {
        ExChargerOps ops = new ExChargerOps(new ExtendedAeCompat(ExtendedAeReads.NONE));
        PlayerServices services = offlineServices();

        // 1. 6 参向后兼容构造默认 waitForOutput=true。
        RunRequest defaultWait = new RunRequest("ae2:charged_certus_quartz_crystal", 2, null, null, 0, 300);
        assertTrue(defaultWait.waitForOutput());
        FullRun waitingRun = ops.feeding(POS, services).orElseThrow()
                .fullRun(POS, defaultWait, Permissions.DEFAULT).orElseThrow();
        ExChargerFullRun waiting = assertInstanceOf(ExChargerFullRun.class, waitingRun);
        assertTrue(waiting.waitsForOutput());
        assertInstanceOf(ActionStatus.Running.class, waiting.afterBatchInserted());

        // 2. 经 RunRequest 显式传 waitForOutput=false（且 maxSeconds=300 > 0）时，放完首批即返回 Done。
        RunRequest noWait = new RunRequest("ae2:charged_certus_quartz_crystal", 2, null, null, 0, 300, false);
        assertFalse(noWait.waitForOutput());
        FullRun fireAndForgetRun = ops.feeding(POS, services).orElseThrow()
                .fullRun(POS, noWait, Permissions.DEFAULT).orElseThrow();
        ExChargerFullRun fireAndForget = assertInstanceOf(ExChargerFullRun.class, fireAndForgetRun);
        assertFalse(fireAndForget.waitsForOutput());
        assertInstanceOf(ActionStatus.Done.class, fireAndForget.afterBatchInserted());
        assertTrue(fireAndForget.complete());
        assertTrue(fireAndForget.notes().stream().anyMatch(n -> n.contains("wait=false")));
    }

    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    private static ActionStatus runUntilDone(Action action) {
        TickContext tick = TickContext.of(1L, null);
        for (int i = 0; i < 60; i++) {
            ActionStatus status = action.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        return ActionStatus.running();
    }

    private static MenuOpening instantOpening(OpenedMenu menu) {
        return new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "假开窗"; }
        };
    }

    private static final class FakeSimpleMenu implements OpenedMenu {
        private final String typeId;
        boolean closed;

        FakeSimpleMenu(String typeId) { this.typeId = typeId; }

        @Override public Optional<MenuContent.Reading> reading() {
            MenuChannel ch = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return false; }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() { closed = true; }
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return typeId; }
                @Override public int slotCount() { return 0; }
                @Override public boolean playerBacked(int slot) { return false; }
                @Override public int playerInventoryIndex(int slot) { return 0; }
            };
            return Optional.of(new MenuContent.Reading(ch, slots, List.of(), List.of(), List.of(),
                    List.of(), List.of()));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean quickMove(int slotId) { return true; }
        @Override public boolean click(int slotId, int button) { return true; }
        @Override public void noteCursorTakenFrom(int slotId) {}
        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) { closed = true; return ActionStatus.done(); }
                @Override public String describe() { return "假关窗"; }
            };
        }
        @Override public void abandon() { closed = true; }
    }

    private static final class FakeInscriberMenu implements OpenedMenu {
        SlotSnapshot topSlot = SlotSnapshot.of(new ItemStack(Items.DIAMOND));
        SlotSnapshot bottomSlot = SlotSnapshot.of(new ItemStack(Items.IRON_INGOT));
        SlotSnapshot outputSlot = SlotSnapshot.of(new ItemStack(Items.GOLD_INGOT, 2));
        boolean closed;

        @Override public Optional<MenuContent.Reading> reading() {
            MenuChannel ch = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return false; }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() { closed = true; }
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return "extendedae:ex_inscriber"; }
                @Override public int slotCount() { return 14; }
                @Override public boolean playerBacked(int slot) { return false; }
                @Override public int playerInventoryIndex(int slot) { return 0; }
            };
            return Optional.of(new MenuContent.Reading(ch, slots,
                    List.of(10, 11, 13), List.of(), List.of(),
                    List.of(topSlot, bottomSlot, outputSlot), List.of()));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override public boolean quickMove(int slotId) {
            if (slotId == 10) { topSlot = SlotSnapshot.empty(); return true; }
            if (slotId == 11) { bottomSlot = SlotSnapshot.empty(); return true; }
            if (slotId == 13) { outputSlot = SlotSnapshot.empty(); return true; }
            return false;
        }

        @Override public boolean click(int slotId, int button) { return true; }

        @Override public Optional<PendingMenuAction> submitModAction(String what, Runnable send,
                MenuConfirmation confirmation, int timeoutTicks) {
            send.run();
            return Optional.empty();
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) { closed = true; return ActionStatus.done(); }
                @Override public String describe() { return "假关窗"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }
}
