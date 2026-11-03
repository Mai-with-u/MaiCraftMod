// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.behavior.perception.SeenCreatures;

/**
 * 找女仆的生产实现：观察编号换回世界里此刻那只生物，逐个问联动登记的改女仆设置的办法；
 * 谁都不认时如实说它是什么、它所属的模组联动登没登记上。
 */
public final class LiveMaids implements FindsMaid {

    private final SeenCreatures creatures;
    private final Supplier<List<MaidSettings>> settings;

    /** @param settings 联动登记的改女仆设置的办法：进世界时建好的那一份 */
    public LiveMaids(SeenCreatures creatures, Supplier<List<MaidSettings>> settings) {
        this.creatures = Objects.requireNonNull(creatures, "creatures");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public Found find(String seenId) {
        Optional<Entity> creature = creatures.find(seenId);
        if (creature.isEmpty()) return new Found.Gone();
        for (MaidSettings each : settings.get()) {
            Optional<MaidSettings.Maid> maid = each.maid(creatures.player(), creature.get());
            if (maid.isPresent()) return new Found.Maid(maid.get());
        }
        SeenCreatures.Unhandled unhandled = creatures.unhandled(creature.get());
        return new Found.NotMaid(unhandled.what(), unhandled.modId(), unhandled.unregistered());
    }
}
