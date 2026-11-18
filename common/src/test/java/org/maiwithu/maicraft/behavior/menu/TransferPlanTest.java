// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Kind;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Step;
import org.maiwithu.maicraft.behavior.menu.TransferPlan.Where;

/** 搬运计划：整堆一笔快速移动；尾数拆成二分堆；只有整堆操作允许交换目标格。 */
class TransferPlanTest {

    private static List<Step> stepsOf(TransferPlan plan) {
        return plan.steps();
    }

    @Test
    void 整堆快速移动只有一笔() {
        TransferPlan plan = TransferPlan.quickMoveWholeStack();
        List<Step> steps = stepsOf(plan);
        assertEquals(1, steps.size());
        assertEquals(Kind.QUICK_MOVE, steps.getFirst().kind());
        assertEquals(Where.SOURCE, steps.getFirst().where());
        assertFalse(plan.maySwapTarget(), "快速移动不交换任何格子");
    }

    @Test
    void 整堆搬到指定格允许交换目标格() {
        TransferPlan plan = TransferPlan.swapWholeStack();
        assertEquals(2, plan.steps().size());
        assertEquals(Kind.TAKE_ALL, plan.steps().get(0).kind());
        assertEquals(Kind.GIVE_ALL, plan.steps().get(1).kind());
        assertTrue(plan.maySwapTarget(), "整堆操作是交换目标格的唯一许可");
    }

    @Test
    void 六十四取四十九放进目标格的是三十二十六一() {
        // 64 取 49：放进目标格的依次是 32、16、1，多拿的一个一个退回源格。
        TransferPlan plan = TransferPlan.exactAmount(64, 49, 64);
        assertFalse(plan.maySwapTarget(), "指定数量的搬运不许交换目标格");
        List<Integer> given = plan.steps().stream()
                .filter(step -> step.where() == Where.TARGET)
                .map(TransferPlan.Step::items)
                .toList();
        assertEquals(List.of(32, 16, 1), given, "尾数按二分堆放进去");
        // 步骤里包括退回：多拿的 8 个里放 1 个、退 7 个。
        long putBack = plan.steps().stream().filter(step -> step.kind() == Kind.PUT_BACK_ONE).count();
        assertEquals(7, putBack);
    }

    @Test
    void 计划执行完源格剩的正好是差额() {
        // 用计划在数字上走一遍：源格、光标、目标格的数量与预期一致，光标最后为空。
        int sourceCount = 64;
        int amount = 49;
        int source = sourceCount;
        int cursor = 0;
        int deposited = 0;
        for (Step step : TransferPlan.exactAmount(sourceCount, amount, 64).steps()) {
            switch (step.kind()) {
                case TAKE_HALF -> { cursor = (source + 1) / 2; source -= cursor; }
                case GIVE_ALL -> { deposited += cursor; cursor = 0; }
                case GIVE_ONE -> { cursor -= 1; deposited += 1; }
                case PUT_BACK_ONE -> { cursor -= 1; source += 1; }
                case QUICK_MOVE, TAKE_ALL -> throw new IllegalStateException("尾数计划里不该出现整堆步骤");
            }
        }
        assertEquals(sourceCount - amount, source);
        assertEquals(amount, deposited);
        assertEquals(0, cursor);
    }

    @Test
    void 拿一半刚好够用时不拆更多() {
        // 16 取 8：拿半堆正好是要的量，放完就结束。
        TransferPlan plan = TransferPlan.exactAmount(16, 8, 16);
        assertEquals(2, plan.steps().size());
        assertEquals(Kind.TAKE_HALF, plan.steps().get(0).kind());
        assertEquals(Kind.GIVE_ALL, plan.steps().get(1).kind());
    }

    @Test
    void 源格一件搬一件是合法的整格搬运() {
        // 附魔装料：身上唯一一把剑（源格 1 件）整格搬进附魔输入格——右键拿走这半堆（恰好拿空源格），
        // 整份放进目标格，不再被当成非法。
        TransferPlan plan = TransferPlan.exactAmount(1, 1, 1);
        assertEquals(2, plan.steps().size());
        assertEquals(Kind.TAKE_HALF, plan.steps().get(0).kind());
        assertEquals(Kind.GIVE_ALL, plan.steps().get(1).kind());
        assertFalse(plan.maySwapTarget(), "指定数量的搬运不许交换目标格");
    }

    @Test
    void 源格两件搬两件整格走尾数计划() {
        // 源格 2 件、要搬 2 件：拿半堆（1 件）放进目标格，再拿剩下那件放下，源格正好拿空。
        TransferPlan plan = TransferPlan.exactAmount(2, 2, 64);
        List<Integer> given = plan.steps().stream()
                .filter(step -> step.where() == Where.TARGET)
                .map(TransferPlan.Step::items)
                .toList();
        assertEquals(List.of(1, 1), given, "两件各走一次半堆放进目标格");
    }

    @Test
    void 超叠一百二十八圆石按光标上限六十四分轮搬运() {
        // 精妙背包单格叠着 128 个圆石（光标单次上限 64），从中搬 80 个：
        // 先按光标上限抓满 64 个放下，再从剩下的 64 个里拆出 16 个放下、退回多余件数，点完源格剩 48 个、光标为空。
        int sourceCount = 128;
        int amount = 80;
        int cursorMax = 64;
        TransferPlan plan = TransferPlan.exactAmount(sourceCount, amount, cursorMax);
        assertFalse(plan.maySwapTarget());
        int[] settled = simulate(plan, sourceCount, cursorMax);
        assertEquals(48, settled[0], "源格剩下未取走的 48 个圆石");
        assertEquals(80, settled[1], "目标收到足额 80 个圆石");
        assertEquals(0, settled[2], "搬完后鼠标光标为空");
    }

    @Test
    void 超叠一百二十八圆石取十六个_右键按截断后半堆三十二抓取并精确退回() {
        // 精妙背包单格叠着 128 个圆石（光标上限 64），只取 16 个垫脚：
        // 模组空手右键先截断到 64 再折半抓起 32 个，放进目标格 16 个后将剩余 16 个退回源格，不误发多余右键反抓悬空。
        int sourceCount = 128;
        int amount = 16;
        int cursorMax = 64;
        TransferPlan plan = TransferPlan.exactAmount(sourceCount, amount, cursorMax);
        assertFalse(plan.maySwapTarget());
        int[] settled = simulate(plan, sourceCount, cursorMax);
        assertEquals(112, settled[0], "源格退回多余件数后剩 112 个圆石");
        assertEquals(16, settled[1], "目标精确收到 16 个圆石");
        assertEquals(0, settled[2], "搬完后鼠标光标为空，无反抓残留");
    }

    @Test
    void 叠放多把石镐按光标上限一逐轮抓取() {
        // 精妙背包单格叠放 3 把石镐（工具自身最大堆叠为 1，光标一次只能抓 1 把），从中取 2 把：
        // 分两轮每次抓 1 把放进目标侧，不因源格数量大于 1 抛异常，最后源格剩 1 把、光标为空。
        int sourceCount = 3;
        int amount = 2;
        int cursorMax = 1;
        TransferPlan plan = TransferPlan.exactAmount(sourceCount, amount, cursorMax);
        assertFalse(plan.maySwapTarget());
        int[] settled = simulate(plan, sourceCount, cursorMax);
        assertEquals(1, settled[0], "源格还剩 1 把石镐");
        assertEquals(2, settled[1], "分两轮共搬出 2 把石镐");
        assertEquals(0, settled[2], "搬完后鼠标光标为空");
    }

    @Test
    void 数量超过源格件数或光标上限非法时仍被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(0, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(64, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(8, 9, 8));
        assertThrows(IllegalArgumentException.class, () -> TransferPlan.exactAmount(64, 10, 0),
                "光标单次上限小于 1 时拒绝生成计划");
    }

    // 按超叠槽位与原版光标上限规则走完计划，核对每一步光标都不超过 cursorMax，返回 {源格剩余, 目标收到, 光标剩余}。
    private static int[] simulate(TransferPlan plan, int sourceCount, int cursorMax) {
        int source = sourceCount;
        int deposited = 0;
        int cursor = 0;
        for (Step step : plan.steps()) {
            switch (step.kind()) {
                case TAKE_ALL -> {
                    cursor = Math.min(cursorMax, source);
                    source -= cursor;
                }
                case TAKE_HALF -> {
                    cursor = (Math.min(cursorMax, source) + 1) / 2;
                    source -= cursor;
                }
                case GIVE_ALL -> {
                    deposited += cursor;
                    cursor = 0;
                }
                case GIVE_ONE -> {
                    cursor -= 1;
                    deposited += 1;
                }
                case PUT_BACK_ONE -> {
                    cursor -= 1;
                    source += 1;
                }
                case QUICK_MOVE -> throw new IllegalStateException("尾数计划里不该出现快速移动");
            }
            assertTrue(cursor >= 0 && cursor <= cursorMax, "光标件数越界：" + cursor);
        }
        return new int[] {source, deposited, cursor};
    }
}
