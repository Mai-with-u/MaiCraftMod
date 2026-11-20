// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 工作方块与自动合成设备的会话级界面读数缓存：AE2 大部分机器配置项（阻挡、锁合成、分离面、自动输出、
 * 聚合器模式、升级卡、CPU 调度等）不随方块实体流同步给客户端；打开界面或改完配置关窗前记进本缓存，
 * 让查看机器与配置完成后的即时读回校验都能拿到真实最新值。
 */
final class Ae2MachineCache {

    /** 样板供应器（方块或线缆部件）的缓存读数。 */
    record ProviderCache(String blockingMode, String lockCraftingMode, String showInAccessTerminal,
            Integer priority, String craftingLockedReason, String unlockStack, List<String> patterns) {}

    /** 合成 CPU 的缓存读数。 */
    record CpuCache(String schedulingMode, boolean cantStoreItems, String lastJobAction,
            List<Ae2MachineReads.CpuEntryView> cpus) {}

    /** 样板编码终端的缓存读数：lastRequest 存最近一次完整编码请求原文供读回核对，lastOutput 存产出物 ID。 */
    record EncodingCache(String mode, boolean substitute, boolean substituteFluids,
            String stonecuttingRecipeId, String lastRequest, String lastOutput) {}

    /** 样板管理终端的缓存读数。 */
    record AccessCache(String showProviders, String lastPutPattern) {}

    /** 定位键：方块本身 side 为 null，线缆部件带所在面。 */
    record TargetKey(BlockPos pos, Direction side) {
        TargetKey(BlockPos pos, Direction side) {
            this.pos = pos.immutable();
            this.side = side;
        }
    }

    private final Map<BlockPos, Ae2MachineReads.VibrationMenuView> vibrations = new HashMap<>();
    private final Map<BlockPos, Ae2MachineReads.CondenserMenuView> condensers = new HashMap<>();
    private final Map<TargetKey, ProviderCache> providers = new HashMap<>();
    private final Map<BlockPos, Ae2MachineReads.AssemblerMenuView> assemblers = new HashMap<>();
    private final Map<BlockPos, Ae2MachineReads.InscriberMenuView> inscribers = new HashMap<>();
    private final Map<BlockPos, CpuCache> cpus = new HashMap<>();
    private final Map<TargetKey, EncodingCache> encodings = new HashMap<>();
    private final Map<TargetKey, AccessCache> accesses = new HashMap<>();

    void putVibration(BlockPos pos, Ae2MachineReads.VibrationMenuView view) {
        vibrations.put(pos.immutable(), view);
    }

    Optional<Ae2MachineReads.VibrationMenuView> vibration(BlockPos pos) {
        return Optional.ofNullable(vibrations.get(pos.immutable()));
    }

    void putCondenser(BlockPos pos, Ae2MachineReads.CondenserMenuView view) {
        condensers.put(pos.immutable(), view);
    }

    Optional<Ae2MachineReads.CondenserMenuView> condenser(BlockPos pos) {
        return Optional.ofNullable(condensers.get(pos.immutable()));
    }

    void putProvider(BlockPos pos, Direction side, ProviderCache view) {
        providers.put(new TargetKey(pos, side), view);
    }

    Optional<ProviderCache> provider(BlockPos pos, Direction side) {
        return Optional.ofNullable(providers.get(new TargetKey(pos, side)));
    }

    void putAssembler(BlockPos pos, Ae2MachineReads.AssemblerMenuView view) {
        assemblers.put(pos.immutable(), view);
    }

    Optional<Ae2MachineReads.AssemblerMenuView> assembler(BlockPos pos) {
        return Optional.ofNullable(assemblers.get(pos.immutable()));
    }

    void putInscriber(BlockPos pos, Ae2MachineReads.InscriberMenuView view) {
        inscribers.put(pos.immutable(), view);
    }

    Optional<Ae2MachineReads.InscriberMenuView> inscriber(BlockPos pos) {
        return Optional.ofNullable(inscribers.get(pos.immutable()));
    }

    void putCpu(BlockPos pos, CpuCache view) {
        cpus.put(pos.immutable(), view);
    }

    Optional<CpuCache> cpu(BlockPos pos) {
        return Optional.ofNullable(cpus.get(pos.immutable()));
    }

    void putEncoding(BlockPos pos, Direction side, EncodingCache view) {
        encodings.put(new TargetKey(pos, side), view);
    }

    Optional<EncodingCache> encoding(BlockPos pos, Direction side) {
        return Optional.ofNullable(encodings.get(new TargetKey(pos, side)));
    }

    void putAccess(BlockPos pos, Direction side, AccessCache view) {
        accesses.put(new TargetKey(pos, side), view);
    }

    Optional<AccessCache> access(BlockPos pos, Direction side) {
        return Optional.ofNullable(accesses.get(new TargetKey(pos, side)));
    }
}
