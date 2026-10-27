// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ServerOperationException;

/**
 * 客户端可查询的只读操作"这个区块里哪些格是谁放的"：按请求里的区块查本服务器的方块归属记录。
 *
 * <p>一次答一个区块：角色走到哪、寻路要挖哪，客户端都按区块先问好记下，不逐格问。
 * 只答角色附近的区块：角色只在身边几个区块里动手，远处的不回答，免得有人拿它扫遍全图、找出谁在哪盖了房子。
 * 只回答记录里有什么；记录还没登记（服务器刚启动的第一刻之前）就答"这个区块一格都没记到"，不猜。
 * 回答里放置人单列一张表，每一格只带它在表里的序号：同一个人盖的房子不重复写他的编号。
 *
 * <p>一个区块里有主的格可能上万，一封回信装不下：按字数分页，格按位置编号从小到大给，
 * 还有下一页时写 more=true 和这一页最后一格的编号 after，客户端照抄 after 接着问，问完才把整块记下。
 */
public final class OwnershipQuery implements BiFunction<ServerPlayer, JsonObject, JsonObject> {

    /** 这个操作在客户端与服务端之间的编号。 */
    public static final String OPERATION = "ownership.chunk";
    /**
     * 操作版本：2 起回答会分页。只认一页的旧客户端协商不到这个版本，按"拿不准"处理，
     * 不会把只有第一页的区块当成整块、把没读到的格当成没人放的。
     */
    public static final int VERSION = 2;
    /**
     * 只答离角色这么多个区块以内的（横竖两个方向取远的那个）。客户端只问角色脚下 12 个区块以内的，
     * 多留 4 个区块给问出去到服务端收到之间角色又走了一段的情况。
     */
    public static final int MAX_DISTANCE_CHUNKS = 16;
    /** 一页回答最多写这么多字：协议一封回信最多 65536 字，扣掉信封外层还留足余量。 */
    static final int PAGE_CHARS = 40_000;
    /** 区块坐标的范围：世界边界三千万格，换成区块不到两百万。 */
    private static final long MAX_CHUNK_COORDINATE = 1_875_000;

    @Override
    public JsonObject apply(ServerPlayer player, JsonObject body) {
        int chunkX = chunkCoordinate(body, "chunk_x");
        int chunkZ = chunkCoordinate(body, "chunk_z");
        OptionalLong after = body.has("after") ? OptionalLong.of(cursor(body)) : OptionalLong.empty();
        // 先核对区块离角色近不近，再读记录：远处的区块连有没有记录都不透露。
        if (!near(SectionPos.blockToSectionCoord(player.getBlockX()), SectionPos.blockToSectionCoord(player.getBlockZ()),
                chunkX, chunkZ)) {
            throw ServerOperationException.notApplied("too_far", "只答角色附近的区块");
        }
        BlockOwnershipRecord record = ServerLinkServices.ownership(player.serverLevel().getServer());
        String dimension = player.serverLevel().dimension().location().toString();
        return page(record, dimension, chunkX, chunkZ, after);
    }

    /** 这个区块离角色所在的区块够不够近：横竖两个方向都不超过上限。 */
    static boolean near(int playerChunkX, int playerChunkZ, int chunkX, int chunkZ) {
        return Math.abs((long) chunkX - playerChunkX) <= MAX_DISTANCE_CHUNKS
                && Math.abs((long) chunkZ - playerChunkZ) <= MAX_DISTANCE_CHUNKS;
    }

    /**
     * 写一页回答：从 after 之后那一格读起，按字数装满一页为止；每一格写成四个数：x、y、z、放置人在表里的序号。
     * 记录还没登记时答一页空的。
     */
    static JsonObject page(BlockOwnershipRecord record, String dimension, int chunkX, int chunkZ, OptionalLong after) {
        JsonObject result = new JsonObject();
        JsonArray owners = new JsonArray();
        JsonArray cells = new JsonArray();
        result.add("owners", owners);
        result.add("cells", cells);
        result.addProperty("more", false);
        if (record == null) return result;
        List<UUID> people = new ArrayList<>();
        Map<UUID, Integer> index = new HashMap<>();
        int[] chars = {0};
        long[] last = {0};
        boolean[] more = {false};
        record.readChunk(dimension, chunkX, chunkZ, after, (position, owner) -> {
            Integer known = index.get(owner.playerId());
            int person = known != null ? known : people.size();
            // 这一格要写的字数：四个数加分隔，放置人第一次出现时再加上他那一串编号。
            int cost = digits(position.getX()) + digits(position.getY()) + digits(position.getZ()) + digits(person) + 4
                    + (known == null ? 39 : 0);
            // 装不下了：至少给了一格才分页，避免一页都给不出去。
            if (chars[0] + cost > PAGE_CHARS && !cells.isEmpty()) {
                more[0] = true;
                return false;
            }
            if (known == null) {
                index.put(owner.playerId(), person);
                people.add(owner.playerId());
            }
            cells.add(position.getX());
            cells.add(position.getY());
            cells.add(position.getZ());
            cells.add(person);
            chars[0] += cost;
            last[0] = position.asLong();
            return true;
        });
        people.forEach(person -> owners.add(person.toString()));
        if (more[0]) {
            result.addProperty("more", true);
            result.addProperty("after", last[0]);
        }
        return result;
    }

    private static int digits(long value) {
        return Long.toString(value).length();
    }

    // 区块坐标：必须是整数，并且在世界边界以内；不是就按坏请求回"没执行"。
    private static int chunkCoordinate(JsonObject body, String key) {
        long value;
        try {
            value = ProtocolJson.number(body, key);
        } catch (IllegalArgumentException invalid) {
            throw ServerOperationException.notApplied("bad_request", "请求要给整数的 " + key);
        }
        if (Math.abs(value) > MAX_CHUNK_COORDINATE) {
            throw ServerOperationException.notApplied("bad_request", key + " 超出世界范围");
        }
        return (int) value;
    }

    // 接着问的那一格编号：上一页回答里的 after 原样抄回来。
    private static long cursor(JsonObject body) {
        try {
            return ProtocolJson.number(body, "after");
        } catch (IllegalArgumentException invalid) {
            throw ServerOperationException.notApplied("bad_request", "after 要原样抄上一页回答里的整数");
        }
    }
}
