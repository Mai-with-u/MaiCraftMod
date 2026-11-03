// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Locale;

import com.google.gson.JsonElement;

import org.maiwithu.maicraft.game.interaction.DigTool;

/**
 * 读目标里的 dig_tool：这次挖方块点名用哪件工具。写物品 ID 就只用那一件，写 hand 空手挖；
 * 不写时沿用整件事的点名（sequence 的一步没写就跟着整件事走），整件事也没写就是自动挑。
 */
final class DigToolReader {

    private DigToolReader() {}

    /** @param inherited 整件事的点名（读 sequence 的一步时）；最外层为 null */
    static DigTool read(JsonElement raw, String path, DigTool inherited, RequestCheck check) {
        if (raw == null || raw.isJsonNull()) return inherited;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            check.error(path, "dig_tool 应该是一件物品的 ID 或 hand", "\"minecraft:stone_pickaxe\" 或 \"hand\"", raw);
            return inherited;
        }
        String written = raw.getAsString().trim();
        if (written.isEmpty()) {
            check.error(path, "dig_tool 不能为空", "\"minecraft:stone_pickaxe\" 或 \"hand\"");
            return inherited;
        }
        String id = written.toLowerCase(Locale.ROOT);
        if (id.equals("hand")) {
            if (!written.equals("hand")) check.note(path + "：\"" + written + "\" 按 \"hand\" 处理");
            return new DigTool.Hand();
        }
        // 物品 ID 允许省略命名空间：stone_pickaxe 按 minecraft:stone_pickaxe 处理，省得每次写全。
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.equals(written)) check.note(path + "：\"" + written + "\" 按 \"" + id + "\" 处理");
        return new DigTool.Item(id);
    }
}
