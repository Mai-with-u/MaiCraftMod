// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.maiwithu.maicraft.mcp.knowledge.web.WebAccess;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;
import org.maiwithu.maicraft.mcp.knowledge.web.WikiArticle;
import org.maiwithu.maicraft.mcp.knowledge.web.WikiPages;

/**
 * lookup(topic=wiki)：联网查 Minecraft Wiki 与 MC百科。query 按标题搜，url 读一篇（按段分页，offset 翻页）。
 * 联网在 MCP 请求线程上做，不交给客户端线程，等网页的几秒不卡游戏。没查成按原因回错误码，并说接着怎么办。
 */
final class WikiTopic {
    /** 网页查不到时先用什么：随包资料与模组自带的资料都在本机，比网页更贴合这一局。 */
    private static final String LOCAL_FIRST = "随包资料、思索（Ponder）、配方（JEI、EMI）读得到的先用它们："
            + "lookup(topic=knowledge) 查资料与思索，lookup(topic=recipe) 查配方";
    private static final int MAX_QUERY_CHARS = 100;

    private final WebKnowledgeService web;

    WikiTopic(WebKnowledgeService web) {
        this.web = Objects.requireNonNull(web, "web");
    }

    /** 核对 topic=wiki 的参数：query 与 url 二选一，不用 id；url 要是能读的条目地址；offset 只配 url。 */
    void check(RequestCheck check, String id, String query, String url, Integer offset) {
        if (id != null) check.error("id", "topic=wiki 不用 id：按标题搜用 query，读一篇用 url", null);
        if (query == null && url == null) {
            check.error("query", "topic=wiki 要给 query（按标题搜）或 url（读一篇）", "例如 query=\"红石比较器\"");
        }
        if (query != null && url != null) check.error("url", "query 和 url 只能给一个", "先用 query 搜，再照抄结果里的 url 读");
        if (query != null && (query.isBlank() || query.length() > MAX_QUERY_CHARS)) {
            check.error("query", "query 要有 1 到 " + MAX_QUERY_CHARS + " 个字", "条目名或物品名，例如 \"红石比较器\"");
        }
        if (url != null) {
            try {
                WebAccess.article(url);
            } catch (IllegalArgumentException refused) {
                check.error("url", refused.getMessage(), "例如 https://zh.minecraft.wiki/w/红石比较器");
            }
        }
        if (offset != null && url == null) check.error("offset", "offset 只在读一篇（给 url）时用", "照抄上一页的 next_offset");
        if (offset != null && offset < 0) check.error("offset", "offset 不能是负数", "0 或更大的整数");
    }

    /** 参数核对过以后回答：关着就照实说关着；搜索给最相关的几条，读一篇给一页。 */
    JsonObject answer(String query, String url, Integer offset, List<String> checkNotes) {
        if (!web.enabled()) {
            return ToolReply.error(ErrorCode.UNAVAILABLE,
                    "联网查 Wiki 关着（启动参数 -Dmaicraft.lookup.wiki=false 关掉的）；" + LOCAL_FIRST);
        }
        List<String> notes = new ArrayList<>(checkNotes);
        try {
            return query != null ? search(query.strip(), notes) : read(WebAccess.article(url), offset == null ? 0 : offset, notes);
        } catch (WebKnowledgeException failed) {
            return failure(failed);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return ToolReply.error(ErrorCode.UNAVAILABLE, "这次联网查询被中断了；再查一次");
        } catch (IOException unexpected) {
            String reason = unexpected.getMessage() == null ? unexpected.getClass().getSimpleName() : unexpected.getMessage();
            return ToolReply.error(ErrorCode.UNAVAILABLE, "联网查 Wiki 没查成：" + reason + "。" + LOCAL_FIRST);
        }
    }

    // 搜到了就建议读排在最前的那篇（它已经读过一遍，接着读不用再联网）；没搜到建议换个说法。
    private JsonObject search(String query, List<String> notes) throws IOException, InterruptedException {
        WebKnowledgeService.SearchAnswer answer = web.search(query);
        JsonObject data = WikiPages.search(query, answer, web.minecraftVersion());
        if (answer.results().isEmpty()) {
            notes.add(answer.site() + "里没有标题对得上「" + query + "」的条目；换个说法再查：用游戏里的正式名字，去掉\"怎么做\"这类问法，"
                    + (answer.chineseSite() ? "或者换成英文名查英文站" : "或者换成中文名查中文站"));
            return ToolReply.ok(data, notes, null);
        }
        if (answer.total() > answer.results().size()) {
            notes.add("标题对得上的一共 " + answer.total() + " 条，这里是最相关的 " + answer.results().size()
                    + " 条；要找的不在里面就换个更具体的说法");
        }
        answer.unreadSummaries().forEach(line -> notes.add("没读到开头一句：" + line));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("topic", "wiki");
        arguments.addProperty("url", answer.results().getFirst().url().toString());
        return ToolReply.ok(data, notes, ToolReply.next(ToolCatalog.LOOKUP, arguments));
    }

    // 读一篇的一页；还有下一页时 next 照抄就是下一页。offset 超出总段数按参数错回，说清这篇有几段。
    private JsonObject read(URI url, int offset, List<String> notes) throws IOException, InterruptedException {
        WikiArticle article = web.read(url);
        int total = article.paragraphs().size();
        if (offset >= total) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 1 处问题",
                    List.of(new FieldError("offset", "这一篇一共 " + total + " 段，offset 最大是 " + (total - 1), "0 到 " + (total - 1))), null);
        }
        WikiPages.Page page = WikiPages.article(article, offset, web.minecraftVersion());
        if (article.hadImages()) notes.add("原文里的图片（含合成格子这类图示）没有转成文字；配方以 lookup(topic=recipe) 为准");
        JsonObject next = null;
        if (page.nextOffset() != null) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("topic", "wiki");
            arguments.addProperty("url", article.url().toString());
            arguments.addProperty("offset", page.nextOffset());
            next = ToolReply.next(ToolCatalog.LOOKUP, arguments);
        }
        return ToolReply.ok(page.content(), notes, next);
    }

    // 没查成按原因回：忙就稍后再试，没有这一篇按 unknown_id，别的都是这一次外部站点用不了。
    private static JsonObject failure(WebKnowledgeException failed) {
        return switch (failed.kind()) {
            case BUSY -> ToolReply.error(ErrorCode.BUSY, failed.getMessage() + "；稍后再试");
            case NOT_FOUND -> ToolReply.error(ErrorCode.UNKNOWN_ID,
                    "没有这一篇：" + failed.getMessage() + "；先用 query 按标题搜，再照抄结果里的 url");
            default -> ToolReply.error(ErrorCode.UNAVAILABLE, "联网查 Wiki 没查成：" + failed.getMessage()
                    + "。" + LOCAL_FIRST + "；网页过一会儿再查，或换个说法再搜");
        };
    }
}
