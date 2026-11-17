// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * lookup(topic=recipe) 的正文：配方页（怎么做出它）与用途页（能拿它做什么、它当工作站能做什么）。
 *
 * <p>配方页一条不省，每条带原料、催化剂、产出与原始定义；用途页可能上百条，每条压成一行（配方 ID、产出、原料），
 * 不带原始定义，要看某一条的细节查它产出物的配方页。两种页都按配方类别分组，写明这一类在哪做。
 * 给了过滤词就只留产出或原料沾词的配方；一段超过一次看得下的条数时只给总数与缩窄建议，不逐条倾倒。
 * 条数没超但条目太大（一行的原料写了几十样替代品、原始定义特别长）时，按配方类别整组装到回包装得下为止，
 * 装不下的组折起来记账（folded），回包整体不再顶到工具分发的单次上限。
 */
public final class RecipePages {
    private RecipePages() {}

    /** 一段逐条列出的上限：超过时只给总数与缩窄建议；工作站配方成千条，全量倒出来会把上下文撑爆。 */
    static final int SECTION_LIMIT = 100;

    /** 一页配方装填的字数预算：加给外层信封与说明的余量后，控制在工具分发约 5 万字符的单次上限之内。 */
    static final int MAX_PAGE_CHARS = 38_000;
    /** 预算之外再留的字数：信封、notes、两段的段头、folded 记账与缩窄建议都从这里出。 */
    private static final int ENVELOPE_CHARS = 1024;

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    /** 配方页：能做出这件物品的全部配方；给了过滤词就只留产出或原料沾词的。 */
    public static JsonObject making(String itemId, String name, RecipeLookup.Answer answer, List<String> terms) {
        JsonObject page = header(itemId, name, answer);
        List<ShownRecipe> recipes = filtered(answer, terms);
        page.addProperty("recipe_count", recipes.size());
        if (recipes.size() > SECTION_LIMIT) {
            page.addProperty("note", tooMany(recipes.size(), terms, ""));
            return page;
        }
        Packed packed = pack(recipes, true, "/categories", budget(page));
        page.add("categories", packed.categories());
        writeFolded(page, packed, terms, "");
        return page;
    }

    /**
     * 用途页：它当原料或催化剂的配方，与它当工作站的加工。两部分各自写从哪读的（多半是同一个查看器）。
     *
     * @param asIngredient 拿它当原料或催化剂的配方
     * @param asWorkstation 它当工作站的那几类配方
     * @param terms 过滤词（已切好、小写）：每个词都要沾到才算命中；空列表不过滤
     */
    public static JsonObject uses(String itemId, String name, RecipeLookup.Answer asIngredient,
            RecipeLookup.Answer asWorkstation, List<String> terms) {
        JsonObject page = header(itemId, name, asIngredient);
        // 两段共用一份预算：先到的先装，一段把预算装满了，后一段整段折起来记账，回包整体不超上限。
        Room room = budget(page);
        page.add("as_ingredient", section(asIngredient, terms, "/as_ingredient/categories", room));
        page.add("as_workstation", section(asWorkstation, terms, "/as_workstation/categories", room));
        return page;
    }

    // 一页的装填余量：页头写成 JSON 的字数加信封余量，从这里扣。
    private static Room budget(JsonObject pageSoFar) {
        return new Room(MAX_PAGE_CHARS - COMPACT.toJson(pageSoFar).length() - ENVELOPE_CHARS);
    }

    private static JsonObject header(String itemId, String name, RecipeLookup.Answer answer) {
        JsonObject page = new JsonObject();
        page.addProperty("item", itemId);
        page.addProperty("name", name);
        page.addProperty("read_from", answer.readFrom());
        return page;
    }

    private static JsonObject section(RecipeLookup.Answer answer, List<String> terms, String path, Room room) {
        List<ShownRecipe> recipes = filtered(answer, terms);
        JsonObject section = new JsonObject();
        section.addProperty("read_from", answer.readFrom());
        section.addProperty("recipe_count", recipes.size());
        if (recipes.size() > SECTION_LIMIT) {
            section.addProperty("note", tooMany(recipes.size(), terms, "，或直接查某一件产出物的配方页"));
            return section;
        }
        Packed packed = pack(recipes, false, path, room);
        section.add("categories", packed.categories());
        writeFolded(section, packed, terms, "，或直接查某一件产出物的配方页");
        return section;
    }

    /** 装填的结果：折起的块在哪条路径下、列出的类别组，与列出了几条、折起了几条（多少字）。 */
    private record Packed(String path, JsonArray categories, int listedRows, int foldedRows, int foldedChars) {}

    // 命中太多的一段只给总数与缩窄建议：全量清单本来也读不完，与其倾倒不如指个缩小问题的路。
    private static String tooMany(int count, List<String> terms, String extra) {
        String narrow = terms.isEmpty() ? "给 query 一个更具体的词" : "换个更具体的词";
        return "命中 " + count + " 条，一次列不下；" + narrow + "再查" + extra;
    }

    /** 配方页装填后的记账：折起的条数写进 folded（与目标结果里的 folded 同一格式），附列出了几条与缩窄建议。 */
    private static void writeFolded(JsonObject page, Packed packed, List<String> terms, String extra) {
        if (packed.foldedRows() == 0) return;
        page.addProperty("recipe_listed", packed.listedRows());
        page.add("folded", reference(packed.path(), packed.foldedRows(), packed.foldedChars()));
        page.addProperty("note", "命中 " + (packed.listedRows() + packed.foldedRows()) + " 条，一次给不下；这次列出 "
                + packed.listedRows() + " 条，" + (terms.isEmpty() ? "给 query 一个更具体的词" : "换个更具体的词") + "再查" + extra);
    }

    /** 折起来的一块：路径、形状、几项、多少字；与目标结果里的 folded 同一格式，这里是记账占位，按 note 指的路重查。 */
    private static JsonArray reference(String path, int items, int chars) {
        JsonArray folded = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("path", path);
        block.addProperty("type", "list");
        block.addProperty("items", items);
        block.addProperty("chars", chars);
        folded.add(block);
        return folded;
    }

    /**
     * 按配方类别分组装填：装得下的组照原样列出，装不下下一组时从它起整组折进 folded 记账。
     * 只按整组装、不列半组：组里的 recipe_count 与列出的行保持一致，不出现数出来对不上的清单。
     */
    private static Packed pack(List<ShownRecipe> recipes, boolean full, String path, Room room) {
        Map<String, List<ShownRecipe>> grouped = new LinkedHashMap<>();
        for (ShownRecipe recipe : recipes) {
            grouped.computeIfAbsent(recipe.category(), category -> new ArrayList<>()).add(recipe);
        }
        JsonArray categories = new JsonArray();
        int listed = 0;
        int foldedRows = 0;
        int foldedChars = 0;
        for (List<ShownRecipe> group : grouped.values()) {
            JsonObject category = category(group, full);
            int chars = COMPACT.toJson(category).length() + 1;
            if (foldedRows == 0 && room.fits(chars)) {
                room.take(chars);
                categories.add(category);
                listed += group.size();
            } else {
                foldedRows += group.size();
                foldedChars += chars;
            }
        }
        return new Packed(path, categories, listed, foldedRows, foldedChars);
    }

    // 按词过滤：每个词都要在这条配方的配方 ID、产出、原料或催化剂（名字、ID、标签）里出现；词为空不过滤。
    static List<ShownRecipe> filtered(RecipeLookup.Answer answer, List<String> terms) {
        if (terms.isEmpty()) return answer.recipes();
        return answer.recipes().stream().filter(recipe -> matches(recipe, terms)).toList();
    }

    /** 过滤后一条都不剩时为假；调用方用它写一句没沾边的指路。词为空不算过滤。 */
    public static boolean hasMatch(RecipeLookup.Answer answer, List<String> terms) {
        return terms.isEmpty() || answer.recipes().stream().anyMatch(recipe -> matches(recipe, terms));
    }

    private static boolean matches(ShownRecipe recipe, List<String> terms) {
        StringBuilder text = new StringBuilder();
        if (recipe.recipeId() != null) text.append(recipe.recipeId()).append(' ');
        recipe.outputs().forEach(stack -> appendWords(text, stack));
        recipe.inputs().forEach(ingredient -> appendWords(text, ingredient));
        recipe.catalysts().forEach(ingredient -> appendWords(text, ingredient));
        String searchable = text.toString().toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(searchable::contains);
    }

    private static void appendWords(StringBuilder text, ShownStack stack) {
        text.append(stack.id()).append(' ').append(stack.name()).append(' ');
    }

    private static void appendWords(StringBuilder text, ShownIngredient ingredient) {
        if (ingredient.tag() != null) text.append(ingredient.tag()).append(' ');
        ingredient.options().forEach(stack -> appendWords(text, stack));
    }

    // 一组配方：写类别、名字、在哪做与条数，组内照查看器给的顺序逐条列出。
    private static JsonObject category(List<ShownRecipe> group, boolean full) {
        ShownRecipe first = group.getFirst();
        JsonObject category = new JsonObject();
        category.addProperty("category", first.category());
        category.addProperty("category_name", first.categoryName());
        JsonArray workstations = new JsonArray();
        first.workstations().forEach(stack -> workstations.add(stack(stack, stack.amount())));
        category.add("workstations", workstations);
        category.addProperty("recipe_count", group.size());
        JsonArray rows = new JsonArray();
        for (ShownRecipe recipe : group) {
            if (full) rows.add(recipe(recipe));
            else rows.add(line(recipe));
        }
        category.add("recipes", rows);
        return category;
    }

    /** 一页的装填余量：逐组量字数扣减，装不下就记满；用途页的两段共用一份，先到的段先装。 */
    private static final class Room {
        private int remaining;

        Room(int budgetChars) {
            remaining = budgetChars;
        }

        /** 这一组写成 JSON 的字数还装得下吗；装不下的组整组折起来记账。 */
        boolean fits(int chars) {
            return chars <= remaining;
        }

        void take(int chars) {
            remaining -= chars;
        }
    }

    // 配方页的一条：配方 ID（给不出就是 null，不编）、原料、催化剂（有才写）、产出、原始定义（有才写）。
    private static JsonObject recipe(ShownRecipe recipe) {
        JsonObject row = new JsonObject();
        row.addProperty("recipe", recipe.recipeId());
        row.add("inputs", ingredients(recipe.inputs()));
        if (!recipe.catalysts().isEmpty()) row.add("catalysts", ingredients(recipe.catalysts()));
        JsonArray outputs = new JsonArray();
        recipe.outputs().forEach(stack -> outputs.add(stack(stack, stack.amount())));
        row.add("outputs", outputs);
        JsonObject definition = recipe.definition();
        if (definition != null) row.add("definition", definition);
        return row;
    }

    // 用途页的一行：配方 ID：产出 ← 原料。
    private static String line(ShownRecipe recipe) {
        List<String> outputs = recipe.outputs().stream().map(RecipePages::words).toList();
        List<String> inputs = recipe.inputs().stream().map(RecipePages::words).toList();
        return (recipe.recipeId() == null ? "（没有配方 ID）" : recipe.recipeId()) + "：" + String.join("、", outputs)
                + " ← " + String.join("、", inputs);
    }

    private static String words(ShownStack stack) {
        String text = stack.name() + " ×" + stack.amount() + (stack.kind() == ShownStack.Kind.FLUID ? " 毫桶" : "");
        return stack.hasChance() ? text + "（" + Math.round(stack.chance() * 100) + "%）" : text;
    }

    private static String words(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            String example = ingredient.options().isEmpty() ? "" : ingredient.options().getFirst().name();
            return example + "（#" + ingredient.tag() + "）×" + ingredient.amount();
        }
        if (ingredient.options().size() == 1) {
            ShownStack only = ingredient.options().getFirst();
            return only.name() + " ×" + ingredient.amount() + (only.kind() == ShownStack.Kind.FLUID ? " 毫桶" : "");
        }
        return String.join(" 或 ", ingredient.options().stream().map(ShownStack::name).toList()) + " ×" + ingredient.amount();
    }

    private static JsonArray ingredients(List<ShownIngredient> ingredients) {
        JsonArray rows = new JsonArray();
        ingredients.forEach(ingredient -> rows.add(ingredient(ingredient)));
        return rows;
    }

    // 一格原料：是标签写标签与一个示例名；只能放一样就写那一样；能放好几样就列出来。
    private static JsonObject ingredient(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            JsonObject tag = new JsonObject();
            tag.addProperty("tag", ingredient.tag());
            if (!ingredient.options().isEmpty()) tag.addProperty("name", ingredient.options().getFirst().name());
            tag.addProperty("count", ingredient.amount());
            return tag;
        }
        if (ingredient.options().size() == 1) {
            return stack(ingredient.options().getFirst(), ingredient.amount());
        }
        JsonObject oneOf = new JsonObject();
        JsonArray options = new JsonArray();
        ingredient.options().forEach(stack -> options.add(stack(stack, stack.amount())));
        oneOf.add("one_of", options);
        oneOf.addProperty("count", ingredient.amount());
        return oneOf;
    }

    // 一样东西：物品写件数，流体写毫桶，别的照查看器给的数；查看器标了几率才写几率。
    private static JsonObject stack(ShownStack stack, long amount) {
        JsonObject row = new JsonObject();
        switch (stack.kind()) {
            case ITEM -> {
                row.addProperty("item", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("count", amount);
            }
            case FLUID -> {
                row.addProperty("fluid", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("amount_mb", amount);
            }
            case OTHER -> {
                row.addProperty("other", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("amount", amount);
            }
        }
        if (stack.hasChance()) row.addProperty("chance", stack.chance());
        return row;
    }
}
