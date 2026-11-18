// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.MachineParts;
import org.maiwithu.maicraft.ability.machine.spi.MountedPart;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的部件本事，登记表给它包的一层：模组停用或接口对不上时不报部件、装不了部件；
 * 交出的动作同样包一层。
 */
final class CompatMachineParts implements MachineParts {

    private final CompatModule module;
    private final MachineParts parts;

    CompatMachineParts(CompatModule module, MachineParts parts) {
        this.module = module;
        this.parts = parts;
    }

    // 模组停用或接口对不上时不报部件：看机器照样列出线缆格，只是不再单列它上面的部件。
    @Override public List<MountedPart> mountedParts(BlockPos at) {
        return ask(() -> parts.mountedParts(at), List.of());
    }

    // 停用或接口对不上时不编宿主原因，交回一句用不了，审阅照实报。
    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return ask(() -> parts.partProblem(part, host), Optional.of(disabled()));
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return act(() -> parts.mount(part, permissions));
    }

    @Override public boolean mounted(PartCell part) {
        return ask(() -> parts.mounted(part), false);
    }

    // 读一样东西：停用了或对不上时给"用不了"时的那个答案。
    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }

    // 要一个动作：给出的动作包一层，推进到一半停用时如实按不支持收场。
    private Optional<Action> act(Supplier<Optional<Action>> make) {
        return ask(make, Optional.<Action>empty()).map(action -> CompatAction.of(module, action));
    }

    private String disabled() {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
