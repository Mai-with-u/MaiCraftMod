// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;

/**
 * 单个 ExtendedAE 设备的专属行为委托接口：用于按方块注册 ID 把水晶组装器、电路切片器、
 * 扩展压印器、扩展充能器、水晶修复器与各扩展网络设备的连网面、状态读数、投料和配置拆开维护。
 */
interface ExtendedAeDeviceOps {

    /** ME 网络种类标识，与 AE2 网络使用同一 ID，使 machine_connect 能自动识别并连线。 */
    NetworkKind ME_NETWORK = new NetworkKind("me", "应用能源2 的 ME 网络");

    /** 专属连网与物料交换面；返回空时由主类型按是否接入 ME 网络给默认六面或单机口。 */
    default Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        return Optional.empty();
    }

    /** 专属运行状态读数；返回空时由主类型回退通用网格态读数。 */
    default Optional<MachineState> state(BlockPos at, ClientLevel level) {
        return Optional.empty();
    }

    /** 站在外面读得到的出口产物。 */
    default List<MachineState.Shown> output(BlockPos at, ClientLevel level) {
        return List.of();
    }

    /** 出口是否在客户端不打开界面时直接可见；为假时 machine_run 采用开窗回头看模式。 */
    default boolean outputVisible(BlockPos at) {
        return true;
    }

    /** 投料与取货本事；不接投料的设备返回空。 */
    default Optional<MachineFeeding> feeding(BlockPos at, PlayerServices services) {
        return Optional.empty();
    }

    /** 设置项读写与配置本事；无配置项的设备返回空。 */
    default Optional<MachineSettings> settings(BlockPos at, PlayerServices services) {
        return Optional.empty();
    }
}
