// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.web.WebFetch;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

/**
 * lookup(topic=wiki) 的回答：关着照实说关着，搜到了 next 指向第一篇，长文按段分页，白名单外的地址不联网就拒，
 * 超时与没有这一篇各回各的错误码。网页用替身回放，不碰真实站点。
 */
class LookupWikiTest {
    private static final String ZH = "https://zh.minecraft.wiki";
    private static final String COMPARATOR = ZH + "/w/红石比较器";

    @Test
    void switchedOffSaysItIsOffInsteadOfNotConnected() {
        JsonObject reply = new LookupTool(registry(), KnowledgeLibrary.offline()).call(args("{\"topic\":\"wiki\",\"query\":\"石头\"}"));
        assertEquals("unavailable", code(reply));
        String message = reply.getAsJsonObject("error").get("message").getAsString();
        assertTrue(message.contains("关着"), message);
        assertFalse(message.contains("接上"), "关着不是还没接上");
    }

    @Test
    void searchListsTheClosestTitlesAndNextReadsTheFirst() {
        ReplayFetch fetch = ReplayFetch.chineseWiki(1);
        JsonObject reply = tool(fetch).call(args("{\"topic\":\"wiki\",\"query\":\"比较器\"}"));
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals("Minecraft Wiki（中文）", data.get("site").getAsString());
        JsonObject first = data.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("红石比较器", first.get("title").getAsString());
        assertEquals(COMPARATOR, first.get("url").getAsString(), "地址照中文写，照抄就能读");
        assertEquals("红石比较器是一种红石元件。", first.get("summary").getAsString());
        assertEquals("1.21.1", data.get("minecraft_version").getAsString(), "带上本实例的版本，网页写的版本对不上时看得出");
        JsonObject next = reply.getAsJsonObject("next").getAsJsonObject("arguments");
        assertEquals(COMPARATOR, next.get("url").getAsString(), "next 照抄就是读第一篇");
    }

    @Test
    void longArticlesArePagedByParagraphAndNextFetchesTheRest() {
        // 40 段、每段 1000 字：一页装 16 段，next 照抄读下一页，到最后一页不再给 next。
        ReplayFetch fetch = ReplayFetch.chineseWiki(40);
        LookupTool lookup = tool(fetch);
        JsonObject firstPage = lookup.call(args("{\"topic\":\"wiki\",\"url\":\"" + COMPARATOR + "#获取\"}"));
        JsonObject data = firstPage.getAsJsonObject("data");
        int total = data.get("total").getAsInt();
        int shown = data.getAsJsonArray("paragraphs").size();
        assertTrue(shown < total, "长文不一次塞完");
        assertEquals(shown, data.get("next_offset").getAsInt());
        assertEquals("## 获取", data.getAsJsonArray("sections").get(0).getAsJsonObject().get("heading").getAsString());
        int read = shown;
        JsonObject arguments = firstPage.getAsJsonObject("next").getAsJsonObject("arguments");
        while (true) {
            JsonObject page = lookup.call(arguments);
            read += page.getAsJsonObject("data").getAsJsonArray("paragraphs").size();
            if (!page.has("next")) break;
            arguments = page.getAsJsonObject("next").getAsJsonObject("arguments");
        }
        assertEquals(total, read, "翻完一段不少");
        assertEquals(1, fetch.calls(COMPARATOR), "翻页读的是缓存，不再联网");
    }

    @Test
    void addressesOutsideTheAllowedSitesAreRefusedBeforeGoingOnline() {
        ReplayFetch fetch = ReplayFetch.chineseWiki(1);
        JsonObject reply = tool(fetch).call(args("{\"topic\":\"wiki\",\"url\":\"https://example.com/w/Stone\"}"));
        assertEquals("invalid_parameter", code(reply));
        assertEquals("url", reply.getAsJsonObject("error").getAsJsonArray("fields").get(0).getAsJsonObject().get("field").getAsString());
        assertTrue(fetch.requested.isEmpty(), "白名单外的地址一个请求也不发");
    }

    @Test
    void aTimeoutAnswersUnavailableAndPointsToTheLocalSources() {
        ReplayFetch fetch = ReplayFetch.chineseWiki(1);
        fetch.failure = new WebKnowledgeException(WebKnowledgeException.Kind.TIMEOUT, "到了这次查询的期限还没从 zh.minecraft.wiki 读完");
        JsonObject reply = tool(fetch).call(args("{\"topic\":\"wiki\",\"url\":\"" + COMPARATOR + "\"}"));
        assertEquals("unavailable", code(reply));
        String message = reply.getAsJsonObject("error").get("message").getAsString();
        assertTrue(message.contains("期限") && message.contains("lookup(topic=knowledge)"), message);
    }

    @Test
    void aMissingArticleIsAnUnknownId() {
        JsonObject reply = tool(ReplayFetch.chineseWiki(1)).call(args("{\"topic\":\"wiki\",\"url\":\"" + ZH + "/w/不存在的条目\"}"));
        assertEquals("unknown_id", code(reply));
    }

    @Test
    void misplacedOrOutOfRangeOffsetsAreParameterErrors() {
        LookupTool lookup = tool(ReplayFetch.chineseWiki(1));
        assertEquals("invalid_parameter", code(lookup.call(args("{\"topic\":\"wiki\",\"query\":\"石头\",\"offset\":3}"))));
        assertEquals("invalid_parameter", code(lookup.call(args("{\"topic\":\"knowledge\",\"offset\":3}"))));
        assertEquals("invalid_parameter", code(lookup.call(args("{\"topic\":\"wiki\",\"url\":\"" + COMPARATOR + "\",\"offset\":999}"))));
        assertEquals("invalid_parameter", code(lookup.call(args("{\"topic\":\"wiki\"}"))), "query 与 url 都没给");
    }

    private static LookupTool tool(ReplayFetch fetch) {
        return new LookupTool(registry(), KnowledgeLibrary.offline(), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                NO_ITEMS, NO_IDS, NOT_IN_WORLD, List::of, new WebKnowledgeService(fetch, true, "1.21.1"));
    }

    private static AbilityRegistry registry() {
        return new AbilityRegistry(new TaskFactories(), modId -> false);
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String code(JsonObject reply) {
        return reply.get("ok").getAsBoolean() ? "ok" : reply.getAsJsonObject("error").get("code").getAsString();
    }

    private static final ReadsItemDescriptions NO_ITEMS = new ReadsItemDescriptions() {
        @Override public Optional<ItemDescription> describe(String itemId) { return Optional.empty(); }
        @Override public List<String> search(List<String> terms) { return List.of(); }
        @Override public String nameOf(String itemId) { return itemId; }
    };

    private static final ReadsRegisteredIds NO_IDS = new ReadsRegisteredIds() {
        @Override public List<String> ids(IdKind kind) { return List.of(); }
        @Override public List<String> tags(IdKind kind) { return List.of(); }
    };

    private static final ClientThread NOT_IN_WORLD = new ClientThread() {
        @Override public <T> T call(Function<TickContext, T> work) { throw new NotInWorld(); }
    };

    /** 回放中文站的取页替身：站点地图里有红石比较器，正文段数可调；可以设成一律失败。 */
    private static final class ReplayFetch implements WebFetch {
        final Map<String, String> pages = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        WebKnowledgeException failure;

        static ReplayFetch chineseWiki(int extraParagraphs) {
            ReplayFetch fetch = new ReplayFetch();
            fetch.pages.put(ZH + "/images/sitemaps/index.xml",
                    "<sitemapindex><sitemap><loc>" + ZH + "/images/sitemaps/NS_0-0.xml</loc></sitemap></sitemapindex>");
            fetch.pages.put(ZH + "/images/sitemaps/NS_0-0.xml", "<urlset><url><loc>" + COMPARATOR + "</loc></url></urlset>");
            StringBuilder html = new StringBuilder("<html><body><h1 id=\"firstHeading\">红石比较器</h1>"
                    + "<div id=\"mw-content-text\"><div class=\"mw-parser-output\"><p>红石比较器是一种红石元件。它能比较信号。</p>"
                    + "<h2>获取</h2>");
            for (int i = 0; i < extraParagraphs; i++) html.append("<p>").append(String.valueOf((char) ('甲' + i % 10)).repeat(1000)).append("</p>");
            fetch.pages.put(COMPARATOR, html.append("</div></div></body></html>").toString());
            return fetch;
        }

        int calls(String uri) {
            return (int) requested.stream().filter(uri::equals).count();
        }

        @Override public Page get(URI uri, long deadlineNanos) throws IOException {
            requested.add(uri.toString());
            if (failure != null) throw failure;
            String html = pages.get(uri.toString());
            if (html == null) throw new WebKnowledgeException(WebKnowledgeException.Kind.NOT_FOUND, "站点上没有这一页（HTTP 404）");
            return new Page(uri, html, Instant.now());
        }
    }
}
