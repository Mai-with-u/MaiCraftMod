// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐上载具的每刻决定与多刻推进：坐上了就完，出事如实分清是哪一样，上一下右键还在等就接着等，
 * 够不着走过去，潜行按着先松再点，够得着才右键，连点三次为止。
 */
class BoardsVehicleTest {

    private BoardsVehicle.Scene scene(boolean riding, boolean gone, boolean freeSeat, boolean inReach, int clicks) {
        return new BoardsVehicle.Scene(riding, gone, freeSeat, inReach, false, 0, clicks, false);
    }

    @Test
    void alreadyRidingCountsAsDoneEvenIfLaterChecksWouldFail() {
        // 已经坐在上面：别的都不用看，坐上就是坐上。
        assertEquals(BoardsVehicle.Next.DONE,
                BoardsVehicle.decide(scene(true, true, false, false, BoardsVehicle.MAX_CLICKS)));
    }

    @Test
    void vehicleGoneFailsBeforeClicking() {
        assertEquals(BoardsVehicle.Next.GONE, BoardsVehicle.decide(scene(false, true, true, true, 0)));
    }

    @Test
    void seatTakenFailsBeforeClicking() {
        assertEquals(BoardsVehicle.Next.NO_SEAT, BoardsVehicle.decide(scene(false, false, false, true, 0)));
    }

    @Test
    void outOfReachWalksInsteadOfClicking() {
        assertEquals(BoardsVehicle.Next.WALK, BoardsVehicle.decide(scene(false, false, true, false, 0)));
    }

    @Test
    void clicksUpToTheLimitThenGivesUp() {
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(scene(false, false, true, true, 0)));
        assertEquals(BoardsVehicle.Next.CLICK,
                BoardsVehicle.decide(scene(false, false, true, true, BoardsVehicle.MAX_CLICKS - 1)));
        assertEquals(BoardsVehicle.Next.GIVE_UP,
                BoardsVehicle.decide(scene(false, false, true, true, BoardsVehicle.MAX_CLICKS)));
    }

    /** 泅渡或贴边站位留下的潜行还按在身上：先松开再点，不把这一下白白送给原版的"潜行即想下来"。 */
    @Test
    void heldSneakReleasesBeforeClicking() {
        BoardsVehicle.Scene sneakHeld = new BoardsVehicle.Scene(false, false, true, true, true, 0, 0, false);
        assertEquals(BoardsVehicle.Next.RELEASE_SNEAK, BoardsVehicle.decide(sneakHeld));
    }

    /** 潜行松不开也不无限等：宽限耗尽就照常点，收场报文会带着当时的按键现场。 */
    @Test
    void sneakReleaseGivesUpWaitingAfterTheGraceAndClicksAnyway() {
        BoardsVehicle.Scene stillHeldAtGraceEnd = new BoardsVehicle.Scene(false, false, true, true, true,
                BoardsVehicle.SNEAK_RELEASE_SETTLE_TICKS + 1, 0, false);
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(stillHeldAtGraceEnd));
        // 宽限的最后一刻还在等：不点，把松开的机会用完。
        BoardsVehicle.Scene lastGraceTick = new BoardsVehicle.Scene(false, false, true, true, true,
                BoardsVehicle.SNEAK_RELEASE_SETTLE_TICKS, 0, false);
        assertEquals(BoardsVehicle.Next.RELEASE_SNEAK, BoardsVehicle.decide(lastGraceTick));
    }

    /** 潜行已松开：不再拦着点击，照常走右键那一步。 */
    @Test
    void releasedSneakDoesNotBlockClicking() {
        BoardsVehicle.Scene released = new BoardsVehicle.Scene(false, false, true, true, false, 3, 0, false);
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(released));
    }

    /**
     * 上一下右键还没出结论（还在转头、或在等服务器设乘客）：接着等它。三次机会已经用到头、
     * 它漂开了一点、潜行还按着，都不另点也不放弃；坐上了、它没了、座满了照常先判。
     */
    @Test
    void aClickInFlightIsWaitedOutBeforeAnythingElseButTheVerdicts() {
        int limit = BoardsVehicle.MAX_CLICKS;
        assertEquals(BoardsVehicle.Next.KEEP_CLICKING,
                BoardsVehicle.decide(new BoardsVehicle.Scene(false, false, true, true, false, 0, limit, true)));
        assertEquals(BoardsVehicle.Next.KEEP_CLICKING,
                BoardsVehicle.decide(new BoardsVehicle.Scene(false, false, true, false, false, 0, 1, true)));
        assertEquals(BoardsVehicle.Next.KEEP_CLICKING,
                BoardsVehicle.decide(new BoardsVehicle.Scene(false, false, true, true, true, 0, 1, true)));
        assertEquals(BoardsVehicle.Next.DONE,
                BoardsVehicle.decide(new BoardsVehicle.Scene(true, false, true, true, false, 0, 1, true)));
        assertEquals(BoardsVehicle.Next.GONE,
                BoardsVehicle.decide(new BoardsVehicle.Scene(false, true, true, true, false, 0, 1, true)));
        assertEquals(BoardsVehicle.Next.NO_SEAT,
                BoardsVehicle.decide(new BoardsVehicle.Scene(false, false, false, true, false, 0, 1, true)));
    }

    /**
     * 实机节奏：一下右键要先平滑转头三刻、再等服务器两刻才把角色设成乘客。原先每刻都重点一次，
     * 第四刻就按"点了 3 次都没坐上"放弃；现在只点这一下，第五刻坐上。
     */
    @Test
    void oneSlowClickIsEnoughToBoard() {
        Sight sight = new Sight();
        List<ScriptedClick> clicks = new ArrayList<>();
        BoardsVehicle boarding = boarding(sight, () -> {
            ScriptedClick click = new ScriptedClick(5, null, () -> sight.riding = true);
            clicks.add(click);
            return click;
        });

        List<ActionStatus> seen = new ArrayList<>();
        for (int tick = 1; tick <= 5; tick++) {
            seen.add(boarding.tick(TickContext.of(tick, null)));
        }

        assertEquals(1, clicks.size(), "这一下还在转头、等确认时不另点：" + seen);
        for (int i = 0; i < 4; i++) {
            assertInstanceOf(ActionStatus.Running.class, seen.get(i), "第 " + (i + 1) + " 刻还在等这一下：" + seen);
        }
        assertInstanceOf(ActionStatus.Done.class, seen.get(4), "第五刻服务器把角色设成乘客，坐上了：" + seen);
        assertEquals(5, clicks.get(0).ticks, "同一下右键一路推进到出结论");
    }

    /** 每一下都要等两刻才失败（确认窗过期）：失败一下才点下一下，点满三下都没坐上如实放弃，带上最后一下的原因。 */
    @Test
    void failedClicksAreRetriedOneAtATimeUpToTheLimit() {
        Sight sight = new Sight();
        List<ScriptedClick> clicks = new ArrayList<>();
        BoardsVehicle boarding = boarding(sight, () -> {
            ScriptedClick click = new ScriptedClick(2, Problem.of(Problem.Kind.STUCK, "确认窗口过期"), () -> {});
            clicks.add(click);
            return click;
        });

        ActionStatus last = null;
        int tick = 0;
        while (tick < 30 && !(last instanceof ActionStatus.Failed) && !(last instanceof ActionStatus.Done)) {
            last = boarding.tick(TickContext.of(++tick, null));
        }

        assertEquals(BoardsVehicle.MAX_CLICKS, clicks.size(), "点满三下才放弃");
        for (ScriptedClick click : clicks) {
            assertEquals(2, click.ticks, "每一下都推进到出结论才点下一下");
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, last);
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
        assertTrue(failed.problem().message().contains("点了 3 次都没坐上"), failed.problem().message());
        assertTrue(failed.problem().message().contains("确认窗口过期"), failed.problem().message());
        assertEquals(2 * BoardsVehicle.MAX_CLICKS + 1, tick, "三下各两刻，第七刻放弃");
    }

    /**
     * 走过去的路上悬在半空被叫先到安全处再停：转给走路去落地，落地前不报停稳；落地后原地等着，不右键、不另起一趟走；
     * 暂停过后接着做，按够不够得着重新走过去。站在地上走着、站着右键时现在就能停。
     */
    @Test
    void walkingOverInMidAirLandsBeforeStoppingAndNeitherClicksNorSetsOffAgain() {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 65, 0), false),
                WalkReport.onTheWay(new BlockPos(2, 65, 0), false),
                WalkReport.stopped(new BlockPos(2, 64, 0), false));
        leg.airborne = true;
        List<TravelFakes.ScriptedWalk> started = new ArrayList<>();
        WalkTo walks = (target, permit) -> {
            TravelFakes.ScriptedWalk run = started.isEmpty() ? leg
                    : new TravelFakes.ScriptedWalk(WalkReport.onTheWay(new BlockPos(3, 64, 0), false));
            started.add(run);
            return run;
        };
        List<ScriptedClick> clicks = new ArrayList<>();
        BoardsVehicle boarding = new BoardsVehicle(walks, TerrainPermit.NATURAL, "那条船",
                context -> new BoardsVehicle.Sighting(false, false, true, false, false, new BlockPos(6, 63, 0)),
                () -> {
                    ScriptedClick click = new ScriptedClick(2, null, () -> {});
                    clicks.add(click);
                    return click;
                });
        assertFalse(boarding.stopSafely(), "还没走起来：现在就能停");
        boarding.tick(TickContext.of(1, null));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, boarding.interruptibility(), "走过去的路上悬在半空停不得");

        assertTrue(boarding.stopSafely(), "悬在半空，要先落地");
        assertEquals(1, leg.safeStops, "先停稳转给了走路");
        assertFalse(boarding.stoppedSafely(), "还没落地");
        for (int tick = 2; tick <= 5; tick++) {
            assertInstanceOf(ActionStatus.Running.class, boarding.tick(TickContext.of(tick, null)), "收尾途中不收场");
        }
        assertTrue(boarding.stoppedSafely(), "落地站稳了");
        assertEquals(1, started.size(), "收尾途中不另起一趟走");
        assertEquals(0, leg.closes, "落地停稳的那一趟留着，不当成走到了收掉");
        assertTrue(clicks.isEmpty(), "收尾途中不右键");

        // 暂停过后接着做：还够不着，重新走过去。
        boarding.pause();
        for (int tick = 6; tick <= 8; tick++) boarding.tick(TickContext.of(tick, null));
        assertEquals(2, started.size(), "暂停过后按够不够得着重新走过去");
        assertFalse(boarding.stopSafely(), "站在地上走着：现在就能停");
    }

    // 够得着、有空座、潜行没按的现场；不该走路，走路入口一被调用就算错。
    private static BoardsVehicle boarding(Sight sight, Supplier<Action> clicks) {
        WalkTo noWalking = (target, permit) -> {
            throw new AssertionError("够得着载具时不该走路");
        };
        return new BoardsVehicle(noWalking, TerrainPermit.NATURAL, "那条船",
                context -> new BoardsVehicle.Sighting(sight.riding, false, true, true, false, BlockPos.ZERO),
                clicks);
    }

    /** 替身现场：角色坐没坐上，由右键替身在"服务器确认"那一刻改。 */
    private static final class Sight {
        boolean riding;
    }

    /** 替身右键：推进满几刻出结论；problem 为空是坐上了（出结论前先让现场变成已坐上），否则按这个原因失败。 */
    private static final class ScriptedClick implements Action {
        private final int ticksToVerdict;
        private final Problem problem;
        private final Runnable onApplied;
        int ticks;

        ScriptedClick(int ticksToVerdict, Problem problem, Runnable onApplied) {
            this.ticksToVerdict = ticksToVerdict;
            this.problem = problem;
            this.onApplied = onApplied;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            ticks++;
            if (ticks < ticksToVerdict) return ActionStatus.running();
            if (problem != null) return ActionStatus.failed(problem);
            onApplied.run();
            return ActionStatus.done();
        }

        @Override
        public String describe() {
            return "替身右键";
        }
    }
}
