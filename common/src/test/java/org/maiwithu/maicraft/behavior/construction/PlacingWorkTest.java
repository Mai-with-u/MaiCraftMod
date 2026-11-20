// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 砌筑挑格与失败记忆的判断：挑格先挑站着就够得着的（最近几格都没有才回退按距离挑）；
 * 够得着按可点面中心到眼睛的直线距离算；走不过去的记忆只有新方块落在起点或点击面旁边才作废。
 */
class PlacingWorkTest {

    private static BlockState STONE;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
    }

    /** 世界替身：一张方块表，没写的格都是空气。 */
    private static final class WorldStub implements ReadsBlocks {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();

        @Override public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
        @Override public BlockState state(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }

    private static PlannedCell cellAt(BlockPos pos) {
        return PlannedCell.block(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:stone")).defaultBlockState(), Set.of());
    }

    // ===== 挑格预筛 =====

    @Test
    void 最近几格里有够得着的就挑它() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)),
                cellAt(new BlockPos(2, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(2, 64, 0));
        assertEquals(new BlockPos(2, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos(),
                "前两格不够得着，挑第三格");
    }

    @Test
    void 最近的格够得着就挑最近的() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(0, 64, 0));
        assertEquals(new BlockPos(0, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos());
    }

    @Test
    void 最近几格都不够得着_回退挑最近的() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)));
        assertEquals(new BlockPos(0, 64, 0), PlacingWork.pickNearestReachable(tier, cell -> false).pos());
    }

    @Test
    void 预筛只看离人最近的几格() {
        List<PlannedCell> tier = new ArrayList<>();
        for (int i = 0; i < 10; i++) tier.add(cellAt(new BlockPos(i, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(9, 64, 0));
        assertEquals(new BlockPos(0, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos(),
                "够得着的格在预筛窗口之外，不为它挑，照距离挑最近");
    }

    // ===== 够得着的面 =====

    @Test
    void 脚边有支撑面的格算够得着() {
        WorldStub world = new WorldStub();
        world.cells.put(new BlockPos(3, 63, 0), STONE);
        assertTrue(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(3, 64, 0)),
                new BlockPos(3, 64, 0), new BlockPos(0, 64, 0)), "点它下面支撑块的上面那一面，面中心在三格多");
    }

    @Test
    void 面中心超出交互距离算不够得着() {
        WorldStub world = new WorldStub();
        world.cells.put(new BlockPos(5, 63, 0), STONE);
        assertFalse(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(5, 64, 0)),
                new BlockPos(5, 64, 0), new BlockPos(0, 64, 0)), "面中心在五格开外");
    }

    @Test
    void 旁边没有能贴的面算不够得着() {
        WorldStub world = new WorldStub();
        assertFalse(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(3, 64, 0)),
                new BlockPos(3, 64, 0), new BlockPos(0, 64, 0)), "下面和四周都是空气，没有能点的面");
    }

    // ===== 走不过去的记忆作废 =====

    @Test
    void 新方块旁边的记忆作废_远处的保留() {
        PlacingWork.FailedApproach failed = new PlacingWork.FailedApproach(
                new BlockPos(0, 64, 0), new BlockPos(5, 64, 1), Direction.NORTH);
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(5, 64, 3), failed), "点击面旁边两格有新方块");
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(1, 64, 0), failed), "起点旁边有新方块");
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(0, 64, 1), failed), "正好压在触及范围边界上算受影响");
        assertFalse(PlacingWork.invalidatedBy(new BlockPos(0, 64, 8), failed), "离起点和点击面都远，记忆照旧成立");
    }

    @Test
    void 站位够得着的近格优先顺着当前视线扫过_不在四格内前后折返甩头() {
        BlockPos feet = new BlockPos(0, 64, 0);
        // 身后一格（0,64,1）虽然离得比眼前三格（0,64,-3）和右侧两格（2,64,0）更近，但全都在四格内站着够得着：
        // 角色面朝北（0,0,-1）时先放眼前的（0,64,-3），接着顺势扫右侧（2,64,0），最后才转身放身后（0,64,1）。
        PlannedCell behind = cellAt(new BlockPos(0, 64, 1));
        PlannedCell side = cellAt(new BlockPos(2, 64, 0));
        PlannedCell ahead = cellAt(new BlockPos(0, 64, -3));
        List<PlannedCell> remaining = new ArrayList<>(List.of(behind, side, ahead));

        PlannedCell first = PlacingWork.pickNearestReachable(remaining, cell -> true, feet, new Vec3(0, 0, -1));
        assertEquals(ahead.pos(), first.pos(), "面朝北时优先放眼前的够得着格，不为差一格距离猛转 180 度");
        remaining.remove(first);

        PlannedCell second = PlacingWork.pickNearestReachable(remaining, cell -> true, feet, new Vec3(0, 0, -1));
        assertEquals(side.pos(), second.pos(), "放完正前方后顺着侧面扫，仍排在正后方之前");
    }

    @Test
    void 放置结果出乎预料且可扳手转正时先记账放下再转正() {
        BlockPos target = new BlockPos(0, 64, 0);
        BlockState wanted = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState wrongFacing = wanted.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        DivergedRig rig = new DivergedRig(target, wanted, wrongFacing, true);

        ActionStatus status = rig.run(40);
        assertTrue(status instanceof ActionStatus.Done, "扳手转正后砌筑应正常完成：" + status);
        assertEquals(1, rig.placeClicks, "已落地后只放 1 次，不应换面重复放");
        assertEquals(1, rig.turnClicks, "应触发扳手转正 1 次");
        assertEquals(wanted, rig.world.get(target), "扳手应把落点格转成目标朝向");
        assertTrue(rig.changes.stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_PLACED),
                "转正前应先把落地那块记账");
        assertTrue(rig.changes.stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_CHANGED),
                "扳手转正后应记 BLOCK_CHANGED");
        assertTrue(rig.site.placed().contains(target));
        assertEquals(0, rig.site.notDone());
    }

    @Test
    void 放置结果出乎预料且无法扳手转正时如实报STUCK不走没生效重放() {
        BlockPos target = new BlockPos(0, 64, 0);
        BlockState wanted = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BlockState wrongFacing = wanted.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        DivergedRig rig = new DivergedRig(target, wanted, wrongFacing, false);

        ActionStatus status = rig.run(40);
        assertTrue(status instanceof ActionStatus.Done, "放弃该格后砌筑阶段收尾：" + status);
        assertEquals(1, rig.placeClicks, "落点格已落地不符时绝不当成没生效换其余支撑面重放");
        assertEquals(0, rig.turnClicks);
        assertEquals(1, rig.site.notDone());
        var groups = rig.site.problemGroups();
        assertEquals(1, groups.size());
        assertTrue(groups.getFirst().reason().contains("目标格 0, 64, 0 已落地但状态与预期不符"), groups.toString());
        assertEquals(Problem.Kind.STUCK, rig.site.reasonCounts().kind());
    }

    /** 离线替身：目标格有多个支撑面，首点落地成错朝向并返回 UNEXPECTED 失败；按开关决定能否扳手转正。 */
    private static final class DivergedRig {
        final BlockPos target;
        final BlockState wanted;
        final BlockState wrongFacing;
        final boolean turnable;
        final Map<BlockPos, BlockState> world = new HashMap<>();
        final Map<String, Integer> carried = new HashMap<>();
        final List<Change> changes = new ArrayList<>();
        final ConstructionSite site;
        final PlacingWork work;
        int placeClicks;
        int turnClicks;

        DivergedRig(BlockPos target, BlockState wanted, BlockState wrongFacing, boolean turnable) {
            this.target = target;
            this.wanted = wanted;
            this.wrongFacing = wrongFacing;
            this.turnable = turnable;
            world.put(target.below(), STONE);
            world.put(target.east(), STONE);
            world.put(target.west(), STONE);
            carried.put("minecraft:oak_stairs", 4);
            carried.put("minecraft:stick", 1);
            PlannedCell cell = PlannedCell.block(target, wanted, Set.of("facing"));
            Blueprint blueprint = new Blueprint("minecraft:overworld", target, List.of(cell));
            this.site = new ConstructionSite(blueprint);
            ConstructionSeams.ReadsSite reads = readsSite();
            this.site.check(reads);
            ConstructionServices services = new ConstructionServices(reads, instantClose(), plans(), clicks(),
                    null, null, itemId -> new ConstructionSeams.HoldPlan.Ready(),
                    (action, pos, blockType) -> Optional.empty(), noopLedger(), null);
            this.work = new PlacingWork(services, site, new TaskRecords() {
                @Override public void change(Change change) { changes.add(change); }
                @Override public void unconfirmed(Change change) {}
                @Override public void attempt(String tried, String whatHappened) {}
            }, Permissions.DEFAULT, "测试砌筑", List.of(cell));
        }

        ActionStatus run(int maxTicks) {
            ActionStatus status = ActionStatus.running();
            for (int i = 1; i <= maxTicks && !(status instanceof ActionStatus.Done); i++) {
                long tick = i;
                status = work.tick(new TickContext() {
                    @Override public long gameTick() { return tick; }
                    @Override public PlayerContext player() { return null; }
                });
            }
            return status;
        }

        private ConstructionSeams.ReadsSite readsSite() {
            return new ConstructionSeams.ReadsSite() {
                @Override public boolean loaded(BlockPos pos) { return true; }
                @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
                @Override public String dimension() { return "minecraft:overworld"; }
                @Override public BlockPos feet() { return new BlockPos(0, 64, 2); }
                @Override public int carried(String itemId) { return carried.getOrDefault(itemId, 0); }
                @Override public boolean creative() { return false; }
                @Override public boolean unbreakable(BlockPos pos) { return false; }
                @Override public Optional<String> temporaryMaterial() { return Optional.empty(); }
            };
        }

        private BringsPlayerClose instantClose() {
            return (approachTarget, permissions) -> new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "走到跟前"; }
            };
        }

        private ConstructionSeams.PlansPlacement plans() {
            return new ConstructionSeams.PlansPlacement() {
                @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
                    return Optional.of(new PlacementPrediction.Placement(clicked, face, wanted, false));
                }

                @Override public boolean requiresSneak(BlockPos clicked) { return false; }

                @Override public Optional<PlacementRules.Turns> turnsTo(BlockState placed, Predicate<BlockState> ok) {
                    if (!turnable || ok.test(placed)) return Optional.empty();
                    return Optional.of(new PlacementRules.Turns(Items.STICK, List.of(Direction.UP)));
                }
            };
        }

        private ConstructionSeams.Clicks clicks() {
            return new ConstructionSeams.Clicks() {
                @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
                        PlacementConfirmation confirmation) {
                    InteractionResult[] out = {null};
                    Action action = new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            placeClicks++;
                            world.put(target, wrongFacing);
                            carried.merge("minecraft:oak_stairs", -1, Integer::sum);
                            out[0] = InteractionResult.unexpected("落点格 0, 64, 0 已落地但朝向或状态与预期不符");
                            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, out[0].scene()));
                        }

                        @Override public String describe() { return "放方块"; }
                    };
                    return new Click(action, () -> out[0]);
                }

                @Override public Click turn(BlockPos block, Direction face, InteractionConfirmation confirmation) {
                    InteractionResult[] out = {null};
                    Action action = new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            turnClicks++;
                            world.put(block, wanted);
                            out[0] = InteractionResult.applied("扳手转正");
                            return ActionStatus.done();
                        }

                        @Override public String describe() { return "扳手转正"; }
                    };
                    return new Click(action, () -> out[0]);
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
        }

        private ConstructionSeams.Ledger noopLedger() {
            return new ConstructionSeams.Ledger() {
                @Override public void siteStarted(Blueprint blueprint) {}
                @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) {}
                @Override public void temporaryRemoved(BlockPos pos) {}
                @Override public List<BlockPos> temporaries() { return List.of(); }
            };
        }
    }
}
