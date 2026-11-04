// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.FEET;
import static org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.Situation.calm;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.Events;
import org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.FakeMoves;
import org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.FinishesAfter;
import org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.ScriptedView;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 着火自救：出岩浆、没岸时喝药垫块游远岸、迈出火、跳水灭火，按处境挑做哪一步；做不了如实发事件。 */
class BurningTaskTest {

    private static final BlockPos BANK = new BlockPos(2, 65, 0);

    private final FakeMoves moves = new FakeMoves();
    private final Events events = new Events();

    @Test
    void headsForTheNearestBankHoldingJumpUntilOutOfLava() {
        moves.exits = List.of(BANK);
        TaskResult result = run(new ScriptedView(calm().inLava(), calm().inLava(), calm()), 3);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("出了岩浆"), result.summary());
        assertEquals(BANK, moves.steered.get(0));
        assertTrue(moves.jumps.get(0), "岩浆里按住跳往上游、借跳上岸");
    }

    @Test
    void switchesToTheNextBankWhenOneCannotBeReached() {
        // 第一个岸 20 刻都挪不近（隔着东西）：换下一个。
        BlockPos other = new BlockPos(0, 65, -2);
        moves.exits = List.of(BANK, other);
        moves.blocked = true;
        BurningTask task = new BurningTask(new ScriptedView(calm().inLava()), moves, events);
        task.start(context(0));
        for (int tick = 0; tick < BurningTask.EXIT_STALL_TICKS + 2; tick++) task.tick(context(tick));
        assertEquals(other, moves.steered.get(moves.steered.size() - 1));
    }

    @Test
    void climbingAgainstTheBankKeepsTheSameExit() {
        // 顶在岸边往上蹿：水平离出口一直是 0.8 格，脚每刻升高 0.04 格，过了换岸的 20 刻也不换岸。
        BlockPos other = new BlockPos(0, 65, -2);
        moves.exits = List.of(BANK, other);
        moves.climbPerTick = 0.04;
        BurningTask task = new BurningTask(new ScriptedView(calm().inLava()), moves, events);
        task.start(context(0));
        for (int tick = 0; tick < BurningTask.EXIT_STALL_TICKS + 5; tick++) task.tick(context(tick));
        assertFalse(moves.steered.contains(other), "爬着的岸没被当成挪不过去");
    }

    @Test
    void withNoBankNearbyItDrinksFirstThenLooksForAShoreCalmly() {
        // 湖中间、3 格内没岸、带着防火药水：先喝；喝下去岩浆伤不着，不再发事件，慢慢往远处的岸游。
        moves.shore = new BlockPos(9, 65, 0);
        ScriptedView view = new ScriptedView(calm().inLava().potion(), calm().inLava().potion(),
                calm().inLava().resistant(3600), calm().inLava().resistant(3600), calm().resistant(3600));
        TaskResult result = run(view, 8);
        assertEquals(List.of(BurningFakes.POTION), moves.drunk);
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED));
        assertTrue(events.unhandled.isEmpty(), "喝了药就伤不着，不用报");
        assertTrue(moves.steered.contains(moves.shore), "喝完药往远处的岸游");
    }

    @Test
    void withNoBankAndNoPotionItPlacesAFootholdAndClimbsOnIt() {
        // 背着圆石、许可让动方块：垫一块，垫好后那一格上面就是岸。
        BlockPos spot = new BlockPos(1, 64, 0);
        moves.foothold = new BurningTask.Foothold.Placing(new FinishesAfter(2), "minecraft:cobblestone", spot);
        ScriptedView view = new ScriptedView(calm().inLava(), calm().inLava(), calm().inLava(), calm().inLava(), calm());
        BurningTask task = new BurningTask(view, moves, events);
        task.start(context(0));
        task.tick(context(0));
        view.advance();
        task.tick(context(1));
        view.advance();
        // 垫好了：现在 3 格内有岸了。
        moves.exits = List.of(spot.above());
        task.tick(context(2));
        view.advance();
        task.tick(context(3));
        view.advance();
        TaskResult result = finished(task.tick(context(4)));
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_PLACED
                && change.what().equals("minecraft:cobblestone")));
        assertEquals(spot.above(), moves.steered.get(moves.steered.size() - 1));
        assertTrue(events.unhandled.isEmpty());
    }

    @Test
    void withNothingToDrinkOrPlaceItSwimsToTheFarShoreAndSaysWhy() {
        // change_blocks=none：不垫，朝最近的岸游，事件写明为什么没垫、往哪游。
        moves.foothold = new BurningTask.Foothold.Unavailable("主目标的许可不让动方块（change_blocks=none）");
        moves.shore = new BlockPos(9, 65, 0);
        BurningTask task = new BurningTask(new ScriptedView(calm().inLava()), moves, events);
        task.start(context(0));
        for (int tick = 0; tick < 3; tick++) task.tick(context(tick));
        assertEquals(1, events.unhandled.size(), "只说一次");
        String said = events.unhandled.get(0);
        assertTrue(said.contains("身上没有防火药水"), said);
        assertTrue(said.contains("change_blocks=none"), said);
        assertTrue(said.contains("正往东约 9 格外的岸游"), said);
        assertEquals(moves.shore, moves.steered.get(moves.steered.size() - 1));
    }

    @Test
    void aLethalBurnWithWaterTwoStepsAwayJumpsInAndKeepsThePotion() {
        TaskResult result = run(new ScriptedView(calm().burning(true).potion().water(2, 2), calm().potion()), 2);
        assertTrue(moves.drunk.isEmpty(), "2 格内有水先跳水，药留着");
        assertEquals(new BlockPos(2, 64, 0), moves.steered.get(0));
        assertTrue(result.summary().contains("走进水里灭了火"), result.summary());
    }

    @Test
    void aLethalBurnWithWaterFurtherAwayDrinksFirst() {
        run(new ScriptedView(calm().burning(true).potion().water(4, 4), calm().burning(true).potion().water(4, 4),
                calm().burning(true).resistant(3600)), 4);
        assertEquals(List.of(BurningFakes.POTION), moves.drunk, "水在 4 步外：先喝药");
    }

    @Test
    void aLethalBurnWithNoPotionAndNoWaterIsReportedAsUnhandled() {
        BurningTask task = new BurningTask(new ScriptedView(calm().burning(true)), moves, events);
        task.start(context(0));
        TaskResult result = finished(task.tick(context(0)));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(1, events.unhandled.size());
        assertTrue(events.unhandled.get(0).contains("够把角色烧死"), events.unhandled.get(0));
    }

    @Test
    void aSurvivableBurnInTheNetherJustBurnsOut() {
        TaskResult result = run(new ScriptedView(calm().burning(false).water(3, 3).nether()), 1);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(moves.steered.isEmpty(), "下界不往水里走");
        assertTrue(result.summary().contains("自己会灭"), result.summary());
    }

    @Test
    void stepsOutOfAFireOntoTheNeighbour() {
        BlockPos step = FEET.east();
        TaskResult result = run(new ScriptedView(calm().inFire(step), calm()), 2);
        assertEquals(step, moves.steered.get(0));
        assertFalse(moves.jumps.get(0), "同一层迈过去不用跳");
        assertTrue(result.summary().contains("迈出了火"), result.summary());
    }

    // 一刻推进一份处境，推进 ticks 刻，要求在这之内收场。
    private TaskResult run(ScriptedView view, int ticks) {
        BurningTask task = new BurningTask(view, moves, events);
        task.start(context(0));
        for (int tick = 0; tick < ticks; tick++) {
            TickResult result = task.tick(context(tick));
            if (result instanceof TickResult.Finished finished) return finished.result();
            view.advance();
        }
        throw new AssertionError("推进 " + ticks + " 刻还没收场：" + task.describe());
    }

    private static TaskResult finished(TickResult result) {
        assertTrue(result instanceof TickResult.Finished, "应当收场了");
        return ((TickResult.Finished) result).result();
    }

    private static TickContext context(long tick) {
        return TickContext.of(tick, null);
    }
}
