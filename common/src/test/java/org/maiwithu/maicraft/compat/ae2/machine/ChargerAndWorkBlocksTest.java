// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2Patterns;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 阶段 1 工作方块离线测试：覆盖充能器与木质曲柄防毁纪律、曲柄放置面校验、
 * 振动室燃料与空桶阻塞提示、物质聚合器阈值提示与低值杂物筛选、
 * ME 箱子相对顶面分流、陨石储罐 16,000 mB 读数、水晶催生器前后两面连网口。
 */
class ChargerAndWorkBlocksTest {

    private static final BlockPos AT = new BlockPos(10, 64, 10);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 充能器读数同步槽内电量与非正面五面接口_曲柄满电或拒转立刻停摇() {
        // 带电工具读数：working、槽 0、item_energy 与 item_energy_max 全部透出。
        Ae2MachineReads.ChargerView charging = new Ae2MachineReads.ChargerView(
                Direction.SOUTH, true, "ae2:portable_item_cell_1k", 1, true, 4000.0, 20000.0);
        MachineState state = ChargerDeviceOps.buildChargerState(charging);
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(true, state.readings().get("working"));
        assertEquals("ae2:portable_item_cell_1k", state.readings().get("slot_0"));
        assertEquals(4000.0, state.readings().get("item_energy"));
        assertEquals(20000.0, state.readings().get("item_energy_max"));
        assertEquals(1, state.shown().size());

        // 曲柄防毁纪律：未满电且 canTurn==true 且冷却归零才允许摇；满电或 canTurn==false 立刻停摇绝不空摇销毁。
        Ae2MachineReads.CrankView canTurn = new Ae2MachineReads.CrankView(Direction.UP, AT, 0f, true);
        Ae2MachineReads.CrankView cannotTurn = new Ae2MachineReads.CrankView(Direction.UP, AT, 0f, false);
        Ae2MachineReads.ChargerView fullTool = new Ae2MachineReads.ChargerView(
                Direction.SOUTH, false, "ae2:portable_item_cell_1k", 1, true, 20000.0, 20000.0);

        assertTrue(ChargerFullRun.shouldTurnCrank(charging, canTurn, 0));
        assertFalse(ChargerFullRun.shouldTurnCrank(charging, canTurn, 5), "rotation 冷却期内不连点");
        assertFalse(ChargerFullRun.shouldTurnCrank(charging, cannotTurn, 0), "canTurn 为假必须立刻停摇");
        assertFalse(ChargerFullRun.shouldTurnCrank(fullTool, canTurn, 0), "工具满电必须立刻停摇");
        assertFalse(ChargerFullRun.shouldTurnCrank(charging, canTurn, 0, ChargerFullRun.MAX_CONSECUTIVE_CRANK_REFUSALS),
                "连续未旋转拒转达上限必须立刻停摇，绝不空摇销毁曲柄");

        // 木质曲柄读数与有效附着面校验：充能器/压印器非正面 5 面、水晶催生器前后 2 面。
        MachineState crankState = ChargerDeviceOps.CrankDeviceOps.buildCrankState(canTurn);
        assertEquals("up", crankState.readings().get("facing"));
        assertEquals(AT.toShortString(), crankState.readings().get("attached_to"));

        assertFalse(CrankPlacementRule.isAttachableFace("ae2:charger", Direction.NORTH, Direction.SOUTH, Direction.NORTH));
        assertTrue(CrankPlacementRule.isAttachableFace("ae2:charger", Direction.NORTH, Direction.SOUTH, Direction.UP));
        assertFalse(CrankPlacementRule.isAttachableFace("ae2:inscriber", Direction.EAST, Direction.WEST, Direction.EAST));
        assertTrue(CrankPlacementRule.isAttachableFace("ae2:inscriber", Direction.EAST, Direction.WEST, Direction.SOUTH));
        assertTrue(CrankPlacementRule.isAttachableFace("ae2:growth_accelerator", Direction.UP, Direction.DOWN, Direction.UP));
        assertTrue(CrankPlacementRule.isAttachableFace("ae2:growth_accelerator", Direction.UP, Direction.DOWN, Direction.DOWN));
        assertFalse(CrankPlacementRule.isAttachableFace("ae2:growth_accelerator", Direction.UP, Direction.DOWN, Direction.NORTH));
    }

    @Test
    void 振动室燃料模式与空桶占位明确点名阻塞物() {
        Ae2Compat compat = MachineTestFakes.compatWith(Ae2MenuSlots.NONE, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineCache cache = new Ae2MachineCache();
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<VibrationMenuView> openVibrationMenu() {
                return Optional.of(new VibrationMenuView(0, 0.0, 100.0, "minecraft:bucket", 1,
                        List.of("ae2:energy_card", "empty", "empty")));
            }
        };
        VibrationChamberOps ops = new VibrationChamberOps(compat, reads, cache);
        FuelUse fuelUse = ops.feeding(AT, MachineTestFakes.offlineServices()).orElseThrow().fuelUse(AT);
        assertEquals(FuelUse.Kind.FUEL, fuelUse.kind());
        assertTrue(fuelUse.note().contains("空桶"));

        Problem enriched = ops.enrichFuelFailure(AT, "minecraft:coal",
                Problem.of(Problem.Kind.REFUSED_BY_GAME, "槽位已满"));
        assertTrue(enriched.message().contains("minecraft:bucket"));
        assertTrue(enriched.message().contains("空桶"));
    }

    @Test
    void 物质聚合器未读标unknown_阈值提示不预判拦截且只选低值杂物() {
        // 未开窗缓存时诚实报 unknown。
        MachineState unknown = CondenserOps.buildState(Optional.empty());
        assertEquals(MachineState.Activity.UNKNOWN, unknown.activity());
        assertEquals("unknown", unknown.readings().get("mode"));

        // 容量门槛提示：1k 够物质球但不够奇点，64k 才够奇点。
        assertTrue(CondenserOps.capacityHint("matter_balls", "empty").contains("1k"));
        assertTrue(CondenserOps.capacityHint("matter_balls", "ae2:cell_component_1k").contains("足够"));
        assertTrue(CondenserOps.capacityHint("singularity", "ae2:cell_component_1k").contains("64k"));
        assertTrue(CondenserOps.capacityHint("singularity", "ae2:cell_component_64k").contains("足够"));

        // 低值杂物筛选：跳过钻石剑与铁锭，仅选中圆石投入黑洞槽。
        MenuContent.Reading reading = MachineTestFakes.readingOf(
                List.of(3, 4, 5),
                List.of(SlotSnapshot.of(new ItemStack(Items.DIAMOND_SWORD)),
                        SlotSnapshot.of(new ItemStack(Items.IRON_INGOT, 16)),
                        SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 64))),
                List.of(0, 1, 2),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()));
        assertEquals(5, CondenserOps.CondenserFullRun.findLowValueTrashSlot(reading));
    }

    @Test
    void ME箱子按相对顶面分流_陨石储罐十六桶直读_水晶催生器仅前后两面连网() {
        // ME 箱子朝向侧放时相对顶面可以是 EAST，而非固定 UP。
        Ae2MachineReads.MeChestView chest = new Ae2MachineReads.MeChestView(
                true, "ae2:item_storage_cell_4k", "not_empty", "fluix", Direction.EAST);
        MachineState chestState = MeChestOps.buildState(chest);
        assertEquals("east", chestState.readings().get("top_face"));
        assertEquals("ae2:item_storage_cell_4k", chestState.readings().get("cell_item"));
        assertTrue(chestState.note().contains("ae2:basic_cell_chest"));
        assertTrue(chestState.note().contains("ae2:me_chest"));
        List<Direction> swapFaces = FaceMenuOpening.facesExcept(Direction.EAST);
        assertEquals(5, swapFaces.size());
        assertFalse(swapFaces.contains(Direction.EAST));

        // 陨石储罐 16,000 mB 客户端直读。
        MachineState tankState = SkyStoneTankOps.buildTankState(
                new Ae2MachineReads.TankView("minecraft:water", 8000, 16000));
        assertEquals("minecraft:water", tankState.readings().get("fluid"));
        assertEquals(8000L, tankState.readings().get("fluid_amount"));
        assertEquals(16000L, tankState.readings().get("fluid_capacity"));
        assertEquals(0.5, tankState.progress());

        // 水晶催生器仅 FRONT 与 BACK 两面暴露 ME 连网/受电/曲柄接口。
        Ae2MachineReads.GrowthView growth = new Ae2MachineReads.GrowthView(true, Direction.NORTH, Direction.SOUTH);
        List<ExchangePoint> points = SkyStoneTankOps.GrowthAcceleratorOps.exchangePointsFor(AT, growth);
        assertEquals(2, points.size());
        assertEquals(Direction.NORTH, points.get(0).side());
        assertEquals(Direction.SOUTH, points.get(1).side());
    }

    @Test
    void 物质聚合器运行等模式同步不连发且核实输出槽清空才累加产出() {
        AtomicInteger configSends = new AtomicInteger();
        AtomicInteger syncDelay = new AtomicInteger();
        AtomicReference<String> currentMode = new AtomicReference<>("trash");
        AtomicInteger outputCount = new AtomicInteger(1);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return "MACHINE_OUTPUT".equals(semanticId) ? List.of(1) : List.of();
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) {
                configSends.incrementAndGet();
                syncDelay.set(3);
                return true;
            }
            @Override public boolean sendSetFilter(int i, ItemStack st) { return true; }
            @Override public boolean sendNoArgAction(String a) { return true; }
            @Override public boolean sendIntAction(String a, int v) { return true; }
            @Override public boolean sendLongAction(String a, long v) { return true; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return true; }
            @Override public boolean sendStringAction(String a, String v) { return true; }
            @Override public boolean sendRawJsonAction(String a, String j) { return true; }
            @Override public boolean openSubMenu(String m) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<CondenserMenuView> openCondenserMenu() {
                if (syncDelay.get() > 0 && syncDelay.decrementAndGet() == 0) {
                    currentMode.set("matter_balls");
                }
                return Optional.of(new CondenserMenuView(currentMode.get(), 256, 256,
                        "ae2:cell_component_1k", outputCount.get() > 0 ? "minecraft:clay_ball" : "", outputCount.get()));
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(
                        List.of(3), List.of(SlotSnapshot.empty()),
                        List.of(1), List.of(outputCount.get() > 0
                                ? SlotSnapshot.of(new ItemStack(Items.CLAY_BALL, outputCount.get()))
                                : SlotSnapshot.empty()))),
                slot -> {
                    if (slot == 1) outputCount.set(0);
                });
        CondenserOps.CondenserFullRun run = new CondenserOps.CondenserFullRun(AT, "minecraft:clay_ball", 1,
                "matter_balls", compat, reads, new Ae2MachineCache(), MachineTestFakes.instantOpening(menu));

        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 30; i++) {
            status = run.tick(MachineTestFakes.emptyTick());
            if (!(status instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, configSends.get(), "模式切换发一次包后应等同步，绝不连发");
        assertEquals(1, run.produced(), "仅在输出槽真实清空后累加产出");
        assertTrue(run.complete());
        assertTrue(menu.closed);
    }
}
