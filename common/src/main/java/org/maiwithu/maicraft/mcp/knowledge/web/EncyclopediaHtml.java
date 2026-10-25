// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * 把条目正文的网页转成按段排好的纯文字：标题一段（前面加 #），一段文字一段，一个列表、一张表格各一段；
 * 表格一行写成一行（| 格 | 格 |），合并单元格的跨度照写，不靠换行猜对应关系。
 * 图片不转文字（合成格子这类图示读不出位置，不猜），只记下这篇有图。
 */
final class EncyclopediaHtml {
    /** 不是正文的部分：脚本样式、导航框、目录、编辑链接、隐藏起来的检索辅助文字。 */
    private static final String NOT_CONTENT = "script,style,link,meta,noscript,form,button,input,select,textarea,nav,footer,"
            + "video,audio,iframe,.mw-editsection,.navbox,.toc,#toc,.mw-empty-elt,[style~=(?i)display\\s*:\\s*none]";
    private static final Set<String> BLOCKS = Set.of("div", "section", "article", "figure", "figcaption", "center",
            "aside", "main", "header", "details", "summary", "blockquote");

    /** 转出来的正文：按段的文字，以及原文里有没有图片。 */
    record Text(List<String> paragraphs, boolean hadImages) {}

    private EncyclopediaHtml() {}

    /**
     * @param container 正文所在的元素（会先复制一份再删东西，不改原文档）
     * @param alsoDrop  这个站特有的非正文部分（选择器），没有时给空串
     */
    static Text paragraphs(Element container, String alsoDrop) {
        Element clean = container.clone();
        clean.select(NOT_CONTENT).remove();
        if (!alsoDrop.isBlank()) clean.select(alsoDrop).remove();
        boolean images = !clean.select("img,svg,canvas,picture").isEmpty();
        List<String> out = new ArrayList<>();
        StringBuilder loose = new StringBuilder();
        walk(clean, out, loose);
        flush(loose, out);
        return new Text(List.copyOf(out), images);
    }

    // 逐个看子节点：段落、标题、列表、表格各成一段；容器往里走；散在容器里的文字攒成一段。
    private static void walk(Element parent, List<String> out, StringBuilder loose) {
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode text) { loose.append(collapse(text.getWholeText())); continue; }
            if (!(child instanceof Element element)) continue;
            String tag = element.normalName();
            if (tag.matches("h[1-6]")) {
                flush(loose, out);
                add(out, "#".repeat(tag.charAt(1) - '0') + " " + inline(element).strip());
            } else if (tag.equals("p")) {
                flush(loose, out);
                add(out, inline(element));
            } else if (tag.equals("ul") || tag.equals("ol")) {
                flush(loose, out);
                add(out, list(element, 0));
            } else if (tag.equals("dl")) {
                flush(loose, out);
                add(out, definitions(element));
            } else if (tag.equals("table")) {
                flush(loose, out);
                add(out, table(element));
            } else if (tag.equals("pre")) {
                flush(loose, out);
                add(out, "```\n" + element.wholeText().strip() + "\n```");
            } else if (BLOCKS.contains(tag)) {
                flush(loose, out);
                walk(element, out, loose);
                flush(loose, out);
            } else {
                loose.append(inline(element));
            }
        }
    }

    // 一个元素里的文字连成一行：换行只留 <br> 的，图片不出字。
    private static String inline(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode word) text.append(collapse(word.getWholeText()));
            else if (child instanceof Element inner) {
                String tag = inner.normalName();
                if (tag.equals("br")) text.append('\n');
                else if (!tag.equals("img")) text.append(inline(inner));
            }
        }
        return text.toString();
    }

    // 列表一项一行，套着的子列表往里缩两格；有序列表写序号。
    private static String list(Element list, int depth) {
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (Element item : list.children()) {
            if (!item.normalName().equals("li")) continue;
            number++;
            StringBuilder line = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node child : item.childNodes()) {
                if (child instanceof Element inner && (inner.normalName().equals("ul") || inner.normalName().equals("ol"))) nested.add(inner);
                else if (child instanceof TextNode word) line.append(collapse(word.getWholeText()));
                else if (child instanceof Element inner) line.append(inline(inner));
            }
            text.append("  ".repeat(depth)).append(list.normalName().equals("ol") ? number + ". " : "- ")
                    .append(line.toString().replaceAll("\\s+", " ").strip()).append('\n');
            for (Element inner : nested) text.append(list(inner, depth + 1));
        }
        return text.toString();
    }

    private static String definitions(Element list) {
        StringBuilder text = new StringBuilder();
        for (Element item : list.children()) {
            String line = inline(item).replaceAll("\\s+", " ").strip();
            if (line.isEmpty()) continue;
            text.append(item.normalName().equals("dt") ? line + "：" : "  " + line).append('\n');
        }
        return text.toString();
    }

    // 表格一行一行写；表头与格子不分，跨行跨列的格写明跨度。表格里再套的表格压成格子里的文字。
    private static String table(Element table) {
        StringBuilder text = new StringBuilder();
        Element caption = table.selectFirst("> caption");
        if (caption != null) text.append(inline(caption).strip()).append('\n');
        for (Element row : table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr")) {
            StringBuilder line = new StringBuilder();
            for (Element cell : row.children()) {
                if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) continue;
                line.append(" | ").append(inline(cell).replaceAll("\\s+", " ").strip());
                for (String span : List.of("rowspan", "colspan"))
                    if (cell.hasAttr(span)) line.append(" [").append(span).append('=').append(cell.attr(span)).append(']');
            }
            if (!line.isEmpty()) text.append(line.substring(1)).append(" |\n");
        }
        return text.toString();
    }

    private static void flush(StringBuilder loose, List<String> out) {
        add(out, loose.toString());
        loose.setLength(0);
    }

    private static void add(List<String> out, String paragraph) {
        String text = paragraph.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (!text.isEmpty() && !text.matches("#+")) out.add(text);
    }

    private static String collapse(String text) { return text.replaceAll("\\s+", " "); }
}
