// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 右键实体提交现场的拒收条件说明：原版当场拒了不说原因，把命中的条件写出来，实机下一轮直接读出是哪一条。 */
class EntityUseSceneTest {

    @Test
    void heldSneakIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(true, 1.5, 3.0, false).explain();
        assertTrue(clause.contains("sneak key was still held"));
        assertFalse(clause.contains("beyond the entity interaction range"));
        assertFalse(clause.contains("passenger"));
    }

    @Test
    void outOfRangeClickReportsTheDistanceAgainstTheRange() {
        String clause = new EntityUseScene(false, 4.2, 3.0, false).explain();
        assertTrue(clause.contains("4.2 blocks from the eyes"));
        assertTrue(clause.contains("entity interaction range of 3.0"));
        assertFalse(clause.contains("sneak key"));
    }

    @Test
    void anOccupiedTargetIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(false, 1.0, 3.0, true).explain();
        assertTrue(clause.contains("already carries a passenger"));
    }

    @Test
    void severalRulesMatchedAreListedTogether() {
        String clause = new EntityUseScene(true, 5.0, 3.0, true).explain();
        assertTrue(clause.contains("sneak key was still held"));
        assertTrue(clause.contains("5.0 blocks from the eyes"));
        assertTrue(clause.contains("already carries a passenger"));
    }

    /** 客户端看得见的拒收条件一条都没命中：如实说拒收发生在服务端一侧，不冒充知道原因。 */
    @Test
    void noVisibleRuleMatchedSaysTheRefusalHappenedServerSide() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false).explain();
        assertTrue(clause.contains("none of the client-visible refusal rules matched"));
        assertTrue(clause.contains("server's side"));
    }
}
