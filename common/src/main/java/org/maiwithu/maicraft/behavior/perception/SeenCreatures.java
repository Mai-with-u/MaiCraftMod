// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 点名的生物：LLM 给一只生物的观察编号 e#（对它说话、改它的设置），从世界里读回此刻那一只；
 * 没有哪个联动认得它时，按实体类型的命名空间认它属于哪个模组——那个模组装了、联动却没登记上，
 * 就如实说"联动没登记"，不说成它本来就不会。
 */
public final class SeenCreatures {

    private final Supplier<Scene> scene;
    private final Supplier<PlayerContext> contexts;
    private final Function<String, Optional<String>> whyNotWorking;

    /**
     * @param whyNotWorking 某个模组装了、联动却没在工作时为什么；联动清单里没有、或好好登记着时给空
     */
    public SeenCreatures(Supplier<Scene> scene, Supplier<PlayerContext> contexts,
            Function<String, Optional<String>> whyNotWorking) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.whyNotWorking = Objects.requireNonNull(whyNotWorking, "whyNotWorking");
    }

    /** 观察编号此刻对应的那只活着的生物；不在了（走远、死了、被收起来）给空。 */
    public Optional<Entity> find(String seenId) {
        Scene current = scene.get();
        PlayerContext player = contexts.get();
        if (current == null || player == null) return Optional.empty();
        return current.seen().entityIn(player.level(), seenId).filter(Entity::isAlive);
    }

    /** 此刻的角色；不在世界里为 null。 */
    public PlayerContext player() {
        return contexts.get();
    }

    /** 没有联动认得它时，它是什么、属于哪个模组、那个模组的联动为什么没在工作。 */
    public Unhandled unhandled(Entity creature) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType());
        String what = creature.hasCustomName()
                ? creature.getCustomName().getString() + "（" + type + "）" : type.toString();
        return new Unhandled(what, type.getNamespace(), whyNotWorking.apply(type.getNamespace()).orElse(null));
    }

    /**
     * 没有联动认得的一只生物。
     *
     * @param what         它是什么：名字与实体类型
     * @param modId        它所属的模组（实体类型的命名空间）；原版生物为 minecraft
     * @param unregistered 这个模组装了、联动却没登记上时为什么；不是这种情况为 null
     */
    public record Unhandled(String what, String modId, String unregistered) {}
}
