// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 别人的东西的生产实现：按交互与容器规格的取向分"改不改动世界"，归属交给全仓唯一的保护判断。
 *
 * <p>不改动世界的：开关门、活板门、栅栏门，按按钮、扳拉杆、敲钟、睡床，点开有界面的方块（箱子、熔炉、
 * 工作台这类，打开看看）；空手骑上船、矿车、马，空手找村民交易。其余一律算改动世界——倒流体、点火、
 * 耕地、剥树皮、剪毛、挤奶、喂食、染色、命名、拴绳、取下盔甲架上的东西，拿不准的也算。
 */
final class LiveOthersThings implements UseSeams.OthersThings {

    private final Supplier<PlayerContext> context;
    private final Supplier<Protection> protection;
    private final ReadsCreatureSituation creatures;
    private final OthersThingsNotice othersNotice;
    private final RareLootNotice rareLootNotice;

    LiveOthersThings(Supplier<PlayerContext> context, Supplier<Protection> protection,
            ReadsCreatureSituation creatures) {
        this(context, protection, creatures, OthersThingsNotice.NONE, RareLootNotice.NONE);
    }

    LiveOthersThings(Supplier<PlayerContext> context, Supplier<Protection> protection,
            ReadsCreatureSituation creatures, OthersThingsNotice othersNotice) {
        this(context, protection, creatures, othersNotice, RareLootNotice.NONE);
    }

    LiveOthersThings(Supplier<PlayerContext> context, Supplier<Protection> protection,
            ReadsCreatureSituation creatures, OthersThingsNotice othersNotice, RareLootNotice rareLootNotice) {
        this.context = Objects.requireNonNull(context, "context");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.creatures = Objects.requireNonNull(creatures, "creatures");
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.rareLootNotice = rareLootNotice == null ? RareLootNotice.NONE : rareLootNotice;
    }

    @Override
    public boolean changesWorld(String heldItem, ResolvedTarget target) {
        ClientLevel level = level();
        if (level == null) return true;
        if (target.isEntity()) {
            Entity entity = level.getEntity(target.entityId());
            // 空手骑上去、空手找村民交易不改动它；手里拿着东西对生物用（剪毛、挤奶、喂、染、拴、命名）都算。
            return heldItem != null || entity == null || !(entity instanceof Boat || entity instanceof AbstractMinecart
                    || entity instanceof AbstractHorse || entity instanceof AbstractVillager);
        }
        BlockState state = level.getBlockState(target.cell());
        Block block = state.getBlock();
        boolean operates = block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof BellBlock || block instanceof BedBlock;
        // 有界面的方块点开是打开看看（手里拿着方块也是先开界面）；开关门、按按钮这类是操作不是改动。
        return !(operates || state.getMenuProvider(level, target.cell()) != null);
    }

    @Override
    public boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks) {
        Protection guard = protection.get();
        if (guard == null) return true;
        // 按类型自己找到的一格：只有记住的区域与额外保护的地标算别人的，归属（谁放的）不参与。
        if (!target.isEntity()) return guard.protectedBy(cellOf(target), blockId(target), protectedLandmarks).protects();
        return creatureSomeoneElses(guard, target);
    }

    @Override
    public boolean someoneElsesPointedAt(ResolvedTarget target, Set<String> protectedLandmarks) {
        Protection guard = protection.get();
        if (guard == null) return true;
        if (!target.isEntity()) {
            return guard.protectedBy(cellOf(target), blockId(target), protectedLandmarks).protects();
        }
        return creatureSomeoneElses(guard, target);
    }

    private boolean creatureSomeoneElses(Protection guard, ResolvedTarget target) {
        ClientLevel level = level();
        Entity entity = level == null ? null : level.getEntity(target.entityId());
        if (entity == null) return true;
        // 处境读不到按别人的算；玩家不是谁的东西，但对玩家用东西同样要点名；角色自己驯服的不算别人的。
        return creatures.situationOf(entity.getUUID())
                .map(situation -> situation.player() || guard.creatureSomeoneElses(situation))
                .orElse(true);
    }

    @Override
    public String whyOthers(ResolvedTarget target, Set<String> protectedLandmarks) {
        Protection guard = protection.get();
        if (guard == null) return "保护判断还没接上，拿不准就按别人的算";
        if (target.isEntity()) return "有主（有名字、被驯服、拴着绳、圈养着）或是玩家";
        // 说清是哪一路挡的：记住的区域，还是额外保护的地标。只在指着的那一格被拒时问这里。
        return guard.describe(guard.protectedBy(cellOf(target), blockId(target), protectedLandmarks));
    }

    /** 这一格最后由谁放下，说成名字：动别人放的东西时回报里写明。归属没问到、自己放的给 null。 */
    @Override
    public String lastModifiedBy(ResolvedTarget target) {
        Protection guard = protection.get();
        if (guard == null || target.isEntity()) return null;
        return guard.placedByOther(cellOf(target)).map(guard::nameOf).orElse(null);
    }

    @Override
    public ContainerWhose containerWhose(ResolvedTarget target, boolean naturalLoot, boolean openedBefore) {
        if (target == null || target.isEntity()) {
            return new ContainerWhose(ContainerWhose.Kind.UNKNOWN, null, openedBefore);
        }
        return ContainerWhose.of(cellOf(target), naturalLoot, protection.get(), openedBefore);
    }

    @Override
    public Runnable prepareWorldChangeNotice(String heldItem, ResolvedTarget target) {
        if (target == null || target.isEntity() || !changesWorld(heldItem, target)) {
            return () -> {};
        }
        String block = blockId(target);
        if (block == null) return () -> {};
        var snapshot = othersNotice.snapshotBlock(cellOf(target), block);
        if (snapshot.isEmpty()) return () -> {};
        boolean[] fired = {false};
        return () -> {
            if (!fired[0]) {
                fired[0] = true;
                othersNotice.blockChanged(snapshot.get());
            }
        };
    }

    @Override
    public void reportRareLoot(ResolvedTarget target, ContainerWhose origin, WorldPosition spot,
            List<RareLootNotice.SeenPrecious> precious) {
        if (target == null || target.isEntity() || precious.isEmpty()) return;
        String block = blockId(target);
        if (block == null) return;
        rareLootNotice.report(spot, block, origin.whose(), rareLootNotice.snapshotPreviouslySeen(spot), precious);
    }

    // 目标格带上这一刻所在的维度：认家、问归属都按维度比，下界里和主世界家坐标重叠的格子不当成家。
    private WorldPosition cellOf(ResolvedTarget target) {
        ClientLevel level = level();
        String dimension = level == null ? null : level.dimension().location().toString();
        return new WorldPosition(target.cell().getX(), target.cell().getY(), target.cell().getZ(), dimension);
    }

    private static String blockId(ResolvedTarget target) {
        ResourceLocation id = ResourceLocation.tryParse(target.typeId());
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? target.typeId() : null;
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}
