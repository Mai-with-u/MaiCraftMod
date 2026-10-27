// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖掉物理结构上的一格：先走到够得着、看得见它的地方（先挑船上，站在地上挖会推船；不站在它正上方），再挖到它没了。
 * 结构上没有连锁挖，一格一格来。
 */
final class StructureDigs implements DigsBlocks {

    private final StructureReads reads;
    private final PlayerServices services;
    private final Permissions permissions;
    private final String structureId;

    StructureDigs(StructureReads reads, PlayerServices services, Permissions permissions, String structureId) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
    }

    @Override public Optional<Action> dig(BlockPos target) {
        return Optional.of(new StepsInOrder("挖掉船上那一格", List.of(
                new StepsInOrder.Step("走到够得着的地方", () -> new StructureReach(reads, services, permissions, structureId,
                        ApproachTarget.ofBlock(target), cell -> false, true)),
                new StepsInOrder.Step("挖", () -> new StructureBreak(reads, services, structureId, target)))));
    }

    // 这一格此刻已经没了：空气或只剩流体；读不到时答还在。
    @Override public boolean gone(TickContext context, BlockPos cell) {
        if (context == null || context.player() == null || context.player().level() == null) return false;
        BlockState state = reads.block(context.player().level(), structureId, cell);
        return state != null && (state.isAir() || state.getBlock() instanceof LiquidBlock);
    }
}
