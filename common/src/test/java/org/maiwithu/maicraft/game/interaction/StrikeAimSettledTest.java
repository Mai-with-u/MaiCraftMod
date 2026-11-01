// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 近战出手前镜头到没到位：视线离瞄点 7° 以内，或准星已经先碰到它，都算到位。 */
class StrikeAimSettledTest {

    /** 水平面上偏开 degrees 度的方向。 */
    private static Vec3 turned(double degrees) {
        double radians = Math.toRadians(degrees);
        return new Vec3(Math.sin(radians), 0, Math.cos(radians));
    }

    @Test
    void crosshairOnTheTargetCountsEvenFarFromTheLeadPoint() {
        // 视线离提前后的瞄点 12°（3 格外疾跑横移的人），准星已经落在他身上：到位，出手。
        assertTrue(Interaction.strikeAimSettled(true, turned(0), turned(12).scale(3)));
    }

    @Test
    void blockedCrosshairIsNamedWithTheBlockAndTheGap() {
        // 准星落在柱子顶面那一格上：收场话写明是哪格方块、眼睛离瞄点几格，读结果的人知道是被台面挡住了。
        BlockHitResult hit = new BlockHitResult(new Vec3(0, 66, 1.9), Direction.UP, new BlockPos(0, 65, 2), false);
        assertEquals("准星打到的是 方块 0, 65, 2，眼睛离瞄点 2.27 格", Interaction.aimBlockedBy(hit, 2.27));
        assertEquals("准星打到的是 空气，眼睛离瞄点 3.10 格",
                Interaction.aimBlockedBy(BlockHitResult.miss(new Vec3(0, 0, 0), Direction.UP, BlockPos.ZERO), 3.1));
    }

    @Test
    void farFromTheLeadPointAndOffTheTargetWaits() {
        // 同样 12°，准星没在他身上：不出手，照旧等镜头转到。
        assertFalse(Interaction.strikeAimSettled(false, turned(0), turned(12).scale(3)));
    }

    @Test
    void withinSevenDegreesIsSettled() {
        assertTrue(Interaction.strikeAimSettled(false, turned(0), turned(6).scale(3)));
        assertFalse(Interaction.strikeAimSettled(false, turned(0), turned(8).scale(3)));
    }
}
