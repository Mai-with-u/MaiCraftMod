// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.SurvivalFakes;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 饥饿需求弄不到吃的之后怎么办：问遍各处都没有就歇一分钟不插，手上的活照常干；
 * 身上有了吃的、处境更急了马上照插；弄到一半超了预算不歇，照控制循环的老规矩缓几秒再试。
 */
class HungerNeedTest {

    private final SurvivalFakes.TestPlayer player = new SurvivalFakes.TestPlayer();
    private final TickContext tick = new SurvivalFakes.TestTick(player);
    private final List<String> events = new ArrayList<>();
    /** 此刻的饥饿处境：饱食度 5（不能疾跑），身上没吃的。 */
    private final AtomicReference<HungerNeed.Facts> now = new AtomicReference<>(new HungerNeed.Facts(5, false, 0));

    @Test
    void 问遍各处都弄不到吃的_歇一分钟不插() {
        // 10-15 直播：哪儿都弄不到吃的，五秒、十秒后又插进来再问一遍，把手上打猎的活停停走走。
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));

        assertEquals(Urgency.SOON, need.urgency(tick));
        runToEnd(need.createTask(tick));

        assertNull(need.urgency(tick), "刚问遍各处都没有，先不插");
        player.tick += HungerBout.REST_AFTER_NOTHING_FOUND_TICKS - 1;
        assertNull(need.urgency(tick), "一分钟没到还歇着");
        player.nextTick();
        assertEquals(Urgency.SOON, need.urgency(tick), "歇够了再找");
        assertEquals(1, events.size());
    }

    @Test
    void 歇着时饿到掉血或身上有了吃的_马上照插() {
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));
        assertNull(need.urgency(tick));

        // 有人扔了块面包过来：身上有了这会儿能吃的，照常插进来吃。
        moves.carrying = true;
        assertEquals(Urgency.SOON, need.urgency(tick));
        moves.carrying = false;

        // 饿到掉血：比歇下时更急，马上再试。
        now.set(new HungerNeed.Facts(0, true, 0));
        assertEquals(Urgency.NOW, need.urgency(tick));
    }

    @Test
    void 弄到一半超了预算不歇() {
        // 找到了来源、只是一分钟没弄到（追鸡追丢了）：不算哪儿都没有，照控制循环的老规矩缓几秒再试。
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.STUCK, "弄吃的过了 60 秒还没弄到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));

        assertEquals(Urgency.SOON, need.urgency(tick));
    }

    @Test
    void 不饿了这一回就过去_下回饿了照常找_同样的失败十分钟内不重说() {
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));
        assertEquals(1, events.size());

        // 别人喂饱了：这一回过去，歇着也一并作废。
        now.set(new HungerNeed.Facts(20, false, 0));
        assertNull(need.urgency(tick));
        now.set(new HungerNeed.Facts(5, false, 0));
        assertEquals(Urgency.SOON, need.urgency(tick), "新的一回饿不再歇着");
        runToEnd(need.createTask(tick));
        assertEquals(1, events.size(), "新的一回饿没吃上，但同样的失败刚说过，不重说");

        // 过了窗口：旧的那份多半已被整理掉，新的一回饿没吃上再完整说一次。
        now.set(new HungerNeed.Facts(20, false, 0));
        assertNull(need.urgency(tick));
        player.tick += HungerBout.SAME_FAILURE_WINDOW_TICKS;
        now.set(new HungerNeed.Facts(5, false, 0));
        assertEquals(Urgency.SOON, need.urgency(tick));
        runToEnd(need.createTask(tick));
        assertEquals(2, events.size(), "窗口过了再完整说一次");
    }

    @Test
    void 伤得重或饿到不能疾跑时_正要用的吃的也照吃() {
        // 只有趁空当垫一口（LATER）时才绕开目标要交、要做菜的那几件；血不到一半、饿到 SOON / NOW 先顾身体。
        assertFalse(HungerNeed.eatsKeptFood(14, false), "趁空当垫一口：绕开正要用的");
        assertTrue(HungerNeed.eatsKeptFood(14, true), "血不到一半");
        assertTrue(HungerNeed.eatsKeptFood(HungerNeed.SPRINT_FLOOR, false), "饿到不能疾跑");
        assertTrue(HungerNeed.eatsKeptFood(0, false), "饿到掉血");
        assertTrue(new HungerNeed.Facts(11, false, 0, true, true, 0).eatsKeptFood());
    }

    @Test
    void 饱食18拿到生鱼不计入普通食物阈值_不触发趁空当吃饭() {
        // 生鱼不计入 smallestPlainNutrition（为 0）：饱食 18 拿到营养 2 的生鱼不会凑成 18+2=20 引爆吃饭。
        assertNull(HungerNeed.assess(new HungerNeed.Facts(18, false, 0)), "身上只有生鱼时不触发 LATER");
        // 身上既有面包（营养 5）又有生鱼（营养 2）时，只按面包算 smallest=5，18+5>20 同样不触发。
        assertNull(HungerNeed.assess(new HungerNeed.Facts(18, false, 5)), "只按熟食营养算阈值，不触发 LATER");
        // 真正挨饿时即使没有现成熟食仍正常触发 SOON / NOW，由选食环节决定是否应急生吃。
        assertEquals(Urgency.SOON, HungerNeed.assess(new HungerNeed.Facts(HungerNeed.SPRINT_FLOOR, false, 0)));
        assertEquals(Urgency.NOW, HungerNeed.assess(new HungerNeed.Facts(0, true, 0)));
    }

    // 把吃饭任务推进到结束，最多一百刻。
    private void runToEnd(Task task) {
        task.start(tick);
        for (int i = 0; i < 100; i++) {
            if (task.tick(tick) instanceof TickResult.Finished) return;
            player.nextTick();
        }
        throw new AssertionError("一百刻内没有结束");
    }
}
