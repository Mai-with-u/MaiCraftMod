// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
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
     * 最近五分钟内角色点开终端看到的物流网络存货快照；返回前先按当前已加载区块现场核对固定终端还在不在，
     * 已被拆掉的终端当场从记忆里剔除，未加载区块与跨维度记录保留。
     */
    default List<RememberedNetwork> rememberedNetworks() {
        return List.of();
    }

    /**
     * 角色空手且不潜行点向 cell 那一格时，若该格在客户端已加载且装着网络终端面板，返回要对准的面板包围盒（世界坐标）：
     * 指定了 preferredSide 时匹配装在那一面的终端；未指定且同格有多台终端时取面板中心离角色眼睛最近的一台。
     */
    default Optional<AABB> terminalPanelAt(BlockPos cell, Direction preferredSide) {
        return Optional.empty();
    }

    /**
     * {@code use} 刚点开一份新界面时，检查它是不是网络终端界面（手持无线终端打开的，或 clickedCell 那一格装着的终端打开的）；
     * 是就认领并返回一个等连接状态与网络存货同步、写入存货记忆再关上界面的查看动作，不是就返回空交给普通容器流程。
     *
     * @param clickedCell 本次右键点击的方块格；手持无线终端对空气使用时为 null
     * @param clickedFace 本次指定点击的面；未指定时为 null
     */
    default Optional<TerminalLook> openTerminalLook(BlockPos clickedCell, Direction clickedFace) {
        return Optional.empty();
    }

    /**
     * 点开一台网络终端界面后的查看动作：等连接状态与网络存货同步完、记进网络存货记忆，再关上界面。
     * 动作结束后由 {@link #summary()} 给出真实网络状态与存货摘要；同步超时未读出时为空。
     */
    interface TerminalLook extends Action {
        /** 同步完成后整理好的网络状态与存货摘要文字；同步超时未读出时为空。 */
        Optional<String> summary();
    }

    /**
     * 最近点开某台终端时看到的物流网络存货记忆快照：固定终端带世界坐标与所在面，随身无线终端标 {@code wireless=true}。
     *
     * @param terminal       终端部件或物品注册 ID（例如 ae2:crafting_terminal、ae2:wireless_terminal）
     * @param wireless       是否为随身无线终端；固定终端为 false
     * @param position       固定终端所在的格子与维度；无线终端为 null
     * @param side           固定终端装在该格的哪一面；无线终端为 null
     * @param linked         打开那一刻界面显示网络连上了没有
     * @param complete       存货表是否同步齐了
     * @param storedByItem   现货物品 ID → 件数（long，只含有货项）
     * @param craftableItems 网络里有样板能自动合成的物品 ID 集合
     * @param recordedAt     看到的时刻
     */
    record RememberedNetwork(String terminal, boolean wireless, WorldPosition position, Direction side,
            boolean linked, boolean complete, Map<String, Long> storedByItem, Set<String> craftableItems,
            Instant recordedAt) {
        public RememberedNetwork {
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(storedByItem, "storedByItem");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (!wireless) {
                Objects.requireNonNull(position, "position");
                Objects.requireNonNull(side, "side");
            }
            storedByItem = Map.copyOf(storedByItem);
            craftableItems = craftableItems == null ? Set.of() : Set.copyOf(craftableItems);
        }

        /** 装在线缆格某一面上的固定终端记忆快照。 */
        public static RememberedNetwork placed(String terminal, WorldPosition position, Direction side,
                boolean linked, boolean complete, Map<String, Long> storedByItem, Set<String> craftableItems,
                Instant recordedAt) {
            return new RememberedNetwork(terminal, false, position, side, linked, complete, storedByItem,
                    craftableItems, recordedAt);
        }

        /** 随身无线终端打开过的网络记忆快照。 */
        public static RememberedNetwork wireless(String terminal, boolean linked, boolean complete,
                Map<String, Long> storedByItem, Set<String> craftableItems, Instant recordedAt) {
            return new RememberedNetwork(terminal, true, null, null, linked, complete, storedByItem,
                    craftableItems, recordedAt);
        }
    }

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
