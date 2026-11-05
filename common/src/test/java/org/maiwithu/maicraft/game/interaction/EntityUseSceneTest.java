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
        assertTrue(clause.contains("潜行键还按着"));
        assertFalse(clause.contains("超出了实体交互距离"));
        assertFalse(clause.contains("乘客"));
        assertFalse(clause.contains("在水面下泡了"));
    }

    @Test
    void outOfRangeClickReportsTheDistanceAgainstTheRange() {
        String clause = new EntityUseScene(false, 4.2, 3.0, false, null).explain();
        assertTrue(clause.contains("离眼睛 4.2 格"));
        assertTrue(clause.contains("实体交互距离 3.0 格"));
        assertFalse(clause.contains("潜行键"));
    }

    @Test
    void anOccupiedTargetIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(false, 1.0, 3.0, true, null).explain();
        assertTrue(clause.contains("已经有乘客"));
    }

    /** 船在水面下泡过原版期限（60 刻）：报出泡了多久和期限本身，这是湿脚湖面空船骑不上去的常见原因。 */
    @Test
    void aLongSubmergedBoatIsNamedAsTheRefusalRule() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, 75.0F).explain();
        assertTrue(clause.contains("在水面下泡了 3.8 秒"));
        assertTrue(clause.contains("泡水超过 3 秒"));
        assertFalse(clause.contains("潜行键"));
    }

    /** 船刚下水泡水未满期限：不构成拒收条件，不往报文里塞。 */
    @Test
    void aFreshlySubmergedBoatIsNotReported() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, 5.0F).explain();
        assertFalse(clause.contains("在水面下泡了"));
    }

    /** 目标不是船（泡水刻数为空）：这条规则不参与，不当作命中也不当异常。 */
    @Test
    void aNonBoatTargetNeverReportsSubmersion() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, null).explain();
        assertFalse(clause.contains("在水面下泡了"));
        assertFalse(clause.contains("当场命中的拒收条件"));
    }

    @Test
    void severalRulesMatchedAreListedTogether() {
        String clause = new EntityUseScene(true, 5.0, 3.0, true, 60.0F).explain();
        assertTrue(clause.contains("潜行键还按着"));
        assertTrue(clause.contains("离眼睛 5.0 格"));
        assertTrue(clause.contains("已经有乘客"));
        assertTrue(clause.contains("在水面下泡了 3.0 秒"));
    }

    /** 客户端看得见的拒收条件一条都没命中：如实说拒收发生在服务端一侧，不冒充知道原因。 */
    @Test
    void noVisibleRuleMatchedSaysTheRefusalHappenedServerSide() {
        String clause = new EntityUseScene(false, 1.0, 3.0, false, null).explain();
        assertTrue(clause.contains("拒收条件一条都没命中"));
        assertTrue(clause.contains("服务端一侧"));
    }
}
