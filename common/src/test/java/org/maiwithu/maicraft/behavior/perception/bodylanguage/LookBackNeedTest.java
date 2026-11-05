// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 被拍了回头看：新鲜的一下才回头、腾不出身子这一下就算了、按人按时间限频、没人回他就先不理他。 */
class LookBackNeedTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID STEVE = UUID.randomUUID();

    /** 被拍的替身：测试直接写最近一下和他最近冲角色做动作的刻。 */
    private static final class FakePokes implements PokesFelt {
        Poke latest;
        final Map<UUID, Long> lastAct = new HashMap<>();

        void poke(UUID who, long tick) {
            latest = new Poke(who, who == ALEX ? 1 : 2, who == ALEX ? "Alex" : "Steve", tick);
            lastAct.put(who, tick);
        }

        @Override public Optional<Poke> latestPoke() {
            return Optional.ofNullable(latest);
        }

        @Override public long lastActTowardMe(UUID who) {
            return lastAct.getOrDefault(who, Long.MIN_VALUE);
        }
    }

    private static TickContext at(long tick) {
        return new TickContext() {
            @Override public long gameTick() {
                return tick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    // 刚被拍、手上的事能停：尽快插进来，建出回头看的任务；同一下不再回第二次。
    @Test
    void freshPokeTurnsAroundOnce() {
        FakePokes pokes = new FakePokes();
        LookBackNeed need = new LookBackNeed(pokes);
        assertNull(need.decide(100, LookBackNeed.Busy.FREE));
        pokes.poke(ALEX, 100);
        assertEquals(Urgency.SOON, need.decide(102, LookBackNeed.Busy.FREE));
        Task task = need.createTask(at(102));
        assertInstanceOf(LookBackTask.class, task);
        assertNull(need.decide(103, LookBackNeed.Busy.FREE));
    }

    // 开着界面、按着使用键、在打仗时被拍：这一下就算了，不排队；界面关了也不补回头。
    @Test
    void busyMomentDropsThePoke() {
        FakePokes pokes = new FakePokes();
        LookBackNeed need = new LookBackNeed(pokes);
        pokes.poke(ALEX, 100);
        assertNull(need.decide(101, new LookBackNeed.Busy(false, false, true, false, false)));
        assertNull(need.decide(102, LookBackNeed.Busy.FREE));
        pokes.poke(ALEX, 400);
        assertNull(need.decide(401, new LookBackNeed.Busy(false, false, false, false, true)));
        pokes.poke(ALEX, 700);
        assertNull(need.decide(701, new LookBackNeed.Busy(true, false, false, false, false)));
    }

    // 被拍半秒多还插不进来：不再回头。
    @Test
    void stalePokeIsDropped() {
        FakePokes pokes = new FakePokes();
        LookBackNeed need = new LookBackNeed(pokes);
        pokes.poke(ALEX, 100);
        assertNull(need.decide(100 + LookBackNeed.FRESH_TICKS + 1, LookBackNeed.Busy.FREE));
    }

    // 同一个人 10 秒内只回头一次；不管是谁 5 秒内只回头一次。
    @Test
    void ratesLimitPerPlayerAndOverall() {
        FakePokes pokes = new FakePokes();
        LookBackNeed need = new LookBackNeed(pokes);
        pokes.poke(ALEX, 100);
        assertEquals(Urgency.SOON, need.decide(100, LookBackNeed.Busy.FREE));
        need.createTask(at(100));
        pokes.poke(STEVE, 150);
        assertNull(need.decide(150, LookBackNeed.Busy.FREE));
        pokes.poke(STEVE, 200);
        assertEquals(Urgency.SOON, need.decide(200, LookBackNeed.Busy.FREE));
        need.createTask(at(200));
        pokes.poke(ALEX, 290);
        assertNull(need.decide(290, LookBackNeed.Busy.FREE));
        pokes.poke(ALEX, 300);
        assertEquals(Urgency.SOON, need.decide(300, LookBackNeed.Busy.FREE));
    }

    // 两分钟内为同一个人回头满三次、LLM 一次都没回他：之后两分钟不再为他回头；回过他就照常。
    @Test
    void ignoredThreeTimesCoolsOffUntilAnswered() {
        FakePokes pokes = new FakePokes();
        LookBackNeed need = new LookBackNeed(pokes);
        for (long tick = 0; tick < 3 * LookBackNeed.SAME_PLAYER_TICKS; tick += LookBackNeed.SAME_PLAYER_TICKS) {
            pokes.poke(ALEX, tick);
            assertEquals(Urgency.SOON, need.decide(tick, LookBackNeed.Busy.FREE));
            need.createTask(at(tick));
        }
        long later = 3 * LookBackNeed.SAME_PLAYER_TICKS;
        pokes.poke(ALEX, later);
        assertNull(need.decide(later, LookBackNeed.Busy.FREE));
        need.answered(ALEX);
        pokes.poke(ALEX, later + 1);
        assertEquals(Urgency.SOON, need.decide(later + 1, LookBackNeed.Busy.FREE));
    }

    // 回头看 2.5 秒；他接着冲角色做动作就从那一下起再看 2.5 秒，最长 6 秒。
    @Test
    void looksLongerWhileHeKeepsGoing() {
        assertEquals(150, LookBackTask.lookUntil(100, 100));
        assertEquals(190, LookBackTask.lookUntil(100, 140));
        assertEquals(220, LookBackTask.lookUntil(100, 200));
        assertEquals(150, LookBackTask.lookUntil(100, Long.MIN_VALUE));
    }

    // 不是起居需求：主目标关掉起居打断也照样回头；不另发临时任务事件。
    @Test
    void notRoutineAndSilent() {
        LookBackNeed need = new LookBackNeed(new FakePokes());
        assertFalse(need.routine());
        assertFalse(need.announcesTempTask());
        assertFalse(need.waitsAfterFailure());
    }
}
