// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.farmersdelight;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.farmersdelight.FarmersDelightReads;

/**
 * 农夫乐事厨锅的读写端：九个格随方块实体的同步包下发，客户端的方块实体上读得到——
 * 餐品显示格（第 6 格）与成品输出格（第 8 格）是做好的餐；供热判定是模组自己的方法，
 * 只读下方方块的状态与标签，客户端照样算得出来。煮的进度不同步，这里也不读。
 */
public final class FarmersDelightClientReads implements FarmersDelightReads {

    /** 厨锅库存里的餐品显示格、成品输出格。类别：游戏事实（CookingPotBlockEntity 的槽位常量）。 */
    private static final int MEAL_DISPLAY_SLOT = CookingPotBlockEntity.MEAL_DISPLAY_SLOT;
    private static final int OUTPUT_SLOT = CookingPotBlockEntity.OUTPUT_SLOT;

    @Override public Optional<List<MachineState.Shown>> meal(ClientLevel level, BlockPos at) {
        if (level.getBlockEntity(at) instanceof CookingPotBlockEntity pot) {
            List<MachineState.Shown> shown = new ArrayList<>();
            for (int slot : new int[] {MEAL_DISPLAY_SLOT, OUTPUT_SLOT}) {
                ItemStack stack = pot.getInventory().getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    shown.add(new MachineState.Shown(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
                }
            }
            return Optional.of(List.copyOf(shown));
        }
        return Optional.empty();
    }

    @Override public Optional<Boolean> heated(ClientLevel level, BlockPos at) {
        if (level.getBlockEntity(at) instanceof CookingPotBlockEntity pot) {
            return Optional.of(pot.isHeated());
        }
        return Optional.empty();
    }
}
