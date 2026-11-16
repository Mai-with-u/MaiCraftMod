// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.CampPits;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 填坑任务离线测试：验证无坑直返、无多余废料不另找材料、逐格填平、废料中途耗尽收尾与观察编号失效处理。
 */
class FillPitTaskTest {

    @Test
    void completesCleanlyWhenNoCampPitsNearby() {
        FakeSite site = new FakeSite();
        TaskResult result = runToEnd(new FillPitTask(
                new FillPitInput(null, 16, Permissions.DEFAULT), site));

        assertEquals(TaskResult.Status.DONE, result.status());
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals(0, details.get("pits_filled").getAsInt());
        assertEquals(0, details.get("blocks_placed").getAsInt());
    }

    @Test
    void failsWithNeedItemWithoutMiningWhenNoSpareFillerCarried() {
        FakeSite site = new FakeSite();
        site.addPit(new BlockPos(2, 64, 0), 2, List.of(new BlockPos(2, 63, 0), new BlockPos(2, 64, 0)));

        TaskResult result = runToEnd(new FillPitTask(
                new FillPitInput(null, 16, Permissions.DEFAULT), site));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
    }

    @Test
    void fillsCampPitLevelWithCarriedDirtAndStepsToRimFirst() {
        FakeSite site = new FakeSite();
        site.playerFeet = new BlockPos(2, 63, 0);
        site.rimStand = new BlockPos(1, 65, 0);
        site.giveFiller("minecraft:dirt", 5);
        List<BlockPos> cells = List.of(new BlockPos(2, 63, 0), new BlockPos(2, 64, 0));
        site.addPit(new BlockPos(2, 64, 0), 2, cells);

        TaskResult result = runToEnd(new FillPitTask(
                new FillPitInput(null, 16, Permissions.DEFAULT), site));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(new BlockPos(1, 65, 0), site.playerFeet);
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals(1, details.get("pits_filled").getAsInt());
        assertEquals(2, details.get("blocks_placed").getAsInt());
        assertFalse(details.has("filler_exhausted"));
        assertTrue(details.getAsJsonArray("pits").get(0).getAsJsonObject().get("filled").getAsBoolean());
    }

    @Test
    void stopsGracefullyAndMarksExhaustedWhenSpareFillerRunsOutMidway() {
        FakeSite site = new FakeSite();
        site.giveFiller("minecraft:cobblestone", 2);
        List<BlockPos> cells = List.of(
                new BlockPos(3, 62, 0), new BlockPos(3, 63, 0), new BlockPos(3, 64, 0));
        site.addPit(new BlockPos(3, 64, 0), 3, cells);

        TaskResult result = runToEnd(new FillPitTask(
                new FillPitInput(new Target.Seen("f4"), 16, Permissions.DEFAULT), site));

        assertEquals(TaskResult.Status.DONE, result.status());
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals(0, details.get("pits_filled").getAsInt());
        assertEquals(2, details.get("blocks_placed").getAsInt());
        assertTrue(details.get("filler_exhausted").getAsBoolean());
    }

    @Test
    void reportsTargetGoneWhenSeenIdExpired() {
        FakeSite site = new FakeSite();
        site.seenPositions.clear();

        TaskResult result = runToEnd(new FillPitTask(
                new FillPitInput(new Target.Seen("f9"), 16, Permissions.DEFAULT), site));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
    }

    private static TaskResult runToEnd(FillPitTask task) {
        task.start(TickContext.of(0, null));
        for (long t = 1; t <= 40; t++) {
            TickResult r = task.tick(TickContext.of(t, null));
            if (r instanceof TickResult.Finished) return task.close(CloseReason.FINISHED);
        }
        throw new AssertionError("填坑任务在 40 刻内没有结束");
    }

    private static final class FakeSite implements FillPitSeams.Site {
        BlockPos playerFeet = new BlockPos(0, 65, 0);
        BlockPos rimStand;
        final Map<String, WorldPosition> seenPositions = new HashMap<>(
                Map.of("f4", new WorldPosition(3, 64, 0, "minecraft:overworld")));
        final List<CampPits.Pit> pits = new ArrayList<>();
        final Set<BlockPos> openCells = new HashSet<>();
        final Deque<String> fillers = new ArrayDeque<>();

        void addPit(BlockPos center, int depth, List<BlockPos> fillCells) {
            pits.add(new CampPits.Pit(center, center.getY(), depth, 1, fillCells));
            openCells.addAll(fillCells);
        }

        void giveFiller(String item, int count) {
            for (int i = 0; i < count; i++) fillers.add(item);
        }

        @Override public BlockPos feet(TickContext context) { return playerFeet; }
        @Override public String dimension(TickContext context) { return "minecraft:overworld"; }
        @Override public Optional<WorldPosition> seen(String seenId) { return Optional.ofNullable(seenPositions.get(seenId)); }
        @Override public Optional<WorldPosition> landmark(String name) { return Optional.empty(); }
        @Override public List<SceneSign> signs() { return List.of(); }
        @Override public boolean cellProtected(WorldPosition position, Set<String> protectedLandmarks) { return false; }
        @Override public List<CampPits.Pit> scanPits(TickContext context, BlockPos origin, int radius, boolean requireCamp) { return List.copyOf(pits); }
        @Override public List<String> availableFillers(TickContext context) { return fillers.isEmpty() ? List.of() : List.of(fillers.peek()); }
        @Override public boolean needsFill(TickContext context, BlockPos cell) { return openCells.contains(cell); }

        @Override
        public Optional<BlockPos> rimStandFor(TickContext context, BlockPos cell, List<BlockPos> pitCells) {
            return rimStand != null && !playerFeet.equals(rimStand) ? Optional.of(rimStand) : Optional.empty();
        }

        @Override
        public Action walkTo(BlockPos feet) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { playerFeet = feet; return ActionStatus.done(); }
                @Override public String describe() { return "走到 " + feet; }
            };
        }

        @Override
        public Optional<Action> placeFiller(TickContext context, BlockPos cell, String fillerId) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) { fillers.poll(); openCells.remove(cell); return ActionStatus.done(); }
                @Override public String describe() { return "放置 " + fillerId; }
            });
        }
    }
}
