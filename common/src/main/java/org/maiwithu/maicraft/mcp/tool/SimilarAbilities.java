// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.Listing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 能力名写错时给的提示：名字互相包含，或者只差几个字母的能力；一个都不像时列出全部能力，
 * 让 LLM 下一次就能写对，而不是只告诉它"没有这个能力"。写的是已经删掉的能力名时，直接说该改用什么。
 */
final class SimilarAbilities {
    /**
     * 已经删掉、但小模型还常顺手写的能力名，以及该改用什么。宿主那边有个让出一轮的 wait 工具，小模型看到它
     * 就以为角色也能站着等；这里直接指到真正该用的能力，不列"相近的能力"——eat 和 wait 只差两个字母，列出来反而带偏。
     */
    private static final Map<String, String> REMOVED = Map.of(
            "wait", "wait 能力已经删了，角色不再站着干等："
                    + "过一会儿回来看用 timer（不占身体，到点在事件里叫你）；过夜用 sleep，天黑后角色也会自己去睡；"
                    + "回血先去干别的、避开危险，到时候用 timer 回来看");

    private SimilarAbilities() {}

    /** 给 {@code id}（带或不带命名空间）找相近的能力，写成一句提示。 */
    static String hint(AbilityRegistry registry, String id) {
        String name = id.substring(id.indexOf(':') + 1);
        // 写的是删掉的能力：只说改用什么，不再按字母相近去猜。
        String instead = REMOVED.get(name);
        if (instead != null) return instead;
        List<AbilitySpec> listed = registry.all().stream().map(AbilityModule::spec)
                .filter(spec -> spec.listing() == Listing.LISTED).toList();
        List<String> similar = listed.stream()
                .filter(spec -> spec.name().contains(name) || name.contains(spec.name())
                        || editDistance(spec.name(), name) <= Math.max(2, name.length() / 3))
                .sorted(Comparator.comparingInt(spec -> editDistance(spec.name(), name)))
                .limit(3).map(AbilitySpec::id).toList();
        if (!similar.isEmpty()) {
            return "相近的能力：" + String.join("、", similar);
        }
        List<String> all = listed.stream().map(AbilitySpec::id).toList();
        return all.isEmpty() ? "lookup() 列出全部能力" : "可用的能力：" + String.join("、", all);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
