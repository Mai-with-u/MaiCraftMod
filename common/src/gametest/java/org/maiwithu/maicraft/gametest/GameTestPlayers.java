// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 游戏内测试共用的真实服务端玩家构造与放置动作；两个加载器的测试入口都从这里取。 */
final class GameTestPlayers {

    private GameTestPlayers() {}

    /** 造一个真实服务端玩家并挪进测试区的角落：只读查询只答角色附近的区块，玩家得站在要问的格子旁。
     * 站角落而不是格子本身：玩家的碰撞箱盖住放置目标格时，原版判定无处可放，链路走不下去。 */
    static ServerPlayer inside(GameTestHelper helper) {
        return at(helper, 1, 1, 1);
    }

    /** 造一个真实服务端玩家并挪到测试区里指定的相对坐标。 */
    static ServerPlayer at(GameTestHelper helper, int x, int y, int z) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos position = helper.absolutePos(new BlockPos(x, y, z));
        player.teleportTo(helper.getLevel(), position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    /** 让玩家像右键放置一样走一遍原版的方块物品放置路径，放置钩子就在这条路上。
     * 服务端上放置成功返回的是 CONSUME 而非 SUCCESS（sidedSuccess 按端取值），只认"这次动作成立"。 */
    static boolean placeAs(ServerPlayer player, Item item, BlockPos support, Direction face) {
        ItemStack held = new ItemStack(item);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support.relative(face)), face, support, false);
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, hit);
        return ((BlockItem) item).place(context).consumesAction();
    }
}
