// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 世界生成往地上铺哪些实心方块：地物配置里"把地里的方块换成什么"收换成的方块，替换目标、判断条件、树和植被不收；
 * 地表规则收铺的方块；只留碰撞箱整格的实心方块。原版注册表整个跑一遍核对收进来的和没收的。
 */
class GeneratedTerrainBlocksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Set<ResourceLocation> ids(String... names) {
        return Set.of(names).stream().map(ResourceLocation::parse).collect(Collectors.toSet());
    }

    @Test
    void 矿脉每一项的换成方块都收_替换目标不收() {
        String ore = """
                {"type":"minecraft:ore","config":{"size":9,"discard_chance_on_air_exposure":0.0,"targets":[
                  {"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:stone_ore_replaceables"},
                   "state":{"Name":"minecraft:iron_ore"}},
                  {"target":{"predicate_type":"minecraft:block_match","block":"minecraft:deepslate"},
                   "state":{"Name":"minecraft:deepslate_iron_ore"}}]}}
                """;
        assertEquals(ids("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
                GeneratedTerrainBlocks.replacementsIn(JsonParser.parseString(ore)));
    }

    @Test
    void 圆盘收按规则挑的候选和兜底_判断条件里的方块不收() {
        // 火山群系往陶瓦里铺黑沙岩的圆盘就是这个样子：替换目标是一串方块，换成的方块按规则挑。
        String disk = """
                {"type":"minecraft:disk","config":{"half_height":2,"radius":{"type":"minecraft:uniform","min_inclusive":2,"max_inclusive":5},
                  "target":{"type":"minecraft:matching_blocks","blocks":["minecraft:terracotta","minecraft:red_sand"]},
                  "state_provider":{"fallback":{"type":"minecraft:simple_state_provider","state":{"Name":"biomesoplenty:black_sandstone"}},
                    "rules":[{"if_true":{"type":"minecraft:would_survive","state":{"Name":"minecraft:oak_sapling"}},
                              "then":{"type":"minecraft:simple_state_provider","state":{"Name":"biomesoplenty:black_sand"}}}]}}}
                """;
        assertEquals(ids("biomesoplenty:black_sandstone", "biomesoplenty:black_sand"),
                GeneratedTerrainBlocks.replacementsIn(JsonParser.parseString(disk)));
    }

    @Test
    void 机械动力的分层矿脉一层套一层也认得出() {
        String layered = """
                {"type":"create:layered_ore","config":{"size":64,"discard_chance_on_air_exposure":0.0,"layer_patterns":[[
                  {"targets":[[{"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:stone_ore_replaceables"},
                                "state":{"Name":"create:asurine"}}]],"min_size":1,"max_size":3,"weight":1}]]}}
                """;
        assertEquals(ids("create:asurine"), GeneratedTerrainBlocks.replacementsIn(JsonParser.parseString(layered)));
    }

    @Test
    void 树和植被没有替换目标_不收() {
        String tree = """
                {"type":"minecraft:tree","config":{"trunk_provider":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:oak_log"}},
                  "foliage_provider":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:oak_leaves"}},
                  "dirt_provider":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:dirt"}}}}
                """;
        assertTrue(GeneratedTerrainBlocks.replacementsIn(JsonParser.parseString(tree)).isEmpty());
    }

    @Test
    void 地表规则收每条铺的方块_条件里没有方块() {
        String rules = """
                {"type":"minecraft:sequence","sequence":[
                  {"type":"minecraft:condition","if_true":{"type":"minecraft:vertical_gradient","random_name":"minecraft:bedrock_floor",
                    "true_at_and_below":{"above_bottom":0},"false_at_and_above":{"above_bottom":5}},
                   "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:bedrock"}}},
                  {"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block","Properties":{"snowy":"false"}}}]}
                """;
        assertEquals(ids("minecraft:bedrock", "minecraft:grass_block"),
                GeneratedTerrainBlocks.surfaceBlocksIn(JsonParser.parseString(rules)));
    }

    @Test
    void 只收整格实心方块_流体空气和矮方块不收() {
        assertTrue(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("minecraft:deepslate")));
        assertFalse(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("minecraft:water")));
        assertFalse(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("minecraft:lava")));
        assertFalse(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("minecraft:air")));
        assertFalse(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("minecraft:snow")));
        assertFalse(GeneratedTerrainBlocks.solidGround(ResourceLocation.parse("othermod:not_installed")));
    }

    @Test
    void 原版世界生成整个跑一遍_地里铺的收进来_做出来的和流体不收() {
        List<String> laid = GeneratedTerrainBlocks.collect(VanillaRegistries.createLookup());
        for (String natural : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:dirt",
                "minecraft:gravel", "minecraft:iron_ore", "minecraft:netherrack", "minecraft:basalt",
                "minecraft:end_stone", "minecraft:sandstone", "minecraft:bedrock")) {
            assertTrue(laid.contains(natural), natural + " 是世界生成铺的");
        }
        for (String made : List.of("minecraft:oak_log", "minecraft:cut_sandstone", "minecraft:chiseled_sandstone",
                "minecraft:smooth_sandstone", "minecraft:water", "minecraft:lava", "minecraft:air")) {
            assertFalse(laid.contains(made), made + " 不是世界生成往地上铺的实心方块");
        }
        assertEquals(laid.stream().sorted().toList(), laid, "按字母排好");
    }
}
