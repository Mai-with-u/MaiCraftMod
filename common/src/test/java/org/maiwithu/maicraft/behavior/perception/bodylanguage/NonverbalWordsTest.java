// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void floodSummaryGivesTotalsByKind() {
        assertEquals("玩家 Alex这一分钟又蹲起 40 下、挥手 12 下", NonverbalWords.overflow("玩家 Alex",
                Map.of(NonverbalAct.Kind.SWING, 12, NonverbalAct.Kind.CROUCH, 40)));
    }
}
