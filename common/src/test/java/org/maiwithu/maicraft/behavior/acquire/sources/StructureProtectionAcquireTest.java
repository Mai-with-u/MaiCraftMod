// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.field.CropSpot;
import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.MinableSpot;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.mining.ScansMinables;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.FarmlandIrrigation;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.ReadsStructureProtection;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 取材与改造入口接入「结构保护区」回归测试：
 * 覆盖采掘（MiningSource）避开机器/建筑、自动装水（WaterFillSource）避开圈地/建筑/机器/农田灌溉水、
 * 收回工作站（ReclaimSource）避开建筑/机器、原地摆块改造（InWorldBlockTransform）底线防护，
 * 以及建筑内成熟作物收割（HarvestSource）默认放行。
 */
class StructureProtectionAcquireTest {

    private static final String DIM = "minecraft:overworld";
    private static final String SELF = "self-id";

    @TempDir
    Path tempDir;

    @Test
    void 建筑内成熟作物收割默认放行_不因结构保护而拦截自家农场收菜() {
        BlockPos cropCell = new BlockPos(10, 65, 10);
        WorldPosition cropPos = new WorldPosition(10, 65, 10, DIM);
        ReadsStructureProtection structures = pos -> pos.equals(cropPos)
                ? StructureProtection.building("温室农场") : StructureProtection.NONE;
        Protection protection = protectionWith(List.of(), structures, FarmlandIrrigation.WaterGrid.EMPTY);
        PermissionCheck check = new PermissionCheck(protection, id -> Optional.empty());

        HarvestSource harvest = new HarvestSource(
                (wanted, dropsFrom, center, radius) -> List.of(new CropSpot(cropCell, "minecraft:wheat")),
                instantCollect(), check);
        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:wheat"), 1, "收菜");
        SourceContext ctx = SourceContext.unscoped(new WorldPosition(10, 65, 12, DIM), Permissions.DEFAULT);

        SourceQuote quote = harvest.quote(req, ctx);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote, "建筑内成熟作物应放行收割");
        assertTrue(harvest.begin(req, offer, ctx).isPresent(), "应能生成收割动作");
    }

    @Test
    void 采掘自动避开在役机器与已登记建筑结构保护格() {
        BlockPos machineCell = new BlockPos(5, 64, 5);
        WorldPosition machinePos = new WorldPosition(5, 64, 5, DIM);
        ReadsStructureProtection structures = pos -> pos.equals(machinePos)
                ? StructureProtection.machine("刷石机") : StructureProtection.NONE;
        Protection protection = protectionWith(List.of(), structures, FarmlandIrrigation.WaterGrid.EMPTY);
        PermissionCheck check = new PermissionCheck(protection, id -> Optional.empty());

        ScansMinables scans = new ScansMinables() {
            @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) { return true; }
            @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom,
                    WorldPosition center, int radius) {
                return List.of(new MinableSpot(machineCell, "minecraft:cobblestone", "minecraft:cobblestone"));
            }
            @Override public boolean scanComplete() { return true; }
            @Override public boolean buriedUnderfoot(WantedItem wanted) { return false; }
            @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
                return "minecraft:cobblestone".equals(blockTypeId);
            }
        };
        ReadsToolRequirements tools = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockTypeId) { return Optional.empty(); }
            @Override public boolean sufficient(String toolItemId, String blockTypeId) { return true; }
        };
        MiningSource mining = new MiningSource(scans, instantCollect(), null,
                tools, check, emptyBackpack(), null, id -> Set.of(), null, null);
        SourceQuote quote = mining.quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 1, "取圆石"),
                SourceContext.unscoped(new WorldPosition(0, 64, 0, DIM), Permissions.DEFAULT));
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("刷石机"), unavailable.reason());
    }

    @Test
    void 采掘混合场景下begin跳过更近的建筑与机器格只挖远处野外格() {
        // 近处 (2,64,0) 是建筑墙、近处 (3,64,0) 是在役刷石机，远处 (12,64,0) 才是野外天然圆石；
        // quote 与 begin 重扫都必须避开更近的受保护结构，只挑选远处野外格。
        BlockPos buildingCell = new BlockPos(2, 64, 0);
        BlockPos machineCell = new BlockPos(3, 64, 0);
        BlockPos wildCell = new BlockPos(12, 64, 0);
        WorldPosition buildingPos = new WorldPosition(2, 64, 0, DIM);
        WorldPosition machinePos = new WorldPosition(3, 64, 0, DIM);

        ReadsStructureProtection structures = pos -> {
            if (pos.equals(buildingPos)) return StructureProtection.building("石堡小屋");
            if (pos.equals(machinePos)) return StructureProtection.machine("刷石机");
            return StructureProtection.NONE;
        };
        Protection protection = protectionWith(List.of(), structures, FarmlandIrrigation.WaterGrid.EMPTY);
        PermissionCheck check = new PermissionCheck(protection, id -> Optional.empty());

        ScansMinables scans = new ScansMinables() {
            @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) { return true; }
            @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom,
                    WorldPosition center, int radius) {
                return List.of(
                        new MinableSpot(buildingCell, "minecraft:cobblestone", "minecraft:cobblestone"),
                        new MinableSpot(machineCell, "minecraft:cobblestone", "minecraft:cobblestone"),
                        new MinableSpot(wildCell, "minecraft:cobblestone", "minecraft:cobblestone"));
            }
            @Override public boolean scanComplete() { return true; }
            @Override public boolean buriedUnderfoot(WantedItem wanted) { return false; }
            @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
                return "minecraft:cobblestone".equals(blockTypeId);
            }
        };
        ReadsToolRequirements tools = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockTypeId) { return Optional.empty(); }
            @Override public boolean sufficient(String toolItemId, String blockTypeId) { return true; }
        };
        List<BlockPos> picked = new ArrayList<>();
        CollectsBlocks recording = (cell, permissions) -> {
            picked.add(cell);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "收 " + cell.toShortString(); }
            });
        };

        MiningSource mining = new MiningSource(scans, recording, null,
                tools, check, emptyBackpack(), null, id -> Set.of(), null, null);
        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 1, "取圆石");
        SourceContext ctx = SourceContext.unscoped(new WorldPosition(0, 64, 0, DIM), Permissions.DEFAULT);

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, mining.quote(req, ctx));
        assertEquals(1, offer.obtainableCount(), "应只统计 1 格野外可挖圆石");
        Action action = mining.begin(req, offer, ctx).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, action.tick(TickContext.of(1, null)));
        assertEquals(List.of(wildCell), picked, "begin 必须跳过更近的建筑与机器格，只挖远处野外格");
    }

    @Test
    void 自动装水同时过滤圈地保护与建筑机器农田灌溉结构保护() {
        WorldPosition regionWater = new WorldPosition(1, 64, 1, DIM);
        WorldPosition buildingPool = new WorldPosition(2, 64, 2, DIM);
        WorldPosition machineWater = new WorldPosition(3, 64, 3, DIM);
        WorldPosition farmWater = new WorldPosition(4, 64, 4, DIM);
        WorldPosition wildRiver = new WorldPosition(5, 64, 5, DIM);

        ReadsStructureProtection structures = new ReadsStructureProtection() {
            @Override public StructureProtection blockAt(WorldPosition pos) {
                return pos.equals(machineWater) ? StructureProtection.machine("洗矿机") : StructureProtection.NONE;
            }
            @Override public StructureProtection fluidAt(WorldPosition pos) {
                if (pos.equals(buildingPool)) return StructureProtection.building("喷泉广场");
                return blockAt(pos);
            }
        };
        FarmlandIrrigation.WaterGrid farmGrid = new FarmlandIrrigation.WaterGrid() {
            @Override public String dimension() { return DIM; }
            @Override public boolean isWaterSource(BlockPos pos) { return pos.equals(new BlockPos(4, 64, 4)); }
            @Override public boolean isSolidFloor(BlockPos pos) { return true; }
            @Override public boolean isFarmland(BlockPos pos) { return pos.equals(new BlockPos(6, 64, 4)); }
        };
        Protection protection = protectionWith(
                List.of(new RememberedRegion("邻居领地", regionWater, 1)), structures, farmGrid);

        assertFalse(WaterFillSource.isAllowedWater(protection, regionWater, Set.of()), "圈地内水格拦截");
        assertFalse(WaterFillSource.isAllowedWater(protection, buildingPool, Set.of()), "建筑外框水池拦截");
        assertFalse(WaterFillSource.isAllowedWater(protection, machineWater, Set.of()), "在役机器水源拦截");
        assertFalse(WaterFillSource.isAllowedWater(protection, farmWater, Set.of()), "农田非无限灌溉水拦截");
        assertTrue(WaterFillSource.isAllowedWater(protection, wildRiver, Set.of()), "野外不受保护河水放行");
    }

    @Test
    void 收回工作站避开已登记建筑与在役机器内的工作台() {
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("reclaim.sqlite")), "w1");
        WorldPosition benchInBuilding = new WorldPosition(30, 64, 30, DIM);
        WorldPosition benchOutside = new WorldPosition(35, 64, 35, DIM);
        memory.workstationSeen(benchInBuilding, "minecraft:crafting_table", Instant.now());
        memory.workstationSeen(benchOutside, "minecraft:crafting_table", Instant.now());

        ReadsStructureProtection structures = pos -> pos.equals(benchInBuilding)
                ? StructureProtection.building("工坊小屋") : StructureProtection.NONE;
        ReadsBlockOwnership selfOwned = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String d, int x, int y, int z) {
                return Optional.of(new PlacedBy(SELF));
            }
            @Override public boolean known(String d, int x, int y, int z) { return true; }
        };
        Protection protection = new Protection(selfOwned, List::of, name -> Optional.empty(), SELF,
                SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id, structures, FarmlandIrrigation.WaterGrid.EMPTY);
        PermissionCheck check = new PermissionCheck(protection, id -> Optional.empty());

        ReclaimSource reclaim = new ReclaimSource(memory, at -> Optional.of("minecraft:crafting_table"),
                protection, check, instantCollect(), id -> Set.of());
        // 建筑里的工作台离角色更近 (30,64,30)，但受结构保护；必须跳过它而选建筑外 (35,64,35) 的工作台。
        SourceQuote quote = reclaim.quote(new ItemRequest(WantedItem.ofItem("minecraft:crafting_table"), 1, "用台子"),
                SourceContext.unscoped(new WorldPosition(30, 64, 31, DIM), Permissions.DEFAULT));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertTrue(offer.risk().contains("35, 64, 35"), "应跳过建筑内工作台，挑野外工作台：" + offer.risk());
        assertEquals(1, offer.obtainableCount());
    }

    @Test
    void 原地摆块改造落入结构保护区时直接安全拦截不挖毁结构() {
        BlockPos protectedCell = new BlockPos(8, 64, 8);
        ReadsStructureProtection structures = pos -> pos.x() == 8 && pos.y() == 64 && pos.z() == 8
                ? StructureProtection.machine("动力锯") : StructureProtection.NONE;
        Protection protection = protectionWith(List.of(), structures, FarmlandIrrigation.WaterGrid.EMPTY);

        PlacesBlockBeside placer = (blockType, placedAt) -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                placedAt.accept(protectedCell);
                return ActionStatus.done();
            }
            @Override public String describe() { return "放块"; }
        });
        boolean[] collected = {false};
        CollectsBlocks collects = (cell, permissions) -> {
            collected[0] = true;
            return Optional.empty();
        };
        InWorldBlockTransform.Seams seams = new InWorldBlockTransform.Seams(
                placer, at -> Optional.of("minecraft:oak_log"),
                (cell, held, result, intoAir) -> Optional.empty(), collects, null, protection);
        InWorldBlockTransform transform = new InWorldBlockTransform("minecraft:oak_log",
                WantedItem.ofTag("minecraft:axes"), "minecraft:stripped_oak_log", false, 1,
                Permissions.DEFAULT, null, seams);

        ActionStatus status = transform.tick(TickContext.of(1, null));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("动力锯"), failed.problem().message());
        assertFalse(collected[0], "不得尝试挖回结构保护区内的格子");
    }

    private static Protection protectionWith(List<RememberedRegion> regions,
            ReadsStructureProtection structures, FarmlandIrrigation.WaterGrid waterGrid) {
        return new Protection((d, x, y, z) -> Optional.empty(), () -> regions, name -> Optional.empty(),
                SELF, SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id, structures, waterGrid);
    }

    private static CollectsBlocks instantCollect() {
        return (cell, permissions) -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return "收 " + cell.toShortString(); }
        });
    }

    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
    }
}
