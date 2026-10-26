// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 走到替身：记住上过路的每个目标格与编译出的导航目标，每趟运行按脚本吐状态；脚本走完后一直 Done。
 * Done 时把世界替身的角色挪到刚上路的目标格，模拟真的走到了；悬空时能不能打断说停不得，
 * 叫先停稳就答应，推进两刻后落地按"已停下"结算（不当作到了），之后等续走。
 */
final class StubMoves implements WalkTo {

    /** 上过路的目标格（按编译出的导航目标中心记）。 */
    final List<BlockPos> begun = new ArrayList<>();
    /** 每趟上路时编译出的导航目标，测试用它核对湿脚位与干站位的差别。 */
    final List<GoalCompiler.Compiled> goals = new ArrayList<>();
    final Deque<ActionStatus> script = new ArrayDeque<>();
    /** 收掉（close）过的运行次数。 */
    int closes;
    int pauses;
    /** 从原地续上的次数。 */
    int resumes;
    /** 悬在半空走着：能不能打断说停不得，叫先停稳就答应收尾。 */
    boolean airborne;
    int safeStops;
    /** 每次真的走到（吐出 Done）之后调用，测试用它摆出"走过去时世界变了"的场景。 */
    Runnable onArrive = () -> {};
    private BlockPos heading;
    private final StubWorld world;

    StubMoves(StubWorld world, ActionStatus... steps) {
        this.world = world;
        for (ActionStatus status : steps) script.add(status);
    }

    @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
        begun.add(target.goal().center());
        goals.add(target);
        heading = target.goal().center();
        return new StubRun();
    }

    /** 测试里把角色挪回原点用。 */
    void teleport(BlockPos at) {
        world.feet = at;
    }

    /** 供断言当前朝向是否为空。 */
    BlockPos heading() {
        return heading;
    }

    /** 测试里造一个眼睛位置与站位对齐的辅助。 */
    static Vec3 eyeAt(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
    }

    /** 一趟走向：按脚本推进；被打断后报"已停下"，被叫先停稳则先在空中收尾两刻再落地停下。 */
    private final class StubRun implements WalkRun {
        boolean paused;
        boolean closed;
        /** 叫停之后推进了几刻；没在收尾为 -1。 */
        int stopTicks = -1;

        boolean landed() {
            return stopTicks >= 2;
        }

        @Override public WalkReport report() {
            return paused || landed() ? WalkReport.stopped(BlockPos.ZERO, false)
                    : WalkReport.onTheWay(BlockPos.ZERO, false);
        }

        @Override public boolean stop() { return true; }

        @Override public boolean resume() {
            if (!paused) return false;
            paused = false;
            resumes++;
            return true;
        }

        @Override public ActionStatus tick(TickContext context) {
            // 收尾途中：先在空中收两刻，落地后按"已停下"结算，与真走到一样不把停稳当作到了。
            if (stopTicks >= 0) {
                stopTicks++;
                return landed() ? ActionStatus.done() : ActionStatus.running();
            }
            ActionStatus status = script.isEmpty() ? ActionStatus.done() : script.poll();
            if (status instanceof ActionStatus.Done && heading != null) {
                world.feet = heading;
                world.grounded = true;
                onArrive.run();
            }
            return status;
        }

        @Override public boolean stopSafely() {
            if (!airborne) return false;
            safeStops++;
            stopTicks = 0;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return stopTicks < 0 || landed();
        }

        @Override public Interruptibility interruptibility() {
            return airborne && !landed() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void pause() {
            paused = true;
            pauses++;
        }

        @Override public void close() {
            closed = true;
            closes++;
        }

        @Override public String describe() {
            return "走向替身站位";
        }
    }
}
