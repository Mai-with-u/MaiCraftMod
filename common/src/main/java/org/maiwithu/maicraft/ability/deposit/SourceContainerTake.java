// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.ItemMatch;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从一只来源容器里取出一轮要转存的物品：走过去点开界面 → 按指定的物品或整箱全部非空物品往角色背包搬 →
 * 搬空、凑够总件数或角色背包放不下时停手 → 关箱前刷新世界记忆并通报动了别人放的箱子 → 关上界面。
 *
 * <p>给 {@code deposit} 带 {@code source} 时做中转取货用：只记本轮确认进入角色背包的物品与件数，
 * 方便下一步原数存进 ME 网络或目标容器，绝不动角色原本随身带的工具、食物与建材。
 */
final class SourceContainerTake implements Action {

    /** 单次开箱取货的超时上限（刻），防止坏界面一直卡住身体。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final KnownContainer container;
    private final List<String> itemIds;
    private final int maxCount;
    private final StoresInContainer.Parts parts;
    private final ReadsItemTags tags;
    private final BackpackView backpack;
    private final TaskRecords records;
    private final MenuOpening opening;

    private OpenedMenu menu;
    private MenuTakeRound round;
    private int waited;
    private Problem openFailure;
    private boolean initialized;
    private boolean hadMatchingItems;
    private boolean hasMoreMatchingItems;
    private int initialMatchedTotal;
    private final Map<String, Integer> initialContainerCounts = new LinkedHashMap<>();
    private final Map<String, Integer> initialPlayerCounts = new HashMap<>();
    private final Map<String, Integer> initialBackpackCounts = new HashMap<>();
    private final Map<String, String> matchedNames = new HashMap<>();
    private final Map<String, Integer> pulled = new LinkedHashMap<>();
    private MenuContent.Reading latestReading;
    private boolean othersReported;
    private boolean unconfirmedFlushed;

    SourceContainerTake(KnownContainer container, List<String> itemIds, int maxCount, Permissions permissions,
            StoresInContainer.Parts parts, ReadsItemTags tags, BackpackView backpack, TaskRecords records) {
        this.container = Objects.requireNonNull(container, "container");
        this.itemIds = List.copyOf(itemIds);
        this.maxCount = Math.max(0, maxCount);
        this.parts = Objects.requireNonNull(parts, "parts");
        this.tags = tags;
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.records = Objects.requireNonNull(records, "records");
        this.opening = parts.opens().open(container.cell(), permissions);
    }

    /** 点不开这只来源容器时的原因；点开过或还没试完时为空。 */
    Optional<Problem> openFailure() {
        return Optional.ofNullable(openFailure);
    }

    /** 开箱第一眼容器里有没有匹配的物品：没有时说明这只箱子不用搬。 */
    boolean hadMatchingItems() {
        return hadMatchingItems;
    }

    /** 关箱时这只来源容器里是不是还剩着没搬完的匹配物品（因为背包满了或到了本轮限额）。 */
    boolean hasMoreMatchingItems() {
        return hasMoreMatchingItems;
    }

    /** 本轮确认从这只来源容器拿进角色背包的各物品件数，按箱子格子先后排序。 */
    Map<String, Integer> pulled() {
        return Map.copyOf(pulled);
    }

    @Override
    public ActionStatus tick(TickContext context) {
        if (menu != null && ++waited > GIVE_UP_TICKS) {
            if (round != null) {
                round.abandonAndNote("总期限到了");
                flushUnconfirmed();
            }
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "从来源容器取货超时：" + container.name(), null));
        }
        if (menu == null) {
            return open(context);
        }
        return take(context);
    }

    // 先走过去点开来源容器：锁着、被拒或点不开时记一笔试法并把原因交回调用方决定换箱或收场。
    private ActionStatus open(TickContext context) {
        ActionStatus status = opening.tick(context);
        if (status instanceof ActionStatus.Running) {
            return status;
        }
        if (status instanceof ActionStatus.Failed failed) {
            openFailure = failed.problem();
            records.attempt("打开来源" + container.name(), failed.problem().message());
            return failed;
        }
        menu = opening.opened().orElseThrow();
        snapshotBackpackBeforeTake();
        return ActionStatus.progressed();
    }

    // 拿到第一份同步读数后按箱内现况列出要搬的条目，交给取货一轮逐笔搬进角色背包并在收尾时关箱。
    private ActionStatus take(TickContext context) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (!initialized) {
            if (reading.isEmpty()) {
                return ActionStatus.running();
            }
            initializeRound(reading.get());
        }
        ActionStatus status = round.tick(context, reading);
        if (!(status instanceof ActionStatus.Running)) {
            flushUnconfirmed();
        }
        return status;
    }

    // 在第一份界面读数上定下这只箱子里有哪些要搬的物品、初始件数与角色背包起点，没匹配物品也照常走收尾关箱。
    private void initializeRound(MenuContent.Reading first) {
        initialized = true;
        latestReading = first;
        for (SlotSnapshot snapshot : first.containerSnapshots()) {
            if (snapshot.isEmpty() || !matchesFilter(snapshot)) {
                continue;
            }
            String itemId = itemIdOf(snapshot);
            initialContainerCounts.merge(itemId, snapshot.count(), Integer::sum);
            matchedNames.putIfAbsent(itemId, snapshot.stack().getHoverName().getString());
        }
        initialMatchedTotal = initialContainerCounts.values().stream().mapToInt(Integer::intValue).sum();
        hadMatchingItems = initialMatchedTotal > 0;
        List<SourceItemWant> wants = new ArrayList<>(initialContainerCounts.size());
        for (String itemId : initialContainerCounts.keySet()) {
            initialPlayerCounts.put(itemId, countInSnapshots(first.playerSnapshots(), itemId));
            if (maxCount > 0) {
                wants.add(new SourceItemWant(itemId));
            }
        }
        round = new MenuTakeRound(menu, wants, new RoundEndings());
    }

    private void snapshotBackpackBeforeTake() {
        for (BackpackStack stack : backpack.stacks()) {
            initialBackpackCounts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
    }

    // 没指定 items 时整箱非空物品都搬；指定了 items 时按物品 ID、带药水种类的键或 # 标签匹配。
    private boolean matchesFilter(SlotSnapshot snapshot) {
        if (itemIds.isEmpty()) {
            return true;
        }
        String itemId = itemIdOf(snapshot);
        for (String filter : itemIds) {
            if (filter.startsWith("#")) {
                String tagName = filter.substring(1);
                if (ItemMatch.matches(snapshot.stack(), filter)
                        || (tags != null && tags.tagsOf(itemId).contains(tagName))) {
                    return true;
                }
            } else if (itemId.equals(filter) || snapshot.key().equals(filter)
                    || ItemMatch.matches(snapshot.stack(), filter)) {
                return true;
            }
        }
        return false;
    }

    private int allMatchedInContainer(MenuContent.Reading reading) {
        int sum = 0;
        for (String itemId : initialContainerCounts.keySet()) {
            sum += countInSnapshots(reading.containerSnapshots(), itemId);
        }
        return sum;
    }

    private static int countInSnapshots(List<SlotSnapshot> snapshots, String itemId) {
        int sum = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty() && itemIdOf(snapshot).equals(itemId)) {
                sum += snapshot.count();
            }
        }
        return sum;
    }

    private static String itemIdOf(SlotSnapshot snapshot) {
        return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    private void flushUnconfirmed() {
        if (unconfirmedFlushed || round == null) {
            return;
        }
        unconfirmedFlushed = true;
        for (String fact : round.unconfirmedFacts()) {
            records.unconfirmed(new Change(Change.Kind.OTHER, "搬运", 1, fact));
        }
    }

    // 关箱前按背包侧实增件数结算本轮取货账本，同时对别人放的方块容器合发一次取货通报。
    private void settleFromReading(MenuContent.Reading reading) {
        pulled.clear();
        for (String itemId : initialContainerCounts.keySet()) {
            int gained = countInSnapshots(reading.playerSnapshots(), itemId)
                    - initialPlayerCounts.getOrDefault(itemId, 0);
            if (gained > 0) {
                pulled.put(itemId, gained);
            }
        }
        hasMoreMatchingItems = allMatchedInContainer(reading) > 0;
        if (parts.memory() != null) {
            Map<String, Integer> contents = reading.containerContents();
            parts.memory().rememberContainerOpened(container.at(), container.blockTypeId(),
                    List.copyOf(contents.keySet()), Instant.now());
        }
        reportOthersTook(reading);
    }

    // 界面半途意外丢失时拿不到关箱读数，改按角色背包当前快照与开箱前快照的差额兜底清点已进包的件数。
    private void settleFromBackpackFallback() {
        Map<String, Integer> now = new HashMap<>();
        for (BackpackStack stack : backpack.stacks()) {
            now.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        pulled.clear();
        int totalPulled = 0;
        for (String itemId : initialContainerCounts.keySet()) {
            int gained = now.getOrDefault(itemId, 0) - initialBackpackCounts.getOrDefault(itemId, 0);
            if (gained > 0) {
                pulled.put(itemId, gained);
                totalPulled += gained;
            }
        }
        hasMoreMatchingItems = totalPulled < initialMatchedTotal;
    }

    private void reportOthersTook(MenuContent.Reading reading) {
        if (othersReported || initialContainerCounts.isEmpty()) {
            return;
        }
        othersReported = true;
        List<OthersThingsNotice.MovedItem> moved = new ArrayList<>();
        initialContainerCounts.forEach((itemId, before) -> {
            int after = countInSnapshots(reading.containerSnapshots(), itemId);
            int delta = before - after;
            if (delta > 0) {
                moved.add(new OthersThingsNotice.MovedItem(itemId, matchedNames.get(itemId), delta));
            }
        });
        if (!moved.isEmpty()) {
            parts.othersNotice().containerTook(container.at(), container.blockTypeId(), moved);
        }
    }

    @Override
    public Interruptibility interruptibility() {
        if (menu == null) {
            return opening.interruptibility();
        }
        return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public void pause() {
        opening.pause();
        if (menu != null) {
            menu.abandon();
        }
    }

    @Override
    public void close() {
        opening.close();
        if (round != null) {
            round.close(true);
            if (latestReading != null && !othersReported) {
                reportOthersTook(latestReading);
            }
            flushUnconfirmed();
        } else if (menu != null) {
            menu.abandon();
        }
    }

    @Override
    public String describe() {
        return "从来源" + container.name() + "取货";
    }

    /** 按具体物品种类拆分的一项取货条目：多物品共享总件数上限，单物品装满时仍继续尝试箱里下一种。 */
    private final class SourceItemWant implements MenuTakeRound.Want {
        private final String itemId;

        SourceItemWant(String itemId) {
            this.itemId = itemId;
        }

        @Override public String label() {
            return "从" + container.name() + "拿 " + itemId;
        }

        @Override public Predicate<SlotSnapshot> matcher() {
            return snapshot -> !snapshot.isEmpty() && itemIdOf(snapshot).equals(itemId);
        }

        @Override public int stillNeeded(MenuContent.Reading reading) {
            latestReading = reading;
            int inContainerNow = countInSnapshots(reading.containerSnapshots(), itemId);
            int takenAll = initialMatchedTotal - allMatchedInContainer(reading);
            int budgetLeft = Math.max(0, maxCount - takenAll);
            return Math.min(inContainerNow, budgetLeft);
        }

        @Override public boolean settleAfterDiverge() {
            return false;
        }
    }

    /** 一轮来源取货的收场：关箱前记下剩余内容与取走件数，关箱后交回完成。 */
    private final class RoundEndings implements MenuTakeRound.Endings {
        @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
            if (lastReading.isPresent()) {
                latestReading = lastReading.get();
                settleFromReading(lastReading.get());
            } else {
                settleFromBackpackFallback();
            }
        }

        @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
            return ActionStatus.done();
        }

        @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
            return ActionStatus.done();
        }
    }
}
