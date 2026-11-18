// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的安装段本事，登记表给它包的一层：模组停用后说不管安装段、装不了；
 * 读写时碰到模组接口对不上也在这里收住，交出的动作同样包一层。
 */
final class CompatMachineInstallations implements MachineInstallations {

    private final CompatModule module;
    private final MachineInstallations installations;

    CompatMachineInstallations(CompatModule module, MachineInstallations installations) {
        this.module = module;
        this.installations = installations;
    }

    // 停用或接口对不上时不编形状原因，交回一句用不了，审阅照实报。
    @Override public Optional<String> installationProblem(Installation installation) {
        return ask(() -> installations.installationProblem(installation), Optional.of(disabled()));
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        return act(() -> installations.install(installation, permissions));
    }

    @Override public Optional<Action> installOnStructure(Installation installation, Permissions permissions,
            BringsPlayerClose reach) {
        return act(() -> installations.installOnStructure(installation, permissions, reach));
    }

    @Override public boolean installed(Installation installation) {
        return ask(() -> installations.installed(installation), false);
    }

    @Override public Optional<List<BlockPos>> placedBy(Installation installation) {
        return ask(() -> installations.placedBy(installation), Optional.empty());
    }

    // 模组停用或对不上时说不管：组装载具照旧直接拉杆，结果里写明没查胶。
    @Override public Optional<List<Installation>> stickTogether(Set<BlockPos> cells) {
        return ask(() -> installations.stickTogether(cells), Optional.<List<Installation>>empty());
    }

    @Override public int stickPreference() {
        return ask(installations::stickPreference, 0);
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
