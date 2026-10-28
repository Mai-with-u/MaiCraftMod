// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.hands.StackMerger;

/**
 * 背包空间：捡东西、合成取出、交易、开箱取物、卸下装备之前，统一来这里腾格子，任务自己不再各自处理"背包满了"。
 *
 * <p>腾挪照真人玩家的做法一步步来：合并散堆 → 能存网络就存进网络（身上绑好的无线终端拿出来存，没带就走去附近装着的
 * ME 终端存，和存东西能力同一份） → 放进随身背包 → 存进附近记得的容器。腾背包只做可逆的搬动，
 * 不替玩家往地上扔东西；有用的只存不丢。每一步都是一个真实的动作（并散堆、存箱子用的是存东西能力同一份存放动作），
 * 确认的变化与没能确认的交互直接记进发起任务的结果。
 *
 * <p>腾一次就是一个动作（{@link #makeRoom}）：每做完一步重新看背包、重新排计划，腾够了算做完；
 * 一步做不成就换计划里的下一步，做不成的容器、东西这次不再试。随身背包、已知容器、存放的
 * 部件没接上时，对应步骤自动跳过；能搬的都搬了还腾不出，如实失败并把现场能处置的出路列进建议，取舍交调用方。
 */
public final class InventorySpace {

    /** 记得多少格以内的容器算"附近"：附近有容器时优先存放，不往地上扔。 */
    public static final int NEARBY_CONTAINER_RANGE = 16;

    /** 要留的东西：预留的、正要用的留够件数，腾背包不挪不丢。 */
    private final ItemsToKeep kept;
    private final BackpackView backpack;
    private final Optional<StackMerger> stackMerger;
    private final Optional<CarriedBackpack> carriedBackpack;
    private final Optional<KnownContainers> knownContainers;
    private final Optional<StoresInContainer.Parts> storing;
    /** 网络存储（身上的无线终端、附近装着的终端）；没接上为空。每次腾挪时现取，联动模组在进世界后才登记。 */
    private final Supplier<Optional<NetworkStorage>> network;

    /**
     * @param kept            要留的东西：留够的件数不挪不丢
     * @param backpack        背包视图：腾挪的每一步都以它读到的现场为准
     * @param stackMerger     合并散堆的接缝；没接上传 {@code Optional.empty()}
     * @param carriedBackpack 随身背包接缝；没接上传 {@code Optional.empty()}
     * @param knownContainers 附近已知容器接缝；没接上传 {@code Optional.empty()}
     * @param storing         存放的现场部件（与存东西能力共用）；没接上传 {@code Optional.empty()}
     */
    public InventorySpace(
            ItemsToKeep kept,
            BackpackView backpack,
            Optional<StackMerger> stackMerger,
            Optional<CarriedBackpack> carriedBackpack,
            Optional<KnownContainers> knownContainers,
            Optional<StoresInContainer.Parts> storing) {
        this(kept, backpack, stackMerger, carriedBackpack, knownContainers, storing, Optional::empty);
    }

    /** @param network 网络存储（身上的无线终端、附近装着的终端）；没有联动时给空 */
    public InventorySpace(
            ItemsToKeep kept,
            BackpackView backpack,
            Optional<StackMerger> stackMerger,
            Optional<CarriedBackpack> carriedBackpack,
            Optional<KnownContainers> knownContainers,
            Optional<StoresInContainer.Parts> storing,
            Supplier<Optional<NetworkStorage>> network) {
        this.kept = Objects.requireNonNull(kept, "kept");
        this.network = Objects.requireNonNull(network, "network");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.stackMerger = Objects.requireNonNull(stackMerger, "stackMerger");
        this.carriedBackpack = Objects.requireNonNull(carriedBackpack, "carriedBackpack");
        this.knownContainers = Objects.requireNonNull(knownContainers, "knownContainers");
        this.storing = Objects.requireNonNull(storing, "storing");
    }

    /**
     * 腾出若干格空位的动作：腾够了算做完；能搬的都搬了还腾不出时以 INVENTORY_FULL 失败，
     * 建议里照现场列出能丢的候选、能存的容器与处置用的能力名，丢哪堆、存到哪由调用方定。
     *
     * @param slotsNeeded 要腾出的空格数
     * @param purpose     拿这些空位做什么，例如"捡起掉落的铁锭"；写进存放的变化备注，让 LLM 看到为什么动了它
     * @param keep        这次要留着的物品注册 ID（例如正要拿的东西），不往外挪
     * @param permissions 这次任务的许可：存进容器要走路，走过去能动多少地形按它来
     * @param records     发起任务的记账口
     */
    public Action makeRoom(int slotsNeeded, String purpose, Set<String> keep, Permissions permissions,
            TaskRecords records) {
        if (slotsNeeded < 0) {
            throw new IllegalArgumentException("要腾出的格数不能为负：" + slotsNeeded);
        }
        return new MakingRoom(slotsNeeded, purpose, Set.copyOf(keep), Objects.requireNonNull(permissions, "permissions"),
                Objects.requireNonNull(records, "records"));
    }

    /** 一次腾挪：看背包 → 排计划 → 做第一步做得了的 → 再看背包，直到腾够、要问或腾不出。 */
    private final class MakingRoom implements Action {

        private final int slotsNeeded;
        private final String purpose;
        private final Permissions permissions;
        private final TaskRecords records;
        /** 不往外挪的东西：任务要留着的，加上这次挪不动的（存哪都存不进的）。 */
        private final Set<String> unmovable;
        /** 这次存不进去的容器：点不开、放不下的，不再往里塞。 */
        private final Set<WorldPosition> failedContainers = new HashSet<>();
        private boolean mergeBroken;
        private boolean carriedBroken;
        /** 这次存不进网络了（终端打不开、没连上、网络满了）：不再试，改走随身背包或箱子。 */
        private boolean networkBroken;
        private SpaceMove doing;
        private Action step;
        /** 正在做的存箱子那一步，和这一步确认存进了几件：做完没存进或放不下了，这只就不再试。 */
        private StoresInContainer storingStep;
        private int storedThisStep;

        MakingRoom(int slotsNeeded, String purpose, Set<String> keep, Permissions permissions, TaskRecords records) {
            this.slotsNeeded = slotsNeeded;
            this.purpose = purpose;
            this.unmovable = new HashSet<>(keep);
            this.permissions = permissions;
            this.records = records;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            // 正在做的一步先做完（界面开着、投掷在等确认时不能转头做别的），再回来看背包。
            if (step != null) {
                switch (step.tick(context)) {
                    case ActionStatus.Running running -> {
                        return running;
                    }
                    case ActionStatus.Done done -> {
                        settleStore();
                        endStep();
                    }
                    case ActionStatus.Failed failed -> {
                        giveUpOn(doing, failed.problem());
                        endStep();
                    }
                }
                return ActionStatus.progressed();
            }
            if (backpack.freeSlots() >= slotsNeeded) {
                return ActionStatus.done();
            }
            SpacePlanner.Plan plan = SpacePlanner.plan(scene());
            // 按计划顺序做第一步做得了的；部件没接上的这一步就做不了，换下一步。
            for (SpaceMove move : plan.moves()) {
                Optional<Action> started = start(move);
                if (started.isPresent()) {
                    doing = move;
                    step = started.get();
                    return ActionStatus.progressed();
                }
                giveUpOn(move, null);
            }
            if (plan.problem() != null) {
                // 能搬的都搬不了或搬了也不够：如实说腾不出，建议交回调用方处置。
                return ActionStatus.failed(plan.problem());
            }
            // 计划腾得够但每一步都做不了（部件没接上、容器不在了……）：如实说腾不出，不悄悄空转。
            return ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "腾地方的办法都用不上，背包腾不出 " + slotsNeeded + " 格"));
        }

        // 一次腾挪要看的现场：这次做不成的合并、随身背包、容器与东西都从计划里拿掉，免得原地重试。
        // 记得的容器只要记得就进现场：存箱子的部件没接上时计划排的存箱步做不了会自动跳过，
        // 腾不出时的建议仍能把容器位置报给调用方（它可以用 deposit 自己去存）。
        private SpacePlanner.Scene scene() {
            List<KnownContainer> containers = knownContainers
                    .map(known -> known.within(NEARBY_CONTAINER_RANGE)).orElse(List.of()).stream()
                    .filter(container -> !failedContainers.contains(container.at()))
                    .toList();
            List<BackpackStack> stacks = backpack.stacks();
            return new SpacePlanner.Scene(
                    stacks,
                    backpack.freeSlots(),
                    slotsNeeded,
                    Set.copyOf(unmovable),
                    stackMerger.isPresent() && !mergeBroken,
                    carriedBroken ? 0 : carriedBackpack.map(CarriedBackpack::freeSlots).orElse(0),
                    containers,
                    !networkBroken && network.get().map(storage -> storage.reachable(nearMe())).orElse(false),
                    keptCounts(stacks));
        }

        // 背包里每种东西要留几件：预留的、正要用的；留够这么多的那几堆腾背包不挪，多出来的照常按价值排。
        private Map<String, Integer> keptCounts(List<BackpackStack> stacks) {
            Map<String, Integer> counts = new HashMap<>();
            for (BackpackStack stack : stacks) {
                if (counts.containsKey(stack.itemId())) continue;
                int keep = kept.keep(stack.itemId());
                counts.put(stack.itemId(), keep);
            }
            counts.values().removeIf(keep -> keep <= 0);
            return counts;
        }

        // 腾地方找终端就在身边：身上绑好的无线终端优先，没带就找和记得的容器一样近（16 格内）的装着的终端。
        private NetworkStorage.TerminalSearch nearMe() {
            return NetworkStorage.TerminalSearch.nearMe(NEARBY_CONTAINER_RANGE, permissions);
        }

        // 把计划里的一步变成动作；部件没接上就给空。
        private Optional<Action> start(SpaceMove move) {
            return switch (move) {
                case SpaceMove.MergeStacks merge -> stackMerger.map(merger -> merger.mergeOne(records));
                case SpaceMove.ToCarriedBackpack(var stack) -> carriedBackpack.flatMap(pack -> pack.store(stack, records));
                // 挑不到终端（刚才还在、这会儿被拆了）就当这一步做不了，换下一步。
                case SpaceMove.ToNetwork(var stacks) -> network.get().flatMap(storage ->
                        switch (storage.store(stacks, "腾地方（" + purpose + "）", nearMe(), records)) {
                            case NetworkStorage.TerminalPick.Chosen chosen -> Optional.of(chosen.storing());
                            case NetworkStorage.TerminalPick.NotFound notFound -> Optional.<Action>empty();
                        });
                case SpaceMove.ToKnownContainer(var container, var stack) -> storing.map(parts -> {
                    storingStep = new StoresInContainer(container, List.of(stack.itemId()), storeLedger(stack),
                            permissions, parts);
                    storedThisStep = 0;
                    return storingStep;
                });
            };
        }

        // 存进容器的账：只存计划里这一堆的件数；存进的记变化，没能确认的、试过的照实记。
        private StoresInContainer.Ledger storeLedger(BackpackStack stack) {
            return new StoresInContainer.Ledger() {
                @Override public int left(String itemId) {
                    return itemId.equals(stack.itemId()) ? stack.count() - storedThisStep : 0;
                }

                @Override public void stored(String itemId, int amount) {
                    storedThisStep += amount;
                    records.change(new Change(Change.Kind.ITEM_STORED, itemId, amount,
                            "腾地方（" + purpose + "）：存进了 " + nameOf(doing)));
                }

                @Override public void unconfirmed(String fact) {
                    records.unconfirmed(new Change(Change.Kind.OTHER, "腾背包", 1, fact));
                }

                // 放进去就被下面的溜槽、漏斗抽走的也腾出了背包：算进这一步，去向照实记进没能确认。
                @Override public void passedThrough(String itemId, int amount, String fact) {
                    storedThisStep += amount;
                    records.unconfirmed(new Change(Change.Kind.OTHER, "腾背包", amount, fact));
                }

                @Override public void attempt(String tried, String whatHappened) {
                    records.attempt("腾地方：" + tried, whatHappened);
                }
            };
        }

        // 一步做不成：这次不再试同一条路——合并坏了不再合，随身背包不再塞，容器不再存，存不进网络不再存。
        private void giveUpOn(SpaceMove move, Problem problem) {
            switch (move) {
                case SpaceMove.MergeStacks merge -> mergeBroken = true;
                case SpaceMove.ToCarriedBackpack carried -> carriedBroken = true;
                case SpaceMove.ToNetwork stored -> networkBroken = true;
                case SpaceMove.ToKnownContainer(var container, var stack) -> failedContainers.add(container.at());
            }
            // 存箱子自己记了试过什么；其余的做不成在这里记一笔，部件没接上的不算试过。
            if (problem != null && !(move instanceof SpaceMove.ToKnownContainer)) {
                records.attempt("腾地方：" + describeMove(move), problem.message());
            }
        }

        // 存箱子那一步做完了：一件没存进、或这只已经放不下了，这次不再往这只里塞，免得原地反复开关。
        private void settleStore() {
            if (doing instanceof SpaceMove.ToKnownContainer(var container, var stack)
                    && (storedThisStep == 0 || !storingStep.full().isEmpty())) {
                failedContainers.add(container.at());
            }
        }

        private void endStep() {
            step.close();
            step = null;
            doing = null;
            storingStep = null;
        }

        @Override
        public void pause() {
            if (step != null) step.pause();
        }

        @Override
        public void close() {
            if (step != null) endStep();
        }

        @Override
        public Interruptibility interruptibility() {
            return step == null ? Interruptibility.WORKING : step.interruptibility();
        }

        @Override
        public String describe() {
            return step == null ? "看背包还差几格（" + purpose + "）" : "腾地方：" + step.describe();
        }
    }

    private static String nameOf(SpaceMove move) {
        return move instanceof SpaceMove.ToKnownContainer(var container, var stack) ? container.name() : "容器";
    }

    // 一步腾挪给人看的说法，例如"把 64 个 minecraft:cobblestone 存进家门口的箱子"。
    private static String describeMove(SpaceMove move) {
        return switch (move) {
            case SpaceMove.MergeStacks merge -> "合并散着的 " + merge.itemId();
            case SpaceMove.ToCarriedBackpack(var stack) -> "把 " + noteOf(stack) + " 放进随身背包";
            case SpaceMove.ToNetwork(var stacks) -> stacks.size() == 1 ? "把 " + noteOf(stacks.getFirst()) + " 存进网络"
                    : "把 " + stacks.size() + " 样东西存进网络";
            case SpaceMove.ToKnownContainer(var container, var stack) -> "把 " + noteOf(stack) + " 存进 " + container.name();
        };
    }

    private static String noteOf(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }
}
