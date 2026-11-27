// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.OutputLook;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 反应仓核心业务单元测试：
 * 覆盖水/岩浆桶换算（余量扣减、够数不备桶、异种流体冲突）、方块直点倒桶与满罐/拒收中止、
 * 固体九格投料与一格收货、流体产物空桶舀取与 FullRun、wait=false 兼容、
 * 以及 auto_export / upgrade.0..3 / flush_fluid_in / flush_fluid_out / output_side.* 配置与状态合并。
 */
class ReactionChamberOpsTest {

    private static final BlockPos CHAMBER_POS = new BlockPos(12, 64, 18);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 流体桶换算按余量扣减并在够数时跳过备桶而在异种流体时报错冲突() {
        StubReads reads = new StubReads();
        // 空槽时：500 mB 水换 1 桶水桶，1500 mB 岩浆换 2 桶岩浆桶，走真实 Fluid.getBucket()。
        ReactionChamberOps ops = newOps(reads, ServerReads.NONE, null);
        ShownRecipe waterRecipe = fluixRecipe(500L);
        FeedPlan emptyPlan = ops.plan(CHAMBER_POS, waterRecipe, 1, Permissions.DEFAULT);
        assertTrue(emptyPlan.conflict().isEmpty());
        assertEquals("minecraft:water_bucket", emptyPlan.entries().getFirst().item());
        assertEquals(1, emptyPlan.entries().getFirst().count());
        assertEquals(3, emptyPlan.entries().size());

        ShownIngredient lava1500 = ShownIngredient.of(new ShownStack(ShownStack.Kind.FLUID,
                "minecraft:lava", "岩浆", 1500L, 1.0));
        Optional<IngredientFeed> lavaFeed = ops.ingredientFeed(CHAMBER_POS, lava1500, 1500L);
        assertTrue(lavaFeed.isPresent());
        assertEquals("minecraft:lava_bucket", lavaFeed.get().itemId());
        assertEquals(2, lavaFeed.get().count());

        // 通过服务端读数提供输入流体槽已有 800 mB 水：3 批共需 1500 mB，扣减后只需 1 桶水。
        ServerReads serverWith800Water = serverReply(CHAMBER_POS, chamberReply("minecraft:water", 800L, "empty", 0L));
        ReactionChamberOps deductOps = newOps(reads, serverWith800Water, null);
        FeedPlan deductPlan = deductOps.plan(CHAMBER_POS, waterRecipe, 3, Permissions.DEFAULT);
        assertTrue(deductPlan.conflict().isEmpty());
        assertEquals("minecraft:water_bucket", deductPlan.entries().getFirst().item());
        assertEquals(1, deductPlan.entries().getFirst().count());

        // 输入槽已有 1200 mB 水，满足 2 批（1000 mB）：跳过备桶并记说明，保留固体原料。
        ServerReads serverWith1200Water = serverReply(CHAMBER_POS, chamberReply("minecraft:water", 1200L, "empty", 0L));
        ReactionChamberOps enoughOps = newOps(reads, serverWith1200Water, null);
        FeedPlan enoughPlan = enoughOps.plan(CHAMBER_POS, waterRecipe, 2, Permissions.DEFAULT);
        assertTrue(enoughPlan.conflict().isEmpty());
        assertEquals(2, enoughPlan.entries().size());
        assertFalse(enoughPlan.entries().stream().anyMatch(e -> e.item().contains("bucket")));
        assertTrue(enoughPlan.notes().stream().anyMatch(n -> n.contains("跳过备桶")));

        // 输入槽存有 500 mB 岩浆而配方要水：经 FeedPlan.conflict 明确报错并提示 flush_fluid_in=true。
        ServerReads serverWithLava = serverReply(CHAMBER_POS, chamberReply("minecraft:lava", 500L, "empty", 0L));
        ReactionChamberOps conflictOps = newOps(reads, serverWithLava, null);
        FeedPlan conflictPlan = conflictOps.plan(CHAMBER_POS, waterRecipe, 1, Permissions.DEFAULT);
        assertTrue(conflictPlan.conflict().isPresent());
        assertTrue(conflictPlan.conflict().get().contains("flush_fluid_in=true"));
        assertFalse(conflictPlan.entries().stream().anyMatch(e -> "minecraft:water_bucket".equals(e.item())));
    }

    @Test
    void 方块直点倒桶逐桶确认主手变空桶且满罐或意外开窗立即中止() {
        AtomicReference<String> hand = new AtomicReference<>("");
        AtomicInteger containerId = new AtomicInteger(0);
        AtomicReference<AdvancedAeReads.TankSlot> tank = new AtomicReference<>(
                new AdvancedAeReads.TankSlot("minecraft:water", 0L, 16_000L));
        AtomicInteger clicks = new AtomicInteger();
        List<InteractionConfirmation> capturedConfirmations = new ArrayList<>();

        ReactionChamberBucketFeed pourTwo = new ReactionChamberBucketFeed(
                CHAMBER_POS, "minecraft:water_bucket", "minecraft:water", 2,
                ReactionChamberOpsTest::doneAction,
                id -> Optional.of(runOnce(() -> hand.set(id))),
                confirmation -> {
                    capturedConfirmations.add(confirmation);
                    return runOnce(() -> {
                        clicks.incrementAndGet();
                        // 模拟服务端确认主手满桶变为空桶、输入槽增加 1000 mB 且不开窗。
                        hand.set(ReactionChamberBucketFeed.EMPTY_BUCKET_ID);
                        tank.set(new AdvancedAeReads.TankSlot("minecraft:water",
                                tank.get().amountMb() + 1000L, 16_000L));
                    });
                },
                () -> Optional.of(tank.get()),
                hand::get,
                containerId::get,
                () -> {});

        ActionStatus done = drive(pourTwo, 30);
        assertInstanceOf(ActionStatus.Done.class, done);
        assertEquals(2, pourTwo.delivered());
        assertEquals(2, clicks.get());
        assertEquals(2, capturedConfirmations.size());
        // 验证倒桶确认条件走 BlockUseConfirmation 体系并确认主手变空桶。
        InteractionConfirmation conf = capturedConfirmations.getFirst();
        assertTrue(conf.requiresBlockAcknowledgement());
        assertTrue(conf.expiryMeansNotApplied());
        assertEquals(InteractionConfirmation.Verdict.APPLIED, conf.observe(null));

        // 满罐（15500/16000 mB，剩余不足 1000 mB）：立刻失败中止，不发点击。
        AtomicInteger fullClicks = new AtomicInteger();
        ReactionChamberBucketFeed fullTank = new ReactionChamberBucketFeed(
                CHAMBER_POS, "minecraft:water_bucket", "minecraft:water", 1,
                ReactionChamberOpsTest::doneAction,
                id -> Optional.of(runOnce(() -> hand.set(id))),
                c -> runOnce(fullClicks::incrementAndGet),
                () -> Optional.of(new AdvancedAeReads.TankSlot("minecraft:water", 15_500L, 16_000L)),
                hand::get, containerId::get, () -> {});
        ActionStatus fullFailed = drive(fullTank, 20);
        assertInstanceOf(ActionStatus.Failed.class, fullFailed);
        assertEquals(0, fullClicks.get());

        // 方块拒收并意外打开界面：确认判 DIVERGED、自动关闭意外界面并立即中止不死循环连点。
        AtomicBoolean closedUnexpected = new AtomicBoolean();
        AtomicInteger rejectClicks = new AtomicInteger();
        hand.set("minecraft:water_bucket");
        containerId.set(0);
        ReactionChamberBucketFeed rejected = new ReactionChamberBucketFeed(
                CHAMBER_POS, "minecraft:water_bucket", "minecraft:water", 3,
                ReactionChamberOpsTest::doneAction,
                id -> Optional.of(runOnce(() -> hand.set(id))),
                confirmation -> new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        rejectClicks.incrementAndGet();
                        containerId.set(7);
                        assertEquals(InteractionConfirmation.Verdict.DIVERGED, confirmation.observe(null));
                        return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "意外开了界面"));
                    }
                    @Override public String describe() { return "模拟拒收开窗"; }
                },
                () -> Optional.of(new AdvancedAeReads.TankSlot("minecraft:water", 0L, 16_000L)),
                hand::get, containerId::get, () -> closedUnexpected.set(true));
        ActionStatus rejectedStatus = drive(rejected, 20);
        assertInstanceOf(ActionStatus.Failed.class, rejectedStatus);
        assertEquals(1, rejectClicks.get());
        assertTrue(closedUnexpected.get());
    }

    @Test
    void 投料分派与固体收货及空桶舀取流体产物桶与不等待模式均正常工作() {
        StubReads reads = new StubReads();
        Map<String, String> fluidBuckets = Map.of(
                "minecraft:water", "minecraft:water_bucket",
                "advanced_ae:quantum_infusion", "advanced_ae:quantum_infusion_bucket");
        ServerReads serverWithQuantum = serverReply(CHAMBER_POS,
                chamberReply("minecraft:water", 2000L, "advanced_ae:quantum_infusion", 2000L));
        ReactionChamberOps ops = newOps(reads, serverWithQuantum, fluidBuckets);

        // 流体桶走 ReactionChamberBucketFeed，固体原料走 MenuFeed；状态空闲报 IDLE 不报 OFFLINE，兼容 wait=false。
        Optional<Action> bucketFeed = ops.feed(CHAMBER_POS,
                new FeedPlan.Entry("minecraft:water_bucket", 1, false), Permissions.DEFAULT);
        assertInstanceOf(ReactionChamberBucketFeed.class, bucketFeed.orElseThrow());
        Optional<Action> solidFeed = ops.feed(CHAMBER_POS,
                new FeedPlan.Entry("ae2:charged_certus_quartz_crystal", 16, false), Permissions.DEFAULT);
        assertInstanceOf(MenuFeed.class, solidFeed.orElseThrow());

        MachineState idleState = ops.state(CHAMBER_POS, null);
        assertEquals(MachineState.Activity.IDLE, idleState.activity());
        assertEquals("minecraft:water", idleState.readings().get("input_fluid"));
        assertEquals(2000L, idleState.readings().get("input_fluid_mb"));
        assertEquals("advanced_ae:quantum_infusion", idleState.readings().get("output_fluid"));
        assertEquals(420000.0, idleState.readings().get("energy_stored"));
        assertEquals(7, ops.exchangePoints(CHAMBER_POS).size());

        // 固体成品收货走 MenuOutputLook；流体产物桶走空桶舀取与 FullRun。
        Optional<Action> solidTake = ops.take(CHAMBER_POS, "ae2:fluix_crystal", 64, Permissions.DEFAULT);
        assertInstanceOf(MenuOutputLook.class, solidTake.orElseThrow());
        Optional<Action> fluidTake = ops.take(CHAMBER_POS, "advanced_ae:quantum_infusion_bucket", 1,
                Permissions.DEFAULT);
        assertTrue(fluidTake.isPresent());
        assertInstanceOf(OutputLook.class, fluidTake.get());

        Optional<FullRun> fluidRun = ops.fullRun(CHAMBER_POS,
                new RunRequest("advanced_ae:quantum_infusion_bucket", 1, null, null, 0, 30),
                Permissions.DEFAULT);
        assertTrue(fluidRun.isPresent());

        // 验证空桶直点舀取动作：切空桶 -> 点方块 -> 确认主手变成量子灌注液桶。
        AtomicReference<String> hand = new AtomicReference<>("");
        AtomicInteger containerId = new AtomicInteger(0);
        AtomicReference<AdvancedAeReads.TankSlot> outTank = new AtomicReference<>(
                new AdvancedAeReads.TankSlot("advanced_ae:quantum_infusion", 2000L, 16_000L));
        OutputLook scoop = ReactionChamberBucketFeed.scoopForTest(
                CHAMBER_POS, "advanced_ae:quantum_infusion_bucket", "advanced_ae:quantum_infusion", 2,
                ReactionChamberOpsTest::doneAction,
                id -> Optional.of(runOnce(() -> hand.set(id))),
                confirmation -> runOnce(() -> {
                    hand.set("advanced_ae:quantum_infusion_bucket");
                    outTank.set(new AdvancedAeReads.TankSlot("advanced_ae:quantum_infusion",
                            outTank.get().amountMb() - 1000L, 16_000L));
                    assertEquals(InteractionConfirmation.Verdict.APPLIED, confirmation.observe(null));
                }),
                () -> Optional.of(outTank.get()),
                hand::get, containerId::get, () -> {});
        assertInstanceOf(ActionStatus.Done.class, drive(scoop, 30));
        assertEquals(2, scoop.seen());
        assertEquals(2, scoop.taken());
    }

    @Test
    void 自动输出与清空双流体槽与相对输出面与升级卡配置全链路生效() {
        StubReads reads = new StubReads();
        reads.autoExport = "no";
        reads.inputFluid = new AdvancedAeReads.TankSlot("minecraft:lava", 3000L, 16_000L);
        reads.outputFluid = new AdvancedAeReads.TankSlot("advanced_ae:quantum_infusion", 1000L, 16_000L);
        reads.allowedOutputs.add("top");
        // 模拟携带网络工具箱偏移：UPGRADE 槽位于 9..12，玩家背包在 16..51。
        reads.semantics.put("UPGRADE", List.of(9, 10, 11, 12));

        StubMenu menu = new StubMenu();
        menu.containerSlots.put(9, SlotSnapshot.of(new ItemStack(Items.REDSTONE, 1)));
        menu.containerSlots.put(10, SlotSnapshot.empty());
        menu.playerSlots.put(16, SlotSnapshot.of(new ItemStack(Items.GOLD_INGOT, 1)));

        ReactionChamberOps ops = newOps(reads, ServerReads.NONE, null, (at, p) -> fakeOpening(menu));

        // 1) auto_export -> yes
        Action autoExportAction = ops.change(CHAMBER_POS, "auto_export", "yes", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(autoExportAction, 20));
        assertEquals("yes", settingValue(ops.settings(CHAMBER_POS), "auto_export"));

        // 2) flush_fluid_in -> true
        Action flushInAction = ops.change(CHAMBER_POS, "flush_fluid_in", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(flushInAction, 20));
        assertTrue(reads.inputFluid.isEmpty());
        assertEquals("true", settingValue(ops.settings(CHAMBER_POS), "flush_fluid_in"));

        // 3) flush_fluid_out -> true
        Action flushOutAction = ops.change(CHAMBER_POS, "flush_fluid_out", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(flushOutAction, 20));
        assertTrue(reads.outputFluid.isEmpty());
        assertEquals("true", settingValue(ops.settings(CHAMBER_POS), "flush_fluid_out"));

        // 4) output_side.right -> true（进入 OutputDirectionMenu 子菜单切换并返回主菜单）
        Action sideAction = ops.change(CHAMBER_POS, "output_side.right", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(sideAction, 30));
        assertTrue(reads.allowedOutputs.contains("right"));
        assertTrue(reads.returnedToParent);
        assertEquals("true", settingValue(ops.settings(CHAMBER_POS), "output_side.right"));
        assertEquals("true", settingValue(ops.settings(CHAMBER_POS), "output_side.top"));
        assertEquals("false", settingValue(ops.settings(CHAMBER_POS), "output_side.front"));

        // 5) upgrade.0 -> empty（命中偏移后的语义槽 9 并快速移出）
        Action removeUpgrade = ops.change(CHAMBER_POS, "upgrade.0", "empty", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(removeUpgrade, 20));
        assertTrue(menu.containerSlots.get(9).isEmpty());
        assertEquals(List.of(9), menu.quickMoved);

        // 6) upgrade.0 放回物品（验证偏移槽 9 快速移入与确认）
        Action putUpgrade = new ReactionChamberConfigActions.UpgradeSlotAction(
                fakeOpening(menu), new AdvancedAeCompat(reads), 0, "minecraft:gold_ingot", view -> {});
        assertInstanceOf(ActionStatus.Done.class, drive(putUpgrade, 20));
        assertEquals("minecraft:gold_ingot", menu.containerSlots.get(9).key());

        // 非法配置键或非法取值返回空。
        assertTrue(ops.change(CHAMBER_POS, "auto_export", "maybe", Permissions.DEFAULT).isEmpty());
        assertTrue(ops.change(CHAMBER_POS, "output_side.diagonal", "true", Permissions.DEFAULT).isEmpty());
        assertTrue(ops.change(CHAMBER_POS, "upgrade.4", "empty", Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 服务端权威读数优先覆盖客户端通电与双流体槽且无服务端空闲不误报未通电() {
        Map<String, String> fluidBuckets = Map.of(
                "minecraft:water", "minecraft:water_bucket",
                "advanced_ae:quantum_infusion", "advanced_ae:quantum_infusion_bucket");
        // 客户端方块实体空闲（供电未知为 null，且流体槽滞后为空），服务端权威有电且双槽有液：服务端必须胜出。
        StubReads reads = new StubReads();
        reads.chamberView = new AdvancedAeReads.ChamberView(false, null, null, Direction.NORTH,
                AdvancedAeReads.TankSlot.EMPTY, AdvancedAeReads.TankSlot.EMPTY, List.of(), List.of());
        ServerReads server = serverReply(CHAMBER_POS,
                chamberReply("minecraft:water", 1500L, "advanced_ae:quantum_infusion", 1000L));
        ReactionChamberOps ops = newOps(reads, server, fluidBuckets);

        MachineState state = ops.state(CHAMBER_POS, null);
        assertEquals(MachineState.Activity.IDLE, state.activity());
        assertEquals(false, state.readings().get("working"));
        assertEquals(true, state.readings().get("powered"));
        assertEquals(true, state.readings().get("active"));
        assertEquals("minecraft:water", state.readings().get("input_fluid"));
        assertEquals(1500L, state.readings().get("input_fluid_mb"));
        assertEquals("advanced_ae:quantum_infusion", state.readings().get("output_fluid"));
        assertEquals(1000L, state.readings().get("output_fluid_mb"));
        assertFalse(state.note().contains("未通电"));

        // 投料计划与出口快照均以服务端权威液量为准，不被客户端滞后空槽覆盖。
        FeedPlan plan = ops.plan(CHAMBER_POS, fluixRecipe(500L), 3, Permissions.DEFAULT);
        assertFalse(plan.entries().stream().anyMatch(e -> e.item().contains("bucket")));
        assertTrue(ops.output(CHAMBER_POS, null).stream()
                .anyMatch(s -> "advanced_ae:quantum_infusion_bucket".equals(s.itemId()) && s.count() == 1));

        // 即使客户端视图携带假 false，服务端权威 true 依然优先。
        reads.chamberView = new AdvancedAeReads.ChamberView(false, false, false, Direction.NORTH,
                AdvancedAeReads.TankSlot.EMPTY, AdvancedAeReads.TankSlot.EMPTY, List.of(), List.of());
        MachineState overrideState = ops.state(CHAMBER_POS, null);
        assertEquals(true, overrideState.readings().get("powered"));
        assertEquals(true, overrideState.readings().get("active"));

        // 无服务端读数且未工作（客户端供电/在线为 null）：保持 IDLE 且不误判未通电；空槽停工时 FullRun 立刻失败不傻等。
        reads.chamberView = new AdvancedAeReads.ChamberView(false, null, null, Direction.NORTH,
                AdvancedAeReads.TankSlot.EMPTY, AdvancedAeReads.TankSlot.EMPTY, List.of(), List.of());
        ReactionChamberOps clientOnlyOps = newOps(reads, ServerReads.NONE, fluidBuckets);
        MachineState unknownPowerState = clientOnlyOps.state(CHAMBER_POS, null);
        assertEquals(MachineState.Activity.IDLE, unknownPowerState.activity());
        assertFalse(unknownPowerState.readings().containsKey("powered"));
        assertFalse(unknownPowerState.readings().containsKey("active"));
        assertFalse(unknownPowerState.note().contains("未通电"));

        FullRun run = clientOnlyOps.fullRun(CHAMBER_POS,
                new RunRequest("advanced_ae:quantum_infusion_bucket", 1, null, null, 0, 30),
                Permissions.DEFAULT).orElseThrow();
        ActionStatus failed = drive(run, 5);
        assertInstanceOf(ActionStatus.Failed.class, failed);
    }

    @Test
    void 清罐动作不凭滞后空槽跳过且无服务端不死等并能经服务端刷新确认与等首包同步() {
        // 1) 无服务端且客户端菜单储罐滞后呈空槽：仍必须发出 clearFluid，不得误跳过。
        StubReads staleEmptyReads = new StubReads();
        staleEmptyReads.inputFluid = AdvancedAeReads.TankSlot.EMPTY;
        ReactionChamberOps staleEmptyOps = newOps(staleEmptyReads, ServerReads.NONE, null,
                (at, p) -> fakeOpening(new StubMenu()));
        Action flushEmpty = staleEmptyOps.change(CHAMBER_POS, "flush_fluid_in", "true", Permissions.DEFAULT)
                .orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(flushEmpty, 15));
        assertEquals(1, staleEmptyReads.clearInCalls);

        // 2) 无服务端且客户端 fluidInv 不刷新（停机/无电反应仓常态）：发包后在短暂观察窗口内稳妥收尾，不死等 40 刻超时。
        StubReads frozenClientReads = new StubReads();
        frozenClientReads.inputFluid = new AdvancedAeReads.TankSlot("minecraft:lava", 3000L, 16_000L);
        frozenClientReads.freezeClientTank = true;
        ReactionChamberOps frozenOps = newOps(frozenClientReads, ServerReads.NONE, null,
                (at, p) -> fakeOpening(new StubMenu()));
        Action flushFrozen = frozenOps.change(CHAMBER_POS, "flush_fluid_in", "true", Permissions.DEFAULT)
                .orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(flushFrozen, 15));
        assertEquals(1, frozenClientReads.clearInCalls);
        assertEquals("true", settingValue(frozenOps.settings(CHAMBER_POS), "flush_fluid_in"));

        // 3) 有服务端通道且客户端 fluidInv 冻结不刷新：发清罐后主动失效服务端缓存，重问拿回 0 mB 立即确认收尾。
        AtomicReference<JsonObject> liveServerReply = new AtomicReference<>(
                chamberReply("minecraft:lava", 3000L, "empty", 0L));
        AtomicInteger serverAskCount = new AtomicInteger();
        ServerReads dynamicServer = dynamicServerReply(liveServerReply, serverAskCount);
        StubReads serverVerifiedReads = new StubReads();
        serverVerifiedReads.inputFluid = new AdvancedAeReads.TankSlot("minecraft:lava", 3000L, 16_000L);
        serverVerifiedReads.freezeClientTank = true;
        serverVerifiedReads.onClearIn = () -> liveServerReply.set(chamberReply("empty", 0L, "empty", 0L));
        ReactionChamberOps serverOps = newOps(serverVerifiedReads, dynamicServer, null,
                (at, p) -> fakeOpening(new StubMenu()));
        // 先预热缓存（3000 mB 岩浆），再执行清罐确认缓存被失效重问。
        assertEquals(3000L, serverOps.state(CHAMBER_POS, null).readings().get("input_fluid_mb"));
        Action flushServer = serverOps.change(CHAMBER_POS, "flush_fluid_in", "true", Permissions.DEFAULT)
                .orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(flushServer, 15));
        assertEquals(1, serverVerifiedReads.clearInCalls);
        assertTrue(serverAskCount.get() >= 2);
        assertEquals(0L, serverOps.state(CHAMBER_POS, null).readings().get("input_fluid_mb"));

        // 4) @GuiSync 首包延迟：openChamberMenu 前 3 刻返回空，AutoExportAction 等待首包后再把 yes 切回 no。
        StubReads delayedSyncReads = new StubReads();
        delayedSyncReads.autoExport = "yes";
        delayedSyncReads.openMenuDelayTicks = 3;
        ReactionChamberOps delayedOps = newOps(delayedSyncReads, ServerReads.NONE, null,
                (at, p) -> fakeOpening(new StubMenu()));
        Action disableExport = delayedOps.change(CHAMBER_POS, "auto_export", "no", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(disableExport, 20));
        assertEquals("no", delayedSyncReads.autoExport);
    }

    private static ReactionChamberOps newOps(StubReads reads, ServerReads serverReads,
                                             Map<String, String> extraFluidBuckets) {
        return newOps(reads, serverReads, extraFluidBuckets, (at, p) -> fakeOpening(new StubMenu()));
    }

    private static ReactionChamberOps newOps(StubReads reads, ServerReads serverReads,
                                             Map<String, String> extraFluidBuckets,
                                             BiFunction<BlockPos, Permissions, MenuOpening> openings) {
        PlayerServices services = stubServices(serverReads);
        AdvancedAeCompat compat = new AdvancedAeCompat(reads);
        return new ReactionChamberOps(compat, services,
                new AdvancedAeDeviceReadings(serverReads, () -> 1_000L),
                openings,
                fluidId -> extraFluidBuckets != null && extraFluidBuckets.containsKey(fluidId)
                        ? Optional.of(extraFluidBuckets.get(fluidId))
                        : ReactionChamberOps.defaultBucketForFluid(fluidId),
                itemId -> {
                    if (extraFluidBuckets != null) {
                        for (Map.Entry<String, String> entry : extraFluidBuckets.entrySet()) {
                            if (entry.getValue().equals(itemId)) return Optional.of(entry.getKey());
                        }
                    }
                    return ReactionChamberOps.defaultFluidForBucket(itemId);
                });
    }

    private static ShownRecipe fluixRecipe(long waterMb) {
        ShownIngredient water = ShownIngredient.of(new ShownStack(ShownStack.Kind.FLUID,
                "minecraft:water", "水", waterMb, 1.0));
        ShownIngredient charged = ShownIngredient.of(ShownStack.item("ae2:charged_certus_quartz_crystal", "充能石英", 16));
        ShownIngredient redstone = ShownIngredient.of(ShownStack.item("minecraft:redstone", "红石", 16));
        return new ShownRecipe("advanced_ae:fluix_crystals", "advanced_ae:reaction", "反应仓",
                List.of(ShownStack.item("advanced_ae:reaction_chamber", "反应仓", 1)),
                List.of(water, charged, redstone), List.of(),
                List.of(ShownStack.item("ae2:fluix_crystal", "福鲁伊克斯水晶", 64)), null);
    }

    private static JsonObject chamberReply(String inFluid, long inMb, String outFluid, long outMb) {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        reply.addProperty("kind", "reaction_chamber");
        reply.addProperty("block_id", "advanced_ae:reaction_chamber");
        JsonObject settings = new JsonObject();
        settings.addProperty("auto_export", "no");
        reply.add("settings", settings);
        JsonArray upgrades = new JsonArray();
        upgrades.add("ae2:speed_card");
        upgrades.add("");
        upgrades.add("");
        upgrades.add("");
        reply.add("upgrades", upgrades);
        JsonObject readings = new JsonObject();
        readings.addProperty("working", false);
        readings.addProperty("powered", true);
        readings.addProperty("active", true);
        readings.addProperty("input_fluid", inFluid);
        readings.addProperty("input_fluid_mb", inMb);
        readings.addProperty("input_fluid_capacity_mb", 16000L);
        readings.addProperty("output_fluid", outFluid);
        readings.addProperty("output_fluid_mb", outMb);
        readings.addProperty("output_fluid_capacity_mb", 16000L);
        readings.addProperty("processing_time", 0);
        readings.addProperty("max_processing_time", 200);
        readings.addProperty("energy_stored", 420000.0);
        readings.addProperty("energy_capacity", 500000.0);
        readings.addProperty("show_warning", false);
        JsonArray sides = new JsonArray();
        sides.add("top");
        readings.add("output_sides", sides);
        reply.add("readings", readings);
        return reply;
    }

    private static ServerReads serverReply(BlockPos pos, JsonObject reply) {
        return dynamicServerReply(new AtomicReference<>(reply), new AtomicInteger());
    }

    private static ServerReads dynamicServerReply(AtomicReference<JsonObject> replyRef, AtomicInteger askCount) {
        return new ServerReads() {
            @Override public boolean available(String operationId) {
                return AdvancedAeCompat.DEVICE_OPERATION.equals(operationId);
            }
            @Override public Optional<UUID> ask(String operationId, JsonObject body) {
                askCount.incrementAndGet();
                return Optional.of(UUID.randomUUID());
            }
            @Override public Optional<ClientRequest.Snapshot> answer(UUID requestId) {
                return Optional.of(new ClientRequest.Snapshot(requestId, AdvancedAeCompat.DEVICE_OPERATION,
                        1, false, ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.APPLIED,
                        false, "", "", 0L, replyRef.get()));
            }
        };
    }

    private static PlayerServices stubServices(ServerReads serverReads) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> doneAction(), new Interactions(null),
                itemId -> Optional.of(doneAction()), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                        return Optional.empty();
                    }
                    @Override public Optional<Action> takeOff(GearSlotName slot) {
                        return Optional.empty();
                    }
                }, inner -> inner, (request, permissions) -> doneAction(), nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), serverReads, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    private static String settingValue(List<MachineSetting> settings, String key) {
        return settings.stream().filter(s -> s.key().equals(key)).findFirst()
                .map(MachineSetting::value).orElse("");
    }

    private static Action doneAction() {
        return new Action() {
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "立即可完成的替身动作"; }
        };
    }

    private static Action runOnce(Runnable task) {
        return new Action() {
            @Override public ActionStatus tick(TickContext tick) {
                task.run();
                return ActionStatus.done();
            }
            @Override public String describe() { return "单步替身动作"; }
        };
    }

    private static ActionStatus drive(Action action, int maxTicks) {
        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < maxTicks; i++) {
            last = action.tick(TickContext.of(i, null));
            if (!(last instanceof ActionStatus.Running)) return last;
        }
        return last;
    }

    private static MenuOpening fakeOpening(OpenedMenu menu) {
        return new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "替身打开界面"; }
        };
    }

    private static final class StubReads implements AdvancedAeReads {
        final Map<String, List<Integer>> semantics = new LinkedHashMap<>();
        ChamberView chamberView;
        String autoExport = "no";
        TankSlot inputFluid = TankSlot.EMPTY;
        TankSlot outputFluid = TankSlot.EMPTY;
        boolean freezeClientTank;
        int openMenuDelayTicks;
        int clearInCalls;
        int clearOutCalls;
        Runnable onClearIn;
        boolean inSubMenu;
        boolean returnedToParent;
        final Set<String> allowedOutputs = new LinkedHashSet<>();

        @Override public Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at) {
            return Optional.ofNullable(chamberView);
        }

        @Override public List<Integer> menuSlotsOf(String semanticId) {
            return semantics.getOrDefault(semanticId, List.of());
        }

        @Override public Optional<ChamberMenuView> openChamberMenu() {
            if (inSubMenu) return Optional.empty();
            if (openMenuDelayTicks > 0) {
                openMenuDelayTicks--;
                return Optional.empty();
            }
            return Optional.of(new ChamberMenuView(autoExport, 0, 200, false, inputFluid, outputFluid,
                    List.of("empty", "empty", "empty", "empty")));
        }

        @Override public Optional<Set<String>> openOutputDirections() {
            return inSubMenu ? Optional.of(Set.copyOf(allowedOutputs)) : Optional.empty();
        }

        @Override public boolean sendAutoExportToggle() {
            autoExport = "yes".equals(autoExport) ? "no" : "yes";
            return true;
        }

        @Override public boolean sendClearFluidIn() {
            clearInCalls++;
            if (onClearIn != null) onClearIn.run();
            if (!freezeClientTank) inputFluid = TankSlot.EMPTY;
            return true;
        }

        @Override public boolean sendClearFluidOut() {
            clearOutCalls++;
            if (!freezeClientTank) outputFluid = TankSlot.EMPTY;
            return true;
        }

        @Override public boolean sendOpenOutputDirection() {
            inSubMenu = true;
            return true;
        }

        @Override public boolean sendToggleOutputSide(String relativeSide) {
            if (!inSubMenu) return false;
            if (!allowedOutputs.remove(relativeSide)) allowedOutputs.add(relativeSide);
            return true;
        }

        @Override public boolean sendReturnToParentMenu() {
            if (!inSubMenu) return false;
            inSubMenu = false;
            returnedToParent = true;
            return true;
        }
    }

    private static final class StubMenu implements OpenedMenu {
        final Map<Integer, SlotSnapshot> containerSlots = new LinkedHashMap<>();
        final Map<Integer, SlotSnapshot> playerSlots = new LinkedHashMap<>();
        final List<Integer> quickMoved = new ArrayList<>();

        @Override public Optional<MenuContent.Reading> reading() {
            List<Integer> cIds = new ArrayList<>(containerSlots.keySet());
            List<SlotSnapshot> cSnaps = new ArrayList<>(containerSlots.values());
            List<Integer> pIds = new ArrayList<>(playerSlots.keySet());
            List<SlotSnapshot> pSnaps = new ArrayList<>(playerSlots.values());
            List<Integer> pInv = new ArrayList<>();
            for (int i = 0; i < pIds.size(); i++) pInv.add(i);
            MenuChannel channel = new MenuChannel() {
                @Override public boolean stillOpen() { return true; }
                @Override public boolean cursorCarrying() { return false; }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return false; }
                @Override public boolean quickMove(int slot) { return false; }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() {}
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return AdvancedAeMachineCatalog.REACTION_CHAMBER_MENU; }
                @Override public int slotCount() { return 62; }
                @Override public boolean playerBacked(int slot) { return playerSlots.containsKey(slot); }
                @Override public int playerInventoryIndex(int slot) { return 0; }
            };
            return Optional.of(new MenuContent.Reading(channel, slots, cIds, pIds, pInv, cSnaps, pSnaps));
        }

        @Override public boolean busy() { return false; }

        @Override public boolean cursorEmpty() { return true; }

        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override public boolean quickMove(int slotId) {
            quickMoved.add(slotId);
            if (containerSlots.containsKey(slotId)) {
                containerSlots.put(slotId, SlotSnapshot.empty());
                return true;
            }
            if (playerSlots.containsKey(slotId)) {
                SlotSnapshot moved = playerSlots.put(slotId, SlotSnapshot.empty());
                for (Map.Entry<Integer, SlotSnapshot> entry : containerSlots.entrySet()) {
                    if (entry.getValue().isEmpty()) {
                        entry.setValue(moved);
                        break;
                    }
                }
                return true;
            }
            return false;
        }

        @Override public boolean click(int slotId, int button) { return false; }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() { return doneAction(); }

        @Override public void abandon() {}
    }
}
