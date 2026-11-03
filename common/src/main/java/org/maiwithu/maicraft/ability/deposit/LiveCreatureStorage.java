// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 生物身上储物的生产实现：观察编号从场景换回游戏实体编号，从世界里读回此刻那一只；
 * 存放时走"点开生物身上的界面"，存完不记进世界记忆（生物会走，下次不一定在那儿）。
 */
final class LiveCreatureStorage implements DepositSeams.CreatureStorage {

    private final Supplier<Scene> scene;
    private final Supplier<PlayerContext> contexts;
    private final MenuLayouts layouts;
    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final MovesToMainhand toMainhand;
    private final HoldsSneak sneaks;

    LiveCreatureStorage(Supplier<Scene> scene, Supplier<PlayerContext> contexts, MenuLayouts layouts,
            BringsPlayerClose close, Interactions interactions, MovesToMainhand toMainhand, HoldsSneak sneaks) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.sneaks = Objects.requireNonNull(sneaks, "sneaks");
    }

    @Override public Optional<Creature> creature(String seenId) {
        Scene current = scene.get();
        PlayerContext player = contexts.get();
        if (current == null || player == null || player.level() == null) return Optional.empty();
        return current.seen().entityIn(player.level(), seenId)
                .map(entity -> new Creature(entity.getId(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        WorldPosition.here(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()),
                        entity.hasCustomName() ? entity.getCustomName().getString() : null));
    }

    @Override public StoresInContainer.Parts parts(int entityId) {
        // 马、驴、骡、羊驼右键是骑上去，潜行右键才开它的物品栏（原版游戏事实）；运输矿车、女仆直接右键就开。
        PlayerContext player = contexts.get();
        Entity entity = player == null || player.level() == null ? null : player.level().getEntity(entityId);
        boolean sneak = entity instanceof AbstractHorse;
        return new StoresInContainer.Parts((cell, permissions) -> ClientMenuOpening.ofEntity(entityId, sneak,
                permissions, layouts, close, interactions, toMainhand, sneaks, contexts), null);
    }
}
