// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 高级 AE（AdvancedAE）基于 AE2 槽位语义的界面布局证明：
 * 反应仓菜单（advanced_ae:reaction_chamber）要求恰好 36 个角色背包槽，容器侧按语义暴露
 * 9 格 MACHINE_INPUT、1 格 MACHINE_OUTPUT 与 4 格 UPGRADE（全部按机器槽核对），
 * 并显式排除动态插入的网络工具箱（TOOLBOX）与幽灵过滤槽（CONFIG）。
 * 输出方向子菜单不含 36 格角色背包，不进入通用容器布局证明。
 */
public final class AdvancedAeSemanticsLayout implements MenuLayoutProof {

    /** 原版角色背包槽位总数：27 格主背包加 9 格快捷栏。F 游戏事实。 */
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** 严禁纳入普通容器搬运的两种 AE2 语义：幽灵过滤槽与随身网络工具箱槽。 */
    private static final Set<String> FORBIDDEN_SEMANTICS = Set.of("CONFIG", "TOOLBOX");

    /**
     * 单个高级 AE 菜单的语义声明规则。
     *
     * @param containerSemantics 有序容器侧语义名列表
     * @param machineSemantics   按机器槽核对搬运的语义名集合（必须为容器侧语义的子集）
     */
    public record Rule(List<String> containerSemantics, Set<String> machineSemantics) {
        public Rule {
            containerSemantics = List.copyOf(Objects.requireNonNull(containerSemantics, "containerSemantics"));
            machineSemantics = Set.copyOf(Objects.requireNonNull(machineSemantics, "machineSemantics"));
            Set<String> declared = new LinkedHashSet<>(containerSemantics);
            for (String semantic : containerSemantics) {
                if (semantic.isBlank()) {
                    throw new IllegalArgumentException("容器侧语义不可为空白字符串");
                }
                if (FORBIDDEN_SEMANTICS.contains(semantic)) {
                    throw new IllegalArgumentException("容器侧语义禁止包含 " + semantic);
                }
            }
            for (String semantic : machineSemantics) {
                if (semantic.isBlank()) {
                    throw new IllegalArgumentException("机器槽语义不可为空白字符串");
                }
                if (FORBIDDEN_SEMANTICS.contains(semantic)) {
                    throw new IllegalArgumentException("机器槽语义禁止包含 " + semantic);
                }
                if (!declared.contains(semantic)) {
                    throw new IllegalArgumentException("机器槽语义必须是容器侧语义的子集：" + semantic);
                }
            }
        }
    }

    private final Function<String, List<Integer>> slotsReader;
    private final Map<String, Rule> rules;

    /** 生产接线：每次查语义槽位都经联动入口包一层，接口对不上时自动停用。 */
    public AdvancedAeSemanticsLayout(AdvancedAeCompat compat) {
        this(Objects.requireNonNull(compat, "compat")::menuSlotsOf, defaultRules());
    }

    /** 离线测试或直接持有语义读端时使用。 */
    public AdvancedAeSemanticsLayout(AdvancedAeReads reads) {
        this(Objects.requireNonNull(reads, "reads")::menuSlotsOf, defaultRules());
    }

    AdvancedAeSemanticsLayout(Function<String, List<Integer>> slotsReader, Map<String, Rule> rules) {
        this.slotsReader = Objects.requireNonNull(slotsReader, "slotsReader");
        Objects.requireNonNull(rules, "rules");
        Map<String, Rule> normalized = new LinkedHashMap<>();
        rules.forEach((menuType, rule) -> {
            String key = Objects.requireNonNull(menuType, "menuType").toLowerCase(Locale.ROOT);
            normalized.put(key, Objects.requireNonNull(rule, "rule"));
        });
        this.rules = Map.copyOf(normalized);
    }

    /** 反应仓默认语义规则：9 格输入 + 1 格输出 + 4 格升级槽，全部按机器槽核对。 */
    public static Map<String, Rule> defaultRules() {
        return Map.of(
                AdvancedAeMachineCatalog.REACTION_CHAMBER_MENU,
                new Rule(List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE"),
                        Set.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE")));
    }

    @Override public Set<String> menuTypes() {
        return rules.keySet();
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 缺少完整 36 格角色背包的菜单（如输出方向子菜单）不能按通用容器布局证明放行。
        if (playerSlots.size() != PLAYER_INVENTORY_SLOTS) {
            return new MenuLayout.Unsupported("高级 AE 界面的角色背包槽位是 " + playerSlots.size()
                    + " 格而不是 " + PLAYER_INVENTORY_SLOTS + " 格，证明不了布局");
        }
        String typeId = slots.menuTypeId() == null ? "" : slots.menuTypeId().toLowerCase(Locale.ROOT);
        Rule rule = rules.get(typeId);
        if (rule == null) {
            return new MenuLayout.Unsupported("未声明语义规则的高级 AE 界面：" + typeId);
        }
        Set<Integer> allowedOther = Set.copyOf(otherSlots);
        // 显式剔除动态插入的网络工具箱与幽灵过滤槽，避免搬运物品时误点。
        Set<Integer> excluded = new LinkedHashSet<>();
        excluded.addAll(slotsReader.apply("TOOLBOX"));
        excluded.addAll(slotsReader.apply("CONFIG"));
        List<Integer> orderedContainer = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Set<Integer> machineSlots = new LinkedHashSet<>();
        for (String semantic : rule.containerSemantics()) {
            for (int index : slotsReader.apply(semantic)) {
                if (!allowedOther.contains(index) || excluded.contains(index)) continue;
                if (seen.add(index)) {
                    orderedContainer.add(index);
                    if (rule.machineSemantics().contains(semantic)) {
                        machineSlots.add(index);
                    }
                }
            }
        }
        if (orderedContainer.isEmpty()) {
            return new MenuLayout.Unsupported("按槽位语义在 " + typeId + " 界面里认不出容器侧格子");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(orderedContainer),
                Set.copyOf(machineSlots));
    }
}
