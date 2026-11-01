// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/** 这一仗箭少了几支：按背包里箭的只数开打与最近一刻的差算，捡回的抵掉；读不到背包的刻不记、一次没读到就不给数。 */
class ArrowsSpentTest {

    @Test
    void spentIsTheDropFromTheFirstReadingToTheLatest() {
        // 开打时 27 支，打着打着剩 5 支：少了 22 支，和射了几箭无关。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(27));
        arrows.note(OptionalInt.of(20));
        arrows.note(OptionalInt.of(5));

        assertEquals(OptionalInt.of(22), arrows.spent());
        assertEquals(22, arrows.spentOrNull());
    }

    @Test
    void arrowsPickedBackUpOffsetTheOnesShot() {
        // 射掉 3 支又从地上捡回 2 支：少的是 1 支，捡得比射得多就是负数。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(10));
        arrows.note(OptionalInt.of(7));
        arrows.note(OptionalInt.of(9));
        assertEquals(OptionalInt.of(1), arrows.spent());

        arrows.note(OptionalInt.of(12));
        assertEquals(OptionalInt.of(-2), arrows.spent());
    }

    @Test
    void shotAndPickedUpAreCountedSeparately() {
        // 射出去 5 支、捡回 2 支：净少 3，离开背包的 5、捡回的 2 各自有数。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(10));
        arrows.note(OptionalInt.of(5));
        arrows.note(OptionalInt.of(7));
        assertEquals(5, arrows.taken());
        assertEquals(2, arrows.pickedUp());
        assertEquals(OptionalInt.of(3), arrows.spent());
    }

    @Test
    void changesWhileSomethingElseHadTheBodyAreLeftToThatThing() {
        // 实机：fight 跑到一半自卫插进来射了 7 支；回来接着打时起点挪到当时的只数，那 7 支由自卫自己交代。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(50));
        arrows.note(OptionalInt.of(49));
        arrows.resumeAt(OptionalInt.of(42));
        arrows.note(OptionalInt.of(40));
        assertEquals(OptionalInt.of(3), arrows.spent(), "自己射的 1 + 2 支");
        assertEquals(3, arrows.taken());
        // 没读过第一笔就回来：什么都不做。
        ArrowsSpent fresh = new ArrowsSpent();
        fresh.resumeAt(OptionalInt.of(9));
        assertTrue(fresh.spent().isEmpty());
    }

    @Test
    void unreadableTicksAreSkippedAndNoReadingMeansNoNumber() {
        // 没接上角色的刻不记；一次都没读到背包就不给数，结果里不写。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.empty());
        assertTrue(arrows.spent().isEmpty());
        assertNull(arrows.spentOrNull());

        arrows.note(OptionalInt.of(8));
        arrows.note(OptionalInt.empty());
        arrows.note(OptionalInt.of(6));
        assertEquals(OptionalInt.of(2), arrows.spent());
    }
}
