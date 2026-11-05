// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 角色死一次会在哪重生。
 *
 * <p>原版不把玩家设下的重生点告诉客户端：只有本机跑着集成服务端（单人世界、开局域网的主机）时，
 * 在服务端线程上照原版重生时的判断读得到——设下的那格还是床、重生锚还有充能、旁边站得起来，就回那里，
 * 否则回世界出生点。连别人的服务器读不到，只能知道世界出生点（服务器进世界时告诉客户端的那个）。
 * 读法和死亡不掉落一样：这一刻交给服务端线程，回来之前答"正在读"，读到后记几秒再重读。
 */
public final class RespawnPoint {

    /** 重生到的是什么地方。 */
    public enum Source {
        /** 睡过的床。 */
        BED,
        /** 充了能的重生锚。 */
        RESPAWN_ANCHOR,
        /** 用 /spawnpoint 命令设下的点，不是床也不是重生锚。 */
        COMMAND,
        /** 世界出生点：没设过重生点，或者设下的那格已经不能用。 */
        WORLD_SPAWN
    }

    /**
     * 重生的地方。
     *
     * @param dimension 所在维度的 ID；世界出生点总在主世界
     */
    public record Spot(int x, int y, int z, String dimension, Source source) {}

    /** 读出来的样子。 */
    public enum Status {
        /** 在服务端线程上读到了这个角色真实的重生点。 */
        READ,
        /** 已经交给服务端线程去读，还没回来。 */
        READING,
        /** 连的是别人的服务器，客户端读不到设下的重生点。 */
        UNREADABLE
    }

    /** @param spot 只在 {@link Status#READ} 时有 */
    public record Reading(Status status, Spot spot) {}

    private static final Reading STILL_READING = new Reading(Status.READING, null);
    private static final Reading NOT_HERE = new Reading(Status.UNREADABLE, null);
    private static final String OVERWORLD = Level.OVERWORLD.location().toString();

    /** 读到的值记多久再重读（毫秒）：睡了一张新床，几秒后就能看到。 */
    private static final long REFRESH_MILLIS = 5_000;

    /** 上一次是从哪个集成服务端读的：换了世界就作废重读。 */
    private IntegratedServer readFrom;
    private CompletableFuture<Spot> pending;
    private Spot known;
    private long readAtMillis;

    /** 此刻读到的样子；只在客户端线程调用。 */
    public Reading read() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            forget(null);
            return NOT_HERE;
        }
        if (server != readFrom) forget(server);
        long now = System.currentTimeMillis();
        settle(now);
        // 还没读过或读到的太旧：交给服务端线程读一次，回来之前照旧答上次读到的（第一次答"正在读"）。
        if (pending == null && (known == null || now - readAtMillis >= REFRESH_MILLIS)) {
            UUID self = minecraft.player.getUUID();
            Spot before = known;
            pending = server.submit(() -> onServer(server, self, before));
        }
        return known == null ? STILL_READING : new Reading(Status.READ, known);
    }

    /** 客户端知道的世界出生点：服务器进世界、重生、换维度时都告诉客户端；还没进世界为空。只在客户端线程调用。 */
    public Optional<Spot> worldSpawn() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return Optional.empty();
        BlockPos spawn = level.getSharedSpawnPos();
        return Optional.of(new Spot(spawn.getX(), spawn.getY(), spawn.getZ(), OVERWORLD, Source.WORLD_SPAWN));
    }

    // 服务端线程上照原版重生的判断读：设下的点还能用就是它，不能用或没设过就是世界出生点。
    // 设下的那格不在加载范围里、上次已经核对过同一格时照旧用上次的结论，不为了看一眼把那片区块读进来。
    private static Spot onServer(MinecraftServer server, UUID self, Spot before) {
        ServerPlayer player = server.getPlayerList().getPlayer(self);
        if (player == null) return null;
        BlockPos pos = player.getRespawnPosition();
        ServerLevel level = server.getLevel(player.getRespawnDimension());
        if (pos != null && level != null) {
            String dimension = level.dimension().location().toString();
            if (!level.isLoaded(pos) && before != null && before.source() != Source.WORLD_SPAWN
                    && before.x() == pos.getX() && before.y() == pos.getY() && before.z() == pos.getZ()
                    && before.dimension().equals(dimension)) {
                return before;
            }
            Optional<Source> source = usable(level, pos, player.getRespawnAngle(), player.isRespawnForced());
            if (source.isPresent()) return new Spot(pos.getX(), pos.getY(), pos.getZ(), dimension, source.get());
        }
        BlockPos spawn = server.overworld().getSharedSpawnPos();
        return new Spot(spawn.getX(), spawn.getY(), spawn.getZ(), OVERWORLD, Source.WORLD_SPAWN);
    }

    // 和原版重生时一样判断设下的那格：重生锚要有充能（命令强设的不要）且这个维度能用、旁边站得起来；
    // 床要这个维度能睡、旁边站得起来；命令设的普通格要脚下和头顶都能站人。都不行就是不能用。
    private static Optional<Source> usable(ServerLevel level, BlockPos pos, float angle, boolean forced) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof RespawnAnchorBlock
                && (forced || state.getValue(RespawnAnchorBlock.CHARGE) > 0) && RespawnAnchorBlock.canSetSpawn(level)) {
            return RespawnAnchorBlock.findStandUpPosition(EntityType.PLAYER, level, pos).map(standing -> Source.RESPAWN_ANCHOR);
        }
        if (state.getBlock() instanceof BedBlock && BedBlock.canSetSpawn(level)) {
            return BedBlock.findStandUpPosition(EntityType.PLAYER, level, pos, state.getValue(BedBlock.FACING), angle)
                    .map(standing -> Source.BED);
        }
        if (!forced) return Optional.empty();
        BlockState above = level.getBlockState(pos.above());
        boolean room = state.getBlock().isPossibleToRespawnInThis(state) && above.getBlock().isPossibleToRespawnInThis(above);
        return room ? Optional.of(Source.COMMAND) : Optional.empty();
    }

    // 服务端线程读回来了就记下；读的时候出错（服务端正在关）或角色还没在服务端上，当没读到，下次再读。
    private void settle(long now) {
        if (pending == null || !pending.isDone()) return;
        try {
            Spot read = pending.join();
            if (read != null) {
                known = read;
                readAtMillis = now;
            }
        } catch (RuntimeException failed) {
            known = null;
        }
        pending = null;
    }

    // 换了世界或连到别人的服务器：上一个世界读到的重生点不能拿来用。
    private void forget(IntegratedServer server) {
        readFrom = server;
        pending = null;
        known = null;
        readAtMillis = 0;
    }
}
