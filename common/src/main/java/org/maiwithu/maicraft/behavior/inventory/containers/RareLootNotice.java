// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 战利品容器稀罕发现通报器：任何能力（loot、use、obtain）打开一只战利品容器（natural_loot 或 no_record），
 * 看到里面有 precious 级的物品，就按世界记忆里"上回看到的内容"去重后发出一条 rare_loot_found 事件。
 *
 * <p>同一只容器里的同一样稀罕物只报一次：上回打开已经写进世界记忆的内容不再重报，本次会话里报过的也记一笔兜底。
 */
public final class RareLootNotice {

    /** 不接事件流时的空实现。 */
    public static final RareLootNotice NONE = new RareLootNotice(null, id -> false, null, TaskEventSink.NONE);

    /** 开箱看到的一样稀罕贵重物品及这一趟拿走了几件。 */
    public record SeenPrecious(String itemId, String displayName, int count, int taken) {
        public SeenPrecious {
            Objects.requireNonNull(itemId, "itemId");
            displayName = displayName == null || displayName.isBlank() ? itemId : displayName;
        }

        public SeenPrecious withTaken(int actualTaken) {
            return new SeenPrecious(itemId, displayName, count, Math.max(0, Math.min(count, actualTaken)));
        }
    }

    private final Protection protection;
    private final Predicate<String> naturalLoot;
    private final WorldMemory memory;
    private final TaskEventSink events;
    private final Set<String> announced = new HashSet<>();

    public RareLootNotice(Protection protection, Predicate<String> naturalLoot, WorldMemory memory,
            TaskEventSink events) {
        this.protection = protection;
        this.naturalLoot = Objects.requireNonNull(naturalLoot, "naturalLoot");
        this.memory = memory;
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 在把这次开箱内容写进世界记忆之前，先抄下这只容器上回打开时看到过哪些物品：
     * 上回已经见过的稀罕物这一趟不再重复报里程碑。
     */
    public Set<String> snapshotPreviouslySeen(WorldPosition at) {
        if (memory == null || at == null) return Set.of();
        return memory.recordAt(MemoryKind.CONTAINER, at)
                .filter(MemoryRecord::openedBefore)
                .map(RareLootNotice::itemIdsFromContents)
                .orElse(Set.of());
    }

    private static Set<String> itemIdsFromContents(MemoryRecord record) {
        if (record.contents() == null || record.contents().isEmpty()) return Set.of();
        Set<String> seen = new HashSet<>();
        for (String written : record.contents()) {
            seen.add(written);
            seen.add(StackKey.parse(written).itemId());
        }
        return Set.copyOf(seen);
    }

    /** 从开箱第一份同步读数的容器槽位里挑出全部 precious 级物品并按物品 ID 合并件数。 */
    public static List<SeenPrecious> collectInitial(List<SlotSnapshot> containerSlots) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (SlotSnapshot slot : containerSlots) {
            if (slot.isEmpty() || !ClientBackpackView.snapshot(slot.stack()).precious()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
            counts.merge(itemId, slot.count(), Integer::sum);
            names.putIfAbsent(itemId, slot.stack().getHoverName().getString());
        }
        List<SeenPrecious> result = new ArrayList<>(counts.size());
        counts.forEach((id, count) -> result.add(new SeenPrecious(id, names.get(id), count, 0)));
        return List.copyOf(result);
    }

    /** 对照关箱前最新容器槽位读数，算出开箱看到的每样稀罕物确认被拿走了几件。 */
    public static List<SeenPrecious> settleTaken(List<SeenPrecious> initial, List<SlotSnapshot> currentSlots) {
        if (initial.isEmpty()) return List.of();
        Map<String, Integer> remaining = new HashMap<>();
        for (SlotSnapshot slot : currentSlots) {
            if (slot.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
            remaining.merge(itemId, slot.count(), Integer::sum);
        }
        List<SeenPrecious> settled = new ArrayList<>(initial.size());
        for (SeenPrecious item : initial) {
            int left = remaining.getOrDefault(item.itemId(), 0);
            settled.add(item.withTaken(Math.max(0, item.count() - left)));
        }
        return List.copyOf(settled);
    }

    /** 按现场归属与标签断定容器来源后尝试发出 rare_loot_found。 */
    public void report(WorldPosition at, String blockId, Set<String> previouslySeen, List<SeenPrecious> found) {
        if (at == null || blockId == null || found.isEmpty()) return;
        ContainerWhose origin = ContainerWhose.of(at, naturalLoot.test(blockId), protection,
                previouslySeen != null && !previouslySeen.isEmpty());
        report(at, blockId, origin.whose(), previouslySeen, found);
    }

    /** 已知容器来源五态时直接判定：只对 natural_loot 与 no_record 战利品容器通报新发现的稀罕物。 */
    public void report(WorldPosition at, String blockId, ContainerWhose.Kind whose, Set<String> previouslySeen,
            List<SeenPrecious> found) {
        if (at == null || blockId == null || found == null || found.isEmpty()) return;
        if (whose != ContainerWhose.Kind.NATURAL_LOOT && whose != ContainerWhose.Kind.NO_RECORD) return;
        Set<String> seenBefore = previouslySeen == null ? Set.of() : previouslySeen;
        List<RareLootFound.Item> fresh = new ArrayList<>();
        for (SeenPrecious candidate : found) {
            if (candidate.count() <= 0 || seenBefore.contains(candidate.itemId())) continue;
            if (!announced.add(spotKey(at, candidate.itemId()))) continue;
            fresh.add(new RareLootFound.Item(candidate.itemId(), candidate.count(), candidate.displayName(),
                    Math.max(0, Math.min(candidate.count(), candidate.taken()))));
        }
        if (fresh.isEmpty()) return;
        events.publishInAction(TaskEvent.Kind.RARE_LOOT_FOUND, formatMessage(at, fresh),
                new RareLootFound(blockId, at, fresh));
    }

    private static String spotKey(WorldPosition at, String itemId) {
        return (at.dimension() == null ? "" : at.dimension()) + "@" + at.x() + "," + at.y() + "," + at.z()
                + "#" + itemId;
    }

    private static String formatMessage(WorldPosition at, List<RareLootFound.Item> items) {
        List<String> parts = new ArrayList<>(items.size());
        boolean allTaken = true;
        boolean noneTaken = true;
        for (RareLootFound.Item item : items) {
            if (item.taken() < item.count()) allTaken = false;
            if (item.taken() > 0) noneTaken = false;
            if (item.taken() > 0 && item.taken() < item.count()) {
                parts.add(item.name() + " ×" + item.count() + "（拿走 " + item.taken() + " 件）");
            } else {
                parts.add(item.name() + " ×" + item.count());
            }
        }
        String suffix = allTaken ? "，已经拿走" : noneTaken ? "，还在箱里" : "";
        return "打开战利品箱（" + at.x() + ", " + at.y() + ", " + at.z() + "）开出了"
                + String.join("、", parts) + suffix;
    }
}
