// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 网络存储接缝：把背包里的东西存进物流网络（AE2 的 ME 网络），像玩家一样拿出身上的无线终端打开，
 * 或走到墙上装着的终端前点开，把那几堆快速移动进去、关上——玩家整理背包就是往 AE 里放，不往地上扔。
 * 存东西能力（into=network）与腾地方共用这一份。
 *
 * <p>实现由联动模组提供，启动时按模组是否安装登记；没有实现方、身上没带也附近没有终端时，腾地方跳过这一步。
 */
public interface NetworkStorage {

    /** 照 search 的挑法有没有终端可用（身上绑好的无线终端，或附近能用的装着的终端）；腾地方排计划时问，不动手。 */
    boolean reachable(TerminalSearch search);

    /**
     * 把主背包里的几堆存进网络。照 search 挑一台终端：在身边时身上绑好访问点的无线终端优先（不用走路），
     * 没带才找脚边装着的；点了地方就只找那里装着的。装着的只用自己或自家人的，上次看没连上的排后面，其余近的先。
     *
     * <p>存的动作：终端只开一次，一样样存完再关（像玩家开着终端一格格点进去，不每样开关一次）；
     * 存进了至少一件算做完，终端打不开、没连上、样样都放不下按问题失败。某一样放不下记一笔试过，接着存下一样。
     * 确认存进去的（按背包少了几件记成存进了网络）与没能确认的交互由动作记进 records。
     *
     * @param purpose 为什么存，写进变化备注，例如"腾地方（捡起掉落的铁锭）"
     * @return 挑到了给存的动作；一台能用的都没有给 {@link TerminalPick.NotFound}，写明身上和附近各是什么情况
     */
    TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search, TaskRecords records);

    /**
     * 去哪里找终端。
     *
     * @param near        在哪一格附近找装着的终端；为 null 时就在角色身边，先看身上绑好的无线终端，没带再找脚边的
     * @param radius      找装着的终端找多远（格）
     * @param permissions 这次任务的许可：走过去能动多少地形、哪些地点受保护按它来
     */
    record TerminalSearch(BlockPos near, int radius, Permissions permissions) {
        public TerminalSearch {
            Objects.requireNonNull(permissions, "permissions");
            if (radius < 0) throw new IllegalArgumentException("找终端的范围不能为负：" + radius);
            near = near == null ? null : near.immutable();
        }

        /** 就在身边：身上的无线终端优先，没带再找脚边 radius 格内装着的终端。 */
        public static TerminalSearch nearMe(int radius, Permissions permissions) {
            return new TerminalSearch(null, radius, permissions);
        }

        /** 去 near 那一格附近 radius 格内装着的终端；点了地方就走过去，不用身上的无线终端。 */
        public static TerminalSearch around(BlockPos near, int radius, Permissions permissions) {
            return new TerminalSearch(Objects.requireNonNull(near, "near"), radius, permissions);
        }

        /** 是不是就在身边找（身上的无线终端优先）。 */
        public boolean nearMeFirst() {
            return near == null;
        }
    }

    /** 挑终端的结论：挑到了给存的动作，一台能用的都没有写明原因。同挑容器的结论。 */
    sealed interface TerminalPick {

        /** 挑到了：存的动作，describe 里写着是哪一台。 */
        record Chosen(Action storing) implements TerminalPick {
            public Chosen {
                Objects.requireNonNull(storing, "storing");
            }
        }

        /** 身上没带、附近也没有能用的终端；reason 写明是没有，还是附近的都是别人的。 */
        record NotFound(String reason) implements TerminalPick {
            public NotFound {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
