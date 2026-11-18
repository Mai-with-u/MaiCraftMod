// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.deposit.DepositSources.Place;
import org.maiwithu.maicraft.ability.deposit.DepositSources.SourceEntry;
import org.maiwithu.maicraft.ability.deposit.DepositSources.SourcePick;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 存东西的任务：未给 {@code source} 时挑好容器、清开压住盖子的方块，把角色背包里该存的东西存进容器或 ME 网络；
 * 给了 {@code source} 时按「从来源取一包 → 往目标存一包」循环分轮中转搬运，角色原本随身带的工具、食物与建材原封不动。
 *
 * <p>存放动作和腾地方存进记得的箱子是同一份：被打断时开着的界面先关上，恢复后重新点开接着存；
 * 被取消时界面也关上。只记确认过的量：每一笔按背包少了几件、容器多了几件对上才记账，对不上的进未确认，不凑数。
 */
final class DepositTask extends PhasedTask<DepositTask.Phase> {

    /** 单次带 source 的整理任务最多往返中转几轮，防止外部漏斗不断往源箱补货导致无限循环。 */
    private static final int MAX_SOURCE_ROUNDS = 32;
    /** 存进网络的那一行在"存进了哪"里的叫法。 */
    private static final String NETWORK_NAME = "ME 网络";

    /** 挑来源/清来源盖子/从来源取一包 → 挑目标容器 → 清开盖子 → 存进去 → 换下一只；存进网络时直接进终端。 */
    enum Phase { SOURCE_CHOOSE, SOURCE_DIG, SOURCE_TAKE, CHOOSE, DIG, STORE, NEXT, NETWORK, OFFHAND }

    private final DepositInput input;
    private final DepositServices services;
    private final BackpackView backpack;
    private final Deque<SourceEntry> sourceCandidates = new ArrayDeque<>();
    private final Deque<ContainerChooser.Entry> candidates = new ArrayDeque<>();
    /** 当前这一轮要存的各物品件数，按存什么的顺序。 */
    private final Map<String, Integer> wanted = new LinkedHashMap<>();
    private final Map<String, Integer> confirmed = new LinkedHashMap<>();
    /** 当前这一轮放进去就被容器下面的溜槽、漏斗抽走的件数：离开了背包，没留在容器里。 */
    private final Map<String, Integer> passed = new LinkedHashMap<>();
    /** 整场任务累计确认存进的件数与溜槽抽走的件数（多轮 source 搬运跨轮累加）。 */
    private final Map<String, Integer> totalConfirmed = new LinkedHashMap<>();
    private final Map<String, Integer> totalPassed = new LinkedHashMap<>();
    /** 放过东西的容器（含放进去就被抽走、一件没留下的）。 */
    private final Set<String> putInto = new LinkedHashSet<>();
    /** 每只目标容器存进了什么：容器的叫法 → 物品 → 件数。 */
    private final Map<String, Map<String, Integer>> storedIn = new LinkedHashMap<>();
    /** 每只来源容器搬出了什么：来源容器的叫法 → 物品 → 件数。 */
    private final Map<String, Map<String, Integer>> takenFrom = new LinkedHashMap<>();
    private final List<Problem> openFailures = new ArrayList<>();
    private final List<Problem> sourceOpenFailures = new ArrayList<>();
    private boolean offhandChecked;
    private final Set<String> networkTried = new HashSet<>();
    private Problem networkFailure;
    private NetworkStorage.TerminalSearch terminalSearch;
    private String searchedAt = "";
    private boolean planned;
    private boolean targetInitialized;
    private Place resolvedSourcePlace;
    private Place resolvedTargetPlace;
    private SourceEntry currentSource;
    private boolean sourceLidCleared;
    private SourceContainerTake taking;
    private int sourceRounds;
    private Action prepared;
    private ContainerChooser.Entry current;
    private StoresInContainer storing;
    private StoresInContainer.Parts creatureParts;

    DepositTask(DepositInput input, DepositServices services, BackpackView backpack) {
        super("存东西", input.source() != null ? Phase.SOURCE_CHOOSE : Phase.CHOOSE,
                new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
        this.backpack = backpack;
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SOURCE_CHOOSE -> chooseSources();
            case SOURCE_DIG -> sourceDig(context);
            case SOURCE_TAKE -> sourceTake(context);
            case CHOOSE -> choose();
            case DIG -> dig(context);
            case STORE -> store(context);
            case NEXT -> nextContainer();
            case NETWORK -> network(context);
            case OFFHAND -> switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> Next.go(Phase.CHOOSE, "副手的东西放回背包了");
                case ActionStatus.Failed failed -> {
                    recordAttempt("把副手的东西放回背包", failed.problem().message());
                    yield Next.go(Phase.CHOOSE, "副手的东西没放回去，先存背包里的");
                }
            };
        };
    }

    // 给了 source 时开工先核对两端防呆约束，再把来源容器排进队列，不动副手、也不扫角色原本随身物品。
    private Next<Phase> chooseSources() {
        resolvedSourcePlace = DepositSources.resolve(input.source(), services);
        resolvedTargetPlace = DepositSources.resolve(input.target(), services);
        Optional<Problem> invalid = DepositSources.validateUpfront(
                resolvedSourcePlace, resolvedTargetPlace, input.network(), services, input.permissions());
        if (invalid.isPresent()) {
            return Next.fail(invalid.get());
        }
        boolean goesToNetwork = input.network()
                || DepositSources.terminalInsteadOfContainer(resolvedTargetPlace, services, input.permissions());
        Optional<SourcePick> picked = DepositSources.pickSources(
                resolvedSourcePlace, resolvedTargetPlace, goesToNetwork, (int) input.radius(), mayDigLid(), services);
        if (picked.isEmpty()) {
            recordProgress("正在扫附近 " + input.radius() + " 格内的来源容器");
            return Next.stay();
        }
        return switch (picked.get()) {
            case SourcePick.Chosen chosen -> {
                sourceCandidates.addAll(chosen.ordered());
                yield nextSource();
            }
            case SourcePick.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 挑下一只来源容器（或继续打开上一轮还没搬完的同一只来源容器），盖子压住且允许挖时先清盖子。
    private Next<Phase> nextSource() {
        if (currentSource == null) {
            currentSource = sourceCandidates.poll();
            sourceLidCleared = false;
        }
        if (currentSource == null) {
            return finishAllSourcesChecked();
        }
        if (currentSource.entry().digLidFirst() && !sourceLidCleared) {
            Optional<Action> dig = services.digs() == null ? Optional.empty()
                    : services.digs().dig(sourceLidCell(), input.permissions());
            if (dig.isEmpty()) {
                recordAttempt("清开来源" + sourceName() + "盖子上的方块", "挖不了，换下一只来源容器");
                currentSource = null;
                return nextSource();
            }
            return goWith(Phase.SOURCE_DIG, dig.get(), "先清开来源" + sourceName() + "盖子上的方块");
        }
        return takeFromCurrentSource();
    }

    private Next<Phase> sourceDig(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                sourceLidCleared = true;
                yield takeFromCurrentSource();
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("清开来源" + sourceName() + "盖子上的方块", failed.problem().message());
                currentSource = null;
                yield nextSource();
            }
        };
    }

    // 打开当前来源容器取出一包可中转的物品，受总 count 剩余额度约束。
    private Next<Phase> takeFromCurrentSource() {
        int budget = input.count() == null ? Integer.MAX_VALUE : Math.max(0, input.count() - (int) totalMovedCount());
        if (budget <= 0) {
            return finishSourceDone();
        }
        ContainerChooser.Candidate candidate = currentSource.entry().candidate();
        taking = new SourceContainerTake(
                new KnownContainer(candidate.name(), candidate.at(), candidate.blockTypeId()),
                input.itemIds(), budget, input.permissions(), currentSource.parts(),
                services.tags(), backpack, records());
        return goWith(Phase.SOURCE_TAKE, taking, "走过去从来源" + sourceName() + "取货");
    }

    // 一轮来源取货结束：箱空就换下一只；背包满拿不出就按背包满停手；拿到货就带着这包实数转去存放。
    private Next<Phase> sourceTake(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Failed failed -> {
                taking.openFailure().ifPresent(sourceOpenFailures::add);
                currentSource = null;
                yield nextSource();
            }
            case ActionStatus.Done done -> {
                if (!taking.hadMatchingItems()) {
                    currentSource = null;
                    yield nextSource();
                }
                Map<String, Integer> pulled = taking.pulled();
                if (pulled.isEmpty()) {
                    Problem full = Problem.of(Problem.Kind.INVENTORY_FULL,
                            "角色背包已满，腾不出空位中转搬运来源容器里的物品",
                            "先清空或存掉身上几格东西，再整理来源容器");
                    long already = totalMovedCount();
                    yield already == 0 ? Next.fail(full)
                            : Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                                    "从来源搬出并存进了 " + already + " 件，角色背包已满腾不出空位继续搬剩下的")
                                    .problem(full).build());
                }
                sourceRounds++;
                pulled.forEach((itemId, amount) -> takenFrom
                        .computeIfAbsent(sourceName(), ignored -> new LinkedHashMap<>())
                        .merge(itemId, amount, Integer::sum));
                if (!taking.hasMoreMatchingItems()) {
                    currentSource = null;
                }
                wanted.clear();
                confirmed.clear();
                passed.clear();
                networkTried.clear();
                networkFailure = null;
                openFailures.clear();
                wanted.putAll(pulled);
                yield startStoreRound();
            }
        };
    }

    // 把本轮刚从 source 取进背包的那批货存进 ME 网络或目标容器；上一轮还没满的目标容器直接接着用。
    private Next<Phase> startStoreRound() {
        if (input.network()
                || DepositSources.terminalInsteadOfContainer(resolvedTargetPlace, services, input.permissions())) {
            return terminalSearch == null ? toNetwork(resolvedTargetPlace) : nextToNetwork();
        }
        if (targetInitialized) {
            if (current != null && (storing == null || storing.full().isEmpty())) {
                return storeInCurrent();
            }
            return nextContainer();
        }
        return Next.go(Phase.CHOOSE, "挑存放的目标容器");
    }

    // 挑存放目标容器：未给 source 时先看副手并按背包算存什么；给了 source 时直接把本轮取出的货存进目标处。
    private Next<Phase> choose() {
        if (input.source() == null) {
            if (!offhandChecked) {
                offhandChecked = true;
                Optional<Action> clear = offhandToBackpack();
                if (clear.isPresent()) return goWith(Phase.OFFHAND, clear.get(), "先把副手的东西放回背包");
            }
            if (!planned) {
                plan();
            }
            if (wanted.isEmpty()) {
                return Next.done(TaskResult.done(input.itemIds().isEmpty() ? "身上没有要存的东西"
                        : "身上没有要存的 " + String.join("、", input.itemIds())));
            }
        }
        Place place = resolvedTargetPlace != null ? resolvedTargetPlace
                : DepositSources.resolve(input.target(), services);
        if (input.network() || DepositSources.terminalInsteadOfContainer(place, services, input.permissions())) {
            return toNetwork(place);
        }
        // 显式点名了具体方块坐标、且该格既不是存储容器也不是终端部件时，直接报错指出不是存储容器，不跨格转投隔壁终端。
        if (place instanceof Place.Cell cell && services.spots().at(cell.at()).isEmpty()) {
            return Next.fail(DepositSources.nonContainerTargetProblem(cell.at(), services));
        }
        Optional<ContainerChooser.Pick> pick = pick(place);
        if (pick.isEmpty()) {
            recordProgress("正在扫附近 " + input.radius() + " 格内的容器");
            return Next.stay();
        }
        targetInitialized = true;
        return switch (pick.get()) {
            case ContainerChooser.Pick.Chosen chosen -> {
                candidates.addAll(chosen.ordered());
                yield nextContainer();
            }
            case ContainerChooser.Pick.NeedApproval approval -> Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "只挑得到这些容器：" + String.join("；", concat(approval.others(), approval.blockedLids())),
                    "点名其中的容器，或允许动它们"));
            case ContainerChooser.Pick.NotFound notFound ->
                    Next.fail(Problem.of(Problem.Kind.NOT_FOUND, notFound.searched(), null));
        };
    }

    private Optional<Action> offhandToBackpack() {
        DepositSeams.ClearsOffhand offhand = services.offhand();
        if (offhand == null) return Optional.empty();
        Optional<BackpackStack> held = offhand.held();
        if (held.isEmpty()) return Optional.empty();
        BiPredicate<String, String> taggedIn = services.tags() == null
                ? (itemId, tagId) -> false : (itemId, tagId) -> services.tags().tagsOf(itemId).contains(tagId);
        if (DepositDecider.choose(List.of(held.get()), input.itemIds(), input.count(), taggedIn,
                services.kept()::keep).isEmpty()) {
            return Optional.empty();
        }
        return offhand.intoBackpack();
    }

    private void plan() {
        planned = true;
        BiPredicate<String, String> taggedIn = services.tags() == null
                ? (itemId, tagId) -> false : (itemId, tagId) -> services.tags().tagsOf(itemId).contains(tagId);
        for (DepositDecider.ToDeposit item : DepositDecider.choose(backpack.stacks(), input.itemIds(),
                input.count(), taggedIn, services.kept()::keep)) {
            wanted.merge(item.stack().itemId(), item.amount(), Integer::sum);
        }
    }

    private Optional<ContainerChooser.Pick> pick(Place place) {
        return switch (place) {
            case Place.Area area -> area(area.center());
            case Place.Creature creature -> Optional.of(onCreature(creature.creature()));
            case Place.Cell cell -> Optional.of(named(cell.at()));
            case Place.Missing missing -> Optional.of(new ContainerChooser.Pick.NotFound(missing.why()));
        };
    }

    private Optional<ContainerChooser.Pick> area(BlockPos center) {
        if (center == null) {
            return Optional.of(new ContainerChooser.Pick.NotFound("这一刻角色不在世界里，找不了容器"));
        }
        List<ContainerChooser.Candidate> found = services.spots().around(center, (int) input.radius());
        if (found.isEmpty() && !services.spots().scanComplete()) {
            return Optional.empty();
        }
        // 单箱 source 往附近自动挑箱子搬运时排除那只来源箱自身，避免搬出来又存回原箱。
        if (resolvedSourcePlace instanceof Place.Cell sourceCell) {
            found = found.stream()
                    .filter(candidate -> !DepositSources.sameCell(candidate.at(), sourceCell.at()))
                    .toList();
        }
        return Optional.of(ContainerChooser.choose(found, wanted.keySet().iterator().next(), mayDigLid()));
    }

    private ContainerChooser.Pick onCreature(DepositSeams.CreatureStorage.Creature creature) {
        creatureParts = services.creatures().parts(creature.entityId());
        return new ContainerChooser.Pick.Chosen(List.of(DepositSources.creatureEntry(creature)));
    }

    private ContainerChooser.Pick named(WorldPosition at) {
        return services.spots().at(at)
                .map(found -> ContainerChooser.choose(List.of(new ContainerChooser.Candidate(found.name(),
                        found.blockTypeId(), found.at(), found.knownContents(), found.hasFreeSpace(), found.walkCost(),
                        false, found.layoutKnown(), found.lid(), found.catSitting())),
                        wanted.keySet().iterator().next(), mayDigLid()))
                .orElseGet(() -> new ContainerChooser.Pick.NotFound(
                        "（" + at.x() + ", " + at.y() + ", " + at.z() + "）那里没有容器"));
    }

    private boolean mayDigLid() {
        Permissions.BlockChanges changes = input.permissions().changeBlocks();
        return services.digs() != null
                && (changes == Permissions.BlockChanges.NATURAL || changes == Permissions.BlockChanges.ANY);
    }

    private Next<Phase> toNetwork(Place place) {
        int radius = (int) input.radius();
        switch (place) {
            case Place.Missing missing -> {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, missing.why(), null));
            }
            case Place.Creature creature -> {
                return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "存进网络要找 ME 终端，点名的却是一只生物",
                        "去掉 into=network 就存进它身上"));
            }
            case Place.Cell cell -> {
                terminalSearch = input.network()
                        ? DepositSources.cellSearch(cell.at(), input.permissions())
                        : DepositSources.exactCellSearch(cell.at(), input.permissions());
                searchedAt = "（" + cell.at().x() + ", " + cell.at().y() + ", " + cell.at().z() + "）";
            }
            case Place.Area area when input.target() == null || input.target() instanceof Target.Here ->
                    terminalSearch = NetworkStorage.TerminalSearch.nearMe(radius, input.permissions());
            case Place.Area area -> {
                if (area.center() == null) {
                    return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "这一刻角色不在世界里，找不了终端", null));
                }
                terminalSearch = NetworkStorage.TerminalSearch.around(area.center(), radius, input.permissions());
            }
        }
        return nextToNetwork();
    }

    private Next<Phase> nextToNetwork() {
        Optional<NetworkStorage> storage = services.network().get();
        if (storage.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "没装能存进网络的联动模组（例如 AE2），存不进网络",
                    "改存进箱子（into=container）"));
        }
        List<BackpackStack> batch = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            int left = entry.getValue() - confirmed.getOrDefault(entry.getKey(), 0);
            if (left <= 0 || networkTried.contains(entry.getKey())) continue;
            networkTried.add(entry.getKey());
            batch.add(new BackpackStack(entry.getKey(), left, 64, false, false, false, false));
        }
        if (batch.isEmpty()) return finish();
        return switch (storage.get().store(batch, "存东西", terminalSearch, networkLedger())) {
            case NetworkStorage.TerminalPick.Chosen chosen ->
                    goWith(Phase.NETWORK, chosen.storing(), "把 " + batch.size() + " 样东西存进网络");
            case NetworkStorage.TerminalPick.NotFound notFound -> {
                networkFailure = Problem.of(Problem.Kind.NOT_FOUND, searchedAt + notFound.reason(),
                        "带上绑好访问点的无线终端，或走近 ME 终端、用 target 点名一台再存，或改存进箱子（into=container）");
                yield finish();
            }
        };
    }

    private Next<Phase> network(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> nextToNetwork();
            case ActionStatus.Failed failed -> {
                recordAttempt("存进网络", failed.problem().message());
                networkFailure = failed.problem();
                yield finish();
            }
        };
    }

    private TaskRecords networkLedger() {
        TaskRecords records = records();
        return new TaskRecords() {
            @Override public void change(Change change) {
                if (change.kind() == Change.Kind.ITEM_STORED) {
                    confirmed.merge(change.what(), change.count(), Integer::sum);
                    totalConfirmed.merge(change.what(), change.count(), Integer::sum);
                    storedIn.computeIfAbsent(NETWORK_NAME, ignored -> new LinkedHashMap<>())
                            .merge(change.what(), change.count(), Integer::sum);
                }
                records.change(change);
            }

            @Override public void unconfirmed(Change change) {
                records.unconfirmed(change);
            }

            @Override public void attempt(String tried, String whatHappened) {
                records.attempt(tried, whatHappened);
            }
        };
    }

    private Next<Phase> nextContainer() {
        if (allStored()) {
            return finish();
        }
        current = candidates.poll();
        if (current == null) {
            return finish();
        }
        if (current.digLidFirst()) {
            Optional<Action> dig = services.digs() == null ? Optional.empty()
                    : services.digs().dig(lidCell(), input.permissions());
            if (dig.isEmpty()) {
                recordAttempt("清开" + name() + "盖子上的方块", "挖不了，换下一只");
                return Next.go(Phase.NEXT, "这只的盖子清不开，换下一只");
            }
            return goWith(Phase.DIG, dig.get(), "先清开" + name() + "盖子上的方块");
        }
        return storeInCurrent();
    }

    private Next<Phase> dig(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> storeInCurrent();
            case ActionStatus.Failed failed -> {
                recordAttempt("清开" + name() + "盖子上的方块", failed.problem().message());
                yield Next.go(Phase.NEXT, "这只的盖子清不开，换下一只");
            }
        };
    }

    private Next<Phase> storeInCurrent() {
        ContainerChooser.Candidate candidate = current.candidate();
        storing = new StoresInContainer(new KnownContainer(candidate.name(), candidate.at(), candidate.blockTypeId()),
                List.copyOf(wanted.keySet()), ledger(), input.permissions(),
                creatureParts != null ? creatureParts : services.storing());
        return goWith(Phase.STORE, storing, "走过去点开" + name());
    }

    private Next<Phase> store(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> allStored() ? finish() : Next.go(Phase.NEXT, name() + "存完了");
            case ActionStatus.Failed failed -> {
                storing.openFailure().ifPresent(openFailures::add);
                yield Next.go(Phase.NEXT, "这只存不进去，换下一只");
            }
        };
    }

    private Next<Phase> finish() {
        if (input.source() != null) {
            return finishSourceRound();
        }
        long deposited = sumValues(confirmed);
        long drained = sumValues(passed);
        long total = sumValues(wanted);
        String drainedNote = drained == 0 ? "" : "；另有 " + drained + " 件放进去就被下面的溜槽或漏斗抽走了，没留在容器里";
        if (deposited + drained >= total) {
            Set<String> touched = new LinkedHashSet<>(storedIn.keySet());
            touched.addAll(putInto);
            return Next.done(TaskResult.done(drained == 0
                    ? "存进了" + whereStored() + "，要存的都存进去了"
                    : "放进了 " + touched.size() + " 只容器，要存的都离开了背包" + drainedNote));
        }
        deposited += drained;
        Problem why = networkFailure != null ? networkFailure
                : deposited == 0 && !openFailures.isEmpty() ? openFailures.getFirst()
                : Problem.of(Problem.Kind.NOT_FOUND, "附近的容器都满了，或都开不了", "走远一点再存，或点名一只有空位的容器");
        if (deposited == 0) {
            return Next.fail(why);
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                "存进了 " + deposited + " 件，还有 " + (total - deposited) + " 件没存下").problem(why).build());
    }

    // 带 source 的一轮转存收尾：这包全存进去了且还没搬完就回头拿下一包；目标满了没存完就立即停手如实交代余量。
    private Next<Phase> finishSourceRound() {
        long totalDone = totalMovedCount();
        if (!allStored()) {
            long roundLeft = sumValues(wanted) - sumValues(confirmed) - sumValues(passed);
            Problem why = networkFailure != null ? networkFailure
                    : totalDone == 0 && !openFailures.isEmpty() ? openFailures.getFirst()
                    : Problem.of(Problem.Kind.NOT_FOUND, "目标容器都满了，或开不了",
                            "换一只有空位的目标容器，或改用 into=\"network\" 存进 ME 网络");
            if (totalDone == 0) {
                return Next.fail(why);
            }
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                    "存进了 " + totalDone + " 件，还有 " + roundLeft + " 件留在背包里没存下").problem(why).build());
        }
        if (input.count() != null && totalDone >= input.count()) {
            return finishSourceDone();
        }
        if (currentSource != null || !sourceCandidates.isEmpty()) {
            if (sourceRounds >= MAX_SOURCE_ROUNDS) {
                return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        "已分 " + sourceRounds + " 轮搬出并存进 " + totalDone + " 件，来源容器里还有未搬完的物品")
                        .problem(Problem.of(Problem.Kind.STUCK,
                                "单次整理搬运已达 " + MAX_SOURCE_ROUNDS + " 轮上限",
                                "再执行一次 deposit 继续搬剩下的"))
                        .build());
            }
            return nextSource();
        }
        return finishAllSourcesChecked();
    }

    private Next<Phase> finishAllSourcesChecked() {
        long totalDone = totalMovedCount();
        if (totalDone == 0) {
            if (!sourceOpenFailures.isEmpty()) {
                return Next.fail(sourceOpenFailures.getFirst());
            }
            return Next.done(TaskResult.done(input.itemIds().isEmpty()
                    ? "来源容器里没有要搬的东西"
                    : "来源容器里没有要搬的 " + String.join("、", input.itemIds())));
        }
        if (!sourceOpenFailures.isEmpty()) {
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                    "从 " + takenFrom.size() + " 只来源容器搬出并存进了 " + totalDone + " 件，另有来源容器打不开")
                    .problem(sourceOpenFailures.getFirst()).build());
        }
        return finishSourceDone();
    }

    private Next<Phase> finishSourceDone() {
        long deposited = sumValues(totalConfirmed);
        long drained = sumValues(totalPassed);
        String drainedNote = drained == 0 ? "" : "；另有 " + drained + " 件放进去就被下面的溜槽或漏斗抽走了，没留在容器里";
        return Next.done(TaskResult.done("从 " + takenFrom.size() + " 只来源容器搬出 " + (deposited + drained)
                + " 件，存进了" + whereStored() + "，要搬的都存进去了" + drainedNote));
    }

    private String whereStored() {
        boolean network = storedIn.containsKey(NETWORK_NAME);
        int containers = storedIn.size() - (network ? 1 : 0);
        if (network && containers == 0) return " ME 网络";
        return " " + containers + " 只容器" + (network ? "和 ME 网络" : "");
    }

    private StoresInContainer.Ledger ledger() {
        return new StoresInContainer.Ledger() {
            @Override public int left(String itemId) {
                return wanted.getOrDefault(itemId, 0) - confirmed.getOrDefault(itemId, 0) - passed.getOrDefault(itemId, 0);
            }

            @Override public void passedThrough(String itemId, int amount, String fact) {
                passed.merge(itemId, amount, Integer::sum);
                totalPassed.merge(itemId, amount, Integer::sum);
                putInto.add(name());
                recordUnconfirmed(new Change(Change.Kind.OTHER, "搬运", amount, fact));
            }

            @Override public void stored(String itemId, int amount) {
                confirmed.merge(itemId, amount, Integer::sum);
                totalConfirmed.merge(itemId, amount, Integer::sum);
                storedIn.computeIfAbsent(name(), ignored -> new LinkedHashMap<>()).merge(itemId, amount, Integer::sum);
                recordChange(new Change(Change.Kind.ITEM_STORED, itemId, amount, "存进了" + name()));
            }

            @Override public void unconfirmed(String fact) {
                recordUnconfirmed(new Change(Change.Kind.OTHER, "搬运", 1, fact));
            }

            @Override public void attempt(String tried, String whatHappened) {
                recordAttempt(tried, whatHappened);
            }
        };
    }

    private boolean allStored() {
        return wanted.entrySet().stream()
                .allMatch(entry -> confirmed.getOrDefault(entry.getKey(), 0)
                        + passed.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private long totalMovedCount() {
        return sumValues(totalConfirmed) + sumValues(totalPassed);
    }

    private static long sumValues(Map<String, Integer> map) {
        return map.values().stream().mapToLong(Integer::longValue).sum();
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
    }

    private String name() {
        return current.candidate().name();
    }

    private String sourceName() {
        return currentSource.entry().candidate().name();
    }

    private BlockPos lidCell() {
        return DepositSources.cellOf(current.candidate().at()).above();
    }

    private BlockPos sourceLidCell() {
        return DepositSources.cellOf(currentSource.entry().candidate().at()).above();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Override protected ResultDetails details() {
        return new DepositDetails(formatLedger(storedIn), takenFrom.isEmpty() ? null : formatLedger(takenFrom));
    }

    private static List<String> formatLedger(Map<String, Map<String, Integer>> ledger) {
        List<String> lines = new ArrayList<>();
        ledger.forEach((container, items) -> {
            List<String> parts = new ArrayList<>();
            items.forEach((itemId, amount) -> parts.add(itemId + " ×" + amount));
            lines.add(container + "：" + String.join("、", parts));
        });
        return lines;
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>();
        wanted.forEach((itemId, amount) -> {
            int missing = amount - confirmed.getOrDefault(itemId, 0) - passed.getOrDefault(itemId, 0);
            if (missing > 0) left.add(itemId + " 还有 " + missing + " 件没存下");
        });
        return left;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case SOURCE_CHOOSE -> "挑来源容器";
            case SOURCE_DIG -> "清开来源容器盖子上的方块";
            case SOURCE_TAKE -> "从来源容器取货";
            case CHOOSE -> "挑容器";
            case DIG -> "清开盖子上的方块";
            case STORE -> "走过去点开容器、搬进去、关上";
            case NEXT -> "换下一只容器";
            case NETWORK -> "打开 ME 终端存进网络";
            case OFFHAND -> "把副手的东西放回背包";
        };
    }
}
