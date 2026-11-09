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
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往下挖楼梯：土下面是石头时一级一级挖下去，挖够就停在楼梯底；挨着水或岩浆、挖不动、
 * 不许动、下一级脚下是空洞都停下；挖到过想要的方块算做完，一格没挖到以原因失败；
 * 人在自己家里先走出家门再挖，楼梯不挖到自家底下。
 */
class ClientStairsDownTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身世界：地面在 64 层，61–63 层是土，60 层往下是石头；个别格子可以另摆。 */
    private static final class World implements ClientStairsDown.Site {
        final Map<BlockPos, String> placed = new HashMap<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        Direction facing = Direction.NORTH;
        final List<BlockPos> walkedTo = new ArrayList<>();
        /** 身上能垫的废料；默认没有。 */
        List<String> fillers = List.of();
        /** 旁边有没有能对着垫的实地；没有时垫不了。 */
        boolean placeable = true;
        /** 垫过哪几格。 */
        final List<BlockPos> filledAt = new ArrayList<>();

        String type(BlockPos cell) {
            String set = placed.get(cell);
            if (set != null) return set;
            if (cell.getY() >= 64) return "minecraft:air";
            return cell.getY() >= 61 ? "minecraft:dirt" : "minecraft:stone";
        }

        @Override public Optional<BlockPos> feet(TickContext context) {
            return Optional.of(feet);
        }
        @Override public boolean onGround(TickContext context) {
            return true;
        }
        @Override public Direction facing(TickContext context) {
            return facing;
        }
        @Override public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
            return switch (type(cell)) {
                case "minecraft:air" -> ClientStairsDown.Ground.OPEN;
                case "minecraft:water", "minecraft:lava" -> ClientStairsDown.Ground.FLUID;
                case "minecraft:bedrock" -> ClientStairsDown.Ground.UNBREAKABLE;
                case "minecraft:crafting_table" -> ClientStairsDown.Ground.FACILITY;
                default -> ClientStairsDown.Ground.DIGGABLE;
            };
        }
        @Override public String blockType(TickContext context, BlockPos cell) {
            return type(cell);
        }
        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }
        // 岩浆块站上去烫脚：生产实现问通行判断，替身按方块名认。
        @Override public boolean hurtsToStandOn(TickContext context, BlockPos cell) {
            return type(cell).equals("minecraft:magma_block");
        }
        @Override public List<String> fillers(TickContext context) {
            return fillers;
        }
        @Override public Optional<Action> placeFiller(TickContext context, BlockPos cell, String filler) {
            if (!placeable) return Optional.empty();
            return Optional.of(done("垫一块", () -> {
                filledAt.add(cell);
                placed.put(cell, filler);
            }));
        }
        /** 下一次走下一级照这段脚本演（跳在半空、落地）；为 null 时一刻走到。 */
        Action nextStep;

        @Override public Action stepTo(BlockPos target) {
            if (nextStep != null) {
                Action step = nextStep;
                nextStep = null;
                return step;
            }
            return done("走下一级", () -> {
                walkedTo.add(target);
                feet = target;
            });
        }
    }

    /** 替身：挖一格当场挖成空气，按先后记下挖了哪些格、挖掉的是什么。 */
    private static final class Digs implements DigsBlocks {
        final World world;
        final List<BlockPos> dug = new ArrayList<>();
        final List<String> types = new ArrayList<>();

        Digs(World world) {
            this.world = world;
        }

        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(done("挖一格", () -> {
                dug.add(target);
                types.add(world.type(target));
                world.placed.put(target, "minecraft:air");
            }));
        }

        /** 装了连锁挖时才连锁；记下每次连锁的形状、命中面和挖掉了几格。 */
        boolean chaining;
        final List<String> chains = new ArrayList<>();

        @Override public Optional<Action> digChained(BlockPos trigger, Direction face,
                BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak) {
            if (!chaining) return dig(trigger);
            boolean down = shape == BreakAccelerator.ChainShape.DOWNWARD_LINE;
            Direction heading = face.getAxis().isVertical() ? world.facing : face.getOpposite();
            List<BlockPos> line = new ArrayList<>();
            for (int k = 0; k < 64; k++) {
                BlockPos cell = trigger.relative(heading, k).below(down ? k : 0);
                if (world.type(cell).equals("minecraft:air")) break;
                line.add(cell);
            }
            if (line.size() < 2 || !line.stream().allMatch(mayBreak)) return dig(trigger);
            return Optional.of(done("连锁挖", () -> {
                chains.add(shape + " " + face + " " + line.size());
                for (BlockPos cell : line) {
                    dug.add(cell);
                    types.add(world.type(cell));
                    world.placed.put(cell, "minecraft:air");
                }
            }));
        }
    }

    private static final class Drops implements PicksUpDrops {
        @Override public Set<Integer> nearby() {
            return Set.of();
        }
        @Override public Action pickUpNewSince(Set<Integer> before) {
            return done("捡起来", () -> {});
        }
    }

    private static Action done(String what, Runnable effect) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                effect.run();
                return ActionStatus.done();
            }
            @Override public String describe() {
                return what;
            }
        };
    }

    private final World world = new World();
    private final Digs digs = new Digs(world);
    /** 受保护的格子：圈起来的地盘，许可检查点拒绝挖。 */
    private final Set<BlockPos> protectedCells = new HashSet<>();

    private ClientStairsDown stairs() {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(),
                        () -> protectedCells.stream()
                                .map(cell -> new RememberedRegion("别人的地盘", new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), null), 0)).toList(),
                        name -> Optional.empty(), "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new ClientStairsDown(world, digs, new Drops(), check);
    }

    private ActionStatus run(int wantedStone) {
        Action action = stairs().digDown(type -> type.equals("minecraft:stone"), wantedStone, Permissions.DEFAULT);
        for (long t = 0; t < 5000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("挖楼梯一直没收场");
    }

    /** 记账替身：收下动作报上来的变化。 */
    private static final class ChangeLog implements TaskRecords {
        final List<Change> changes = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change);
        }
        @Override public void unconfirmed(Change change) {
            changes.add(change);
        }
        @Override public void attempt(String tried, String whatHappened) {}
    }

    private ActionStatus run(Action action) {
        for (long t = 0; t < 20000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("挖台阶一直没收场");
    }

    @Test
    void 挖到够深的地方就停_挖掉的方块按种类合并记进变化() {
        // 出行的下掘段：站到的地方满足停点判断就收工，挖掉的方块按种类合成一条记进这次的变化。
        ChangeLog log = new ChangeLog();
        Action action = stairs().digDown(feet -> feet.getY() <= 60, 18, Permissions.DEFAULT, log).orElseThrow();

        assertInstanceOf(ActionStatus.Done.class, run(action));
        assertEquals(60, world.feet.getY(), "挖到够深的那一级就停");
        assertFalse(log.changes.isEmpty(), "挖掉的方块进了变化");
        long booked = log.changes.stream().filter(change -> change.kind() == Change.Kind.BLOCK_BROKEN)
                .mapToLong(Change::count).sum();
        assertEquals(digs.dug.size(), booked, "挖掉的每一格都记了账");
        assertTrue(log.changes.stream().allMatch(change -> change.note().contains("往下挖台阶挖掉的")),
                "账目说明写明是下掘段挖掉的");
    }

    @Test
    void 台阶挖到挖不动的层级就停下_挖到过的照样记进变化() {
        // y=58 整层是基岩：挖到基岩上一层几个方向都过不去，带着挖到过的深度收工；已挖掉的土和石头照样记进变化。
        for (int x = -3; x <= 3; x++) {
            for (int z = -8; z <= 3; z++) {
                world.placed.put(new BlockPos(x, 58, z), "minecraft:bedrock");
            }
        }
        ChangeLog log = new ChangeLog();
        Action action = stairs().digDown(feet -> feet.getY() <= 40, 72, Permissions.DEFAULT, log).orElseThrow();

        assertInstanceOf(ActionStatus.Done.class, run(action));
        assertEquals(59, world.feet.getY(), "挖到基岩上一层就停，不再往下");
        assertFalse(digs.dug.isEmpty(), "够不着的层级之前已经挖掉了一些");
        long booked = log.changes.stream().filter(change -> change.kind() == Change.Kind.BLOCK_BROKEN)
                .mapToLong(Change::count).sum();
        assertEquals(digs.dug.size(), booked, "挖掉过的每一格都记了账");
    }

    // 她睡过的床在 (0, 64, 0)：床旁 8 格是她自己的家。
    private ClientStairsDown stairsAtHome() {
        ReadsOwnHome home = new ReadsOwnHome() {
            @Override public Optional<WorldPosition> sleptBed() {
                return Optional.of(new WorldPosition(0, 64, 0, "minecraft:overworld"));
            }

            @Override public List<Building> ownBuildings() {
                return List.of();
            }
        };
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(), name -> Optional.empty(),
                        "self", SeesVillagers.NOBODY, home, id -> id),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new ClientStairsDown(world, digs, new Drops(), check);
    }

    @Test
    void 人在自己家里_先走出家门到家外的地面再挖_楼梯一格不挖在家里() {
        Action action = stairsAtHome().digDown(type -> type.equals("minecraft:stone"), 3, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(action));
        BlockPos spot = world.walkedTo.getFirst();
        assertTrue(Math.sqrt(spot.distSqr(BlockPos.ZERO.above(64))) > Protection.HOME_RADIUS_BLOCKS, "先走到家外：" + spot);
        assertEquals(64, spot.getY(), "走到家外的地面上");
        assertFalse(digs.dug.isEmpty(), "到了家外才挖");
        for (BlockPos cell : digs.dug) {
            assertTrue(Math.sqrt(cell.distSqr(BlockPos.ZERO.above(64))) > Protection.HOME_RADIUS_BLOCKS, "挖到了家里：" + cell);
        }
    }

    @Test
    void 家外的地挑最近的一格_四周三格外也不在家里_找不到就为空() {
        Predicate<BlockPos> flat = cell -> cell.getY() == 64;
        Predicate<BlockPos> home = cell -> Math.sqrt(cell.distSqr(BlockPos.ZERO.above(64))) <= 8.0;
        BlockPos spot = StairsOutsideHome.pick(BlockPos.ZERO.above(64), home, flat).orElseThrow();
        assertFalse(home.test(spot));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            assertFalse(home.test(spot.relative(side, StairsOutsideHome.MARGIN)), "朝 " + side + " 走三格回到了家里");
        }
        assertEquals(Optional.empty(), StairsOutsideHome.pick(BlockPos.ZERO.above(64), cell -> true, flat), "四处都是家");
    }

    @Test
    void 土下面是石头_一级一级挖下去_挖够三格停在楼梯底() {
        assertInstanceOf(ActionStatus.Done.class, run(3));

        // 每一级都从上往下挖：第一级只有脚下那格是土，往后每级三格；挖到第三格石头就收工。
        assertEquals(new BlockPos(0, 63, -1), digs.dug.getFirst());
        assertEquals(3, digs.types.stream().filter("minecraft:stone"::equals).count());
        assertEquals(new BlockPos(0, 59, -5), world.feet);
        for (int i = 1; i < digs.dug.size(); i++) {
            BlockPos before = digs.dug.get(i - 1);
            BlockPos now = digs.dug.get(i);
            if (before.getX() == now.getX() && before.getZ() == now.getZ()) {
                assertTrue(now.getY() < before.getY(), "同一级要从上往下挖：" + digs.dug);
            }
        }
    }

    @Test
    void 走下一级跳在半空被叫先停稳_落地才停_不挖下一格_暂停后按站的位置接着挖() {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(0, 64, -1), false),
                WalkReport.onTheWay(new BlockPos(0, 63, -1), false),
                WalkReport.stopped(new BlockPos(0, 63, -1), false));
        leg.airborne = true;
        world.nextStep = leg;
        Action action = stairs().digDown(type -> type.equals("minecraft:stone"), 3, Permissions.DEFAULT);
        assertFalse(action.stopSafely(), "还没动手：现在就能停");
        long t = 0;
        while (world.nextStep != null && t < 200) action.tick(new Tick(t++));
        action.tick(new Tick(t++));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, action.interruptibility(), "走下一级跳在半空停不得");

        // 跳在半空被叫先到安全处再停：转给走路去落地；落地后原地等着，不当成走下了这一级、不挖下一格。
        assertTrue(action.stopSafely(), "悬在半空，要先落地");
        assertEquals(1, leg.safeStops, "先停稳转给了走路");
        assertFalse(action.stoppedSafely(), "还没落地");
        int dugBefore = digs.dug.size();
        for (int i = 0; i < 4; i++) {
            assertInstanceOf(ActionStatus.Running.class, action.tick(new Tick(t++)), "收尾途中不收场");
        }
        assertTrue(action.stoppedSafely(), "落地站稳了");
        assertEquals(dugBefore, digs.dug.size(), "收尾途中不挖下一格");
        assertEquals(0, leg.closes, "落地停稳的那一趟留着");

        // 暂停过后接着做：按此刻站的位置重新看下一级，照常挖够三格。
        action.pause();
        ActionStatus status = ActionStatus.running();
        while (status instanceof ActionStatus.Running && t < 5000) status = action.tick(new Tick(t++));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(3, digs.types.stream().filter("minecraft:stone"::equals).count());
    }

    @Test
    void 前面挨着岩浆_拐个弯接着挖_挨岩浆的那一级不挖() {
        // 第二级齐脚那格的左边是岩浆：挖开就流进来。往北挖不了，左拐也挨着它，右拐往东接着挖。
        world.placed.put(new BlockPos(-1, 63, -2), "minecraft:lava");

        assertInstanceOf(ActionStatus.Done.class, run(3));
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.getZ() == -2), "挨着岩浆的那一级一格都不挖：" + digs.dug);
        assertTrue(digs.dug.contains(new BlockPos(1, 62, -1)), "第二级拐到东边挖：" + digs.dug);
    }

    @Test
    void 这一级前左右都挨着岩浆_一格石头都没挖到就以危险失败() {
        // 往前、往左、往右都挨着岩浆；往回挖会挖掉来时那一级，也不挖。
        world.placed.put(new BlockPos(-1, 63, -2), "minecraft:lava");
        world.placed.put(new BlockPos(2, 63, -1), "minecraft:lava");

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(3));
        assertEquals(Problem.Kind.DANGER, failed.problem().kind());
        assertTrue(failed.problem().message().contains("minecraft:lava"), failed.problem().message());
        assertTrue(digs.dug.stream().allMatch(cell -> cell.getZ() == -1 && cell.getX() == 0),
                "第二级哪个方向都没挖：" + digs.dug);
    }

    @Test
    void 下一级脚下是岩浆块_不往那边挖_转个方向() {
        // 下界往下挖台阶穿过 Y27–36 的岩浆块层：北边第一级脚下是岩浆块，挖开了也站不上去，转到东边挖。
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:magma_block");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertEquals(new BlockPos(1, 63, 0), digs.dug.getFirst(), "北边脚下是岩浆块，转到东边");
        assertFalse(world.walkedTo.contains(new BlockPos(0, 63, -1)), "不站到岩浆块上：" + world.walkedTo);
    }

    @Test
    void 四面下一级脚下都是岩浆块_一格不挖_写明岩浆块停下() {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            world.placed.put(new BlockPos(0, 62, 0).relative(side), "minecraft:magma_block");
        }

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.DANGER, failed.problem().kind());
        assertTrue(failed.problem().message().contains("minecraft:magma_block"), failed.problem().message());
        assertTrue(digs.dug.isEmpty(), "哪个方向都没挖：" + digs.dug);
    }

    @Test
    void 下一级落一格下去是岩浆_不往那边走_拐开接着挖() {
        // 挖开第一级才看清：脚下空着，再往下一格是岩浆（下界挖穿进洞，洞底是岩浆海）。不停下整趟，换个方向挖。
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");
        world.placed.put(new BlockPos(0, 61, -1), "minecraft:lava");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertTrue(world.walkedTo.stream().noneMatch(cell -> cell.getX() == 0 && cell.getZ() == -1),
                "不往岩浆那边走下去：" + world.walkedTo);
        assertEquals(1, digs.types.stream().filter("minecraft:stone"::equals).count());
    }

    @Test
    void 半路前面是圈起来的格子_拐个弯接着往下挖() {
        // 挖到一半前面是圈地里不许拆的方块：往左、往右拐着接着挖，不为这一格停下整条采掘。
        protectedCells.add(new BlockPos(0, 62, -2));

        assertInstanceOf(ActionStatus.Done.class, run(3));
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 62, -2)::equals), "不许拆的不挖：" + digs.dug);
        assertEquals(3, digs.types.stream().filter("minecraft:stone"::equals).count());
    }

    @Test
    void 半路一格挖不下来_换个方向再挖() {
        // 第二级齐脚那格挖不下来（够不着、被拒）：这个方向记作挖不了，拐弯接着挖。
        DigsBlocks failing = new DigsBlocks() {
            @Override public Optional<Action> dig(BlockPos target) {
                if (target.equals(new BlockPos(0, 63, -2))) {
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "被拒了"));
                        }
                        @Override public String describe() {
                            return "挖不下来";
                        }
                    });
                }
                return digs.dig(target);
            }
        };
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(), name -> Optional.empty(),
                        "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        Action action = new ClientStairsDown(world, failing, new Drops(), check)
                .digDown(type -> type.equals("minecraft:stone"), 2, Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (long t = 0; t < 5000 && status instanceof ActionStatus.Running; t++) status = action.tick(new Tick(t));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(digs.dug.stream().anyMatch(cell -> cell.getX() != 0), "拐了弯：" + digs.dug);
    }

    @Test
    void 挖到一半前面挨着水_拐开接着挖够() {
        // 第五级旁边是水：这一级往北挖开就灌进来，拐个弯接着往下挖，挖够九格石头。
        BlockPos water = new BlockPos(1, 59, -5);
        world.placed.put(water, "minecraft:water");

        assertInstanceOf(ActionStatus.Done.class, run(9));
        assertEquals(9, digs.types.stream().filter("minecraft:stone"::equals).count());
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.distManhattan(water) == 1), "挨着水的格不挖：" + digs.dug);
    }

    @Test
    void 脸对着刚用过的工作台_不拆它_转个方向挖() {
        // 实机（全量测试 10-12）：做完木镐脸正对着工作台，第一级楼梯把它挖走了，接着做石镐就没台子可用。
        world.placed.put(new BlockPos(0, 64, -1), "minecraft:crafting_table");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertTrue(digs.types.stream().noneMatch("minecraft:crafting_table"::equals), "工作台不拆：" + digs.types);
        assertEquals(new BlockPos(1, 63, 0), digs.dug.getFirst(), "北边是工作台，转到东边");
    }

    @Test
    void 脸对着的方向挖不动_转个方向再挖() {
        world.placed.put(new BlockPos(0, 63, -1), "minecraft:bedrock");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertEquals(new BlockPos(1, 63, 0), digs.dug.getFirst(), "北边挖不动，转到东边");
    }

    @Test
    void 圈起来的格子不挖_要许可() {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            protectedCells.add(new BlockPos(0, 63, 0).relative(side));
        }

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(digs.dug.isEmpty());
    }

    @Test
    void 下一级脚下只空一格_下面是实地_照常落下去接着挖() {
        // 站在比地面高一格的地方开挖：下一级脚下空着，再往下一格就是地面，不是洞。
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");

        run(1);
        assertTrue(world.walkedTo.contains(new BlockPos(0, 62, -1)), "落到实地上面那一格：" + world.walkedTo);
    }

    // 第一级脚下那格挖开后，下面三格都是洞穴的空气。
    private void holeUnderFirstStep() {
        world.placed.put(new BlockPos(0, 62, -1), "minecraft:air");
        world.placed.put(new BlockPos(0, 61, -1), "minecraft:air");
        world.placed.put(new BlockPos(0, 60, -1), "minecraft:air");
    }

    @Test
    void 下一级脚下是空洞_身上没垫料_停下写清没法垫() {
        holeUnderFirstStep();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertTrue(failed.problem().message().contains("空的") && failed.problem().message().contains("没法垫"),
                failed.problem().message());
        assertTrue(world.walkedTo.isEmpty(), "脚下是空的不走下去");
    }

    @Test
    void 下一级脚下是空洞_有垫料_垫一块接着往下挖() {
        // 许可默认准放方块、身上有泥土：在下一级脚下垫一块，走下去接着挖到石头。
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");

        assertInstanceOf(ActionStatus.Done.class, run(1));
        assertEquals(List.of(new BlockPos(0, 62, -1)), world.filledAt, "垫在下一级正下方");
        assertTrue(world.walkedTo.contains(new BlockPos(0, 63, -1)), "垫好走下去：" + world.walkedTo);
        assertTrue(digs.types.contains("minecraft:stone"), "接着挖到了石头：" + digs.types);
    }

    @Test
    void 下一级脚下是空洞_那一格不许放_不垫() {
        // 空洞那一格圈在地里（受保护）：不往里垫，停下说清不许垫。
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");
        protectedCells.add(new BlockPos(0, 62, -1));

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("不许垫方块"), failed.problem().message());
        assertTrue(world.filledAt.isEmpty() && world.walkedTo.isEmpty());
    }

    @Test
    void 下一级脚下是空洞_旁边没有能对着垫的实地_停下() {
        holeUnderFirstStep();
        world.fillers = List.of("minecraft:dirt");
        world.placeable = false;

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertTrue(failed.problem().message().contains("没有能对着垫的实地"), failed.problem().message());
        assertTrue(world.walkedTo.isEmpty());
    }

    @Test
    void 挖到一半被打断_回来按此刻站的位置重新看下一级() {
        // 夜里被叫去睡觉、躲怪，回来时人不在原来那一级上：不对着原来那几格接着挖。
        Action action = stairs().digDown(type -> type.equals("minecraft:stone"), 1, Permissions.DEFAULT);
        for (long t = 0; digs.dug.isEmpty(); t++) action.tick(new Tick(t));
        action.pause();
        world.feet = new BlockPos(10, 64, 0);
        for (long t = 100; digs.dug.size() < 2; t++) action.tick(new Tick(t));

        assertEquals(new BlockPos(10, 63, -1), digs.dug.get(1));
    }

    /** 长程挖矿：挖到 targetY 横着挖；站到 sightAt 这一格就看得见矿，看见了去挖（记一笔就做完）。 */
    private final List<BlockPos> minedFrom = new ArrayList<>();

    private ActionStatus prospect(int targetY, int floorY, BlockPos sightAt) {
        return prospectSeeing(targetY, floorY, feet -> feet.equals(sightAt));
    }

    /** 同上，站到哪些格看得见矿由 inSight 说（看矿有半径，走过那一片的任何一格都看得见）。 */
    private ActionStatus prospectSeeing(int targetY, int floorY, Predicate<BlockPos> inSight) {
        DigsStairsDown.Tunnel tunnel = new DigsStairsDown.Tunnel(targetY, floorY,
                type -> type.equals("minecraft:iron_ore"), 3, inSight,
                feet -> Optional.of(done("挖看见的铁矿", () -> minedFrom.add(feet))));
        Action action = stairs().tunnelToward(tunnel, Permissions.DEFAULT).orElseThrow();
        for (long t = 0; t < 20000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("长程挖矿一直没收场");
    }

    @Test
    void 长程挖矿_挖台阶到那一层再横着挖_看见矿就去挖() {
        // 从地面 64 层朝北挖台阶到 56 层（八级），转向东在 56 层横着挖，走到 (4,56,-8) 看见了铁矿。
        ActionStatus status = prospect(56, 0, new BlockPos(4, 56, -8));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of(new BlockPos(4, 56, -8)), minedFrom, "看见了就去挖");
        assertTrue(world.walkedTo.contains(new BlockPos(0, 56, -8)), "第八级落到 56 层：" + world.walkedTo);
        assertTrue(world.walkedTo.contains(new BlockPos(1, 56, -8)), "到了那一层转个向横着走：" + world.walkedTo);
        assertTrue(digs.dug.contains(new BlockPos(1, 57, -8)) && !digs.dug.contains(new BlockPos(1, 55, -8)),
                "横着的通道只挖齐头、齐脚两格，不挖脚下");
    }

    @Test
    void 长程挖矿横着挖满一段还没看见_如实说停在通道尽头() {
        world.feet = new BlockPos(0, 56, 0);
        for (int z = -80; z <= 0; z++) {
            for (int y = 55; y <= 58; y++) world.placed.put(new BlockPos(0, y, z), "minecraft:stone");
            world.placed.put(new BlockPos(0, 56, z), z == 0 ? "minecraft:air" : "minecraft:stone");
            world.placed.put(new BlockPos(0, 57, z), z == 0 ? "minecraft:air" : "minecraft:stone");
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, prospect(56, 0, null));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertTrue(failed.problem().message().contains("横着挖了 " + ClientStairsDown.LEVEL_STEPS), failed.problem().message());
    }

    @Test
    void 长程挖矿在地上横着走_连着空着的格一次走过去_走到头再看() {
        // 站在 64 层的地面上横挖：前面全是空气、脚下是土，一次走八格，不每格停下来寻一次路。
        ActionStatus status = prospect(64, 0, new BlockPos(0, 64, -16));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of(new BlockPos(0, 64, -8), new BlockPos(0, 64, -16)), world.walkedTo,
                "一次走 " + ClientStairsDown.OPEN_RUN + " 格，走到头看见了矿");
        assertEquals(List.of(new BlockPos(0, 64, -16)), minedFrom);
        assertTrue(digs.dug.isEmpty(), "空着的格不挖");
    }

    @Test
    void 横着走过的空格到要挖的格前停下_挖完那格只走一格() {
        world.placed.put(new BlockPos(0, 64, -5), "minecraft:stone");
        prospect(64, 0, new BlockPos(0, 64, -13));
        assertEquals(List.of(new BlockPos(0, 64, -4), new BlockPos(0, 64, -5), new BlockPos(0, 64, -13)),
                world.walkedTo, "停在石头前，挖开那格走一格，后面空着的再一次走过去");
        assertEquals(List.of(new BlockPos(0, 64, -5)), digs.dug);
    }

    @Test
    void 横着连着走的空格挨着岩浆_走到它前面就停() {
        world.placed.put(new BlockPos(1, 64, -3), "minecraft:lava");
        prospect(64, 0, new BlockPos(0, 64, -2));
        assertEquals(new BlockPos(0, 64, -2), world.walkedTo.getFirst(), "挨着岩浆的那格不一口气走过去：" + world.walkedTo);
    }

    @Test
    void 装了连锁挖_往下三行各连锁一次_横着两行各连锁一次() {
        digs.chaining = true;
        // 连锁先把前面一长条挖空，后面空着的几格一口气走过去：横到 x=6 以后就看得见矿。
        ActionStatus status = prospectSeeing(40, 0, feet -> feet.getY() == 40 && feet.getX() >= 6);
        assertInstanceOf(ActionStatus.Done.class, status, () -> status + " 走过：" + world.walkedTo);
        // 往北挖台阶：齐头、齐脚两行对着迎面（朝南那面），脚下那行对着顶面；到 40 层转向东横着挖，两行对着朝西那面。
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("DOWNWARD_LINE south")), digs.chains.toString());
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("DOWNWARD_LINE up")), digs.chains.toString());
        assertTrue(digs.chains.stream().anyMatch(c -> c.startsWith("STRAIGHT_LINE west")), digs.chains.toString());
        assertEquals(1, minedFrom.size(), "看见了就去挖：" + minedFrom);
        assertTrue(minedFrom.getFirst().getX() >= 6, minedFrom.toString());
        assertTrue(digs.chains.size() < digs.dug.size(), "连锁一下挖开一长条，不是一格一格挖");
    }

    @Test
    void 连锁的那一行挨着岩浆_那一行不连锁_走到跟前拐个弯接着挖() {
        digs.chaining = true;
        // 脚下那一行的斜线在第六级挨着岩浆：这一行不连锁、逐格挖；别的两行不挨着照样连锁。
        // 挖到第五级看清第六级挨着岩浆，不往那边挖，拐个弯接着往下。
        world.placed.put(new BlockPos(1, 58, -6), "minecraft:lava");
        prospect(40, 0, null);
        assertFalse(digs.dug.contains(new BlockPos(0, 58, -6)), "挨着岩浆的那一格不挖");
        assertTrue(digs.chains.getFirst().startsWith("DOWNWARD_LINE south"),
                "第一级脚下那行的斜线挨着岩浆不连，先连的是齐脚那行：" + digs.chains);
        assertTrue(world.walkedTo.stream().anyMatch(cell -> cell.getX() < 0), "拐弯接着挖：" + world.walkedTo);
    }

    @Test
    void 一直挖不到石头_挖到上限如实失败() {
        // 地下全是沙子（恶地、沙漠那种）：挖了上限级数还没见到石头。
        for (int y = 0; y < 64; y++) {
            for (int z = -80; z <= 0; z++) {
                for (int x = -1; x <= 1; x++) {
                    world.placed.put(new BlockPos(x, y, z), "minecraft:sand");
                }
            }
        }

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(1));
        assertEquals(Problem.Kind.NOT_FOUND, failed.problem().kind());
        assertEquals(ClientStairsDown.MAX_DRY_STEPS, world.walkedTo.size());
    }
}
