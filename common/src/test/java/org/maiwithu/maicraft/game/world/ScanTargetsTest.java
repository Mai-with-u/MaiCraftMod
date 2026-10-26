// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 登记账本与索引的核对：换世界时索引整体清空，账本却跟着读端活下来，
 * 账本说登记过而索引不认得的方块要补登记，find 才不会把"没登记"报成"附近没有"。
 */
class ScanTargetsTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    /** 登记处替身：记下每次提交的种类，known 集合表示索引此刻认得的方块，退世界时清空。 */
    private static final class FakeScans implements RegistersScanTargets {
        final Set<Block> known = new HashSet<>();
        final List<Set<Block>> submitted = new ArrayList<>();

        /** 模拟离开世界：索引整体清空，读端的账本不知情。 */
        void dropAll() {
            known.clear();
        }

        @Override public boolean registered(ResourceKey<Level> dimension, Block block) {
            return known.contains(block);
        }

        @Override public void register(ResourceKey<Level> dimension, Collection<Block> blocks) {
            submitted.add(Set.copyOf(blocks));
            known.addAll(blocks);
        }
    }

    @Test
    void 第一次用到的方块登记进索引() {
        FakeScans scans = new FakeScans();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertEquals(List.of(Set.of(Blocks.WATER)), scans.submitted);
    }

    @Test
    void 登记过且索引认得的不再重复登记() {
        FakeScans scans = new FakeScans();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertEquals(1, scans.submitted.size(), "登记是计数式的，同一读端不重复提交");
    }

    @Test
    void 索引清空后账本记过的要补登记() {
        FakeScans scans = new FakeScans();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        scans.dropAll();
        // 回到世界（账本里还记着水）：索引不认得了，必须补登记，
        // 否则索引里没有这种方块，find 把"没登记"报成"附近没有"。
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertEquals(2, scans.submitted.size());
        assertEquals(Set.of(Blocks.WATER), scans.submitted.get(1));
    }

    @Test
    void 清空后新出现的方块照常登记() {
        FakeScans scans = new FakeScans();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        scans.dropAll();
        targets.ensure(OVERWORLD, List.of(Blocks.ENCHANTING_TABLE));
        assertEquals(2, scans.submitted.size());
        assertEquals(Set.of(Blocks.ENCHANTING_TABLE), scans.submitted.get(1));
    }

    @Test
    void 不同维度各记各的账() {
        FakeScans scans = new FakeScans();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        // 另一个维度还没登记过，索引也不认得它：照常登记。
        targets.ensure(NETHER, List.of(Blocks.WATER));
        assertEquals(2, scans.submitted.size());
    }
}
