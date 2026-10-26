// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.mekanism;

import java.util.Locale;
import java.util.Optional;

import mekanism.api.chemical.ChemicalStack;
import mekanism.common.content.network.ChemicalNetwork;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.FluidNetwork;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.DynamicNetwork;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

import org.maiwithu.maicraft.server.compat.mekanism.MekanismNetworkReads;

/**
 * 在服务端读通用机械管网的读写端：这一格的管道方块实体拿到它所在的那张网（同一种介质连着的管道共用一个网络对象），
 * 读几段管道、连着几处机器；能量网、流体网、化学品网读网里的缓冲（存量）与上限，流体、化学品再读上一刻送出去多少。
 * 只翻译，不判断。
 */
public final class MekanismNetworkServerReads implements MekanismNetworkReads {

    @Override public Optional<PipeNetwork> network(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof TileEntityTransmitter tile)) return Optional.empty();
        Transmitter<?, ?, ?> transmitter = tile.getTransmitter();
        // 刚放下、还没并进网的管道没有网络对象：当成"不在任何网上"，过几刻再问就有了。
        if (!transmitter.hasTransmitterNetwork()) return Optional.empty();
        DynamicNetwork<?, ?, ?> network = transmitter.getTransmitterNetwork();
        return Optional.of(new PipeNetwork(network.getUUID().toString(), mediumOf(transmitter),
                network.transmittersSize(), network.getAcceptorCount(), stockOf(network)));
    }

    // 一段管道只传一种介质，名字和客户端读管道时用的一样（energy、fluid、chemical、item、heat）。
    private static String mediumOf(Transmitter<?, ?, ?> transmitter) {
        for (TransmissionType type : transmitter.getSupportedTransmissionTypes()) {
            return type.name().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // 存量在网络对象的缓冲里：能量网按焦耳存，换成 FE（通用机械界面默认的显示单位），和打开能量立方看到的同一口径；
    // 物品网（东西在管道里边走边送）、导热网不在网里存东西，给空。
    private static Optional<Stock> stockOf(DynamicNetwork<?, ?, ?> network) {
        if (network instanceof EnergyNetwork energy) {
            return Optional.of(new Stock("", EnergyUnit.FORGE_ENERGY.convertTo(energy.energyContainer.getEnergy()),
                    EnergyUnit.FORGE_ENERGY.convertTo(energy.energyContainer.getMaxEnergy()), -1, "FE"));
        }
        if (network instanceof FluidNetwork fluid) {
            FluidStack held = fluid.fluidTank.getFluid();
            String id = held.isEmpty() ? "" : BuiltInRegistries.FLUID.getKey(held.getFluid()).toString();
            return Optional.of(new Stock(id, held.getAmount(), fluid.fluidTank.getCapacity(),
                    fluid.getPrevTransferAmount(), "mB"));
        }
        if (network instanceof ChemicalNetwork chemical) {
            ChemicalStack held = chemical.chemicalTank.getStack();
            String id = held.isEmpty() ? "" : held.getChemicalHolder().getRegisteredName();
            return Optional.of(new Stock(id, held.getAmount(), chemical.chemicalTank.getCapacity(),
                    chemical.getPrevTransferAmount(), "mB"));
        }
        return Optional.empty();
    }
}
