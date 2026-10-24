// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 对一只实体右键使用的动作接缝：坐上载具（船、骆驼、矿车）用。
 * 走到够得着的站位由调用方负责，动作自己完成瞄准、核对命中、提交与逐刻确认。
 * 生产实现包住交互动作入口，测试用替身。
 */
public interface UsesEntities {

    /** 右键这只实体，生效与否按给定的确认条件核对；接缝没接上时给空。 */
    Action useEntity(Entity target, InteractionConfirmation confirmation);
}
