// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 一段肢体动作说成一句话：连着的同一种合成几下，按先后，开头看着角色说一次，最后说方位。 */
class NonverbalWordsTest {

    private static NonverbalAct act(NonverbalAct.Kind kind, long tick, boolean toward, String detail) {
        return new NonverbalAct(kind, tick, 1, toward, detail);
    }

    @Test
    void crouchesThenDrawsASwordThenSwingsAtMe() {
        List<NonverbalAct> acts = List.of(
                act(NonverbalAct.Kind.CROUCH, 1, true, ""), act(NonverbalAct.Kind.CROUCH, 5, true, ""),
                act(NonverbalAct.Kind.CROUCH, 9, true, ""), act(NonverbalAct.Kind.CROUCH, 13, true, ""),
                act(NonverbalAct.Kind.HOLD, 20, true, "铁剑"),
                act(NonverbalAct.Kind.SWING, 25, true, "对着你"), act(NonverbalAct.Kind.SWING, 35, true, "对着你"));
        assertEquals("玩家 Alex（e4）看着你蹲起 4 下，拿出铁剑，对着你挥了 2 下；他在你前方 3 格",
                NonverbalWords.sentence("玩家 Alex（e4）", acts, "他在你前方 3 格", true));
    }

    @Test
    void swingingAtAChestSaysWhereItIsAndWhetherHeLooksAtMeNow() {
        List<NonverbalAct> acts = List.of(
                act(NonverbalAct.Kind.SWING, 1, false, "对着箱子（你右侧 3 格）"),
                act(NonverbalAct.Kind.SWING, 9, false, "对着箱子（你右侧 3 格）"),
                act(NonverbalAct.Kind.NOD, 20, true, ""));
        assertEquals("玩家 Alex（e4）对着箱子（你右侧 3 格）挥了 2 下，点了 1 下头；他在你左前方 2 格，看着你",
                NonverbalWords.sentence("玩家 Alex（e4）", acts, "他在你左前方 2 格", true));
    }

    /** 说成一句话，最多等 2 秒：扔东西、走开又回头不合并，曾经让下标不前进、死循环到内存溢出。 */
    private static String say(List<NonverbalAct> acts, boolean lookingNow) {
        return assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> NonverbalWords.sentence("玩家 Alex（e4）", acts, "他在你前方 3 格", lookingNow));
    }

    @Test
    void aLoneTossIsSaidOnce() {
        // 一段里只有扔东西这一下：照细节说一次，不卡住。
        assertEquals("玩家 Alex（e4）朝你扔了 3 个面包，落在你脚边；他在你前方 3 格",
                say(List.of(act(NonverbalAct.Kind.TOSS, 1, true, "朝你扔了 3 个面包，落在你脚边")), false));
    }

    @Test
    void aLoneWalkBackIsSaidOnce() {
        assertEquals("玩家 Alex（e4）往东走了 4 格又回头看你；他在你前方 3 格，看着你",
                say(List.of(act(NonverbalAct.Kind.WALK, 1, true, "往东走了 4 格又回头看你")), true));
    }

    @Test
    void tossesInARowAreSaidOneByOne() {
        // 连着扔两次也各说各的：扔了什么、落在哪不一样，不合成"扔了 2 下"。
        List<NonverbalAct> acts = List.of(
                act(NonverbalAct.Kind.TOSS, 1, true, "朝你扔了 1 个面包，落在你脚边"),
                act(NonverbalAct.Kind.TOSS, 9, true, "朝你扔了 1 个面包，落在你脚边"));
        assertEquals("玩家 Alex（e4）朝你扔了 1 个面包，落在你脚边，朝你扔了 1 个面包，落在你脚边；他在你前方 3 格",
                say(acts, false));
    }

    @Test
    void aWalkBetweenCrouchesKeepsTheOrder() {
        // 蹲起、走开又回头、再蹲起：按先后说，走开前后的蹲起各自合并。
        List<NonverbalAct> acts = List.of(
                act(NonverbalAct.Kind.CROUCH, 1, true, ""), act(NonverbalAct.Kind.CROUCH, 5, true, ""),
                act(NonverbalAct.Kind.WALK, 40, true, "往东走了 4 格又回头看你"),
                act(NonverbalAct.Kind.CROUCH, 50, true, ""), act(NonverbalAct.Kind.CROUCH, 54, true, ""),
                act(NonverbalAct.Kind.CROUCH, 58, true, ""));
        assertEquals("玩家 Alex（e4）看着你蹲起 2 下，往东走了 4 格又回头看你，蹲起 3 下；他在你前方 3 格",
                say(acts, true));
    }

    @Test
    void aNodSeriesCountsItsOwnSwings() {
        // 点头一串认一次、自带几下：第一串也要按它自己的下数算，不从 0 起。
        assertEquals("玩家 Alex（e4）看着你点了 3 下头；他在你前方 3 格",
                say(List.of(new NonverbalAct(NonverbalAct.Kind.NOD, 1, 3, true, "")), false));
    }

    @Test
    void floodSummaryGivesTotalsByKind() {
        assertEquals("玩家 Alex这一分钟又蹲起 40 下、挥手 12 下", NonverbalWords.overflow("玩家 Alex",
                Map.of(NonverbalAct.Kind.SWING, 12, NonverbalAct.Kind.CROUCH, 40)));
    }
}
