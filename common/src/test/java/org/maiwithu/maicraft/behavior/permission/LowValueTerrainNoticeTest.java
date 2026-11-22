// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 低价值天然地形归属上报降噪离线单测：
 * 覆盖通用材质分级纯函数（不硬编码模组方块 ID）、1 格草方块豁免、3 格黑沙豁免、
 * 4 格草方块合并汇总报 1 条、以及 1 格木桶/红石/齿轮箱必报（同时验证 OthersThingsNotice 与清障链路）。
 */
class LowValueTerrainNoticeTest {

    private static final String SELF_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String PLAYER_A_UUID = "00000000-0000-0000-0000-00000000000a";
    private static final String DIM = "minecraft:overworld";

    /** 模拟第三方模组黑沙类型：仅用于按类层次校验 FallingBlock 识别，不在冻结注册表后实例化。 */
    private abstract static class ModBlackSand extends FallingBlock {
        ModBlackSand(BlockBehaviour.Properties properties) {
            super(properties);
        }
    }

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Protection protection() {
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.of(new PlacedBy(PLAYER_A_UUID));
            }
            @Override public boolean known(String dimension, int x, int y, int z) {
                return true;
            }
        };
        ReadsOwnHome home = new ReadsOwnHome() {
            @Override public Optional<WorldPosition> sleptBed() {
                return Optional.of(new WorldPosition(0, 64, 0, DIM));
            }
            @Override public List<Building> ownBuildings() {
                return List.of();
            }
        };
        ReadsBlockOwnership selfBedOwnership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                if (x == 0 && y == 64 && z == 0) return Optional.of(new PlacedBy(SELF_UUID));
                return ownership.whoPlaced(dimension, x, y, z);
            }
            @Override public boolean known(String dimension, int x, int y, int z) {
                return true;
            }
        };
        return new Protection(selfBedOwnership, List::of, name -> Optional.empty(), SELF_UUID,
                SeesVillagers.NOBODY, home, uuid -> PLAYER_A_UUID.equals(uuid) ? "玩家甲" : uuid);
    }

    private static TaskEventSink goalBoundSink(TaskEventLog log) {
        return new TaskEventSink() {
            @Override public void publish(TaskEvent.Kind kind, String message) {
                log.publish(kind, message);
            }
            @Override public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
                log.appendForGoal(kind, 73L, "maicraft:build", message, facts);
            }
        };
    }

    @Test
    void 通用材质分级识别原版与模组散装地形_高价值方块全部精确保留() {
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.DIRT.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.SAND.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.RED_SAND.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.GRAVEL.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.SAND));
        assertTrue(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.RED_SAND));
        assertTrue(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.GRAVEL));
        assertTrue(LowValueTerrainBlocks.isLooseFallingTerrainType(ModBlackSand.class),
                "继承 FallingBlock 的模组黑沙应靠类型特征识别为低价值地形");
        assertFalse(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.ANVIL), "铁砧不算散沙");
        assertFalse(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.DRAGON_EGG), "龙蛋不算散沙");
        assertFalse(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.SUSPICIOUS_SAND), "可疑沙子不算散沙");
        assertFalse(LowValueTerrainBlocks.isLooseFallingTerrain(Blocks.WHITE_CONCRETE_POWDER), "混凝土粉末不算散沙");

        FakeBlockTags modTags = FakeBlockTags.vanilla().tag("minecraft:sand", "mod:black_sand");
        assertTrue(LowValueTerrainBlocks.isLowValueTerrain("mod:black_sand", modTags),
                "挂 #minecraft:sand 标签的模组方块应识别为低价值地形");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain("mod:gearbox", modTags),
                "未挂低价值地形标签的模组齿轮箱维持高价值");

        // 容器、机械（方块实体）、红石、矿块、耕地、木构、石砖、铁砧、混凝土粉末一律维持高价值（1 格必报）。
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.BARREL.defaultBlockState()), "木桶");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.FURNACE.defaultBlockState()), "熔炉");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.HOPPER.defaultBlockState()), "漏斗");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.REDSTONE_WIRE.defaultBlockState()), "红石粉");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.REPEATER.defaultBlockState()), "中继器");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.FARMLAND.defaultBlockState()), "耕地");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.OAK_PLANKS.defaultBlockState()), "木板");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.STONE_BRICKS.defaultBlockState()), "石砖");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.IRON_ORE.defaultBlockState()), "矿块");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.ANVIL.defaultBlockState()), "铁砧不算散沙");
        assertFalse(LowValueTerrainBlocks.isLowValueTerrain(Blocks.WHITE_CONCRETE_POWDER.defaultBlockState()), "混凝土粉末");

        Protection protection = protection();
        WorldPosition nearHome = new WorldPosition(2, 64, 0, DIM);
        assertEquals("", protection.othersAtHomeNote(nearHome, Blocks.GRASS_BLOCK.defaultBlockState()),
                "单格草方块在自家清障中静默豁免归属说明");
        assertTrue(protection.othersAtHomeNote(nearHome, Blocks.BARREL.defaultBlockState()).contains("玩家甲"),
                "单格木桶维持精确归属说明");
    }

    @Test
    void 一格草方块与三格黑沙静默豁免_不推others_things_touched() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeBlockTags modTags = FakeBlockTags.vanilla().tag("minecraft:sand", "mod:black_sand");
        OthersThingsNotice notice = new OthersThingsNotice(protection(), id -> false,
                id -> LowValueTerrainBlocks.isLowValueTerrain(id, modTags), goalBoundSink(log));

        // 任务 1：破坏 1 格草方块 -> 静默豁免。
        WorldPosition grass = new WorldPosition(2, 64, 0, DIM);
        notice.blockBroken(notice.snapshotBlock(grass, "minecraft:grass_block").orElseThrow(), 10L);
        notice.flushDue(10L + OthersThingsNotice.BREAK_BATCH_WINDOW_TICKS);
        notice.flushNow();
        assertEquals(0, log.read(null, 0, OptionalLong.empty(), 10, 0).events().size(), "1 格草方块应静默豁免");

        // 任务 2：破坏 3 格黑沙（经 #minecraft:sand 标签识别）-> 仍静默豁免。
        for (int i = 0; i < 3; i++) {
            WorldPosition sand = new WorldPosition(3 + i, 64, 0, DIM);
            notice.blockBroken(notice.snapshotBlock(sand, "mod:black_sand").orElseThrow(), 100L + i * 10L);
        }
        notice.flushDue(200L);
        notice.flushNow();
        assertEquals(0, log.read(null, 0, OptionalLong.empty(), 10, 0).events().size(), "3 格黑沙应静默豁免");
    }

    @Test
    void 四格草方块合并汇总报一条_一格木桶红石齿轮箱各自必报() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeBlockTags modTags = FakeBlockTags.vanilla().tag("minecraft:sand", "mod:black_sand");
        OthersThingsNotice notice = new OthersThingsNotice(protection(), id -> false,
                id -> LowValueTerrainBlocks.isLowValueTerrain(id, modTags), goalBoundSink(log));

        // 同一任务跨越 50 刻间隔累计破坏 4 格草方块 -> 合并汇总报 1 条（count = 4）。
        for (int i = 0; i < 4; i++) {
            WorldPosition pos = new WorldPosition(2 + i, 64, 0, DIM);
            notice.blockBroken(notice.snapshotBlock(pos, "minecraft:grass_block").orElseThrow(), 10L + i * 50L);
            notice.flushDue(10L + i * 50L + 45L);
        }
        notice.flushNow();

        // 1 格木桶、1 格红石粉、1 格齿轮箱各自 1 格即精确上报。
        notice.blockBroken(notice.snapshotBlock(new WorldPosition(10, 64, 0, DIM), "minecraft:barrel").orElseThrow(), 500L);
        notice.flushNow();
        notice.blockBroken(notice.snapshotBlock(new WorldPosition(11, 64, 0, DIM), "minecraft:redstone_wire").orElseThrow(), 600L);
        notice.flushNow();
        notice.blockBroken(notice.snapshotBlock(new WorldPosition(12, 64, 0, DIM), "mod:gearbox").orElseThrow(), 700L);
        notice.flushNow();

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(4, events.size(), "4 格草方块汇总 1 条 + 木桶/红石/齿轮箱各 1 条 = 4 条");

        JsonObject grassEvent = ResultJson.event(events.get(0)).getAsJsonObject("others_things");
        assertEquals("broke", grassEvent.get("action").getAsString());
        assertEquals(4, grassEvent.get("count").getAsInt(), "4 格草方块应合并汇总报 1 条且 count=4");

        assertEquals("minecraft:barrel", ResultJson.event(events.get(1)).getAsJsonObject("others_things").get("block").getAsString());
        assertEquals("minecraft:redstone_wire", ResultJson.event(events.get(2)).getAsJsonObject("others_things").get("block").getAsString());
        assertEquals("mod:gearbox", ResultJson.event(events.get(3)).getAsJsonObject("others_things").get("block").getAsString());
    }

    @Test
    void 自家清障破坏三格落沙不写归属说明_四格草方块汇总一条_一格木桶必写归属说明() {
        // 3 格落沙（沙子/红沙/沙砾，均为 FallingBlock）：清障完成后所有 Change.note 不含“玩家甲”。
        TaskResult threeSand = runClear(List.of(
                Map.entry(new BlockPos(1, 64, 0), Blocks.SAND.defaultBlockState()),
                Map.entry(new BlockPos(2, 64, 0), Blocks.RED_SAND.defaultBlockState()),
                Map.entry(new BlockPos(3, 64, 0), Blocks.GRAVEL.defaultBlockState())));
        assertEquals(TaskResult.Status.DONE, threeSand.status());
        assertTrue(threeSand.changes().stream().noneMatch(change -> change.note().contains("玩家甲")),
                "3 格落沙静默豁免，不写归属说明：" + threeSand.changes());

        // 4 格草方块：合并汇总成 1 条带“玩家甲”且 count=4 的 Change。
        TaskResult fourGrass = runClear(List.of(
                Map.entry(new BlockPos(1, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState()),
                Map.entry(new BlockPos(2, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState()),
                Map.entry(new BlockPos(3, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState()),
                Map.entry(new BlockPos(4, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState())));
        assertEquals(TaskResult.Status.DONE, fourGrass.status());
        List<Change> ownedGrass = fourGrass.changes().stream()
                .filter(change -> change.kind() == Change.Kind.BLOCK_BROKEN && change.note().contains("玩家甲"))
                .toList();
        assertEquals(1, ownedGrass.size(), "4 格草方块应合并汇总为 1 条带归属说明的变更：" + fourGrass.changes());
        assertEquals(4, ownedGrass.getFirst().count());

        // 1 格木桶：立刻写归属说明。
        TaskResult oneBarrel = runClear(List.of(
                Map.entry(new BlockPos(1, 64, 0), Blocks.BARREL.defaultBlockState())));
        assertEquals(TaskResult.Status.DONE, oneBarrel.status());
        assertTrue(oneBarrel.changes().stream().anyMatch(change -> change.note().contains("玩家甲")),
                "1 格木桶必须精确写入归属说明：" + oneBarrel.changes());
    }

    private static TaskResult runClear(List<Map.Entry<BlockPos, BlockState>> obstacles) {
        Map<BlockPos, BlockState> world = new HashMap<>();
        List<PlannedCell> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : obstacles) {
            world.put(entry.getKey(), entry.getValue());
            cells.add(PlannedCell.air(entry.getKey()));
        }
        Blueprint blueprint = new Blueprint(DIM, cells.getFirst().pos(), cells);
        ConstructionSeams.ReadsSite reads = new ConstructionSeams.ReadsSite() {
            @Override public boolean loaded(BlockPos pos) { return true; }
            @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            @Override public String dimension() { return DIM; }
            @Override public BlockPos feet() { return new BlockPos(0, 64, 2); }
            @Override public int carried(String itemId) { return 0; }
            @Override public boolean creative() { return false; }
            @Override public boolean unbreakable(BlockPos pos) { return false; }
            @Override public Optional<String> temporaryMaterial() { return Optional.empty(); }
        };
        BringsPlayerClose instantClose = (target, permissions) -> new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return "走到跟前"; }
        };
        DigsBlocks instantDig = target -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                world.put(target, Blocks.AIR.defaultBlockState());
                return ActionStatus.done();
            }
            @Override public String describe() { return "清障挖掉"; }
        });
        ConstructionSeams.Guards homeGuards = new ConstructionSeams.Guards() {
            @Override public Optional<Problem> allows(
                    PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
                return Optional.empty();
            }
            @Override public String othersAtHomeNote(BlockPos pos) {
                return "是 玩家甲 放的，在你家（你睡的床旁）";
            }
        };
        ConstructionSeams.PlansPlacement noopPlans = new ConstructionSeams.PlansPlacement() {
            @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
                return Optional.empty();
            }
            @Override public boolean requiresSneak(BlockPos clicked) { return false; }
        };
        ConstructionSeams.Clicks noopClicks = new ConstructionSeams.Clicks() {
            @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation) {
                throw new UnsupportedOperationException();
            }
            @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
                throw new UnsupportedOperationException();
            }
            @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
                throw new UnsupportedOperationException();
            }
            @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
                throw new UnsupportedOperationException();
            }
        };
        ConstructionSeams.Ledger noopLedger = new ConstructionSeams.Ledger() {
            @Override public void siteStarted(Blueprint bp) {}
            @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) {}
            @Override public void temporaryRemoved(BlockPos pos) {}
            @Override public List<BlockPos> temporaries() { return List.of(); }
        };
        ConstructionServices services = new ConstructionServices(reads, instantClose, noopPlans, noopClicks,
                instantDig, null, itemId -> new ConstructionSeams.HoldPlan.Ready(), homeGuards, noopLedger, null);
        Task task = new ConstructionTask(new ConstructionInput(blueprint, Permissions.DEFAULT, "测试清障"), services);
        for (long t = 1; t <= 2000; t++) {
            long now = t;
            TickResult result = task.tick(new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            });
            if (result instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("清障任务未在 2000 刻内结束");
    }
}
