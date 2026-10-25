// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 读 Minecraft Wiki 的一篇条目：标题取页面大标题，正文取条目内容区，不调用站点禁止自动抓取的 API。 */
final class MinecraftWikiSource {
    /**
     * Minecraft Wiki 特有的非正文：要靠脚本才显示的互动小工具（没有脚本时只剩一句"正在加载"），
     * 信息框里的图片区（只有图和"Java版""基岩版"这类图注）。
     */
    private static final String SITE_CHROME = ".mcw-calc,.infobox-imagearea";

    private final WebFetch fetch;

    MinecraftWikiSource(WebFetch fetch) { this.fetch = fetch; }

    WikiArticle read(URI article, long deadline) throws IOException, InterruptedException {
        WebFetch.Page page = fetch.get(article, deadline);
        Document document = Jsoup.parse(page.body(), page.uri().toString());
        Element content = document.selectFirst("#mw-content-text .mw-parser-output");
        Element heading = document.selectFirst("#firstHeading");
        if (content == null || heading == null)
            throw new WebKnowledgeException(WebKnowledgeException.Kind.UNREADABLE,
                    "页面取回来了，但找不到条目正文（站点可能改版，或者回的是验证页）");
        EncyclopediaHtml.Text text = EncyclopediaHtml.paragraphs(content, SITE_CHROME);
        if (text.paragraphs().isEmpty())
            throw new WebKnowledgeException(WebKnowledgeException.Kind.UNREADABLE, "这一篇的正文是空的");
        return new WikiArticle(WebAccess.siteName(article), article, heading.text(), lead(content),
                text.paragraphs(), text.hadImages(), page.fetchedAt());
    }

    // 条目的开头那段话：内容区里第一个有字的直接段落，信息框和提示框里的字不算。
    private static String lead(Element content) {
        for (Element paragraph : content.select("> p")) {
            String text = paragraph.text().strip();
            if (!text.isEmpty()) return text;
        }
        return "";
    }
}
