// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/**
 * 载具部件资料：随包那一篇读得出来，只认自己的地址；这一套三个模组的物品资料页挂上它，别的模组不挂；
 * 技能和资料索引指过来的地址就是这一篇。读的是真资源文件，少一篇或改了地址这里先红。
 */
class VehiclePartsKnowledgeTest {

    private final VehiclePartsKnowledge knowledge = new VehiclePartsKnowledge();

    @Test
    void 读得出随包那一篇_别的地址不归它管() {
        KnowledgeDocument document = knowledge.read(VehiclePartsKnowledge.URI);
        assertNotNull(document);
        assertTrue(document.text().startsWith("# 载具部件"), document.text().substring(0, 20));
        assertEquals("text/markdown", document.mimeType());
        assertNull(knowledge.read("maicraft://knowledge/vehicle/other"));
        assertEquals(List.of(VehiclePartsKnowledge.URI), knowledge.entries().stream().map(KnowledgeDocument.Entry::uri).toList());
    }

    @Test
    void 这一套三个模组的资料页挂上它() {
        for (String id : List.of("simulated:physics_assembler", "aeronautics:propeller_bearing", "offroad:wheel_mount")) {
            assertEquals(1, knowledge.entriesAbout(id).size(), id);
        }
        assertTrue(knowledge.entriesAbout("create:mechanical_bearing").isEmpty(), "机械动力自己的方块不挂");
        assertTrue(knowledge.entriesAbout("simulated").isEmpty(), "不是注册 ID 不挂");
    }

    @Test
    void 技能和资料索引指的就是这一篇() throws IOException {
        assertTrue(resource("/assets/maicraft/skills/create/create-vehicles/SKILL.md").contains(VehiclePartsKnowledge.URI),
                "技能里指资料的地址对不上");
        assertTrue(resource("/assets/maicraft/knowledge/index.md").contains("(" + VehiclePartsKnowledge.URI + ")"),
                "资料索引里没登记这一篇");
        assertTrue(resource(VehiclePartsKnowledge.RESOURCE).contains("skill://create/create-vehicles/SKILL.md"),
                "资料里指技能的地址对不上");
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = VehiclePartsKnowledgeTest.class.getResourceAsStream(name)) {
            assertNotNull(input, "缺少资源 " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
