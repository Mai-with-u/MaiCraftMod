// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 水下一口气游不过去：同一段走路为换气停下 3 次、离目标都没近 1 格就判这段路从这里走不通；
 * 近过 1 格清零、氧气还多时停下不算、换了目的地从头数、同一次停下只数一回。
 */
class BreathStopsTest {

    /** 箱子在水底深处；角色每次潜到同一个深度就得上去换气。 */
    private static final BlockPos CHEST = new BlockPos(0, 20, 0);
    private static final BlockPos DIVE = new BlockPos(0, 50, 0);
    private static final BlockPos SURFACE = new BlockPos(0, 62, 0);

    private static WalkRunProgress progress() {
        return new WalkRunProgress(GoalCompiler.standOn(CHEST).goal());
    }

    private static void swimAt(WalkRunProgress progress, BlockPos feet) {
        progress.observe(new WalkRunProgress.Observation(feet, false, true, true, false, false));
    }

    // 潜到 feet 处被叫停去换气：交出身体按已停下结算，换完气从水面接着走。
    private static void diveAndBreathe(WalkRunProgress progress, BlockPos feet, boolean outOfBreath) {
        swimAt(progress, feet);
        progress.pause(outOfBreath);
        progress.stopWhereLastSeen(feet);
        assertEquals(WalkReport.State.STOPPED, progress.report().state());
        progress.resume();
    }

    @Test
    void threeBreathStopsWithoutGettingCloserGiveUpAsNoRouteFromHere() {
        // 三次都潜到同一个深度就得上去换气：第三次换完气接着走的第一刻判走不通，不再潜下去重算。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, true);
        diveAndBreathe(progress, DIVE, true);
        diveAndBreathe(progress, DIVE, true);
        swimAt(progress, SURFACE);
        WalkReport report = progress.report();
        assertEquals(WalkReport.State.FAILED, report.state());
        assertTrue(report.noRouteFromHere());
        assertEquals(Problem.Kind.UNREACHABLE, report.problem().kind());
        String message = report.problem().message();
        assertTrue(message.contains("为换气停下了 3 次"), message);
        assertTrue(message.contains("最近到过 " + DIVE.toShortString()), message);
        assertTrue(message.contains("离目标还有约 30 格"), message);
    }

    @Test
    void twoBreathStopsStillKeepWalking() {
        // 只停了两次：照旧接着走。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, true);
        diveAndBreathe(progress, DIVE, true);
        swimAt(progress, SURFACE);
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }

    @Test
    void gettingOneBlockCloserResetsTheCount() {
        // 第二次潜得比开始数时深了 2 格：清零重数，三次停下里只有后两次算数，还不够判死。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, true);
        diveAndBreathe(progress, DIVE.below(2), true);
        diveAndBreathe(progress, DIVE.below(2), true);
        swimAt(progress, SURFACE);
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }

    @Test
    void stoppingWithAirToSpareDoesNotCount() {
        // 被别的事叫停时氧气还多：不算为换气停下。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, false);
        diveAndBreathe(progress, DIVE, false);
        diveAndBreathe(progress, DIVE, false);
        swimAt(progress, SURFACE);
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }

    @Test
    void oneStopIsCountedOnceEvenIfPausedTwice() {
        // 先到安全处再停、再暂停各叫一次：同一次停下只数一回。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, true);
        swimAt(progress, DIVE);
        progress.pause(true);
        progress.pause(true);
        progress.stopWhereLastSeen(DIVE);
        progress.resume();
        swimAt(progress, SURFACE);
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }

    @Test
    void newDestinationStartsCountingAgain() {
        // 停了两次后换了目的地：是新的一段，再停一次不判死。
        WalkRunProgress progress = progress();
        diveAndBreathe(progress, DIVE, true);
        diveAndBreathe(progress, DIVE, true);
        progress.retarget(GoalCompiler.standOn(CHEST.east(40)).goal());
        diveAndBreathe(progress, DIVE, true);
        swimAt(progress, SURFACE);
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }

    @Test
    void ordinaryFailureIsNotNoRouteFromHere() {
        // 一般的走不通（比如长时间没进展）不算从这里到不了，靠近照旧换下一个站位。
        WalkRunProgress progress = progress();
        progress.fail(Problem.of(Problem.Kind.STUCK, "走到长时间没有新进展"), DIVE);
        assertFalse(progress.report().noRouteFromHere());
    }
}
