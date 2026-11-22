// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.game.world.ReadsLocalizedNames;

/**
 * 观察视图响应尾部的模组短词表（{@code mod_labels}）：
 * 扫描视图正文里实际出现过的非 {@code minecraft:} 命名空间物品、方块、实体类型 ID，
 * 按客户端当前语言读出本地化名称并去重后附在响应根对象尾部。
 *
 * <p>正文仍百分百维持纯英文 ID 不变；全原版响应或查不到任何模组名称时不写入该字段，原版场景零额外字符开销。
 */
public final class ModLabels {

    /** 响应根对象尾部的短词表字段名。 */
    public static final String FIELD = "mod_labels";

    private static final String VANILLA_PREFIX = "minecraft:";
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private ModLabels() {}

    /**
     * 扫描视图根对象并把去重后的非原版模组名称表附在尾部；若无任何有效模组名称则不写入字段。
     */
    public static void appendTo(JsonObject root, ReadsLocalizedNames names) {
        if (root == null || names == null) {
            return;
        }
        Map<String, String> labels = collect(root, names);
        if (labels.isEmpty()) {
            return;
        }
        JsonObject table = new JsonObject();
        labels.forEach(table::addProperty);
        root.add(FIELD, table);
    }

    /** 收集节点内出现过的所有非原版注册 ID 及其当前语言名称，按首次出现顺序去重。 */
    public static Map<String, String> collect(JsonElement root, ReadsLocalizedNames names) {
        if (root == null || names == null) {
            return Map.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        scan(root, candidates);
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (String id : candidates) {
            names.labelOfAny(id).ifPresent(label -> {
                String clean = label.strip();
                if (!clean.isEmpty() && !clean.equalsIgnoreCase(id)) {
                    labels.put(id, clean);
                }
            });
        }
        return labels.isEmpty() ? Map.of() : Collections.unmodifiableMap(labels);
    }

    // 递归收集 JSON 字符串值与对象键名（例如 distant_entities 的键名本身是实体类型 ID），跳过 mod_labels 自身。
    private static void scan(JsonElement node, Set<String> out) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonPrimitive()) {
            if (node.getAsJsonPrimitive().isString()) {
                collectCandidate(node.getAsString(), out);
            }
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                scan(element, out);
            }
            return;
        }
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (FIELD.equals(entry.getKey())) {
                    continue;
                }
                collectCandidate(entry.getKey(), out);
                scan(entry.getValue(), out);
            }
        }
    }

    // 只收非 minecraft: 命名空间的标准资源 ID；其余非注册项会由 ReadsLocalizedNames 过滤掉。
    private static void collectCandidate(String raw, Set<String> out) {
        if (raw == null || raw.isEmpty() || raw.startsWith(VANILLA_PREFIX) || raw.startsWith("#")) {
            return;
        }
        if (RESOURCE_ID.matcher(raw).matches()) {
            out.add(raw);
        }
    }
}
