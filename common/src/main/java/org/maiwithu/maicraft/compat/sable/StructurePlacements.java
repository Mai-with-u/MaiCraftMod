// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.LivePlacements;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 物理结构上的放置预测：从角色现在的眼睛找被点那一面看得见的点（换到世界里射线核过），再把命中点和视线换到结构自己的轴上，
 * 交给地上那一份放置预测算原版会放成什么——Sable 放方块时就是把人的朝向换到结构里再定朝向的，算法全仓只有一份。
 * 点会开界面的方块要蹲、放下后能不能拿扳手转到位，和地上一样问。
 */
final class StructurePlacements implements ConstructionSeams.PlansPlacement {

    private final StructureReads reads;
    private final Supplier<PlayerContext> context;
    private final String structureId;
    private final LivePlacements onLand;

    StructurePlacements(StructureReads reads, Supplier<PlayerContext> context, String structureId,
            List<PlacementRules> rules) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.context = Objects.requireNonNull(context, "context");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.onLand = new LivePlacements(context, rules);
    }

    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
        return predict(cell, cell.pos(), clicked, face);
    }

    // 命中点与视线按结构此刻的位姿换回地块：原版拿地块里的格、地块里的命中点和换过去的朝向定放成什么。
    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos placeAt, BlockPos clicked,
            Direction face) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        Optional<SableStructures.Structure> ship = StructureScene.find(reads, level, player, structureId);
        if (ship.isEmpty()) return Optional.empty();
        BlockState state = reads.block(level, structureId, clicked);
        if (state == null) return Optional.empty();
        Vec3 eye = player.getEyePosition();
        SableStructures.Pose pose = ship.get().pose();
        BlockHitResult seen = StructureSight.visible(level, player, pose, eye, clicked, face,
                InteractionRange.blockReach(player),
                StructureSight.aimPoints(clicked, state.getShape(level, clicked).toAabbs(), face));
        if (seen == null) return Optional.empty();
        Vec3 local = pose.toLocal(seen.getLocation());
        return onLand.predictLooking(cell, placeAt, new BlockHitResult(local, seen.getDirection(), clicked, false),
                local.subtract(pose.toLocal(eye)));
    }

    @Override public boolean requiresSneak(BlockPos clicked) {
        return onLand.requiresSneak(clicked);
    }

    @Override public Optional<PlacementRules.Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
        return onLand.turnsTo(placed, wanted);
    }
}
