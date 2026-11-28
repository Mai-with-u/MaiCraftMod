// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 动手问价那一轮的记忆：做法类来源排做法时同一样原料回头问好几遍，这一轮里同一种问法只问遍一次来源；
 * 问法不同的照样分开问；下一轮（下一刻）重新问，不沿用上一轮记下的，问完了也不再记着。
 */
class ItemAcquisitionPriceMemoryTest {

    private static final Permissions PERMISSIONS = Permissions.DEFAULT;
    private static final String SWORD = "minecraft:wooden_sword";
    private static final String PLANKS = "minecraft:oak_planks";

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    private ItemAcquisition engine(ItemSource... sources) {
        return new ItemAcquisition(List.of(sources), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    private static ItemRequest want(String item, int count) {
        return new ItemRequest(WantedItem.ofItem(item), count, "测试");
    }

    /** 一刻一刻推进，直到动作定局，返回终态。 */
    private static ActionStatus runToSettlement(Action action) {
        for (int tick = 1; tick <= 50; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("五十刻还没定局");
    }

    @Test
    void 一轮问价里同一样原料排做法时问了三遍_只问遍一次来源() {
        // 做剑排先后时两条做法都缺两块木板，问不出价时还要再问一遍是不是还没看完：这一轮里木板只问一次箱子。
        CountingChest chest = new CountingChest(new SourceQuote.Unavailable("记得的箱子", "没有木板"));
        SwordCrafting craft = new SwordCrafting(0);
        ItemAcquisition engine = engine(chest, craft);
        craft.engine = engine;

        ActionStatus status = runToSettlement(engine.need(want(SWORD, 1), PERMISSIONS));

        assertEquals(ActionStatus.done(), status);
        assertEquals(1, craft.plankPrices.size(), "只问了一轮价");
        assertEquals(1, chest.asked.get(PLANKS));
        assertEquals(1, backpack.stacks.stream().filter(stack -> stack.itemId().equals(SWORD))
                .mapToInt(stack -> stack.count()).sum(), "挑中的照旧是自己做，剑做出来了");
    }

    @Test
    void 下一轮重新问_上一轮记下的不沿用_问完了也不再记着() {
        // 头一轮箱子里还没有木板，做剑的来源答还在看现场，引擎等一刻再问；这时箱子里有了木板，
        // 第二轮要问到新的答案，不拿上一轮记下的"问不出价"。
        CountingChest chest = new CountingChest(new SourceQuote.Unavailable("记得的箱子", "还没放进去"),
                new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(20, 2), null));
        SwordCrafting craft = new SwordCrafting(1);
        ItemAcquisition engine = engine(chest, craft);
        craft.engine = engine;

        ActionStatus status = runToSettlement(engine.need(want(SWORD, 1), PERMISSIONS));

        assertEquals(ActionStatus.done(), status);
        assertEquals(2, craft.plankPrices.size(), "问了两轮价");
        assertTrue(craft.plankPrices.get(0).isEmpty(), "头一轮箱子里还没有木板");
        assertTrue(craft.plankPrices.get(1).isPresent(), "第二轮问到了新放进去的木板");
        assertEquals(2, chest.asked.get(PLANKS), "每一轮各问一次箱子");

        // 拿完了：再问价是此刻的现场，照常问遍来源。
        engine.cheapestCost(want(PLANKS, 2), PERMISSIONS, true);
        assertEquals(3, chest.asked.get(PLANKS));
    }

    @Test
    void 一轮问价里问法不同的照样分开问() {
        // 要两块和要六十四块、算不算现做都是不同的问法：答案可能不一样，各问一次箱子，不拿一个答另一个。
        CountingChest chest = new CountingChest(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(20, 2), null));
        chest.onlyUpTo = 2;
        List<Optional<Double>> prices = new ArrayList<>();
        SwordCrafting craft = new SwordCrafting(0) {
            @Override protected Optional<Double> askPlanks() {
                prices.add(engine.cheapestCost(want(PLANKS, 2), PERMISSIONS, true));
                prices.add(engine.cheapestCost(want(PLANKS, 64), PERMISSIONS, true));
                prices.add(engine.cheapestCost(want(PLANKS, 2), PERMISSIONS, false));
                prices.add(engine.cheapestCost(want(PLANKS, 64), PERMISSIONS, true));
                return prices.getFirst();
            }
        };
        ItemAcquisition engine = engine(chest, craft);
        craft.engine = engine;

        assertEquals(ActionStatus.done(), runToSettlement(engine.need(want(SWORD, 1), PERMISSIONS)));

        assertTrue(prices.get(0).isPresent(), "两块箱子里有");
        assertTrue(prices.get(1).isEmpty(), "六十四块箱子里不够，不拿两块的答案顶");
        assertTrue(prices.get(2).isPresent(), "不算现做也问得到箱子");
        assertTrue(prices.get(3).isEmpty(), "第二次问六十四块用了记下的");
        assertEquals(3, chest.asked.get(PLANKS), "三种问法各问一次");
    }

    @Test
    void 无环子问题不依赖外层约束_切换外层路径后仍复用缓存() {
        // 容器里有没有木板与外层正在要原木还是木斧无关：未触发外层依赖时，切换外层仍 100% 复用无环缓存。
        CountingChest chest = new CountingChest(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(20, 2), null));
        List<WantedItem> outer = new ArrayList<>(List.of(WantedItem.ofItem("minecraft:oak_log")));
        PriceAsks asks = new PriceAsks(List.of(chest), new FixedSpot(0, 64, 0), () -> List.copyOf(outer));
        asks.during(new QuoteBudget(100), () -> {
            asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            outer.clear();
            outer.add(WantedItem.ofItem("minecraft:wooden_axe"));
            return asks.ask(want(PLANKS, 2), PERMISSIONS, true);
        });
        assertEquals(1, chest.asked.get(PLANKS), "无环子问题应跨外层分支复用，只问 1 次");
    }

    @Test
    void 实际撞到外层环依赖的子问题按外层隔离_不污染无环缓存() {
        // 制造来源在 outer 含原木时因防环剔除了配方并调用 markOuterDependency()：
        // 该结果按当前外层隔离，换到不含原木的外层时必须重算并得出新报价，绝不复用受污染缓存。
        List<WantedItem> outer = new ArrayList<>(List.of(WantedItem.ofItem("minecraft:oak_log")));
        PriceAsks[] holder = new PriceAsks[1];
        int[] craftCalls = {0};
        ItemSource outerSensitiveCraft = new ItemSource() {
            @Override public String describe() { return "受外层影响的合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                craftCalls[0]++;
                if (outer.contains(WantedItem.ofItem("minecraft:oak_log"))) {
                    holder[0].markOuterDependency();
                    return new SourceQuote.Unavailable(describe(), "外层正要原木，跳过这条配方");
                }
                return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(outerSensitiveCraft), new FixedSpot(0, 64, 0), () -> List.copyOf(outer));
        holder[0] = asks;

        PriceAsks.Price[] results = new PriceAsks.Price[2];
        asks.during(new QuoteBudget(100), () -> {
            results[0] = asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            // 相同外层再问一次命中隔离缓存
            asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            // 切换外层不再撞环：重算得到有效报价
            outer.clear();
            outer.add(WantedItem.ofItem("minecraft:wooden_axe"));
            results[1] = asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            return null;
        });

        assertTrue(results[0].cost().isEmpty(), "撞外层环时无报价");
        assertTrue(results[1].cost().isPresent(), "换外层后不误用受污染缓存，应正常得出报价");
        assertEquals(2, craftCalls[0], "同外层复用一次，换外层重算一次，共 2 次");
    }

    @Test
    void 大标签多变体配方共享原料_DAG记忆化与预算保护压住问价爆炸() {
        // 模拟 #functionalstorage:drawer（40 种木头抽屉变体共享 20 种木板与箱子等子问题，且在不同外层分支下反复问价）：
        // 无环子问题跨分支 100% 复用 + 动手问价预算保护，将原本数万次全源扫描压到百次级以内。
        int[] leafQuotes = {0};
        ItemSource leafSource = new ItemSource() {
            @Override public String describe() { return "模拟箱子"; }
            @Override public AcquireVia via() { return AcquireVia.CONTAINER; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                leafQuotes[0]++;
                return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(8, 2), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        List<WantedItem> outer = new ArrayList<>();
        PriceAsks[] asksHolder = new PriceAsks[1];
        ItemSource drawerCrafting = new ItemSource() {
            @Override public String describe() { return "模拟大标签合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (!"#functionalstorage:drawer".equals(request.wanted().specifier())) {
                    return new SourceQuote.Unavailable(describe(), "非抽屉");
                }
                // 40 种抽屉配方，每种展开 20 种木板 + 1 种箱子（共 840 次子问价，且模拟不同外层路径）。
                for (int variant = 0; variant < 40; variant++) {
                    outer.clear();
                    outer.add(WantedItem.ofItem("functionalstorage:drawer_" + variant));
                    asksHolder[0].ask(want("minecraft:chest", 1), PERMISSIONS, true);
                    for (int plank = 0; plank < 20; plank++) {
                        asksHolder[0].ask(want("minecraft:plank_" + plank, 6), PERMISSIONS, true);
                    }
                }
                return new SourceQuote.Offer(describe(), 1, new AcquisitionCost(0, 8), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(leafSource, drawerCrafting), new FixedSpot(0, 64, 0),
                () -> List.copyOf(outer));
        asksHolder[0] = asks;

        asks.duringConsult(new ItemRequest(WantedItem.ofTag("functionalstorage:drawer"), 1, "测试"),
                100L, "0,64,0|0:0:0",
                () -> asks.quote(drawerCrafting,
                        new ItemRequest(WantedItem.ofTag("functionalstorage:drawer"), 1, "测试"),
                        SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS)));

        // 20 种木板 + 1 种箱子，无环 DAG 记忆化后仅各问 1 次叶子源（总共 21 次，远低于百次！）。
        assertEquals(21, leafQuotes[0], "40×21=840 次跨分支问价经 DAG 记忆化应压缩至 21 次真实源扫描");
    }

    @Test
    void 同一游戏刻现场未变时复用无环报价_跨刻后重新问价() {
        CountingChest chest = new CountingChest(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(10, 2), null));
        PriceAsks asks = new PriceAsks(List.of(chest), new FixedSpot(0, 64, 0), List::of);
        // 第 50 刻连续两轮 consult，指纹未变：第二轮复用同刻无环缓存，不重问箱子。
        asks.duringConsult(want(PLANKS, 2), 50L, "0,64,0|1:2:0", () -> asks.ask(want(PLANKS, 2), PERMISSIONS, true));
        asks.duringConsult(want(PLANKS, 2), 50L, "0,64,0|1:2:0", () -> asks.ask(want(PLANKS, 2), PERMISSIONS, true));
        assertEquals(1, chest.asked.get(PLANKS), "同刻指纹未变时复用无环缓存");
        // 第 51 刻跨刻后重新问价。
        asks.duringConsult(want(PLANKS, 2), 51L, "0,64,0|1:2:0", () -> asks.ask(want(PLANKS, 2), PERMISSIONS, true));
        assertEquals(2, chest.asked.get(PLANKS), "跨刻后重新问价");
    }

    @Test
    void 连续等待扫描期间节流复查_不再每刻原样全源重算() {
        CountingChest chest = new CountingChest(new SourceQuote.Unavailable("记得的箱子", "没有木板"));
        // 连续 10 轮都返回 NotYet 模拟冷索引视野扫描未完。
        SwordCrafting craft = new SwordCrafting(10);
        ItemAcquisition engine = engine(chest, craft);
        craft.engine = engine;

        Action action = engine.need(want(SWORD, 1), PERMISSIONS);
        for (int tick = 1; tick <= 12; tick++) {
            action.tick(new StubTick(tick));
        }
        // 12 刻内因连续 stillLooking 节流（第 1、2 刻后每 5 刻复查一次），全源重算次数大幅低于 12 次。
        assertTrue(craft.plankPrices.size() <= 5,
                "连续等待扫描 12 刻不应每刻重算，实际重算次数：" + craft.plankPrices.size());
    }

    @Test
    void 额度已尽的报价一律标脏当前帧_半截报价绝不入缓存() {
        // 额度用完后再问来源：这趟报价无论来源自己有没有发现额度问题，引擎收帧时一律标脏，不入缓存。
        int[] quoteCalls = {0};
        ItemSource budgetStoppingSource = new ItemSource() {
            @Override public String describe() { return "预算止损合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                quoteCalls[0]++;
                return new SourceQuote.Unavailable(describe(), "额度已尽时的回答");
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(budgetStoppingSource), new FixedSpot(0, 64, 0), List::of);

        QuoteBudget budget = new QuoteBudget(1);
        budget.takeQuote();
        assertEquals(0, budget.refused(), "前置条件：恰好用满时尚未拒绝");
        asks.during(budget, () -> {
            QuoteQuestion q = asks.questionOf(budgetStoppingSource, want(PLANKS, 1), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            SourceQuote first = asks.quoteRemembered(budgetStoppingSource, q, want(PLANKS, 1),
                    SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS));
            assertTrue(first instanceof SourceQuote.Unavailable);
            assertEquals(null, asks.cached(q), "额度已尽时的报价一律不入 remembered 共享缓存");
            assertEquals(0, budget.refused(), "耗尽发生在进帧之前，不补记新的预算拒绝");
            return null;
        });
        assertEquals(1, quoteCalls[0]);
    }

    @Test
    void 估算路径问价同样压帧追踪外层依赖_绝不污染无环缓存() {
        // 模拟估算路径先在外层包含 oak_log 时调 quoteRemembered 触发 markOuterDependency()，
        // 换到不含 oak_log 的外层时不应命中被污染的无环缓存。
        List<WantedItem> outer = new ArrayList<>(List.of(WantedItem.ofItem("minecraft:oak_log")));
        PriceAsks[] holder = new PriceAsks[1];
        ItemSource outerSensitive = new ItemSource() {
            @Override public String describe() { return "受外层影响的估算合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (outer.contains(WantedItem.ofItem("minecraft:oak_log"))) {
                    holder[0].markOuterDependency();
                    return new SourceQuote.Unavailable(describe(), "撞外层原木");
                }
                return new SourceQuote.Offer(describe(), 1, new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(outerSensitive), new FixedSpot(0, 64, 0), () -> List.copyOf(outer));
        holder[0] = asks;

        AcquisitionEstimates estimates = new AcquisitionEstimates(List.of(outerSensitive),
                new FixedSpot(0, 64, 0), 4, asks, () -> List.copyOf(outer));
        asks.during(new QuoteBudget(100), () -> {
            QuoteQuestion withLog = asks.questionOf(outerSensitive, want(PLANKS, 2), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            SourceQuote blocked = asks.quoteRemembered(outerSensitive, withLog, want(PLANKS, 2),
                    SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS));
            assertTrue(blocked instanceof SourceQuote.Unavailable);
            outer.clear();
            QuoteQuestion withoutLog = asks.questionOf(outerSensitive, want(PLANKS, 2), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            assertEquals(null, asks.cached(withoutLog), "撞外层依赖的结果绝不能落入 withoutOuter 通用缓存");
            return null;
        });
        // 外层已清空，独立估算木板应得到可行估算而非沿用受污染结果。
        assertTrue(estimates.estimate(want(PLANKS, 2), PERMISSIONS, ItemAcquisition.Scope.ALL).effort().timed());
    }

    @Test
    void 制造来源重入短路返回降级报价时绝不写入共享缓存与同刻缓存() {
        // 做法来源排先后途中回头问木棍的价，里层问价落到它自己头上：引擎从帧栈认出重入短路，
        // 里层与被污染的外层报价都不入缓存；同一刻以正常身份再问必须拿到真实报价。
        boolean[] reentrant = {true};
        PriceAsks[] holder = new PriceAsks[1];
        ItemSource craft = new ItemSource() {
            @Override public String describe() { return "可重入合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (PLANKS.equals(request.wanted().specifier()) && reentrant[0]) {
                    holder[0].ask(want("minecraft:stick", 1), PERMISSIONS, true);
                    return new SourceQuote.Unavailable(describe(), "重入降级返回");
                }
                return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(craft), new FixedSpot(0, 64, 0), List::of);
        holder[0] = asks;
        SourceContext ctx = SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS);

        asks.duringConsult(want(SWORD, 1), 88L, "fp-same", () -> {
            QuoteQuestion q = asks.questionOf(craft, want(PLANKS, 2), PERMISSIONS, true, null);
            SourceQuote degraded = asks.quoteRemembered(craft, q, want(PLANKS, 2), ctx);
            assertTrue(degraded instanceof SourceQuote.Unavailable);
            assertEquals(null, asks.cached(q), "重入降级报价绝不写入本轮 remembered 缓存");
            QuoteQuestion stick = asks.questionOf(craft, want("minecraft:stick", 1), PERMISSIONS, true, null);
            assertEquals(null, asks.cached(stick), "重入触发的里层报价同样不入缓存");
            return null;
        });

        // 同一游戏刻相同指纹下以正常非重入身份再次问价，必须拿到真实 Offer，绝不命中过期降级结果。
        reentrant[0] = false;
        SourceQuote normal = asks.duringConsult(want(SWORD, 1), 88L, "fp-same", () -> {
            QuoteQuestion q = asks.questionOf(craft, want(PLANKS, 2), PERMISSIONS, true, null);
            return asks.quoteRemembered(craft, q, want(PLANKS, 2), ctx);
        });
        assertTrue(normal instanceof SourceQuote.Offer, "降级结果不能污染同刻缓存，正常调用应得出 Offer");
    }

    @Test
    void 预算在本帧问价期间耗尽的报价不完整_引擎收帧时标脏不入缓存() {
        // 做法来源排候选时额度中途用完就静默收场（剩余候选不问也不报告）：引擎收帧时看到额度已尽，
        // 认定这趟报价不完整，不入缓存，并补记一笔预算拒绝让估算感知"没估完"。
        CountingChest chest = new CountingChest(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(20, 2), null));
        PriceAsks[] holder = new PriceAsks[1];
        int[] craftQuotes = {0};
        // 排候选问两遍原料价（正好把额度 2 用完），随后不再问任何价就报出结果：来源自己没报告截断。
        ItemSource quietCutCraft = new ItemSource() {
            @Override public String describe() { return "静默截断的合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                // 只有排木板做法这一趟才回头问原料价；里层问木棍、线的报价直接答，不再往下问。
                if (!PLANKS.equals(request.wanted().specifier())) {
                    return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(0, 4), null);
                }
                craftQuotes[0]++;
                holder[0].ask(want("minecraft:stick", 1), PERMISSIONS, true);
                holder[0].ask(want("minecraft:string", 1), PERMISSIONS, true);
                return new SourceQuote.Offer(describe(), 1, new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(chest, quietCutCraft), new FixedSpot(0, 64, 0), List::of);
        holder[0] = asks;
        SourceContext ctx = SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS);

        QuoteBudget budget = new QuoteBudget(2);
        asks.during(budget, () -> {
            QuoteQuestion q = asks.questionOf(quietCutCraft, want(PLANKS, 1), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            SourceQuote result = asks.quoteRemembered(quietCutCraft, q, want(PLANKS, 1), ctx);
            assertTrue(result instanceof SourceQuote.Offer);
            assertEquals(null, asks.cached(q), "预算在本帧内耗尽的半截报价绝不写入共享缓存");
            assertEquals(1, budget.refused(), "used=" + budget.used() + " refused=" + budget.refused());
            return null;
        });
        assertEquals(1, craftQuotes[0]);
    }

    @Test
    void 同一来源还没答完又被问到_重入答案不入共享缓存_干净答案照常复用() {
        // 做法来源排先后途中回头问别的原料，里层问价又落到它自己头上：里层这趟是重入短路，
        // 引擎从帧栈认出同一来源还开着帧，标脏不入缓存；之后不带重入的干净答案照常入缓存复用。
        PriceAsks[] holder = new PriceAsks[1];
        int[] craftQuotes = {0};
        ItemSource reentrantCraft = new ItemSource() {
            @Override public String describe() { return "会重入的合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                craftQuotes[0]++;
                // 排木板的做法先后时回头问木棍的价：这次里层问价又落到本来源头上，重入短路。
                if (PLANKS.equals(request.wanted().specifier())) {
                    holder[0].ask(want("minecraft:stick", 1), PERMISSIONS, true);
                }
                return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        PriceAsks asks = new PriceAsks(List.of(reentrantCraft), new FixedSpot(0, 64, 0), List::of);
        holder[0] = asks;
        SourceContext ctx = SourceContext.unscoped(new FixedSpot(0, 64, 0).currentPosition(), PERMISSIONS);

        asks.during(new QuoteBudget(100), () -> {
            asks.ask(want(PLANKS, 1), PERMISSIONS, true);
            QuoteQuestion stick = asks.questionOf(reentrantCraft, want("minecraft:stick", 1), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            assertEquals(null, asks.cached(stick), "重入短路的里层报价绝不写入共享缓存");
            // 重入已随帧吸收污染外层，木板这趟也不入缓存：再问一次要真问，里层木棍同样两趟各真问一次。
            asks.ask(want(PLANKS, 1), PERMISSIONS, true);
            assertEquals(4, craftQuotes[0], "两趟木板加两趟里层木棍都不入缓存，共真问 4 次");
            QuoteQuestion clean = asks.questionOf(reentrantCraft, want("minecraft:string", 1), PERMISSIONS, false,
                    ItemAcquisition.Scope.ALL);
            asks.quoteRemembered(reentrantCraft, clean, want("minecraft:string", 1), ctx);
            assertNotNull(asks.cached(clean), "帧干净时报价照常入缓存");
            asks.quoteRemembered(reentrantCraft, clean, want("minecraft:string", 1), ctx);
            assertEquals(5, craftQuotes[0], "干净答案第二次直接复用缓存，不再问来源");
            return null;
        });
    }

    @Test
    void 同刻背包等量替换物品或副手变化时指纹立即失效() {
        backpack.add("minecraft:oak_planks", 4);
        String fp1 = PriceAsks.stateFingerprint(new FixedSpot(0, 64, 0).currentPosition(), backpack, offhand, 0,
                Set.of(), List.of(), 0, 0, null);
        // 1:1 替换背包物品：总格数与总件数不变，但物品种类变了，指纹必须变化。
        backpack.stacks.clear();
        backpack.add("minecraft:spruce_planks", 4);
        String fp2 = PriceAsks.stateFingerprint(new FixedSpot(0, 64, 0).currentPosition(), backpack, offhand, 0,
                Set.of(), List.of(), 0, 0, null);
        assertNotEquals(fp1, fp2, "背包 1:1 替换不同物品必须令同刻指纹失效");

        // 副手新增盾牌：主背包未变，指纹也必须变化。
        offhand.hold(new BackpackStack("minecraft:shield", 1, 1, true, false, false, false));
        String fp3 = PriceAsks.stateFingerprint(new FixedSpot(0, 64, 0).currentPosition(), backpack, offhand, 0,
                Set.of(), List.of(), 0, 0, null);
        assertNotEquals(fp2, fp3, "副手变化必须令同刻指纹失效");

        // knownUnobtainable 或外层 prefer 变化同样令指纹失效。
        String fp4 = PriceAsks.stateFingerprint(new FixedSpot(0, 64, 0).currentPosition(), backpack, offhand, 0,
                Set.of("minecraft:diamond"), List.of(), 0, 0, null);
        String fp5 = PriceAsks.stateFingerprint(new FixedSpot(0, 64, 0).currentPosition(), backpack, offhand, 0,
                Set.of("minecraft:diamond"), List.of("oak"), 0, 0, null);
        assertNotEquals(fp3, fp4);
        assertNotEquals(fp4, fp5);
    }

    @Test
    void 等待冷索引扫描期间每刻持续推进扫描探针而不重复展开大配方_扫完当刻立刻重算() {
        // 模拟冷索引方块扫描：需要连续被调用 5 次才会扫描完毕并给出报价；
        // 制造来源报价时会问到该扫描来源。等待期间每刻必须持续推进扫描来源（零减速），
        // 同时制造来源不再每刻重复展开大配方；第 5 刻扫描完毕后当刻立刻重新问价并定局。
        int[] scanCalls = {0};
        ItemSource coldScan = new ItemSource() {
            @Override public String describe() { return "冷索引扫描"; }
            @Override public AcquireVia via() { return AcquireVia.MINE; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (!PLANKS.equals(request.wanted().specifier())) {
                    return new SourceQuote.Unavailable(describe(), "只扫木板");
                }
                scanCalls[0]++;
                if (scanCalls[0] < 5) {
                    return new SourceQuote.NotYet(describe(), "正在建冷索引第 " + scanCalls[0] + " 步");
                }
                return new SourceQuote.Offer(describe(), 2, new AcquisitionCost(6, 2), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.empty();
            }
        };
        int[] craftQuotes = {0};
        ItemAcquisition[] engineHolder = new ItemAcquisition[1];
        ItemSource heavyCraft = new ItemSource() {
            @Override public String describe() { return "大配方合成"; }
            @Override public AcquireVia via() { return AcquireVia.CRAFT; }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (!SWORD.equals(request.wanted().specifier())) {
                    return new SourceQuote.Unavailable(describe(), "只做剑");
                }
                craftQuotes[0]++;
                Optional<Double> price = engineHolder[0].cheapestCost(want(PLANKS, 2), PERMISSIONS, false);
                if (price.isEmpty() && engineHolder[0].priceStillLooking(want(PLANKS, 2), PERMISSIONS, false)) {
                    return new SourceQuote.NotYet(describe(), "等冷索引扫完木板");
                }
                return new SourceQuote.Offer(describe(), 1, new AcquisitionCost(0, 6), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add(SWORD, 1);
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "做剑"; }
                });
            }
        };
        ItemAcquisition engine = engine(coldScan, heavyCraft);
        engineHolder[0] = engine;

        Action action = engine.need(want(SWORD, 1), PERMISSIONS);
        ActionStatus settled = null;
        int ticks = 0;
        for (int t = 1; t <= 20; t++) {
            ticks = t;
            ActionStatus st = action.tick(new StubTick(t));
            if (!(st instanceof ActionStatus.Running)) {
                settled = st;
                break;
            }
        }
        assertEquals(ActionStatus.done(), settled);
        // 冷索引需要 5 次推进，第 1~5 刻每刻都推进了冷索引（无 5 倍减速），第 5 刻扫完立刻重算并第 6 刻完成！
        assertTrue(ticks <= 8, "冷索引扫完应立刻完成，不应被节流拖慢到二十多刻，实际刻数：" + ticks);
        assertEquals(2, craftQuotes[0], "等待冷索引期间不应重复展开大配方，仅首刻与扫完后各展开 1 次");
        assertTrue(scanCalls[0] >= 5, "冷索引扫描探针应每刻被持续推进");
    }

    /** 记得的箱子替身：只答木板，按给定的答案依次答（用完重复最后一条），记下每样东西被问了几次。 */
    private static final class CountingChest implements ItemSource {
        final Map<String, Integer> asked = new HashMap<>();
        private final Deque<SourceQuote> answers = new ArrayDeque<>();
        private SourceQuote lastAnswer;
        /** 木板要得比这多时答不够；不限为 0。 */
        int onlyUpTo;

        CountingChest(SourceQuote... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override public String describe() {
            return "记得的箱子";
        }

        @Override public AcquireVia via() {
            return AcquireVia.CONTAINER;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            asked.merge(request.wanted().specifier(), 1, Integer::sum);
            if (!request.wanted().specifier().equals(PLANKS)) {
                return new SourceQuote.Unavailable("记得的箱子", "只有木板");
            }
            if (onlyUpTo > 0 && request.count() > onlyUpTo) {
                return new SourceQuote.Unavailable("记得的箱子", "只有 " + onlyUpTo + " 块");
            }
            if (!answers.isEmpty()) lastAnswer = answers.poll();
            return lastAnswer;
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            return Optional.empty();
        }
    }

    /**
     * 做剑的来源替身：报价时像做法类来源排做法那样回头问木板的价——两条做法各问一遍，问不出时再问是不是还没看完；
     * 头几次报价答还在看现场，让引擎等一刻再问一轮。动手就把剑放进背包。
     */
    private class SwordCrafting implements ItemSource {
        ItemAcquisition engine;
        /** 每一轮报价时问到的木板价，按先后记。 */
        final List<Optional<Double>> plankPrices = new ArrayList<>();
        private final int notYetFirst;

        SwordCrafting(int notYetFirst) {
            this.notYetFirst = notYetFirst;
        }

        @Override public String describe() {
            return "自己做";
        }

        @Override public AcquireVia via() {
            return AcquireVia.CRAFT;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            if (!request.wanted().specifier().equals(SWORD)) {
                return new SourceQuote.Unavailable("自己做", "只做剑");
            }
            plankPrices.add(askPlanks());
            if (plankPrices.size() <= notYetFirst) {
                return new SourceQuote.NotYet("自己做", "木板附近还没看完");
            }
            return new SourceQuote.Offer("自己做", request.count(), new AcquisitionCost(0, 6), null);
        }

        // 两条做法都缺两块木板，各问一遍；问不出价时再问一遍是不是还没看完。
        protected Optional<Double> askPlanks() {
            engine.cheapestCost(want(PLANKS, 2), PERMISSIONS, true);
            Optional<Double> price = engine.cheapestCost(want(PLANKS, 2), PERMISSIONS, true);
            if (price.isEmpty()) engine.priceStillLooking(want(PLANKS, 2), PERMISSIONS, true);
            return price;
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    backpack.add(SWORD, request.count());
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "做剑";
                }
            });
        }
    }
}
