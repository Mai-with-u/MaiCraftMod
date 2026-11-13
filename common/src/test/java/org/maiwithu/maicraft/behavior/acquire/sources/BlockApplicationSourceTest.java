// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsBlockTypes;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplication;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplications;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 手工涂抹来源的离线与引擎串联验证：无去皮木料时经斧头去皮来源自动选木种并一条龙做出安山机壳与列车机壳，
 * 支持 keep_held_item 不消耗手持物、防环与缺料估算。
 */
class BlockApplicationSourceTest {

    private static final AcquireVia VIA = new AcquireVia(
            "item_application", "机械动力的手工涂抹：放下基底方块，拿材料右键变成机壳等再挖回");
    private static final WorldPosition HERE = new WorldPosition(0, 64, 0, "minecraft:overworld");
    private static final BlockPos SPOT = new BlockPos(1, 64, 0);

    private static final BlockApplication ANDESITE_CASING = new BlockApplication(
            "create:item_application/andesite_casing_from_log",
            List.of("minecraft:stripped_acacia_log", "minecraft:stripped_oak_log", "minecraft:stripped_spruce_log"),
            List.of("create:andesite_alloy"),
            false,
            "create:andesite_casing",
            1,
            true);

    private static final BlockApplication BRASS_CASING = new BlockApplication(
            "create:item_application/brass_casing_from_log",
            List.of("minecraft:stripped_acacia_log", "minecraft:stripped_oak_log"),
            List.of("create:brass_ingot"),
            false,
            "create:brass_casing",
            1,
            true);

    private static final BlockApplication RAILWAY_CASING = new BlockApplication(
            "create:item_application/railway_casing",
            List.of("create:brass_casing"),
            List.of("create:sturdy_sheet"),
            false,
            "create:railway_casing",
            1,
            true);

    private static final BlockApplication KEEP_TOOL_RECIPE = new BlockApplication(
            "modpack:item_application/carved_block",
            List.of("minecraft:stone"),
            List.of("modpack:chisel"),
            true,
            "modpack:carved_stone",
            1,
            true);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void chainsStrippingAndManualApplicationToMakeAndesiteAndRailwayCasingsFromRawOakLogs() {
        // 身上只有 3 根橡木原木、1 把铁斧、2 个安山合金、1 个黄铜锭与 1 张坚固板，一根去皮原木都没有：
        // 先要 2 个安山机壳，再要 1 个列车机壳（需先涂黄铜机壳再涂坚固板），全程自动选 stripped_oak_log 一条龙做成。
        Bag bag = new Bag();
        bag.put("minecraft:oak_log", 3);
        bag.put("minecraft:iron_axe", 1);
        bag.put("create:andesite_alloy", 2);
        bag.put("create:brass_ingot", 1);
        bag.put("create:sturdy_sheet", 1);

        List<BlockApplication> recipes = List.of(ANDESITE_CASING, BRASS_CASING, RAILWAY_CASING);
        SimWorld world = new SimWorld(bag, recipes);
        AtomicReference<ItemAcquisition> engineRef = new AtomicReference<>();
        ItemNeeds deferredNeeds = new ForwardingNeeds(engineRef);

        StrippingSource stripping = new StrippingSource(bag, null, tags(), deferredNeeds,
                allowAllPermissions(), world.seams(), null);
        BlockApplicationSource application = new BlockApplicationSource(
                tableOf(recipes), () -> Optional.of(recipes), bag, null, tags(), deferredNeeds,
                allowAllPermissions(), world.seams(), item -> 1);

        ItemAcquisition engine = new ItemAcquisition(
                List.of(stripping, application),
                bag, null, tags(), () -> HERE, Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        engineRef.set(engine);
        stripping.watchChain(engine::inProgress, engine::outerWanted);
        application.watchChain(engine::inProgress, engine::outerWanted);

        Action makeAndesite = engine.actionFor(
                new ItemRequest(WantedItem.ofItem("create:andesite_casing"), 2, "做机器机壳"), Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, drive(makeAndesite));
        assertEquals(2, bag.count("create:andesite_casing"));
        assertEquals(0, bag.count("create:andesite_alloy"));
        assertEquals(1, bag.count("minecraft:oak_log"), "只削了做 2 个安山机壳所需的 2 根橡木原木");
        assertTrue(world.blocks.isEmpty());

        Action makeRailway = engine.actionFor(
                new ItemRequest(WantedItem.ofItem("create:railway_casing"), 1, "做列车站"), Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, drive(makeRailway));
        assertEquals(1, bag.count("create:railway_casing"));
        assertEquals(0, bag.count("minecraft:oak_log"));
        assertEquals(0, bag.count("create:brass_ingot"));
        assertEquals(0, bag.count("create:sturdy_sheet"));
        assertTrue(world.blocks.isEmpty());
    }

    @Test
    void keepHeldItemRecipeNeedsOnlyOneToolAndDoesNotConsumeIt() {
        Bag bag = new Bag();
        bag.put("minecraft:stone", 4);
        List<BlockApplication> recipes = List.of(KEEP_TOOL_RECIPE);
        SimWorld world = new SimWorld(bag, recipes);
        AtomicReference<ItemAcquisition> engineRef = new AtomicReference<>();
        ForwardingNeeds needs = new ForwardingNeeds(engineRef);
        BlockApplicationSource source = new BlockApplicationSource(
                tableOf(recipes), () -> Optional.of(recipes), bag, null, tags(), needs,
                allowAllPermissions(), world.seams(), item -> 1);

        ItemRequest request = new ItemRequest(WantedItem.ofItem("modpack:carved_stone"), 4, "雕刻石块");
        SourceContext context = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));
        List<ItemRequest> missing = source.missingIngredients(request, offer, context);
        assertEquals(1, missing.size(), "石头已够 4 块，keep_held_item 只缺 1 把凿子");
        assertEquals("modpack:chisel", missing.getFirst().wanted().specifier());
        assertEquals(1, missing.getFirst().count());

        bag.put("modpack:chisel", 1);
        Action run = source.begin(request, offer, context).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, drive(run));
        assertEquals(4, bag.count("modpack:carved_stone"));
        assertEquals(1, bag.count("modpack:chisel"), "keep_held_item 为真时手持物保留不消耗");
    }

    private static BlockApplications tableOf(List<BlockApplication> recipes) {
        return new BlockApplications() {
            @Override public AcquireVia via() { return VIA; }
            @Override public List<BlockApplication> recipes(Level level) { return recipes; }
        };
    }

    private static ActionStatus drive(Action action) {
        TickContext tick = TickContext.of(1, null);
        for (int i = 0; i < 120; i++) {
            ActionStatus status = action.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("动作在 120 刻内未结束");
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

    private static final class ForwardingNeeds implements ItemNeeds {
        private final AtomicReference<ItemAcquisition> target;

        ForwardingNeeds(AtomicReference<ItemAcquisition> target) {
            this.target = target;
        }

        @Override
        public Action actionFor(ItemRequest request, Permissions permissions) {
            return target.get().actionFor(request, permissions);
        }

        @Override
        public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
            return target.get().actionFor(request, permissions, records);
        }

        @Override
        public Action actionForTool(ItemRequest request, Permissions permissions, TaskRecords records) {
            return target.get().actionForTool(request, permissions, records);
        }

        @Override
        public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions, boolean includeMaking) {
            return target.get() == null ? Optional.empty()
                    : target.get().cheapestCost(request, permissions, includeMaking);
        }
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

    private static final class SimWorld implements PlacesBlockBeside, ReadsBlockTypes, CollectsBlocks {
        private final Bag bag;
        private final List<BlockApplication> recipes;
        final Map<BlockPos, String> blocks = new HashMap<>();

        SimWorld(Bag bag, List<BlockApplication> recipes) {
            this.bag = bag;
            this.recipes = recipes;
        }

        InWorldBlockTransform.Seams seams() {
            return new InWorldBlockTransform.Seams(this, this, this::apply, this, null);
        }

        @Override
        public Optional<Action> placeBeside(String blockType, Consumer<BlockPos> placedAt) {
            return Optional.of(done(() -> {
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
            return Optional.of(done(() -> {
                if (!held.isTag()) {
                    boolean keep = recipes.stream()
                            .filter(r -> r.resultId().equals(resultBlockId) && r.heldItems().contains(held.itemId()))
                            .anyMatch(BlockApplication::keepHeldItem);
                    if (!keep) bag.take(held.itemId(), 1);
                }
                blocks.put(cell, resultBlockId);
            }));
        }

        @Override
        public Optional<Action> collect(BlockPos cell, Permissions permissions) {
            return Optional.of(done(() -> {
                String removed = blocks.remove(cell);
                if (removed != null) bag.put(removed, 1);
            }));
        }

        private static Action done(Runnable effect) {
            return new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    effect.run();
                    return ActionStatus.done();
                }

                @Override public String describe() { return "模拟一步"; }
            };
        }
    }
}
