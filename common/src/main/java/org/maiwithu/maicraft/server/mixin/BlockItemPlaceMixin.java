// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端记录方块归属与放置确认的钩子：原版把方块物品放进世界统一走 BlockItem.place，
 * 这里在它成功返回时把"谁、在哪、放了什么"交给服务端登记，并推送给该玩家的 MaiCraft 客户端。
 * 钩子挂在 place 而不是它内部的 placeBlock：床的物品类覆写了 placeBlock 只改放置参数，
 * 挂在 placeBlock 上放床就整张漏记。客户端侧的预测也会走进这个方法，只在服务端一侧记录。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void maicraft$recordPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // 只在放置真正成功时记录；失败的放置什么都没改变，不能当作归属。
        if (cir.getReturnValue() == InteractionResult.FAIL) return;
        // 客户端预演放置时这里拿到的是客户端玩家，不是服务端玩家：先认清是服务端那一侧再记，不能先强转。
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;
        // 放了什么以放置后的世界为准：占两格的方块此刻另一半已经由原版补放完毕，读到的是完整形态。
        BlockState placed = player.serverLevel().getBlockState(context.getClickedPos());
        if (!placed.is(((BlockItem) (Object) this).getBlock())) return;
        MinecraftServer server = player.serverLevel().getServer();
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(server);
        if (ownership == null) return;
        String dimension = player.serverLevel().dimension().location().toString();
        long tick = Integer.toUnsignedLong(server.getTickCount());
        ownership.recordPlacement(dimension, context.getClickedPos(), player.getUUID(), tick);
        // 门的上半、床头是原版顺带放下的另一半，不走放置流程：记在同一个人名下，客户端问到的整扇门、整张床都是这个人放的。
        BlockOwnershipRecord.otherHalf(context.getClickedPos(), placed)
                .ifPresent(half -> ownership.recordPlacement(dimension, half, player.getUUID(), tick));
        ServerConfirmations confirmations = ServerLinkServices.confirmations(server);
        if (confirmations != null) confirmations.placed(player, context.getClickedPos(), placed);
    }
}
