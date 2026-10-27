// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.AimCheck;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 对着物理结构上的格时角色的第一人称现场：目标格是结构地块里的格，看得见的部位按结构此刻的位姿换成世界里的点给转头用；
 * 准星射线照原版打（Sable 让它打得中结构上的格，给的是地块里的格与命中点），够不够得着按换到世界后的距离算。
 * 交互动作每刻取一份，结构挪了照新的位姿算；结构不见了时一格都读不到、哪里都看不见，由交互动作照"够不着"收场。
 */
final class StructureScene implements FirstPersonScene {

    /** 在角色周围多远认这个结构（格）。 */
    static final int NEAR = 64;

    private final LocalPlayer player;
    private final ClientLevel level;
    private final StructureReads reads;
    /** 这一刻的结构；看不到了为 null。 */
    private final SableStructures.Structure ship;

    private StructureScene(LocalPlayer player, ClientLevel level, StructureReads reads, SableStructures.Structure ship) {
        this.player = player;
        this.level = level;
        this.reads = reads;
        this.ship = ship;
    }

    /** 交互动作每刻取现场的办法：按结构身份找这一刻的结构。 */
    static Function<PlayerContext, FirstPersonScene> of(StructureReads reads, String structureId) {
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(structureId, "structureId");
        return context -> new StructureScene(context.localPlayer(), context.level(), reads,
                find(reads, context.level(), context.localPlayer(), structureId).orElse(null));
    }

    /** 角色附近这个身份的结构。 */
    static Optional<SableStructures.Structure> find(StructureReads reads, ClientLevel level, LocalPlayer player,
            String structureId) {
        if (level == null || player == null) return Optional.empty();
        return reads.structures(level, player, player.getBoundingBox().inflate(NEAR)).stream()
                .filter(candidate -> candidate.id().equals(structureId)).findFirst();
    }

    @Override public Vec3 eyePosition() {
        return player.getEyePosition();
    }

    @Override public Vec3 viewVector() {
        return player.getViewVector(1.0f);
    }

    // 沿视线射原版射线：打中结构上的格按换到世界后的距离核够不够得着，打中世界里的格照原样核。
    @Override public HitResult sightRay() {
        double reach = InteractionRange.blockReach(player);
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = StructureSight.clip(level, player, eye, player.getViewVector(1.0f).scale(reach));
        if (hit.getType() != HitResult.Type.BLOCK) return hit;
        Vec3 world = onShip(hit.getBlockPos()) ? ship.pose().toWorld(hit.getLocation()) : hit.getLocation();
        return eye.distanceTo(world) <= reach ? hit : BlockHitResult.miss(world, hit.getDirection(), hit.getBlockPos());
    }

    @Override public BlockHitResult visibleHit(BlockPos target) {
        return visible(target, null);
    }

    @Override public BlockHitResult visibleFaceHit(BlockPos target, Direction face) {
        return visible(target, face);
    }

    // 结构上不倒桶：物品按自己射线用的也按整格找看得见的部位。
    @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) {
        return visible(target, null);
    }

    @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) {
        return AimCheck.hitsBlock(sightRay(), target);
    }

    @Override public BlockState blockAt(BlockPos pos) {
        return ship == null ? null : reads.block(level, ship.id(), pos);
    }

    @Override public boolean isLoaded(BlockPos pos) {
        return blockAt(pos) != null;
    }

    @Override public ItemStack heldItem(InteractionHand hand) {
        return player.getItemInHand(hand).copy();
    }

    // 外形按地块里那一格的方块算，瞄准点换到世界后挨个从眼睛射过去，第一下打中那一格（那一面）的才算。
    private BlockHitResult visible(BlockPos target, Direction face) {
        BlockState state = blockAt(target);
        if (state == null) return null;
        List<AABB> boxes = state.getShape(level, target).toAabbs();
        return StructureSight.visible(level, player, ship.pose(), player.getEyePosition(), target, face,
                InteractionRange.blockReach(player), StructureSight.aimPoints(target, boxes, face));
    }

    // 这一格在不在这艘船的地块里：地块里读得到方块的才是。
    private boolean onShip(BlockPos pos) {
        return ship != null && reads.block(level, ship.id(), pos) != null;
    }
}
