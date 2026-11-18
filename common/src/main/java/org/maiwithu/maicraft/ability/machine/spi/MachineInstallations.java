// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器装、拆安装段的本事：一段原生安装段（传送带、强力胶框、ME 线缆）形状合不合规则、怎么装、
 * 装成没有、拆整台时哪些格跟着拆，以及组装会动的结构时还缺哪几段。不管安装段的机器不实现这条。
 */
public interface MachineInstallations {

    /**
     * 这段原生安装段的形状合不合规则（直线、长度不超模组配置、两端是不是该有的方块）；合规则给空，
     * 不合给一句说明。只用来审阅与计划阶段报错，不拦施工：形状不对的段照样可以试着装，结果如实报。
     */
    Optional<String> installationProblem(Installation installation);

    /** 用模组自己的方式装这一段（两端的轴已就位后用传送带连上）；这种机器不管这种安装段时给空。 */
    Optional<Action> install(Installation installation, Permissions permissions);

    /**
     * 在组装好的物理结构上装这一段：格是结构的地块坐标，走过去用 {@code reach}（它会上船或站到船边够得着的地方）。
     * 只发一包、人站得够近就行的安装段（胶）装得了；要瞄准点击的（传送带、链条、机械臂）在结构上还不会，给空。
     */
    default Optional<Action> installOnStructure(Installation installation, Permissions permissions,
            BringsPlayerClose reach) {
        return Optional.empty();
    }

    /** 这一段现在是不是按要求装成了；按最终结构核对，给整机比对用。 */
    boolean installed(Installation installation);

    /**
     * 拆整台时这段安装段里要跟着拆的格：这一段自己放下的方块（机械臂、一格格接上的 ME 线缆）。
     * 两头本来就在的方块不算：链条输送机、传送带两头的轴写在蓝图 cells 里随整台拆；机械臂的取放点、
     * 链条连到的另一台可能是别的机器或主城原有的东西，拆这一台不能拆到它们。
     * 不认这种安装段时给空，认但这一段没放方块时给空表。
     */
    default Optional<List<BlockPos>> placedBy(Installation installation) {
        return Optional.empty();
    }

    /**
     * 要让这一片方块跟着一起动（组装成会动的结构），还缺哪些安装段：Create 是还没被强力胶框住的地方各补一框。
     * 只算不动手，装交给 {@link #install}；不管这件事的机器类型给空，管但已经都粘好时给空表。
     * 片外的方块不往里框，免得垫的、地面跟着一起走。
     *
     * @param cells 要一起动的方块，世界坐标；此刻那一格是空气或流体的不算
     */
    default Optional<List<Installation>> stickTogether(Set<BlockPos> cells) {
        return Optional.empty();
    }

    /**
     * 同一片有几家都能粘时谁先：数大的先问，先答上的那家来粘。默认 0；
     * 只粘会动结构、不粘会转的机械装置的胶（Simulated 的蜂蜜胶）比强力胶高。
     */
    default int stickPreference() {
        return 0;
    }
}
