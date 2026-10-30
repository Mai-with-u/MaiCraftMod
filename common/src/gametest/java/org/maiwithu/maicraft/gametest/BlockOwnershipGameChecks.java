// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.OwnershipFile;
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
        // 只看自己拆掉的那格：同区块里其他测试放的方块答出来是正常的，归属记录是世界共享的状态。
        JsonArray cells = answer.getAsJsonArray("cells");
        for (int i = 0; i < cells.size(); i += 4) {
            if (cells.get(i).getAsInt() == target.getX()
                    && cells.get(i + 1).getAsInt() == target.getY()
                    && cells.get(i + 2).getAsInt() == target.getZ()) {
                helper.fail("方块拆掉后按区块问归属还答出了这格");
            }
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

    /** 造一个真实的服务端玩家并挪进测试区的角落。 */
    private static ServerPlayer mockPlayerInside(GameTestHelper helper) {
        return GameTestPlayers.inside(helper);
    }

    /** 让玩家走一遍原版的方块物品放置路径，放置钩子就在这条路上。 */
    private static boolean placeBlockAs(ServerPlayer player, Item item, BlockPos support, Direction face) {
        return GameTestPlayers.placeAs(player, item, support, face);
    }

    /** 归属记录跟着存档走：放置后存盘，像重启后的首刻一样从盘上读回，人还是那个人。 */
    public static void saveAndReloadKeepsOwners(GameTestHelper helper) {
        ServerPlayer player = mockPlayerInside(helper);
        helper.setBlock(4, 2, 4, Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(4, 3, 4));
        if (!placeBlockAs(player, Items.STONE, target.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到记录那一步");
        }
        MinecraftServer server = helper.getLevel().getServer();
        String dimension = helper.getLevel().dimension().location().toString();
        // 与 OwnershipSaver 拍快照后写盘走同一段代码；这里在服务器线程上同步存，不等后台写盘线程。
        OwnershipFile file = OwnershipFile.of(server);
        file.saveIfDirty(ServerLinkServices.ownership(server));
        BlockOwnershipRecord reloaded = file.load();
        Optional<BlockOwnershipRecord.Owner> owner = reloaded.ownerOf(dimension, target);
        if (owner.isEmpty() || !owner.get().playerId().equals(player.getUUID())) {
            helper.fail("存盘读回后这格的归属丢了或记成了别人：" + target);
        }
        BlockPos neverPlaced = target.above(3);
        if (reloaded.ownerOf(dimension, neverPlaced).isPresent()) {
            helper.fail("没放过方块的一格在读回的记录里有了归属：" + neverPlaced);
        }
        helper.succeed();
    }

    /** 存档文件坏掉不挡开服：读不出来按空记录开始，不猜、不崩；内存里的记录不受坏文件影响。 */
    public static void brokenOwnershipFileStartsEmpty(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String dimension = helper.getLevel().dimension().location().toString();
        // 先经真实放置确保盘上有一份有效文件，再把它写坏。
        ServerPlayer player = mockPlayerInside(helper);
        helper.setBlock(4, 2, 4, Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(4, 3, 4));
        if (!placeBlockAs(player, Items.STONE, target.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到记录那一步");
        }
        OwnershipFile file = OwnershipFile.of(server);
        file.saveIfDirty(ServerLinkServices.ownership(server));
        // 与 OwnershipFile 的存盘文件同名：名字改了这里要一起改，测试会大声失败提醒。
        Path fileLocation = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("maicraft_block_owners.dat");
        try {
            Files.writeString(fileLocation, "这不是 NBT", StandardCharsets.UTF_8);
        } catch (IOException writeFailure) {
            helper.fail("写坏存档文件失败：" + writeFailure);
        }
        BlockOwnershipRecord reloaded = file.load();
        if (reloaded.ownerOf(dimension, target).isPresent()) {
            helper.fail("坏文件读出来不该有任何归属记录");
        }
        if (ServerLinkServices.ownership(server).ownerOf(dimension, target).isEmpty()) {
            helper.fail("内存里的归属记录不该被坏文件影响");
        }
        helper.succeed();
    }

    /** 查询的边界：远区块连有没有记录都不透露；坏请求按"没执行"回，不猜。 */
    public static void queryRejectsFarChunksAndBadRequests(GameTestHelper helper) {
        ServerPlayer player = mockPlayerInside(helper);
        OwnershipQuery query = new OwnershipQuery();
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        assertRejected(helper, () -> query.apply(player, chunkBody(playerChunkX + 17, playerChunkZ)), "too_far");
        assertRejected(helper, () -> query.apply(player, new JsonObject()), "bad_request");
        assertRejected(helper, () -> query.apply(player, chunkBody(2_000_000, 0)), "bad_request");
        helper.succeed();
    }

    private static JsonObject chunkBody(int chunkX, int chunkZ) {
        JsonObject body = new JsonObject();
        body.addProperty("chunk_x", chunkX);
        body.addProperty("chunk_z", chunkZ);
        return body;
    }

    private static void assertRejected(GameTestHelper helper, Supplier<JsonObject> call, String expectedCode) {
        try {
            call.get();
            helper.fail("这次调用应该按「" + expectedCode + "」拒绝，却成功了");
        } catch (ServerOperationException rejected) {
            if (!expectedCode.equals(rejected.code())) {
                helper.fail("拒绝码应是 " + expectedCode + "，实际是 " + rejected.code());
            }
        }
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
        return new OwnershipQuery().apply(player, chunkBody(target.getX() >> 4, target.getZ() >> 4));
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
