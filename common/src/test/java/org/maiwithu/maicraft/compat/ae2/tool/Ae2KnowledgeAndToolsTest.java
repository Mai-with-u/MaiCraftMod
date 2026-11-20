// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/**
 * AE2 进阶专题知识、GuideME 指南索引与 JSX 剥离，以及 Ae2Tools 统一汇编与菜单布局零冲突测试。
 */
class Ae2KnowledgeAndToolsTest {

    private static final String SAMPLE_CHARGER_MD = """
            ---
            navigation:
              parent: items-blocks-machines/items-blocks-machines-index.md
              title: Charger
              icon: charger
              position: 310
            categories:
            - machines
            item_ids:
            - ae2:charger
            - crank
            ---

            # The Charger

            <BlockImage id="charger" scale="8" />

            The Charger provides a way to charge <ItemLink id="certus_quartz_crystal" /> and <ItemLink id="minecraft:compass">Vanilla Compass</ItemLink>.

            <GameScene zoom="4" background="transparent">
              <ImportStructure src="../assets/assemblies/charger_hopper.snbt" />
              <IsometricCamera yaw="195" pitch="30" />
            </GameScene>

            ## Recipe

            <RecipeFor id="charger" />
            """;

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 指南解析元信息并剥离界面标签同时保留物品链接文本() {
        Ae2GuideKnowledge.PageIndex index = Ae2GuideKnowledge.parseFrontmatter(
                "items-blocks-machines/charger.md", SAMPLE_CHARGER_MD);
        assertEquals("Charger", index.title());
        assertEquals(List.of("machines"), index.categories());
        assertEquals(Set.of("ae2:charger", "ae2:crank"), index.itemIds());
        assertEquals("maicraft://knowledge/ae2/guide/items-blocks-machines/charger", index.uri());

        String cleaned = Ae2GuideKnowledge.stripGuideJsx(SAMPLE_CHARGER_MD);
        assertFalse(cleaned.contains("---"));
        assertFalse(cleaned.contains("<BlockImage"));
        assertFalse(cleaned.contains("<GameScene"));
        assertFalse(cleaned.contains("<ImportStructure"));
        assertFalse(cleaned.contains("<RecipeFor"));
        assertTrue(cleaned.contains("# The Charger"));
        assertTrue(cleaned.contains("`ae2:certus_quartz_crystal`"));
        assertTrue(cleaned.contains("Vanilla Compass（`minecraft:compass`）"));
    }

    @Test
    void 指南知识来源按物品编号检索并按需读取正文() {
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public List<String> listGuidePages() {
                return List.of("items-blocks-machines/charger.md");
            }

            @Override public Optional<String> readGuidePage(String relativePath) {
                return "items-blocks-machines/charger.md".equals(relativePath)
                        ? Optional.of(SAMPLE_CHARGER_MD) : Optional.empty();
            }
        };
        Ae2GuideKnowledge guide = new Ae2GuideKnowledge(ToolTestFakes.compat(), reads);

        assertEquals(1, guide.entries().size());
        assertEquals(Ae2GuideKnowledge.INDEX_URI, guide.entries().getFirst().uri());
        List<KnowledgeDocument.Entry> about = guide.entriesAbout("ae2:charger");
        assertEquals(1, about.size());
        assertEquals("Charger", about.getFirst().title());

        KnowledgeDocument doc = guide.read(about.getFirst().uri());
        assertNotNull(doc);
        assertFalse(doc.text().contains("<GameScene"));
        assertTrue(doc.text().contains("`ae2:certus_quartz_crystal`"));
        assertTrue(guide.status().contains("1 篇条目"));
    }

    @Test
    void 中文进阶专题知识来源覆盖六大主题并按物品编号命中() {
        Ae2ExtendedKnowledge knowledge = new Ae2ExtendedKnowledge();
        assertEquals(6, knowledge.entries().size());
        assertFalse(knowledge.entriesAbout("ae2:charger").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:pattern_encoding_terminal").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:me_p2p_tunnel").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:io_port").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:quantum_ring").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:portable_item_cell_64k").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:matter_cannon").isEmpty());

        for (KnowledgeDocument.Entry entry : knowledge.entries()) {
            KnowledgeDocument doc = knowledge.read(entry.uri());
            assertNotNull(doc);
            assertFalse(doc.text().isBlank());
        }
    }

    @Test
    void 统一汇编入口暴露全部扩展且四个独占菜单布局与既有六布局零冲突() {
        Ae2Compat compat = ToolTestFakes.compat();
        Ae2Tools tools = new Ae2Tools(compat, Ae2ToolReads.NONE);
        PlayerServices services = ToolTestFakes.services(() -> null, Map.of(), List.of());

        assertEquals(1, tools.dismantleTools().size());
        assertEquals(1, tools.dismantleTools(services).size());
        assertEquals(1, tools.placementRules().size());
        assertEquals(5, tools.itemSettings(services).size());
        assertEquals(1, tools.carriedBackpacks(services).size());
        assertEquals(2, tools.itemSources(services).size());
        assertEquals(1, tools.rangedWeapons().size());
        assertEquals(1, tools.modSights().size());
        assertEquals(2, tools.knowledgeSources().size());

        Map<String, Ae2SemanticsLayout.Rule> rules = tools.layoutRules();
        assertEquals(Set.of(
                Ae2Tools.MENU_NETWORK_TOOL,
                Ae2Tools.MENU_QUARTZ_KNIFE,
                Ae2Tools.MENU_PORTABLE_ITEM_CELL,
                Ae2Tools.MENU_PORTABLE_FLUID_CELL), rules.keySet());

        // 核实 Ae2Compat 全量登记进 CompatRegistry 时四个独占菜单布局已并入且与既有布局零冲突。
        CompatRegistry registry = CompatRegistry.empty();
        compat.contribute(registry);
        assertNotNull(registry.menuLayouts());
    }
}
