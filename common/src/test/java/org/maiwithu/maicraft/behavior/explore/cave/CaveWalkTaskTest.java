// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.explore.ExploreDetails;
import org.maiwithu.maicraft.behavior.explore.ExploreFinding;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.perception.CaveOpenings;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 钻洞：起步、看一眼挑下一段、留意的东西、收场与先到安全处再停，都在假洞里演。 */
class CaveWalkTaskTest {

    @TempDir
    Path temp;

    private final CaveFakes.Cave cave = new CaveFakes.Cave();
    private final CaveFakes.Walks walks = new CaveFakes.Walks(cave);
    private final List<CaveFakes.Look> looks = new ArrayList<>();
    private final List<CaveOpenings.Opening> openings = new ArrayList<>();
    private final WalkedArea walked = new WalkedArea();
    private WorldMemory memory;

    private CaveWalkTask task(CaveWalkPlan plan) {
        if (memory == null) {
            memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key");
        }
        return new CaveWalkTask(plan, cave, walks, () -> List.copyOf(openings), () -> {
            CaveFakes.Look look = new CaveFakes.Look();
            looks.add(look);
            return look;
        }, walked, memory, (kind, message) -> { });
    }

    private static CaveWalkPlan plan(int maxDistance) {
        return new CaveWalkPlan(List.of(), false, null, 360, 0, maxDistance, 0, TerrainPermit.NATURAL);
    }

    private static TaskResult run(CaveWalkTask task, int maxTicks) {
        CaveFakes.Tick tick = new CaveFakes.Tick();
        task.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("钻洞 " + maxTicks + " 刻还没收场：" + task.describe());
    }

    private static CaveDetails details(TaskResult result) {
        return assertInstanceOf(CaveDetails.class, result.details());
    }

    private static CaveOpenings.Opening mouth(int x, int y, int z) {
        return new CaveOpenings.Opening(CaveOpenings.Kind.CAVE_MOUTH, new BlockPos(x, y, z), false, 0, 0, x, z, x, z);
    }

    @Test
    void 在洞里就地开始_顺着洞走到尽头收场() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(60, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);

        TaskResult result = run(task(plan(2048)), 2000);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(ExploreDetails.StopReason.FRONTIERS_EXHAUSTED, details(result).stopReason());
        assertTrue(cave.feet.getX() >= 56, "走到了洞的尽头：" + cave.feet);
        assertTrue(walks.started.stream().noneMatch(cell -> cell.getX() < 0), "没往洞外走：" + walks.started);
        assertEquals(details(result).legs().total(), looks.size() - 1, "每走到一处新地方停下转头看一圈");
        assertTrue(result.summary().contains("travel(here)"), "收场说回地面怎么走：" + result.summary());
    }

    @Test
    void 站在半砖上脚下读成下面一格_照常量路开走() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(40, 64, 1));
        cave.feet = new BlockPos(5, 63, 0);

        TaskResult result = run(task(plan(2048)), 2000);

        assertEquals(ExploreDetails.StopReason.FRONTIERS_EXHAUSTED, details(result).stopReason(), result.summary());
        assertFalse(walks.started.isEmpty(), "量完路就走，不在原地反复重量");
    }

    @Test
    void 在地面上走到最近的看得见的洞口_记成到访过的洞口() {
        cave.openGround(new BlockPos(-20, 64, -1), new BlockPos(-1, 64, 1));
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(30, 64, 1));
        cave.feet = new BlockPos(-10, 64, 0);
        openings.add(mouth(0, 64, 0));

        TaskResult result = run(task(plan(2048)), 2000);

        assertEquals(new BlockPos(0, 64, 0), walks.started.getFirst(), "先走到洞口");
        CaveDetails details = details(result);
        assertEquals(new WorldPosition(0, 64, 0, "minecraft:overworld"), details.entrance());
        assertTrue(details.findings().stream().anyMatch(finding -> finding.kind() == ExploreFinding.Kind.TERRAIN
                && "洞口".equals(finding.targetId()) && finding.origin() == MemoryOrigin.VISITED));
        assertTrue(memory.allRecords().stream().anyMatch(record -> record.kind() == MemoryKind.SITE
                && record.origin() == MemoryOrigin.VISITED && record.contents().contains("洞口")),
                "进过的洞口记进世界记忆，回露天走得回来");
    }

    @Test
    void 给了方向只挑那边的洞口() {
        cave.openGround(new BlockPos(-30, 64, -1), new BlockPos(30, 64, 1));
        cave.feet = new BlockPos(0, 64, 0);
        openings.add(mouth(-6, 64, 0));
        openings.add(mouth(10, 64, 0));
        walks.nothingReachable = true;

        run(task(new CaveWalkPlan(List.of(), false, Target.Toward.EAST, 90, 0, 128, 0, TerrainPermit.NATURAL)), 200);

        assertEquals(new BlockPos(10, 64, 0), walks.started.getFirst(), "西边那个更近，但不在东边的扇形里");
        assertFalse(walks.started.contains(new BlockPos(-6, 64, 0)));
    }

    @Test
    void 看不见洞口说清看了多大范围() {
        cave.openGround(new BlockPos(-10, 64, -10), new BlockPos(10, 64, 10));

        TaskResult result = run(task(plan(128)), 200);

        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("32 格"), result.problem().message());
        assertTrue(result.problem().suggestion().contains("obtain"), result.problem().suggestion());
    }

    @Test
    void 看见留意的矿走到够得着的地方收场() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(40, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);
        BlockPos ore = new BlockPos(20, 66, 0);
        cave.sightings.add(new CaveWorld.Sighting("minecraft:iron_ore", ore, true, false, true));
        cave.sightings.add(new CaveWorld.Sighting("minecraft:coal_ore", new BlockPos(12, 63, 1), true, false, false));

        TaskResult result = run(task(new CaveWalkPlan(List.of("#c:ores/iron"), false, null, 360, 0, 128, 0,
                TerrainPermit.NATURAL)), 500);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(ExploreDetails.StopReason.INTEREST_SEEN, details(result).stopReason());
        assertTrue(walks.goals.getLast().sacred().contains(ore.asLong()), "按够得着、看得见它去站位，矿本身不挖开");
        assertTrue(details(result).findings().stream().anyMatch(finding -> finding.kind() == ExploreFinding.Kind.ORE_VEIN
                && "minecraft:coal_ore".equals(finding.targetId()) && finding.blocks() == 1),
                "顺路看见的矿也记下");
    }

    @Test
    void 拐角后面看不见的矿不记() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(30, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);
        BlockPos behindCorner = new BlockPos(9, 63, 1);
        cave.sightings.add(new CaveWorld.Sighting("minecraft:diamond_ore", behindCorner, true, false, true));
        cave.hidden.add(behindCorner);

        TaskResult result = run(task(new CaveWalkPlan(List.of("minecraft:diamond_ore"), false, null, 360, 0, 2048, 0,
                TerrainPermit.NATURAL)), 2000);

        assertEquals(ExploreDetails.StopReason.FRONTIERS_EXHAUSTED, details(result).stopReason(),
                "看不见的留意方块不算看见");
        assertTrue(details(result).findings().stream().noneMatch(finding -> finding.kind() == ExploreFinding.Kind.ORE_VEIN));
    }

    @Test
    void 走满一趟的路程收场_再钻一次不重走() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(400, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);

        TaskResult first = run(task(plan(2048)), 4000);
        assertEquals(ExploreDetails.StopReason.WALK_LIMIT, details(first).stopReason());
        assertTrue(details(first).legs().walkedBlocks() >= CaveWalkTask.WALK_LIMIT);
        int reachedX = cave.feet.getX();

        walks.started.clear();
        run(task(plan(2048)), 4000);
        assertTrue(walks.started.stream().allMatch(cell -> cell.getX() > reachedX),
                "再钻一次接着往没走过的地方走：" + walks.started);
    }

    @Test
    void 离洞口到了说好的距离就收场() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(200, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);

        TaskResult result = run(task(plan(64)), 2000);

        assertEquals(ExploreDetails.StopReason.RADIUS_REACHED, details(result).stopReason());
        assertTrue(walks.started.stream().allMatch(cell -> cell.getX() - 5 <= 64), "不挑界外的：" + walks.started);
    }

    @Test
    void 一段都没走成如实说受阻() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(40, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);
        walks.nothingReachable = true;

        TaskResult result = run(task(plan(2048)), 2000);

        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertEquals(ExploreDetails.StopReason.BLOCKED, details(result).stopReason());
        assertEquals(1, looks.size(), "没走到原地换一处，不再转头");
    }

    @Test
    void 岔路记下来_眼前走完了去最近的岔路() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(40, 64, 1));
        cave.tunnel(new BlockPos(19, 64, -12), new BlockPos(21, 64, -2));
        cave.feet = new BlockPos(5, 64, 0);

        TaskResult result = run(task(plan(2048)), 4000);

        assertEquals(ExploreDetails.StopReason.FRONTIERS_EXHAUSTED, details(result).stopReason());
        assertTrue(walks.started.stream().anyMatch(cell -> cell.getX() >= 36), "主洞走到头：" + walks.started);
        assertTrue(walks.started.stream().anyMatch(cell -> cell.getZ() <= -8), "岔洞也走了：" + walks.started);
    }

    @Test
    void 过不去的方向写进岔路_带上挡着的是什么() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(20, 64, 1));
        for (int z = -1; z <= 1; z++) {
            cave.flood(new BlockPos(21, 64, z), CaveWorld.Liquid.WATER);
            cave.flood(new BlockPos(21, 65, z), CaveWorld.Liquid.WATER);
        }
        cave.feet = new BlockPos(5, 64, 0);

        TaskResult result = run(task(plan(2048)), 2000);

        assertTrue(details(result).branches().stream().anyMatch(branch -> "水".equals(branch.blockedBy())),
                "东头是水：" + details(result).branches());
    }

    @Test
    void 换了维度如实作废() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(60, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);
        CaveWalkTask task = task(plan(2048));
        CaveFakes.Tick tick = new CaveFakes.Tick();
        task.start(tick);
        task.tick(tick);
        cave.dimension = "minecraft:the_nether";

        TickResult after = task.tick(tick);

        TaskResult result = assertInstanceOf(TickResult.Finished.class, after).result();
        assertEquals(TaskResult.Status.CANCELLED, result.status());
    }

    @Test
    void 走在半空被叫停先落地站稳_落地不当成走到() {
        cave.tunnel(new BlockPos(0, 64, -1), new BlockPos(60, 64, 1));
        cave.feet = new BlockPos(5, 64, 0);
        walks.nextAirborne = true;
        CaveWalkTask task = task(plan(2048));
        CaveFakes.Tick tick = new CaveFakes.Tick();
        task.start(tick);
        while (walks.started.isEmpty()) {
            task.tick(tick);
        }
        task.tick(tick);

        assertTrue(task.stopSafely(), "半空中要先收尾");
        assertFalse(task.stoppedSafely());
        task.tick(tick);
        assertTrue(task.stoppedSafely(), "落地站稳了才算停好");
        assertEquals(1, walks.started.size(), "落地不当成走到，不开下一段");
        TaskResult closed = task.close(CloseReason.CANCELLED);
        assertEquals(0, details(closed).legs().total(), "这一段没走完，不算一段");
    }
}
