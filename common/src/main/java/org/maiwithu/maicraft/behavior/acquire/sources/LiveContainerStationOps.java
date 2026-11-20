// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 箱式加工站原生容器操作的生产实现：不硬编码任何模组方块，复用通用容器打开、存放与取货动作。
 * 进料箱投料复用 {@link StoresInContainer}，出料箱取货复用 {@link MenuTakeRound}，核对进料箱开箱读完即关。
 */
public final class LiveContainerStationOps implements ContainerStationOps {

    /** 单次开箱取货或核对的最长耐心（刻），防止界面长期异常时无限卡住。 */
    private static final long MENU_TIMEOUT_TICKS = 20L * 30;

    private final ReadsItemTags tags;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;
    private final BiFunction<BlockPos, Permissions, MenuOpening> openings;
    private final OthersThingsNotice othersNotice;
    private final Function<WorldPosition, Optional<String>> blockTypes;

    public LiveContainerStationOps(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts,
            OthersThingsNotice othersNotice) {
        this(tags, memory, contexts,
                (at, permissions) -> new ClientMenuOpening(at, permissions, layouts, close, interactions, contexts),
                othersNotice, null);
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(layouts, "layouts");
    }

    /** 离线单测构造入口：直接注入界面打开工厂与方块读数接缝。 */
    LiveContainerStationOps(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings, OthersThingsNotice othersNotice,
            Function<WorldPosition, Optional<String>> blockTypes) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = contexts == null ? () -> null : contexts;
        this.openings = Objects.requireNonNull(openings, "openings");
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.blockTypes = blockTypes == null ? this::readBlockTypeAt : blockTypes;
    }

    @Override
    public FeedAction feed(WorldPosition input, String stationName, String ingredientItemId, int count,
            Permissions permissions) {
        return new LiveFeedAction(input, stationName, ingredientItemId, count, permissions);
    }

    @Override
    public TakeAction takeOutput(WorldPosition output, String stationName, WantedItem result, int wantCount,
            String ingredientItemId, Permissions permissions) {
        return new LiveTakeAction(output, stationName, result, wantCount, ingredientItemId, permissions);
    }

    @Override
    public PeekAction peekInput(WorldPosition input, String stationName, String ingredientItemId,
            Permissions permissions) {
        return new LivePeekAction(input, stationName, ingredientItemId, permissions);
    }

    // 读当前已加载格子的真实方块 ID，写入世界记忆与通报用；读不到现场时留空，不做猜测。
    private Optional<String> readBlockTypeAt(WorldPosition position) {
        PlayerContext current = contexts.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return Optional.empty();
        BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
        return Optional.of(BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).toString());
    }

    private Optional<String> blockTypeAt(WorldPosition position) {
        return blockTypes.apply(position);
    }

    private void rememberContainer(WorldPosition position, MenuContent.Reading reading) {
        Map<String, Integer> contents = reading.containerContents();
        blockTypeAt(position).ifPresent(blockType ->
                memory.rememberContainerOpened(position, blockType, List.copyOf(contents.keySet()), Instant.now()));
    }

    private static String itemIdOf(SlotSnapshot snapshot) {
        return snapshot.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    private static int countItemInContainer(MenuContent.Reading reading, String itemId) {
        int sum = 0;
        for (SlotSnapshot snapshot : reading.containerSnapshots()) {
            if (!snapshot.isEmpty() && itemIdOf(snapshot).equals(itemId)) {
                sum += snapshot.count();
            }
        }
        return sum;
    }

    private static int countItemInContents(Map<String, Integer> contents, String itemId) {
        int sum = 0;
        for (Map.Entry<String, Integer> entry : contents.entrySet()) {
            if (StackKey.parse(entry.getKey()).itemId().equals(itemId)) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    /** 往投料箱放入指定原料：复用通用存箱流程，同时记录放进即被漏斗抽走的件数与关箱前箱内剩余件数。 */
    private final class LiveFeedAction implements FeedAction, ReportsUnconfirmed {
        private final String ingredientItemId;
        private final int targetCount;
        private final StoresInContainer storing;
        private final List<String> unconfirmed = new ArrayList<>();
        private int stored;
        private int passedThrough;

        LiveFeedAction(WorldPosition input, String stationName, String ingredientItemId, int count,
                Permissions permissions) {
            this.ingredientItemId = ingredientItemId;
            this.targetCount = count;
            KnownContainer container = new KnownContainer("加工站「" + stationName + "」进料箱", input,
                    blockTypeAt(input).orElse(""));
            StoresInContainer.Ledger ledger = new StoresInContainer.Ledger() {
                @Override public int left(String id) {
                    return id.equals(ingredientItemId) ? Math.max(0, targetCount - delivered()) : 0;
                }

                @Override public void stored(String id, int amount) {
                    if (id.equals(ingredientItemId)) stored += amount;
                }

                @Override public void passedThrough(String id, int amount, String fact) {
                    // 放进投料箱瞬间被下方溜槽或漏斗抽走：原料已进入加工线，计入已投入件数并保留说明。
                    if (id.equals(ingredientItemId)) passedThrough += amount;
                    unconfirmed.add(fact);
                }

                @Override public void unconfirmed(String fact) {
                    unconfirmed.add(fact);
                }

                @Override public void attempt(String tried, String whatHappened) { }
            };
            StoresInContainer.Parts parts = new StoresInContainer.Parts(openings::apply, memory, othersNotice);
            this.storing = new StoresInContainer(container, List.of(ingredientItemId), ledger, permissions, parts);
        }

        @Override public int delivered() {
            return stored + passedThrough;
        }

        @Override public int remainingInInput() {
            Optional<Map<String, Integer>> contents = storing.contentsAfter();
            if (contents.isPresent()) {
                return countItemInContents(contents.get(), ingredientItemId);
            }
            return passedThrough > 0 && stored == 0 ? 0 : stored;
        }

        @Override public ActionStatus tick(TickContext tick) { return storing.tick(tick); }
        @Override public void pause() { storing.pause(); }
        @Override public void close() { storing.close(); }
        @Override public Interruptibility interruptibility() { return storing.interruptibility(); }
        @Override public String describe() { return storing.describe(); }
        @Override public List<String> unconfirmedFacts() { return List.copyOf(unconfirmed); }
    }

    /** 到出料箱开箱取成品：走通用界面打开与取货一轮，不套用"供料箱留一半"限制（出料箱成品应全部可取）。 */
    private final class LiveTakeAction implements TakeAction, ReportsUnconfirmed {
        private final WorldPosition output;
        private final String stationName;
        private final WantedItem result;
        private final int wantCount;
        private final String ingredientItemId;
        private final MenuOpening opening;
        private OpenedMenu menu;
        private MenuTakeRound round;
        private int waited;
        private int startBackpackTotal = -1;
        private Map<String, Integer> startBackpackByItem = Map.of();
        private int taken;
        private OptionalInt remainingIngredient = OptionalInt.empty();

        LiveTakeAction(WorldPosition output, String stationName, WantedItem result, int wantCount,
                String ingredientItemId, Permissions permissions) {
            this.output = output;
            this.stationName = stationName;
            this.result = result;
            this.wantCount = wantCount;
            this.ingredientItemId = ingredientItemId;
            this.opening = openings.apply(new BlockPos(output.x(), output.y(), output.z()), permissions);
        }

        @Override public int taken() { return taken; }
        @Override public OptionalInt remainingIngredient() { return remainingIngredient; }

        @Override public ActionStatus tick(TickContext tick) {
            if (menu != null && ++waited > MENU_TIMEOUT_TICKS) {
                round.abandonAndNote("出料箱取货超时");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "加工站「" + stationName + "」出料箱取货超时", null));
            }
            if (menu == null) {
                ActionStatus status = opening.tick(tick);
                if (!(status instanceof ActionStatus.Done)) return status;
                menu = opening.opened().orElseThrow();
                round = new MenuTakeRound(menu, List.of(new OutputWant()), new OutputEndings());
                return ActionStatus.progressed();
            }
            return round.tick(tick, menu.reading());
        }

        private boolean matchesResult(SlotSnapshot snapshot) {
            return !snapshot.isEmpty() && result.matches(snapshot.key(), tags.tagsOf(itemIdOf(snapshot)));
        }

        // 按角色背包侧（36 格）统计匹配成品总数，避免出料箱接漏斗/溜槽持续吐货抵消容器侧差值。
        private int matchedInBackpack(MenuContent.Reading reading) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.playerSnapshots()) {
                if (matchesResult(snapshot)) total += snapshot.count();
            }
            return total;
        }

        private Map<String, Integer> matchedByItemInBackpack(MenuContent.Reading reading) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : reading.playerSnapshots()) {
                if (matchesResult(snapshot)) counts.merge(itemIdOf(snapshot), snapshot.count(), Integer::sum);
            }
            return counts;
        }

        private Map<String, String> displayNamesInBackpack(MenuContent.Reading reading) {
            Map<String, String> names = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : reading.playerSnapshots()) {
                if (matchesResult(snapshot)) {
                    names.putIfAbsent(itemIdOf(snapshot), snapshot.stack().getHoverName().getString());
                }
            }
            return names;
        }

        private void reportTook(MenuContent.Reading reading) {
            Optional<String> blockType = blockTypeAt(output);
            if (blockType.isEmpty()) return;
            Map<String, Integer> after = matchedByItemInBackpack(reading);
            Map<String, String> labels = displayNamesInBackpack(reading);
            List<OthersThingsNotice.MovedItem> moved = new ArrayList<>();
            after.forEach((itemId, count) -> {
                int delta = count - startBackpackByItem.getOrDefault(itemId, 0);
                if (delta > 0) moved.add(new OthersThingsNotice.MovedItem(itemId, labels.get(itemId), delta));
            });
            if (!moved.isEmpty()) {
                othersNotice.containerTook(output, blockType.get(), moved);
            }
        }

        @Override public Interruptibility interruptibility() {
            return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        @Override public void close() {
            opening.close();
            if (round != null) round.close(true);
        }

        @Override public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }

        @Override public String describe() {
            return "从加工站「" + stationName + "」出料箱取" + result.describe();
        }

        private final class OutputWant implements MenuTakeRound.Want {
            @Override public String label() { return describe(); }
            @Override public Predicate<SlotSnapshot> matcher() { return LiveTakeAction.this::matchesResult; }
            @Override public void firstReading(MenuContent.Reading reading) {
                startBackpackTotal = matchedInBackpack(reading);
                startBackpackByItem = matchedByItemInBackpack(reading);
            }
            @Override public int stillNeeded(MenuContent.Reading reading) {
                int nowTaken = startBackpackTotal < 0 ? 0 : Math.max(0, matchedInBackpack(reading) - startBackpackTotal);
                taken = Math.max(taken, nowTaken);
                return Math.max(0, wantCount - nowTaken);
            }
            @Override public boolean settleAfterDiverge() { return false; }
        }

        private final class OutputEndings implements MenuTakeRound.Endings {
            @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                lastReading.ifPresent(reading -> {
                    if (startBackpackTotal >= 0) {
                        taken = Math.max(taken, Math.max(0, matchedInBackpack(reading) - startBackpackTotal));
                        reportTook(reading);
                    }
                    if (ingredientItemId != null) {
                        remainingIngredient = OptionalInt.of(countItemInContainer(reading, ingredientItemId));
                    }
                    rememberContainer(output, reading);
                });
            }

            @Override public ActionStatus finishAfterClosed(boolean tookAnything) { return ActionStatus.done(); }
            @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) { return ActionStatus.done(); }
        }
    }

    /** 打开进料箱看一眼原料还剩几件就关箱，用来核实机器是否真的在抽料。 */
    private final class LivePeekAction implements PeekAction {
        private final WorldPosition input;
        private final String stationName;
        private final String ingredientItemId;
        private final MenuOpening opening;
        private OpenedMenu menu;
        private Action closing;
        private int remaining;
        private int waited;

        LivePeekAction(WorldPosition input, String stationName, String ingredientItemId, Permissions permissions) {
            this.input = input;
            this.stationName = stationName;
            this.ingredientItemId = ingredientItemId;
            this.opening = openings.apply(new BlockPos(input.x(), input.y(), input.z()), permissions);
        }

        @Override public int remainingIngredient() { return remaining; }

        @Override public ActionStatus tick(TickContext tick) {
            if (menu != null && ++waited > MENU_TIMEOUT_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "查看加工站「" + stationName + "」进料箱超时", null));
            }
            if (closing != null) {
                ActionStatus status = closing.tick(tick);
                return status instanceof ActionStatus.Running ? status : ActionStatus.done();
            }
            if (menu == null) {
                ActionStatus status = opening.tick(tick);
                if (!(status instanceof ActionStatus.Done)) return status;
                menu = opening.opened().orElseThrow();
                return ActionStatus.progressed();
            }
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE,
                        "加工站「" + stationName + "」进料箱界面还没看完就被关掉了", null));
            }
            remaining = countItemInContainer(reading.get(), ingredientItemId);
            rememberContainer(input, reading.get());
            closing = menu.closing();
            return ActionStatus.progressed();
        }

        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        @Override public void close() {
            opening.close();
            if (closing != null) closing.close();
            else if (menu != null) menu.abandon();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() {
            return "核对加工站「" + stationName + "」进料箱里的" + ingredientItemId;
        }
    }
}
