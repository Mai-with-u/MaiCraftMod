// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 找听法的生产实现：观察编号从场景换回游戏实体编号，从世界里读回此刻那一只，逐个问联动的说话办法。
 * 谁都不认时看它的实体类型属于哪个模组：那个模组装了、联动却没登记上，就如实说联动没登记，不说成它本来就不会。
 */
public final class LiveListeners implements FindsListener {

    private final Supplier<Scene> scene;
    private final Supplier<PlayerContext> contexts;
    private final Supplier<List<TalkTo>> talkTos;
    private final Function<String, Optional<String>> whyNotWorking;

    /**
     * @param talkTos       联动登记的说话办法：进世界时建好的那一份
     * @param whyNotWorking 某个模组装了、联动却没在工作时为什么；联动清单里没有、或好好登记着时给空
     */
    public LiveListeners(Supplier<Scene> scene, Supplier<PlayerContext> contexts, Supplier<List<TalkTo>> talkTos,
            Function<String, Optional<String>> whyNotWorking) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.talkTos = Objects.requireNonNull(talkTos, "talkTos");
        this.whyNotWorking = Objects.requireNonNull(whyNotWorking, "whyNotWorking");
    }

    @Override public Found find(String seenId) {
        Scene current = scene.get();
        PlayerContext player = contexts.get();
        if (current == null || player == null || player.level() == null) return new Found.Gone();
        Optional<Entity> creature = current.seen().entityIn(player.level(), seenId).filter(Entity::isAlive);
        if (creature.isEmpty()) return new Found.Gone();
        // 先问联动：谁认得这只生物、它会听，就交给谁。
        for (TalkTo talkTo : talkTos.get()) {
            Optional<TalkTo.Listener> listener = talkTo.listener(player, creature.get());
            if (listener.isPresent()) return new Found.Listening(listener.get());
        }
        // 谁都不认：按实体类型的命名空间认它属于哪个模组，那个模组装了却没登记上时如实说。
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(creature.get().getType());
        String what = creature.get().hasCustomName()
                ? creature.get().getCustomName().getString() + "（" + type + "）" : type.toString();
        return new Found.Deaf(what, type.getNamespace(), whyNotWorking.apply(type.getNamespace()).orElse(null));
    }
}
