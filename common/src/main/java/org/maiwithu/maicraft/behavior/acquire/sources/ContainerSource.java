// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
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
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
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
 * <p>别人的箱子不碰：别人放的、在玩家地盘里的、这次任务额外保护的地标旁的，归属还没问清的也算，
 * 记得里面有货也不去翻——默认绝不碰玩家的箱子。自己放的、自家人（所有者信任的玩家）放的照常翻。
 * 跳过的箱子在说明里按原因合并计数，说清记在谁名下或是哪一路挡的，以及自家人怎么写进实例配置。
 * 唯一的例外是请求带着别人的箱子放行（饿到危险线弄口粮）：能用的箱子都没有这样东西时，才报最近一只
 * 有货或没开过的别人的箱子，开箱时只拿放行的那一样、刚好够的件数，额外保护的地标旁照旧不碰。
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
    /** 玩家编号换成名字：跳过别人的箱子时写记在谁名下，在线写名字，不在线写编号。 */
    private final Function<String, String> ownerNames;

    /** 玩家编号原样写：离线测试与读不到玩家列表时用。 */
    public ContainerSource(SpotsContainers spots, ReadsItemTags tags, Protection protection, ContainerTakes takes) {
        this(spots, tags, protection, takes, Function.identity());
    }

    /** @param ownerNames 玩家编号换成名字，跳过别人的箱子时写记在谁名下 */
    public ContainerSource(SpotsContainers spots, ReadsItemTags tags, Protection protection, ContainerTakes takes,
            Function<String, String> ownerNames) {
        this.spots = spots;
        this.tags = tags;
        this.protection = protection;
        this.takes = takes;
        this.ownerNames = ownerNames;
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

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        // 候选由找容器的读端给出：现场扫到的（刚摆下、记忆里还没有的箱子也在内）与记过的并成一份，近的在前。
        List<ContainerChooser.Candidate> candidates =
                spots.around(cellOf(context.characterAt()), searchRadius(context));
        ContainerChooser.Candidate known = null;
        ContainerChooser.Candidate maybe = null;
        boolean maybeUnsureKind = false;
        ContainerChooser.Candidate stale = null;
        // 跳过的别人的箱子按为什么不能用、记在谁名下合并计数：几只同一个原因只说一次带只数。
        Map<StorageUse, Integer> skippedOthers = new LinkedHashMap<>();
        // 带着别人的箱子放行时，最近一只有货或没开过的别人的箱子：能用的箱子都没有时才报它。
        ContainerChooser.Candidate others = null;
        StorageUse othersUse = null;
        boolean skippedUnreachable = false;
        int considered = 0;
        UnreachablePlaces unreachable = context.unreachable();
        for (ContainerChooser.Candidate candidate : candidates) {
            // 这一次里已经走不到跟前的箱子：不再去问，只记一笔又问到了它。
            if (unreachable != null && unreachable.skips(candidate.at(), request.wanted().describe())) {
                skippedUnreachable = true;
                continue;
            }
            // 别人的箱子不翻：记得里面有货也跳过，只在说明里交代是谁的；自家人的照常翻。
            // 这次任务额外保护的地标读端不知道，在这里按任务许可再问一遍保护判断。
            StorageUse use = storageUse(candidate, context);
            if (!use.usable()) {
                if (others == null && othersWorthTrying(candidate, use, request)) {
                    others = candidate;
                    othersUse = use;
                }
                skippedOthers.merge(use, 1, Integer::sum);
                continue;
            }
            considered++;
            if (known == null && candidate.knownContents() != null && holdsWanted(candidate, request)) {
                known = candidate;
            }
            // 点名了药水种类、记得这只箱子里有这种药水瓶却没记下是哪种（认种类以前记的）：
            // 和没开过的一样，走过去打开看了再拿，不当成没有。
            boolean unsureKind = candidate.knownContents() != null && !holdsWanted(candidate, request)
                    && kindNotRecorded(candidate, request);
            if (maybe == null && (candidate.knownContents() == null || unsureKind)) {
                maybe = candidate;
                maybeUnsureKind = unsureKind;
            }
            // 开过、记得里面没有，但就在跟前：记忆可能过时（主播刚往里放了东西），真人会顺手再开一眼。
            if (stale == null && candidate.knownContents() != null && !holdsWanted(candidate, request) && !unsureKind
                    && candidate.walkCost() <= RECHECK_NEAR_BLOCKS) {
                stale = candidate;
            }
        }
        if (known != null) {
            int count = countWanted(known, request);
            if (count > 0) {
                return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                        new AcquisitionCost(reachableCost(known, context), 4),
                        depthNote(known, context, "开箱时以现场为准"), positionHint(known));
            }
        }
        if (maybe != null) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(reachableCost(maybe, context), 4),
                    depthNote(maybe, context, maybeUnsureKind
                            ? "记得这只箱子里有" + request.wanted().itemId() + "，没记下是哪种药水，打开看了再拿"
                            : "这只箱子只是看见过，没开过，不确定里面有什么"), positionHint(maybe));
        }
        if (stale != null) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(stale.walkCost() + RECHECK_NEAR_BLOCKS, 4),
                    "上回开的时候没有，就在跟前，再开一眼看看有没有新放进去的", positionHint(stale));
        }
        // 附近的容器还没扫完时不冒充没有：交回"再问"，引擎等一会儿重新问价，等太久才如实收场。
        if (!spots.scanComplete()) {
            return new SourceQuote.NotYet(describe(), "附近的容器还没扫完，扫完再答有没有");
        }
        // 能用的箱子里都没有，又带着别人的箱子放行（饿急了弄口粮）：报最近那只别人的箱子，说清是谁的。
        if (others != null) return othersOffer(others, othersUse, request, context);
        return nothingHere(request, skippedOthers, skippedUnreachable && considered == 0);
    }

    // 哪只都拿不了：能翻的都是这一次走不到跟前的，不另说一条原因，引擎在最外层合成一句说；
    // 否则说记得的容器里没有，跳过的别人的箱子按原因合并计数接在后面。
    private SourceQuote.Unavailable nothingHere(ItemRequest request, Map<StorageUse, Integer> skippedOthers,
            boolean onlyUnreachable) {
        if (onlyUnreachable && skippedOthers.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), UnreachablePlaces.SKIPPED);
        }
        return new SourceQuote.Unavailable(describe(),
                "记得的容器里没有装着" + request.wanted().describe() + "的" + skippedText(skippedOthers));
    }

    /**
     * 跳过的别人的箱子怎么说："，别人的箱子不翻：2 只记在 bob 名下、1 只不知道是谁的（按别人的算）"；
     * 同一个原因、同一个人的只说一次带只数，长度只随原因与人数增长，不随箱子数增长。
     * 有记在别人名下的，再说一次怎么把自家人写进实例配置；没跳过的给空串。
     */
    private String skippedText(Map<StorageUse, Integer> skipped) {
        if (skipped.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        skipped.forEach((use, count) -> parts.add(count + " 只" + use.describe(ownerNames)));
        // 只有记在别人名下的那几只，写进自家人名单就能拿；地标、地盘、归属没问到的写了也不管用，不教。
        boolean recorded = skipped.keySet().stream().anyMatch(use -> use.kind() == StorageUse.Kind.PLACED_BY_OTHER);
        String howToAllow = recorded ? "；放的人是自家人就写进" + TrustedPlayers.LISTED_IN + "，写了就能拿" : "";
        return "，别人的箱子不翻：" + String.join("、", parts) + howToAllow;
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
        // 报价之后才知道它是玩家的（归属刚问到）：同样不翻，交回空让引擎换路；带着放行、能拿吃的除外。
        StorageUse use = storageUse(container, context);
        if (!use.usable() && !takableUnderAllowance(use, request)) {
            return Optional.empty();
        }
        KnownContainer known = new KnownContainer(
                container.name(), container.at(), container.blockTypeId());
        List<ItemRequest> wants = new ArrayList<>();
        if (!use.usable()) {
            // 别人的箱子：放行绑到这一只上，开箱时只拿放行的那一样、刚好够的件数，拿到了报回去；顺路的别样一件不拿。
            wants.add(request.withOthersStorage(request.othersStorage().at(use, container.at())));
        } else {
            // 开都开了：把这一趟还要的另外几样一起带上，箱里有的就顺手拿了，不再为每样各开一次。
            wants.add(request);
            for (ItemRequest extra : context.alsoWanted()) {
                if (!extra.wanted().specifier().equals(request.wanted().specifier())) wants.add(extra);
            }
        }
        Optional<Action> take = takes.take(known, wants, context.permissions());
        UnreachablePlaces unreachable = context.unreachable();
        if (unreachable == null) return take;
        WorldPosition at = container.at();
        String label = describe() + " " + at.x() + ", " + at.y() + ", " + at.z();
        return take.map(action -> new NotesUnreachable(action, unreachable, at, label, request.wanted().describe()));
    }

    /**
     * 开箱取货的动作套一层：靠近收场为到不了时，把这只箱子连同经过记进这一次的"走不到的地方"。
     * 别的照原样转给里面那一个，没能确认的交互照旧交回。
     */
    private record NotesUnreachable(Action take, UnreachablePlaces unreachable, WorldPosition at, String label,
            String asking) implements Action, ReportsUnconfirmed {

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
     * 这只箱子能不能翻：自己或自家人放的、野外无主的能翻；别人的、在玩家地盘里的、额外保护的、拿不准的不翻。
     * 不能翻时连同是哪一路挡的、记在谁名下一起交回，说明里照它说。
     */
    private StorageUse storageUse(ContainerChooser.Candidate candidate, SourceContext context) {
        return protection.storageUse(candidate.at(), candidate.blockTypeId(), context.permissions().protectedLandmarks());
    }

    /** 带着放行时值不值得去翻这只别人的箱子：能拿，而且记得里面有要的东西或者没开过。 */
    private boolean othersWorthTrying(ContainerChooser.Candidate candidate, StorageUse use, ItemRequest request) {
        return takableUnderAllowance(use, request)
                && (candidate.knownContents() == null || holdsWanted(candidate, request));
    }

    /** 带着别人的箱子放行时，这只别人的箱子能不能拿：放行还开着、又不是额外保护的地标旁的。 */
    private static boolean takableUnderAllowance(StorageUse use, ItemRequest request) {
        return request.othersStorage().open() && use.usableWhenStarving();
    }

    // 别人的箱子的报价：记得有货报件数，没开过报不知道多少；说明里写是谁的，LLM 和日志都看得出为什么去翻它。
    private SourceQuote.Offer othersOffer(ContainerChooser.Candidate candidate, StorageUse use, ItemRequest request,
            SourceContext context) {
        int count = candidate.knownContents() == null ? SourceQuote.Offer.UNKNOWN_COUNT
                : Math.min(countWanted(candidate, request), request.count());
        return new SourceQuote.Offer(describe(), count, new AcquisitionCost(reachableCost(candidate, context), 4),
                depthNote(candidate, context, "能用的箱子里没有，饿急了从别人的箱子（" + use.describe(ownerNames)
                        + "）拿刚好够吃的"), positionHint(candidate));
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
