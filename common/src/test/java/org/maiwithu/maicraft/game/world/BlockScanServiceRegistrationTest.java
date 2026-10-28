// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 登记账本与真实索引的核对：读端经 {@link ScanTargets} 在生产扫描服务上登记目标方块，
 * 索引只认登记过的方块；离开世界时索引整体清空而账本跟着读端活下来，
 * 补登记前先问索引还认不认得，对不上的重新登记——找方块才不会把"没登记"报成"附近没有"。
 * 这里接真实索引（不经替身），钉住登记、清账与补登记在索引一侧的语义。
 */
class BlockScanServiceRegistrationTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    @Test
    void 读端登记过的方块索引就认得() {
        BlockScanService scans = new BlockScanService();
        ScanTargets targets = new ScanTargets(scans);
        assertFalse(scans.registered(OVERWORLD, Blocks.WATER), "还没登记过，索引不该认得");
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertTrue(scans.registered(OVERWORLD, Blocks.WATER), "登记过之后索引必须认得，否则查询把没登记报成附近没有");
    }

    @Test
    void 换世界清空索引后账本记过的要补登记回真实索引() {
        BlockScanService scans = new BlockScanService();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        // 模拟离开世界：扫描服务整体清空，读端的账本不知情。
        scans.dropAll();
        assertFalse(scans.registered(OVERWORLD, Blocks.WATER), "索引清空后不该再认得");
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertTrue(scans.registered(OVERWORLD, Blocks.WATER), "账本记过而索引不认得时必须补登记");
    }

    @Test
    void 登记是按方块一种一种对的账() {
        BlockScanService scans = new BlockScanService();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertFalse(scans.registered(OVERWORLD, Blocks.CHERRY_LOG), "只登记过水，原木还不认得");
        targets.ensure(OVERWORLD, List.of(Blocks.WATER, Blocks.CHERRY_LOG));
        assertTrue(scans.registered(OVERWORLD, Blocks.WATER));
        assertTrue(scans.registered(OVERWORLD, Blocks.CHERRY_LOG));
    }

    @Test
    void 维度各记各的账() {
        BlockScanService scans = new BlockScanService();
        ScanTargets targets = new ScanTargets(scans);
        targets.ensure(OVERWORLD, List.of(Blocks.WATER));
        assertFalse(scans.registered(NETHER, Blocks.WATER), "主世界登记过不代表下界也登记了");
        targets.ensure(NETHER, List.of(Blocks.WATER));
        assertTrue(scans.registered(NETHER, Blocks.WATER));
    }
}
