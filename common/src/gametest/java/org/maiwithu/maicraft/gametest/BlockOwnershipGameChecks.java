// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.gametest;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.OwnershipQuery;
import org.maiwithu.maicraft.server.ServerLinkServices;

/**
 * 游戏内测试的共享核对动作：在真实服务端世界里演一遍方块归属链路的三个场景，由两个加载器
 * 各自的测试入口登记执行。这里只写"做什么与怎么断言"；测试注册、模板名的命名空间写法是
 * 加载器差异，留在各自的入口类里。
 */
public final class BlockOwnershipGameChecks {

    private BlockOwnershipGameChecks() {}

    /** 放下的方块要记在放置人名下，客户端按区块问归属时要答出同一格同一个人。 */
    public static void placeBlockRecordsOwner(GameTestHelper helper) {
        ServerPlayer player = mockPlayerInside(helper);
        helper.setBlock(4, 2, 4, Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(4, 3, 4));
        if (!placeBlockAs(player, Items.STONE, target.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到记录那一步");
        }
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(helper.getLevel().getServer());
        assertOwnerIs(helper, ownership, target, player.getUUID());
        assertChunkAnswerNamesOwner(helper, chunkAnswerFor(helper, player, target), target, player.getUUID());
        helper.succeed();
    }

    /** 方块被拆掉后这格的归属不再对应任何方块：记录去掉，按区块问也问不到了。 */
    public static void breakBlockForgetsOwner(GameTestHelper helper) {
        ServerPlayer player = mockPlayerInside(helper);
        helper.setBlock(4, 2, 4, Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(4, 3, 4));
        if (!placeBlockAs(player, Items.STONE, target.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到记录那一步");
        }
        // 走玩家自己的破坏路径：服务端的拆除钩子挂在方块破坏事件上，直接拆世界不经过它。
        if (!player.gameMode.destroyBlock(target)) {
            helper.fail("原版破坏没有成功，拆除钩子没被走到");
        }
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(helper.getLevel().getServer());
        if (ownership.ownerOf(helper.getLevel().dimension().location().toString(), target).isPresent()) {
            helper.fail("方块拆掉后归属记录还在，查到的人对不上任何方块");
        }
        JsonObject answer = chunkAnswerFor(helper, player, target);
        if (!answer.getAsJsonArray("cells").isEmpty()) {
            helper.fail("方块拆掉后按区块问归属还答出了这格");
        }
        helper.succeed();
    }

    /** 床头是原版放床脚时顺带放下的另一半，不经放置钩子：要一并记在放床的人名下。 */
    public static void bedBothHalvesRecorded(GameTestHelper helper) {
        ServerPlayer player = mockPlayerInside(helper);
        // 床脚和床头都要有支撑，先铺一格宽的地板；玩家朝向决定床头朝哪，断言按实际朝向算。
        helper.setBlock(4, 1, 4, Blocks.STONE);
        helper.setBlock(4, 1, 5, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.STONE);
        BlockPos foot = helper.absolutePos(new BlockPos(4, 2, 4));
        if (!placeBlockAs(player, Items.RED_BED, foot.below(), Direction.UP)) {
            helper.fail("原版放床没有成功，链路没走到记录那一步");
        }
        BlockState placed = helper.getLevel().getBlockState(foot);
        if (!placed.is(Blocks.RED_BED) || placed.getValue(BedBlock.PART) != BedPart.FOOT) {
            helper.fail("放下的不是床脚：另一半的推断前提不成立，" + placed);
        }
        BlockPos head = foot.relative(BedBlock.getConnectedDirection(placed));
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(helper.getLevel().getServer());
        assertOwnerIs(helper, ownership, foot, player.getUUID());
        assertOwnerIs(helper, ownership, head, player.getUUID());
        helper.succeed();
    }

    /** 造一个真实的服务端玩家并挪进测试区的角落：只读查询只答角色附近的区块，玩家得站在要问的格子旁。
     * 站角落而不是格子本身：玩家的碰撞箱盖住放置目标格时，原版判定无处可放，链路走不下去。 */
    private static ServerPlayer mockPlayerInside(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        player.teleportTo(helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    /** 让玩家像右键放置一样走一遍原版的方块物品放置路径，放置钩子就在这条路上。
     * 服务端上放置成功返回的是 CONSUME 而非 SUCCESS（sidedSuccess 按端取值），只认"这次动作成立"。 */
    private static boolean placeBlockAs(ServerPlayer player, Item item, BlockPos support, Direction face) {
        ItemStack held = new ItemStack(item);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support.relative(face)), face, support, false);
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, hit);
        return ((BlockItem) item).place(context).consumesAction();
    }

    private static void assertOwnerIs(GameTestHelper helper, BlockOwnershipRecord ownership,
                                      BlockPos position, UUID expected) {
        Optional<BlockOwnershipRecord.Owner> owner = ownership.ownerOf(
                helper.getLevel().dimension().location().toString(), position);
        if (owner.isEmpty()) {
            helper.fail("这一格没有归属记录：" + position);
        } else if (!owner.get().playerId().equals(expected)) {
            helper.fail("这一格记成了别人：" + position + " -> " + owner.get().playerId());
        }
    }

    /** 按这格所在区块问一次只读查询操作，走与客户端相同的入口与分页回答。 */
    private static JsonObject chunkAnswerFor(GameTestHelper helper, ServerPlayer player, BlockPos target) {
        JsonObject body = new JsonObject();
        body.addProperty("chunk_x", target.getX() >> 4);
        body.addProperty("chunk_z", target.getZ() >> 4);
        return new OwnershipQuery().apply(player, body);
    }

    private static void assertChunkAnswerNamesOwner(GameTestHelper helper, JsonObject answer,
                                                    BlockPos target, UUID expected) {
        JsonArray cells = answer.getAsJsonArray("cells");
        JsonArray owners = answer.getAsJsonArray("owners");
        for (int i = 0; i < cells.size(); i += 4) {
            boolean sameCell = cells.get(i).getAsInt() == target.getX()
                    && cells.get(i + 1).getAsInt() == target.getY()
                    && cells.get(i + 2).getAsInt() == target.getZ();
            if (!sameCell) continue;
            String owner = owners.get(cells.get(i + 3).getAsInt()).getAsString();
            if (!owner.equals(expected.toString())) {
                helper.fail("按区块问到的放置人是别人：" + owner);
            }
            return;
        }
        helper.fail("按区块问归属没有答出这格：" + target);
    }
}
