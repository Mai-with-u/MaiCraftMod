// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.mining.Campground;
import org.maiwithu.maicraft.behavior.navigation.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 营地地面坑的客户端扫描读端：每两秒或角色挪过六格刷新一轮场景里的营地坑，同时支持填坑任务在动手前即时复核。
 */
public final class ClientCampPitSight {

    /** 场景扫描的刷新间隔（刻，2 秒）。 */
    private static final long REFRESH_TICKS = 40;
    private static final double MOVE_REFRESH_DIST_SQ = 36.0;

    private final Campground campground;
    private final Predicate<WorldPosition> insideStructure;
    private long lastTick = Long.MIN_VALUE / 2;
    private BlockPos lastOrigin;
    private List<CampPits.Pit> cached = List.of();

    public ClientCampPitSight(Campground campground, Predicate<WorldPosition> insideStructure) {
        this.campground = Objects.requireNonNull(campground, "campground");
        this.insideStructure = Objects.requireNonNull(insideStructure, "insideStructure");
    }

    /** 按节拍刷新场景里的营地地面坑；不到刷新点时返回空表示沿用上一轮结果。 */
    public Optional<List<CampPits.Pit>> tick(PlayerContext current, long gameTick) {
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || current.level() == null) return Optional.empty();
        BlockPos origin = player.blockPosition();
        boolean moved = lastOrigin == null || origin.distSqr(lastOrigin) >= MOVE_REFRESH_DIST_SQ;
        if (!moved && gameTick - lastTick < REFRESH_TICKS) {
            return Optional.empty();
        }
        lastTick = gameTick;
        lastOrigin = origin.immutable();
        cached = scanNow(current, origin, CampPits.SCAN_RADIUS, true);
        return Optional.of(cached);
    }

    /** 对给定中心与半径立刻扫一遍地面坑：填坑动作开工前与复核时调它。 */
    public List<CampPits.Pit> scanNow(PlayerContext current, BlockPos origin, int radius, boolean requireCamp) {
        if (current == null || current.level() == null) return List.of();
        ClientLevel level = current.level();
        String dimension = level.dimension().location().toString();
        return CampPits.find(origin, radius, requireCamp, new CampPits.Ground() {
            @Override
            public boolean anyCampNearby(int cx, int cy, int cz, int r) {
                int step = 8;
                for (int dx = -r; dx <= r; dx += step) {
                    for (int dz = -r; dz <= r; dz += step) {
                        if (campground.covers(new WorldPosition(cx + dx, cy, cz + dz, dimension))) return true;
                    }
                }
                return campground.covers(new WorldPosition(cx, cy, cz, dimension));
            }

            @Override
            public boolean inCamp(int x, int y, int z) {
                return campground.covers(new WorldPosition(x, y, z, dimension));
            }

            @Override
            public boolean insideStructure(int x, int y, int z) {
                return insideStructure.test(new WorldPosition(x, y, z, dimension));
            }

            @Override
            public CampPits.Column columnAt(int x, int z, int refY) {
                return readColumn(level, x, z, refY);
            }
        });
    }

    private static CampPits.Column readColumn(ClientLevel level, int x, int z, int refY) {
        BlockPos probe = new BlockPos(x, refY, z);
        if (!level.isLoaded(probe)) return CampPits.Column.drain();
        int groundFeetY = ClientSurfaceHeight.constructionGround(level, x, z);
        if (groundFeetY <= level.getMinBuildHeight()) return CampPits.Column.drain();
        int topY = groundFeetY - 1;
        if (Math.abs(topY - refY) > 12) return CampPits.Column.drain();
        BlockPos topPos = new BlockPos(x, topY, z);
        BlockState topState = level.getBlockState(topPos);
        BlockState aboveState = level.getBlockState(topPos.above());
        // 水坑、岩浆、含水方块、耕地、楼梯、半砖、梯子、门栏、容器与工作设施都不算要填的天然烂坑。
        if (!topState.getFluidState().isEmpty() || !aboveState.getFluidState().isEmpty()) {
            return CampPits.Column.drain();
        }
        if (isArtificialFeature(level, topPos, topState) || isArtificialFeature(level, topPos.above(), aboveState)) {
            return CampPits.Column.drain();
        }
        // 长着原木树干的那一列：不填树干格，但树根高度照样给邻格提供平地基准。
        if (aboveState.is(BlockTags.LOGS)) {
            return CampPits.Column.naturalGround(topY);
        }
        // 室内地板或连着地下暗洞口的矿道台阶不算地面野坑。
        if (level.getBrightness(LightLayer.SKY, topPos.above()) < 12 || leadsIntoDarkTunnel(level, topPos.above())) {
            return CampPits.Column.drain();
        }
        return CampPits.Column.naturalGround(topY);
    }

    private static boolean isArtificialFeature(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.hasBlockEntity() || state.getMenuProvider(level, pos) != null) return true;
        var block = state.getBlock();
        return block instanceof FarmBlock || block instanceof StairBlock || block instanceof SlabBlock
                || block instanceof LadderBlock || block instanceof BedBlock || block instanceof DoorBlock
                || block instanceof TrapDoorBlock || block instanceof FenceBlock || block instanceof FenceGateBlock
                || block instanceof WallBlock;
    }

    // 坑底四邻若直接敞向天空光低于 8 的两格高暗通道，说明这是通往地下矿道或洞穴的入口，不堵口。
    private static boolean leadsIntoDarkTunnel(ClientLevel level, BlockPos feet) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!level.isLoaded(side)) continue;
            if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()
                    && level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()
                    && level.getBrightness(LightLayer.SKY, side) < CaveOpenings.DARK_SKY_LIGHT) {
                return true;
            }
        }
        return false;
    }
}
