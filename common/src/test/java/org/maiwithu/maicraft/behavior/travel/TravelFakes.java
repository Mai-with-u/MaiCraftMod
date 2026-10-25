// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.behavior.travel.planning.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgress;
import org.maiwithu.maicraft.behavior.travel.planning.ReadsSeenTargets;
import org.maiwithu.maicraft.behavior.travel.settle.ReadsPlacedBlocks;

/** 出行的离线测试替身：摆现场、演走到，不碰游戏对象。 */
public final class TravelFakes {

    private TravelFakes() {}

    /** 本刻上下文替身：只有刻号，角色可换（默认没有角色——出行测试多半不碰角色对象）。 */
    public static final class TestTick implements TickContext {
        private long gameTick = 0;
        private PlayerContext player;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return player;
        }

        public void nextTick() {
            gameTick++;
        }

        /** 给这一刻摆上角色（出行记账要看背包）。 */
        public void withPlayer(PlayerContext player) {
            this.player = player;
        }
    }

    /** 角色替身：只有一个背包视图，其余一概不接（出行任务只读背包记账）。 */
    public static final class FakePlayer implements PlayerContext {
        private final BackpackView backpack;

        public FakePlayer(BackpackView backpack) {
            this.backpack = backpack;
        }

        @Override public BackpackView backpack() {
            return backpack;
        }

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("出行测试不碰角色输入");
        }
        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("出行测试不碰交互");
        }
        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("出行测试不碰界面");
        }
        @Override public long clientTick() { return 0; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return false; }
        @Override public boolean tryClaimInteraction() { return false; }
    }

    /** 背包替身：手工登记格子里的物品堆。 */
    public static final class FakeBag implements BackpackView {
        public final List<BackpackStack> stacks = new ArrayList<>();

        public void put(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, true));
        }

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }
    }

    /** 现场替身：角色站哪、朝哪。 */
    public static final class FakeWorld implements TravelWorldView {
        public WorldPosition spot = WorldPosition.here(0, 64, 0);
        public Target.Toward facing = Target.Toward.NORTH;

        @Override public WorldPosition currentSpot() {
            return spot;
        }

        @Override public Target.Toward currentFacing() {
            return facing;
        }

        /** 地表脚的高度；没摆为读不到（那一片没加载）。 */
        public Integer surface;

        /** 逐列摆的地表高度（列 x,z → 脚位高度）：摆水边这类有的柱是水、有的柱是岸的地形。 */
        public final Map<String, Integer> columnSurfaces = new HashMap<>();

        /** 逐列摆的顶盖样本（列 x,z → 样本）：摆"这一列已加载、整列是水"这类事实；没摆的列当未加载。 */
        public final Map<String, ColumnSample> columnSamples = new HashMap<>();

        @Override public OptionalInt surfaceFeet(int x, int z) {
            Integer byColumn = columnSurfaces.get(x + "," + z);
            if (byColumn != null) {
                return OptionalInt.of(byColumn);
            }
            return surface == null ? OptionalInt.empty() : OptionalInt.of(surface);
        }

        @Override public ColumnSample column(int x, int z) {
            ColumnSample byColumn = columnSamples.get(x + "," + z);
            return byColumn != null ? byColumn : ColumnSample.unloaded();
        }
    }

    /** 记过的地点替身：手工登记。 */
    public static final class FakePlaces implements ReadsRememberedPlaces {
        private final Map<String, WorldPosition> places = new HashMap<>();

        public void put(String name, WorldPosition position) {
            places.put(name, position);
        }

        @Override public Optional<WorldPosition> place(String name) {
            return Optional.ofNullable(places.get(name));
        }
    }

    /** 观察编号替身：手工登记编号与看得见的告示牌。 */
    public static final class FakeSeen implements ReadsSeenTargets {
        private final Map<String, WorldPosition> seen = new HashMap<>();
        private final List<SceneSign> signs = new ArrayList<>();

        public void put(String id, WorldPosition position) {
            seen.put(id, position);
        }

        /** 摆一块看得见的告示牌：正面几行字，方位东、距离按给的算。 */
        public void sign(String id, WorldPosition position, int distance, String... frontLines) {
            signs.add(new SceneSign(id, "minecraft:oak_sign", position, "前方", "东", distance,
                    new SignLines(List.of(frontLines), List.of())));
        }

        @Override public Optional<WorldPosition> positionOf(String observationId) {
            return Optional.ofNullable(seen.get(observationId));
        }

        @Override public List<SceneSign> signs() {
            return List.copyOf(signs);
        }
    }

    /** 垫过的方块替身：结算时照实交出。 */
    public static final class FakePlaced implements ReadsPlacedBlocks {
        private final List<BlockPos> placed = new ArrayList<>();

        public void put(BlockPos cell) {
            placed.add(cell);
        }

        @Override public List<BlockPos> placedDuringCurrentWalk() {
            return List.copyOf(placed);
        }
    }

    /** 进展记录替身：把逐刻报告收下来。 */
    public static final class ProgressRecorder implements TravelProgressListener {
        public final List<TravelProgress> received = new ArrayList<>();

        @Override public void onTravelProgress(TravelProgress progress) {
            received.add(progress);
        }
    }

    /**
     * 走到替身：按脚本逐刻交出走到情况；记下收到的导航目标与许可。
     * stop 脚本控制"先请求、落到安全边界才停"要演几刻。
     */
    public static final class FakeWalks implements WalkTo {
        public final List<GoalCompiler.Compiled> started = new ArrayList<>();
        public final List<TerrainPermit> permits = new ArrayList<>();
        private final Deque<ScriptedWalk> walks = new ArrayDeque<>();
        private ScriptedWalk current;

        /** 登记一段走到运行；每次 start 依次取用，取完后复用最后一段。 */
        public void enqueue(ScriptedWalk walk) {
            walks.add(walk);
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            started.add(target);
            permits.add(permit);
            if (!walks.isEmpty()) {
                current = walks.remove();
            }
            return current;
        }
    }

    /** 一段演出来的走到运行。 */
    public static final class ScriptedWalk implements WalkRun {
        private final Deque<WalkReport> reports = new ArrayDeque<>();
        private final Deque<Boolean> stopAnswers = new ArrayDeque<>();
        private WalkReport last = WalkReport.planning();
        public int stopRequests;
        public int pauseRequests;
        /** 从停稳里续走的次数：任务不该收掉重开一趟，而是让停稳的运行接着走。 */
        public int resumes;
        /** 悬在半空走着：叫它先停稳就答应收尾，落地报"已停下"才算停稳；落地的过程由报告脚本演出来。 */
        public boolean airborne;
        public int safeStops;

        /** 被生存需求打断：只记请求；停下的过程由测试用报告脚本演出来。 */
        @Override public void pause() {
            pauseRequests++;
        }

        /** 只有报过"已停下"才续得上：续走后回到正在算路，后面的脚本接着演。 */
        @Override public boolean resume() {
            if (last.state() != WalkReport.State.STOPPED) return false;
            resumes++;
            last = WalkReport.planning();
            return true;
        }

        public ScriptedWalk(WalkReport... reports) {
            for (WalkReport report : reports) {
                this.reports.add(report);
            }
        }

        /** stop() 的回答队列：false 表示"接受了请求还没停稳"，true 表示本刻已停住。 */
        public ScriptedWalk stopAnswers(Boolean... answers) {
            for (Boolean answer : answers) {
                stopAnswers.add(answer);
            }
            return this;
        }

        /** 测试中途改剧本：清掉没演的脚本换成新的。 */
        public void rerun(WalkReport... reports) {
            this.reports.clear();
            for (WalkReport report : reports) {
                this.reports.add(report);
            }
        }

        @Override public ActionStatus tick(TickContext context) {
            // 停稳的运行自己不再往前演：脚本留在原地，续走（resume）之后才接着演后面的报告。
            if (last.state() == WalkReport.State.STOPPED) {
                return ActionStatus.done();
            }
            if (reports.isEmpty()) {
                return ActionStatus.running();
            }
            WalkReport next = reports.remove();
            last = next;
            // 真实的走到运行在路上不逐刻自报进展（进展由出行任务按剩余距离记），替身同样只报"还在做"。
            return switch (next.state()) {
                case ARRIVED, STOPPED -> ActionStatus.done();
                case FAILED -> ActionStatus.failed(next.problem());
                default -> ActionStatus.running();
            };
        }

        @Override public WalkReport report() {
            return last;
        }

        @Override public boolean stop() {
            stopRequests++;
            Boolean answer = stopAnswers.poll();
            return answer == null || answer;
        }

        @Override public boolean stopSafely() {
            if (!airborne) return false;
            safeStops++;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return safeStops == 0 || last.state() == WalkReport.State.STOPPED;
        }

        @Override public String describe() {
            return "演出来的走到";
        }
    }

    /** 给断言用的快捷取值：编好的导航目标。 */
    public static NavGoal goalOf(GoalCompiler.Compiled compiled) {
        return compiled.goal();
    }

    /** 提问原因快捷判断。 */
    public static Question.Reason reasonOf(Question question) {
        return question.reason();
    }

    /** 问题种类快捷判断。 */
    public static Problem.Kind kindOf(Problem problem) {
        return problem.kind();
    }
}
