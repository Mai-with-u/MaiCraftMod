// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 读 MC百科的一页：物品、模组或教程。它不让程序用站内搜索，所以只能按地址读。 */
final class McmodSource {
    /** 页面标题后面统一挂着站名，去掉后剩"条目名 - 模组名"。 */
    private static final String TITLE_SUFFIX = " - MC百科";

    private final WebFetch fetch;

    McmodSource(WebFetch fetch) { this.fetch = fetch; }

    WikiArticle read(URI uri, long deadline) throws IOException, InterruptedException {
        WebFetch.Page page = fetch.get(uri, deadline);
        Document document = Jsoup.parse(page.body(), page.uri().toString());
        String selector = uri.getPath().startsWith("/class/") ? ".class-text .text-area.common-text"
                : uri.getPath().startsWith("/post/") ? ".post-content" : ".item-content";
        var contents = document.select(selector);
        if (contents.isEmpty())
            throw new WebKnowledgeException(WebKnowledgeException.Kind.UNREADABLE,
                    "页面取回来了，但找不到 MC百科 的正文（站点可能改版，或者回的是验证页）");
        Element article = new Element("article");
        article.setBaseUri(page.uri().toString());
        // 物品页的正文后面接上它的资料表（分类、合成表格），这些是表格不是图片，照样转成文字。
        for (Element part : contents) article.appendChild(part.clone());
        if (uri.getPath().startsWith("/item/"))
            for (Element part : document.select(".item-info-table")) article.appendChild(part.clone());
        EncyclopediaHtml.Text text = EncyclopediaHtml.paragraphs(article, "");
        if (text.paragraphs().isEmpty())
            throw new WebKnowledgeException(WebKnowledgeException.Kind.UNREADABLE, "这一页的正文是空的");
        Element first = article.selectFirst("p:matches(\\S)");
        return new WikiArticle(WebAccess.siteName(uri), uri, title(document), first == null ? "" : first.text().strip(),
                text.paragraphs(), text.hadImages(), page.fetchedAt());
    }

    private static String title(Document document) {
        String title = document.title();
        int suffix = title.indexOf(TITLE_SUFFIX);
        return (suffix > 0 ? title.substring(0, suffix) : title).strip();
    }
}
