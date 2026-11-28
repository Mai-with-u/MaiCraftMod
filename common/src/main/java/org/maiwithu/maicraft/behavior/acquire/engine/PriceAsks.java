// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.interaction.DigTool;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 问价不拿：问遍所有登记的物品来源，记下把一点东西弄进背包最省一条路的总代价（折成格），
 * 并记下这一刻有没有来源还没看清现场（附近的方块还没扫完）。做法排先后、挑可以互换的原料都用它；
 * 不动手、不记事实、不改世界记忆。
 *
 * <p>问价记忆采用无环 DAG 记忆化：子问题计算过程未撞到外层环依赖时，按去掉外层路径的通用键缓存，
 * 同轮内跨任意分支 100% 复用；只有实际触发外层环拦截的子问题才按外层约束隔离。
 * 动手问价挂载原料问价预算上限，且在同一游戏刻且状态未变时轻量复用无环非扫描中报价，
 * 避免含数十种变体的标签配方把渲染线程拖死。所有状态仅在客户端主线程访问。
 */
final class PriceAsks {

    private static final Logger LOG = LoggerFactory.getLogger(PriceAsks.class);
    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;
    /** 动手问价单轮允许的新增全源原料问价上限（命中记忆化的复用不扣额度），防止大标签展开冻结渲染线程。 */
    static final int CONSULT_MAX_INGREDIENT_ASKS = 200;
    /** 同一游戏刻轻量无环缓存的条目上限，防止单刻极端多目标撑大内存。 */
    private static final int SAME_TICK_MAX_ENTRIES = 512;

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    /** 做法不拿来当原料的那段外层此刻要的东西：只在实际撞到外层约束时才进隔离键。 */
    private final Supplier<List<WantedItem>> outerWanted;

    /** 本轮估算或动手问价的记忆：无环子问题按 withoutOuter 键存，撞外层的按完整键存。 */
    private Map<QuoteQuestion, SourceQuote> remembered;
    /** 当前生效的问价预算（估算或动手问价）；不在轮次内时为 null。 */
    private QuoteBudget budget;
    /** 动手问价那一轮的账：问了谁几次、各花多久、几次用了记下的；不在那一轮里时为 null。 */
    private QuoteTally tally;

    /** 单线程调用帧栈：追踪每一层子问题是否撞到外层约束、递归环或预算截断，决定能否写入无环缓存。 */
    private final Deque<AskFrame> frames = new ArrayDeque<>();
    /** 此刻正在递归问价的物品集合：防不同制造来源之间互相递归问价成指数环。 */
    private final Set<WantedItem> activeAsks = new HashSet<>();

    /** 同游戏刻无环轻量复用缓存（绝不存 NotYet，换刻或背包/位置指纹变化即清空）。 */
    private final Map<QuoteQuestion, SourceQuote> sameTickCache = new HashMap<>();
    private long sameTickKey = Long.MIN_VALUE;
    private String sameTickFingerprint;
    private boolean sameTickActive;
    /** 本轮动手问价期间返回 NotYet 的非制造扫描来源探针：等待期间按刻推进建索引，同时跳过大配方重复展开。 */
    private List<ScanProbe> consultScanProbes;

    /** 本轮问价中仍在扫描现场的非制造来源探针（用于等待期间持续推进冷索引建段）。 */
    record ScanProbe(ItemSource source, ItemRequest request, Permissions permissions, boolean includeMaking,
            ItemAcquisition.Scope scope) {}

    PriceAsks(List<ItemSource> sources, ReadsCharacterPosition position, Supplier<List<WantedItem>> outerWanted) {
        this.sources = List.copyOf(sources);
        this.position = position;
        this.outerWanted = outerWanted;
    }

    /** 一遍问价的结果：最省一条路的总代价（问不出时为空），以及有没有来源还没看清现场。 */
    record Price(Optional<Double> cost, boolean looking) {}

    /** 一种问法的完整键（含当前外层约束）；无环子问题复用时自动查它的 withoutOuter 键。 */
    QuoteQuestion questionOf(ItemSource source, ItemRequest request, Permissions permissions, boolean includeMaking,
            ItemAcquisition.Scope scope) {
        return QuoteQuestion.ofSource(source, request, permissions, includeMaking, scope, outerWanted.get());
    }

    /** 通知当前正在计算的子问题：本次计算实际撞到了外层约束（因 outerWanted 剔除了候选），不可写入无环通用缓存。 */
    void markOuterDependency() {
        AskFrame current = frames.peek();
        if (current != null) current.touchedOuter = true;
    }

    /** 通知当前帧与预算：来源因预算耗尽主动提前止损，结果不完整，不可写入共享缓存并记作预算拒绝。 */
    void markBudgetCut() {
        AskFrame current = frames.peek();
        if (current != null) current.budgetCut = true;
        if (budget != null) budget.markRefused();
    }

    /** 通知当前帧：制造来源因重入返回了降级报价（未排序或跳过远端问价），绝不写入共享缓存与同刻缓存。 */
    void markDegraded() {
        AskFrame current = frames.peek();
        if (current != null) current.degraded = true;
    }

    /** 取出本轮动手问价收集到的非制造扫描探针（供等待扫描期间按刻推进冷索引建段）。 */
    List<ScanProbe> scanProbes() {
        return consultScanProbes == null ? List.of() : List.copyOf(consultScanProbes);
    }

    /** 当前一轮问价的预算是否已耗尽：供多候选配方与标签展开提前止损。 */
    boolean budgetExhausted() {
        return budget != null && budget.exhausted();
    }

    /** 查记忆中的价：先查无环通用键（跨外层分支 100% 复用），未命中再查带外层隔离键与同刻无环缓存。 */
    SourceQuote cached(QuoteQuestion question) {
        QuoteQuestion base = question.withoutOuter();
        if (remembered != null) {
            SourceQuote acyclic = remembered.get(base);
            if (acyclic != null) return acyclic;
            if (question.hasOuter()) {
                SourceQuote scoped = remembered.get(question);
                if (scoped != null) return scoped;
            }
        }
        if (sameTickActive) {
            return sameTickCache.get(base);
        }
        return null;
    }

    /** 一次估算期间：问过的价记下来，问遍各来源一次从这次估算的额度里扣一次；干完就忘掉、不再扣。 */
    <T> T during(QuoteBudget estimateBudget, Supplier<T> work) {
        if (remembered != null) return work.get();
        remembered = new HashMap<>();
        budget = estimateBudget;
        try {
            return work.get();
        } finally {
            remembered = null;
            budget = null;
            activeAsks.clear();
            frames.clear();
        }
    }

    /** 动手拿东西时问一轮价（不带刻号版本，兼容已有调用与测试）。 */
    <T> T duringConsult(ItemRequest asked, Supplier<T> work) {
        return duringConsult(asked, Long.MIN_VALUE, null, work);
    }

    /**
     * 动手拿东西时问一轮价：挂载单轮问价预算保护渲染线程；同一游戏刻且角色位置/背包指纹未变时
     * 轻量复用无环且非扫描中的报价，跨刻或现场变化立即清空，不沿用旧世界的答案。
     */
    <T> T duringConsult(ItemRequest asked, long gameTick, String stateFingerprint, Supplier<T> work) {
        if (remembered != null) return work.get();
        boolean enableSameTick = gameTick != Long.MIN_VALUE && stateFingerprint != null;
        if (enableSameTick) {
            if (gameTick != sameTickKey || !stateFingerprint.equals(sameTickFingerprint)) {
                sameTickCache.clear();
                sameTickKey = gameTick;
                sameTickFingerprint = stateFingerprint;
            }
            sameTickActive = true;
        } else {
            sameTickActive = false;
        }
        remembered = new HashMap<>();
        budget = new QuoteBudget(CONSULT_MAX_INGREDIENT_ASKS);
        tally = new QuoteTally(AcquisitionEstimates.describe(asked), System::nanoTime);
        consultScanProbes = new ArrayList<>();
        try {
            return work.get();
        } finally {
            long millis = tally.elapsedMillis();
            if (QuoteTally.slow(millis)) LOG.info(tally.report(millis));
            remembered = null;
            budget = null;
            tally = null;
            consultScanProbes = null;
            sameTickActive = false;
            activeAsks.clear();
            frames.clear();
        }
    }

    /** 向一个来源问一次价，并在动手问价轮次内记账（不含帧追踪与缓存，缓存与帧隔离统一走 quoteRemembered）。 */
    SourceQuote quote(ItemSource source, ItemRequest request, SourceContext context) {
        if (tally == null) return source.quote(request, context);
        tally.progressBefore(source.describe()).ifPresent(LOG::info);
        long started = System.nanoTime();
        try {
            return source.quote(request, context);
        } finally {
            tally.quoted(source.describe(), System.nanoTime() - started);
        }
    }

    /**
     * 统一带帧追踪的 DAG 记忆化问价入口（估算与动手问价共用）：
     * 压帧追踪外层拦截、递归环、预算截断与重入降级；仅当帧干净可缓存时写入记忆，
     * 未撞外层时写无环通用键，撞外层时按完整外层键隔离；降级、撞环或预算截断结果绝不写入任何缓存。
     */
    SourceQuote quoteRemembered(ItemSource source, QuoteQuestion question, ItemRequest request,
            SourceContext context) {
        SourceQuote known = cached(question);
        if (known != null) return known;
        AskFrame frame = new AskFrame();
        frame.source = source;
        // 同一来源的报价还没算完又被问到（做法来源排先后时回头问原料价）：里层这趟是重入短路，
        // 拿不到排好的完整答案，由引擎在压帧时认出来，不等来源自己报告。
        for (AskFrame open : frames) {
            if (open.source == source) {
                frame.degraded = true;
                break;
            }
        }
        boolean exhaustedAtEntry = budget != null && budget.exhausted();
        frames.push(frame);
        SourceQuote fresh;
        try {
            fresh = quote(source, request, context);
        } finally {
            frames.pop();
            // 收帧时额度已经用完：这趟报价途中发生过预算截断（来源静默跳过剩余候选引擎看不见，
            // 但额度耗尽一定发生在这趟里），结果不完整，不入缓存，并补记一笔预算拒绝让估算感知没估完。
            if (budget != null && budget.exhausted()) {
                frame.budgetCut = true;
                if (!exhaustedAtEntry) budget.markRefused();
            }
            AskFrame parent = frames.peek();
            if (parent != null) parent.absorb(frame);
        }
        if (consultScanProbes != null && fresh instanceof SourceQuote.NotYet
                && !AcquireVia.makes(source.via().name())) {
            recordScanProbe(source, request, context.permissions(), question.includeMaking(), question.scope());
        }
        if (remembered != null && frame.cacheable()) {
            QuoteQuestion storeKey = frame.touchedOuter ? question : question.withoutOuter();
            remembered.put(storeKey, fresh);
            if (!frame.touchedOuter && sameTickActive && !(fresh instanceof SourceQuote.NotYet)
                    && sameTickCache.size() < SAME_TICK_MAX_ENTRIES) {
                sameTickCache.put(storeKey, fresh);
            }
        }
        return fresh;
    }

    private void recordScanProbe(ItemSource source, ItemRequest request, Permissions permissions,
            boolean includeMaking, ItemAcquisition.Scope scope) {
        if (consultScanProbes.size() >= 8) return;
        for (ScanProbe existing : consultScanProbes) {
            if (existing.source() == source && existing.request().wanted().equals(request.wanted())) {
                return;
            }
        }
        consultScanProbes.add(new ScanProbe(source, request, permissions, includeMaking, scope));
    }

    /**
     * 推进仍在扫描现场的非制造来源探针（例如冷索引方块扫描）：在当刻 duringConsult 内走 quoteRemembered，
     * 使探针扫完当刻拿到的首个有效报价直接进入本轮记忆，随后全源问价立即可复用而不多问一次；返回仍未扫完的探针子集。
     */
    List<ScanProbe> probePendingScans(List<ScanProbe> probes, DigTool digTool, UnreachablePlaces unreachable) {
        List<ScanProbe> remaining = new ArrayList<>();
        WorldPosition spot = position.currentPosition();
        for (ScanProbe probe : probes) {
            SourceContext context = probe.scope() == null
                    ? SourceContext.unscoped(spot, probe.permissions())
                    : SourceContext.scoped(spot, probe.permissions(),
                            probe.scope().radiusBlocks(), probe.scope().maxDistanceBlocks())
                            .withDigTool(digTool).withUnreachable(unreachable);
            QuoteQuestion question = questionOf(probe.source(), probe.request(), probe.permissions(),
                    probe.includeMaking(), probe.scope());
            if (quoteRemembered(probe.source(), question, probe.request(), context) instanceof SourceQuote.NotYet) {
                remaining.add(probe);
            }
        }
        return remaining;
    }

    /**
     * 计算同刻轻复用的完整现场指纹：覆盖角色坐标格、主背包物品种类与数量、副手物品、已持有数、
     * 已知不可得物品集、外层偏好链、已排除来源数、不可达地点数与指定工具，任一变化即作废同刻缓存。
     */
    static String stateFingerprint(WorldPosition pos, BackpackView backpack, OffhandContents offhand, int carried,
            Set<String> knownUnobtainable, List<String> outerPrefer, int excludedCount, int unreachableCount,
            DigTool digTool) {
        StringBuilder sb = new StringBuilder(128);
        if (pos == null) {
            sb.append('?');
        } else {
            sb.append(pos.x()).append(',').append(pos.y()).append(',').append(pos.z())
                    .append(',').append(pos.dimension());
        }
        sb.append("|c=").append(carried).append("|inv=");
        Map<String, Integer> byItem = new TreeMap<>();
        int stacks = 0;
        for (BackpackStack stack : backpack.stacks()) {
            stacks++;
            byItem.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        sb.append(stacks).append(':');
        byItem.forEach((id, count) -> sb.append(id).append('=').append(count).append(';'));
        sb.append("|off=");
        if (offhand != null) {
            offhand.heldInOffhand().ifPresentOrElse(
                    stack -> sb.append(stack.itemId()).append('=').append(stack.count()),
                    () -> sb.append('-'));
        } else {
            sb.append('-');
        }
        if (!knownUnobtainable.isEmpty()) {
            sb.append("|no=");
            for (String item : new TreeSet<>(knownUnobtainable)) {
                sb.append(item).append(',');
            }
        }
        if (!outerPrefer.isEmpty()) {
            sb.append("|pref=");
            for (String pref : outerPrefer) {
                sb.append(pref).append(',');
            }
        }
        sb.append("|ex=").append(excludedCount).append("|un=").append(unreachableCount);
        if (digTool != null) {
            sb.append("|tool=").append(digTool);
        }
        return sb.toString();
    }

    /**
     * 问一遍各来源，求把请求弄进背包的最省总代价（折成格）以及是否还有来源在扫现场。
     * 命中记忆直接返回不扣预算；正在外层问同一样东西时撞环截断，防多来源互相递归成指数树。
     */
    Price ask(ItemRequest request, Permissions permissions, boolean includeMaking) {
        if (activeAsks.contains(request.wanted())) {
            AskFrame top = frames.peek();
            if (top != null) top.touchedCycle = true;
            return new Price(Optional.empty(), false);
        }
        List<ItemSource> participants = new ArrayList<>();
        List<QuoteQuestion> questions = new ArrayList<>();
        List<WantedItem> outer = outerWanted.get();
        for (ItemSource source : sources) {
            String via = source.via().name();
            if (!ItemAcquisition.Scope.ALL.allows(via) || (!includeMaking && AcquireVia.makes(via))) continue;
            participants.add(source);
            questions.add(QuoteQuestion.ofSource(source, request, permissions, includeMaking, null, outer));
        }
        boolean allKnown = remembered != null && questions.stream().allMatch(q -> cached(q) != null);
        if (!allKnown) {
            if (budget != null && !budget.takeIngredientAsk()) {
                AskFrame top = frames.peek();
                if (top != null) top.budgetCut = true;
                return new Price(Optional.empty(), false);
            }
            if (tally != null) tally.askedAround();
        } else if (tally != null) {
            tally.recalled();
        }
        SourceContext context = SourceContext.unscoped(position.currentPosition(), permissions);
        Optional<Double> cheapest = Optional.empty();
        boolean looking = false;
        activeAsks.add(request.wanted());
        try {
            for (int i = 0; i < participants.size(); i++) {
                SourceQuote quote = quoteRemembered(participants.get(i), questions.get(i), request, context);
                if (quote instanceof SourceQuote.Offer offer) {
                    double cost = offer.cost().total() + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                    if (cheapest.isEmpty() || cost < cheapest.get()) cheapest = Optional.of(cost);
                }
                if (quote instanceof SourceQuote.NotYet) looking = true;
            }
        } finally {
            activeAsks.remove(request.wanted());
        }
        return new Price(cheapest, looking);
    }

    /** 问各来源最近一条路的距离（格），复用同一份 DAG 记忆、预算守卫与防环保护，不再绕过缓存裸扫全源。 */
    Optional<Double> nearestDistance(ItemRequest request, Permissions permissions) {
        if (activeAsks.contains(request.wanted())) {
            AskFrame top = frames.peek();
            if (top != null) top.touchedCycle = true;
            return Optional.empty();
        }
        List<ItemSource> participants = new ArrayList<>();
        List<QuoteQuestion> questions = new ArrayList<>();
        List<WantedItem> outer = outerWanted.get();
        for (ItemSource source : sources) {
            if (!ItemAcquisition.Scope.ALL.allows(source.via().name())) continue;
            participants.add(source);
            questions.add(QuoteQuestion.ofSource(source, request, permissions, true, null, outer));
        }
        boolean allKnown = remembered != null && questions.stream().allMatch(q -> cached(q) != null);
        if (!allKnown) {
            if (budget != null && !budget.takeIngredientAsk()) {
                AskFrame top = frames.peek();
                if (top != null) top.budgetCut = true;
                return Optional.empty();
            }
            if (tally != null) tally.askedAround();
        } else if (tally != null) {
            tally.recalled();
        }
        SourceContext context = SourceContext.unscoped(position.currentPosition(), permissions);
        Optional<Double> nearest = Optional.empty();
        activeAsks.add(request.wanted());
        try {
            for (int i = 0; i < participants.size(); i++) {
                SourceQuote quote = quoteRemembered(participants.get(i), questions.get(i), request, context);
                if (quote instanceof SourceQuote.Offer offer) {
                    double distance = offer.cost().distanceBlocks()
                            + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                    if (nearest.isEmpty() || distance < nearest.get()) nearest = Optional.of(distance);
                }
            }
        } finally {
            activeAsks.remove(request.wanted());
        }
        return nearest;
    }

    /** 单层子问题问价的依赖标记：只要碰到外层拦截、递归环、预算截断或重入降级，就不把结果写入无环通用缓存。 */
    private static final class AskFrame {
        /** 这层子问题在向哪个来源问价：用来认出同一来源还没答完又被问到（重入短路）。 */
        ItemSource source;
        boolean touchedOuter;
        boolean touchedCycle;
        boolean budgetCut;
        boolean degraded;

        void absorb(AskFrame child) {
            touchedOuter |= child.touchedOuter;
            touchedCycle |= child.touchedCycle;
            budgetCut |= child.budgetCut;
            degraded |= child.degraded;
        }

        boolean cacheable() {
            return !budgetCut && !touchedCycle && !degraded;
        }
    }
}
