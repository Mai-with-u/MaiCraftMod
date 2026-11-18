// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.ability.machine.spi.MachineParts;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkLoad;

/**
 * 联动模组交来的机器类型，登记表给它包的一层：模组停用后不再认领方块（机器能力就报"没有认得它的机器类型"），
 * 读状态如实说读不到，动作一律给不出。名字、角色这些登记时就定下的静态信息照常回答。
 *
 * <p>机器类型自己报的几条本事（投料取货、设置项、安装段、部件）也各自包一层；
 * 里面没有的本事照样给空，机器能力看不出来源是不是联动。
 */
public final class CompatMachineType implements MachineType {

    private final CompatModule module;
    private final MachineType type;

    public CompatMachineType(CompatModule module, MachineType type) {
        this.module = Objects.requireNonNull(module, "module");
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override public String id() { return type.id(); }

    @Override public String name() { return type.name(); }

    @Override public String modId() { return type.modId(); }

    @Override public MachineRole role() { return type.role(); }

    @Override public boolean covers(BlockState state) {
        return ask(() -> type.covers(state), false);
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        return ask(() -> type.exchangePoints(state, at), List.of());
    }

    @Override public MachineState state(BlockPos at) {
        if (!module.active()) return MachineState.unknown(disabled());
        try {
            return type.state(at);
        } catch (ModApiMismatch broken) {
            return MachineState.unknown(broken.getMessage());
        }
    }

    @Override public Optional<NetworkLoad> load(BlockState state) {
        return ask(() -> type.load(state), Optional.empty());
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return ask(() -> type.output(at), List.of());
    }

    @Override public boolean outputVisible(BlockPos at) {
        return ask(() -> type.outputVisible(at), true);
    }

    // 模组停用或对不上时不说产物掉在哪：machine_run 照出口读不读得到那一条走，读不到的如实说。
    @Override public Optional<AABB> dropArea(BlockPos at) {
        return ask(() -> type.dropArea(at), Optional.empty());
    }

    // 模组停用后不替它抄：交回空，机器能力照抄方块，抄出来的格照实写着建不回来。
    @Override public Optional<MachineCopy> copy(Map<BlockPos, BlockState> cells) {
        return ask(() -> type.copy(cells), Optional.empty());
    }

    // 本事各包各的：里面没有的本事给空，有的包一层再交出去。
    @Override public Optional<MachineFeeding> feeding() {
        return type.feeding().map(feeding -> new CompatMachineFeeding(module, feeding));
    }

    @Override public Optional<MachineSettings> settings() {
        return type.settings().map(settings -> new CompatMachineSettings(module, settings));
    }

    @Override public Optional<MachineInstallations> installations() {
        return type.installations().map(installations -> new CompatMachineInstallations(module, installations));
    }

    @Override public Optional<MachineParts> parts() {
        return type.parts().map(parts -> new CompatMachineParts(module, parts));
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

    private String disabled() {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
