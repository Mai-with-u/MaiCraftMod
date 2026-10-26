// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.flightcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ace.flight.block.AltitudeHoldBlockEntity;
import ace.flight.block.AttitudeHoldBlockEntity;
import ace.flight.block.FlightControlRedstoneProxyBlock;
import ace.flight.block.FlightControlRedstoneProxyBlockEntity;
import ace.flight.block.VelocityCouplerBlockEntity;
import ace.flight.control.AttitudeHoldMode;
import ace.flight.network.SetAltitudeHoldGainsPayload;
import ace.flight.network.SetAttitudeHoldConfigPayload;
import ace.flight.network.SetVelocityCouplerConfigPayload;
import ace.flight.redstone.FaceAssignment;
import ace.flight.redstone.ProxyFace;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.createmod.catnip.data.Couple;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.compat.flightcontrol.StabilizerParts;

/**
 * Flight Control 稳定器的读写端：物理结构的地块里挑出高度稳定器、智能姿态稳定器、线速度耦合器、飞控红石代理，
 * 读服务器同步下来的开关、动力、出力方式、模式、目标与当前高度、最大目标速度；代理每个接了动作的面，
 * 看面旁边那个无线收端听哪对频率。改设置发它们界面保存时发的包，P、D 这些照原样带上。只翻译，不判断。
 */
public final class FlightControlPartReads implements StabilizerParts {

    /** 高度稳定器的三种模式在它自己那里是 0、1、2。 */
    private static final int MODE_HOLD_SET = 1;

    @Override public Optional<FlightStabilizers.Setup> read(ClientLevel level, String structureId) {
        SubLevel subLevel = subLevel(level, structureId);
        if (subLevel == null) return Optional.empty();
        LevelPlot plot = subLevel.getPlot();
        BoundingBox3ic bounds = plot.getBoundingBox();
        List<FlightStabilizers.Stabilizer> stabilizers = new ArrayList<>();
        List<FlightStabilizers.ProxyInput> inputs = new ArrayList<>();
        boolean complete = true;
        // 地块按区块一块块看：已经拿到的读里面的方块实体，没拿到的不为读去加载，记成没读全。
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                LevelChunk chunk = chunk(plot, new ChunkPos(chunkX, chunkZ));
                if (chunk == null) {
                    complete = false;
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    stabilizer(entity).ifPresent(stabilizers::add);
                    if (entity instanceof FlightControlRedstoneProxyBlockEntity proxy) inputs.addAll(inputs(plot, proxy));
                }
            }
        }
        return Optional.of(new FlightStabilizers.Setup(stabilizers, inputs, complete,
                complete ? "" : "结构有区块没拿到，稳定器按读到的部分"));
    }

    // 一个稳定器的样子：开没开看它自己同步下来的结论，动力看轴转不转、过没过载，出力看接没接飞控计算机、开没开直接施力。
    private static Optional<FlightStabilizers.Stabilizer> stabilizer(BlockEntity entity) {
        BlockPos at = entity.getBlockPos().immutable();
        if (entity instanceof AltitudeHoldBlockEntity hold) {
            String mode = switch (hold.altitudeMode) {
                case 1 -> "hold_set";
                case 2 -> "minimum";
                default -> "hold_current";
            };
            double target = hold.altitudeMode == 0 ? hold.capturedAltitude : hold.hasSetAltitude ? hold.presetAltitude : Double.NaN;
            return Optional.of(new FlightStabilizers.Stabilizer(FlightStabilizers.ALTITUDE_HOLD, at, hold.lastActive,
                    powered(hold.getSpeed(), hold.isOverStressed()),
                    output(hold.getLinkedComputerPos() != null, hold.applyForceDirectly), mode, target,
                    hold.hasDisplayedAltitude ? hold.displayedAltitude : Double.NaN, 0, 0, 0));
        }
        if (entity instanceof AttitudeHoldBlockEntity attitude) {
            String mode = attitude.mode == AttitudeHoldMode.LEVEL ? "level_heading"
                    : attitude.mode == AttitudeHoldMode.HOLD_CURRENT ? "hold_attitude" : "level";
            return Optional.of(new FlightStabilizers.Stabilizer(FlightStabilizers.ATTITUDE_HOLD, at, attitude.lastActive,
                    powered(attitude.getSpeed(), attitude.isOverStressed()),
                    output(attitude.getLinkedComputerPos() != null, attitude.applyTorqueDirectly), mode, Double.NaN,
                    Double.NaN, 0, 0, 0));
        }
        if (entity instanceof VelocityCouplerBlockEntity coupler) {
            return Optional.of(new FlightStabilizers.Stabilizer(FlightStabilizers.VELOCITY_COUPLER, at, coupler.lastActive,
                    powered(coupler.getSpeed(), coupler.isOverStressed()),
                    output(coupler.getLinkedComputerPos() != null, coupler.applyForceDirectly), "", Double.NaN, Double.NaN,
                    coupler.maxVx, coupler.maxVy, coupler.maxVz));
        }
        return Optional.empty();
    }

    // 代理每个接了动作的面：转给哪个稳定器的哪个动作，面旁边那一格是无线收端就读它听的频率。
    private static List<FlightStabilizers.ProxyInput> inputs(LevelPlot plot, FlightControlRedstoneProxyBlockEntity proxy) {
        List<FlightStabilizers.ProxyInput> found = new ArrayList<>();
        BlockPos at = proxy.getBlockPos();
        Direction facing = proxy.getBlockState().getValue(FlightControlRedstoneProxyBlock.FACING);
        for (ProxyFace face : ProxyFace.values()) {
            FaceAssignment assignment = proxy.getFaceAssignment(face);
            if (assignment == null || !assignment.hasAssignment()) continue;
            BlockPos target = at.offset(assignment.targetOffset()).immutable();
            found.add(new FlightStabilizers.ProxyInput(frequency(plot, at.relative(face.toWorldDirection(facing))),
                    device(plot, target), target, assignment.actionId()));
        }
        return found;
    }

    private static String device(LevelPlot plot, BlockPos at) {
        BlockEntity entity = blockEntity(plot, at);
        if (entity instanceof AltitudeHoldBlockEntity) return FlightStabilizers.ALTITUDE_HOLD;
        if (entity instanceof AttitudeHoldBlockEntity) return FlightStabilizers.ATTITUDE_HOLD;
        if (entity instanceof VelocityCouplerBlockEntity) return FlightStabilizers.VELOCITY_COUPLER;
        LevelChunk chunk = chunk(plot, new ChunkPos(at));
        return chunk == null ? "unknown" : BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(at).getBlock()).toString();
    }

    // 面旁边的无线收端听的频率：机械动力的链路切到发送的不算；不是无线收端为空串。
    private static String frequency(LevelPlot plot, BlockPos at) {
        BlockEntity entity = blockEntity(plot, at);
        LinkBehaviour link = entity == null ? null : BlockEntityBehaviour.get(entity, LinkBehaviour.TYPE);
        if (link == null) return "";
        BlockState state = entity.getBlockState();
        if (state.hasProperty(RedstoneLinkBlock.RECEIVER) && !state.getValue(RedstoneLinkBlock.RECEIVER)) return "";
        Couple<RedstoneLinkNetworkHandler.Frequency> key = link.getNetworkKey();
        return id(key.getFirst()) + " + " + id(key.getSecond());
    }

    private static String id(RedstoneLinkNetworkHandler.Frequency frequency) {
        return BuiltInRegistries.ITEM.getKey(frequency.getStack().getItem()).toString();
    }

    private static boolean powered(float speed, boolean overStressed) {
        return Math.abs(speed) > 0 && !overStressed;
    }

    private static String output(boolean linked, boolean direct) {
        return linked ? FlightStabilizers.OUTPUT_COMPUTER : direct ? FlightStabilizers.OUTPUT_DIRECT : FlightStabilizers.OUTPUT_NONE;
    }

    // 设定高度：模式切到保持设定高度，默认开关拨到让它开着的那一边（红石总开关高着时反过来），P、D 照原样。
    @Override public boolean holdAltitude(ClientLevel level, String structureId, BlockPos local, double y) {
        if (!(blockEntity(level, structureId, local) instanceof AltitudeHoldBlockEntity hold)) return false;
        PacketDistributor.sendToServer(new SetAltitudeHoldGainsPayload(local, hold.kp, hold.kd,
                !hold.masterSwitchSignalHigh, MODE_HOLD_SET, true, y));
        return true;
    }

    // 打开：默认开关拨到让它开着的那一边，别的设置照原样带上。
    @Override public boolean switchOn(ClientLevel level, String structureId, BlockPos local) {
        BlockEntity entity = blockEntity(level, structureId, local);
        if (entity instanceof AttitudeHoldBlockEntity attitude) {
            PacketDistributor.sendToServer(new SetAttitudeHoldConfigPayload(local, attitude.kp, attitude.kd,
                    attitude.mode.controlIndex(), attitude.bodyForwardFacing.controlIndex(),
                    attitude.allowRedstoneModeSwitching, !attitude.masterSwitchSignalHigh));
            return true;
        }
        if (entity instanceof VelocityCouplerBlockEntity coupler) {
            PacketDistributor.sendToServer(new SetVelocityCouplerConfigPayload(local, !coupler.masterSwitchSignalHigh,
                    coupler.kpX, coupler.kpY, coupler.kpZ, coupler.kdX, coupler.kdY, coupler.kdZ,
                    coupler.maxVx, coupler.maxVy, coupler.maxVz));
            return true;
        }
        return false;
    }

    @Override public boolean setHorizontalSpeed(ClientLevel level, String structureId, BlockPos local, double speed) {
        if (!(blockEntity(level, structureId, local) instanceof VelocityCouplerBlockEntity coupler)) return false;
        PacketDistributor.sendToServer(new SetVelocityCouplerConfigPayload(local, coupler.defaultEnabled,
                coupler.kpX, coupler.kpY, coupler.kpZ, coupler.kdX, coupler.kdY, coupler.kdZ, speed, coupler.maxVy, speed));
        return true;
    }

    private static BlockEntity blockEntity(ClientLevel level, String structureId, BlockPos local) {
        SubLevel subLevel = subLevel(level, structureId);
        return subLevel == null ? null : blockEntity(subLevel.getPlot(), local);
    }

    private static BlockEntity blockEntity(LevelPlot plot, BlockPos at) {
        LevelChunk chunk = chunk(plot, new ChunkPos(at));
        return chunk == null ? null : chunk.getBlockEntity(at);
    }

    // 地块里装着这一块的区块；没拿到为 null。
    private static LevelChunk chunk(LevelPlot plot, ChunkPos at) {
        PlotChunkHolder holder = plot.getChunkHolder(plot.toLocal(at));
        return holder == null ? null : holder.getChunk();
    }

    private static SubLevel subLevel(ClientLevel level, String id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        try {
            SubLevel subLevel = container.getSubLevel(UUID.fromString(id));
            return subLevel == null || subLevel.isRemoved() ? null : subLevel;
        } catch (IllegalArgumentException wrong) {
            return null;
        }
    }
}
