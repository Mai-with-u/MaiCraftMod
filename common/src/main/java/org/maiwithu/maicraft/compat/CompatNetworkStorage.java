// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 联动模组交来的网络存储，登记表给它包的一层：模组停用后不算能存，交出的动作同样包一层，
 * 推进到一半停用按不支持收场；碰到模组接口对不上也在这里收住。
 */
public final class CompatNetworkStorage implements NetworkStorage {

    private final CompatModule module;
    private final NetworkStorage storage;

    public CompatNetworkStorage(CompatModule module, NetworkStorage storage) {
        this.module = Objects.requireNonNull(module, "module");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override public boolean reachable(TerminalSearch search) {
        return ask(() -> storage.reachable(search), false);
    }

    // 挑到的存的动作同样包一层；停用或接口对不上时按"没有能用的终端"说。
    @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
            TaskRecords records) {
        TerminalPick pick = ask(() -> storage.store(stacks, purpose, search, records),
                new TerminalPick.NotFound(module.name() + " 的联动用不了，存不进网络"));
        return pick instanceof TerminalPick.Chosen chosen
                ? new TerminalPick.Chosen(CompatAction.of(module, chosen.storing())) : pick;
    }

    // 问一句：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }
}
