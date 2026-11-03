// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 工地读数的生产实现：全部按这一刻的客户端现场回答，角色不在世界里或那一格没加载时如实给空。
 * 临时方块的材料从身上挑：整块、易挖、没有方块实体、不会掉落的建材，越多的越先用。
 */
public final class LiveSite implements ConstructionSeams.ReadsSite {

    private final Supplier<PlayerContext> context;

    public LiveSite(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public boolean loaded(BlockPos pos) {
        ClientLevel level = level();
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        return level != null && level.isLoaded(pos) && level.isInWorldBounds(pos);
    }

    @Override public BlockState state(BlockPos pos) {
        ClientLevel level = level();
        return level == null ? null : level.getBlockState(pos);
    }

    @Override public String dimension() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().location().toString();
    }

    // 下界这类超热维度岩浆每格只少一级、流得更远：倒岩浆前圈流路按维度类型的这一项取流距。
    @Override public boolean lavaSpreadsFar() {
        ClientLevel level = level();
        return level != null && level.dimensionType().ultraWarm();
    }

    @Override public BlockPos feet() {
        LocalPlayer player = player();
        return player == null ? null : player.blockPosition();
    }

    @Override public int carried(String itemId) {
        LocalPlayer player = player();
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        return player == null || item == null ? 0 : player.getInventory().countItem(item);
    }

    @Override public boolean creative() {
        LocalPlayer player = player();
        return player != null && player.getAbilities().instabuild;
    }

    // 基岩这类挖掘速度为负的挖不动；世界边界外、建筑高度外也算做不了。
    @Override public boolean unbreakable(BlockPos pos) {
        ClientLevel level = level();
        if (level == null || !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) return true;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(level, pos) < 0;
    }

    // 原版放方块前看那一格里有没有挡着放的实体（女仆、狐狸、船这类，掉落物和经验球不算）；角色自己不算。
    @Override public Optional<String> creatureIn(BlockPos pos) {
        ClientLevel level = level();
        LocalPlayer player = player();
        if (level == null || player == null || !loaded(pos)) return Optional.empty();
        return level.getEntities(player, new AABB(pos), entity -> !entity.isRemoved() && entity.blocksBuilding)
                .stream().findFirst().map(LiveSite::describe);
    }

    // 从眼睛到那一面中心拉一条线，线上先碰到的能点中的实体就是挡着的：点下去点到的是它而不是那一面。
    @Override public Optional<String> creatureBetween(BlockPos clicked, Direction face) {
        LocalPlayer player = player();
        if (player == null || level() == null) return Optional.empty();
        Vec3 eye = player.getEyePosition();
        Vec3 point = Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, point, new AABB(eye, point).inflate(1.0),
                entity -> !entity.isSpectator() && entity.isPickable(), eye.distanceToSqr(point));
        return hit == null ? Optional.empty() : Optional.of(describe(hit.getEntity()));
    }

    // 生物的称呼：显示的名字加实体类型 ID，LLM 认得出是谁，也能按 ID 找到它。
    private static String describe(Entity entity) {
        return entity.getName().getString() + "（" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + "）";
    }

    @Override public Optional<String> temporaryMaterial() {
        PlayerContext current = context.get();
        if (current == null || current.backpack() == null) return Optional.empty();
        BackpackStack best = null;
        for (BackpackStack stack : current.backpack().stacks()) {
            if (!stack.buildingMaterial() || stack.precious() || stack.gear() || stack.food() || !plainFullBlock(stack.itemId())) continue;
            if (best == null || stack.count() > best.count()) best = stack;
        }
        return Optional.ofNullable(best).map(BackpackStack::itemId);
    }

    // 能当垫脚的：整块碰撞、没有方块实体、不会掉落（沙子、沙砾垫上去会塌）。
    private static boolean plainFullBlock(String itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        if (!(item instanceof BlockItem blockItem)) return false;
        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !state.hasBlockEntity()
                && !(blockItem.getBlock() instanceof FallingBlock);
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }
}
