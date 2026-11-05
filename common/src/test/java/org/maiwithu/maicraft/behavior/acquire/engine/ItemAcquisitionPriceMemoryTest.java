// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    void 做法不吃的那段外层不同_问价分开记() {
        // 同一种问法，做法不拿来当原料的那段外层不同（直接要原木，和为砍原木备斧头），做法剔掉的不同、答案可能不同：
        // 同一段外层问两遍只问一次来源，换了一段外层再问一次，不拿一个答另一个。
        CountingChest chest = new CountingChest(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(20, 2), null));
        List<WantedItem> outer = new ArrayList<>(List.of(WantedItem.ofItem("minecraft:oak_log")));
        PriceAsks asks = new PriceAsks(List.of(chest), new FixedSpot(0, 64, 0), () -> List.copyOf(outer));
        asks.during(new QuoteBudget(100), () -> {
            asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            asks.ask(want(PLANKS, 2), PERMISSIONS, true);
            outer.clear();
            outer.add(WantedItem.ofItem("minecraft:wooden_axe"));
            return asks.ask(want(PLANKS, 2), PERMISSIONS, true);
        });
        assertEquals(2, chest.asked.get(PLANKS));
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
