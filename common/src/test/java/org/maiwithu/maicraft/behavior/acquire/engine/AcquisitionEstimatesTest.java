// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 动手前估算：按引擎此刻会走的路往下估原料，档位取最重、秒数相加、最远取最大；
 * 挑中的路走不通就换下一条；只问价不动手；弄不到的原因同一样只讲一次。
 */
class AcquisitionEstimatesTest {

    private static final Permissions PERMISSIONS = Permissions.DEFAULT;
    private static final String SWORD = "minecraft:wooden_sword";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String STICK = "minecraft:stick";
    private static final String LOG = "minecraft:oak_log";
    private static final String WOOL = "minecraft:white_wool";
    private static final String BED = "minecraft:white_bed";

    private final Stub craft = new Stub("自己做", AcquireVia.CRAFT);
    private final Stub mine = new Stub("采掘", AcquireVia.MINE);
    private final Stub hunt = new Stub("狩猎", AcquireVia.HUNT);
    private final Stub chest = new Stub("记得的箱子", AcquireVia.CONTAINER);

    private ItemAcquisition engine() {
        return new ItemAcquisition(List.of(craft, mine, hunt, chest), new FakeBackpack(36), new FakeOffhand(),
                new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    private static ItemRequest want(String item, int count) {
        return new ItemRequest(WantedItem.ofItem(item), count, "测试");
    }

    private static ItemAcquisition.Scope maxDistance(double blocks) {
        return new ItemAcquisition.Scope(Set.of(), blocks, null);
    }

    // 木剑在背包合成格里做，缺木板、木棍；木板、木棍也在背包里做，原料（原木）各自由测试给。
    private void swordRecipes() {
        craft.offer(SWORD, (request, context) -> new SourceQuote.Offer("自己做", 1, new AcquisitionCost(0, 6, 64),
                "附近没有工作台，会在身边放一个，做完挖下来收回背包", "sword"), List.of(want(PLANKS, 2), want(STICK, 1)));
        craft.offer(STICK, (request, context) -> new SourceQuote.Offer("自己做", 4, new AcquisitionCost(0, 5),
                "在背包的合成格里做，不用工作台", "stick"), List.of());
    }

    @Test
    void 身上的原木就够做木剑_不用离开_给秒数不给距离() {
        swordRecipes();
        craft.offer(PLANKS, (request, context) -> new SourceQuote.Offer("自己做", 4, new AcquisitionCost(0, 5),
                "在背包的合成格里做，不用工作台", "planks"), List.of());

        GoalEstimate estimate = engine().estimate(want(SWORD, 1), PERMISSIONS);

        assertEquals(Effort.READY, estimate.effort());
        assertNull(estimate.farthestBlocks(), "不用离开不说走多远");
        assertNotNull(estimate.roughSeconds());
        assertTrue(estimate.roughSeconds() > 0);
        assertTrue(estimate.how().contains("缺 minecraft:oak_planks ×2、minecraft:stick ×1"), estimate.how());
        assertEquals(0, craft.begun + mine.begun + hunt.begun + chest.begun, "估算不动手");
    }

    @Test
    void 身上没原木_十四格外有树_整件是知道在哪_最远十四格() {
        swordRecipes();
        craft.offer(PLANKS, (request, context) -> new SourceQuote.Offer("自己做", 4, new AcquisitionCost(0, 5, 32),
                "在背包的合成格里做，不用工作台", "planks"), List.of(want(LOG, 1)));
        mine.offer(LOG, (request, context) -> new SourceQuote.Offer("采掘", 5, new AcquisitionCost(14, 2), null),
                List.of());

        GoalEstimate estimate = engine().estimate(want(SWORD, 1), PERMISSIONS);

        assertEquals(Effort.NEARBY, estimate.effort());
        assertEquals(14.0, estimate.farthestBlocks());
        assertNotNull(estimate.roughSeconds());
        // 定了档位的那一样原料接着讲，一路讲到十四格外的树。
        assertTrue(estimate.how().contains("采掘（14 格外）"), estimate.how());
    }

    @Test
    void 羊毛_看见羊是知道在哪_给了出门距离是要出去找_没给是弄不到() {
        hunt.offer(WOOL, (request, context) -> context.maxDistanceBlocks() == null
                ? new SourceQuote.Unavailable("狩猎", "已加载的范围里没有会掉出它的生物；不越界去找")
                : new SourceQuote.Offer("狩猎", SourceQuote.Offer.UNKNOWN_COUNT,
                        new AcquisitionCost(context.maxDistanceBlocks(), 7), "附近没有，出去找", "search").outToSearch(),
                List.of());

        GoalEstimate searching = engine().estimate(List.of(want(WOOL, 3)), PERMISSIONS, maxDistance(128));
        assertEquals(Effort.SEARCH, searching.effort());
        assertEquals(128.0, searching.farthestBlocks());
        assertNull(searching.roughSeconds(), "要出去找不编秒数");

        GoalEstimate blocked = engine().estimate(want(WOOL, 3), PERMISSIONS);
        assertEquals(Effort.BLOCKED, blocked.effort());
        assertTrue(blocked.how().contains("不越界去找"), blocked.how());

        hunt.offer(WOOL, (request, context) -> new SourceQuote.Offer("狩猎", 3, new AcquisitionCost(20, 7), null),
                List.of());
        assertEquals(Effort.NEARBY, engine().estimate(want(WOOL, 3), PERMISSIONS).effort());
    }

    @Test
    void 床的原料不带出门距离_附近没羊就是弄不到_羊毛的原因只讲一次() {
        bedRecipe();
        hunt.offer(WOOL, (request, context) -> context.maxDistanceBlocks() == null
                ? new SourceQuote.Unavailable("狩猎", "已加载的范围里没有会掉出白色羊毛的生物")
                : new SourceQuote.Offer("狩猎", 3, new AcquisitionCost(context.maxDistanceBlocks(), 7), null)
                        .outToSearch(), List.of());

        // 给了 max_distance 也只管床这一样：备羊毛问遍来源、不出门找。
        GoalEstimate estimate = engine().estimate(List.of(want(BED, 1)), PERMISSIONS, maxDistance(128));

        assertEquals(Effort.BLOCKED, estimate.effort());
        String how = estimate.how();
        assertTrue(how.contains("minecraft:white_wool ×3 弄不到"), how);
        int first = how.indexOf("已加载的范围里没有会掉出白色羊毛的生物");
        assertTrue(first >= 0, how);
        assertEquals(-1, how.indexOf("已加载的范围里没有会掉出白色羊毛的生物", first + 1), "同一个原因只讲一次：" + how);
    }

    @Test
    void 挑中的合成走不通_换成记得的箱子那条() {
        bedRecipe();
        // 箱子远得多，挑路时排在现做后面；现做走不通才轮到它。
        chest.offer(BED, (request, context) -> new SourceQuote.Offer("记得的箱子", 1, new AcquisitionCost(120, 4), null),
                List.of());

        GoalEstimate estimate = engine().estimate(want(BED, 1), PERMISSIONS);

        assertEquals(Effort.NEARBY, estimate.effort());
        assertEquals(120.0, estimate.farthestBlocks());
        assertTrue(estimate.how().startsWith("记得的箱子"), estimate.how());
    }

    @Test
    void 原料一样知道在哪一样要出去找_整件要出去找_还没看完加不用离开_整件估不了() {
        craft.offer(BED, (request, context) -> new SourceQuote.Offer("自己做", 1, new AcquisitionCost(0, 6), null, "bed"),
                List.of(want(WOOL, 3), want(PLANKS, 3)));
        mine.offer(PLANKS, (request, context) -> new SourceQuote.Offer("采掘", 3, new AcquisitionCost(12, 3), null),
                List.of());
        hunt.offer(WOOL, (request, context) -> new SourceQuote.Offer("狩猎", SourceQuote.Offer.UNKNOWN_COUNT,
                new AcquisitionCost(100, 7), null, "search").outToSearch(), List.of());
        assertEquals(Effort.SEARCH, engine().estimate(want(BED, 1), PERMISSIONS).effort());

        hunt.offer(WOOL, (request, context) -> new SourceQuote.NotYet("狩猎", "附近的生物还没看完"), List.of());
        mine.offer(PLANKS, (request, context) -> new SourceQuote.Offer("采掘", 3, new AcquisitionCost(2, 3), null),
                List.of());
        GoalEstimate looking = engine().estimate(want(BED, 1), PERMISSIONS);
        assertEquals(Effort.UNKNOWN, looking.effort());
        assertTrue(looking.stillLooking(), "附近还没看完，等一会儿再估可能就有了");
    }

    @Test
    void 原料链转回自己_那一样估不了_不卡住() {
        craft.offer(PLANKS, (request, context) -> new SourceQuote.Offer("自己做", 4, new AcquisitionCost(0, 5), null, "p"),
                List.of(want(LOG, 1)));
        craft.offer(LOG, (request, context) -> new SourceQuote.Offer("自己做", 1, new AcquisitionCost(0, 5), null, "l"),
                List.of(want(PLANKS, 9)));

        GoalEstimate estimate = engine().estimate(want(PLANKS, 4), PERMISSIONS);

        assertEquals(Effort.UNKNOWN, estimate.effort());
        assertTrue(estimate.how().contains("转回了它自己"), estimate.how());
    }

    @Test
    void 许可不让打羊_弄不到并说要放开哪项() {
        hunt.offer(WOOL, (request, context) -> new SourceQuote.NeedsApproval("狩猎",
                Problem.of(Problem.Kind.NEED_APPROVAL, "打羊要 kill_animals 许可", "放开 kill_animals")), List.of());

        GoalEstimate estimate = engine().estimate(want(WOOL, 1), PERMISSIONS);

        assertEquals(Effort.BLOCKED, estimate.effort());
        assertTrue(estimate.how().contains("要放开许可（打羊要 kill_animals 许可）"), estimate.how());
    }

    @Test
    void 一次要两样_外层取重的那档_秒数相加() {
        chest.offer(STICK, (request, context) -> new SourceQuote.Offer("记得的箱子", 4, new AcquisitionCost(3, 4), null),
                List.of());
        mine.offer(LOG, (request, context) -> new SourceQuote.Offer("采掘", 5, new AcquisitionCost(30, 4), null),
                List.of());
        ItemAcquisition engine = engine();
        GoalEstimate stick = engine.estimate(want(STICK, 4), PERMISSIONS);
        GoalEstimate log = engine.estimate(want(LOG, 4), PERMISSIONS);

        GoalEstimate both = engine.estimate(List.of(want(STICK, 4), want(LOG, 4)), PERMISSIONS, ItemAcquisition.Scope.ALL);

        assertEquals(Effort.READY, stick.effort());
        assertEquals(Effort.NEARBY, both.effort());
        assertEquals(2, both.items().size());
        assertEquals(stick.roughSeconds() + log.roughSeconds(), both.roughSeconds());
    }

    @Test
    void 几层都弄不到_说法长度有界() {
        // 床 ← 羊毛 ← 线 ← 蛛网……每层都弄不到：原因各讲一次，不一层套一层地抄。
        bedRecipe();
        craft.offer(WOOL, (request, context) -> new SourceQuote.Offer("自己做", 1, new AcquisitionCost(0, 5), null, "w"),
                List.of(want("minecraft:string", 4)));
        craft.offer("minecraft:string", (request, context) -> new SourceQuote.Offer("自己做", 9,
                new AcquisitionCost(0, 5), null, "s"), List.of(want("minecraft:cobweb", 1)));
        GoalEstimate estimate = engine().estimate(want(BED, 1), PERMISSIONS);
        assertEquals(Effort.BLOCKED, estimate.effort());
        assertTrue(estimate.how().length() < 2000, "说法应有界：" + estimate.how().length());
    }

    // 床在背包外的工作台上做：缺三块白色羊毛，木板身上有。
    private void bedRecipe() {
        craft.offer(BED, (request, context) -> new SourceQuote.Offer("自己做", 1, new AcquisitionCost(0, 6, 96),
                "附近没有工作台，会在身边放一个，做完挖下来收回背包", "bed"), List.of(want(WOOL, 3)));
    }

    /** 按物品答报价、答还缺哪几样原料的替身来源；没登记的物品一律给不了。动手只记次数。 */
    private static final class Stub implements ItemSource {
        private final String name;
        private final AcquireVia via;
        private final Map<String, BiFunction<ItemRequest, SourceContext, SourceQuote>> quotes = new HashMap<>();
        private final Map<String, List<ItemRequest>> missing = new HashMap<>();
        int begun;

        Stub(String name, AcquireVia via) {
            this.name = name;
            this.via = via;
        }

        void offer(String item, BiFunction<ItemRequest, SourceContext, SourceQuote> quote, List<ItemRequest> ingredients) {
            quotes.put(item, quote);
            missing.put(item, ingredients);
        }

        @Override public String describe() {
            return name;
        }

        @Override public AcquireVia via() {
            return via;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            var quote = quotes.get(request.wanted().specifier());
            return quote == null ? new SourceQuote.Unavailable(name, "没有" + request.wanted().specifier())
                    : quote.apply(request, context);
        }

        @Override public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer,
                SourceContext context) {
            return missing.getOrDefault(request.wanted().specifier(), List.of());
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            begun++;
            return Optional.empty();
        }
    }
}
