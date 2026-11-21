// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/** 应用机械中文知识来源离线测试：核对固定 URI、全部 12 件物品与 QIO 索引及核心机制正文覆盖。 */
class AppMekKnowledgeTest {

    @Test
    void 覆盖全部应用机械物品并给出总线样板通道与放射性限制要点() {
        AppMekKnowledge knowledge = new AppMekKnowledge();
        assertEquals(1, knowledge.entries().size());
        assertEquals(AppMekKnowledge.URI_INDEX, knowledge.entries().getFirst().uri());

        List<String> expectedIds = List.of(
                "appmek:chemical_cell_housing",
                "appmek:chemical_storage_cell_1k",
                "appmek:chemical_storage_cell_4k",
                "appmek:chemical_storage_cell_16k",
                "appmek:chemical_storage_cell_64k",
                "appmek:chemical_storage_cell_256k",
                "appmek:portable_chemical_cell_1k",
                "appmek:portable_chemical_cell_4k",
                "appmek:portable_chemical_cell_16k",
                "appmek:portable_chemical_cell_64k",
                "appmek:portable_chemical_cell_256k",
                "appmek:chemical_p2p_tunnel",
                "mekanism:qio_dashboard");
        for (String id : expectedIds) {
            assertEquals(1, knowledge.entriesAbout(id).size(), "应命中知识条目：" + id);
        }
        assertTrue(knowledge.entriesAbout("minecraft:stone").isEmpty());
        assertNull(knowledge.read("maicraft://knowledge/machine/appmek/missing"));

        KnowledgeDocument doc = knowledge.read(AppMekKnowledge.URI_INDEX);
        assertNotNull(doc);
        assertTrue(doc.text().contains("key_type.chemical"));
        assertTrue(doc.text().contains("chemical:<id>"));
        assertTrue(doc.text().contains("\"kind\":\"chemical\""));
        assertTrue(doc.text().contains("attunement=chemical"));
        assertTrue(doc.text().contains("mekanism:qio_dashboard"));
        assertTrue(doc.text().contains("背面"));
        assertTrue(doc.text().contains("仅支持物品"));
        assertTrue(doc.text().contains("不支持化学品"));
        assertTrue(doc.text().contains("放射性"));
        assertTrue(doc.text().contains("ae2:condenser"));
    }
}
