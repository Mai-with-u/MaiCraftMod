// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 按地址回放固定网页的取页替身，记下每个地址取了几次；没登记的地址按站点上没有这一页回答。
 * 取页时刻固定在 {@link #FETCHED_AT}，测试里用同一刻的时钟，缓存与索引都还新鲜。
 */
final class PageReplay implements WebFetch {
    static final String ZH = "https://zh.minecraft.wiki";
    static final URI COMPARATOR = WebAccess.article(ZH + "/w/红石比较器");
    static final Instant FETCHED_AT = Instant.parse("2026-10-26T00:00:00Z");

    final Map<String, String> pages = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();

    /** 中文站：站点地图里有红石比较器（重复登记了两次）、它的一个子页和石头，红石比较器有一篇正文。 */
    static PageReplay chineseWiki() {
        PageReplay replay = new PageReplay();
        replay.pages.put(ZH + "/images/sitemaps/index.xml",
                "<sitemapindex><sitemap><loc>" + ZH + "/images/sitemaps/NS_0-0.xml</loc></sitemap>"
                        + "<sitemap><loc>" + ZH + "/images/sitemaps/NS_2-0.xml</loc></sitemap></sitemapindex>");
        replay.pages.put(ZH + "/images/sitemaps/NS_0-0.xml", "<urlset>"
                + "<url><loc>" + ZH + "/w/%E7%BA%A2%E7%9F%B3%E6%AF%94%E8%BE%83%E5%99%A8</loc></url>"
                + "<url><loc>" + ZH + "/w/%E7%BA%A2%E7%9F%B3%E6%AF%94%E8%BE%83%E5%99%A8</loc></url>"
                + "<url><loc>" + ZH + "/w/红石比较器/DV</loc></url>"
                + "<url><loc>" + ZH + "/w/石头</loc></url></urlset>");
        replay.pages.put(COMPARATOR.toString(), comparatorPage());
        return replay;
    }

    /** 红石比较器一篇：信息框、开头那段话、隐藏的检索文字、带编辑链接的小标题、列表、带跨列与图片的表格、导航框。 */
    static String comparatorPage() {
        return "<html><head><title>红石比较器</title></head><body><h1 id=\"firstHeading\">红石比较器</h1>"
                + "<div id=\"mw-content-text\"><div class=\"mw-parser-output\">"
                + "<div class=\"infobox\"><div class=\"infobox-imagearea\"><p>Java版</p></div>"
                + "<table><tr><th>硬度</th><td>0</td></tr></table></div>"
                + "<p>红石比较器是一种红石元件。它能比较信号。</p>"
                + "<span class=\"searchaux\" style=\"display:none\">[[|]]</span>"
                + "<div class=\"mw-heading mw-heading2\"><h2>获取</h2><span class=\"mw-editsection\">[ 编辑 ]</span></div>"
                + "<ul><li>红石火把</li><li>下界石英</li></ul>"
                + "<table class=\"wikitable\"><tr><th>材料</th><th>配方</th></tr>"
                + "<tr><td>红石火把 + 下界石英</td><td colspan=\"2\">合成<img alt=\"图\" src=\"/x.png\"></td></tr></table>"
                + "<table class=\"navbox\"><tr><td>导航框</td></tr></table>"
                + "</div></div></body></html>";
    }

    int calls(URI uri) {
        return calls.getOrDefault(uri.toString(), 0);
    }

    @Override public Page get(URI uri, long deadlineNanos) throws IOException {
        calls.merge(uri.toString(), 1, Integer::sum);
        String body = pages.get(uri.toString());
        if (body == null) throw new WebKnowledgeException(WebKnowledgeException.Kind.NOT_FOUND, "站点上没有这一页（HTTP 404）");
        return new Page(uri, body, FETCHED_AT);
    }
}
