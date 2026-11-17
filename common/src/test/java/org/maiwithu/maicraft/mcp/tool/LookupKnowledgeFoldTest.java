// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

/**
 * lookup 查资料的超长回包装填：正文超过一页预算时按行装一页，folded 记账、照 offset 翻页读得回全文，
 * 特长的单行也装得下；目录与搜索命中装不下时折起记账并指路收窄；小资料照旧整篇给，不加翻页字段。
 */
class LookupKnowledgeFoldTest {

    private static final String BIG_DOC = "maicraft://knowledge/ponder/big/1";

    @Test
    void 正文超过一页预算时按行装一页并给翻页路标() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 2500; i++) text.append("第").append(i).append("行：一二三四五六七八九十甲乙丙丁\n");
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(textSource(text.toString()))));

        JsonObject reply = dispatcher.call("lookup", args(BIG_DOC, null));

        assertTrue(reply.get("ok").getAsBoolean(), "装填后不再整包 too_large：" + reply);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS, "装填后的回包在单次上限之内");
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals(BIG_DOC, data.get("id").getAsString());
        assertEquals(0, data.get("offset").getAsInt());
        int total = data.get("total").getAsInt();
        assertTrue(total > 1, "全文不止一页：" + total);
        int next = data.get("next_offset").getAsInt();
        assertTrue(next > 0 && next < total);
        JsonObject folded = data.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/text", folded.get("path").getAsString());
        assertEquals("text", folded.get("type").getAsString());
        assertTrue(folded.get("chars").getAsInt() > 0, "folded 记下没给出的字数");
        assertTrue(reply.getAsJsonArray("notes").get(0).getAsString().contains("下一页"), reply::toString);
        JsonObject nextCall = reply.getAsJsonObject("next").getAsJsonObject("arguments");
        assertEquals("knowledge", nextCall.get("topic").getAsString());
        assertEquals(BIG_DOC, nextCall.get("id").getAsString());
        assertEquals(next, nextCall.get("offset").getAsInt(), "next 照抄就是下一页");
    }

    @Test
    void 照offset翻页拼得回全文() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 2500; i++) text.append("第").append(i).append("行：一二三四五六七八九十甲乙丙丁\n");
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(textSource(text.toString()))));

        StringBuilder read = new StringBuilder();
        JsonObject arguments = args(BIG_DOC, null);
        int pages = 0;
        while (true) {
            JsonObject reply = dispatcher.call("lookup", arguments);
            assertTrue(reply.get("ok").getAsBoolean(), "第 " + (pages + 1) + " 页：" + reply);
            assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS);
            read.append(reply.getAsJsonObject("data").get("text").getAsString());
            pages++;
            if (!reply.getAsJsonObject("data").has("next_offset")) break;
            arguments = new JsonObject();
            arguments.addProperty("topic", "knowledge");
            arguments.addProperty("id", BIG_DOC);
            arguments.addProperty("offset", reply.getAsJsonObject("data").get("next_offset").getAsInt());
        }
        assertEquals(text.toString(), read.toString(), "整篇正文翻页读得回，一字不少");
        assertTrue(pages > 1, "长文分成了 " + pages + " 页");
    }

    @Test
    void 特长的单行也按预算切开装得下() {
        // 整篇没有换行：按预算切成几段行号，照样在预算内翻页，不劈开一个字。
        String singleLine = "甲乙丙丁戊己庚辛壬癸".repeat(9000);
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(textSource(singleLine))));

        JsonObject reply = dispatcher.call("lookup", args(BIG_DOC, null));

        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS);
        JsonObject data = reply.getAsJsonObject("data");
        assertTrue(data.get("total").getAsInt() > 1, "特长单行切成了 " + data.get("total").getAsInt() + " 段");
        assertTrue(data.getAsJsonArray("folded").get(0).getAsJsonObject().get("chars").getAsInt() > 0);
    }

    @Test
    void offset超出总行数按参数错回() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 50; i++) text.append("第").append(i).append("行\n");
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(textSource(text.toString()))));

        JsonObject over = dispatcher.call("lookup", args(BIG_DOC, 999999));
        assertEquals("invalid_parameter", over.getAsJsonObject("error").get("code").getAsString());
        assertEquals("offset", over.getAsJsonObject("error").getAsJsonArray("fields").get(0)
                .getAsJsonObject().get("field").getAsString());
        assertTrue(over.getAsJsonObject("error").getAsJsonArray("fields").get(0).getAsJsonObject().get("message")
                .getAsString().contains("行"), over::toString);

        JsonObject negative = dispatcher.call("lookup", args(BIG_DOC, -1));
        assertEquals("invalid_parameter", negative.getAsJsonObject("error").get("code").getAsString());

        // 不带 id 的 knowledge 不接 offset。
        JsonObject noId = dispatcher.call("lookup", JsonParser.parseString(
                "{\"topic\": \"knowledge\", \"offset\": 1}").getAsJsonObject());
        assertEquals("invalid_parameter", noId.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void 小资料照旧整篇给不加翻页字段() {
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(textSource("只有一行的小资料\n"))));

        JsonObject reply = dispatcher.call("lookup", args(BIG_DOC, null));

        assertTrue(reply.get("ok").getAsBoolean());
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals(3, data.keySet().size(), "整篇装得下时只有 id、title、text：" + data.keySet());
        assertEquals("只有一行的小资料\n", data.get("text").getAsString());
        assertFalse(data.has("folded"));
        assertFalse(reply.has("next"));
    }

    @Test
    void 目录装不下时折起记账并指路收窄() {
        // 一个条目全放进目录的来源（多个这样的来源加起来也会超预算）：目录逐行装到预算内，装不下的折起记账。
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(sceneSource(600, 600))));

        JsonObject reply = dispatcher.call("lookup", JsonParser.parseString("{\"topic\": \"knowledge\"}").getAsJsonObject());

        assertTrue(reply.get("ok").getAsBoolean(), "目录装填后不再整包 too_large：" + reply);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS);
        JsonObject data = reply.getAsJsonObject("data");
        int listed = data.getAsJsonArray("knowledge").size();
        assertTrue(listed < 600, "只列出装得下的 " + listed + " 篇");
        assertTrue(data.has("sources"), "目录照旧附各来源现状");
        JsonObject folded = data.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/knowledge", folded.get("path").getAsString());
        assertEquals("list", folded.get("type").getAsString());
        assertEquals(600 + 16 - listed, folded.get("items").getAsInt(), "随包常识也在目录里，一起算进折起的行数");
        assertTrue(reply.getAsJsonArray("notes").get(0).getAsString().contains("资料目录共"), reply::toString);
    }

    @Test
    void 搜索命中装不下时折起记账并指路收窄() {
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(sceneSource(600, 5))));

        JsonObject reply = dispatcher.call("lookup", JsonParser.parseString(
                "{\"topic\": \"knowledge\", \"query\": \"磁石\"}").getAsJsonObject());

        assertTrue(reply.get("ok").getAsBoolean(), "搜索装填后不再整包 too_large：" + reply);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS);
        JsonObject data = reply.getAsJsonObject("data");
        int listed = data.getAsJsonArray("knowledge").size();
        assertTrue(listed < 600, "只列出装得下的 " + listed + " 篇");
        JsonObject folded = data.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/knowledge", folded.get("path").getAsString());
        assertEquals(600 - listed, folded.get("items").getAsInt());
        assertTrue(reply.getAsJsonArray("notes").get(0).getAsString().contains("命中 600 篇"), reply::toString);
        // next 照旧建议读排在最前的那篇。
        assertEquals("maicraft://knowledge/ponder/scene/0",
                reply.getAsJsonObject("next").getAsJsonObject("arguments").get("id").getAsString());
    }

    private static JsonObject args(String id, Integer offset) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("topic", "knowledge");
        arguments.addProperty("id", id);
        if (offset != null) arguments.addProperty("offset", offset);
        return arguments;
    }

    /** 一篇长正文的思索来源：读得到 BIG_DOC，目录与来源现状照常给。 */
    private static KnowledgeSource textSource(String text) {
        return new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) {
                return uri.equals(BIG_DOC)
                        ? new KnowledgeDocument(uri, "scene", "大篇资料", "思索场景", text)
                        : null;
            }
            @Override public String status() { return "思索：可用，1 个场景"; }
        };
    }

    /** 一个条目很多的思索来源：搜索候选给 scene/0..scene/N-1（搜索词带「磁石」），目录放 catalogRows 行索引。 */
    private static KnowledgeSource sceneSource(int count, int catalogRows) {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new KnowledgeDocument.Entry("maicraft://knowledge/ponder/scene/" + i, "scene." + i,
                    "磁石场景" + i, "思索场景 · 磁石", "磁石 场景"));
        }
        List<KnowledgeDocument.Entry> catalog = List.copyOf(entries);
        return new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() {
                return catalog.subList(0, catalogRows);
            }

            @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
                return query.contains("磁石") ? catalog : List.of();
            }

            @Override public KnowledgeDocument read(String uri) { return null; }
            @Override public String status() { return "思索：可用，" + count + " 个场景"; }
        };
    }

    private static LookupTool lookup(KnowledgeSource source) {
        ClientThread thread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(null);
            }
        };
        return new LookupTool(new AbilityRegistry(new TaskFactories(), modId -> false),
                new KnowledgeLibrary(List.of(source)), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                new Items(), new Ids(), thread, List::of, WebKnowledgeService.off());
    }

    /** 替身物品表：什么都查不到，这一组测试只走随包与登记来源。 */
    private static final class Items implements ReadsItemDescriptions {
        @Override public Optional<ReadsItemDescriptions.ItemDescription> describe(String itemId) {
            return Optional.empty();
        }

        @Override public List<String> search(List<String> terms) {
            return List.of();
        }

        @Override public String nameOf(String itemId) {
            return itemId;
        }
    }

    /** 替身注册表：没有注册 ID。 */
    private static final class Ids implements ReadsRegisteredIds {
        @Override public List<String> ids(IdKind kind) { return List.of(); }

        @Override public List<String> tags(IdKind kind) { return List.of(); }
    }
}
