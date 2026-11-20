// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

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
 * 基于 AE2 槽位语义的声明式界面布局证明：按菜单类型注册 ID 声明需要纳入容器侧的语义列表与机器槽语义集合，
 * 运行时经 {@link Ae2MenuSlots} 按语义取出真实槽位编号，自动排除动态插入的网络工具箱（TOOLBOX）与幽灵过滤槽（CONFIG）。
 * 纯配置界面允许容器侧为空，使打开界面改配置时仍能通过布局核对。
 */
public final class Ae2SemanticsLayout implements MenuLayoutProof {

    /** 原版角色背包槽位总数：27 格主背包加 9 格快捷栏。F 游戏事实。 */
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** 严禁纳入普通容器侧搬运的两种 AE2 槽位语义：幽灵过滤槽与随身网络工具箱槽。 */
    private static final Set<String> FORBIDDEN_SEMANTICS = Set.of("CONFIG", "TOOLBOX");

    /**
     * 单个 AE2 菜单类型的语义规则。
     *
     * @param containerSemantics 有序的容器侧语义名称列表，决定证明结果里 containerSlots 的槽位顺序
     * @param machineSemantics   其中需要按机器槽确认搬运的语义集合（必须为 containerSemantics 的子集）
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

        /** 纯配置界面规则：只认角色 36 格背包，不暴露任何普通容器搬运槽。 */
        public static Rule configOnly() {
            return new Rule(List.of(), Set.of());
        }
    }

    private final Function<String, List<Integer>> slotsReader;
    private final Map<String, Rule> rules;

    /** 生产接线用：每次读语义槽位都经联动入口包一层，接口对不上时自动停用。 */
    public Ae2SemanticsLayout(Ae2Compat compat, Map<String, Rule> rules) {
        this(Objects.requireNonNull(compat, "compat")::menuSlotsOf, rules);
    }

    /** 离线测试或直接持有语义读端时使用。 */
    public Ae2SemanticsLayout(Ae2MenuSlots menuSlots, Map<String, Rule> rules) {
        this(Objects.requireNonNull(menuSlots, "menuSlots")::slotsOf, rules);
    }

    private Ae2SemanticsLayout(Function<String, List<Integer>> slotsReader, Map<String, Rule> rules) {
        this.slotsReader = Objects.requireNonNull(slotsReader, "slotsReader");
        Objects.requireNonNull(rules, "rules");
        Map<String, Rule> normalized = new LinkedHashMap<>();
        rules.forEach((menuType, rule) -> {
            String key = Objects.requireNonNull(menuType, "menuType").toLowerCase(Locale.ROOT);
            normalized.put(key, Objects.requireNonNull(rule, "rule"));
        });
        this.rules = Map.copyOf(normalized);
    }

    @Override public Set<String> menuTypes() {
        return rules.keySet();
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 只对带完整 36 格角色背包的菜单给 Supported，不含背包的子菜单不走通用容器布局证明。
        if (playerSlots.size() != PLAYER_INVENTORY_SLOTS) {
            return new MenuLayout.Unsupported("AE2 界面的角色背包槽位是 " + playerSlots.size() + " 格而不是 "
                    + PLAYER_INVENTORY_SLOTS + " 格，证明不了布局");
        }
        String typeId = slots.menuTypeId() == null ? "" : slots.menuTypeId().toLowerCase(Locale.ROOT);
        Rule rule = rules.get(typeId);
        if (rule == null) {
            return new MenuLayout.Unsupported("未声明语义规则的 AE2 界面：" + typeId);
        }
        // 纯配置界面（例如发信器、空间锚）：容器侧与机器槽均为空，只承认 36 格角色背包。
        if (rule.containerSemantics().isEmpty()) {
            return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
        }
        Set<Integer> allowedOther = Set.copyOf(otherSlots);
        // 即使菜单槽位有意外重叠，也显式排除网络工具箱与幽灵过滤槽，避免容器搬运误点。
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
            return new MenuLayout.Unsupported("按 AE2 槽位语义在 " + typeId + " 界面里认不出容器侧格子");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(orderedContainer),
                Set.copyOf(machineSlots));
    }
}
