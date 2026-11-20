// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Problem;
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
        if (!(pick instanceof TerminalPick.Chosen chosen)) return pick;
        Action wrapped = chosen.storing() instanceof ReportsStoreProblem reporting
                ? new Storing(module, chosen.storing(), reporting)
                : CompatAction.of(module, chosen.storing());
        return new TerminalPick.Chosen(wrapped);
    }

    // 读最近记下的网络存货快照：模组已停用或接口对不上时返回空表。
    @Override public List<RememberedNetwork> rememberedNetworks() {
        return ask(storage::rememberedNetworks, List.of());
    }

    // 找这一格装着的终端面板包围盒：停用或接口对不上时按没有面板处理，退回普通方块点击。
    @Override public Optional<AABB> terminalPanelAt(BlockPos cell, Direction preferredSide) {
        return ask(() -> storage.terminalPanelAt(cell, preferredSide), Optional.empty());
    }

    // 认领刚点开的终端界面并包装查看动作：推进中模组停用或报错时由包装层按不支持安全收尾。
    @Override public Optional<TerminalLook> openTerminalLook(BlockPos clickedCell, Direction clickedFace) {
        Optional<TerminalLook> opened = ask(() -> storage.openTerminalLook(clickedCell, clickedFace), Optional.empty());
        return opened.map(look -> new Looking(module, look));
    }

    /** 终端界面查看动作的联动保护壳：动作结束后原样交出动作自己记下的网络摘要。 */
    private static final class Looking extends CompatAction implements TerminalLook {
        private final TerminalLook look;

        Looking(CompatModule module, TerminalLook look) {
            super(module, look);
            this.look = look;
        }

        @Override public Optional<String> summary() {
            return look.summary();
        }
    }

    /** 存进网络动作的联动保护壳：部分存入后把底层网络拒收原因原样透传给上层任务。 */
    private static final class Storing extends CompatAction implements ReportsStoreProblem {
        private final ReportsStoreProblem reporting;

        Storing(CompatModule module, Action action, ReportsStoreProblem reporting) {
            super(module, action);
            this.reporting = reporting;
        }

        @Override public Optional<Problem> storeProblem() {
            return reporting.storeProblem();
        }
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
