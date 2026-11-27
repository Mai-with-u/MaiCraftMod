// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

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
 * ExtendedAE 通用语义界面布局证明：按菜单类型注册 ID 声明容器侧允许暴露的语义列表、
 * 按机器槽核对的语义集合与专属排除语义；运行时自动跳过随身网络工具箱（TOOLBOX）、幽灵过滤槽（CONFIG）
 * 与流体储罐专用槽，且仅暴露当前启用页（{@code isSlotEnabled() == true}）的槽位。
 */
public final class ExtendedAeSemanticsLayout implements MenuLayoutProof {

    /** 原版角色背包槽位总数：27 格主背包加 9 格快捷栏。F 游戏事实。 */
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** 任何菜单都严禁纳入普通容器搬运的两种 AE2 语义：幽灵过滤槽与随身网络工具箱槽。 */
    private static final Set<String> ALWAYS_FORBIDDEN = Set.of("CONFIG", "TOOLBOX");

    /**
     * 单个 ExtendedAE 菜单类型的语义规则。
     *
     * @param containerSemantics 有序的容器侧允许语义（按启用态读取）
     * @param machineSemantics   其中需要按机器槽核对的语义子集
     * @param excludedSemantics  该菜单专属排除的语义（例如水晶组装器的 STORAGE 储罐槽、装罐机的 EX_1）
     * @param allowEmptyContainer 纯配置界面或无升级槽部件界面是否允许容器侧为空
     */
    public record Rule(List<String> containerSemantics, Set<String> machineSemantics,
            Set<String> excludedSemantics, boolean allowEmptyContainer) {
        public Rule {
            containerSemantics = List.copyOf(Objects.requireNonNull(containerSemantics, "containerSemantics"));
            machineSemantics = Set.copyOf(Objects.requireNonNull(machineSemantics, "machineSemantics"));
            excludedSemantics = Set.copyOf(Objects.requireNonNull(excludedSemantics, "excludedSemantics"));
            Set<String> declared = new LinkedHashSet<>(containerSemantics);
            for (String semantic : containerSemantics) {
                if (semantic.isBlank() || ALWAYS_FORBIDDEN.contains(semantic) || excludedSemantics.contains(semantic)) {
                    throw new IllegalArgumentException("容器侧语义非法或被排除：" + semantic);
                }
            }
            for (String semantic : machineSemantics) {
                if (!declared.contains(semantic)) {
                    throw new IllegalArgumentException("机器槽语义必须是容器侧语义的子集：" + semantic);
                }
            }
        }

        /** 全部容器语义均按机器槽核对的常用规则。 */
        public static Rule allMachine(List<String> semantics, Set<String> excluded, boolean allowEmpty) {
            return new Rule(semantics, Set.copyOf(semantics), excluded, allowEmpty);
        }

        /** 纯配置界面规则：只承认 36 格角色背包，不暴露普通容器搬运槽。 */
        public static Rule configOnly() {
            return new Rule(List.of(), Set.of(), Set.of(), true);
        }
    }

    private final Function<String, List<Integer>> enabledReader;
    private final Function<String, List<Integer>> allReader;
    private final Map<String, Rule> rules;

    /** 生产接线用：每次查槽经联动入口包一层，接口变化时自动停用。 */
    public ExtendedAeSemanticsLayout(ExtendedAeCompat compat) {
        this(Objects.requireNonNull(compat, "compat")::enabledMenuSlotsOf,
                compat::allMenuSlotsOf, defaultRules());
    }

    /** 离线测试或直接持有读写端时使用。 */
    public ExtendedAeSemanticsLayout(ExtendedAeReads reads) {
        this(Objects.requireNonNull(reads, "reads")::enabledSlotsOf,
                reads::allSlotsOf, defaultRules());
    }

    /** 自定义规则构造（用于离线测试边界校验）。 */
    public ExtendedAeSemanticsLayout(Function<String, List<Integer>> enabledReader,
            Function<String, List<Integer>> allReader, Map<String, Rule> rules) {
        this.enabledReader = Objects.requireNonNull(enabledReader, "enabledReader");
        this.allReader = Objects.requireNonNull(allReader, "allReader");
        Map<String, Rule> normalized = new LinkedHashMap<>();
        Objects.requireNonNull(rules, "rules").forEach((id, rule) ->
                normalized.put(Objects.requireNonNull(id, "menuId").toLowerCase(Locale.ROOT),
                        Objects.requireNonNull(rule, "rule")));
        this.rules = Map.copyOf(normalized);
    }

    /** 覆盖全部 ExtendedAE 设备与 13 种扩展线缆部件的菜单布局规则表。 */
    public static Map<String, Rule> defaultRules() {
        Map<String, Rule> map = new LinkedHashMap<>();
        // 核心加工机与网络设备菜单。
        map.put("extendedae:crystal_assembler", Rule.allMachine(
                List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE"), Set.of("STORAGE"), false));
        map.put("extendedae:circuit_cutter", Rule.allMachine(
                List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE"), Set.of(), false));
        // 扩展压印器：4 页工作槽同在菜单中，运行时经 enabledSlotsOf 只暴露当前启用页的 4 格 + 升级槽。
        map.put("extendedae:ex_inscriber", Rule.allMachine(
                List.of("INSCRIBER_PLATE_TOP", "EX_1", "EX_2", "EX_3",
                        "INSCRIBER_PLATE_BOTTOM", "EX_4", "EX_5", "EX_6",
                        "MACHINE_INPUT", "EX_7", "EX_8", "EX_9",
                        "MACHINE_OUTPUT", "EX_10", "EX_11", "EX_12", "UPGRADE"),
                Set.of(), false));
        map.put("extendedae:ex_pattern_provider", Rule.allMachine(
                List.of("ENCODED_PATTERN"), Set.of(), false));
        map.put("extendedae:ex_drive", Rule.allMachine(
                List.of("STORAGE_CELL", "EX_1"), Set.of(), false));
        // 扩展接口与超大接口：仅暴露当前启用页存储槽（EX_2/4/6/8）与升级槽，严禁暴露配置幽灵槽（EX_1/3/5/7）。
        Rule interfaceRule = new Rule(
                List.of("EX_2", "EX_4", "EX_6", "EX_8", "UPGRADE"),
                Set.of("UPGRADE"),
                Set.of("EX_1", "EX_3", "EX_5", "EX_7"),
                false);
        map.put("extendedae:ex_interface", interfaceRule);
        map.put("extendedae:oversize_interface", interfaceRule);
        map.put("extendedae:ex_molecular_assembler", Rule.allMachine(
                List.of("EX_1", "EX_2", "EX_3", "EX_4", "EX_5", "EX_6", "EX_7", "EX_8",
                        "MACHINE_OUTPUT", "UPGRADE"),
                Set.of(), false));
        map.put("extendedae:ex_ioport", Rule.allMachine(
                List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE"), Set.of(), false));
        map.put("extendedae:ingredient_buffer", new Rule(
                List.of("STORAGE"), Set.of(), Set.of(), false));
        // 装罐机：只暴露容器槽 EX_2，排除通用流体/物品槽 EX_1。
        map.put("extendedae:caner", Rule.allMachine(
                List.of("EX_2"), Set.of("EX_1"), false));
        map.put("extendedae:wireless_connector", Rule.allMachine(List.of("UPGRADE"), Set.of(), true));
        map.put("extendedae:wireless_hub", Rule.allMachine(List.of("UPGRADE"), Set.of(), true));
        map.put("extendedae:assembler_matrix", Rule.configOnly());
        // 全部 13 种扩展线缆部件菜单：允许仅暴露升级槽（若该部件无升级槽则按纯配置界面放行），排除 CONFIG/TOOLBOX。
        Rule partMenuRule = Rule.allMachine(List.of("UPGRADE"), Set.of(), true);
        for (String partMenu : List.of(
                "extendedae:ex_pattern_access_terminal",
                "extendedae:ex_export_bus",
                "extendedae:ex_import_bus",
                "extendedae:tag_export_bus",
                "extendedae:tag_storage_bus",
                "extendedae:mod_export_bus",
                "extendedae:mod_storage_bus",
                "extendedae:precise_export_bus",
                "extendedae:precise_storage_bus",
                "extendedae:threshold_export_bus",
                "extendedae:threshold_level_emitter",
                "extendedae:active_formation_plane",
                "extendedae:smart_annihilation_plane")) {
            map.put(partMenu, partMenuRule);
        }
        return Map.copyOf(map);
    }

    @Override public Set<String> menuTypes() {
        return rules.keySet();
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 仅对携带完整 36 格角色背包的菜单给 Supported，避免把子菜单误当普通容器。
        if (playerSlots.size() != PLAYER_INVENTORY_SLOTS) {
            return new MenuLayout.Unsupported("ExtendedAE 界面的角色背包槽位是 " + playerSlots.size()
                    + " 格而不是 " + PLAYER_INVENTORY_SLOTS + " 格，证明不了布局");
        }
        String typeId = slots.menuTypeId() == null ? "" : slots.menuTypeId().toLowerCase(Locale.ROOT);
        Rule rule = rules.get(typeId);
        if (rule == null) {
            return new MenuLayout.Unsupported("未登记语义规则的 ExtendedAE 界面：" + typeId);
        }
        if (rule.containerSemantics().isEmpty()) {
            return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
        }
        Set<Integer> allowedOther = Set.copyOf(otherSlots);
        Set<Integer> excluded = collectExcluded(rule);
        List<Integer> orderedContainer = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Set<Integer> machineSlots = new LinkedHashSet<>();
        // 仅纳入当前启用且未被排除的容器槽位，确保分页界面不把隐藏页槽位暴露给普通物品搬运。
        for (String semantic : rule.containerSemantics()) {
            for (int index : enabledReader.apply(semantic)) {
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
            if (rule.allowEmptyContainer()) {
                return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
            }
            return new MenuLayout.Unsupported("按槽位语义在 " + typeId + " 界面里认不出启用的容器侧格子");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(orderedContainer),
                Set.copyOf(machineSlots));
    }

    private Set<Integer> collectExcluded(Rule rule) {
        Set<Integer> excluded = new LinkedHashSet<>();
        for (String forbidden : ALWAYS_FORBIDDEN) {
            excluded.addAll(allReader.apply(forbidden));
        }
        for (String extra : rule.excludedSemantics()) {
            excluded.addAll(allReader.apply(extra));
        }
        return excluded;
    }
}
