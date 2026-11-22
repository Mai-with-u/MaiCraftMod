// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 下矿两段式防落沙离线单测：
 * 第一层覆盖地表黑沙走步回填退回重挖、地下沙砾塌落沉降等待与放宽清理额度；
 * 第二层覆盖 2×1 双格交错安全穿沙井进入实岩下 3 格平滑切阶梯，以及探测到洞穴或岩浆时安全中止不坠落。
 */
class ClientStairsDownGravityTest {

    private record Tick(long gameTick) implements TickContext {
        @Override
        public PlayerContext player() {
            return null;
        }
    }

    /** 替身世界：支持标记重力方块（沙子、沙砾、模组黑沙）、下落实体与走步失败回填脚本。 */
    private static final class GravityWorld implements ClientStairsDown.Site {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<String> gravityTypes = new HashSet<>(Set.of("minecraft:sand", "minecraft:gravel", "mod:black_sand"));
        final Set<BlockPos> fallingEntitiesAbove = new HashSet<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        Direction facing = Direction.NORTH;
        int defaultRockTopY = 60;
        String defaultOverburden = "minecraft:dirt";
        final List<BlockPos> walkedTo = new ArrayList<>();
        /** 下几次走步失败并把指定格回填成落沙：模拟 WALK_ONLY 走步被落沙挡住。 */
        int failStepAndRefillRemaining;
        String refillType = "mod:black_sand";
        /** 在指定格下掘完成后触发的动态世界变化（模拟途中突然暴露洞穴或岩浆）。 */
        final Map<BlockPos, Runnable> onDugHooks = new HashMap<>();
        /** 定时落沙脚本：在指定刻号往指定格填入落沙（模拟第 8 刻沙砾落进剖面）。 */
        final Map<Long, Runnable> timedEvents = new HashMap<>();

        String type(BlockPos cell) {
            String set = blocks.get(cell);
            if (set != null) return set;
            if (cell.getY() >= 64) return "minecraft:air";
            return cell.getY() > defaultRockTopY ? defaultOverburden : "minecraft:stone";
        }

        void tickTimed(long gameTick) {
            Runnable event = timedEvents.remove(gameTick);
            if (event != null) event.run();
        }

        @Override
        public Optional<BlockPos> feet(TickContext context) {
            return Optional.of(feet);
        }

        @Override
        public boolean onGround(TickContext context) {
            return true;
        }

        @Override
        public Direction facing(TickContext context) {
            return facing;
        }

        @Override
        public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
            return switch (type(cell)) {
                case "minecraft:air" -> ClientStairsDown.Ground.OPEN;
                case "minecraft:water", "minecraft:lava" -> ClientStairsDown.Ground.FLUID;
                case "minecraft:bedrock" -> ClientStairsDown.Ground.UNBREAKABLE;
                default -> ClientStairsDown.Ground.DIGGABLE;
            };
        }

        @Override
        public String blockType(TickContext context, BlockPos cell) {
            return type(cell);
        }

        @Override
        public String dimension(TickContext context) {
            return "minecraft:overworld";
        }

        @Override
        public boolean fallingBlock(TickContext context, BlockPos cell) {
            return gravityTypes.contains(type(cell));
        }

        @Override
        public boolean hasFallingEntityAbove(TickContext context, BlockPos cell) {
            return fallingEntitiesAbove.contains(cell);
        }

        @Override
        public boolean solidRock(TickContext context, BlockPos cell) {
            return ground(context, cell) == ClientStairsDown.Ground.DIGGABLE
                    && !fallingBlock(context, cell)
                    && !type(cell).equals("minecraft:dirt");
        }

        @Override
        public Action stepTo(BlockPos target) {
            return new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    if (failStepAndRefillRemaining > 0) {
                        failStepAndRefillRemaining--;
                        blocks.put(target, refillType);
                        return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "前方被落沙堵住走不过去"));
                    }
                    walkedTo.add(target);
                    feet = target;
                    return ActionStatus.done();
                }

                @Override
                public String describe() {
                    return "走一步到 " + target.toShortString();
                }
            };
        }
    }

    /** 替身挖掘：记录每格挖掘时的脚位与挖掘目标，便于断言绝未直挖自己脚下。 */
    private static final class RecordingDigs implements DigsBlocks {
        final GravityWorld world;
        final List<BlockPos> dug = new ArrayList<>();
        final List<BlockPos> feetWhenDug = new ArrayList<>();
        /** 某格被挖空后立刻由上方连续塌落补进的次数（模拟地下沙砾口袋）。 */
        final Map<BlockPos, Integer> instantGravelRefills = new HashMap<>();

        RecordingDigs(GravityWorld world) {
            this.world = world;
        }

        @Override
        public Optional<Action> dig(BlockPos target) {
            return Optional.of(new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    dug.add(target.immutable());
                    feetWhenDug.add(world.feet.immutable());
                    int left = instantGravelRefills.getOrDefault(target, 0);
                    if (left > 0) {
                        instantGravelRefills.put(target, left - 1);
                        world.blocks.put(target, "minecraft:gravel");
                    } else {
                        world.blocks.put(target, "minecraft:air");
                    }
                    Runnable hook = world.onDugHooks.remove(target);
                    if (hook != null) hook.run();
                    return ActionStatus.done();
                }

                @Override
                public String describe() {
                    return "挖 " + target.toShortString();
                }
            });
        }
    }

    private static final class NoopDrops implements PicksUpDrops {
        @Override
        public Set<Integer> nearby() {
            return Set.of();
        }

        @Override
        public Action pickUpNewSince(Set<Integer> before) {
            return new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }

                @Override
                public String describe() {
                    return "捡掉落物";
                }
            };
        }
    }

    private static PermissionCheck allowAll() {
        Protection protection = new Protection((dim, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        ReadsCreatureSituation creatures = new ReadsCreatureSituation() {
            @Override
            public Optional<CreatureSituation> situationOf(UUID entityId) {
                return Optional.empty();
            }
        };
        return new PermissionCheck(protection, creatures);
    }

    private static ActionStatus run(GravityWorld world, Action action, int maxTicks) {
        for (long t = 1; t <= maxTicks; t++) {
            world.tickTimed(t);
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("下矿动作在 " + maxTicks + " 刻内未结束");
    }

    @Test
    void 地表黑沙走步时回填下一级_自动退回重挖不直接判失败() {
        GravityWorld world = new GravityWorld();
        // 单层泥土上常规台阶下矿，模拟走入下一级前黑沙意外滑落回填落脚格两次，退回 DIG 重挖后顺利继续。
        world.failStepAndRefillRemaining = 2;
        RecordingDigs digs = new RecordingDigs(world);
        ClientStairsDown stairs = new ClientStairsDown(world, digs, new NoopDrops(), allowAll());

        ActionStatus status = run(world, stairs.digDown("minecraft:stone"::equals, 2, Permissions.DEFAULT), 5000);
        assertInstanceOf(ActionStatus.Done.class, status, "回填 2 次在重试上限内应退回重挖并顺利完成");
        long firstStepDigs = digs.dug.stream().filter(pos -> pos.equals(new BlockPos(0, 63, -1))).count();
        assertEquals(3, firstStepDigs, "第一级落脚格初次挖 1 次 + 两次回填各重挖 1 次 = 3 次");
        assertTrue(world.walkedTo.contains(new BlockPos(0, 63, -1)), "重挖清净后应顺利迈入第一级");
    }

    @Test
    void 地下沙砾口袋塌落_原地等待沉降并放宽额度清净后继续下矿() {
        GravityWorld world = new GravityWorld();
        // 角色在地下 Y=42 实岩层挖阶梯，正前方台阶头顶压着 15 格沙砾口袋（超过旧 12 次上限，但在 24 次重力额度内）。
        world.feet = new BlockPos(0, 42, 0);
        world.defaultRockTopY = 60;
        for (int y = 41; y <= 45; y++) {
            world.blocks.put(new BlockPos(0, y, 0), y >= 42 ? "minecraft:air" : "minecraft:stone");
        }
        BlockPos topAhead = new BlockPos(0, 43, -1);
        world.blocks.put(topAhead, "minecraft:gravel");
        world.blocks.put(topAhead.above(), "minecraft:gravel");
        // 挖完顶部沙砾后先变空并挂上正下落实体，第 8 刻后砸回 topAhead；随后再连塌 13 块，总共清 15 块沙砾。
        RecordingDigs digs = new RecordingDigs(world);
        digs.instantGravelRefills.put(topAhead, 13);
        world.onDugHooks.put(topAhead, () -> {
            // 模拟最后一块沙砾清完瞬间仍有一块在空中下落：等满落定窗内落回后再清一次。
            world.blocks.put(topAhead.above(), "minecraft:air");
        });
        ClientStairsDown stairs = new ClientStairsDown(world, digs, new NoopDrops(), allowAll());

        ActionStatus status = run(world, stairs.digDown("minecraft:stone"::equals, 2, Permissions.DEFAULT), 5000);
        assertInstanceOf(ActionStatus.Done.class, status, "15 格沙砾在放宽后的 24 次重力额度内应清净并挖到石头");
        long gravelClears = digs.dug.stream().filter(topAhead::equals).count();
        assertTrue(gravelClears >= 14, "应连续清理超过旧上限 12 次的沙砾塌落：" + gravelClears);
        assertTrue(world.walkedTo.contains(new BlockPos(0, 41, -1)), "清净后应顺利走下该级台阶");
    }

    @Test
    void 连续黑沙覆盖层触发2x1双格安全穿沙井_下沉至实岩下3格后平滑切入阶梯() {
        GravityWorld world = new GravityWorld();
        // 地表 Y=63..60 连续 4 格模组黑沙（厚度 ≥ 2），Y=59 及以下为实岩。
        world.defaultOverburden = "mod:black_sand";
        world.defaultRockTopY = 59;
        RecordingDigs digs = new RecordingDigs(world);

        // 先验证纯函数预查：正确选出 2×1 双格，目标脚高 = 59 - 3 - 1 = 55（头顶 57,58,59 共 3 格实岩顶板）。
        Optional<SandShaftPlan> plan = SandShaftPlan.evaluate(world, new Tick(0), world.feet, Direction.NORTH,
                cell -> false, cell -> true, Set.of());
        assertTrue(plan.isPresent(), "4 格连续黑沙且 16 格内见实岩应触发 2×1 穿沙井");
        assertEquals(59, plan.get().rockTopY());
        assertEquals(55, plan.get().targetFeetY(), "切回阶梯时头顶上方应保留 3 格实岩顶板");

        // 执行完整下掘直到抵达 Y <= 50：先由 2×1 井下到 Y=55，再平滑切入标准斜向阶梯到达 Y=50。
        ClientStairsDown stairs = new ClientStairsDown(world, digs, new NoopDrops(), allowAll());
        Action action = stairs.digDown(feet -> feet.getY() <= 50, 40, Permissions.DEFAULT, null).orElseThrow();
        ActionStatus status = run(world, action, 10000);
        assertInstanceOf(ActionStatus.Done.class, status, "穿沙井切阶梯应顺利到达 Y <= 50");
        assertTrue(world.feet.getY() <= 50, "最终深度到达目标层：" + world.feet);

        // 核心安全不变量：穿沙井阶段（Y > 55）绝不能直挖角色当前站立列的正下方！
        for (int i = 0; i < digs.dug.size(); i++) {
            BlockPos target = digs.dug.get(i);
            BlockPos standing = digs.feetWhenDug.get(i);
            boolean dugDirectlyUnderSelf = target.getX() == standing.getX()
                    && target.getZ() == standing.getZ()
                    && target.getY() < standing.getY();
            assertFalse(dugDirectlyUnderSelf, "绝不允许 1×1 直挖自己脚下：站在 " + standing + " 挖了 " + target);
        }

        // 验证穿沙井确实在 (0,0) 与 (0,-1) 双列交替下沉到 Y=55，再斜向走出双格井继续阶梯下矿。
        assertTrue(world.walkedTo.stream().anyMatch(pos -> pos.getY() == 55
                && (pos.getX() == 0 && (pos.getZ() == 0 || pos.getZ() == -1))),
                "2×1 穿沙井应将角色安全送达实岩下 3 格目标层 Y=55：" + world.walkedTo);
        assertTrue(world.walkedTo.stream().anyMatch(pos -> pos.getY() <= 52
                && (pos.getX() != 0 || pos.getZ() < -1 || pos.getZ() > 0)),
                "离开穿沙井后应平滑切入标准阶梯水平推进：" + world.walkedTo);
    }

    @Test
    void 穿沙井预查或途中遇洞穴与岩浆_安全中止绝不坠落() {
        // 场景 A：实岩顶面下方 2 格存在岩浆，纯函数预查直接拒绝该井位，但能找到 2 格外的安全横移井位。
        GravityWorld preScanWorld = new GravityWorld();
        preScanWorld.defaultOverburden = "mod:black_sand";
        preScanWorld.defaultRockTopY = 60;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                preScanWorld.blocks.put(new BlockPos(dx, 58, dz), "minecraft:lava");
            }
        }
        Optional<SandShaftPlan> rejected = SandShaftPlan.evaluate(preScanWorld, new Tick(0), preScanWorld.feet,
                Direction.NORTH, cell -> false, cell -> true, Set.of());
        assertTrue(rejected.isEmpty(), "下方 2 格有岩浆时绝不能在原地开井");
        Optional<BlockPos> shifted = SandShaftPlan.findShiftedStart(preScanWorld, new Tick(0), preScanWorld.feet,
                Direction.NORTH, cell -> false, cell -> true);
        assertTrue(shifted.isPresent(), "2 格外无岩浆的安全沙地应被选为横移重选井位");

        // 场景 B：穿沙井下掘途中相邻列 B 下方突然暴露洞穴与岩浆，状态机必须立刻安全中止且绝不迈入危险格。
        GravityWorld midWorld = new GravityWorld();
        midWorld.defaultOverburden = "mod:black_sand";
        midWorld.defaultRockTopY = 59;
        RecordingDigs digs = new RecordingDigs(midWorld);
        SandShaftPlan safePlan = SandShaftPlan.evaluate(midWorld, new Tick(0), midWorld.feet, Direction.NORTH,
                cell -> false, cell -> true, Set.of()).orElseThrow();
        BlockPos colB = safePlan.colB();
        // 挖掉 B 列第一刀底部 (0, 62, -1) 的瞬间，下方 (0, 61, -1) 变成空洞、(0, 60, -1) 露出岩浆。
        BlockPos triggerCut = new BlockPos(colB.getX(), 62, colB.getZ());
        midWorld.onDugHooks.put(triggerCut, () -> {
            midWorld.blocks.put(new BlockPos(colB.getX(), 61, colB.getZ()), "minecraft:air");
            midWorld.blocks.put(new BlockPos(colB.getX(), 60, colB.getZ()), "minecraft:lava");
        });

        SandShaftDigging shaft = new SandShaftDigging(midWorld, digs, new NoopDrops(), allowAll(), Permissions.DEFAULT,
                safePlan, "minecraft:stone"::equals, 10, null, null, cell -> false,
                () -> new DigsBlocks.ToolPrep(10, false, Permissions.DEFAULT, null, null));
        ActionStatus status = run(midWorld, shaft, 500);
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(SandShaftDigging.ShaftExit.ABORTED_HAZARD, shaft.exit(), "下方暴露空洞与岩浆时必须立即安全中止");
        assertEquals(new BlockPos(0, 64, 0), midWorld.feet, "角色必须留在安全列 A 上，绝不跨入上方悬空的 B 列坠落");
        assertTrue(midWorld.walkedTo.isEmpty(), "检测到空洞/岩浆后一次危险走步都不应发生");
    }
}
