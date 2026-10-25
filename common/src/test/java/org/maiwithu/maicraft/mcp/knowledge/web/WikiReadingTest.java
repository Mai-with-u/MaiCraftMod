// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.mcp.knowledge.web.PageReplay.COMPARATOR;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 正文怎么读、标题怎么排：Wiki 与 MC百科的正文转成按段的文字，站点地图里的标题按离查询多近排序。网页用回放替身。 */
class WikiReadingTest {
    private static final Clock FIXED = Clock.fixed(PageReplay.FETCHED_AT, ZoneOffset.UTC);

    @Test
    void wikiArticlesBecomeParagraphsWithSectionsAndChromeDropped() throws Exception {
        WikiArticle article = new MinecraftWikiSource(PageReplay.chineseWiki()).read(COMPARATOR, soon());
        assertEquals("红石比较器", article.title());
        assertEquals("Minecraft Wiki（中文）", article.site());
        List<String> paragraphs = article.paragraphs();
        assertTrue(paragraphs.contains("## 获取"), "标题单独一段");
        assertTrue(paragraphs.contains("- 红石火把\n- 下界石英"), "列表一项一行");
        assertTrue(paragraphs.contains("| 材料 | 配方 |\n| 红石火把 + 下界石英 | 合成 [colspan=2] |"), "表格一行一行写，跨度照写");
        assertTrue(paragraphs.contains("| 硬度 | 0 |"), "信息框里的数据表留着");
        assertTrue(paragraphs.stream().noneMatch(text -> text.contains("[[|]]") || text.contains("导航框")
                        || text.contains("编辑") || text.contains("Java版")),
                "隐藏的检索文字、导航框、编辑链接、信息框图片区的图注都不算正文");
        assertTrue(article.hadImages(), "记下原文有图");
        assertEquals(new WikiArticle.Section("## 获取", paragraphs.indexOf("## 获取")), article.sections().getFirst());
        assertEquals("红石比较器是一种红石元件。", article.summary(), "开头一句取开头那段话，不取信息框里的字");
    }

    @Test
    void aPageWithoutTheArticleLayoutIsUnreadableNotEmpty() {
        PageReplay replay = PageReplay.chineseWiki();
        replay.pages.put(COMPARATOR.toString(), "<html><body>请完成验证</body></html>");
        WebKnowledgeException failed = assertThrows(WebKnowledgeException.class,
                () -> new MinecraftWikiSource(replay).read(COMPARATOR, soon()));
        assertEquals(WebKnowledgeException.Kind.UNREADABLE, failed.kind(), "验证页或改版不当成一篇空条目");
    }

    @Test
    void mcmodPagesAreReadByAddressWithTheSiteNameTakenOffTheTitle() throws Exception {
        PageReplay replay = new PageReplay();
        URI item = WebAccess.article("https://www.mcmod.cn/item/330128.html#recipe");
        replay.pages.put(item.toString(), "<html><head><title>应力 - 机械动力 (Create) - MC百科|最大的Minecraft中文MOD百科</title></head>"
                + "<body><div class=\"item-content\"><p>应力是机械动力对扭矩的简化。</p><p>应力不能储存。</p></div>"
                + "<table class=\"item-info-table\"><tr><td>资料分类</td><td>动力系统</td></tr></table></body></html>");
        WikiArticle article = new McmodSource(replay).read(item, soon());
        assertEquals("应力 - 机械动力 (Create)", article.title());
        assertEquals("MC百科", article.site());
        assertEquals(List.of("应力是机械动力对扭矩的简化。", "应力不能储存。", "| 资料分类 | 动力系统 |"), article.paragraphs(),
                "物品页的正文后面接上资料表");
        assertEquals("应力是机械动力对扭矩的简化。", article.summary());
    }

    @Test
    void theClosestTitleComesFirstAndDuplicatesCountOnce() throws Exception {
        WikiTitleIndex.Search found = new WikiTitleIndex(PageReplay.chineseWiki(), FIXED).search(WebAccess.WIKI_ZH, "比较器", 3, soon());
        assertEquals(2, found.total(), "红石比较器登记了两次只算一条，加上它的子页");
        assertEquals("红石比较器", found.hits().getFirst().title(), "短的标题排前");
        assertEquals(COMPARATOR, found.hits().getFirst().uri());
    }

    @Test
    void aQuestionFindsTheTitleItContains() throws Exception {
        WikiTitleIndex.Search found = new WikiTitleIndex(PageReplay.chineseWiki(), FIXED).search(WebAccess.WIKI_ZH, "红石比较器怎么用", 3, soon());
        assertEquals("红石比较器", found.hits().getFirst().title(), "问句里含着的最长标题排最前");
        assertFalse(found.hits().stream().anyMatch(hit -> hit.title().equals("石头")));
    }

    @Test
    void englishSitemapsListTitlesWithSpaces() throws Exception {
        PageReplay replay = new PageReplay();
        replay.pages.put("https://minecraft.wiki/images/sitemaps/index.xml",
                "<sitemapindex><sitemap><loc>https://minecraft.wiki/images/sitemaps/NS_0-0.xml.gz</loc></sitemap></sitemapindex>");
        replay.pages.put("https://minecraft.wiki/images/sitemaps/NS_0-0.xml.gz",
                "<urlset><url><loc>https://minecraft.wiki/w/Redstone_Comparator</loc></url>"
                        + "<url><loc>https://minecraft.wiki/w/Special:Search</loc></url></urlset>");
        WikiTitleIndex.Search found = new WikiTitleIndex(replay, FIXED).search(WebAccess.WIKI_EN, "comparator", 3, soon());
        assertEquals(1, found.total(), "带冒号的特殊页不进索引");
        assertEquals("Redstone Comparator", found.hits().getFirst().title(), "下划线换回空格");
    }

    private static long soon() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }
}
