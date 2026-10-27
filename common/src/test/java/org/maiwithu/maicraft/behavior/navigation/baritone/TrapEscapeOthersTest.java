// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
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
import org.maiwithu.maicraft.behavior.navigation.OthersBlockBroken;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 被别人的方块封死在小坑里：放开别人放的普通方块挖出来，挖之前记下是谁放的；一趟最多 24 格；
 * 那次脱困一收场当场报一条事件。这次任务自己保护的格、没放开时都不算。
 */
class TrapEscapeOthersTest {

    private static final String OWNER = "11111111-2222-3333-4444-555555555555";
    private static final BlockPos FEET = new BlockPos(5, 64, 5);

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

    // 这个世界里所有泥土都是 OWNER 放的普通方块，别的都不是。
    private static void dirtPlacedByOwner() {
        NavigationProtection.installWorldRule(new NavigationProtection.CellRule() {
            @Override public boolean protects(int x, int y, int z, BlockState state) {
                return true;
            }

            @Override public Optional<String> placedByOtherWhenTrapped(int x, int y, int z, BlockState state) {
                return state != null && state.is(Blocks.DIRT) ? Optional.of(OWNER) : Optional.empty();
            }
        });
    }

    private static void install(boolean throughOthers, LongSet sacred) {
        NavigationProtection.install(sacred, LongSets.emptySet(), Integer.MIN_VALUE, throughOthers);
    }

    @Test
    void 放开了才算_这次任务自己保护的格照旧不碰() {
        dirtPlacedByOwner();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        install(false, LongSets.emptySet());
        assertEquals(Optional.empty(), NavigationProtection.escapeOwner(FEET, dirt), "没放开：别人的泥土照旧受保护");
        install(true, LongSets.emptySet());
        assertEquals(Optional.of(OWNER), NavigationProtection.escapeOwner(FEET, dirt));
        assertEquals(Optional.empty(), NavigationProtection.escapeOwner(FEET, Blocks.STONE.defaultBlockState()),
                "不是别人的普通方块不放开");
        install(true, LongSets.singleton(FEET.asLong()));
        assertEquals(Optional.empty(), NavigationProtection.escapeOwner(FEET, dirt), "这次任务额外保护的格不碰");
    }

    @Test
    void 挖之前记下是谁放的_出坑报一条事件() {
        dirtPlacedByOwner();
        List<String> events = new ArrayList<>();
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, (kind, message) -> {
            assertEquals(TaskEvent.Kind.OTHERS_BLOCKS_BROKEN, kind);
            events.add(message);
        });
        assertTrue(escape.raise(FEET, () -> true, 0));
        install(escape.throughOthers(), LongSets.emptySet());

        BlockPos wall = FEET.east();
        escape.watchDigging(wall, Blocks.DIRT.defaultBlockState(), null);
        assertFalse(escape.noteBroken(wall));
        // 挖开的是天然石头（不是别人的方块）：只记挖开了一格，不进别人的账。
        BlockPos stone = FEET.above(2);
        escape.watchDigging(stone, Blocks.STONE.defaultBlockState(), null);
        escape.noteBroken(stone);

        assertEquals(List.of(new OthersBlockBroken(wall.toShortString(), "minecraft:dirt", OWNER)), escape.othersBroken());
        assertTrue(events.isEmpty(), "还在坑里时不报");
        escape.restore();
        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains(FEET.toShortString()) && events.getFirst().contains(OWNER + " 放的 1 格")
                && events.getFirst().contains("出来了"), events.getFirst());
        assertTrue(escape.notes().getFirst().contains("挖开别人放的普通方块") && escape.notes().getFirst().contains("挖开 2 格"),
                escape.notes().getFirst());
        assertEquals(1, escape.othersBroken().size(), "出坑后整趟的账还在，出行结算要列出来");
    }

    @Test
    void 一趟最多挖二十四格_到上限收回放开_走不通时写明() {
        dirtPlacedByOwner();
        List<String> events = new ArrayList<>();
        TrapEscape escape = new TrapEscape(TerrainPermit.NATURAL, (kind, message) -> events.add(message));
        assertTrue(escape.raise(FEET, () -> true, 0));
        install(true, LongSets.emptySet());
        boolean closed = false;
        for (int i = 0; i < TrapEscape.MAX_OTHERS_BLOCKS; i++) {
            BlockPos cell = FEET.east(i + 1);
            escape.watchDigging(cell, Blocks.DIRT.defaultBlockState(), null);
            closed = escape.noteBroken(cell);
            if (i < TrapEscape.MAX_OTHERS_BLOCKS - 1) assertFalse(closed);
        }
        assertTrue(closed, "第二十四格挖开就收回放开，调用方重新算路");
        assertFalse(escape.throughOthers());
        assertFalse(escape.raise(FEET, () -> true, 10), "这一趟到上限后不再放开");
        Problem stuck = escape.explain(Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null));
        assertTrue(stuck.message().contains("24 格的上限") && stuck.message().contains("还是没出去"), stuck.message());
        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains("还是没出去"), events.getFirst());
        // 同一次脱困的说明可能被问好几次，事件只报一次。
        escape.explain(Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null));
        assertEquals(1, events.size());
    }

    @Test
    void 挖出坑就走到了_收场时补报一次_停下的不说出没出来() {
        dirtPlacedByOwner();
        List<String> events = new ArrayList<>();
        TrapEscape arrived = new TrapEscape(TerrainPermit.NATURAL, (kind, message) -> events.add(message));
        assertTrue(arrived.raise(FEET, () -> true, 0));
        install(true, LongSets.emptySet());
        arrived.watchDigging(FEET.east(), Blocks.DIRT.defaultBlockState(), null);
        arrived.noteBroken(FEET.east());
        arrived.finish(true);
        assertTrue(events.getLast().endsWith("出来了"), events.getLast());
        assertTrue(arrived.notes().getFirst().contains("走到了目的地"), arrived.notes().getFirst());
        assertFalse(arrived.throughOthers(), "收场时收回放开");

        TrapEscape stopped = new TrapEscape(TerrainPermit.NATURAL, (kind, message) -> events.add(message));
        assertTrue(stopped.raise(FEET, () -> true, 0));
        stopped.watchDigging(FEET.west(), Blocks.DIRT.defaultBlockState(), null);
        stopped.noteBroken(FEET.west());
        stopped.finish(false);
        assertTrue(events.getLast().endsWith(OWNER + " 放的 1 格"), "停下的说不准出没出来：" + events.getLast());
        stopped.finish(false);
        assertEquals(2, events.size(), "收过场的不再报");
    }

    @Test
    void 挖了几个人的方块按人分开数() {
        String text = TrapEscape.whose(List.of(new OthersBlockBroken("1, 64, 1", "minecraft:dirt", "甲"),
                new OthersBlockBroken("2, 64, 1", "minecraft:dirt", "乙"),
                new OthersBlockBroken("3, 64, 1", "minecraft:glass", "甲")));
        assertEquals("甲 放的 2 格、乙 放的 1 格", text);
    }
}
