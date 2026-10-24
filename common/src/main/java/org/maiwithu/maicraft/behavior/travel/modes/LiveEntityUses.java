// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 右键一只实体的生产实现：包住交互动作入口的右键实体（骑乘、剪毛这一类）。
 * 启动时建一份交给出行的现场部件；替身测试不经过它。
 */
public final class LiveEntityUses implements UsesEntities {

    private final Interactions interactions;

    public LiveEntityUses(Interactions interactions) {
        this.interactions = interactions;
    }

    @Override
    public Action useEntity(Entity target, InteractionConfirmation confirmation) {
        return interactions.useEntity(target, confirmation);
    }
}
