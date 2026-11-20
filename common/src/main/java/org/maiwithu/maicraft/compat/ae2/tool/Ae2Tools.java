// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * AE2 便携存储元件、手持工具、石英扳手、陨石罗盘与知识层补强的统一对外汇编入口。
 * 供最终单点汇总接线直接向 CompatRegistry 贡献拆解工具、放置转正规则、物品设置、
 * 随身背包、随身来源、远程武器、场景感知、知识来源以及本组独占的 4 个 36 格菜单语义规则。
 */
public final class Ae2Tools {

    public static final String MENU_NETWORK_TOOL = "ae2:networktool";
    public static final String MENU_QUARTZ_KNIFE = "ae2:quartzknife";
    public static final String MENU_PORTABLE_ITEM_CELL = "ae2:portable_item_cell";
    public static final String MENU_PORTABLE_FLUID_CELL = "ae2:portable_fluid_cell";

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;

    public Ae2Tools(Ae2Compat compat) {
        this(compat, Ae2ToolReads.NONE);
    }

    public Ae2Tools(Ae2Compat compat, Ae2ToolReads reads) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** 拆卸工具建法（供 CompatRegistry.dismantleTool 直接登记）：石英扳手与网络工具拆解 AE2 设备与线缆部件。 */
    public List<Function<PlayerServices, DismantleTool>> dismantleTools() {
        return List.of(services -> new Ae2Wrench(compat, reads, services));
    }

    /** 直接按进世界后的 PlayerServices 构造拆卸工具实例。 */
    public List<DismantleTool> dismantleTools(PlayerServices services) {
        return List.of(new Ae2Wrench(compat, reads, services));
    }

    /** 放置转正规则：仅石英扳手旋转方块与切换样板供应器推送方向（排除网络工具）。 */
    public List<PlacementRules> placementRules() {
        return List.of(new Ae2WrenchPlacementRules(compat, reads));
    }

    /** 放置转正规则（按角色随身扳手优先匹配赛特斯或下界石英扳手，供单点接线传入实时上下文）。 */
    public List<PlacementRules> placementRules(Supplier<PlayerContext> context) {
        return List.of(new Ae2WrenchPlacementRules(compat, reads, context));
    }

    /** 全部 AE2 手持物品设置：石英切割刀、陨石罗盘、内存卡、上色器、熵变仪/物质炮/充能法杖。 */
    public List<ItemSettings> itemSettings(PlayerServices services) {
        Objects.requireNonNull(services, "services");
        return List.of(
                new QuartzKnifeSettings(compat, reads, services),
                new MeteoriteCompassSettings(compat, reads, services),
                new MemoryCardSettings(compat, reads, services),
                new ColorApplicatorSettings(compat, reads, services),
                new PoweredToolSettings(compat, reads, services));
    }

    /** 随身背包实现：10 种 AE2 便携存储元件（物品版参与腾背包，全部 10 种展示于自身观察）。 */
    public List<CarriedBackpack> carriedBackpacks(PlayerServices services) {
        Objects.requireNonNull(services, "services");
        return List.of(new PortableCellBackpack(compat, reads, services));
    }

    /** 随身物品来源：便携存储元件（物品与流体）与网络工具 9 格升级卡箱（均为 via=carried）。 */
    public List<ItemSource> itemSources(PlayerServices services) {
        Objects.requireNonNull(services, "services");
        return List.of(
                new PortableCellSource(compat, reads, services),
                new NetworkToolboxSource(compat, reads, services));
    }

    /** 远程武器实现：物质炮（排除零伤彩弹、瞬发直线射线与内部弹药/电量发射确认）。 */
    public List<RangedWeapon> rangedWeapons() {
        return List.of(new MatterCannonWeapon(compat, reads));
    }

    /** 场景感知实现：陨石罗盘反透视指针感知。 */
    public List<ModSight> modSights() {
        return List.of(new MeteoriteCompassSight(compat, reads));
    }

    /** 知识来源补强：6 篇 AE2 中文进阶专题与按需只读的 GuideME 内置手册索引。 */
    public List<KnowledgeSource> knowledgeSources() {
        return List.of(
                new Ae2ExtendedKnowledge(),
                new Ae2GuideKnowledge(compat, reads));
    }

    /**
     * 本组独占的 4 个带完整 36 格角色背包的 AE2 菜单语义规则（绝不与既有 6 布局撞菜单 ID）：
     * ae2:networktool、ae2:quartzknife、ae2:portable_item_cell、ae2:portable_fluid_cell。
     */
    public Map<String, Ae2SemanticsLayout.Rule> layoutRules() {
        Map<String, Ae2SemanticsLayout.Rule> rules = new LinkedHashMap<>();
        // 网络工具箱：9 格 STORAGE 升级卡槽 + 36 格玩家背包。
        rules.put(MENU_NETWORK_TOOL, new Ae2SemanticsLayout.Rule(List.of("STORAGE"), Set.of()));
        // 石英切割刀：1 格 MACHINE_INPUT 金属锭槽 + 1 格 MACHINE_OUTPUT 模板输出槽 + 36 格玩家背包。
        rules.put(MENU_QUARTZ_KNIFE, new Ae2SemanticsLayout.Rule(
                List.of("MACHINE_INPUT", "MACHINE_OUTPUT"),
                Set.of("MACHINE_INPUT", "MACHINE_OUTPUT")));
        // 便携物品/流体存储元件：继承 MEStorageMenu，货在 AE2 存货表不在普通槽位里，容器侧保持空集合。
        rules.put(MENU_PORTABLE_ITEM_CELL, Ae2SemanticsLayout.Rule.configOnly());
        rules.put(MENU_PORTABLE_FLUID_CELL, Ae2SemanticsLayout.Rule.configOnly());
        return Map.copyOf(rules);
    }
}
