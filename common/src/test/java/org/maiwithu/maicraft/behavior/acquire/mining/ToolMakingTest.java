// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.hands.DigToolChoice;

/** 现做工具的账：石头一格也做、泥土短活不做、木的先于石的、料只算不留的、被留着的说得清。 */
class ToolMakingTest {

    private static final DigToolChoice.Block STONE = new DigToolChoice.Block(1.5f, true, true);
    private static final DigToolChoice.Block DIRT = new DigToolChoice.Block(0.5f, false, false);
    private static final ToolMaking.Option WOODEN_PICKAXE =
            new ToolMaking.Option("minecraft:wooden_pickaxe", ToolMaking.Head.PLANKS, 2, true);
    private static final ToolMaking.Option STONE_PICKAXE =
            new ToolMaking.Option("minecraft:stone_pickaxe", ToolMaking.Head.STONE, 4, true);
    private static final ToolMaking.Option WOODEN_SHOVEL =
            new ToolMaking.Option("minecraft:wooden_shovel", ToolMaking.Head.PLANKS, 2, true);

    private static ToolMaking.Stock planks(int planks, int keptPlanks) {
        return new ToolMaking.Stock(planks, 0, 0, 0, false, keptPlanks, 0);
    }

    @Test
    void 石头只挖一格也先做木镐() {
        // 石头徒手 150 刻、木镐 23 刻：一格省 127 刻，比做一把的 120 刻多。
        var decision = ToolMaking.decide(STONE, 150, 1, List.of(WOODEN_PICKAXE, STONE_PICKAXE), planks(9, 0));
        var make = assertInstanceOf(ToolMaking.Decision.Make.class, decision);
        assertEquals("minecraft:wooden_pickaxe", make.option().itemId());
        assertEquals(127, make.savedPerBlock());
    }

    @Test
    void 泥土短活不做锹_连挖一片才做() {
        assertInstanceOf(ToolMaking.Decision.NotWorth.class,
                ToolMaking.decide(DIRT, 15, 2, List.of(WOODEN_SHOVEL), planks(9, 0)));
        assertInstanceOf(ToolMaking.Decision.Make.class,
                ToolMaking.decide(DIRT, 15, 40, List.of(WOODEN_SHOVEL), planks(9, 0)));
    }

    @Test
    void 木板不够就看石镐_两样都不够说清缺什么() {
        // 身上 2 块不留的木板、留着 20 块：木镐要 9 块不够；石镐要 3 块圆石也没有。
        var decision = ToolMaking.decide(STONE, 150, 3, List.of(WOODEN_PICKAXE, STONE_PICKAXE), planks(2, 20));
        var noMaterial = assertInstanceOf(ToolMaking.Decision.NoMaterial.class, decision);
        assertEquals("minecraft:wooden_pickaxe", noMaterial.option().itemId());
        assertTrue(noMaterial.why().contains("木板要 9 块"), noMaterial.why());
        assertTrue(noMaterial.why().contains("留着 20 块"), noMaterial.why());
    }

    @Test
    void 带着工作台和木棍时三块木板就够_原木折成木板算() {
        var withTable = new ToolMaking.Stock(3, 0, 2, 0, true, 0, 0);
        assertInstanceOf(ToolMaking.Decision.Make.class, ToolMaking.decide(STONE, 150, 1, List.of(WOODEN_PICKAXE), withTable));
        var logsOnly = new ToolMaking.Stock(0, 3, 0, 0, false, 0, 0);
        assertInstanceOf(ToolMaking.Decision.Make.class, ToolMaking.decide(STONE, 150, 1, List.of(WOODEN_PICKAXE), logsOnly));
    }

    @Test
    void 有圆石没木头做头时用石的() {
        var stock = new ToolMaking.Stock(6, 0, 0, 3, false, 0, 0);
        // 6 块木板：木镐要 9 块不够，石镐要 6 块木板（木棍 2 + 工作台 4）加 3 块圆石够。
        var make = assertInstanceOf(ToolMaking.Decision.Make.class,
                ToolMaking.decide(STONE, 150, 1, List.of(WOODEN_PICKAXE, STONE_PICKAXE), stock));
        assertEquals("minecraft:stone_pickaxe", make.option().itemId());
    }
}
