// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.GestureConfirmations;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 原版营火与点火全链路离线测试：往点燃/未点燃营火放食物的准确回报、空手右键营火的事实提示、
 * 已点燃营火/蜡烛不再误点旁边空气格、未点燃营火/TNT 走状态变化确认以及点击面与效果格一致。
 */
class CampfireUseTest {

    private static final BlockPos CAMPFIRE = new BlockPos(5, 64, 5);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 往已点燃营火放食物_不报未知加工容器并明确说明自动弹出() {
        Rig rig = new Rig();
        rig.world.blocks.put(CAMPFIRE, "minecraft:campfire");
        rig.world.blockEntities.add(CAMPFIRE);
        rig.world.held = new UseSeams.ReadsWorld.Held("minecraft:potato", 2);
        rig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(true, 0, 4));
        rig.interactions.script(InteractionResult.applied("放上了土豆"), ItemUseAim.Gesture.USE_TARGET,
                new UseSeams.ReadsWorld.Held("minecraft:potato", 2), () -> {
                    rig.world.held = new UseSeams.ReadsWorld.Held("minecraft:potato", 1);
                    rig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(true, 1, 4));
                });

        TaskResult result = rig.run(input("minecraft:potato"));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("已放上营火（已点燃，当前 1/4 格，约 30 秒烤熟后会自动弹出成掉落物，无需右键取下）"),
                result.summary());
        assertFalse(result.summary().contains("不确认会不会做出东西"), result.summary());
        assertNull(((UseDetails) result.details()).unrecognizedProcessor());
    }

    @Test
    void 往未点燃营火放食物_明确提示未点燃不会烤熟且需先点火() {
        Rig rig = new Rig();
        rig.world.blocks.put(CAMPFIRE, "minecraft:soul_campfire");
        rig.world.blockEntities.add(CAMPFIRE);
        rig.world.held = new UseSeams.ReadsWorld.Held("minecraft:potato", 1);
        rig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(false, 1, 4));
        rig.interactions.script(InteractionResult.applied("放上了土豆"), ItemUseAim.Gesture.USE_TARGET,
                new UseSeams.ReadsWorld.Held("minecraft:potato", 1), () -> {
                    rig.world.held = null;
                    rig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(false, 2, 4));
                });

        TaskResult result = rig.run(input("minecraft:potato"));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("已放上营火，但营火目前未点燃，不会烤熟，请先用打火石或火焰弹点火"),
                result.summary());
        assertNull(((UseDetails) result.details()).unrecognizedProcessor());
    }

    @Test
    void 拿非食物方块潜行贴着营火放方块或旁格落块_不误报已放上营火() {
        // 场景 A：潜行拿圆石贴着营火放方块，即使消耗主手也绝不能误报「已放上营火」。
        Rig sneakRig = new Rig();
        sneakRig.world.blocks.put(CAMPFIRE, "minecraft:campfire");
        sneakRig.world.blockEntities.add(CAMPFIRE);
        sneakRig.world.held = new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 2);
        sneakRig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(true, 0, 4));
        sneakRig.interactions.script(InteractionResult.applied("在营火旁放下了圆石"), ItemUseAim.Gesture.USE_TARGET,
                new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 2), () -> {
                    sneakRig.world.held = new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 1);
                    sneakRig.world.blocks.put(CAMPFIRE.above(), "minecraft:cobblestone");
                });

        UseInput sneakInput = new UseInput(
                new Target.Position(CAMPFIRE.getX(), CAMPFIRE.getY(), CAMPFIRE.getZ(), null),
                "minecraft:cobblestone", null, null, 1, 32, List.of(), Permissions.DEFAULT, null, 0, true);
        TaskResult sneakResult = sneakRig.run(sneakInput);
        assertEquals(TaskResult.Status.DONE, sneakResult.status());
        assertFalse(sneakResult.summary().contains("已放上营火"), sneakResult.summary());

        // 场景 B：不潜行拿圆石对着营火右键，原版无法烤圆石而把圆石落到了营火旁格（烤位未增），同样不能误报「已放上营火」。
        Rig besideRig = new Rig();
        besideRig.world.blocks.put(CAMPFIRE, "minecraft:campfire");
        besideRig.world.blockEntities.add(CAMPFIRE);
        besideRig.world.held = new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 2);
        besideRig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(true, 0, 4));
        besideRig.interactions.script(InteractionResult.applied("在营火旁放下了圆石"), ItemUseAim.Gesture.USE_TARGET,
                new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 2), () -> {
                    besideRig.world.held = new UseSeams.ReadsWorld.Held("minecraft:cobblestone", 1);
                    besideRig.world.blocks.put(CAMPFIRE.east(), "minecraft:cobblestone");
                });

        TaskResult besideResult = besideRig.run(input("minecraft:cobblestone"));
        assertEquals(TaskResult.Status.DONE, besideResult.status());
        assertFalse(besideResult.summary().contains("已放上营火"), besideResult.summary());
    }

    @Test
    void 空手右键营火无效果_给出营火不支持右键取物的游戏事实() {
        Rig rig = new Rig();
        rig.world.blocks.put(CAMPFIRE, "minecraft:campfire");
        rig.world.blockEntities.add(CAMPFIRE);
        rig.world.campfires.put(CAMPFIRE, new UseSeams.ReadsWorld.CampfireStatus(true, 1, 4));
        rig.interactions.script(InteractionResult.notApplied("现场纹丝没动"), ItemUseAim.Gesture.EMPTY_HAND,
                null, () -> { });

        TaskResult result = rig.run(input(null));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("营火不支持右键取物")
                && result.problem().message().contains("自动弹出"), result.problem().message());

        // 游戏动作栏明确拒绝时（如领地保护），保留游戏原话不被营火规则覆盖。
        Rig refusedRig = new Rig();
        refusedRig.world.blocks.put(CAMPFIRE, "minecraft:campfire");
        refusedRig.world.blockEntities.add(CAMPFIRE);
        refusedRig.interactions.script(InteractionResult.notApplied("现场纹丝没动"), ItemUseAim.Gesture.EMPTY_HAND,
                null, () -> refusedRig.refusalMessage = "该区域已受保护");
        TaskResult refused = refusedRig.run(input(null));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, refused.problem().kind());
        assertTrue(refused.problem().message().contains("该区域已受保护"), refused.problem().message());
    }

    @Test
    void 已点燃营火蜡烛识别为无需再点_未点燃营火蜡烛TNT识别为点着自身() {
        BlockState litCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);
        BlockState unlitCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false);
        BlockState litCandle = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true);
        BlockState unlitCandle = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, false);
        BlockState litCake = Blocks.CANDLE_CAKE.defaultBlockState().setValue(CandleCakeBlock.LIT, true);
        BlockState unlitCake = Blocks.CANDLE_CAKE.defaultBlockState().setValue(CandleCakeBlock.LIT, false);
        BlockState tnt = Blocks.TNT.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertTrue(LiveUseInteractions.alreadyLit(litCampfire));
        assertFalse(LiveUseInteractions.ignitesItself(litCampfire));
        assertFalse(LiveUseInteractions.alreadyLit(unlitCampfire));
        assertTrue(LiveUseInteractions.ignitesItself(unlitCampfire));

        assertTrue(LiveUseInteractions.alreadyLit(litCandle));
        assertTrue(LiveUseInteractions.ignitesItself(unlitCandle));
        assertTrue(LiveUseInteractions.alreadyLit(litCake));
        assertTrue(LiveUseInteractions.ignitesItself(unlitCake));

        assertFalse(LiveUseInteractions.alreadyLit(tnt));
        assertTrue(LiveUseInteractions.ignitesItself(tnt));
        assertFalse(LiveUseInteractions.alreadyLit(stone));
        assertFalse(LiveUseInteractions.ignitesItself(stone));

        ResolvedTarget resolved = ResolvedTarget.block(CAMPFIRE, "minecraft:campfire", "b1");
        ItemUseAim.Around alreadyLitAround = new ItemUseAim.Around(
                CAMPFIRE.east(), Set.of(), Set.of(), ItemUseAim.AboveCell.OPEN, false, true);
        Problem problem = LiveUseInteractions.whyNoAim(
                ItemUseAim.Gesture.IGNITE, resolved, ItemUseAim.CellNature.SOLID, alreadyLitAround);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, problem.kind());
        assertTrue(problem.message().contains("已经点着了，无需再点"), problem.message());
    }

    @Test
    void 未点燃营火点火使用状态变化确认_且实心目标点火传递算出的点击面() {
        BlockState unlitCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false);
        ItemUseAim.Around unlitAround = new ItemUseAim.Around(
                CAMPFIRE.east(), Set.of(), Set.of(), ItemUseAim.AboveCell.OPEN, true, false);
        ItemUseAim.Aim campfireAim = ItemUseAim.aim(
                ItemUseAim.Gesture.IGNITE, CAMPFIRE, ItemUseAim.CellNature.SOLID, unlitAround).orElseThrow();
        assertEquals(ItemUseAim.Confirmation.BLOCK_TURNS_INTO, campfireAim.confirmation());
        assertNull(LiveUseInteractions.resolvedClickFace(campfireAim, CAMPFIRE, null),
                "点着营火自身时不强制限定单面");
        // GestureConfirmations 对 BLOCK_TURNS_INTO 生成方块状态变化确认，且不误挂过期即没生效。
        InteractionConfirmation confirmation = GestureConfirmations.forGesture(
                campfireAim.confirmation(), campfireAim.effectCell(), InteractionHand.MAIN_HAND,
                new ItemStack(Items.FLINT_AND_STEEL), unlitCampfire, 0);
        assertFalse(confirmation.expiryMeansNotApplied());

        // 对普通实心方块未显式传 face 时，算出的朝向角色点击面会完整传给 useBlockFace，与 effectCell 保持一致。
        ItemUseAim.Around stoneAround = new ItemUseAim.Around(
                CAMPFIRE.east(2), Set.of(), Set.of(), ItemUseAim.AboveCell.OPEN, false, false);
        ItemUseAim.Aim stoneAim = ItemUseAim.aim(
                ItemUseAim.Gesture.IGNITE, CAMPFIRE, ItemUseAim.CellNature.SOLID, stoneAround).orElseThrow();
        assertEquals(Direction.EAST, stoneAim.clickFace());
        assertEquals(CAMPFIRE.east(), stoneAim.effectCell());
        assertEquals(Direction.EAST, LiveUseInteractions.resolvedClickFace(stoneAim, CAMPFIRE, null));
    }

    private static UseInput input(String item) {
        return new UseInput(
                new Target.Position(CAMPFIRE.getX(), CAMPFIRE.getY(), CAMPFIRE.getZ(), null),
                item, null, null, 1, 32, List.of(), Permissions.DEFAULT);
    }

    private static TickContext tickOf(long now) {
        return new TickContext() {
            @Override public long gameTick() { return now; }
            @Override public PlayerContext player() { return null; }
        };
    }

    /** 最小离线测试台：构造营火与脚本化交互，驱动 UseTask 到终态。 */
    private static final class Rig {
        final StubWorld world = new StubWorld();
        final StubInteractions interactions = new StubInteractions();
        String refusalMessage;

        TaskResult run(UseInput input) {
            UseSeams.ReadsGameRefusal refusal = () -> Optional.ofNullable(refusalMessage);
            UseSeams.OthersThings allowAll = new UseSeams.OthersThings() {
                @Override public boolean changesWorld(String heldItem, ResolvedTarget target) { return false; }
                @Override public boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks) {
                    return false;
                }
            };
            HoldsSneak passSneak = inner -> inner;
            UseServices services = new UseServices(world, interactions,
                    (BringsPlayerClose) (target, permissions) -> new InstantAction(),
                    item -> new UseSeams.HandPlan.Ready(),
                    null, null, null, refusal, null, null, null, null, null, allowAll, null,
                    new FacilityKinds(FakeBlockTags.vanilla()), passSneak);
            UseTask task = new UseTask(input, services);
            task.start(tickOf(0));
            for (int tick = 1; tick <= 60; tick++) {
                TickResult step = task.tick(tickOf(tick));
                if (step instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("任务在 60 刻内未结束");
        }
    }

    private static final class InstantAction implements Action {
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
        @Override public String describe() { return "瞬间到位"; }
    }

    private static final class StubWorld implements UseSeams.ReadsWorld {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<BlockPos> blockEntities = new HashSet<>();
        final Map<BlockPos, CampfireStatus> campfires = new HashMap<>();
        Held held;

        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public BlockPos feet() { return BlockPos.ZERO; }
        @Override public boolean loaded(BlockPos cell) { return true; }
        @Override public Optional<String> blockId(BlockPos cell) {
            return Optional.of(blocks.getOrDefault(cell, "minecraft:air"));
        }
        @Override public boolean blockIs(BlockPos cell, String blockOrTag) {
            return blockId(cell).map(blockOrTag::equals).orElse(false);
        }
        @Override public boolean hasBlockEntity(BlockPos cell) { return blockEntities.contains(cell); }
        @Override public Optional<CampfireStatus> campfire(BlockPos cell) {
            return Optional.ofNullable(campfires.get(cell));
        }
        @Override public Optional<BlockPos> ground(int x, int z) { return Optional.empty(); }
        @Override public Fluid fluid(BlockPos cell) { return Fluid.NONE; }
        @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) { return Optional.empty(); }
        @Override public Sign sign(BlockPos cell) { return Sign.NOT_A_SIGN; }
        @Override public Optional<SeenEntity> entity(int entityId) { return Optional.empty(); }
        @Override public boolean riding(int entityId) { return false; }
        @Override public Optional<Held> heldItem() { return Optional.ofNullable(held); }
        @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
            return Optional.of(target.isEntity()
                    ? ApproachTarget.ofEntity(new AABB(target.cell())) : ApproachTarget.ofBlock(target.cell()));
        }
    }

    private static final class StubInteractions implements UseSeams.BuildsInteraction {
        private final Deque<UseSeams.Built> script = new ArrayDeque<>();

        void script(InteractionResult result, ItemUseAim.Gesture gesture,
                UseSeams.ReadsWorld.Held heldBefore, Runnable onEnd) {
            InteractionResult[] settled = {null};
            Action action = new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    settled[0] = result;
                    onEnd.run();
                    return result.verdict() == InteractionVerdict.APPLIED
                            ? ActionStatus.done()
                            : ActionStatus.failed(Problem.of(Problem.Kind.STUCK, result.scene(), null));
                }
                @Override public String describe() { return "脚本交互"; }
            };
            script.add(new UseSeams.Built.Ready(action, () -> settled[0], gesture, heldBefore, 0, null));
        }

        @Override public UseSeams.Built build(ResolvedTarget target, boolean writesSign) {
            return script.isEmpty()
                    ? new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.STUCK, "脚本用完", null))
                    : script.poll();
        }
    }
}
