// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * 空手右键就骑上去的载具：船、矿车、马这一族（骆驼也是马的分支）。
 * 村民是交易不是骑乘，不在这批里；手里拿着东西对它们用（装鞍、喂食）另有用途，也不算骑。
 */
public final class RightClickMounts {

    private RightClickMounts() {}

    /** 这只生物是不是空手右键就骑上去的载具。 */
    public static boolean mountsWithEmptyHand(Entity entity) {
        return entity instanceof Boat || entity instanceof AbstractMinecart || entity instanceof AbstractHorse;
    }
}
