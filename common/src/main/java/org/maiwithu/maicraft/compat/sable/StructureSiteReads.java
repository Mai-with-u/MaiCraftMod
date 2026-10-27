// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.LiveSite;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 物理结构上的工地读数：格读结构地块里的（那一块没拿到就如实说没加载，不当空气），角色脚下按结构此刻的位姿换成地块里的格；
 * 身上有几件、是不是创造模式、在哪个维度照地上的读。结构上不垫临时方块：垫上去就成了船的一部分，改船的重量和样子。
 */
final class StructureSiteReads implements ConstructionSeams.ReadsSite {

    private final StructureReads reads;
    private final Supplier<PlayerContext> context;
    private final String structureId;
    /** 身上的东西、维度、创造模式这几样和地上一样读。 */
    private final LiveSite onLand;

    StructureSiteReads(StructureReads reads, Supplier<PlayerContext> context, String structureId) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.context = Objects.requireNonNull(context, "context");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.onLand = new LiveSite(context);
    }

    @Override public boolean loaded(BlockPos pos) {
        return read(pos) != null;
    }

    @Override public BlockState state(BlockPos pos) {
        BlockState state = read(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override public String dimension() {
        return onLand.dimension();
    }

    // 角色脚下：人在世界里，按结构此刻的位姿换到地块里那一格（施工就近挑下一格时按它算远近）。
    @Override public BlockPos feet() {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        return StructureScene.find(reads, level, player, structureId)
                .map(ship -> BlockPos.containing(ship.pose().toLocal(player.position())))
                .orElse(null);
    }

    @Override public int carried(String itemId) {
        return onLand.carried(itemId);
    }

    @Override public boolean creative() {
        return onLand.creative();
    }

    // 挖不动的：挖掘速度为负的（基岩这类）。结构上没有世界边界那一说。
    @Override public boolean unbreakable(BlockPos pos) {
        BlockState state = read(pos);
        PlayerContext current = context.get();
        return state != null && !state.isAir() && current != null && current.level() != null
                && state.getDestroySpeed(current.level(), pos) < 0;
    }

    @Override public Optional<String> temporaryMaterial() {
        return Optional.empty();
    }

    @Override public boolean temporaryBlocksAllowed() {
        return false;
    }

    @Override public boolean lavaSpreadsFar() {
        return onLand.lavaSpreadsFar();
    }

    private BlockState read(BlockPos pos) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        return level == null ? null : reads.block(level, structureId, pos);
    }
}
