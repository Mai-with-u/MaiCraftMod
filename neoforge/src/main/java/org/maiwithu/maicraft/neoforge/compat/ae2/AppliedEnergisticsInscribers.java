// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.List;
import java.util.Optional;

import appeng.blockentity.misc.InscriberBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.Ae2Inscribers;

/**
 * AE2 压印器的读写端：压印器的四个工作格随方块实体的同步包下发（顶模板、底模板、输入、输出），
 * 客户端的方块实体上读得到；输出格是内部库存的第 3 格。进度与有没有电不同步，这里也不读。
 */
public final class AppliedEnergisticsInscribers implements Ae2Inscribers {

    /** 压印器内部库存四个工作格的格号：0 顶模板、1 底模板、2 输入、3 输出。类别：游戏事实（InscriberBlockEntity 的合并库存）。 */
    private static final int TOP_SLOT = 0;
    private static final int BOTTOM_SLOT = 1;
    private static final int INPUT_SLOT = 2;
    private static final int OUTPUT_SLOT = 3;

    @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
        if (level != null && level.getBlockEntity(at) instanceof InscriberBlockEntity inscriber) {
            ItemStack output = inscriber.getInternalInventory().getStackInSlot(OUTPUT_SLOT);
            if (output.isEmpty()) return Optional.of(List.of());
            return Optional.of(List.of(new MachineState.Shown(idOf(output), output.getCount())));
        }
        return Optional.empty();
    }

    @Override public Optional<Inputs> inputs(ClientLevel level, BlockPos at) {
        return slots(level, at).map(Slots::toInputs);
    }

    @Override public Optional<Slots> slots(ClientLevel level, BlockPos at) {
        if (level != null && level.getBlockEntity(at) instanceof InscriberBlockEntity inscriber) {
            // 压印器内部库存的四格工作槽随网络流完整同步到客户端，直接按格号读取当前物品与件数。
            var inv = inscriber.getInternalInventory();
            return Optional.of(new Slots(
                    shownOf(inv.getStackInSlot(TOP_SLOT)),
                    shownOf(inv.getStackInSlot(BOTTOM_SLOT)),
                    shownOf(inv.getStackInSlot(INPUT_SLOT)),
                    shownOf(inv.getStackInSlot(OUTPUT_SLOT))));
        }
        return Optional.empty();
    }

    private static MachineState.Shown shownOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : new MachineState.Shown(idOf(stack), stack.getCount());
    }

    private static String idOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
