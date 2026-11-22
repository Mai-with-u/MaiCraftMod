// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 找方块近处遮挡线索的离线测试：近距墙后的神龛或功能块给出遮挡线索，
 * 地下自然矿石与未知远处被挡方块严守防透视红线、绝不泄露坐标。
 */
class HiddenBlockLeadsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final String OVERWORLD = "minecraft:overworld";
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    @BeforeAll
    static void bootMinecraft() {
        // 离线构造原版方块状态核验矿石与天然地形分类：先引导注册表。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 近距墙后神龛给出遮挡线索并取最近一处() {
        HiddenBlockLeads leads = new HiddenBlockLeads();
        leads.followQuery(CENTER, List.of("touhou_little_maid:shrine"), 1, 48);

        // 两处近距（32 格内）被屋檐与墙体挡住的神龛，跨轮次累计后线索报总数与最近坐标。
        leads.recordIfEligible(new BlockPos(20, 65, 0), false, CENTER, OVERWORLD, null);
        leads.recordIfEligible(new BlockPos(6, 65, -8), false, CENTER, OVERWORLD, null);

        String lead = leads.lead(CENTER);
        assertNotNull(lead);
        assertTrue(lead.contains("另有 2 处被墙体挡住"), lead);
        assertTrue(lead.contains("(6, 65, -8)"), lead);
        assertTrue(lead.contains("约 10 格"), lead);
        assertTrue(lead.contains("走过去看得见了再找"), lead);

        // 无可见命中结算时，结论直接带上该遮挡线索，不误导成附近没有神龛。
        FindInput input = new FindInput(FindInput.FindKind.BLOCK, List.of("touhou_little_maid:shrine"), 1, 48);
        TaskResult result = FindSettlement.insufficient(input, List.of(), Map.of(), lead);
        assertTrue(result.summary().contains("(6, 65, -8)"), result.summary());
    }

    @Test
    void 深埋未露头自然矿石与天然地形绝不泄露透视线索() {
        HiddenBlockLeads leads = new HiddenBlockLeads();
        leads.followQuery(CENTER, List.of("minecraft:diamond_ore"), 4, 48);

        // 即使就在 10 格内被石头挡住，自然矿石与远古残骸也不允许进遮挡线索。
        assertTrue(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()));
        assertTrue(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.ANCIENT_DEBRIS.defaultBlockState()));
        assertTrue(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.NETHER_QUARTZ_ORE.defaultBlockState()));
        assertTrue(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.STONE.defaultBlockState()));
        assertTrue(HiddenBlockLeads.isOreBlockId("create:zinc_ore"));
        assertTrue(HiddenBlockLeads.isOreBlockId("mekanism:deepslate_osmium_ore"));
        assertFalse(HiddenBlockLeads.isNaturalOreOrTerrain(Blocks.CRAFTING_TABLE.defaultBlockState()));
        assertFalse(HiddenBlockLeads.isOreBlockId("touhou_little_maid:shrine"));

        leads.recordIfEligible(new BlockPos(4, 58, 0), Blocks.DIAMOND_ORE.defaultBlockState(), CENTER, OVERWORLD, null);
        leads.recordIfEligible(new BlockPos(5, 58, 0), Blocks.ANCIENT_DEBRIS.defaultBlockState(), CENTER, OVERWORLD, null);
        leads.recordIfEligible(new BlockPos(6, 58, 0), true, CENTER, OVERWORLD, null);
        assertNull(leads.lead(CENTER), "自然矿石与天然地形不得产生任何遮挡线索");
    }

    @Test
    void 超过近距且不在已记住建筑或区域内的被挡方块不给线索_在已记住区域内才给(@TempDir Path temp) {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "b".repeat(64));
        memory.rememberRegion("神社院子", new WorldPosition(44, 64, 0, OVERWORLD), 10);
        memory.rememberBuilding(new WorldPosition(-45, 64, 0, OVERWORLD), "小木屋", NOW);

        HiddenBlockLeads leads = new HiddenBlockLeads();
        leads.followQuery(CENTER, List.of("touhou_little_maid:shrine"), 1, 64);

        // 45 格外未知区域里被挡住的方块：既超 32 格又不在已记住建筑或区域内，不给线索。
        leads.recordIfEligible(new BlockPos(0, 64, 45), false, CENTER, OVERWORLD, memory);
        assertNull(leads.lead(CENTER));

        // 45 格外已记住区域内被挡住的神龛：允许给线索。
        leads.recordIfEligible(new BlockPos(45, 64, 0), false, CENTER, OVERWORLD, memory);
        assertNotNull(leads.lead(CENTER));
        assertTrue(leads.lead(CENTER).contains("(45, 64, 0)"), leads.lead(CENTER));

        // 已记住建筑附近 24 格内同样允许；切换查询后旧账本清空。
        leads.followQuery(CENTER, List.of("minecraft:enchanting_table"), 1, 64);
        assertNull(leads.lead(CENTER), "查询条件变化后清空旧线索");
        leads.recordIfEligible(new BlockPos(-42, 65, 0), false, CENTER, OVERWORLD, memory);
        assertNotNull(leads.lead(CENTER));
        assertTrue(leads.lead(CENTER).contains("(-42, 65, 0)"), leads.lead(CENTER));
    }

    @Test
    void 同一格转为可见后从遮挡线索中移除() {
        HiddenBlockLeads leads = new HiddenBlockLeads();
        leads.followQuery(CENTER, List.of("touhou_little_maid:shrine"), 1, 32);
        BlockPos shrine = new BlockPos(8, 64, 0);

        leads.recordIfEligible(shrine, false, CENTER, OVERWORLD, null);
        assertNotNull(leads.lead(CENTER));
        leads.markVisible(shrine);
        assertNull(leads.lead(CENTER), "变为可见并命中后不再重复报成被墙体挡住");
    }
}
