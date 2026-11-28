// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineType;

/**
 * AE2 各类设备的轻量化操作委托接缝：按设备种类编号（例如 ae2:charger、ae2:cable_bus、ae2:vibration_chamber）
 * 路由进 {@link Ae2MachineType}，让各子包独立提供本类设备的读数、投料、设置、安装与按面部件配置，
 * 避免把所有 AE2 设备分支都堆进同一个机器类型类里。
 */
public interface Ae2DeviceOps {

    /**
     * 这种设备的连接口与换物面覆盖（如充能器非正面五面、水晶催生器前后两面、量子环非四角四面）；
     * 返回空时走通用六面联网或单机默认规则。
     */
    default Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        return Optional.empty();
    }

    /**
     * 这一格设备的完整客户端状态覆盖；返回空时由通用 ME 网格读数组装基础状态，再并入
     * {@link #extraReadings} 与 {@link #extraShown}。
     */
    default Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        return Optional.empty();
    }

    /** 在通用 ME 网格读数之外补充的键值对读数（值只用 Long、Double、Boolean、String）。 */
    default Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        return Map.of();
    }

    /** 在通用读数之外补充展示的槽内或表面物品；没有时为空列表。 */
    default List<MachineState.Shown> extraShown(BlockPos pos, ClientLevel level) {
        return List.of();
    }

    /** 这种设备的投料、燃料与整段运行本事（振动室燃料、充能器整段充能等）；没有时为空。 */
    default Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.empty();
    }

    /** 这种设备的设置项本事（线缆部件按面配置、压印器设置、物质聚合器模式等）；没有时为空。 */
    default Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.empty();
    }

    /** 这种设备的安装段本事；没有时为空。 */
    default Optional<MachineInstallations> installations(PlayerServices services) {
        return Optional.empty();
    }

    /** 宿主格某一面部件的设置项与现值（供查看机器在部件设备上直接透出）；没有时为空列表。 */
    default List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
        return List.of();
    }

    /** 这种设备站在外面能看到的出口展示物；没有时为空列表。 */
    default List<MachineState.Shown> output(BlockPos pos, ClientLevel level) {
        return List.of();
    }
}
