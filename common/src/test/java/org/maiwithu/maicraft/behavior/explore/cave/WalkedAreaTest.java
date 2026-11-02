// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 走过的地方：每挪 2 格一个点，6 格内算走过，换维度清空，满了丢最早的。 */
class WalkedAreaTest {

    private static WorldPosition at(int x, int z) {
        return new WorldPosition(x, 64, z, "minecraft:overworld");
    }

    @Test
    void 每挪两格记一个点() {
        WalkedArea walked = new WalkedArea();
        for (int x = 0; x <= 10; x++) {
            walked.note(at(x, 0));
        }
        assertEquals(6, walked.size(), "0、2、4、6、8、10 各一个点");
    }

    @Test
    void 六格内算走过() {
        WalkedArea walked = new WalkedArea();
        walked.note(at(0, 0));
        assertTrue(walked.walkedNear(new BlockPos(6, 64, 0), CaveLegChoice.FRESH));
        assertFalse(walked.walkedNear(new BlockPos(7, 64, 0), CaveLegChoice.FRESH));
        assertFalse(walked.walkedNear(new BlockPos(0, 71, 0), CaveLegChoice.FRESH), "高低也算距离");
    }

    @Test
    void 换了维度清空() {
        WalkedArea walked = new WalkedArea();
        walked.note(at(0, 0));
        walked.note(new WorldPosition(100, 64, 100, "minecraft:the_nether"));
        assertEquals(1, walked.size());
        assertFalse(walked.walkedNear(new BlockPos(0, 64, 0), CaveLegChoice.FRESH));
    }

    @Test
    void 满了丢最早的() {
        WalkedArea walked = new WalkedArea();
        for (int i = 0; i <= WalkedArea.MAX_POINTS; i++) {
            walked.note(at(i * WalkedArea.STEP, 0));
        }
        assertEquals(WalkedArea.MAX_POINTS, walked.size());
        assertFalse(walked.walkedNear(new BlockPos(-6, 64, 0), 5), "最早的那个点丢了");
        assertTrue(walked.walkedNear(new BlockPos(WalkedArea.MAX_POINTS * WalkedArea.STEP, 64, 0), 1));
    }
}
