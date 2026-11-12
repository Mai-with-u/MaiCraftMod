// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;

/**
 * 容器的来源：眼前这一片找得到的容器（现场扫描与世界记忆并成一份候选），按开没开过分三路报价。
 * 开过、记了内容的报明确的数；只看见过、没开过的报"不知道有多少"；开过、记得里面没有的，
 * 就在跟前时也再给一次机会——记忆只是线索，刚装进去的货、别人顺手放的东西都要到现场开了才算数。
 * 到了跟前以现场为准，开了才算数；用哪只箱子、里面实际有多少，由开箱取物的执行接缝到现场核对并如实回报。
 *
 * <p>谁放的箱子都能翻（归属不拦），只有额外保护的地标旁与记住的区域里的不碰。
 * 跳过的箱子在说明里按原因合并计数，说清是地标还是区域挡的。
 * 请求带着饿急通报（饿到危险线弄口粮）时，挑中的箱子若不是角色自己放的，绑上通报交给开箱：
 * 只拿通报点名的那一样口粮、刚好够的件数，拿到了由通报发事件让宿主知道。
 *
 * <p>一只箱子这一次拿东西里走不到跟前，记进这一次的"走不到的地方"：后面的原料不再拿去问它，
 * 只记下又问到了它，失败说明里由引擎合成一句。
 */
public final class ContainerSource implements ItemSource {

    /** 找容器的半径：再远就不算"顺手能拿"，宁可自己去做。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;

    private final SpotsContainers spots;
    private final ReadsItemTags tags;
    private final Protection protection;
    private final ContainerTakes takes;
    private final Predicate<String> naturalLoot;

    /** 生产与离线测试都用这一种：归属是谁放的这个事实由保护判断当场给。 */
    public ContainerSource(SpotsContainers spots, ReadsItemTags tags, Protection protection, ContainerTakes takes) {
        this(spots, tags, protection, takes, blockId -> blockId != null && blockId.startsWith("lootr:"));
    }

    /** 生产接入方块标签读端：按游戏数据认自然战利品容器，自动取材时与自家、无记录容器一起排在别人放的容器前面。 */
    public ContainerSource(SpotsContainers spots, ReadsItemTags tags, Protection protection, ContainerTakes takes,
            Predicate<String> naturalLoot) {
        this.spots = spots;
        this.tags = tags;
        this.protection = protection;
        this.takes = takes;
        this.naturalLoot = naturalLoot == null ? id -> false : naturalLoot;
    }

    /** 记得没有、但离这么近（格）的箱子再开一眼：几步路的事，记忆可能过时。 */
    static final double RECHECK_NEAR_BLOCKS = 8;

    /**
     * 落差比这大（格）的箱子按难走到算：脚边几格的地下室不算，深坑、深矿里的算。
     * 走路代价的来路是直线距离（找容器的读端按坐标直线算，没有走过没走过的路线记忆可用），
     * 落差大的箱子真走过去多半要下矿、绕洞，直线距离把它算得太近，报价就把落差再折一遍进代价。
     * 只降排序不划掉：深处的箱子可能经洞穴可达，只是不优先；真没别的来路时它照旧报价，
     * 到不了跟前由引擎按现场如实收场再换路。
     */
    static final double DEEP_DROP_BLOCKS = 24;

    @Override public String describe() {
        return "记得的箱子";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CONTAINER;
    }

    /** 一拨候选箱子里挑出来的头名：记得有货的、没开过可能有的、跟前顺手重看的。 */
    private static final class TierPick {
        ContainerChooser.Candidate known;
        ContainerChooser.Candidate maybe;
        boolean maybeUnsureKind;
        ContainerChooser.Candidate stale;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        // 候选由找容器的读端给出：现场扫到的（刚摆下、记忆里还没有的箱子也在内）与记过的并成一份，近的在前。
        // 自动取材先翻自己放的、自然战利品容器与无放置记录的，别人放的排最后，只有前面几类都没有要的东西时才去翻。
        List<ContainerChooser.Candidate> candidates =
                spots.around(cellOf(context.characterAt()), searchRadius(context));
        TierPick preferred = new TierPick();
        TierPick others = new TierPick();
        // 挡住的箱子按是哪一路合并计数：几只同一个原因只说一次带只数。
        Map<StorageUse, Integer> skipped = new LinkedHashMap<>();
        boolean skippedUnreachable = false;
        int considered = 0;
        UnreachablePlaces unreachable = context.unreachable();
        for (ContainerChooser.Candidate candidate : candidates) {
            // 这一次里已经走不到跟前的箱子：不再去问，只记一笔又问到了它。
            if (unreachable != null && unreachable.skips(candidate.at(), request.wanted().describe())) {
                skippedUnreachable = true;
                continue;
            }
            // 额外保护的地标旁、记住的区域里的不翻：记得里面有货也跳过，只在说明里交代是哪一路挡的。
            // 这次任务额外保护的地标读端不知道，在这里按任务许可再问一遍保护判断。
            StorageUse use = storageUse(candidate, context);
            if (!use.usable()) {
                skipped.merge(use, 1, Integer::sum);
                continue;
            }
            considered++;
            TierPick tier = placedByOtherPlayer(candidate) ? others : preferred;
            if (tier.known == null && candidate.knownContents() != null && holdsWanted(candidate, request)) {
                tier.known = candidate;
            }
            // 点名了药水种类、记得这只箱子里有这种药水瓶却没记下是哪种（认种类以前记的）：
            // 和没开过的一样，走过去打开看了再拿，不当成没有。
            boolean unsureKind = candidate.knownContents() != null && !holdsWanted(candidate, request)
                    && kindNotRecorded(candidate, request);
            if (tier.maybe == null && (candidate.knownContents() == null || unsureKind)) {
                tier.maybe = candidate;
                tier.maybeUnsureKind = unsureKind;
            }
            // 开过、记得里面没有，但就在跟前：记忆可能过时（主播刚往里放了东西），真人会顺手再开一眼。
            if (tier.stale == null && candidate.knownContents() != null && !holdsWanted(candidate, request)
                    && !unsureKind && candidate.walkCost() <= RECHECK_NEAR_BLOCKS) {
                tier.stale = candidate;
            }
        }
        SourceQuote.Offer firstChoice = knownOrMaybeOffer(preferred, request, context);
        if (firstChoice != null) return firstChoice;
        SourceQuote.Offer othersChoice = knownOrMaybeOffer(others, request, context);
        if (othersChoice != null) return othersChoice;
        ContainerChooser.Candidate stale = preferred.stale != null ? preferred.stale : others.stale;
        if (stale != null) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(stale.walkCost() + RECHECK_NEAR_BLOCKS, 4),
                    "上回开的时候没有，就在跟前，再开一眼看看有没有新放进去的", positionHint(stale));
        }
        // 附近的容器还没扫完时不冒充没有：交回"再问"，引擎等一会儿重新问价，等太久才如实收场。
        if (!spots.scanComplete()) {
            return new SourceQuote.NotYet(describe(), "附近的容器还没扫完，扫完再答有没有");
        }
        return nothingHere(request, skipped, skippedUnreachable && considered == 0);
    }

    // 这一拨候选里有记得装着的或没开过的，就按先确定、后未开过的顺序给出报价；都没有回 null，轮到下一拨。
    private SourceQuote.Offer knownOrMaybeOffer(TierPick tier, ItemRequest request, SourceContext context) {
        if (tier.known != null) {
            int count = countWanted(tier.known, request);
            if (count > 0) {
                return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                        new AcquisitionCost(reachableCost(tier.known, context), 4),
                        depthNote(tier.known, context, "开箱时以现场为准"), positionHint(tier.known));
            }
        }
        if (tier.maybe != null) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(reachableCost(tier.maybe, context), 4),
                    depthNote(tier.maybe, context, tier.maybeUnsureKind
                            ? "记得这只箱子里有" + request.wanted().itemId() + "，没记下是哪种药水，打开看了再拿"
                            : "这只箱子只是看见过，没开过，不确定里面有什么"), positionHint(tier.maybe));
        }
        return null;
    }

    // 自然战利品容器与自家、无记录容器都不算别人放的；只有归属明确写着其他玩家放的非战利品容器才排最后。
    private boolean placedByOtherPlayer(ContainerChooser.Candidate candidate) {
        return !naturalLoot.test(candidate.blockTypeId())
                && protection.placedByAnotherPlayer(candidate.at());
    }

    // 哪只都拿不了：能翻的都是这一次走不到跟前的，不另说一条原因，引擎在最外层合成一句说；
    // 否则说记得的容器里没有，挡住的箱子按原因合并计数接在后面。
    private SourceQuote.Unavailable nothingHere(ItemRequest request, Map<StorageUse, Integer> skipped,
            boolean onlyUnreachable) {
        if (onlyUnreachable && skipped.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), UnreachablePlaces.SKIPPED);
        }
        return new SourceQuote.Unavailable(describe(),
                "记得的容器里没有装着" + request.wanted().describe() + "的" + skippedText(skipped));
    }

    /**
     * 挡住的箱子怎么说："，能用的都给挡住了：2 只在这次额外保护的地标「家」旁、1 只在记住的区域「河东的农场」里"；
     * 同一个原因的只说一次带只数，长度只随原因数增长，不随箱子数增长。没挡住的给空串。
     */
    private String skippedText(Map<StorageUse, Integer> skipped) {
        if (skipped.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        skipped.forEach((use, count) -> parts.add(count + " 只" + use.describe()));
        return "，能用的都给挡住了：" + String.join("、", parts);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        WorldPosition hinted = parsePosition(offer.hint());
        // 到了跟前再核对该报价认的那只箱子。快查没对上不等于箱子没了：记忆与扫描索引可能一时没并齐，
        // 重读一遍现场候选（按报价认的那格对），对上了照常去开——记忆只是线索，箱里实际有什么开了才算数。
        Optional<ContainerChooser.Candidate> found = spots.at(hinted);
        if (found.isEmpty()) {
            found = spots.around(cellOf(context.characterAt()), searchRadius(context)).stream()
                    .filter(candidate -> MemoryRecord.sameSpot(candidate.at(), hinted))
                    .findFirst();
        }
        if (found.isEmpty()) {
            // 重读后那一格确实什么都没有（被拆走、记忆指错）：交回空，由引擎换路。
            return Optional.empty();
        }
        ContainerChooser.Candidate container = found.get();
        // 报价之后它被圈进了区域或点了额外保护的地标：不翻，交回空让引擎换路。
        StorageUse use = storageUse(container, context);
        if (!use.usable()) {
            return Optional.empty();
        }
        // 饿急通报（饿到危险线弄口粮）：这一只不是角色自己放的，就绑上通报交给开箱——
        // 只拿通报点名的那一样口粮、刚好够的件数，拿到了发事件让宿主知道是谁家的。
        // 自己放的、归属还没问到的都不绑：前者是自己的东西，后者是谁的还说不清。
        ItemRequest want = request;
        if (want.foodReport().open()) {
            String ownerName = protection.placedByOther(container.at()).map(protection::nameOf).orElse(null);
            if (ownerName != null || !protection.ownershipKnown(container.at())) {
                want = want.withFoodReport(want.foodReport().at(container.at(), ownerName));
            }
        }
        List<ItemRequest> wants = new ArrayList<>();
        if (want.foodReport().bound()) {
            // 绑了通报：开箱只拿通报点名的那一样、刚好够的件数；顺路的别样一件不拿。
            wants.add(want);
        } else {
            // 开都开了：把这一趟还要的另外几样一起带上，箱里有的就顺手拿了，不再为每样各开一次。
            wants.add(want);
            for (ItemRequest extra : context.alsoWanted()) {
                if (!extra.wanted().specifier().equals(want.wanted().specifier())) wants.add(extra);
            }
        }
        boolean wasUnopened = container.knownContents() == null;
        KnownContainer known = new KnownContainer(container.name(), container.at(), container.blockTypeId());
        Optional<Action> take = takes.take(known, wants, context.permissions());
        if (wasUnopened) {
            // 没开过的箱子（例如优先去翻的战利品箱）打开一看里面没有要的东西：记忆已更新，按单处未出货交回，引擎好接着试下一只箱子。
            take = take.map(action -> new UnopenedProbe(action, container, request));
        }
        UnreachablePlaces unreachable = context.unreachable();
        if (unreachable == null) return take;
        WorldPosition at = container.at();
        String label = describe() + " " + at.x() + ", " + at.y() + ", " + at.z();
        return take.map(action -> new NotesUnreachable(action, unreachable, at, label, request.wanted().describe()));
    }

    /** 没开过的箱子开完已经写进记忆却没有要的东西时，按单点落空交回，不把其余还没试的箱子一棍子划掉。 */
    private final class UnopenedProbe implements Action, ReportsUnconfirmed, FailedOnlyAtSpots {
        private final Action take;
        private final ContainerChooser.Candidate probed;
        private final ItemRequest request;
        private boolean emptySpot;

        UnopenedProbe(Action take, ContainerChooser.Candidate probed, ItemRequest request) {
            this.take = take;
            this.probed = probed;
            this.request = request;
        }

        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = take.tick(context);
            if (status instanceof ActionStatus.Done) {
                Optional<ContainerChooser.Candidate> updated = spots.at(probed.at());
                if (updated.isPresent() && updated.get().knownContents() != null
                        && !holdsWanted(updated.get(), request)) {
                    emptySpot = true;
                    return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND,
                            probed.name() + " 打开看了，里面没有" + request.wanted().describe()));
                }
            }
            return status;
        }

        @Override public boolean onlyThoseSpotsFailed() { return emptySpot; }
        @Override public List<String> unconfirmedFacts() {
            return take instanceof ReportsUnconfirmed reporting ? reporting.unconfirmedFacts() : List.of();
        }
        @Override public void pause() { take.pause(); }
        @Override public void close() { take.close(); }
        @Override public boolean stopSafely() { return take.stopSafely(); }
        @Override public boolean stoppedSafely() { return take.stoppedSafely(); }
        @Override public Interruptibility interruptibility() { return take.interruptibility(); }
        @Override public Interruptibility interruptibility(TickContext context) { return take.interruptibility(context); }
        @Override public String describe() { return take.describe(); }
    }

    /**
     * 开箱取货的动作套一层：靠近收场为到不了时，把这只箱子连同经过记进这一次的"走不到的地方"。
     * 别的照原样转给里面那一个，没能确认的交互照旧交回。
     */
    private record NotesUnreachable(Action take, UnreachablePlaces unreachable, WorldPosition at, String label,
            String asking) implements Action, ReportsUnconfirmed, FailedOnlyAtSpots {

        @Override public boolean onlyThoseSpotsFailed() {
            return take instanceof FailedOnlyAtSpots spots && spots.onlyThoseSpotsFailed();
        }

        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = take.tick(context);
            if (status instanceof ActionStatus.Failed failed && failed.problem().kind() == Problem.Kind.UNREACHABLE) {
                unreachable.note(at, label, asking, failed.problem().message());
            }
            return status;
        }

        @Override public List<String> unconfirmedFacts() {
            return take instanceof ReportsUnconfirmed reporting ? reporting.unconfirmedFacts() : List.of();
        }

        @Override public void pause() { take.pause(); }
        @Override public void close() { take.close(); }
        @Override public boolean stopSafely() { return take.stopSafely(); }
        @Override public boolean stoppedSafely() { return take.stoppedSafely(); }
        @Override public Interruptibility interruptibility() { return take.interruptibility(); }
        @Override public Interruptibility interruptibility(TickContext context) { return take.interruptibility(context); }
        @Override public String describe() { return take.describe(); }
    }

    /**
     * 这只箱子能不能翻：只有额外保护的地标旁、记住的区域里的不翻；谁放的都能翻（归属不拦）。
     * 不能翻时连同是哪一路挡的一起交回，说明里照它说。
     */
    private StorageUse storageUse(ContainerChooser.Candidate candidate, SourceContext context) {
        return protection.storageUse(candidate.at(), candidate.blockTypeId(),
                context.permissions().protectedLandmarks());
    }

    /** 这次搜多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    // 走过去的代价按到得了与否修正：直线距离之外，落差大的把落差再折一遍（见 DEEP_DROP_BLOCKS 的说明）。
    private static double reachableCost(ContainerChooser.Candidate candidate, SourceContext context) {
        return candidate.walkCost() + depthPenalty(candidate, context);
    }

    // 深度落差：同一个维度里才比（维度没记的按同一个算）；头顶上的和脚下的同样难走。
    private static double depthPenalty(ContainerChooser.Candidate candidate, SourceContext context) {
        WorldPosition at = candidate.at();
        WorldPosition here = context.characterAt();
        if (at.dimension() != null && here.dimension() != null && !at.dimension().equals(here.dimension())) {
            return 0;
        }
        double drop = Math.abs(at.y() - here.y());
        return drop <= DEEP_DROP_BLOCKS ? 0 : drop;
    }

    // 落差大的箱子在风险说明里交代一句：LLM 看得见为什么这只箱子排在了后面。
    private static String depthNote(ContainerChooser.Candidate candidate, SourceContext context, String original) {
        double penalty = depthPenalty(candidate, context);
        if (penalty <= 0) return original;
        WorldPosition at = candidate.at();
        String where = at.y() > context.characterAt().y() ? "头顶上" : "脚下";
        return original + "（在" + where + " " + (int) penalty + " 格深处，"
                + "没走过这条路线，要绕的路可能远得多）";
    }

    /** 开过、且记下的内容里有想要的东西的容器。 */
    private boolean holdsWanted(ContainerChooser.Candidate candidate, ItemRequest request) {
        return countWanted(candidate, request) > 0;
    }

    /** 点名了药水种类，记得的内容里有这种药水瓶、却只记了物品 ID 没记种类：说不准是不是要的那种。 */
    private static boolean kindNotRecorded(ContainerChooser.Candidate candidate, ItemRequest request) {
        if (request.wanted().potion() == null) return false;
        for (String written : candidate.knownContents()) {
            StackKey seen = StackKey.parse(written);
            if (seen.potion() == null && seen.itemId().equals(request.wanted().itemId())) return true;
        }
        return false;
    }

    private int countWanted(ContainerChooser.Candidate candidate, ItemRequest request) {
        int total = 0;
        for (String itemId : candidate.knownContents()) {
            if (request.wanted().matches(itemId, tags.tagsOf(itemId))) {
                total++;
            }
        }
        return total;
    }

    private static BlockPos cellOf(WorldPosition at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }

    /** 报价认位置的写法：引擎不解读，动手时原样带回去查现场与记忆。 */
    private static String positionHint(ContainerChooser.Candidate candidate) {
        WorldPosition at = candidate.at();
        return at.x() + "," + at.y() + "," + at.z() + (at.dimension() == null ? "" : "@" + at.dimension());
    }

    private static WorldPosition parsePosition(String hint) {
        String[] parts = hint.split("@")[0].split(",");
        String dimension = hint.contains("@") ? hint.split("@")[1] : null;
        return new WorldPosition(
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), dimension);
    }
}
