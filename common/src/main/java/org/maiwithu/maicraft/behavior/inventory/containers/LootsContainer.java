// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.maiwithu.maicraft.behavior.inventory.ItemWorth;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.Worth;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 搜刮一只容器的动作：走到跟前点开、按与腾背包共用的价值分级挑出值得拿的物品，按从贵到贱搬进背包再关上。
 *
 * <p>如果到场核出容器是角色自己或别的玩家放的（{@code self} / {@code player}），只看清并记下里面有什么就关箱，
 * 一件都不搬，收场标记为非战利品容器交回能力层说明。战利品容器则按 needed → precious → gear → useful → food
 * 依次搬到背包放不下为止，建材、零碎与垃圾留在箱子里标 {@code not_worth}，放不下的标 {@code no_room}。
 */
public final class LootsContainer implements Action {

    /** 一次开箱搜刮的总期限（刻），从点开容器算起。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    /** 开箱看到或拿走的一档物品计数。 */
    public record GradedItem(String item, int count, String grade) {}

    /** 留在容器里的一档物品计数及留下的原因（not_worth 或 no_room）。 */
    public record LeftItem(String item, int count, String grade, String why) {}

    /** 开出的稀罕贵重物及其中拿走了几件。 */
    public record RareFind(String item, int count, String name, int taken) {}

    /** 一格物品的分级结果：供生产按游戏物品组件读，也供离线测试注入。 */
    public record InspectedSlot(String itemId, String displayName, Worth worth, boolean precious) {}

    /** 给一格容器槽位定价值等级的读法。 */
    @FunctionalInterface
    public interface SlotClassifier {
        InspectedSlot inspect(SlotSnapshot slot, ItemsToKeep kept, Map<String, Integer> backpackTotals);
    }

    /** 生产默认分级：读原版物品栈快照，用腾背包同一份 ItemWorth 判级。 */
    public static final SlotClassifier LIVE_CLASSIFIER = (slot, kept, backpackTotals) -> {
        BackpackStack snap = ClientBackpackView.snapshot(slot.stack());
        Worth worth = ItemWorth.ofForLoot(snap, kept, backpackTotals);
        return new InspectedSlot(snap.itemId(), slot.stack().getHoverName().getString(), worth, snap.precious());
    };

    /** 一次开箱搜刮结算出的完整账目。 */
    public record Report(
            String block,
            WorldPosition position,
            ContainerWhose origin,
            List<GradedItem> opened,
            List<GradedItem> taken,
            List<LeftItem> left,
            List<RareFind> rareFinds,
            boolean backpackFull,
            boolean nonLootRefused,
            List<String> unconfirmed) {
    }

    private record BucketKey(String itemId, Worth worth) {}

    private final String containerLabel;
    private final String blockId;
    private final WorldPosition position;
    private final ContainerWhose initialOrigin;
    private final MenuOpening opening;
    private final ItemsToKeep kept;
    private final WorldMemory memory;
    private final RareLootNotice rareLootNotice;
    private final SlotClassifier classifier;

    private OpenedMenu menu;
    private MenuTakeRound round;
    private int waited;
    private ContainerWhose settledOrigin;
    private Set<String> previouslySeen = Set.of();
    private Map<String, Integer> initialBackpackTotals;
    private final Map<BucketKey, Integer> initialBuckets = new LinkedHashMap<>();
    private final Map<String, RareLootNotice.SeenPrecious> initialPrecious = new LinkedHashMap<>();
    private MenuContent.Reading latestReading;
    private boolean nonLootRefused;
    private Report finalReport;

    public LootsContainer(String containerLabel, String blockId, WorldPosition position, ContainerWhose initialOrigin,
            MenuOpening opening, ItemsToKeep kept, WorldMemory memory, RareLootNotice rareLootNotice) {
        this(containerLabel, blockId, position, initialOrigin, opening, kept, memory, rareLootNotice, LIVE_CLASSIFIER);
    }

    public LootsContainer(String containerLabel, String blockId, WorldPosition position, ContainerWhose initialOrigin,
            MenuOpening opening, ItemsToKeep kept, WorldMemory memory, RareLootNotice rareLootNotice,
            SlotClassifier classifier) {
        this.containerLabel = Objects.requireNonNull(containerLabel, "containerLabel");
        this.blockId = Objects.requireNonNull(blockId, "blockId");
        this.position = Objects.requireNonNull(position, "position");
        this.initialOrigin = Objects.requireNonNull(initialOrigin, "initialOrigin");
        this.opening = Objects.requireNonNull(opening, "opening");
        this.kept = Objects.requireNonNull(kept, "kept");
        this.memory = memory;
        this.rareLootNotice = rareLootNotice == null ? RareLootNotice.NONE : rareLootNotice;
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /** 动作收场后的搜刮报告；还没点开同步完时为空。 */
    public Optional<Report> report() {
        return Optional.ofNullable(finalReport);
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (menu == null) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            return ActionStatus.progressed();
        }
        if (++waited > GIVE_UP_TICKS) {
            if (round != null) round.abandonAndNote("总期限到了");
            settleReport();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "搜刮容器超时：" + containerLabel, null));
        }
        Optional<MenuContent.Reading> reading = menu.reading();
        if (round == null) {
            if (reading.isEmpty()) return ActionStatus.running();
            beginRound(reading.get());
        }
        return round.tick(tick, reading);
    }

    // 拿到第一份同步读数：先冻结开箱前的记忆记录与初始分级清单；自家或别人的箱子只看不拿，直接去关箱。
    private void beginRound(MenuContent.Reading first) {
        latestReading = first;
        boolean openedBefore = memory != null
                ? memory.recordAt(MemoryKind.CONTAINER, position).map(MemoryRecord::openedBefore)
                        .orElse(initialOrigin.openedBySelf())
                : initialOrigin.openedBySelf();
        settledOrigin = new ContainerWhose(initialOrigin.whose(), initialOrigin.lastModifiedBy(), openedBefore);
        previouslySeen = rareLootNotice.snapshotPreviouslySeen(position);
        initialBackpackTotals = backpackTotals(first.playerSnapshots());

        for (SlotSnapshot slot : first.containerSnapshots()) {
            if (slot.isEmpty()) continue;
            InspectedSlot inspected = inspect(slot);
            initialBuckets.merge(new BucketKey(inspected.itemId(), inspected.worth()), slot.count(), Integer::sum);
            if (inspected.precious() || inspected.worth() == Worth.PRECIOUS) {
                initialPrecious.compute(inspected.itemId(), (id, prev) -> prev == null
                        ? new RareLootNotice.SeenPrecious(id, inspected.displayName(), slot.count(), 0)
                        : new RareLootNotice.SeenPrecious(id, prev.displayName(), prev.count() + slot.count(), 0));
            }
        }

        ContainerWhose.Kind whose = settledOrigin.whose();
        if (whose == ContainerWhose.Kind.SELF || whose == ContainerWhose.Kind.PLAYER) {
            nonLootRefused = true;
            round = new MenuTakeRound(menu, List.of(), new RoundEndings());
            return;
        }
        List<LootWant> wants = buildLootWants();
        round = new MenuTakeRound(menu, wants, new RoundEndings());
    }

    private List<LootWant> buildLootWants() {
        List<BucketKey> toTake = new ArrayList<>();
        for (BucketKey key : initialBuckets.keySet()) {
            if (key.worth().worthLooting()) toTake.add(key);
        }
        // 严格按 needed -> precious -> gear -> useful -> food 排序，同档保留容器内出现顺序。
        toTake.sort(Comparator.comparingInt(key -> key.worth().ordinal()));
        List<LootWant> wants = new ArrayList<>(toTake.size());
        for (BucketKey key : toTake) {
            wants.add(new LootWant(key));
        }
        return wants;
    }

    private InspectedSlot inspect(SlotSnapshot slot) {
        return classifier.inspect(slot, kept, initialBackpackTotals == null ? Map.of() : initialBackpackTotals);
    }

    private Map<String, Integer> backpackTotals(List<SlotSnapshot> playerSlots) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (SlotSnapshot slot : playerSlots) {
            if (slot.isEmpty()) continue;
            String id = classifier.inspect(slot, kept, Map.of()).itemId();
            totals.merge(id, slot.count(), Integer::sum);
        }
        return Map.copyOf(totals);
    }

    // 关箱前对照初始分桶与最新容器槽位读数结算拿走了什么、留下了什么，并发出稀罕发现事件与更新记忆。
    private void settleReport() {
        if (finalReport != null || latestReading == null) return;
        Map<BucketKey, Integer> remainingBuckets = new LinkedHashMap<>();
        Map<String, Integer> remainingByItem = new LinkedHashMap<>();
        for (SlotSnapshot slot : latestReading.containerSnapshots()) {
            if (slot.isEmpty()) continue;
            InspectedSlot inspected = inspect(slot);
            remainingBuckets.merge(new BucketKey(inspected.itemId(), inspected.worth()), slot.count(), Integer::sum);
            remainingByItem.merge(inspected.itemId(), slot.count(), Integer::sum);
        }

        List<GradedItem> opened = new ArrayList<>();
        List<GradedItem> taken = new ArrayList<>();
        List<LeftItem> left = new ArrayList<>();
        boolean full = false;
        for (Map.Entry<BucketKey, Integer> entry : initialBuckets.entrySet()) {
            BucketKey key = entry.getKey();
            int startCount = entry.getValue();
            int leftCount = Math.min(startCount, remainingBuckets.getOrDefault(key, 0));
            int takenCount = Math.max(0, startCount - leftCount);
            String grade = key.worth().grade();
            opened.add(new GradedItem(key.itemId(), startCount, grade));
            if (!nonLootRefused && takenCount > 0) {
                taken.add(new GradedItem(key.itemId(), takenCount, grade));
            }
            int reportedLeft = nonLootRefused ? startCount : leftCount;
            if (reportedLeft > 0) {
                boolean roomShortage = !nonLootRefused && key.worth().worthLooting();
                if (roomShortage) full = true;
                left.add(new LeftItem(key.itemId(), reportedLeft, grade, roomShortage ? "no_room" : "not_worth"));
            }
        }

        List<RareFind> rareFinds = settleRareFinds(remainingByItem);
        if (memory != null) {
            memory.rememberContainerOpened(position, blockId,
                    List.copyOf(latestReading.containerContents().keySet()), Instant.now());
        }
        List<String> unconfirmed = round == null ? List.of() : round.unconfirmedFacts();
        finalReport = new Report(blockId, position, settledOrigin, List.copyOf(opened), List.copyOf(taken),
                List.copyOf(left), rareFinds, full, nonLootRefused, unconfirmed);
    }

    private List<RareFind> settleRareFinds(Map<String, Integer> remainingByItem) {
        if (nonLootRefused || initialPrecious.isEmpty()) return List.of();
        List<RareFind> finds = new ArrayList<>(initialPrecious.size());
        List<RareLootNotice.SeenPrecious> forEvent = new ArrayList<>(initialPrecious.size());
        for (RareLootNotice.SeenPrecious item : initialPrecious.values()) {
            int leftCount = remainingByItem.getOrDefault(item.itemId(), 0);
            int takenCount = Math.max(0, item.count() - leftCount);
            finds.add(new RareFind(item.itemId(), item.count(), item.displayName(), takenCount));
            forEvent.add(item.withTaken(takenCount));
        }
        rareLootNotice.report(position, blockId, settledOrigin.whose(), previouslySeen, forEvent);
        return List.copyOf(finds);
    }

    @Override
    public Interruptibility interruptibility() {
        return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public void pause() {
        opening.pause();
        if (menu != null) menu.abandon();
    }

    @Override
    public void close() {
        opening.close();
        if (round != null) {
            round.close(true);
            settleReport();
        } else if (menu != null) {
            menu.abandon();
        }
    }

    @Override
    public String describe() {
        return "搜刮" + containerLabel;
    }

    private final class LootWant implements MenuTakeRound.Want {
        private final BucketKey key;

        LootWant(BucketKey key) {
            this.key = key;
        }

        @Override
        public String label() {
            return "从" + containerLabel + "搜刮 " + key.itemId();
        }

        @Override
        public Predicate<SlotSnapshot> matcher() {
            return slot -> {
                if (slot.isEmpty()) return false;
                InspectedSlot inspected = inspect(slot);
                return key.itemId().equals(inspected.itemId()) && key.worth() == inspected.worth();
            };
        }

        @Override
        public int stillNeeded(MenuContent.Reading reading) {
            latestReading = reading;
            int remaining = 0;
            for (SlotSnapshot slot : reading.containerSnapshots()) {
                if (matcher().test(slot)) remaining += slot.count();
            }
            return remaining;
        }

        @Override
        public boolean settleAfterDiverge() {
            return false;
        }
    }

    private final class RoundEndings implements MenuTakeRound.Endings {
        @Override
        public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
            lastReading.ifPresent(reading -> latestReading = reading);
            settleReport();
        }

        @Override
        public ActionStatus finishAfterClosed(boolean tookAnything) {
            settleReport();
            return ActionStatus.done();
        }

        @Override
        public ActionStatus finishAfterMenuGone(boolean tookAnything) {
            settleReport();
            return ActionStatus.done();
        }
    }
}
