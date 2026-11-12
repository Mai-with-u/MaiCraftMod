// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.StorageUse;

/**
 * 挑战利品容器：不点名目标时，从半径内的候选容器里挑出还没开过的战利品容器并排好尝试顺序的纯函数。
 *
 * <p>只挑自然战利品容器（{@code natural_loot}）与没有放置记录的容器（{@code no_record}），且自然战利品容器排在最前，
 * 同档按走过去的代价由近到远排。角色自己放的、其他玩家放的、归属还没问到的、已经亲手打开过的、
 * 以及落在圈地或额外保护地标里的容器一律不挑，全部淘汰时按原因合并说明。
 */
public final class LootContainerChooser {

    /** 一只待筛选的容器：基础扫描事实、容器来源五态、以及存储保护结论。 */
    public record Candidate(ContainerChooser.Candidate container, ContainerWhose origin, StorageUse storageUse) {
        public Candidate {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(storageUse, "storageUse");
        }
    }

    /** 入选的一只战利品容器：压着天然方块时先挖开盖子再开箱。 */
    public record Entry(ContainerChooser.Candidate container, ContainerWhose origin, boolean digLidFirst) {
        public Entry {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** 筛选结论。 */
    public sealed interface Pick {
        /** 挑出了可开的未开战利品容器，按尝试先后排好。 */
        record Chosen(List<Entry> ordered) implements Pick {
            public Chosen {
                ordered = List.copyOf(ordered);
            }
        }

        /** 半径内没有能去搜刮的未开战利品容器；searched 写明找了多大范围及淘汰原因计数。 */
        record NotFound(String searched) implements Pick {}
    }

    private LootContainerChooser() {}

    /**
     * 在半径内的候选里挑没开过的战利品容器。
     *
     * @param candidates       扫描与记忆合并的候选容器
     * @param mayDigNaturalLid 这次任务的许可是否允许挖开压在盖子上的天然方块
     * @param radius           搜索半径（格），用于未找到时写清范围
     */
    public static Pick chooseUnopened(List<Candidate> candidates, boolean mayDigNaturalLid, int radius) {
        List<Entry> usable = new ArrayList<>();
        int alreadyOpened = 0;
        int playerPlaced = 0;
        int ownershipPending = 0;
        int guarded = 0;
        int blocked = 0;
        for (Candidate item : candidates) {
            if (!item.storageUse().usable()) {
                guarded++;
                continue;
            }
            ContainerWhose.Kind whose = item.origin().whose();
            if (whose == ContainerWhose.Kind.SELF || whose == ContainerWhose.Kind.PLAYER) {
                playerPlaced++;
                continue;
            }
            if (whose == ContainerWhose.Kind.UNKNOWN) {
                ownershipPending++;
                continue;
            }
            if (item.origin().openedBySelf() || item.container().knownContents() != null) {
                alreadyOpened++;
                continue;
            }
            ContainerChooser.Candidate c = item.container();
            if (!c.layoutKnown() || c.catSitting() || c.lid() == ContainerChooser.Lid.BLOCKED_BY_FOREIGN
                    || (c.lid() == ContainerChooser.Lid.BLOCKED_BY_NATURAL && !mayDigNaturalLid)) {
                blocked++;
                continue;
            }
            usable.add(new Entry(c, item.origin(), c.lid() == ContainerChooser.Lid.BLOCKED_BY_NATURAL));
        }
        if (!usable.isEmpty()) {
            usable.sort(Comparator.comparingInt((Entry e) -> tier(e.origin().whose()))
                    .thenComparingDouble(e -> e.container().walkCost()));
            return new Pick.Chosen(usable);
        }
        return new Pick.NotFound(describeEmpty(radius, alreadyOpened, playerPlaced, ownershipPending, guarded, blocked));
    }

    private static int tier(ContainerWhose.Kind whose) {
        return whose == ContainerWhose.Kind.NATURAL_LOOT ? 0 : 1;
    }

    private static String describeEmpty(int radius, int alreadyOpened, int playerPlaced, int ownershipPending,
            int guarded, int blocked) {
        List<String> notes = new ArrayList<>();
        if (alreadyOpened > 0) notes.add("已开过 " + alreadyOpened + " 只");
        if (playerPlaced > 0) notes.add("玩家放的 " + playerPlaced + " 只");
        if (ownershipPending > 0) notes.add("归属还没问到 " + ownershipPending + " 只");
        if (guarded > 0) notes.add("受圈地或保护地标挡住 " + guarded + " 只");
        if (blocked > 0) notes.add("盖子被压住或界面认不出 " + blocked + " 只");
        String base = "附近 " + radius + " 格内没有没开过的战利品容器";
        return notes.isEmpty() ? base : base + "（" + String.join("，", notes) + "）";
    }
}
