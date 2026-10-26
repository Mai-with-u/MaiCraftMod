// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.flightcontrol;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;

/**
 * Flight Control 稳定器的读写接缝（客户端）：读物理结构地块里的高度稳定器、智能姿态稳定器、线速度耦合器、
 * 飞控红石代理（服务器同步下来的设置与状态），改设置发它们界面保存时发的包。只翻译，不判断。
 */
public interface StabilizerParts {

    /** 结构地块里已经拿到的区块中的稳定器与代理；结构不在为空。没拿到的区块不为读去加载，记成没读全。 */
    Optional<FlightStabilizers.Setup> read(ClientLevel level, String structureId);

    /**
     * 高度稳定器设成保持设定高度、设定高度 y、开着：和在它的界面里填好保存一样（P、D 照原样带上）。
     * 那一格不是高度稳定器、地块没拿到返回假。
     */
    boolean holdAltitude(ClientLevel level, String structureId, BlockPos local, double y);

    /** 智能姿态稳定器或线速度耦合器打开：默认开关拨到让它开着的那一边，别的设置照原样带上。那一格不是它们返回假。 */
    boolean switchOn(ClientLevel level, String structureId, BlockPos local);

    /** 线速度耦合器沿机体 x、z 的最大目标速度设成 speed，竖轴与别的设置照原样带上。那一格不是耦合器返回假。 */
    boolean setHorizontalSpeed(ClientLevel level, String structureId, BlockPos local, double speed);
}
