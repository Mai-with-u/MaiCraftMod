// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.interaction.GestureConfirmations;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放置类交互的过期结算：确认窗等到过期、生效的证据一样都没出现时，
 * 放船、放床这类手势按「提交了但没生效」如实收场；拿不准的手势照旧按没能确认。
 */
class PlacementExpiryTest {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @Test
    void 放置类手势的确认挂上了过期即没生效的断定() {
        BlockPos effect = new BlockPos(1, 64, 1);
        // 放船（效果靠物品自己）与放床这类（目标格变样、开界面、手上变样任一）都是放置类：
        // 证据全靠客户端预测与服务端同步，一个窗口过去一样都没变就能断定没生效。
        assertTrue(GestureConfirmations.forGesture(ItemUseAim.Confirmation.OWN_USE_TAKES_EFFECT,
                effect, InteractionHand.MAIN_HAND, ItemStack.EMPTY, null, 0).expiryMeansNotApplied());
        assertTrue(GestureConfirmations.forGesture(ItemUseAim.Confirmation.TARGET_OR_HAND_CHANGES,
                effect, InteractionHand.MAIN_HAND, ItemStack.EMPTY, STONE, 0).expiryMeansNotApplied());
        // 点火这类效果可能延迟出现（火点着了也会自然熄灭）的，过期仍按没能确认，不冒充知道。
        assertFalse(GestureConfirmations.forGesture(ItemUseAim.Confirmation.FIRE_APPEARS,
                effect, InteractionHand.MAIN_HAND, ItemStack.EMPTY, STONE, 0).expiryMeansNotApplied());
    }

    @Test
    void 确认窗到期按断定结算() {
        // 挂了断定的放置类按「提交了但没生效」收场；没挂的照旧按没能确认。
        assertEquals(PendingInteraction.Status.CONFIRMED_NOT_APPLIED,
                DefaultInteractionSender.expiryStatus(GestureConfirmations.forGesture(
                        ItemUseAim.Confirmation.OWN_USE_TAKES_EFFECT,
                        new BlockPos(1, 64, 1), InteractionHand.MAIN_HAND, ItemStack.EMPTY, null, 0)));
        assertEquals(PendingInteraction.Status.UNCERTAIN,
                DefaultInteractionSender.expiryStatus(InteractionConfirmation.pending()));
    }
}
