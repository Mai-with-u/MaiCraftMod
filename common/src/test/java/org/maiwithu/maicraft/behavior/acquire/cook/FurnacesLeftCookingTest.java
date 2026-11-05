// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 装炉就走：这一炉还要烧多久、值不值得走，留着烧的那几台怎么记、换、划掉，计时怎么挂、怎么摘。 */
class FurnacesLeftCookingTest {

    private static final WorldPosition FURNACE = new WorldPosition(12, 64, -30, "minecraft:overworld");
    private static final WorldPosition OTHER = new WorldPosition(20, 64, -30, "minecraft:overworld");

    /** 记下挂了哪些计时、摘了哪些的替身：编号从 1 起，挂满了按 full 为空。 */
    private static final class RecordedTimers implements FurnacesLeftCooking.SetsTimers {
        final List<String> notes = new ArrayList<>();
        final List<Integer> afterSeconds = new ArrayList<>();
        final List<Integer> cancelled = new ArrayList<>();
        boolean full;

        @Override public Optional<Integer> set(int seconds, String note, long gameTick) {
            if (full) return Optional.empty();
            notes.add(note);
            afterSeconds.add(seconds);
            return Optional.of(notes.size());
        }

        @Override public void cancel(int timerId) {
            cancelled.add(timerId);
        }
    }

    // 64 个海带在熔炉里烧，走的时候背包里还有 167 个；按原版熔炉一件 200 刻估。
    private static LeftCooking kelp(WorldPosition at, int loaded, long now) {
        return new LeftCooking(at, WorkstationRecipe.Kind.SMELTING, "minecraft:dried_kelp_from_smelting",
                "minecraft:dried_kelp", "干海带", "minecraft:kelp", "海带", "minecraft:coal", loaded, loaded, 167,
                now, now + 200L * loaded, null);
    }

    @Test
    void longBatchLeavesShortBatchWaits() {
        // 一炉 64 个海带要烧 12800 刻（640 秒），装好就走；五件铁矿 1000 刻（50 秒），站着等完。
        assertTrue(FurnaceLeaving.leavesNow(FurnaceLeaving.cookTicksLeft(64, 0f, 200)));
        assertFalse(FurnaceLeaving.leavesNow(FurnaceLeaving.cookTicksLeft(5, 0f, 200)));
        // 正好六件、手上这件烧了一半：还要 1100 刻，不到一分钟，站着等。
        assertEquals(1100, FurnaceLeaving.cookTicksLeft(6, 0.5f, 200));
        assertFalse(FurnaceLeaving.leavesNow(1100));
        // 高炉烧一件 100 刻：十三件 1300 刻才过一分钟。
        assertFalse(FurnaceLeaving.leavesNow(FurnaceLeaving.cookTicksLeft(12, 0f, 100)));
        assertTrue(FurnaceLeaving.leavesNow(FurnaceLeaving.cookTicksLeft(13, 0f, 100)));
    }

    @Test
    void leavingSetsATimerAndCountsTheLeave() {
        RecordedTimers timers = new RecordedTimers();
        FurnacesLeftCooking left = new FurnacesLeftCooking(timers);
        LeftCooking stored = left.leave(kelp(FURNACE, 64, 1000));
        // 计时比估的烧完时刻晚一秒叫，备注说清回哪台取什么。
        assertEquals(List.of(641), timers.afterSeconds);
        assertEquals("回 (12, 64, -30) 的熔炉取干海带 ×64", timers.notes.getFirst());
        assertEquals(1, stored.timerId());
        assertEquals(1, left.timesLeft());
        assertEquals(640, stored.readyInSeconds());
        assertEquals(11, stored.readyInMinutes());
        Problem problem = left.lastLeft();
        assertEquals(Problem.Kind.WRONG_TIME, problem.kind());
        assertEquals("装好炉先走了：(12, 64, -30) 的熔炉里在烧 64 个海带，约 11 分钟烧好；背包里还有 167 个海带没装",
                problem.message());
        assertTrue(problem.suggestion().contains("计时 1 到点"));
        assertTrue(problem.suggestion().contains("obtain minecraft:dried_kelp"));
    }

    @Test
    void sameFurnaceReplacesTheOldOneAndDropsItsTimer() {
        RecordedTimers timers = new RecordedTimers();
        FurnacesLeftCooking left = new FurnacesLeftCooking(timers);
        left.leave(kelp(FURNACE, 64, 0));
        // 回来早了，取走烧好的一些，剩下的再装炉就走：旧计时摘掉，只记新的那一炉。
        left.leave(kelp(FURNACE, 30, 5000));
        assertEquals(List.of(1), timers.cancelled);
        assertEquals(30, left.at(FURNACE).orElseThrow().loaded());
        assertEquals(2, left.at(FURNACE).orElseThrow().timerId());
        assertEquals(1, left.cooking("minecraft:dried_kelp", "minecraft:overworld").size());
        assertEquals(2, left.timesLeft());
    }

    @Test
    void collectedFurnaceIsCrossedOutAndItsTimerDropped() {
        RecordedTimers timers = new RecordedTimers();
        FurnacesLeftCooking left = new FurnacesLeftCooking(timers);
        left.leave(kelp(FURNACE, 64, 0));
        left.leave(kelp(OTHER, 64, 0));
        left.collected(new WorldPosition(12, 64, -30, null));
        assertTrue(left.at(FURNACE).isEmpty());
        assertEquals(List.of(1), timers.cancelled);
        assertEquals(1, left.cooking("minecraft:dried_kelp", null).size());
    }

    @Test
    void cookingIsFoundByItemAndDimension() {
        FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
        left.leave(kelp(FURNACE, 64, 0));
        assertEquals(1, left.cooking("minecraft:dried_kelp", "minecraft:overworld").size());
        assertTrue(left.cooking("minecraft:dried_kelp", "minecraft:the_nether").isEmpty());
        assertTrue(left.cooking("minecraft:iron_ingot", "minecraft:overworld").isEmpty());
    }

    @Test
    void sinceMarkPicksWhatThisRunLeft() {
        FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
        left.leave(kelp(FURNACE, 64, 0));
        int mark = left.timesLeft();
        assertTrue(left.since(mark).isEmpty());
        left.leave(kelp(OTHER, 20, 100));
        assertEquals(List.of(OTHER), left.since(mark).stream().map(LeftCooking::furnace).toList());
        assertEquals(2, left.since(0).size());
    }

    @Test
    void fullTimersStillRecordTheBatchWithoutAnId() {
        RecordedTimers timers = new RecordedTimers();
        timers.full = true;
        FurnacesLeftCooking left = new FurnacesLeftCooking(timers);
        LeftCooking stored = left.leave(kelp(FURNACE, 64, 0));
        assertNull(stored.timerId());
        assertTrue(left.lastLeft().suggestion().startsWith("计时挂满了没挂上，约 11 分钟后再 obtain"));
    }

    @Test
    void returningTellsWhetherTheBatchIsStillThere() {
        LeftCooking cooking = kelp(FURNACE, 64, 0);
        // 到点前回来，里面还是那一炉：算自己的，什么都不用另说。
        FurnaceLeaving.Return early = FurnaceLeaving.onReturn(cooking, 30, 34, "", 6000);
        assertTrue(early.ours());
        assertNull(early.finding());
        // 过了估的烧完时刻投入口里还有：炉子停过，照实说烧好几件、还有几件。
        FurnaceLeaving.Return stalled = FurnaceLeaving.onReturn(cooking, 40, 24, "", 20000);
        assertTrue(stalled.ours());
        assertTrue(stalled.finding().startsWith("到点了还没烧完：烧好 24 个，还有 40 个没烧"));
        // 烧完了，只剩产出格里的成品：照常是自己的。
        assertNull(FurnaceLeaving.onReturn(cooking, 0, 64, "", 20000).finding());
        // 里面一件这次的都没有：被人动过，不当成烧好了。
        FurnaceLeaving.Return moved = FurnaceLeaving.onReturn(cooking, 0, 0, "投入口放着 3 个生牛肉，产出格空着", 20000);
        assertFalse(moved.ours());
        assertTrue(moved.finding().contains("投入口放着 3 个生牛肉"));
        assertEquals("回 (12, 64, -30) 的熔炉取干海带", FurnaceLeaving.returnTried(cooking));
    }
}
