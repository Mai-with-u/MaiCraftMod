// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 建造顺序与近距避让改进离线单测：
 * 覆盖单层连通铺面/窄路远端起手退着铺、角色脚格/头格占据降权、垂向盖帽依赖门控、短步距避让超时缩短四项改进。
 */
class PlacementOrderTest {

    private static BlockState STONE;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static PlannedCell stoneCell(int x, int y, int z) {
        return PlannedCell.block(new BlockPos(x, y, z), STONE, Set.of());
    }

    /** 离线砌筑测试台：支持记录放块顺序、控制生物占位、模拟近距走近卡死切换候选面。 */
    private static final class PlacingRig {
        final Map<BlockPos, BlockState> world = new HashMap<>();
        final List<BlockPos> placedOrder = new ArrayList<>();
        final Map<BlockPos, String> creatures = new HashMap<>();
        int creatureBlocksLeft = Integer.MAX_VALUE;
        BlockPos feet = new BlockPos(0, 64, 1);
        /** 走近调用次数与前几次卡死不结束（演近距避让 80 刻超时切换）。 */
        int approachCalls;
        int stuckFirstApproaches;
        final ConstructionSite site;
        final PlacingWork work;

        PlacingRig(List<PlannedCell> cells, BlockPos startFeet) {
            this.feet = startFeet;
            for (int x = -2; x <= 8; x++) {
                for (int z = -2; z <= 4; z++) {
                    world.put(new BlockPos(x, 63, z), STONE);
                }
            }
            Blueprint blueprint = new Blueprint("minecraft:overworld", cells.getFirst().pos(), cells);
            this.site = new ConstructionSite(blueprint);
            ConstructionSeams.ReadsSite reads = readsSite();
            this.site.check(reads);
            ConstructionServices services = new ConstructionServices(reads, close(), plans(), clicks(),
                    null, null, itemId -> new ConstructionSeams.HoldPlan.Ready(),
                    (action, pos, blockType) -> Optional.empty(), noopLedger(), null);
            this.work = new PlacingWork(services, site, new TaskRecords() {
                @Override public void change(Change change) {}
                @Override public void unconfirmed(Change change) {}
                @Override public void attempt(String tried, String whatHappened) {}
            }, Permissions.DEFAULT, "测试砌筑", site.pendingPlace());
        }

        int runUntilDone(int maxTicks) {
            for (int tick = 1; tick <= maxTicks; tick++) {
                long now = tick;
                ActionStatus status = work.tick(new TickContext() {
                    @Override public long gameTick() { return now; }
                    @Override public PlayerContext player() { return null; }
                });
                if (status instanceof ActionStatus.Done) return tick;
            }
            throw new AssertionError("在 " + maxTicks + " 刻内未完成砌筑");
        }

        private ConstructionSeams.ReadsSite readsSite() {
            return new ConstructionSeams.ReadsSite() {
                @Override public boolean loaded(BlockPos pos) { return true; }
                @Override public BlockState state(BlockPos pos) {
                    return world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                }
                @Override public String dimension() { return "minecraft:overworld"; }
                @Override public BlockPos feet() { return feet; }
                @Override public int carried(String itemId) { return 64; }
                @Override public boolean creative() { return false; }
                @Override public boolean unbreakable(BlockPos pos) { return false; }
                @Override public Optional<String> temporaryMaterial() { return Optional.empty(); }
                @Override public Optional<String> creatureIn(BlockPos pos) {
                    return Optional.ofNullable(creatures.get(pos));
                }
            };
        }

        private BringsPlayerClose close() {
            return new BringsPlayerClose() {
                @Override public Action toward(ApproachTarget target, Permissions permissions) {
                    approachCalls++;
                    boolean stuck = approachCalls <= stuckFirstApproaches;
                    return new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            return stuck ? ActionStatus.running() : ActionStatus.done();
                        }
                        @Override public String describe() { return stuck ? "近距避让卡住" : "走近到位"; }
                    };
                }
                @Override public Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
                    return toward(target, permissions);
                }
            };
        }

        private ConstructionSeams.PlansPlacement plans() {
            return new ConstructionSeams.PlansPlacement() {
                @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
                    if (creatures.containsKey(cell.pos())) {
                        if (--creatureBlocksLeft >= 0) {
                            return Optional.of(new PlacementPrediction.Placement(clicked, face, null, false));
                        }
                        creatures.remove(cell.pos());
                    }
                    return Optional.of(new PlacementPrediction.Placement(clicked, face, cell.state(), false));
                }
                @Override public boolean requiresSneak(BlockPos clicked) { return false; }
            };
        }

        private ConstructionSeams.Clicks clicks() {
            return new ConstructionSeams.Clicks() {
                @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
                        PlacementConfirmation confirmation) {
                    Action action = new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            world.put(cell.pos(), placement.predicted());
                            placedOrder.add(cell.pos().immutable());
                            return ActionStatus.done();
                        }
                        @Override public String describe() { return "放方块"; }
                    };
                    return new Click(action, () -> InteractionResult.applied("放好"));
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

    @Test
    void 单层连通窄路从远离出口一端起手退着铺_不从中段切开() {
        List<PlannedCell> strip = List.of(
                stoneCell(0, 64, 0), stoneCell(1, 64, 0), stoneCell(2, 64, 0),
                stoneCell(3, 64, 0), stoneCell(4, 64, 0));
        assertTrue(PlacementOrder.isSingleLayerConnected(strip), "1×5 连通单层路应启用远端退着铺");
        assertFalse(PlacementOrder.isSingleLayerConnected(List.of(stoneCell(0, 64, 0), stoneCell(4, 64, 0))),
                "两格离散点不构成连通铺面，保留常规就近优先");
        assertFalse(PlacementOrder.isSingleLayerConnected(List.of(stoneCell(0, 64, 0), stoneCell(1, 64, 0),
                stoneCell(2, 64, 0), stoneCell(0, 65, 0))), "跨多层建筑不走单层退着铺");

        // 角色从近端 (0, 64, 1) 起步，应先铺最远的 x=4，依次退着铺回 x=0。
        PlacingRig rig = new PlacingRig(strip, new BlockPos(0, 64, 1));
        rig.runUntilDone(200);
        assertEquals(List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(0, 64, 0)), rig.placedOrder, "单层窄路应严格从远端退着铺回出口");
    }

    @Test
    void 角色当前脚格与头格被显著降权_同层优先放其他可达不碰撞格() {
        BlockPos feet = new BlockPos(0, 64, 0);
        PlannedCell feetCell = stoneCell(0, 64, 0);
        PlannedCell headCell = stoneCell(0, 65, 0);
        PlannedCell sideCell = stoneCell(2, 64, 0);
        assertTrue(PlacementOrder.intersectsPlayer(feetCell, feet));
        assertTrue(PlacementOrder.intersectsPlayer(headCell, feet));
        assertFalse(PlacementOrder.intersectsPlayer(sideCell, feet));

        double occupiedScore = PlacementOrder.pickScore(feetCell, feet, new Vec3(0, 0, -1),
                PlacementOrder.WALK_TURN_TIEBREAKER);
        double sideScore = PlacementOrder.pickScore(sideCell, feet, new Vec3(0, 0, -1),
                PlacementOrder.WALK_TURN_TIEBREAKER);
        assertTrue(occupiedScore > sideScore + 50.0, "脚下格应被施加显著惩罚排到不碰撞格之后");

        // 即使脚下格排在列表最前且几何上“够得着”，pickNearestReachable 也必须跳过它先挑不碰撞的旁格。
        PlannedCell picked = PlacementOrder.pickNearestReachable(List.of(feetCell, sideCell),
                cell -> true, feet, new Vec3(1, 0, 0));
        assertEquals(sideCell.pos(), picked.pos(), "应先放不碰撞的旁格，避免踩格放不下去");
    }

    @Test
    void 正下方格仍在延后表时禁止提前盖帽_下层放好或确认放不成后再解封上层() {
        // 场景 A：下层 (0,64,0) 暂时被生物占住进 DeferredCells；旁侧 (1,64,0) 有已放石墙让 (0,65,0) 本来有面可点，
        // 但垂向盖帽门控必须压住 (0,65,0)，直到生物离开、下层 (0,64,0) 先放好才放上层 (0,65,0)。
        List<PlannedCell> column = List.of(stoneCell(0, 64, 0), stoneCell(0, 65, 0));
        PlacingRig rig = new PlacingRig(column, new BlockPos(0, 64, 2));
        rig.world.put(new BlockPos(1, 64, 0), STONE);
        rig.world.put(new BlockPos(1, 65, 0), STONE);
        rig.creatures.put(new BlockPos(0, 64, 0), "测试生物");
        rig.creatureBlocksLeft = 3;
        rig.runUntilDone(600);
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0)), rig.placedOrder,
                "必须等下层 (0,64,0) 放好后再放正上方 (0,65,0)，绝不提前盖帽封死下层");

        // 场景 B：下层 (0,64,0) 生物一直不走、最终确认放不成后，上层 (0,65,0) 应自动解除拦截并贴旁墙放好。
        PlacingRig permanentBlock = new PlacingRig(column, new BlockPos(0, 64, 2));
        permanentBlock.world.put(new BlockPos(1, 64, 0), STONE);
        permanentBlock.world.put(new BlockPos(1, 65, 0), STONE);
        permanentBlock.creatures.put(new BlockPos(0, 64, 0), "一直站着的生物");
        permanentBlock.runUntilDone(1000);
        assertEquals(List.of(new BlockPos(0, 65, 0)), permanentBlock.placedOrder,
                "下层确认放不成后应解除垂向门控，允许上层正常放置而不死锁");
    }

    @Test
    void 近距避让移动超时缩短至80刻_超时迅速切候选面不卡死400刻() {
        assertEquals(PlacementOrder.SHORT_APPROACH_PATIENCE_TICKS,
                PlacementOrder.approachPatienceTicks(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)),
                "同层距离 2 格 (< 3 格) 走近超时应缩短为 80 刻");
        assertEquals(BringsPlayerClose.WALK_PATIENCE_TICKS,
                PlacementOrder.approachPatienceTicks(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0)),
                "距离达到 3 格及以上保持 400 刻常规耐心");
        assertEquals(BringsPlayerClose.WALK_PATIENCE_TICKS,
                PlacementOrder.approachPatienceTicks(new BlockPos(0, 64, 0), new BlockPos(1, 66, 0)),
                "高出 2 格的墙体/屋顶格即便三维距离不足 3 格也保持 400 刻耐心，不被 80 刻强杀切断不可达缓存");

        // 角色距目标格 (1,64,0) 仅 1 格，目标格有底面与西面两处支撑：第一面走近一直卡住，
        // 应在第 80 刻后立刻超时切到第二面并顺利放完，总耗时远小于旧 400 刻。
        PlacingRig rig = new PlacingRig(List.of(stoneCell(1, 64, 0)), new BlockPos(0, 64, 0));
        rig.world.put(new BlockPos(2, 64, 0), STONE);
        rig.stuckFirstApproaches = 1;
        int ticks = rig.runUntilDone(200);
        assertTrue(ticks < 150, "近距避让卡住应在 80 刻左右快速切面完成，而非苦等 400 刻，实际耗时：" + ticks);
        assertEquals(2, rig.approachCalls, "首面 80 刻超时后应切换到第二候选面");
        assertEquals(List.of(new BlockPos(1, 64, 0)), rig.placedOrder);
    }
}
