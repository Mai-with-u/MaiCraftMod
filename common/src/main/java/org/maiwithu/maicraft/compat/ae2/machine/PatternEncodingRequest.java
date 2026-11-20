// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * 样板编码终端单次完整编码请求的结构化记录与纯校验逻辑：
 * 在入口一次性将 JSON 字符串解析成带类型的不可变记录，并在调用 encode() 前严格核验当前模式配方有效，
 * 防止 AE2 服务端在配方无效时触发 clearPattern() 把输出槽已有编码样板洗回空白样板。
 *
 * @param mode                编码模式：crafting / processing / smithing / stonecutting
 * @param inputs              输入槽条目（按槽位顺序，空条目表示跳过或留空）
 * @param outputs             输出槽条目（仅 processing 模式使用，0 号为主产物必填）
 * @param substitute          是否启用物品替换（可选）
 * @param substituteFluids    是否启用流体替换（可选）
 * @param stonecuttingRecipe  切石模式下的配方 ID（可选）
 */
record PatternEncodingRequest(String mode, List<SlotEntry> inputs, List<SlotEntry> outputs,
        Boolean substitute, Boolean substituteFluids, String stonecuttingRecipe) {

    static final List<String> MODES = List.of("crafting", "processing", "smithing", "stonecutting");

    /** 单个幽灵槽条目：支持普通物品与流体，以及大于 64 的处理样板堆叠数量。 */
    record SlotEntry(String kind, String id, long amount) {
        boolean isEmpty() {
            return id == null || id.isBlank() || amount <= 0;
        }
        boolean isFluid() {
            return "fluid".equalsIgnoreCase(kind);
        }
    }

    PatternEncodingRequest {
        mode = Objects.requireNonNull(mode, "mode").toLowerCase(Locale.ROOT);
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    /** 将配置值 JSON 字符串解析为编码请求；格式错误或模式非法时返回空并带原因。 */
    static ParseResult parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.error("encode_pattern 不能为空，需提供包含 mode 与 inputs 的 JSON 对象");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(rawJson);
        } catch (JsonParseException ex) {
            return ParseResult.error("encode_pattern 不是合法 JSON：" + ex.getMessage());
        }
        if (!root.isJsonObject()) {
            return ParseResult.error("encode_pattern 必须是 JSON 对象");
        }
        JsonObject obj = root.getAsJsonObject();
        String mode = readString(obj, "mode");
        if (mode == null || mode.isBlank()) {
            return ParseResult.error("encode_pattern 缺少 mode（crafting / processing / smithing / stonecutting）");
        }
        String normalizedMode = normalizeMode(mode);
        if (!MODES.contains(normalizedMode)) {
            return ParseResult.error("不支持的样板编码模式：" + mode);
        }
        List<SlotEntry> inputs = parseEntries(obj.get("inputs"));
        List<SlotEntry> outputs = parseEntries(obj.get("outputs"));
        Boolean sub = readBoolean(obj, "substitute");
        Boolean subFluid = readBoolean(obj, "substitute_fluids");
        String stoneRecipe = readString(obj, "stonecutting_recipe");

        // 入口静态基本校验，尽早拦下明显残缺的请求。
        String staticErr = validateStatic(normalizedMode, inputs, outputs, stoneRecipe);
        if (staticErr != null) {
            return ParseResult.error(staticErr);
        }
        return ParseResult.ok(new PatternEncodingRequest(normalizedMode, inputs, outputs, sub, subFluid, stoneRecipe));
    }

    /** 兼容 smithing_table 与 smithing 两种写法。 */
    static String normalizeMode(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return "smithing_table".equals(lower) ? "smithing" : lower;
    }

    private static String validateStatic(String mode, List<SlotEntry> inputs, List<SlotEntry> outputs,
            String stoneRecipe) {
        boolean anyInput = inputs.stream().anyMatch(entry -> !entry.isEmpty());
        if (!anyInput) return "样板编码请求至少需要 1 个非空输入项";
        if ("processing".equals(mode)) {
            if (outputs.isEmpty() || outputs.getFirst().isEmpty()) {
                return "处理样板（processing）的 0 号主产物输出槽必须非空";
            }
        }
        if ("smithing".equals(mode)) {
            if (inputs.size() < 3 || inputs.get(0).isEmpty() || inputs.get(1).isEmpty() || inputs.get(2).isEmpty()) {
                return "锻造台样板（smithing）需要依次提供模板、底材、升级材料 3 个非空输入";
            }
        }
        if ("stonecutting".equals(mode) && (stoneRecipe == null || stoneRecipe.isBlank())) {
            return "切石样板（stonecutting）必须指定 stonecutting_recipe 配方 ID";
        }
        return null;
    }

    /**
     * 调用 encode() 前的现场防洗样板校验：确认当前打开的样板编码终端已识别出合法配方，
     * 返回空表示可以安全编码；非空说明配方无效，必须拒绝发送 encode() 以免触发 clearPattern()。
     */
    static Optional<String> validateMenuBeforeEncode(PatternEncodingRequest request,
            Ae2MachineReads.PatternEncodingMenuView view) {
        if (view == null) return Optional.of("读不到样板编码终端界面状态");
        if (view.hasEncodedPattern()) {
            return Optional.of("输出槽仍占着已有编码样板，必须先清空输出槽再编码");
        }
        String mode = normalizeMode(view.mode() == null ? "" : view.mode());
        if (!mode.equals(request.mode())) {
            return Optional.of("终端当前模式为 " + mode + "，尚未切到目标模式 " + request.mode());
        }
        return switch (request.mode()) {
            case "crafting" -> view.craftingResultId() != null && !view.craftingResultId().isEmpty()
                    ? Optional.empty()
                    : Optional.of("当前 3×3 合成格未能匹配有效工作台配方，拒绝调用 encode() 以防洗掉样板");
            case "processing" -> view.processingInputNonEmpty() && view.processingPrimaryOutputNonEmpty()
                    ? Optional.empty()
                    : Optional.of("处理样板输入格或 0 号主产物槽为空，拒绝调用 encode()");
            case "stonecutting" -> isStonecuttingValid(request.stonecuttingRecipe(), view)
                    ? Optional.empty()
                    : Optional.of("切石配方 " + request.stonecuttingRecipe()
                            + " 未生效或不在当前可选切石配方中，拒绝调用 encode()");
            case "smithing" -> view.smithingValid()
                    ? Optional.empty()
                    : Optional.of("锻造台三槽未能匹配有效锻造配方，拒绝调用 encode()");
            default -> Optional.of("未知编码模式：" + request.mode());
        };
    }

    private static boolean isStonecuttingValid(String wantedRecipe, Ae2MachineReads.PatternEncodingMenuView view) {
        if (wantedRecipe == null || wantedRecipe.isBlank()) return false;
        if (!wantedRecipe.equals(view.stonecuttingRecipeId())) return false;
        return view.stonecuttingRecipes() == null || view.stonecuttingRecipes().isEmpty()
                || view.stonecuttingRecipes().contains(wantedRecipe);
    }

    private static List<SlotEntry> parseEntries(JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) return List.of();
        JsonArray arr = raw.getAsJsonArray();
        List<SlotEntry> result = new ArrayList<>(arr.size());
        for (JsonElement element : arr) {
            result.add(parseEntry(element));
        }
        return List.copyOf(result);
    }

    private static SlotEntry parseEntry(JsonElement element) {
        if (element == null || element.isJsonNull()) return new SlotEntry("item", "", 0);
        if (element.isJsonPrimitive()) {
            String id = element.getAsString().trim();
            return id.isEmpty() ? new SlotEntry("item", "", 0) : new SlotEntry("item", id, 1);
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String fluid = readString(obj, "fluid");
            if (fluid != null && !fluid.isBlank()) {
                long amount = readLong(obj, "amount", 1000L);
                return new SlotEntry("fluid", fluid, amount);
            }
            String kind = readString(obj, "kind");
            if (kind == null || kind.isBlank()) kind = "item";
            String id = readString(obj, "id");
            if (id == null || id.isBlank()) id = readString(obj, "item");
            long defaultAmt = "fluid".equalsIgnoreCase(kind) ? 1000L : 1L;
            long amount = obj.has("amount") ? readLong(obj, "amount", defaultAmt)
                    : readLong(obj, "count", defaultAmt);
            return new SlotEntry(kind, id == null ? "" : id.trim(), amount);
        }
        return new SlotEntry("item", "", 0);
    }

    private static String readString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    private static Boolean readBoolean(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsBoolean() : null;
    }

    private static long readLong(JsonObject obj, String key, long fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 解析结果：成功带请求记录，失败带中文错误说明。 */
    record ParseResult(PatternEncodingRequest request, String error) {
        static ParseResult ok(PatternEncodingRequest request) {
            return new ParseResult(request, null);
        }
        static ParseResult error(String error) {
            return new ParseResult(null, error);
        }
        boolean ok() {
            return request != null;
        }
    }
}
