// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.survival.SuicideWayOut;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 卡坑脱困没出去时的说法按实际发生的写：一格没动就说没动，并说身边挡着什么、为什么没挖、还剩哪条出路；
 * 真挖开了别人放的格子才说"别人放的"；只垫了块就只说垫了几块。
 */
class TrapEscapeStoryTest {

    private static final String OWNER = "11111111-2222-3333-4444-555555555555";
    private static final BlockPos FEET = new BlockPos(5, 64, 5);
    private static final Problem NO_ROUTE = Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null);
    private static final String SANDSTONE = "biomesoplenty:black_sandstone";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetProtection() {
        NavigationProtection.installWorldRule(NavigationProtection.CellRule.NONE);
        NavigationProtection.clear();
    }

    // 坑壁：脚下与东边身体两格是模组的黑砂岩，不算天然地形、没有放置记录。
    private static TrapWalls sandstonePit() {
        return new TrapWalls(List.of(
                new TrapWalls.Cell(FEET.below(), SANDSTONE, TrapWalls.Wall.NOT_TERRAIN),
                new TrapWalls.Cell(FEET.east(), SANDSTONE, TrapWalls.Wall.NOT_TERRAIN),
                new TrapWalls.Cell(FEET.east().above(), SANDSTONE, TrapWalls.Wall.NOT_TERRAIN)));
    }

    @Test
    void 许可已放开但一格没动_说没动和挡着的是什么_能单独下令挖掉哪几格() {
        // 实机：任务许可已经是 any，坑壁是模组的黑砂岩。
        TrapEscape escape = new TrapEscape(TerrainPermit.ANY, TaskEventSink.NONE);
        assertTrue(escape.raise(FEET, () -> true, 0));
        Problem stuck = escape.explain(NO_ROUTE, TrapEscapeStoryTest::sandstonePit, () -> true, true);
        assertEquals("没有能走到目标的路线；卡在坑里，自动脱困一格没动；脚下和四周的 " + SANDSTONE
                + " 不算天然地形，寻路不会自己挖，可以单独下令挖掉：(5, 63, 5)、(6, 64, 5)、(6, 65, 5)", stuck.message());
    }

    @Test
    void 只许垫的许可_挡着的不算天然地形时只能垫上去_没料照实说() {
        TrapEscape escape = new TrapEscape(TerrainPermit.TEMPORARY, TaskEventSink.NONE);
        assertTrue(escape.raise(FEET, () -> true, 0));
        Problem noScaffold = escape.explain(NO_ROUTE, TrapEscapeStoryTest::sandstonePit, () -> true, false);
        assertEquals("没有能走到目标的路线；卡在坑里，没等许可临时放开挖天然方块，自动脱困一格没动；脚下和四周的 " + SANDSTONE
                + " 不算天然地形，寻路不会自己挖；这次许可只能垫方块上去，手边没有能垫的料", noScaffold.message());
        // 手边有料：料是哪些已经在走不通的现场里列着，这里只说只能垫上去。
        Problem withScaffold = escape.explain(NO_ROUTE, TrapEscapeStoryTest::sandstonePit, () -> true, true);
        assertTrue(withScaffold.message().endsWith("这次许可只能垫方块上去"), withScaffold.message());
    }

    @Test
    void 受保护的方块_说受保护_要挖得先同意() {
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, TaskEventSink.NONE);
        assertTrue(escape.raise(FEET, () -> true, 0));
        TrapWalls walls = new TrapWalls(List.of(
                new TrapWalls.Cell(FEET.east(), "minecraft:chest", TrapWalls.Wall.PROTECTED),
                new TrapWalls.Cell(FEET.west(), "minecraft:stone", TrapWalls.Wall.PROTECTED)));
        Problem stuck = escape.explain(NO_ROUTE, () -> walls, () -> true, true);
        assertEquals("没有能走到目标的路线；卡在坑里，自动脱困一格没动；脚下和四周的 minecraft:chest、minecraft:stone"
                + " 受保护，要挖得先同意", stuck.message());
    }

    @Test
    void 真挖开了别人放的格子_按放的人数() {
        // 脚下那一列以外是 OWNER 放的：挖开东边一格泥土（OWNER 放的）、头顶一格天然石头（没有记录）。
        NavigationProtection.installWorldRule(new NavigationProtection.CellRule() {
            @Override public boolean protects(int x, int y, int z, BlockState state) {
                return true;
            }

            @Override public boolean escapeBreakable(int x, int y, int z, BlockState state) {
                return state != null && (state.is(Blocks.DIRT) || state.is(Blocks.STONE));
            }

            @Override public Optional<String> placedByOther(int x, int y, int z) {
                return x != FEET.getX() ? Optional.of(OWNER) : Optional.empty();
            }
        });
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, TaskEventSink.NONE);
        assertTrue(escape.raise(FEET, () -> true, 0));
        NavigationProtection.install(LongSets.emptySet(), LongSets.emptySet(), Integer.MIN_VALUE, escape.throughOthers());
        escape.watchDigging(FEET.east(), Blocks.DIRT.defaultBlockState(), null);
        escape.noteBroken(FEET.east());
        escape.watchDigging(FEET.above(2), Blocks.STONE.defaultBlockState(), null);
        escape.noteBroken(FEET.above(2));
        Problem stuck = escape.explain(NO_ROUTE, () -> TrapWalls.NONE, () -> true, true);
        assertEquals("没有能走到目标的路线；卡在坑里，自动脱困：挖开 2 格，其中" + OWNER + " 放的 1 格，还是没出去",
                stuck.message());
    }

    @Test
    void 只垫了块_只说垫了几块() {
        // 任务许可只垫不挖：临时升到能挖天然方块，结果只垫了两块、一格没挖。
        TrapEscape escape = new TrapEscape(TerrainPermit.TEMPORARY, TaskEventSink.NONE);
        assertTrue(escape.raise(FEET, () -> true, 0));
        escape.notePlaced();
        escape.notePlaced();
        Problem stuck = escape.explain(NO_ROUTE, () -> TrapWalls.NONE, () -> true, true);
        assertEquals("没有能走到目标的路线；卡在坑里，没等许可临时放开挖天然方块，自动脱困：垫了 2 块，还是没出去",
                stuck.message());
    }

    @Test
    void 升到头还出不去_建议末尾接寻死出路() {
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, TaskEventSink.NONE,
                () -> Optional.of(SuicideWayOut.WHEN_ON));
        assertTrue(escape.raise(FEET, () -> true, 0));
        Problem stuck = escape.explain(NO_ROUTE, () -> TrapWalls.NONE, () -> true, true);
        assertTrue(stuck.suggestion().endsWith(SuicideWayOut.WHEN_ON), stuck.suggestion());
    }

    @Test
    void 不许动方块被困在坑里_说明照旧_建议末尾接寻死出路() {
        // none 不升许可，原样按走不通收场：出不去的人照样该知道还有寻死这条路。
        TrapEscape escape = new TrapEscape(TerrainPermit.WALK_ONLY, TaskEventSink.NONE,
                () -> Optional.of(SuicideWayOut.WHEN_UNKNOWN));
        assertFalse(escape.raise(FEET, () -> true, 0));
        Problem stuck = escape.explain(NO_ROUTE, () -> TrapWalls.NONE, () -> true, true);
        assertEquals(NO_ROUTE.message(), stuck.message());
        assertTrue(stuck.suggestion().endsWith(SuicideWayOut.WHEN_UNKNOWN), stuck.suggestion());
        // 没困在坑里（就是远、没路）不提。
        assertSame(NO_ROUTE, escape.explain(NO_ROUTE, () -> TrapWalls.NONE, () -> false, true));
    }

    @Test
    void 没在脱困不读坑壁_原问题照旧() {
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, TaskEventSink.NONE);
        assertSame(NO_ROUTE, escape.explain(NO_ROUTE, () -> {
            throw new AssertionError("没在脱困不该读现场");
        }, () -> true, true));
    }

    @Test
    void 几种方块不算天然地形时每个坐标跟上方块_能挖的单独说一次() {
        TrapWalls walls = new TrapWalls(List.of(
                new TrapWalls.Cell(FEET.east(), "a:wall", TrapWalls.Wall.NOT_TERRAIN),
                new TrapWalls.Cell(FEET.west(), "b:wall", TrapWalls.Wall.NOT_TERRAIN),
                new TrapWalls.Cell(FEET.below(), "minecraft:stone", TrapWalls.Wall.MAY_DIG)));
        assertEquals("脚下和四周的 a:wall、b:wall 不算天然地形，寻路不会自己挖，可以单独下令挖掉：(6, 64, 5) a:wall、(4, 64, 5) b:wall"
                + "；minecraft:stone 这次能挖，重新算路还是没有路", walls.describe(Permissions.BlockChanges.NATURAL, true));
        assertEquals("", TrapWalls.NONE.describe(Permissions.BlockChanges.ANY, false));
    }
}
