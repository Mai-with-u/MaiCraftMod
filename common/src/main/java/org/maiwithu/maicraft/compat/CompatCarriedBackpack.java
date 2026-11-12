// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 联动模组交来的随身背包，登记表给它包的一层：模组停用后空槽答 0、存放交回空、观察列表为空；
 * 交出的存放动作同样包一层，推进中途碰到模组接口对不上也在这里收住，不让 LinkageError 炸掉任务。
 */
public final class CompatCarriedBackpack implements CarriedBackpack {

    private final CompatModule module;
    private final CarriedBackpack backpack;

    public CompatCarriedBackpack(CompatModule module, CarriedBackpack backpack) {
        this.module = Objects.requireNonNull(module, "module");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
    }

    @Override public int freeSlots() {
        return ask(backpack::freeSlots, 0);
    }

    @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
        return ask(() -> backpack.store(stack, records), Optional.<Action>empty())
                .map(action -> CompatAction.of(module, action));
    }

    @Override public List<CarriedPack> carried() {
        return ask(backpack::carried, List.of());
    }

    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }
}
