// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeNotReady;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

/**
 * lookup 查配方与资料：配方页按类别分组、写工作站与原始定义；用途页分"当原料"和"当工作站"两部分，每条一行；
 * id 加 query 按词过滤，只留产出或原料沾词的；一段命中太多只给总数与缩窄建议；
 * 按名字找物品 ID；参数组合写错一次报全；角色不在世界里时配方回答 not_in_world，资料退到随包常识并说明。
 */
class LookupRecipeTest {

    private static final String ALLOY = "create:andesite_alloy";
    private static final String MIXER = "create:mechanical_mixer";

    @Test
    void 配方页按类别分组_写工作站与原始定义() {
        JsonObject data = ok(lookup(true).call(args("recipe", ALLOY, null, null)));

        assertEquals("安山合金", data.get("name").getAsString());
        assertEquals("emi", data.get("read_from").getAsString());
        assertEquals(1, data.get("recipe_count").getAsInt());
        JsonObject mixing = data.getAsJsonArray("categories").get(0).getAsJsonObject();
        assertEquals("create:mixing", mixing.get("category").getAsString());
        assertEquals("搅拌", mixing.get("category_name").getAsString());
        assertEquals(MIXER, mixing.getAsJsonArray("workstations").get(0).getAsJsonObject().get("item").getAsString());
        JsonObject recipe = mixing.getAsJsonArray("recipes").get(0).getAsJsonObject();
        JsonArray inputs = recipe.getAsJsonArray("inputs");
        assertEquals("c:nuggets/iron", inputs.get(1).getAsJsonObject().get("tag").getAsString(), "标签原料写标签");
        assertEquals(0.5, recipe.getAsJsonArray("outputs").get(1).getAsJsonObject().get("chance").getAsDouble(), "标了几率才写几率");
        assertFalse(recipe.getAsJsonArray("outputs").get(0).getAsJsonObject().has("chance"));
        assertEquals("none", recipe.getAsJsonObject("definition").get("heat_requirement").getAsString());
    }

    @Test
    void 用途页分当原料与当工作站两部分_每条一行() {
        JsonObject data = ok(lookup(true).call(args("recipe", MIXER, null, true)));

        JsonObject station = data.getAsJsonObject("as_workstation");
        assertEquals(1, station.get("recipe_count").getAsInt());
        String line = station.getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("recipes").get(0).getAsString();
        assertEquals("create:mixing/andesite_alloy：安山合金 ×1、碎石 ×1（50%） ← 安山岩 ×1、铁粒（#c:nuggets/iron）×1", line);
        assertEquals(0, data.getAsJsonObject("as_ingredient").get("recipe_count").getAsInt());
    }

    @Test
    void 按名字找物品ID并建议读第一个的配方页() {
        JsonObject reply = lookup(true).call(args("recipe", null, "安山合金", null));

        JsonObject data = ok(reply);
        assertEquals(ALLOY, data.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(ALLOY, reply.getAsJsonObject("next").getAsJsonObject("arguments").get("id").getAsString());
    }

    @Test
    void 用途页按词过滤_只留产出或原料沾词的配方() {
        JsonObject data = ok(lookup(true, new BuffetViewer()).call(args("recipe", MIXER, "烤肉", true)));

        JsonObject station = data.getAsJsonObject("as_workstation");
        assertEquals(90, station.get("recipe_count").getAsInt(), "120 道里只留沾词的 90 道");
        JsonArray rows = station.getAsJsonArray("categories").get(0).getAsJsonObject().getAsJsonArray("recipes");
        assertEquals(90, rows.size());
        assertTrue(rows.get(0).getAsString().startsWith("create:buffet/roast_1："));
        assertEquals(0, data.getAsJsonObject("as_ingredient").get("recipe_count").getAsInt());
    }

    @Test
    void 过滤后全部落空时给一句没沾边的指路() {
        JsonObject reply = lookup(true, new BuffetViewer()).call(args("recipe", MIXER, "回锅肉", true));

        JsonObject data = ok(reply);
        assertEquals(0, data.getAsJsonObject("as_workstation").get("recipe_count").getAsInt());
        assertEquals(0, data.getAsJsonObject("as_workstation").getAsJsonArray("categories").size());
        assertTrue(notes(reply).stream().anyMatch(note -> note.contains("回锅肉")), reply::toString);
    }

    @Test
    void 配方页也按词过滤_落空时指路() {
        JsonObject hit = lookup(true).call(args("recipe", ALLOY, "铁粒", null));
        assertEquals(1, ok(hit).get("recipe_count").getAsInt(), "原料里有铁粒，留得下");

        JsonObject miss = lookup(true).call(args("recipe", ALLOY, "不沾边的词", null));
        assertEquals(0, ok(miss).get("recipe_count").getAsInt());
        assertTrue(notes(miss).stream().anyMatch(note -> note.contains("不沾边的词")), miss::toString);
    }

    @Test
    void 命中太多的一段只给总数与缩窄建议_另一段照常() {
        JsonObject data = ok(lookup(true, new BuffetViewer()).call(args("recipe", MIXER, null, true)));

        JsonObject station = data.getAsJsonObject("as_workstation");
        assertEquals(120, station.get("recipe_count").getAsInt());
        assertFalse(station.has("categories"), "超过上限的段不逐条倾倒");
        assertTrue(station.get("note").getAsString().contains("120"), station::toString);

        // 当原料那段只有 0 条，照常给 categories，不被大段连坐。
        assertTrue(data.getAsJsonObject("as_ingredient").has("categories"));
    }

    @Test
    void 配方页命中太多时只给总数与缩窄建议() {
        JsonObject data = ok(lookup(true, new BuffetViewer()).call(args("recipe", ALLOY, null, null)));

        assertEquals(120, data.get("recipe_count").getAsInt());
        assertFalse(data.has("categories"), "超过上限的配方页不逐条倾倒");
        assertTrue(data.get("note").getAsString().contains("120"), data::toString);
    }

    @Test
    void 配方页条目太大时按类别装到预算内_装不下的整组折起记账() {
        // 条数没超但定义特别长：装得下的小类照常列出，装不下的大类整组折进 folded，回包不再整包退回 too_large。
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(true, new HugeViewer())));
        JsonObject reply = dispatcher.call("lookup", args("recipe", ALLOY, null, null));

        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS, "装填后的回包在单次上限之内");
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals(55, data.get("recipe_count").getAsInt());
        assertEquals(5, data.get("recipe_listed").getAsInt());
        assertEquals(1, data.getAsJsonArray("categories").size(), "只有装得下的小类照常列出");
        assertEquals(5, data.getAsJsonArray("categories").get(0).getAsJsonObject().getAsJsonArray("recipes").size());
        JsonObject folded = data.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/categories", folded.get("path").getAsString());
        assertEquals("list", folded.get("type").getAsString());
        assertEquals(50, folded.get("items").getAsInt());
        assertTrue(folded.get("chars").getAsInt() > 0);
        assertTrue(data.get("note").getAsString().contains("命中 55 条"), data::toString);
    }

    @Test
    void 用途页两段共用一份预算_装不下的段整段折起记账() {
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(lookup(true, new HugeViewer())));
        JsonObject reply = dispatcher.call("lookup", args("recipe", MIXER, null, true));

        assertTrue(reply.get("ok").getAsBoolean(), "装填后不再整包 too_large：" + reply);
        assertTrue(ToolCatalog.GSON.toJson(reply).length() <= ToolDispatcher.MAX_REPLY_CHARS);
        JsonObject data = reply.getAsJsonObject("data");
        JsonObject ingredient = data.getAsJsonObject("as_ingredient");
        assertEquals(55, ingredient.get("recipe_count").getAsInt());
        assertEquals(5, ingredient.get("recipe_listed").getAsInt());
        JsonObject folded = ingredient.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/as_ingredient/categories", folded.get("path").getAsString());
        assertEquals(50, folded.get("items").getAsInt());
        // 前一段把预算用掉后，后一段整段折起：条数照写，一行不列（categories 空着，与一段零条命中的样子一致）。
        JsonObject station = data.getAsJsonObject("as_workstation");
        assertEquals(40, station.get("recipe_count").getAsInt());
        assertEquals(0, station.get("recipe_listed").getAsInt());
        assertEquals(0, station.getAsJsonArray("categories").size(), "预算装满后的段一行不列");
        assertEquals(40, station.getAsJsonArray("folded").get(0).getAsJsonObject().get("items").getAsInt());
        assertTrue(station.get("note").getAsString().contains("40"), station::toString);
    }

    @Test
    void 参数写错一次报全_没有的物品回答unknown_id() {
        JsonObject missing = lookup(true).call(args("recipe", null, null, null));
        assertEquals(ErrorCode.INVALID_PARAMETER.wireName(), missing.getAsJsonObject("error").get("code").getAsString());

        JsonObject wrongTopic = lookup(true).call(args("knowledge", null, null, true));
        assertEquals(ErrorCode.INVALID_PARAMETER.wireName(), wrongTopic.getAsJsonObject("error").get("code").getAsString());

        // id 和 query 一起给只有查配方放行，别的 topic 照旧拒。
        JsonObject both = lookup(true).call(args("knowledge", "food", "钻石", null));
        assertEquals(ErrorCode.INVALID_PARAMETER.wireName(), both.getAsJsonObject("error").get("code").getAsString());

        JsonObject unknown = lookup(true).call(args("recipe", "create:no_such_thing", null, null));
        assertEquals(ErrorCode.UNKNOWN_ID.wireName(), unknown.getAsJsonObject("error").get("code").getAsString());

        // 写岔一个字母：报错带上本实例里相近的物品 ID。
        JsonObject typo = lookup(true).call(args("recipe", "create:mechanical_mixr", null, null));
        assertTrue(typo.getAsJsonObject("error").get("message").getAsString()
                .startsWith("物品 create:mechanical_mixr 不存在；你是不是要 create:mechanical_mixer；"), typo::toString);
    }

    @Test
    void 不在世界里时配方回答not_in_world_资料退到随包常识() {
        JsonObject recipe = lookup(false).call(args("recipe", ALLOY, null, null));
        assertEquals(ErrorCode.NOT_IN_WORLD.wireName(), recipe.getAsJsonObject("error").get("code").getAsString());

        JsonObject catalog = lookup(false).call(args("knowledge", null, null, null));
        assertTrue(catalog.get("ok").getAsBoolean());
        assertTrue(catalog.getAsJsonArray("notes").get(0).getAsString().startsWith("角色不在世界里"));
    }

    @Test
    void 资料目录附各来源现状() {
        JsonObject data = ok(lookup(true).call(args("knowledge", null, null, null)));

        assertEquals(List.of("思索：可用，1 个场景"),
                data.getAsJsonArray("sources").asList().stream().map(element -> element.getAsString()).toList());
    }

    @Test
    void 资料一刻准备不完时下一刻接着读同一篇() {
        int[] reads = {0};
        KnowledgeSource slow = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) {
                if (!uri.equals("maicraft://knowledge/ponder/create/mechanical_mixer/1")) return null;
                if (++reads[0] < 3) throw new KnowledgeNotReady("还在回放，已到第 " + reads[0] * 100 + " 刻");
                return new KnowledgeDocument(uri, "scene", "场景", "思索场景", "正文");
            }
            @Override public String status() { return ""; }
        };
        ClientThread thread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(null);
            }
        };
        LookupTool lookup = new LookupTool(new AbilityRegistry(new TaskFactories(), modId -> false), new KnowledgeLibrary(List.of(slow)),
                new RecipeLookup(List.of(), new MixingViewer()), new Items(), new Ids(), thread, List::of, WebKnowledgeService.off());

        JsonObject data = ok(lookup.call(args("knowledge", "maicraft://knowledge/ponder/create/mechanical_mixer/1", null, null)));

        assertEquals("正文", data.get("text").getAsString());
        assertEquals(3, reads[0], "读了三次：前两次还在准备");
    }

    private static JsonObject ok(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static List<String> notes(JsonObject reply) {
        return reply.getAsJsonArray("notes").asList().stream().map(element -> element.getAsString()).toList();
    }

    private static JsonObject args(String topic, String id, String query, Boolean uses) {
        JsonObject args = new JsonObject();
        args.addProperty("topic", topic);
        if (id != null) args.addProperty("id", id);
        if (query != null) args.addProperty("query", query);
        if (uses != null) args.addProperty("uses", uses);
        return args;
    }

    private static LookupTool lookup(boolean inWorld) {
        return lookup(inWorld, new MixingViewer());
    }

    private static LookupTool lookup(boolean inWorld, RecipeViewer viewer) {
        ClientThread thread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                if (!inWorld) throw new NotInWorld();
                return work.apply(null);
            }
        };
        KnowledgeSource ponder = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) { return null; }
            @Override public String status() { return "思索：可用，1 个场景"; }
        };
        return new LookupTool(new AbilityRegistry(new TaskFactories(), modId -> false), new KnowledgeLibrary(List.of(ponder)),
                new RecipeLookup(List.of(viewer), viewer), new Items(), new Ids(), thread, List::of,
                WebKnowledgeService.off());
    }

    /** 替身物品表：安山合金与动力搅拌器。 */
    private static final class Items implements ReadsItemDescriptions {
        @Override public Optional<ItemDescription> describe(String itemId) {
            if (!itemId.equals(ALLOY) && !itemId.equals(MIXER)) return Optional.empty();
            return Optional.of(new ItemDescription(itemId, nameOf(itemId), null, List.of(), List.of(), "", List.of(), List.of()));
        }

        @Override public List<String> search(List<String> terms) {
            return terms.stream().allMatch((ALLOY + " 安山合金")::contains) ? List.of(ALLOY) : List.of();
        }

        @Override public String nameOf(String itemId) {
            return itemId.equals(ALLOY) ? "安山合金" : "动力搅拌器";
        }
    }

    /** 替身注册表：只注册了安山合金与动力搅拌器两件物品。 */
    private static final class Ids implements ReadsRegisteredIds {
        @Override public List<String> ids(IdKind kind) {
            return kind == IdKind.ITEM ? List.of(ALLOY, MIXER) : List.of();
        }

        @Override public List<String> tags(IdKind kind) {
            return List.of();
        }
    }

    /** 替身配方查看器：只有一条搅拌配方，动力搅拌器是它的工作站。 */
    private static final class MixingViewer implements RecipeViewer {
        @Override public String name() { return "emi"; }
        @Override public boolean importsOtherViewers() { return true; }
        @Override public Readiness readiness() { return Readiness.yes(); }
        @Override public List<ShownRecipe> making(String itemId) { return itemId.equals(ALLOY) ? List.of(mixing()) : List.of(); }
        @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { return itemId.equals(MIXER) ? List.of(mixing()) : List.of(); }

        private static ShownRecipe mixing() {
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "create:mixing");
            definition.addProperty("heat_requirement", "none");
            ShownIngredient nugget = new ShownIngredient("c:nuggets/iron", List.of(ShownStack.item("minecraft:iron_nugget", "铁粒", 1)), 1);
            return new ShownRecipe("create:mixing/andesite_alloy", "create:mixing", "搅拌",
                    List.of(ShownStack.item(MIXER, "动力搅拌器", 1), ShownStack.item("create:basin", "工作盆", 1)),
                    List.of(ShownIngredient.of(ShownStack.item("minecraft:andesite", "安山岩", 1)), nugget), List.of(),
                    List.of(ShownStack.item(ALLOY, "安山合金", 1),
                            new ShownStack(ShownStack.Kind.ITEM, "minecraft:gravel", "碎石", 1, 0.5)), definition);
        }
    }

    /** 替身配方查看器：动力搅拌器当工作站时给一大桌配方（90 道烤肉、30 道甜汤），做过滤与段级降级的料。 */
    private static final class BuffetViewer implements RecipeViewer {
        @Override public String name() { return "emi"; }
        @Override public boolean importsOtherViewers() { return true; }
        @Override public Readiness readiness() { return Readiness.yes(); }
        @Override public List<ShownRecipe> making(String itemId) { return itemId.equals(ALLOY) ? buffet() : List.of(); }
        @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { return itemId.equals(MIXER) ? buffet() : List.of(); }

        private static List<ShownRecipe> buffet() {
            List<ShownRecipe> recipes = new ArrayList<>();
            for (int i = 1; i <= 90; i++) {
                recipes.add(dish("create:buffet/roast_" + i, "烤肉 " + i, "minecraft:cooked_porkchop", "烤肉"));
            }
            for (int i = 1; i <= 30; i++) {
                recipes.add(dish("create:buffet/soup_" + i, "甜汤 " + i, "minecraft:sweet_berries", "甜汤"));
            }
            return List.copyOf(recipes);
        }

        // 一行配方：搅拌器当工作站、一样原料（名字带菜系词，过滤匹配用）、一样产出。
        private static ShownRecipe dish(String id, String outputName, String outputId, String inputName) {
            return new ShownRecipe(id, "create:mixing", "搅拌",
                    List.of(ShownStack.item(MIXER, "动力搅拌器", 1)),
                    List.of(ShownIngredient.of(ShownStack.item("minecraft:apple", inputName, 1))), List.of(),
                    List.of(ShownStack.item(outputId, outputName, 1)), null);
        }
    }

    /** 替身配方查看器：条数不多但条目特别大（原料列几十样替代品、原始定义很长），做装填折叠的料。 */
    private static final class HugeViewer implements RecipeViewer {
        private static final String LONG_NAME = "模组加的长名字物品一二三四五六七八九十甲乙丙丁戊己庚辛壬癸";

        @Override public String name() { return "emi"; }
        @Override public boolean importsOtherViewers() { return true; }
        @Override public Readiness readiness() { return Readiness.yes(); }
        // 当原料：一小类 5 条小行装得下，一大类 50 条大行整组折起。
        @Override public List<ShownRecipe> using(String itemId) {
            List<ShownRecipe> recipes = new ArrayList<>();
            for (int i = 1; i <= 5; i++) recipes.add(dish("create:small/dish_" + i, "小菜 " + i, "create:small", false, null));
            for (int i = 1; i <= 50; i++) recipes.add(dish("create:wide/dish_" + i, "大菜 " + i, "create:wide", true, null));
            return List.copyOf(recipes);
        }

        // 当工作站：一大类 40 条大行，前一段用掉预算后这里整段折起。
        @Override public List<ShownRecipe> atWorkstation(String itemId) {
            List<ShownRecipe> recipes = new ArrayList<>();
            for (int i = 1; i <= 40; i++) recipes.add(dish("create:bench/roast_" + i, "烤肉 " + i, "create:bench", true, null));
            return List.copyOf(recipes);
        }

        // 配方页：一小类 5 条定义短的，一大类 50 条定义特别长的。
        @Override public List<ShownRecipe> making(String itemId) {
            List<ShownRecipe> recipes = new ArrayList<>();
            for (int i = 1; i <= 5; i++) recipes.add(dish("create:small/dish_" + i, "小菜 " + i, "create:small", false, null));
            for (int i = 1; i <= 50; i++) recipes.add(dish("create:long/dish_" + i, "长谱 " + i, "create:long", false, longDefinition()));
            return List.copyOf(recipes);
        }

        // 一条配方：wide 时原料一格写几十样替代品（用途页的一行因此特别长），definition 非空时带很长的原始定义（配方页的一条因此特别大）。
        private static ShownRecipe dish(String id, String outputName, String category, boolean wide, JsonObject definition) {
            ShownIngredient input = wide ? new ShownIngredient(null, manyOptions(), 1)
                    : ShownIngredient.of(ShownStack.item("minecraft:apple", "苹果", 1));
            return new ShownRecipe(id, category, "流水席",
                    List.of(ShownStack.item(MIXER, "动力搅拌器", 1)), List.of(input), List.of(),
                    List.of(ShownStack.item(id + "/out", outputName, 1)), definition);
        }

        private static List<ShownStack> manyOptions() {
            List<ShownStack> options = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                options.add(ShownStack.item("minecraft:alt_" + i, LONG_NAME + i, 1));
            }
            return List.copyOf(options);
        }

        private static JsonObject longDefinition() {
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "create:sequenced");
            definition.addProperty("pattern", "格".repeat(1200));
            return definition;
        }
    }
}
