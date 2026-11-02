// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.cook.BrewPlan;
import org.maiwithu.maicraft.behavior.acquire.cook.BrewStep;
import org.maiwithu.maicraft.behavior.acquire.cook.BrewingRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.SetsUpWorkstation;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 酿造台这条路：只酿点名了种类的药水，水瓶指去装；记得的酿造台走过去用，没有就报身边摆一个（没许可、没接缝如实说）；
 * 开第一炉前只备第一组的瓶子和第一步的材料；点开发现放着别人东西的那台，下回问价先不算它。
 */
class BrewingSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final WorldPosition HERE = WorldPosition.here(0, 64, 0);
    private static final SourceContext CONTEXT = SourceContext.unscoped(HERE, Permissions.DEFAULT);
    private static final StackKey WATER = BrewPlan.WATER_BOTTLE;
    private static final StackKey AWKWARD = new StackKey(StackKey.PLAIN_POTION, "minecraft:awkward");
    private static final StackKey FIRE = new StackKey(StackKey.PLAIN_POTION, "minecraft:fire_resistance");
    private static final List<BrewStep> TABLE = List.of(
            new BrewStep(WATER, "minecraft:nether_wart", AWKWARD),
            new BrewStep(AWKWARD, "minecraft:magma_cream", FIRE));

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeTags tags = new FakeTags();
    /** 备料时回引擎要过什么。 */
    private final List<ItemRequest> asked = new ArrayList<>();
    /** 酿造台动作拿到的那几台。 */
    private final List<List<WorldPosition>> brewedAt = new ArrayList<>();
    /** 推进时报"这台放着别人东西"的那一台；为空就不报。 */
    private WorldPosition occupiedStand;
    private SetsUpWorkstation placer = (blockType, placedAt) -> Optional.of(done("摆" + blockType));

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    // 备料替身：记下要了什么，当场做完。
    private ItemNeeds needs() {
        return (request, permissions) -> {
            asked.add(request);
            return done("备" + request.wanted().describe());
        };
    }

    // 酿造台动作替身：记下拿到哪几台；推进时要报占用的就报一声，当场做完。
    private BrewingRuns runs() {
        return (plan, stands, permissions, records, needs, carried, occupied) -> {
            brewedAt.add(stands);
            Consumer<WorldPosition> report = occupied;
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    if (occupiedStand != null) report.accept(occupiedStand);
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "酿 " + plan.target().written();
                }
            });
        };
    }

    private BrewingSource source(WorldMemory memory) {
        return new BrewingSource(() -> TABLE, memory, runs(), placer, needs(), backpack, new FakeOffhand(), tags);
    }

    private static ItemRequest wanted(StackKey key, int count) {
        return new ItemRequest(WantedItem.ofItem(key.written()), count, "测试");
    }

    private static Action done(String what) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }
            @Override public String describe() {
                return what;
            }
        };
    }

    private static ActionStatus runToEnd(Action action) {
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 50 && !(status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed);
                tick++) {
            status = action.tick(new StubTick(tick));
        }
        return status;
    }

    @Test
    void 不是药水_没说种类_要水瓶_各说一句该怎么办() {
        BrewingSource source = source(memory());
        SourceQuote stone = source.quote(new ItemRequest(WantedItem.ofItem("minecraft:stone"), 1, "测试"), CONTEXT);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, stone).reason().contains("只酿药水"));
        SourceQuote plain = source.quote(new ItemRequest(WantedItem.ofItem(StackKey.PLAIN_POTION), 1, "测试"), CONTEXT);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, plain).reason().contains("potion"));
        SourceQuote water = source.quote(wanted(WATER, 1), CONTEXT);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, water).reason().contains("fill"));
    }

    @Test
    void 酿造表里一路酿不出的_说没有步骤() {
        SourceQuote quote = source(memory()).quote(
                wanted(new StackKey(StackKey.PLAIN_POTION, "minecraft:luck"), 1), CONTEXT);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, quote).reason().contains("没有一路酿得出"));
    }

    @Test
    void 记得的酿造台按路程报价_要等的按炉数算() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(WorldPosition.here(30, 64, 40), "minecraft:brewing_stand", NOW);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source(memory).quote(wanted(FIRE, 3), CONTEXT));
        assertEquals(50, offer.cost().distanceBlocks(), 0.001);
        assertEquals(2 * 400, offer.cost().waitTicks(), "一组两步两炉");
        assertTrue(offer.risk().contains("30, 64, 40"), offer.risk());
    }

    @Test
    void 没记得酿造台_有许可就报身边摆一个_没许可没接缝如实说() {
        assertInstanceOf(SourceQuote.Offer.class, source(memory()).quote(wanted(FIRE, 1), CONTEXT));

        Permissions noBlocks = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        SourceQuote refused = source(memory()).quote(wanted(FIRE, 1), SourceContext.unscoped(HERE, noBlocks));
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, refused).reason().contains("许可"));

        placer = null;
        SourceQuote noPlacer = source(memory()).quote(wanted(FIRE, 1), CONTEXT);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, noPlacer).reason().contains("用过一次才会记得"));
    }

    @Test
    void 开第一炉前只备第一组的瓶子和第一步的材料() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(WorldPosition.here(5, 64, 0), "minecraft:brewing_stand", NOW);
        BrewingSource source = source(memory);
        ItemRequest request = wanted(FIRE, 5);
        SourceQuote.Offer offer = (SourceQuote.Offer) source.quote(request, CONTEXT);
        Action action = source.begin(request, offer, CONTEXT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runToEnd(action));
        assertEquals(List.of(WATER.written() + "×3", "minecraft:nether_wart×1"),
                asked.stream().map(each -> each.wanted().specifier() + "×" + each.count()).toList(),
                "岩浆膏和第二组的瓶子等的时候再弄");
        assertEquals(1, brewedAt.size());
    }

    @Test
    void 身上有够数的粗制的药水_从它接着酿_不再要水瓶() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(WorldPosition.here(5, 64, 0), "minecraft:brewing_stand", NOW);
        backpack.stacks.add(new BackpackStack(StackKey.PLAIN_POTION, 1, 1, false, false, false, false, AWKWARD.potion()));
        backpack.stacks.add(new BackpackStack(StackKey.PLAIN_POTION, 1, 1, false, false, false, false, AWKWARD.potion()));
        backpack.add("minecraft:magma_cream", 1);
        BrewingSource source = source(memory);
        ItemRequest request = wanted(FIRE, 2);
        SourceQuote.Offer offer = (SourceQuote.Offer) source.quote(request, CONTEXT);
        assertEquals(0, offer.cost().shortfallBlocks(), 0.001, "瓶子和材料都齐了");
        source.begin(request, offer, CONTEXT).orElseThrow();
        assertTrue(asked.isEmpty(), "不再去要水瓶和地狱疣：" + asked);
    }

    @Test
    void 点开放着别人东西的那台_下回问价先不算它() {
        WorldMemory memory = memory();
        WorldPosition stand = WorldPosition.here(5, 64, 0);
        memory.rememberWorkstationUsed(stand, "minecraft:brewing_stand", NOW);
        BrewingSource source = source(memory);
        ItemRequest request = wanted(FIRE, 1);
        SourceQuote.Offer first = (SourceQuote.Offer) source.quote(request, CONTEXT);
        assertTrue(first.risk().contains("5, 64, 0"), first.risk());
        occupiedStand = stand;
        runToEnd(source.begin(request, first, CONTEXT).orElseThrow());

        SourceQuote.Offer second = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        assertTrue(second.risk().contains("身边放一个"), "那台不算了，改报身边摆：" + second.risk());
    }
}
