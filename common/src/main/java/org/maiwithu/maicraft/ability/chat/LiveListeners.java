// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.behavior.perception.SeenCreatures;

/**
 * 找听法的生产实现：观察编号换回世界里此刻那只生物，逐个问联动的说话办法；
 * 谁都不认时如实说它是什么、它所属的模组联动登没登记上，不说成它本来就不会。
 */
public final class LiveListeners implements FindsListener {

    private final SeenCreatures creatures;
    private final Supplier<List<TalkTo>> talkTos;

    /** @param talkTos 联动登记的说话办法：进世界时建好的那一份 */
    public LiveListeners(SeenCreatures creatures, Supplier<List<TalkTo>> talkTos) {
        this.creatures = Objects.requireNonNull(creatures, "creatures");
        this.talkTos = Objects.requireNonNull(talkTos, "talkTos");
    }

    @Override public Found find(String seenId) {
        Optional<Entity> creature = creatures.find(seenId);
        if (creature.isEmpty()) return new Found.Gone();
        // 先问联动：谁认得这只生物、它会听，就交给谁。
        for (TalkTo talkTo : talkTos.get()) {
            Optional<TalkTo.Listener> listener = talkTo.listener(creatures.player(), creature.get());
            if (listener.isPresent()) return new Found.Listening(listener.get());
        }
        SeenCreatures.Unhandled unhandled = creatures.unhandled(creature.get());
        return new Found.Deaf(unhandled.what(), unhandled.modId(), unhandled.unregistered());
    }
}
