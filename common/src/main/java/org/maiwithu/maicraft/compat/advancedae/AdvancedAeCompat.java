// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 高级 AE（AdvancedAE）客户端联动入口：向联动登记表贡献反应仓界面语义布局证明、
 * 反应仓机器类型、服务端设备只读操作声明 {@code advanced_ae.device}（版本 1）与中文知识页。
 * 每次读写模组类均经联动入口包一层，版本不符抛 LinkageError 时自动停用而不拖崩游戏。
 */
public final class AdvancedAeCompat extends CompatModule {

    /** 模组注册 ID。 */
    public static final String MOD_ID = "advanced_ae";
    /** 服务端设备权威只读操作名。 */
    public static final String DEVICE_OPERATION = AdvancedAeDeviceReadings.DEVICE_OPERATION;
    /** 服务端设备权威只读操作版本。 */
    public static final int DEVICE_OPERATION_VERSION = AdvancedAeDeviceReadings.DEVICE_OPERATION_VERSION;

    private final AdvancedAeReads reads;

    public AdvancedAeCompat(AdvancedAeReads reads) {
        super(MOD_ID, "高级 AE");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 反应仓界面布局证明：要求 36 格角色背包，容器侧按语义暴露 9 输入 + 1 输出 + 4 升级槽，排除 TOOLBOX 与 CONFIG。
        registry.menuLayout(this, new AdvancedAeSemanticsLayout(this));
        // 反应仓机器类型：负责 machine_inspect / machine_connect / machine_run / machine_configure。
        for (AdvancedAeMachineCatalog.Kind kind : AdvancedAeMachineCatalog.kinds()) {
            registry.machineType(this, services -> new AdvancedAeMachineType(kind, this, services,
                    blocksOf(kind.blocks())));
        }
        // 声明服务端只读操作 advanced_ae.device（版本 1），用于不开窗读取反应仓进度、电量、配置与允许输出面。
        registry.serverRead(this, DEVICE_OPERATION, DEVICE_OPERATION_VERSION);
        // 挂载高级 AE 反应仓中文知识页。
        registry.knowledgeSource(this, new AdvancedAeKnowledge());
    }

    /** 读取指定格的反应仓客户端快照。 */
    public Optional<AdvancedAeReads.ChamberView> chamberAt(ClientLevel level, BlockPos at) {
        return call("读反应仓方块状态", () -> reads.chamberAt(level, at));
    }

    /** 按 AE2 槽位语义查询当前打开的高级 AE 菜单槽位编号。 */
    public List<Integer> menuSlotsOf(String semanticId) {
        return call("按语义查高级 AE 界面槽位", () -> reads.menuSlotsOf(semanticId));
    }

    /** 读取当前打开的反应仓主菜单同步字段。 */
    public Optional<AdvancedAeReads.ChamberMenuView> openChamberMenu() {
        return call("读反应仓界面字段", reads::openChamberMenu);
    }

    /** 读取当前打开的输出方向子菜单已启用的相对面集合。 */
    public Optional<Set<String>> openOutputDirections() {
        return call("读输出方向子菜单状态", reads::openOutputDirections);
    }

    /** 在反应仓界面发送自动输出开关切换包。 */
    public boolean sendAutoExportToggle() {
        return call("切换反应仓自动输出", reads::sendAutoExportToggle);
    }

    /** 在反应仓界面发送清空输入流体槽动作。 */
    public boolean sendClearFluidIn() {
        return call("清空反应仓输入流体槽", reads::sendClearFluidIn);
    }

    /** 在反应仓界面发送清空输出流体槽动作。 */
    public boolean sendClearFluidOut() {
        return call("清空反应仓输出流体槽", reads::sendClearFluidOut);
    }

    /** 在反应仓界面请求打开输出方向子菜单。 */
    public boolean sendOpenOutputDirection() {
        return call("打开反应仓输出方向子菜单", reads::sendOpenOutputDirection);
    }

    /** 在输出方向子菜单中切换指定相对面的允许输出状态。 */
    public boolean sendToggleOutputSide(String relativeSide) {
        return call("切换反应仓输出方向", () -> reads.sendToggleOutputSide(relativeSide));
    }

    /** 在输出方向子菜单中返回上级反应仓主菜单。 */
    public boolean sendReturnToParentMenu() {
        return call("返回反应仓主界面", reads::sendReturnToParentMenu);
    }

    /** 将方块注册 ID 列表解析为方块对象集合；未注册方块跳过。 */
    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }
}
