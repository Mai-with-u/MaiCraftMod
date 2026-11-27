// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 高级 AE（AdvancedAE）客户端读写与菜单发包接缝：
 * 读取客户端同步的反应仓方块实体（工作态、供电态、双流体槽与输入输出物品槽）、
 * 按 AE2 槽位语义查询当前打开界面的真实槽位编号、读取反应仓与输出方向子菜单同步字段，
 * 以及发送自动输出切换、清空输入/输出流体槽、打开与切换相对六面输出方向及返回上级界面的操作包。
 * 直接调用模组类的实现在 NeoForge 模块，离线测试注入替身。
 */
public interface AdvancedAeReads {

    /** 反应仓单槽流体容量上限（16 桶 = 16,000 mB）。F 游戏事实。 */
    int TANK_CAPACITY_MB = 16_000;
    /** 反应仓默认单次加工总刻数。F 游戏事实。 */
    int MAX_PROCESSING_STEPS = 200;
    /** 反应仓内部缓冲电量上限（AE）。F 游戏事实。 */
    double MAX_POWER_STORAGE = 500_000.0;
    /** 反应仓升级卡槽位数。F 游戏事实。 */
    int UPGRADE_SLOT_COUNT = 4;

    /** 无 NeoForge 读写端时的空实现：读不出任何状态，也不发包。 */
    AdvancedAeReads NONE = new AdvancedAeReads() {
        @Override public Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at) {
            return Optional.empty();
        }

        @Override public List<Integer> menuSlotsOf(String semanticId) {
            return List.of();
        }

        @Override public Optional<ChamberMenuView> openChamberMenu() {
            return Optional.empty();
        }

        @Override public Optional<Set<String>> openOutputDirections() {
            return Optional.empty();
        }

        @Override public boolean sendAutoExportToggle() {
            return false;
        }

        @Override public boolean sendClearFluidIn() {
            return false;
        }

        @Override public boolean sendClearFluidOut() {
            return false;
        }

        @Override public boolean sendOpenOutputDirection() {
            return false;
        }

        @Override public boolean sendToggleOutputSide(String relativeSide) {
            return false;
        }

        @Override public boolean sendReturnToParentMenu() {
            return false;
        }
    };

    /**
     * 单个流体储罐槽的快照。
     *
     * @param fluidId    流体注册 ID；空槽为空串
     * @param amountMb   当前存量（mB）
     * @param capacityMb 容量上限（mB）
     */
    record TankSlot(String fluidId, long amountMb, long capacityMb) {
        public static final TankSlot EMPTY = new TankSlot("", 0L, TANK_CAPACITY_MB);

        public TankSlot {
            fluidId = fluidId == null ? "" : fluidId;
            amountMb = Math.max(0L, amountMb);
            capacityMb = capacityMb > 0L ? capacityMb : TANK_CAPACITY_MB;
        }

        /** 槽位是否为空（无流体或存量为 0）。 */
        public boolean isEmpty() {
            return fluidId.isEmpty() || amountMb <= 0L;
        }
    }

    /**
     * 客户端直接从反应仓方块实体读到的快照（实体同步流自带工作态、双流体槽与物品槽；
     * 供电与在线状态仅在客户端明确有据时非空，未知时为 {@code null} 以免假值覆盖服务端权威读数）。
     *
     * @param working     是否正在加工
     * @param powered     是否通电；客户端无法判定时为 {@code null}
     * @param active      是否已接入 ME 网络并在线；客户端无法判定时为 {@code null}
     * @param front       方块朝向（用于参考）
     * @param inputFluid  输入流体槽（槽 1）
     * @param outputFluid 输出流体槽（槽 0）
     * @param inputs      输入物品槽非空快照（9 格）
     * @param outputs     输出物品槽非空快照（1 格）
     */
    record ChamberView(boolean working, Boolean powered, Boolean active, Direction front,
                       TankSlot inputFluid, TankSlot outputFluid,
                       List<MachineState.Shown> inputs, List<MachineState.Shown> outputs) {
        public ChamberView {
            inputFluid = inputFluid == null ? TankSlot.EMPTY : inputFluid;
            outputFluid = outputFluid == null ? TankSlot.EMPTY : outputFluid;
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        }
    }

    /**
     * 打开反应仓主菜单时同步到的界面字段快照。
     *
     * @param autoExport        自动输出开关（yes / no）
     * @param processingTime    当前加工进度刻数
     * @param maxProcessingTime 单次加工总刻数
     * @param showWarning       是否触发缺电或输出受阻告警
     * @param inputFluid        输入流体槽（槽 1）
     * @param outputFluid       输出流体槽（槽 0）
     * @param upgrades          4 个升级槽物品注册 ID（空槽为 empty）
     */
    record ChamberMenuView(String autoExport, int processingTime, int maxProcessingTime, boolean showWarning,
                           TankSlot inputFluid, TankSlot outputFluid, List<String> upgrades) {
        public ChamberMenuView {
            autoExport = autoExport == null ? "no" : autoExport;
            inputFluid = inputFluid == null ? TankSlot.EMPTY : inputFluid;
            outputFluid = outputFluid == null ? TankSlot.EMPTY : outputFluid;
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        }
    }

    /** 读取指定格的反应仓客户端方块实体快照；未加载或不是反应仓返回空。 */
    Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at);

    /** 按 AE2 槽位语义名读取当前打开的高级 AE 菜单槽位编号列表（如 MACHINE_INPUT / MACHINE_OUTPUT / UPGRADE / TOOLBOX / CONFIG）。 */
    List<Integer> menuSlotsOf(String semanticId);

    /** 当前打开的菜单若为反应仓主界面，返回同步到的配置、进度、告警与双流体槽快照。 */
    Optional<ChamberMenuView> openChamberMenu();

    /**
     * 当前打开的界面若为输出方向子菜单且已完成服务端同步，返回当前启用的相对面名集合
     * （小写 front / back / left / right / top / bottom）；未打开或尚未同步完返回空。
     */
    Optional<Set<String>> openOutputDirections();

    /** 在反应仓界面发送切换自动输出开关（Settings.AUTO_EXPORT）的配置按钮包。 */
    boolean sendAutoExportToggle();

    /** 在反应仓界面触发清空输入流体槽（槽 1，调用 clearFluid / flushFluid）。 */
    boolean sendClearFluidIn();

    /** 在反应仓界面触发清空输出流体槽（槽 0，调用 clearFluidOut / flushFluidOut）。 */
    boolean sendClearFluidOut();

    /** 在反应仓界面触发打开输出方向子菜单（调用 configureOutput）。 */
    boolean sendOpenOutputDirection();

    /** 在输出方向子菜单中切换指定相对面的输出开关（调用 updateSideStatus）。 */
    boolean sendToggleOutputSide(String relativeSide);

    /** 在输出方向子菜单中返回上级反应仓主菜单。 */
    boolean sendReturnToParentMenu();
}
