// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 一种机器的识别与读数：这格是不是这种机器、在机器里干什么、哪几面和外界交换东西、现在转不转、
 * 出口有什么、在它那种网上吃多少给多少、把现场抄成机器蓝图的写法。
 *
 * <p>动手的本事拆在旁边的四条接口上：投料与取货 {@link MachineFeeding}、设置项 {@link MachineSettings}、
 * 安装段 {@link MachineInstallations}、部件 {@link MachineParts}。机器类型只实现自己真正有的部分，
 * 用下面的自报方法交出来；只会投料取货的简单机器（厨锅）答不了安装段、部件、设置的问题。
 *
 * <p>只用 Minecraft 与 Java 的类型；模组的类只出现在 NeoForge 模块的读写端。实现由联动入口经登记表交来，
 * 进世界时带着联动能用的玩家行为建，返回的动作和自带能力一样走过去、换到主手、点、等游戏确认。
 * 动作只负责"做一次原生操作并如实报告"，做得成做不成、机器能不能转，由机器能力按读回的事实判断。
 */
public interface MachineType {

    /** 这种机器的编号，通常就是主方块的注册 ID，例如 create:mechanical_press。 */
    String id();

    /** 给 LLM 看的名字，例如"动力压机"。 */
    String name();

    /** 来自哪个模组（模组 ID）。 */
    String modId();

    /** 这一格（方块状态）是不是这种机器；认领了才归进机器。 */
    boolean covers(BlockState state);

    /** 这种机器的方块在机器里干什么。 */
    MachineRole role();

    /** 这一格和外界交换东西的面：介质、进还是出，加工处在哪（压机正下方、锯的前方）。 */
    List<ExchangePoint> exchangePoints(BlockState state, BlockPos at);

    /** 这一格此刻的运行状态，只读客户端看得见的；读不到的写进说明，不猜。 */
    MachineState state(BlockPos at);

    /**
     * 这种方块在它那种网上吃多少、给多少（每转应力、频道）：审阅估算整份蓝图够不够用。
     * 只作估计，不拦施工；没有这类读数给空。
     */
    default Optional<NetworkLoad> load(BlockState state) {
        return Optional.empty();
    }

    /** 这台机器出口现在有什么：置物台台面、出口箱、传送带末端；只列看得见的。 */
    List<MachineState.Shown> output(BlockPos at);

    /**
     * 出口站在外面读不读得到：置物台、工作盆上画出来的东西，石磨同步过来的产物格，都读得到；
     * 格子在界面里的（通用机械的机器）读不到。读不到时 machine_run 投完料隔一个加工周期走过去点开界面看一眼，
     * 看到了就收，几回都没出才按部分完成收场；产物掉在地上的机器改给 {@link #dropArea}。
     */
    default boolean outputVisible(BlockPos at) {
        return true;
    }

    /**
     * 做好的东西掉在旁边地上的机器（朝上的锯往前弹、粉碎轮从中间那格往下漏）：产物落在哪一块，世界坐标。
     * 给了它，machine_run 就数这一块地上新多出来的掉落物、走过去捡，不再问 {@link #output} 与取货；
     * 产物不落地的机器给空。
     */
    default Optional<AABB> dropArea(BlockPos at) {
        return Optional.empty();
    }

    /**
     * 读现场时把一台机器里归这种机器认领的格抄成机器蓝图的写法：传送带、链条输送机之间的链、机械臂、
     * ME 线缆与部件照抄方块建不回来，写成安装段与部件；照抄方块与属性就建得回来的给空，由机器能力照抄。
     *
     * @param cells 这台机器里这种机器认领的格与它们此刻的方块状态，世界坐标
     */
    default Optional<MachineCopy> copy(Map<BlockPos, BlockState> cells) {
        return Optional.empty();
    }

    /**
     * 这台机器收料与出货的本事；不收料不出货的机器（管道、线缆格）给空。
     */
    default Optional<MachineFeeding> feeding() {
        return Optional.empty();
    }

    /**
     * 这台机器的设置项读写；没有设置项的机器给空。
     */
    default Optional<MachineSettings> settings() {
        return Optional.empty();
    }

    /**
     * 这台机器装、拆安装段的本事；不管安装段的机器给空。
     */
    default Optional<MachineInstallations> installations() {
        return Optional.empty();
    }

    /**
     * 这台机器装在方块某一面上的部件；没有部件这种东西的机器给空。
     */
    default Optional<MachineParts> parts() {
        return Optional.empty();
    }
}
