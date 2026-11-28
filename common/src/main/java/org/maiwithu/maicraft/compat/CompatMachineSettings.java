// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的设置项本事，登记表给它包的一层：模组停用后读不到设置、给不出动作；
 * 读写时碰到模组接口对不上也在这里收住，交出的动作同样包一层。
 */
final class CompatMachineSettings implements MachineSettings {

    private final CompatModule module;
    private final MachineSettings settings;

    CompatMachineSettings(CompatModule module, MachineSettings settings) {
        this.module = module;
        this.settings = settings;
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        return DegradedAnswer.ask(module, () -> settings.settings(at), List.of());
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return act(() -> settings.change(at, key, value, permissions));
    }

    @Override public List<MachineSetting> settingsOnStructure(String structureId, BlockPos at) {
        return DegradedAnswer.ask(module, () -> settings.settingsOnStructure(structureId, at), List.of());
    }

    @Override public Optional<Action> changeOnStructure(String structureId, BlockPos at, String key, String value,
            Permissions permissions, BringsPlayerClose reach) {
        return act(() -> settings.changeOnStructure(structureId, at, key, value, permissions, reach));
    }

    @Override public List<MachineSetting> partSettings(BlockPos host, Direction side) {
        return DegradedAnswer.ask(module, () -> settings.partSettings(host, side), List.of());
    }

    @Override public Optional<String> describeChange(String key, String before, String after) {
        return DegradedAnswer.ask(module, () -> settings.describeChange(key, before, after), Optional.empty());
    }


    // 要一个动作：给出的动作包一层，推进到一半停用时如实按不支持收场。
    private Optional<Action> act(Supplier<Optional<Action>> make) {
        return DegradedAnswer.ask(module, make, Optional.<Action>empty()).map(action -> CompatAction.of(module, action));
    }
}
