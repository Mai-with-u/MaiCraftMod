// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 读回来的一篇网页条目：出处（站名与地址）、标题、开头那段话、按段排好的正文，以及原文里有没有图片。
 * 段落按原文顺序，标题段以 # 开头；按段分页时从第几段读起就是段落的下标。
 * 开头那段话单独取：正文最前面常是信息框里的零碎格子，拿来当摘要会答非所问。
 */
public record WikiArticle(String site, URI url, String title, String lead, List<String> paragraphs, boolean hadImages,
                          Instant fetchedAt) {
    /** 开头一句最多多少字：够看出这一篇讲的是什么。 */
    private static final int SUMMARY_CHARS = 200;

    public WikiArticle {
        paragraphs = List.copyOf(paragraphs);
    }

    /** 文章里的一节：标题，以及它在第几段，按这个段号翻页就从这一节读起。 */
    public record Section(String heading, int offset) {}

    /** 全篇的章节目录：每个标题段一行，层级用标题前的 # 个数表示。 */
    public List<Section> sections() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).startsWith("#")) sections.add(new Section(paragraphs.get(i), i));
        }
        return sections;
    }

    /** 开头那段话的第一句，太长时截到够看出讲什么为止并加省略号；这一篇没有开头那段话时为空串。 */
    public String summary() {
        String sentence = firstSentence(lead.replace('\n', ' '));
        return sentence.length() <= SUMMARY_CHARS ? sentence : sentence.substring(0, SUMMARY_CHARS) + "…";
    }

    /** 全篇多少字：缓存按它算占了多少。 */
    int chars() {
        return paragraphs.stream().mapToInt(String::length).sum();
    }

    // 中文按句号，英文按句点加空格断句；一句也断不出来就整段当一句。
    private static String firstSentence(String paragraph) {
        int chinese = paragraph.indexOf('。');
        int english = paragraph.indexOf(". ");
        int end = chinese < 0 ? english : english < 0 ? chinese : Math.min(chinese, english);
        return (end < 0 ? paragraph : paragraph.substring(0, end + 1)).strip();
    }
}
