// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记住的箱式加工站：一进料容器、一出料容器（允许同一容器），由调用方按名字标注并按世界持久化。
 *
 * <p>这类加工站不绑定任何具体模组方块，角色通过原生开箱投料与开箱取货使用它；
 * {@code exclude} 写明不交给本站处理的物品 ID 或 {@code #标签}（例如排除食物让食物仍走熔炉或烟熏炉）。
 *
 * @param name    加工站名字，例如"鼓风熔炼站"
 * @param via     用途途径，例如 {@code smelt}
 * @param input   投料容器位置（带维度）
 * @param output  取货容器位置（带维度，可与投料容器为同一格）
 * @param exclude 不交给本站处理的物品 ID 或 {@code #标签} 列表，已统一小写并去重
 */
public record RememberedStation(String name, String via, WorldPosition input, WorldPosition output,
        List<String> exclude) {

    public RememberedStation {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("加工站的名字不能为空");
        if (via == null || via.isBlank()) throw new IllegalArgumentException("加工站的途径不能为空");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        name = name.trim();
        via = via.trim().toLowerCase(Locale.ROOT);
        Set<String> normalized = new LinkedHashSet<>();
        if (exclude != null) {
            for (String item : exclude) {
                if (item != null && !item.isBlank()) {
                    normalized.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        exclude = List.copyOf(new ArrayList<>(normalized));
    }

    /** 这个加工站是否在指定维度：未写维度的一方按相配算，两口都在该维度才算匹配。 */
    public boolean inDimension(String dimension) {
        return sameDimension(input.dimension(), dimension) && sameDimension(output.dimension(), dimension);
    }

    /** 投料与取货是否指向同一只容器（同一格且同维度）。 */
    public boolean sameContainer() {
        return MemoryRecord.sameSpot(input, output);
    }

    private static boolean sameDimension(String first, String second) {
        return first == null || second == null || first.equals(second);
    }
}
