// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 右键生物的确认：出手前后的原版实体事实每一条线索各变一次都算生效，背包东西变少不算，什么都没变不算。
 * 主手变了、开了界面、目标当场不在了这三条由组合时的另外两份条件与"按编号找回不到"兜住，这里只测冻结的现场。
 */
class EntityUseConfirmationTest {

    private static final UUID HORSE = UUID.randomUUID();
    private static final UUID MAID = UUID.randomUUID();
    private static final UUID SELF = UUID.randomUUID();

    private static final EntityUseConfirmation.Snapshot BEFORE =
            new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, false, null, 10);

    @Test
    void 什么都没变_不算生效() {
        assertFalse(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, false,
                null, 10)));
    }

    @Test
    void 角色骑上了坐骑() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(HORSE, List.of(), List.of(), null, false,
                null, 10)));
    }

    @Test
    void 拿鞍抱起女仆_角色身上多了乘客() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(MAID), List.of(), null, false,
                null, 10)));
    }

    @Test
    void 把动物塞进船_目标的乘客变了() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(MAID), null, false,
                null, 10)));
    }

    @Test
    void 让船里的动物下来_目标的坐骑变了() {
        var inBoat = new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), HORSE, false, null, 10);
        assertTrue(inBoat.differsFrom(BEFORE));
    }

    @Test
    void 潜行右键让狼坐下_坐姿变了() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, true,
                null, 10)));
    }

    @Test
    void 蛋糕驯服女仆_驯服者变了() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, false,
                SELF, 10)));
    }

    @Test
    void 取回墓碑_背包多了东西算_少了不算() {
        assertTrue(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, false,
                null, 15)));
        // 喂掉一个苹果背包少了一件：由主手变了那一条认，这里不当线索。
        assertFalse(BEFORE.differsFrom(new EntityUseConfirmation.Snapshot(null, List.of(), List.of(), null, false,
                null, 9)));
    }
}
