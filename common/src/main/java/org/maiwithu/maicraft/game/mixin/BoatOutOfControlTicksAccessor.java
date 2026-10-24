// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 船自己记的失控账的读端：船连续没入水下的刻数是原版私有的，骑乘拒收判断要取同一个数，不在本地另记一份。 */
@Mixin(Boat.class)
public interface BoatOutOfControlTicksAccessor {

    @Accessor("outOfControlTicks")
    float maicraft$outOfControlTicks();
}
