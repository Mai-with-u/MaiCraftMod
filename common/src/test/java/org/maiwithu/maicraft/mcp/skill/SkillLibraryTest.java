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
 * 随包技能的加载与按模组筛选：索引里的十四篇都在，按模组筛，正文原样读出。
 *
 * <p>技能文件在 main 资源里，测试直接读真文件；这样名字、目录、frontmatter 一起被检查，
 * 不会出现测试替身全绿、真实资源却少一篇的情况。
 */
class SkillLibraryTest {

    @Test
    void loadsEveryIndexedSkillWithItsAddress() {
        SkillLibrary library = new SkillLibrary(mod -> true);
        List<Skill> catalog = library.catalog();
        assertEquals(14, catalog.size(), "索引里的十四篇都加载");
        Set<String> uris = catalog.stream().map(Skill::uri).collect(Collectors.toSet());
        assertTrue(uris.contains("skill://survival/survival-opening/SKILL.md"));
        assertTrue(uris.contains("skill://create/create-machine-workflow/SKILL.md"));
        assertTrue(uris.contains("skill://mekanism/mekanism-getting-started/SKILL.md"));
        assertTrue(uris.contains("skill://progression/progression-ftb-quests/SKILL.md"));
        assertTrue(uris.contains("skill://building/building-workflow/SKILL.md"));
        assertTrue(uris.contains("skill://create/create-vehicles/SKILL.md"));
        assertTrue(uris.contains("skill://touhoulittlemaid/maid-getting-started/SKILL.md"));
        assertTrue(uris.contains("skill://social/player-body-language/SKILL.md"));
        assertTrue(uris.contains("skill://ae2/ae2-getting-started/SKILL.md"));
        assertTrue(uris.contains("skill://ae2/ae2-network-and-autocraft/SKILL.md"));
        assertTrue(uris.stream().allMatch(uri -> uri.endsWith("/SKILL.md")), "地址都以 SKILL.md 结尾");
    }

    @Test
    void hidesSkillsWhoseModsAreMissing() {
        SkillLibrary vanillaOnly = new SkillLibrary(mod -> false);
        assertEquals(5, vanillaOnly.catalog().size(), "没装联动模组时只剩五篇不依赖模组的技能");
        assertFalse(vanillaOnly.catalog().stream().anyMatch(skill -> skill.name().equals("create-machine-workflow")));
        assertFalse(vanillaOnly.catalog().stream().anyMatch(skill -> skill.name().equals("maid-getting-started")),
                "没装车万女仆时女仆入门不上架");
        assertFalse(vanillaOnly.catalog().stream().anyMatch(skill -> skill.name().equals("ae2-getting-started")),
                "没装 AE2 时起步技能不上架");
        assertFalse(vanillaOnly.catalog().stream().anyMatch(skill -> skill.name().equals("ae2-network-and-autocraft")),
                "没装 AE2 时网络与自动合成技能不上架");
        assertThrows(IllegalArgumentException.class,
                () -> vanillaOnly.read("skill://create/create-machine-workflow/SKILL.md"));
        assertThrows(IllegalArgumentException.class,
                () -> vanillaOnly.read("skill://ae2/ae2-getting-started/SKILL.md"));
        assertThrows(IllegalArgumentException.class,
                () -> vanillaOnly.read("skill://ae2/ae2-network-and-autocraft/SKILL.md"));
        assertEquals(5, vanillaOnly.list().getAsJsonArray("resources").size(), "目录也不列不发布的技能");

        SkillLibrary withCreate = new SkillLibrary(mod -> mod.equals("create"));
        assertEquals(7, withCreate.catalog().size(), "装了 Create 多出两篇");
        assertTrue(withCreate.catalog().stream().anyMatch(skill -> skill.name().equals("create-power-network")));
        assertThrows(IllegalArgumentException.class,
                () -> withCreate.read("skill://mekanism/mekanism-ore-processing/SKILL.md"));
        // 载具技能只看 Simulated 接没接上：光装机械动力不列，装了 Simulated 才列。
        assertFalse(withCreate.catalog().stream().anyMatch(skill -> skill.name().equals("create-vehicles")));
        SkillLibrary withSimulated = new SkillLibrary(mod -> mod.equals("simulated"));
        assertEquals(6, withSimulated.catalog().size(), "装了 Simulated 多出载具一篇");
        assertTrue(withSimulated.catalog().stream().anyMatch(skill -> skill.name().equals("create-vehicles")));

        // 装了 AE2 时上架两篇 AE2 玩法技能，且均可原样读出。
        SkillLibrary withAe2 = new SkillLibrary(mod -> mod.equals("ae2"));
        assertEquals(7, withAe2.catalog().size(), "装了 AE2 多出两篇玩法技能");
        assertTrue(withAe2.catalog().stream().anyMatch(skill -> skill.name().equals("ae2-getting-started")));
        assertTrue(withAe2.catalog().stream().anyMatch(skill -> skill.name().equals("ae2-network-and-autocraft")));
        String gettingStarted = withAe2.read("skill://ae2/ae2-getting-started/SKILL.md")
                .getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(gettingStarted.startsWith("---\nname: ae2-getting-started\n"));
        assertTrue(gettingStarted.contains("maicraft://knowledge/machine/ae2/daily-machines"));
        String networkAutocraft = withAe2.read("skill://ae2/ae2-network-and-autocraft/SKILL.md")
                .getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(networkAutocraft.startsWith("---\nname: ae2-network-and-autocraft\n"));
        assertTrue(networkAutocraft.contains("maicraft://knowledge/machine/appmek/index"));
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
