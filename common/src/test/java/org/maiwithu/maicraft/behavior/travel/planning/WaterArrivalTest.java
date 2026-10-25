// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水面目标柱的收场话术：挪到岸格的偏差说明，与失败时给调用方的建议落点。 */
class WaterArrivalTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void detourNoteStatesWhereItLandedAndHowFarOff() {
        String note = WaterArrival.detourNote(134, 32, new WorldPosition(137, 62, 36, null));
        assertEquals("目标在水中央，落到了 (137, 62, 36)，偏差 5.0 格", note);
    }

    @Test
    void strictFailureAndOpenWaterFailureShareOneSuggestionFormat() {
        // 严格到达与开阔水找不到岸是两种失败，报文给调用方的建议落点是同一个格式：坐标 + 方位 + 距离。
        Problem strict = WaterArrival.waterColumnFailure(134, 32, true,
                Optional.of(new WorldPosition(134, 62, 36, null)));
        Problem openWater = WaterArrival.waterColumnFailure(134, 32, false,
                Optional.of(new WorldPosition(134, 62, 36, null)));
        assertTrue(strict.message().contains("(134, 62, 36)"));
        assertTrue(strict.message().contains("南 4.0 格"));
        assertTrue(openWater.message().contains("(134, 62, 36)"));
        assertTrue(openWater.message().contains("南 4.0 格"));
        // 区别只在为什么没挪：严格是玩家要的，开阔水是附近实在没有岸。
        assertTrue(strict.message().contains("严格到达不自动挪"));
        assertTrue(openWater.message().contains("附近没有找到能站的岸格"));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, strict.kind());
    }

    @Test
    void failureWithoutNearbyShoreSaysSo() {
        Problem none = WaterArrival.waterColumnFailure(134, 32, false, Optional.empty());
        assertTrue(none.message().contains("周围也没有找到能站的岸格"), none.message());
    }
}
