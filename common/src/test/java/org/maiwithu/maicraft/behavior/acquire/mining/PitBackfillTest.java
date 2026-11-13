// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采掘坑口核查与收工回填测试：浅台阶入口与平地竖坑在有多余废料时封平、无多余废料时如实记未填；
 * 周围原本就有的工程坑与只揭一层的浅表皮不误当成要填的坑。
 */
class PitBackfillTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    private static final class FakeSite implements ClientStairsDown.Site {
        final Map<BlockPos, String> blocks = new HashMap<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        List<String> fillers = List.of();

        String blockAt(BlockPos pos) {
            String overridden = blocks.get(pos);
            if (overridden != null) return overridden;
            return pos.getY() <= 63 ? "minecraft:dirt" : "minecraft:air";
        }

        @Override public Optional<BlockPos> feet(TickContext context) {
            return Optional.of(feet);
        }

        @Override public boolean onGround(TickContext context) {
            return true;
        }

        @Override public Direction facing(TickContext context) {
            return Direction.NORTH;
        }

        @Override public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
            return "minecraft:air".equals(blockAt(cell))
                    ? ClientStairsDown.Ground.OPEN
                    : ClientStairsDown.Ground.DIGGABLE;
        }

        @Override public String blockType(TickContext context, BlockPos cell) {
            return blockAt(cell);
        }

        @Override public String dimension(TickContext context) {
            return "minecraft:overworld";
        }

        @Override public OptionalInt surfaceY(TickContext context, BlockPos feet) {
            return OptionalInt.of(64);
        }

        @Override public List<String> fillers(TickContext context) {
            return fillers;
        }

        @Override public Optional<Action> placeFiller(TickContext context, BlockPos cell, String filler) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext ctx) {
                    blocks.put(cell, filler);
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "封口";
                }
            });
        }

        @Override public Action stepTo(BlockPos target) {
            return new Action() {
                @Override public ActionStatus tick(TickContext ctx) {
                    feet = target;
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "走回地面";
                }
            };
        }
    }

    private static ActionStatus runToEnd(Action action) {
        for (int t = 0; t < 30; t++) {
            ActionStatus status = action.tick(new Tick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("动作未在 30 刻内结束");
    }

    @Test
    void shallowStairsEntryIsCappedWithSpareCobblestoneAndRecorded() {
        FakeSite site = new FakeSite();
        site.feet = new BlockPos(-361, 64, 40);
        site.fillers = List.of("minecraft:cobblestone");
        DugPits pits = new DugPits();

        DigsStairsDown inner = (yields, wanted, permissions) -> new Action() {
            private int step;
            @Override public ActionStatus tick(TickContext context) {
                step++;
                if (step == 1) {
                    // 第一级往下挖开 (-361, 63, 39) 并迈下去，再挖到深 3 格的 61 层。
                    site.blocks.put(new BlockPos(-361, 63, 39), "minecraft:air");
                    site.blocks.put(new BlockPos(-361, 62, 38), "minecraft:air");
                    site.feet = new BlockPos(-361, 63, 39);
                    return ActionStatus.running();
                }
                site.feet = new BlockPos(-361, 61, 37);
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "挖台阶";
            }
        };

        DigsStairsDown wrapped = PitBackfill.wrapStairs(inner, site, pits);
        assertInstanceOf(ActionStatus.Done.class,
                runToEnd(wrapped.digDown("minecraft:stone"::equals, 3, Permissions.DEFAULT)));

        List<DugPits.Pit> recorded = pits.since(0);
        assertEquals(1, recorded.size());
        DugPits.Pit pit = recorded.getFirst();
        assertEquals(-361, pit.at().x());
        assertEquals(63, pit.at().y());
        assertEquals(39, pit.at().z());
        assertEquals(3, pit.depth());
        assertTrue(pit.filled());
        assertTrue(pit.note().contains("圆石"), pit.note());
        assertEquals("minecraft:cobblestone", site.blockAt(new BlockPos(-361, 63, 39)));
    }

    @Test
    void stairsWithoutSpareFillerLeavesWalkableEntryAndReportsUnfilled() {
        FakeSite site = new FakeSite();
        site.feet = new BlockPos(0, 64, 0);
        site.fillers = List.of();
        DugPits pits = new DugPits();

        DigsStairsDown inner = (yields, wanted, permissions) -> new Action() {
            private int step;
            @Override public ActionStatus tick(TickContext context) {
                if (step++ == 0) {
                    site.blocks.put(new BlockPos(0, 63, -1), "minecraft:air");
                    site.feet = new BlockPos(0, 63, -1);
                    return ActionStatus.running();
                }
                site.feet = new BlockPos(0, 61, -3);
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "挖台阶";
            }
        };

        assertInstanceOf(ActionStatus.Done.class,
                runToEnd(PitBackfill.wrapStairs(inner, site, pits).digDown("minecraft:stone"::equals, 3, Permissions.DEFAULT)));
        assertEquals(1, pits.since(0).size());
        assertFalse(pits.since(0).getFirst().filled());
        assertTrue(pits.since(0).getFirst().note().contains("台阶口"));
    }

    @Test
    void surfaceBatchIgnoresPreExistingPitAndOneDeepScrapeButCapsTwoDeepShaft() {
        FakeSite site = new FakeSite();
        // 旁边 (5, 63..61, 5) 原本就有别人挖的三格深工程坑，不在本次 batch 里，绝不能去填它。
        site.blocks.put(new BlockPos(5, 63, 5), "minecraft:air");
        site.blocks.put(new BlockPos(5, 62, 5), "minecraft:air");
        site.blocks.put(new BlockPos(5, 61, 5), "minecraft:air");
        site.fillers = List.of("minecraft:dirt");
        DugPits pits = new DugPits();

        // 只揭平地表层一格 (2, 63, 2)：高差只有 1，不算坑也不回填。
        Action oneDeep = PitBackfill.wrapSurfaceBatch(new Action() {
            @Override public ActionStatus tick(TickContext ctx) {
                site.blocks.put(new BlockPos(2, 63, 2), "minecraft:air");
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "揭一层";
            }
        }, List.of(new BlockPos(2, 63, 2)), site, pits);
        runToEnd(oneDeep);
        assertTrue(pits.since(0).isEmpty(), "只低一格能直接迈上来，不算坑");
        assertEquals("minecraft:air", site.blockAt(new BlockPos(5, 63, 5)), "别人原本的工程坑一格不碰");

        // 平地竖着挖了两格深 (0, 63, 0) 与 (0, 62, 0)：收工前用多余泥土封平顶层并记账。
        site.feet = new BlockPos(1, 64, 0);
        List<BlockPos> shaft = List.of(new BlockPos(0, 63, 0), new BlockPos(0, 62, 0));
        Action twoDeep = PitBackfill.wrapSurfaceBatch(new Action() {
            @Override public ActionStatus tick(TickContext ctx) {
                for (BlockPos pos : shaft) site.blocks.put(pos, "minecraft:air");
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "竖挖两格";
            }
        }, shaft, site, pits);
        runToEnd(twoDeep);

        assertEquals(1, pits.since(0).size());
        DugPits.Pit pit = pits.since(0).getFirst();
        assertEquals(2, pit.depth());
        assertTrue(pit.filled());
        assertEquals("minecraft:dirt", site.blockAt(new BlockPos(0, 63, 0)));
    }
}
