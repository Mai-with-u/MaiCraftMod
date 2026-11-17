// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/**
 * lookup(topic=knowledge) 的回答正文：读一篇时整篇装得下就整篇给（与原先一样只有 id、title、text）；
 * 超过一次回包装得下的量时按行装到预算以内，装不下的部分折起来记账（folded），并给下一页的起始行号，
 * 照 offset 翻页读得回全文。目录与搜索清单同样逐行装到预算内，装不下的行折起来记账——
 * 一篇长资料或一份大目录不再把整包退回 too_large 失败。
 */
public final class KnowledgePages {

    /** 一页装多少字（正文与清单同用这一份）：加给信封与说明的余量后，控制在工具分发约 5 万字符的单次上限之内。 */
    static final int MAX_TEXT_CHARS = 38_000;
    /** 预算之外再留的字数：信封、notes、翻页字段与 folded 记账从这里出。 */
    private static final int ENVELOPE_CHARS = 1024;

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private KnowledgePages() {}

    /** 一页的装填结果：回答正文（翻到后半篇时带翻页字段与折叠记账），下一页从第几行起（没有下一页为 null），全文共几行。 */
    public record Page(JsonObject content, Integer nextLine, int totalLines) {}

    /** 清单的装填结果：回答正文（装不下的行折进 folded），列出了几行、折起了几行。 */
    public record Listing(JsonObject content, int listedRows, int foldedRows) {}

    /**
     * 资料目录或搜索结果的一页：每行 id、title、summary，照调用方排好的顺序装到一页预算以内。
     * 搜索的顺序是相关度，目录的顺序是地址；{@code sourceStatuses} 只在目录（不带 query）时给，
     * 附在 sources 一节。装不下的行折进 folded（path 指向 knowledge 一节），总数由调用方写进 notes。
     */
    public static Listing listing(List<KnowledgeDocument.Entry> entries, List<String> sourceStatuses) {
        JsonObject data = new JsonObject();
        JsonArray list = new JsonArray();
        data.add("knowledge", list);
        if (sourceStatuses != null) {
            JsonArray sources = new JsonArray();
            sourceStatuses.forEach(sources::add);
            data.add("sources", sources);
        }
        int usedChars = COMPACT.toJson(data).length() + 256;
        int foldedRows = 0;
        int foldedChars = 0;
        for (KnowledgeDocument.Entry entry : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.uri());
            row.addProperty("title", entry.title());
            row.addProperty("summary", entry.description());
            int rowChars = COMPACT.toJson(row).length() + 1;
            if (foldedRows == 0 && usedChars + rowChars <= MAX_TEXT_CHARS) {
                list.add(row);
                usedChars += rowChars;
            } else {
                foldedRows++;
                foldedChars += rowChars;
            }
        }
        if (foldedRows > 0) {
            data.add("folded", reference("/knowledge", "list", foldedRows, foldedChars));
        }
        return new Listing(data, list.size(), foldedRows);
    }

    /** 全文切成装填行后的行数；offset 的合法范围是 0 到它减一，由调用方先核对。 */
    public static int lines(String text) {
        return chunks(text).size();
    }

    /**
     * 一篇资料的一页：从 {@code fromLine} 行起把正文装到一页预算以内，至少装一行；
     * 装不下下一行时停在这里，下一页从它开始。整篇装得下且从头读时只给 id、title、text，不加翻页字段。
     */
    public static Page document(String uri, String title, String text, int fromLine) {
        List<Chunk> chunks = chunks(text);
        JsonObject content = new JsonObject();
        content.addProperty("id", uri);
        content.addProperty("title", title);
        int budget = MAX_TEXT_CHARS - COMPACT.toJson(content).length() - ENVELOPE_CHARS;
        StringBuilder shown = new StringBuilder();
        int shownChars = 0;
        int end = fromLine;
        while (end < chunks.size() && (end == fromLine || shownChars + chunks.get(end).escapedChars() <= budget)) {
            Chunk chunk = chunks.get(end);
            shown.append(chunk.text());
            shownChars += chunk.escapedChars();
            end++;
        }
        content.addProperty("text", shown.toString());
        Integer next = end < chunks.size() ? end : null;
        if (fromLine > 0 || next != null) {
            content.addProperty("offset", fromLine);
            content.addProperty("total", chunks.size());
            if (next != null) {
                content.addProperty("next_offset", next);
                int omittedChars = 0;
                for (int i = end; i < chunks.size(); i++) omittedChars += chunks.get(i).escapedChars();
                content.add("folded", reference("/text", "text", null, omittedChars));
            }
        }
        return new Page(content, next, chunks.size());
    }

    /** 折起来的一块：路径、形状、几项（一段文字没有几项，给 null 就不写）、多少字；与目标结果里的 folded 同一格式，这里是记账占位，读法写在 note 里（翻页或收窄）。 */
    private static JsonArray reference(String path, String type, Integer items, int chars) {
        JsonArray folded = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("path", path);
        block.addProperty("type", type);
        if (items != null) block.addProperty("items", items);
        block.addProperty("chars", chars);
        folded.add(block);
        return folded;
    }

    /** 正文的一行：原文里的字（两处换行之间的文字，含行尾换行）；特长的行是按预算切开的一段。 */
    private record Chunk(String text) {
        /** 这一行写成 JSON 字符串的字数：换行、引号这类字符合计转义开销，与分发的量法一致。 */
        int escapedChars() {
            int count = 2;
            for (int i = 0; i < text.length(); i++) {
                count += switch (text.charAt(i)) {
                    case '"', '\\', '\n', '\r', '\t', '\b', '\f' -> 2;
                    default -> text.charAt(i) < 0x20 ? 6 : 1;
                };
            }
            return count;
        }
    }

    // 全文切成装填行：按换行分；一行超过一页预算时再按预算切成几段（段边界退开代理对，不把一个字劈成两半），
    // 保证任何一行都装得进一页，再长的资料也能在预算内翻页读完。
    private static List<Chunk> chunks(String text) {
        List<Chunk> chunks = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline + 1;
            int chunkStart = lineStart;
            while (chunkStart < lineEnd) {
                int chunkEnd = Math.min(chunkStart + MAX_TEXT_CHARS, lineEnd);
                if (chunkEnd < lineEnd && Character.isHighSurrogate(text.charAt(chunkEnd - 1))) chunkEnd--;
                chunks.add(new Chunk(text.substring(chunkStart, chunkEnd)));
                chunkStart = chunkEnd;
            }
            lineStart = lineEnd;
        }
        return chunks;
    }
}
