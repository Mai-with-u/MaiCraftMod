// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsBlockTypes;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 斧头去皮来源与原地摆块改造动作的离线验证：原版 21 种去皮映射提取、标签选料与防环、
 * 先备斧头再补齐被做斧头消耗的原木、逐块摆削收与半途失败自动挖回临时木桩。
 */
class StrippingSourceTest {

    private static final WorldPosition HERE = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final BlockPos SPOT = new BlockPos(11, 64, 10);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaStrippablesCoversAllLogsWoodsStemsHyphaeAndBambooBlock() {
        Map<String, String> pairs = VanillaStrippables.resultToRaw();
        assertEquals(21, pairs.size());
        assertEquals("minecraft:oak_log", pairs.get("minecraft:stripped_oak_log"));
        assertEquals("minecraft:cherry_log", pairs.get("minecraft:stripped_cherry_log"));
        assertEquals("minecraft:mangrove_wood", pairs.get("minecraft:stripped_mangrove_wood"));
        assertEquals("minecraft:crimson_stem", pairs.get("minecraft:stripped_crimson_stem"));
        assertEquals("minecraft:warped_hyphae", pairs.get("minecraft:stripped_warped_hyphae"));
        assertEquals("minecraft:bamboo_block", pairs.get("minecraft:stripped_bamboo_block"));
    }

    @Test
    void picksCarriedWoodForStrippedTagAndSkipsPlainLogsTagOrOuterWanted() {
        Bag bag = new Bag();
        bag.put("minecraft:iron_axe", 1);
        bag.put("minecraft:spruce_log", 4);
        FakeWorld world = new FakeWorld(bag);
        StrippingSource source = new StrippingSource(bag, null, tags(), world, allowAllPermissions(), world.seams(), null);

        // 要 #c:stripped_logs 时身上只有云杉原木，直接挑 stripped_spruce_log，缺料为 0。
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(new ItemRequest(WantedItem.ofTag("c:stripped_logs"), 3, "做机壳"),
                        SourceContext.unscoped(HERE, Permissions.DEFAULT)));
        assertEquals("minecraft:stripped_spruce_log", offer.hint());
        assertEquals(0.0, offer.cost().shortfallBlocks());

        // 要 #minecraft:logs 时未去皮原木已满足标签，不白削皮。
        assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(new ItemRequest(WantedItem.ofTag("minecraft:logs"), 2, "烧木炭"),
                        SourceContext.unscoped(HERE, Permissions.DEFAULT)));

        // 外层正要留着 spruce_log 时不拿它去削皮。
        source.watchChain(List::of,
                () -> List.of(new ItemRequest(WantedItem.ofItem("minecraft:spruce_log"), 1, "外层保留")));
        SourceQuote.Offer switched = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(new ItemRequest(WantedItem.ofTag("c:stripped_logs"), 2, "做机壳"),
                        SourceContext.unscoped(HERE, Permissions.DEFAULT)));
        assertFalse("minecraft:stripped_spruce_log".equals(switched.hint()));
    }

    @Test
    void preparesAxeFirstThenReplenishesLogsSpentCraftingAxeAndStripsOneByOne() {
        // 开场只有 3 根橡木原木、没斧头，要 3 根去皮橡木原木：
        // 备斧头消耗 2 根橡木原木后，备原木步骤自动补回 2 根，最后三轮逐块摆下、削皮、挖回，地面清空。
        Bag bag = new Bag();
        bag.put("minecraft:oak_log", 3);
        FakeWorld world = new FakeWorld(bag);
        world.onPrepareTool = () -> {
            bag.take("minecraft:oak_log", 2);
            bag.put("minecraft:wooden_axe", 1);
        };
        StrippingSource source = new StrippingSource(bag, null, tags(), world, allowAllPermissions(), world.seams(), null);

        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:stripped_oak_log"), 3, "手工涂抹基底");
        SourceContext context = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));

        Action run = source.begin(request, offer, context).orElseThrow();
        ActionStatus status = drive(run);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(3, bag.count("minecraft:stripped_oak_log"));
        assertEquals(0, bag.count("minecraft:oak_log"));
        assertEquals(2, world.fetchedRawCount, "做斧头用掉 2 根原木后应精确补采 2 根");
        assertTrue(world.blocks.isEmpty(), "每块削完当场挖回，地面不留任何木桩");
    }

    @Test
    void reclaimsPlacedLogWhenStrippingFailsMidway() {
        Bag bag = new Bag();
        bag.put("minecraft:oak_log", 1);
        bag.put("minecraft:stone_axe", 1);
        FakeWorld world = new FakeWorld(bag);
        world.failApply = Problem.of(Problem.Kind.REFUSED_BY_GAME, "领地保护禁止去皮", null);
        RecordingTaskRecords records = new RecordingTaskRecords();
        StrippingSource source = new StrippingSource(bag, null, tags(), world, allowAllPermissions(), world.seams(), null);

        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:stripped_oak_log"), 1, "去皮");
        SourceContext context = SourceContext.booked(HERE, Permissions.DEFAULT, records);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));

        Action run = source.begin(request, offer, context).orElseThrow();
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, drive(run));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
        assertTrue(world.blocks.isEmpty(), "去皮失败后刚放下的原木已被自动挖回");
        assertEquals(1, bag.count("minecraft:oak_log"), "原木已收回背包");
        assertFalse(records.attempts.isEmpty());
    }

    @Test
    void reportsMissingIngredientsAndChecksPlacementPermission() {
        Bag bag = new Bag();
        bag.put("minecraft:oak_log", 1);
        FakeWorld world = new FakeWorld(bag);
        StrippingSource source = new StrippingSource(bag, null, tags(), world, allowAllPermissions(), world.seams(), null);

        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:stripped_oak_log"), 4, "去皮");
        SourceContext allowed = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, allowed));
        List<ItemRequest> missing = source.missingIngredients(request, offer, allowed);
        assertEquals(2, missing.size());
        assertEquals("#minecraft:axes", missing.get(0).wanted().specifier());
        assertEquals(1, missing.get(0).count());
        assertEquals("minecraft:oak_log", missing.get(1).wanted().specifier());
        assertEquals(3, missing.get(1).count());

        Permissions noChanges = new Permissions(Permissions.BlockChanges.NONE, Permissions.Fight.SELF_DEFENSE,
                false, Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());
        assertInstanceOf(SourceQuote.NeedsApproval.class,
                source.quote(request, SourceContext.unscoped(HERE, noChanges)));
    }

    private static ActionStatus drive(Action action) {
        TickContext tick = TickContext.of(1, null);
        for (int i = 0; i < 64; i++) {
            ActionStatus status = action.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("动作在 64 刻内未结束");
    }

    private static PermissionCheck allowAllPermissions() {
        return new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), List::of, name -> Optional.empty(), "self"),
                entityId -> Optional.empty());
    }

    private static ReadsItemTags tags() {
        return itemId -> {
            if (itemId.endsWith("_axe")) return Set.of("minecraft:axes");
            if (itemId.startsWith("minecraft:stripped_") && itemId.endsWith("_log")) {
                return Set.of("minecraft:logs", "c:stripped_logs");
            }
            if (itemId.endsWith("_log")) return Set.of("minecraft:logs");
            return Set.of();
        };
    }

    private static final class Bag implements BackpackView {
        private final Map<String, Integer> items = new LinkedHashMap<>();

        void put(String itemId, int count) {
            if (count <= 0) return;
            items.merge(itemId, count, Integer::sum);
        }

        void take(String itemId, int count) {
            int have = items.getOrDefault(itemId, 0);
            if (have <= count) items.remove(itemId);
            else items.put(itemId, have - count);
        }

        int count(String itemId) {
            return items.getOrDefault(itemId, 0);
        }

        @Override
        public List<BackpackStack> stacks() {
            List<BackpackStack> list = new ArrayList<>();
            items.forEach((id, count) -> list.add(new BackpackStack(id, count, 64, false, false, false, false)));
            return list;
        }

        @Override public int usedSlots() { return items.size(); }
        @Override public int totalSlots() { return 36; }
    }

    private static final class FakeWorld implements ItemNeeds, PlacesBlockBeside, ReadsBlockTypes, CollectsBlocks {
        private final Bag bag;
        final Map<BlockPos, String> blocks = new HashMap<>();
        Runnable onPrepareTool = () -> {};
        Problem failApply;
        int fetchedRawCount;

        FakeWorld(Bag bag) {
            this.bag = bag;
        }

        InWorldBlockTransform.Seams seams() {
            return new InWorldBlockTransform.Seams(this, this, this::apply, this, null);
        }

        @Override
        public Action actionFor(ItemRequest request, Permissions permissions) {
            return doneAction(() -> {
                if (!request.wanted().isTag()) {
                    bag.put(request.wanted().itemId(), request.count());
                    fetchedRawCount += request.count();
                }
            });
        }

        @Override
        public Action actionForTool(ItemRequest request, Permissions permissions, TaskRecords records) {
            return doneAction(onPrepareTool);
        }

        @Override
        public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions, boolean includeMaking) {
            return Optional.of(10.0);
        }

        @Override
        public Optional<Action> placeBeside(String blockType, Consumer<BlockPos> placedAt) {
            return Optional.of(doneAction(() -> {
                bag.take(blockType, 1);
                blocks.put(SPOT, blockType);
                placedAt.accept(SPOT);
            }));
        }

        @Override
        public Optional<String> blockTypeAt(WorldPosition at) {
            return Optional.ofNullable(blocks.get(new BlockPos(at.x(), at.y(), at.z())));
        }

        private Optional<Action> apply(BlockPos cell, WantedItem held, String resultBlockId, boolean intoAir) {
            return Optional.of(new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    if (failApply != null) return ActionStatus.failed(failApply);
                    blocks.put(cell, resultBlockId);
                    return ActionStatus.done();
                }

                @Override public String describe() { return "削皮"; }
            });
        }

        @Override
        public Optional<Action> collect(BlockPos cell, Permissions permissions) {
            return Optional.of(doneAction(() -> {
                String removed = blocks.remove(cell);
                if (removed != null) bag.put(removed, 1);
            }));
        }

        private static Action doneAction(Runnable effect) {
            return new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    effect.run();
                    return ActionStatus.done();
                }

                @Override public String describe() { return "一步测试动作"; }
            };
        }
    }

    private static final class RecordingTaskRecords implements TaskRecords {
        final List<Change> changes = new ArrayList<>();
        final List<Attempt> attempts = new ArrayList<>();

        @Override public void change(Change change) { changes.add(change); }
        @Override public void unconfirmed(Change change) {}
        @Override public void attempt(String what, String outcome) { attempts.add(new Attempt(what, outcome)); }
    }
}
