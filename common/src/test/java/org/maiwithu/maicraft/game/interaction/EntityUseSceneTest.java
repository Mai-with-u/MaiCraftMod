// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 右键实体提交现场的拒收条件说明：原版当场拒了不说原因，把命中的条件写出来，实机下一轮直接读出是哪一条。 */
class EntityUseSceneTest {

    @Test
    void heldSneakIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(true, 1.5, 3.0, false, null).explain();
        assertTrue(clause.contains("sneak key was still held"));
        assertFalse(clause.contains("beyond the entity interaction range"));
        assertFalse(clause.contains("passenger"));
        assertFalse(clause.contains("under the water surface"));
    }

    @Test
    void outOfRangeClickReportsTheDistanceAgainstTheRange() {
        String clause = new EntityUseScene(false, 4.2, 3.0, false, null).explain();
        assertTrue(clause.contains("4.2 blocks from the eyes"));
        assertTrue(clause.contains("entity interaction range of 3.0"));
        assertFalse(clause.contains("sneak key"));
    }

    @Test
    void anOccupiedTargetIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(false, 1.0, 3.0, true, null).explain();
        assertTrue(clause.contains("already carries a passenger"));
    }

    /** 船在水面下泡过原版期限（60 刻）：报出泡了多久和期限本身，这是湿脚湖面空船骑不上去的常见原因。 */
    @Test
    void aLongSubmergedBoatIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, 75.0F).explain();
        assertTrue(clause.contains("under the water surface for 3.8 seconds"));
        assertTrue(clause.contains("60 ticks (3 seconds)"));
        assertFalse(clause.contains("sneak key"));
    }

    /** 船刚下水泡水未满期限：不构成拒收条件，不往报文里塞。 */
    @Test
    void aFreshlySubmergedBoatIsNotReported() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, 5.0F).explain();
        assertFalse(clause.contains("under the water surface"));
    }

    /** 目标不是船（泡水刻数为空）：这条规则不参与，不当作命中也不当异常。 */
    @Test
    void aNonBoatTargetNeverReportsSubmersion() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, null).explain();
        assertFalse(clause.contains("under the water surface"));
        assertFalse(clause.contains("on-sight rules"));
    }

    @Test
    void severalRulesMatchedAreListedTogether() {
        String clause = new EntityUseScene(true, 5.0, 3.0, true, 60.0F).explain();
        assertTrue(clause.contains("sneak key was still held"));
        assertTrue(clause.contains("5.0 blocks from the eyes"));
        assertTrue(clause.contains("already carries a passenger"));
        assertTrue(clause.contains("under the water surface for 3.0 seconds"));
    }

    /** 客户端看得见的拒收条件一条都没命中：如实说拒收发生在服务端一侧，不冒充知道原因。 */
    @Test
    void noVisibleRuleMatchedSaysTheRefusalHappenedServerSide() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, null).explain();
        assertTrue(clause.contains("none of the client-visible refusal rules matched"));
        assertTrue(clause.contains("server's side"));
    }
}
