// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 右键方块与使用手中物品各自的确认依据（右键生物的在 {@link EntityUseConfirmation}）：点击格、相邻格、手中物品或菜单只要有一项变化，就作为点击已生效的线索。
 * 这些依据只回答「游戏接没接这一下」，不验证每种物品真正想产生的完整结果，也不能拿它们代替成品检查。
 */
final class UseConfirmations {

    private UseConfirmations() {}

    /** 右键方块：核对点击格、相邻格、手中物品、菜单，外加告示牌编辑屏与打火石点火这两项特殊线索。 */
    static InteractionConfirmation blockUse(Player player, BlockHitResult hit, InteractionHand usedHand) {
        var clicked = hit.getBlockPos().immutable();
        var adjacent = clicked.relative(hit.getDirection()).immutable();
        var clickedBefore = player.level().getBlockState(clicked);
        ItemStack heldBefore = player.getItemInHand(usedHand).copy();
        int beforeMenu = player.containerMenu.containerId;
        InteractionConfirmation adjacentChanged = player.level().isLoaded(adjacent)
                ? InteractionConfirmation.blockChanged(
                        adjacent, player.level().getBlockState(adjacent))
                : InteractionConfirmation.pending();
        // 告示牌右键的原生效果是打开编辑屏，四项世界观察全都察觉不到；把屏幕出现补进确认依据，
        // 否则点击注定以“未确认”超时收场，后续写字阶段根本拿不到执行权。
        InteractionConfirmation signEditor = clickedBefore.getBlock() instanceof SignBlock
                ? InteractionConfirmation.signEditorScreenOpened()
                : InteractionConfirmation.pending();
        // 打火石把落格的火或传送门方块补进确认依据；只有耐久变化时确认虽可通过，
        // 火是否真实出现仍由调用方的事后检查如实裁决，不在这里改写结论。
        InteractionConfirmation ignition = heldBefore.is(Items.FLINT_AND_STEEL)
                ? InteractionConfirmation.ignitionWorldEffect(clicked.relative(hit.getDirection()))
                : InteractionConfirmation.pending();
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.blockChanged(clicked, clickedBefore),
                adjacentChanged,
                InteractionConfirmation.heldItemChanged(usedHand, heldBefore),
                InteractionConfirmation.menuChanged(beforeMenu),
                signEditor,
                ignition);
    }

    /** 使用手中物品：手里东西变了、菜单变了，或已经进入使用状态，都算这一下被游戏接了。 */
    static InteractionConfirmation itemUse(Player player, InteractionHand usedHand, ItemStack heldBefore) {
        int beforeMenu = player.containerMenu.containerId;
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(usedHand, heldBefore),
                InteractionConfirmation.menuChanged(beforeMenu),
                c -> c.localPlayer().isUsingItem()
                        ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING);
    }
}
