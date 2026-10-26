// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 随包技能的加载与门控：索引里的十篇都在，按模组筛，正文原样读出。
 *
 * <p>技能文件在 main 资源里，测试直接读真文件；这样名字、目录、frontmatter 一起被检查，
 * 不会出现测试替身全绿、真实资源却少一篇的情况。
 */
class SkillLibraryTest {

    @Test
    void loadsEveryIndexedSkillWithItsAddress() {
        SkillLibrary library = new SkillLibrary(mod -> true);
        List<Skill> catalog = library.catalog();
        assertEquals(10, catalog.size(), "索引里的十篇都加载");
        Set<String> uris = catalog.stream().map(Skill::uri).collect(Collectors.toSet());
        assertTrue(uris.contains("skill://survival/survival-opening/SKILL.md"));
        assertTrue(uris.contains("skill://create/create-machine-workflow/SKILL.md"));
        assertTrue(uris.contains("skill://mekanism/mekanism-getting-started/SKILL.md"));
        assertTrue(uris.contains("skill://progression/progression-ftb-quests/SKILL.md"));
        assertTrue(uris.contains("skill://building/building-workflow/SKILL.md"));
        assertTrue(uris.contains("skill://create/create-vehicles/SKILL.md"));
        assertTrue(uris.stream().allMatch(uri -> uri.endsWith("/SKILL.md")), "地址都以 SKILL.md 结尾");
    }

    @Test
    void hidesSkillsWhoseModsAreMissing() {
        SkillLibrary vanillaOnly = new SkillLibrary(mod -> false);
        assertEquals(4, vanillaOnly.catalog().size(), "没装联动模组时只剩四篇不依赖模组的技能");
        assertFalse(vanillaOnly.catalog().stream().anyMatch(skill -> skill.name().equals("create-machine-workflow")));
        assertThrows(IllegalArgumentException.class,
                () -> vanillaOnly.read("skill://create/create-machine-workflow/SKILL.md"));
        assertEquals(4, vanillaOnly.list().getAsJsonArray("resources").size(), "目录也不列不发布的技能");

        SkillLibrary withCreate = new SkillLibrary(mod -> mod.equals("create"));
        assertEquals(6, withCreate.catalog().size(), "装了 Create 多出两篇");
        assertTrue(withCreate.catalog().stream().anyMatch(skill -> skill.name().equals("create-power-network")));
        assertThrows(IllegalArgumentException.class,
                () -> withCreate.read("skill://mekanism/mekanism-ore-processing/SKILL.md"));
        // 载具技能只看 Simulated 接没接上：光装机械动力不列，装了 Simulated 才列。
        assertFalse(withCreate.catalog().stream().anyMatch(skill -> skill.name().equals("create-vehicles")));
        SkillLibrary withSimulated = new SkillLibrary(mod -> mod.equals("simulated"));
        assertEquals(5, withSimulated.catalog().size(), "装了 Simulated 多出载具一篇");
        assertTrue(withSimulated.catalog().stream().anyMatch(skill -> skill.name().equals("create-vehicles")));
    }

    @Test
    void readReturnsTheSkillFileVerbatim() {
        SkillLibrary library = new SkillLibrary(mod -> true);
        JsonObject result = library.read("skill://survival/survival-opening/SKILL.md");
        JsonArray contents = result.getAsJsonArray("contents");
        assertEquals(1, contents.size());
        JsonObject content = contents.get(0).getAsJsonObject();
        assertEquals("skill://survival/survival-opening/SKILL.md", content.get("uri").getAsString());
        assertEquals("text/markdown", content.get("mimeType").getAsString());
        String text = content.get("text").getAsString();
        assertTrue(text.startsWith("---\nname: survival-opening\n"));
        assertTrue(text.contains("# 生存开局"));
    }

    @Test
    void listCarriesTheDescriptionAndMimeType() {
        SkillLibrary library = new SkillLibrary(mod -> true);
        JsonArray resources = library.list().getAsJsonArray("resources");
        JsonObject opening = null;
        for (var element : resources) {
            JsonObject resource = element.getAsJsonObject();
            if (resource.get("name").getAsString().equals("survival-opening")) {
                opening = resource;
            }
        }
        assertTrue(opening != null, "目录里有生存开局");
        assertTrue(opening.get("description").getAsString().startsWith("生存开局："));
        assertEquals("text/markdown", opening.get("mimeType").getAsString());
    }

    @Test
    void rejectsAnUnknownAddress() {
        SkillLibrary library = new SkillLibrary(mod -> true);
        assertThrows(IllegalArgumentException.class,
                () -> library.read("skill://survival/no-such-skill/SKILL.md"));
        assertThrows(IllegalArgumentException.class, () -> library.read("maicraft://knowledge/index"));
    }
}
