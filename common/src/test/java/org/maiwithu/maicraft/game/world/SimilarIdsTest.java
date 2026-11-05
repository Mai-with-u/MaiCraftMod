// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 写错的注册 ID 找相近的名字：只比冒号后的名字，像到一定程度才给，标签只和标签比。 */
class SimilarIdsTest {

    @Test
    void 只差几个字母给出那个ID() {
        assertEquals(Optional.of("minecraft:diamond"),
                SimilarIds.closest(List.of("minecraft:dirt", "minecraft:diamond"), "minecraft:diamon"));
    }

    @Test
    void 互相包含算很像() {
        assertEquals(Optional.of("minecraft:woodland_mansion"),
                SimilarIds.closest(List.of("minecraft:village_plains", "minecraft:woodland_mansion"), "minecraft:mansion"));
    }

    @Test
    void 共有一个长词也给提示() {
        assertEquals(Optional.of("minecraft:jungle_pyramid"),
                SimilarIds.closest(List.of("minecraft:desert_pyramid", "minecraft:jungle_pyramid"), "minecraft:jungle_temple"));
    }

    @Test
    void 跨模组只撞上一个词的不推荐() {
        // 女仆的 smart_slab_init 和航空学的 smart_propeller 只共有 smart，不是一样东西。
        assertEquals(Optional.empty(),
                SimilarIds.closest(List.of("aeronautics:smart_propeller"), "touhou_little_maid:smart_slab_init"));
    }

    @Test
    void 同一个模组里拼错照旧推荐() {
        assertEquals(Optional.of("touhou_little_maid:smart_slab_init"),
                SimilarIds.closest(List.of("aeronautics:smart_propeller", "touhou_little_maid:smart_slab_init"),
                        "touhou_little_maid:smart_slab_inti"));
        assertEquals(Optional.of("create:smart_chute"),
                SimilarIds.closest(List.of("aeronautics:smart_propeller", "create:smart_chute"), "create:smart_funnel"));
    }

    @Test
    void 只写错命名空间时同名的排在包含关系前面() {
        // 两个候选都"像"：一个只是包含，一个名字一字不差；后者才是 LLM 想要的。
        assertEquals(Optional.of("create:wrench"),
                SimilarIds.closest(List.of("minecraft:wrench_holder", "create:wrench"), "minecraft:wrench"));
    }

    @Test
    void 一个都不像就不硬凑() {
        assertEquals(Optional.empty(),
                SimilarIds.closest(List.of("minecraft:diamond", "minecraft:stone"), "minecraft:qqqq"));
        assertEquals(Optional.empty(), SimilarIds.closest(List.of(), "minecraft:stone"));
    }

    @Test
    void 标签和标签比_建议补上井号() {
        assertEquals(Optional.of("#minecraft:logs"),
                SimilarIds.closest(List.of("minecraft:planks", "minecraft:logs"), "#minecraft:log"));
    }

    @Test
    void 统一说法有建议带后半句_没有就只说不存在() {
        assertEquals("物品 minecraft:diamon 不存在；你是不是要 minecraft:diamond",
                SimilarIds.missing("物品", "minecraft:diamon", Optional.of("minecraft:diamond")));
        assertEquals("群系 minecraft:qqqq 不存在", SimilarIds.missing("群系", "minecraft:qqqq", Optional.empty()));
    }

    @Test
    void 注册ID读端按种类挑候选_标签说成某某标签() {
        ReadsRegisteredIds registered = new ReadsRegisteredIds() {
            @Override public List<String> ids(IdKind kind) {
                return switch (kind) {
                    case ITEM -> List.of("minecraft:oak_log", "minecraft:torch");
                    case BLOCK -> List.of("minecraft:coal_ore");
                    case ENTITY_TYPE -> List.of("minecraft:zombie");
                    case BIOME -> List.of("minecraft:beach", "minecraft:plains");
                    case STRUCTURE -> List.of("minecraft:village_plains");
                    case POTION -> List.of("minecraft:fire_resistance", "minecraft:long_fire_resistance");
                };
            }

            @Override public List<String> tags(IdKind kind) {
                return kind == IdKind.ITEM ? List.of("minecraft:logs") : List.of();
            }
        };
        assertEquals("物品 minecraft:oak_lg 不存在；你是不是要 minecraft:oak_log",
                registered.missing(IdKind.ITEM, "minecraft:oak_lg"));
        assertEquals("物品标签 #minecraft:log 不存在；你是不是要 #minecraft:logs",
                registered.missing(IdKind.ITEM, "#minecraft:log"));
        assertEquals("方块 minecraft:coal_oer 不存在；你是不是要 minecraft:coal_ore",
                registered.missing(IdKind.BLOCK, "minecraft:coal_oer"));
        assertEquals("药水种类 minecraft:fire_resistence 不存在；你是不是要 minecraft:fire_resistance",
                registered.missing(IdKind.POTION, "minecraft:fire_resistence"));
        assertEquals("实体 minecraft:zombi 不存在；你是不是要 minecraft:zombie",
                registered.missing(IdKind.ENTITY_TYPE, "minecraft:zombi"));
        assertEquals("群系 minecraft:beachh 不存在；你是不是要 minecraft:beach",
                registered.missing(IdKind.BIOME, "minecraft:beachh"));
        assertEquals("结构 minecraft:village 不存在；你是不是要 minecraft:village_plains",
                registered.missing(IdKind.STRUCTURE, "minecraft:village"));
    }

    /** 替身：女仆的智能台阶只是物品，墙上的火把只是方块，原木既是物品也是方块，僵尸只是实体。 */
    private static final ReadsRegisteredIds MIXED = new ReadsRegisteredIds() {
        @Override public List<String> ids(IdKind kind) {
            return switch (kind) {
                case ITEM -> List.of("minecraft:oak_log", "touhou_little_maid:smart_slab_init");
                case BLOCK -> List.of("aeronautics:smart_propeller", "minecraft:oak_log", "minecraft:wall_torch");
                case ENTITY_TYPE -> List.of("minecraft:zombie");
                default -> List.of();
            };
        }

        @Override public List<String> tags(IdKind kind) {
            return List.of();
        }
    };

    @Test
    void 物品当方块写_直说它是物品() {
        assertEquals("touhou_little_maid:smart_slab_init 是物品，不是方块",
                MIXED.missing(IdKind.BLOCK, "touhou_little_maid:smart_slab_init"));
    }

    @Test
    void 方块当物品写_直说它是方块() {
        assertEquals("minecraft:wall_torch 是方块，不是物品", MIXED.missing(IdKind.ITEM, "minecraft:wall_torch"));
    }

    @Test
    void 实体当物品写_直说它是实体() {
        assertEquals("minecraft:zombie 是实体，不是物品", MIXED.missing(IdKind.ITEM, "minecraft:zombie"));
    }

    @Test
    void 两类都有的一起说_几类都可以的按或说() {
        assertEquals("minecraft:oak_log 是物品和方块，不是实体", MIXED.missing(IdKind.ENTITY_TYPE, "minecraft:oak_log"));
        assertEquals("touhou_little_maid:smart_slab_init 是物品，不是方块或实体",
                MIXED.missing(List.of(IdKind.BLOCK, IdKind.ENTITY_TYPE), "touhou_little_maid:smart_slab_init"));
    }

    @Test
    void 要的那类里有的不说是别的() {
        // 大小写写岔了：物品里其实有原木，不说"是方块"，照旧给相近名。
        assertEquals("物品 Minecraft:Oak_Log 不存在；你是不是要 minecraft:oak_log",
                MIXED.missing(IdKind.ITEM, "Minecraft:Oak_Log"));
    }

    @Test
    void 哪类都没有又跨模组只撞一个词的只说不存在() {
        assertEquals("方块 touhou_little_maid:smart_slab_inti 不存在",
                MIXED.missing(IdKind.BLOCK, "touhou_little_maid:smart_slab_inti"));
    }
}
