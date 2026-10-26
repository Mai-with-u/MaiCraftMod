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
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往上挖楼梯：石头里一级一级挖上去，每级先头顶、再前上方、再前方齐头，走上去，到露天停；
 * 挨着水换方向；头顶压着沙砾照挖、掉下来再挖；不许动的格拐弯绕开；挖掉的方块合并成一条变化。
 */
class ClientStairsUpTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身世界：69 层及以下是石头，70 层往上是空气；人站在 60 层一个两格高的小洞里。 */
    private static final class World implements ClientStairsDown.Site {
        final Map<BlockPos, String> placed = new HashMap<>();
        BlockPos feet = new BlockPos(0, 60, 0);
        Direction facing = Direction.NORTH;

        World() {
            placed.put(new BlockPos(0, 60, 0), "minecraft:air");
            placed.put(new BlockPos(0, 61, 0), "minecraft:air");
        }

        String type(BlockPos cell) {
            String set = placed.get(cell);
            if (set != null) return set;
            return cell.getY() >= 70 ? "minecraft:air" : "minecraft:stone";
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
                default -> ClientStairsDown.Ground.DIGGABLE;
            };
        }
        @Override public String blockType(TickContext context, BlockPos cell) {
            return type(cell);
        }
        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }
        /** 下一次走上一级照这段脚本演（跳在半空、落地）；为 null 时一刻走到。 */
        Action nextStep;

        @Override public Action stepTo(BlockPos target) {
            if (nextStep != null) {
                Action step = nextStep;
                nextStep = null;
                return step;
            }
            return done("走上一级", () -> feet = target);
        }
    }

    /** 替身：挖一格当场挖成空气，按先后记下挖了哪些格；上面压着沙砾的，挖空了沙砾掉进这一格。 */
    private final class Digs implements DigsBlocks {
        final List<BlockPos> dug = new ArrayList<>();

        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(done("挖一格", () -> {
                dug.add(target);
                boolean gravelAbove = world.type(target.above()).equals("minecraft:gravel");
                world.placed.put(target, gravelAbove ? "minecraft:gravel" : "minecraft:air");
                if (gravelAbove) world.placed.put(target.above(), "minecraft:air");
            }));
        }
    }

    private static final class Records implements TaskRecords {
        final List<Change> changes = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change);
        }
        @Override public void unconfirmed(Change change) {}
        @Override public void attempt(String tried, String whatHappened) {}
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
    private final Digs digs = new Digs();
    private final Records records = new Records();
    /** 受保护的格子：别人放的方块，许可检查点拒绝挖。 */
    private final Set<BlockPos> protectedCells = new HashSet<>();

    private Action climbing() {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> protectedCells.contains(new BlockPos(x, y, z))
                        ? Optional.of(new ReadsBlockOwnership.PlacedBy("someone")) : Optional.empty(),
                        () -> List.of(), name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new ClientStairsUp(world, digs, check).digUp(feet -> feet.getY() >= 70, Permissions.DEFAULT, records);
    }

    @Test
    void 走上一级跳在半空被叫先停稳_落地才停_不挖下一格_暂停后按站的位置接着挖() {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(0, 61, 0), false),
                WalkReport.onTheWay(new BlockPos(0, 61, -1), false),
                WalkReport.stopped(new BlockPos(0, 61, -1), false));
        leg.airborne = true;
        world.nextStep = leg;
        Action action = climbing();
        assertFalse(action.stopSafely(), "还没动手：现在就能停");
        long t = 0;
        while (world.nextStep != null && t < 200) action.tick(new Tick(t++));
        action.tick(new Tick(t++));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, action.interruptibility(), "走上一级跳在半空停不得");

        // 跳在半空被叫先到安全处再停：转给走路去落地；落地后原地等着，不当成走上了这一级、不挖下一格。
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

        // 暂停过后接着做：按此刻站的位置重新看这一级，照常挖到地面。
        action.pause();
        ActionStatus status = ActionStatus.running();
        while (status instanceof ActionStatus.Running && t < 5000) status = action.tick(new Tick(t++));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(70, world.feet.getY());
    }

    private ActionStatus climb() {
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> protectedCells.contains(new BlockPos(x, y, z))
                        ? Optional.of(new ReadsBlockOwnership.PlacedBy("someone")) : Optional.empty(),
                        () -> List.of(), name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        // 出来了 = 站在地面那一层（70 层）上，头顶再没有石头。
        Action action = new ClientStairsUp(world, digs, check)
                .digUp(feet -> feet.getY() >= 70, Permissions.DEFAULT, records);
        for (long t = 0; t < 5000; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("挖楼梯一直没收场");
    }

    @Test
    void 石头里一级一级挖上去_到地面停下() {
        assertInstanceOf(ActionStatus.Done.class, climb());

        // 第一级：先挖头顶，再前上方，再前方齐头；一路朝北挖上去，十级到 70 层。
        assertEquals(List.of(new BlockPos(0, 62, 0), new BlockPos(0, 62, -1), new BlockPos(0, 61, -1)),
                digs.dug.subList(0, 3));
        assertEquals(new BlockPos(0, 70, -10), world.feet);
        // 挖掉的石头合成一条变化，件数就是挖掉的格数。
        assertEquals(1, records.changes.size(), records.changes.toString());
        Change change = records.changes.getFirst();
        assertEquals(Change.Kind.BLOCK_BROKEN, change.kind());
        assertEquals("minecraft:stone", change.what());
        assertEquals(digs.dug.size(), change.count());
    }

    @Test
    void 前面挨着地下水_换个方向挖() {
        // 第一级朝北：前上方那格的西边是水，挖开就灌进来。顺时针换到东边接着挖。
        world.placed.put(new BlockPos(-1, 62, -1), "minecraft:water");

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(cell -> cell.getZ() == -1 && cell.getY() <= 62),
                "挨着水的那一级一格都不挖：" + digs.dug);
        assertTrue(digs.dug.contains(new BlockPos(1, 61, 0)), "第一级拐到东边挖：" + digs.dug);
    }

    @Test
    void 头顶上面压着沙砾_照挖_掉下来再挖一下接着往上() {
        // 头顶那格上面是沙砾：挖开头顶它掉下来，再挖一下就通了，照样挖到地面。
        world.placed.put(new BlockPos(0, 63, 0), "minecraft:gravel");

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertEquals(List.of(new BlockPos(0, 62, 0), new BlockPos(0, 62, 0)), digs.dug.subList(0, 2),
                "头顶挖了两下（第二下挖掉掉下来的沙砾）：" + digs.dug);
        assertEquals(70, world.feet.getY());
    }

    @Test
    void 半路前面是别人放的方块_拐个弯接着往上挖() {
        // 第三级前方齐头那格不许拆：往左、往右拐着接着挖，到地面照样出来。
        protectedCells.add(new BlockPos(0, 63, -3));

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 63, -3)::equals), "不许拆的不挖：" + digs.dug);
        assertEquals(70, world.feet.getY());
    }

    @Test
    void 前方脚下是空洞_这个方向不挖() {
        // 朝北那一级脚下是洞，站不上去：换个方向。
        world.placed.put(new BlockPos(0, 60, -1), "minecraft:air");

        assertInstanceOf(ActionStatus.Done.class, climb());
        assertTrue(digs.dug.stream().noneMatch(new BlockPos(0, 61, -1)::equals), "朝北那一级不挖：" + digs.dug);
    }
}
