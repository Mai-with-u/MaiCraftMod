// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * lookup(topic=wiki) 的回答正文：搜索结果一条一行（标题、地址、开头一句），读一篇按段分页。
 * 每次都带出处（站名与地址）和本实例的 Minecraft 版本，网页写的版本对不上时一眼看得出。
 */
public final class WikiPages {
    /** 一页大约装多少字：和按路径读折起来的证据一样，至少装一段，一段再长也整段给。 */
    public static final int PAGE_CHARS = 16000;

    private WikiPages() {}

    /** 搜索结果：站名、对得上的总条数、排在最前的几条。 */
    public static JsonObject search(String query, WebKnowledgeService.SearchAnswer answer, String minecraftVersion) {
        JsonObject data = new JsonObject();
        data.addProperty("site", answer.site());
        data.addProperty("query", query);
        data.addProperty("total", answer.total());
        JsonArray results = new JsonArray();
        for (WebKnowledgeService.Found found : answer.results()) {
            JsonObject row = new JsonObject();
            row.addProperty("title", found.title());
            row.addProperty("url", found.url().toString());
            if (!found.summary().isEmpty()) row.addProperty("summary", found.summary());
            results.add(row);
        }
        data.add("results", results);
        data.addProperty("minecraft_version", minecraftVersion);
        return data;
    }

    /** 读一篇的一页：回答正文（从 offset 那一段起装到约 {@link #PAGE_CHARS} 字），以及下一页从第几段起（没有下一页时为 null）。 */
    public record Page(JsonObject content, Integer nextOffset) {}

    /**
     * 一篇条目的一页：出处、标题、全篇的章节目录（每节从第几段起，想直接看"合成"就照它翻）、这一页的段落、
     * 总段数；还有下一页时给 next_offset。offset 要在 0 到总段数减一之间，由调用方先核对。
     */
    public static Page article(WikiArticle article, int offset, String minecraftVersion) {
        JsonObject data = new JsonObject();
        data.addProperty("site", article.site());
        data.addProperty("url", article.url().toString());
        data.addProperty("title", article.title());
        data.addProperty("minecraft_version", minecraftVersion);
        JsonArray sections = new JsonArray();
        for (WikiArticle.Section section : article.sections()) {
            JsonObject row = new JsonObject();
            row.addProperty("heading", section.heading());
            row.addProperty("offset", section.offset());
            sections.add(row);
        }
        data.add("sections", sections);
        JsonArray paragraphs = new JsonArray();
        int total = article.paragraphs().size();
        int end = offset;
        int chars = 0;
        // 至少装一段；装下一段会超过一页时停在这里，下一页从它开始。
        while (end < total && (end == offset || chars + article.paragraphs().get(end).length() <= PAGE_CHARS)) {
            chars += article.paragraphs().get(end).length();
            paragraphs.add(article.paragraphs().get(end));
            end++;
        }
        data.add("paragraphs", paragraphs);
        data.addProperty("offset", offset);
        data.addProperty("total", total);
        Integer next = end < total ? end : null;
        if (next != null) data.addProperty("next_offset", next);
        return new Page(data, next);
    }
}
