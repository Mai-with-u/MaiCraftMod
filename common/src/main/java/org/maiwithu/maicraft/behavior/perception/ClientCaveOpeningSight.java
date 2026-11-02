// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;

/**
 * 洞口观察的读端：每刻推进一小段"扫一圈"，扫完一圈交出这一圈看到的洞口与峡谷，接着从角色此刻脚下再扫下一圈。
 * 只读客户端已加载的区块，不动身体、不改方块。
 *
 * <p>能进人按碰撞外形为空、不是液体算：门（关着开着都一样）挡住往里量，有门的房子不会被当成洞；
 * 地表跳过树叶和树干，两棵大树中间不会被当成一道沟。视线走观察共用的遮挡判断，墙后的不算看见。
 */
public final class ClientCaveOpeningSight implements CaveOpenings.Ground {

    private CaveOpenings.Sweep sweep;
    private String sweepDimension;
    /** 这一刻在读的世界与角色：扫描只在本刻推进时读，跨刻不留。 */
    private ClientLevel level;
    private LocalPlayer player;

    /**
     * 推进一刻：扫完一圈给这一圈看到的开口（近的在前），没扫完给空。没有角色时作废手上的这一圈，
     * 换了维度从头扫——上一个维度的地表不能拿来认这边的洞。
     */
    public Optional<List<CaveOpenings.Opening>> tick(PlayerContext current) {
        level = current == null ? null : current.level();
        player = current == null ? null : current.localPlayer();
        if (level == null || player == null) {
            sweep = null;
            return Optional.empty();
        }
        String dimension = level.dimension().location().toString();
        if (sweep == null || !dimension.equals(sweepDimension)) {
            sweep = new CaveOpenings.Sweep(this, player.blockPosition());
            sweepDimension = dimension;
        }
        if (!sweep.tick()) {
            return Optional.empty();
        }
        List<CaveOpenings.Opening> found = sweep.openings();
        // 下一刻从角色那时站的地方起扫下一圈：人一直在走，洞口的远近和看不看得见跟着变。
        sweep = null;
        return Optional.of(found);
    }

    @Override
    public boolean loaded(int x, int z) {
        // 客户端世界的 hasChunkAt 永远答"加载着"，按区块真加载没加载判。
        return level.isLoaded(new BlockPos(x, 0, z));
    }

    @Override
    public int surfaceY(int x, int z) {
        // 从同步了的高度图往下读：树叶、树干跳过（树底下照样是露天），停在第一格挡身体的方块或液体上面。
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        for (int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1; y >= minY; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) continue;
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) return y + 1;
        }
        return minY;
    }

    @Override
    public boolean roomy(int x, int y, int z) {
        return roomy(level, new BlockPos(x, y, z));
    }

    /**
     * 能进人：碰撞外形为空、不是液体。门关着开着都挡（有门的房子不会被当成洞）；认洞口往里量、钻洞量路共用这一条。
     */
    public static boolean roomy(BlockGetter level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, cell).isEmpty();
    }

    @Override
    public boolean standable(int x, int y, int z) {
        return BlockPassability.isDryStandable(level, new BlockPos(x, y, z));
    }

    @Override
    public int skyLight(int x, int y, int z) {
        return level.getBrightness(LightLayer.SKY, new BlockPos(x, y, z));
    }

    @Override
    public boolean visible(int x, int y, int z) {
        return ObservationVisibility.point(player, new Vec3(x + 0.5, y + 0.5, z + 0.5));
    }
}
