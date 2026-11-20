// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;

/**
 * 木质曲柄（ae2:crank）的放置规则：必须等它附着的主机方块先放好（placeAfter），
 * 且只能贴附在支持手动摇动接口的有效面上（充能器非正面 5 面、压印器非正面 5 面、水晶催生器前后 2 面）。
 */
final class CrankPlacementRule implements PlacementRules {

    static final String CRANK_ID = "ae2:crank";
    private static final ResourceLocation CRANK_KEY = ResourceLocation.parse(CRANK_ID);

    private final Ae2MachineReads reads;

    CrankPlacementRule(Ae2MachineReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
        if (!isCrank(state)) return Optional.empty();
        Optional<Direction> facing = reads.crankFacing(state);
        if (facing.isEmpty()) return Optional.empty();
        // 曲柄的朝向指向附着面外侧，因此它依附的主机位于反方向相邻格。
        BlockPos hostPos = at.relative(facing.get().getOpposite());
        if (world.loaded(hostPos)) {
            BlockState hostState = world.state(hostPos);
            Set<Direction> validFaces = reads.crankAttachableFaces(hostState, null, hostPos);
            if (!validFaces.isEmpty() && !validFaces.contains(facing.get())) {
                return Optional.empty();
            }
        }
        Item crankItem = BuiltInRegistries.ITEM.getOptional(CRANK_KEY).orElse(Items.AIR);
        if (crankItem == Items.AIR) return Optional.empty();
        return Optional.of(new Placement(crankItem, at));
    }

    // 木质曲柄不能悬空放置，施工顺序必须等附着主机方块就位后再贴上去。
    @Override public List<BlockPos> placeAfter(BlockState state) {
        if (!isCrank(state)) return List.of();
        return reads.crankFacing(state)
                .map(facing -> List.of(BlockPos.ZERO.relative(facing.getOpposite())))
                .orElse(List.of());
    }

    /** 判断某主机方块某面是否属于支持曲柄附着的有效面（离线纯判定入口）。 */
    static boolean isAttachableFace(String hostBlockId, Direction hostFront, Direction hostBack, Direction attachFace) {
        if (hostBlockId == null || attachFace == null) return false;
        return switch (hostBlockId) {
            case "ae2:charger", "ae2:inscriber" -> hostFront != null && attachFace != hostFront;
            case "ae2:growth_accelerator" -> attachFace == hostFront || attachFace == hostBack;
            default -> false;
        };
    }

    private static boolean isCrank(BlockState state) {
        return state != null && CRANK_KEY.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
}
