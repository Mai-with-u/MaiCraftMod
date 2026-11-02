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
    void changesWhileSomethingElseHadTheBodyGoToWhoeverClaimedThem() {
        // 实机：fight 跑到一半自卫插进来射了 7 支，自卫自己认下 7 支消耗；回来接着打时那 7 支归自卫，这一仗不记。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(50));
        arrows.note(OptionalInt.of(49));
        arrows.resumeAt(OptionalInt.of(42), 7, 0);
        arrows.note(OptionalInt.of(40));
        assertEquals(OptionalInt.of(3), arrows.spent(), "自己射的 1 + 2 支");
        assertEquals(3, arrows.taken());
        // 没读过第一笔就回来：什么都不做。
        ArrowsSpent fresh = new ArrowsSpent();
        fresh.resumeAt(OptionalInt.of(9), 0, 0);
        assertTrue(fresh.spent().isEmpty());
    }

    @Test
    void arrowSubtractedDuringAnInterludeNobodyClaimsStaysWithThisFight() {
        // 实机：fight 一共少了 78 支却只记到 76——走开前最后一箭的扣减落在吃饭、换气插进来的那段，
        // 吃饭、换气不射箭也不认箭，原先回来时把那段整个写掉。没人认下的就是这一仗的。
        ArrowsSpent arrows = new ArrowsSpent();
        arrows.note(OptionalInt.of(10));
        arrows.note(OptionalInt.of(9));
        arrows.resumeAt(OptionalInt.of(8), 0, 0);
        assertEquals(OptionalInt.of(2), arrows.spent());
        assertEquals(2, arrows.taken());
        // 各归各的：自卫捡回 2 又射掉 2，这一仗在飞的 1 支同时扣了，这一仗记自己那 1 支。
        ArrowsSpent mixed = new ArrowsSpent();
        mixed.note(OptionalInt.of(10));
        mixed.resumeAt(OptionalInt.of(9), 2, 2);
        assertEquals(OptionalInt.of(1), mixed.spent());
        // 插进来的捡回比它射的多、这一仗走开前没有在飞的箭：那段净多出来的归插进来的，这一仗不变。
        ArrowsSpent gained = new ArrowsSpent();
        gained.note(OptionalInt.of(10));
        gained.resumeAt(OptionalInt.of(11), 1, 2);
        assertEquals(OptionalInt.of(0), gained.spent());
        assertEquals(0, gained.pickedUp());
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
