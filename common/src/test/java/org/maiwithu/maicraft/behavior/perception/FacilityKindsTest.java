// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 设施按方块标签认：染色潜影盒、损坏的铁砧、进了通用标签的模组方块都算；末影箱不算容器。 */
class FacilityKindsTest {

    private final FakeBlockTags tags = FakeBlockTags.vanilla()
            .tag("c:chests", "ironchests:iron_chest", "enderstorage:ender_chest")
            .tag("c:chests/ender", "enderstorage:ender_chest")
            .tag("c:player_workstations/crafting_tables", "sometable:fancy_table");
    private final FacilityKinds kinds = new FacilityKinds(tags);

    @Test
    void 染色的潜影盒也是容器() {
        assertTrue(kinds.isContainer("minecraft:red_shulker_box"));
        assertTrue(kinds.isContainer("minecraft:light_blue_shulker_box"));
        assertTrue(kinds.isContainer("minecraft:shulker_box"));
    }

    @Test
    void 原版的箱子木桶照旧算容器() {
        assertTrue(kinds.isContainer("minecraft:chest"));
        assertTrue(kinds.isContainer("minecraft:trapped_chest"));
        assertTrue(kinds.isContainer("minecraft:barrel"));
    }

    @Test
    void 进了通用箱子标签的模组箱子算容器() {
        assertTrue(kinds.isContainer("ironchests:iron_chest"));
        assertTrue(kinds.containerBlockTypes().contains("ironchests:iron_chest"));
    }

    @Test
    void 末影箱不算容器() {
        // 原版的末影箱按 ID 和标签都挡住；模组的末影箱只要挂着末影箱标签同样不算。
        assertFalse(kinds.isContainer("minecraft:ender_chest"));
        assertFalse(kinds.isContainer("enderstorage:ender_chest"));
        assertFalse(kinds.containerBlockTypes().contains("minecraft:ender_chest"));
        assertFalse(kinds.containerBlockTypes().contains("enderstorage:ender_chest"));
    }

    @Test
    void 开裂与损坏的铁砧也是工作设施() {
        assertTrue(kinds.isWorkstation("minecraft:chipped_anvil"));
        assertTrue(kinds.isWorkstation("minecraft:damaged_anvil"));
        assertTrue(kinds.workstationBlockTypes().contains("minecraft:damaged_anvil"));
    }

    @Test
    void 进了通用工作台标签的模组台子算工作设施() {
        assertTrue(kinds.isWorkstation("sometable:fancy_table"));
        assertTrue(kinds.workstationBlockTypes().contains("sometable:fancy_table"));
    }

    @Test
    void 原版按名字列的工作设施不靠标签也认() {
        FacilityKinds noTags = new FacilityKinds(new FakeBlockTags());
        assertTrue(noTags.isWorkstation("minecraft:smoker"));
        assertTrue(noTags.isWorkstation("minecraft:anvil"));
        assertTrue(noTags.workstationBlockTypes().contains("minecraft:brewing_stand"));
    }

    @Test
    void 没挂标签的方块两类都不算() {
        assertFalse(kinds.isContainer("minecraft:furnace"));
        assertFalse(kinds.isWorkstation("minecraft:chest"));
        assertFalse(kinds.isContainer("minecraft:stone"));
    }

    @Test
    void 设施观察的种类里有床() {
        // 床没有方块标签，从注册表的全部方块类型里按名字认：染色床、模组照这个名字起的床都算，
        // 不叫这个名字的方块不算；设施观察的清单把床与容器、工作设施并在一起，观察一侧只取这一份。
        FakeBlockTags withBeds = FakeBlockTags.vanilla()
                .block("minecraft:white_bed", "minecraft:red_bed", "somemod:camp_bed", "somemod:bedrock");
        FacilityKinds beds = new FacilityKinds(withBeds);

        assertTrue(beds.bedBlockTypes().contains("minecraft:white_bed"));
        assertTrue(beds.bedBlockTypes().contains("minecraft:red_bed"));
        assertTrue(beds.bedBlockTypes().contains("somemod:camp_bed"));
        assertFalse(beds.bedBlockTypes().contains("somemod:bedrock"));
        assertTrue(beds.facilityBlockTypes().contains("minecraft:chest"));
        assertTrue(beds.facilityBlockTypes().contains("minecraft:furnace"));
        assertTrue(beds.facilityBlockTypes().contains("minecraft:white_bed"));
        assertFalse(beds.facilityBlockTypes().contains("minecraft:ender_chest"));
    }
}
