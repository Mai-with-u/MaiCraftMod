// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 高级 AE（AdvancedAE）中文知识来源：提供反应仓批量加工指南、流体桶方块直点用法、
 * 高瞬时耗电电网准备建议与全部配置键说明，并挂载到反应仓及核心批量产物的物品资料页。
 */
public final class AdvancedAeKnowledge implements KnowledgeSource {

    /** 高级 AE 知识总览页地址。 */
    public static final String INDEX = "maicraft://knowledge/machine/advanced_ae/index";

    private static final List<String> RELATED_IDS = List.of(
            AdvancedAeMachineCatalog.REACTION_CHAMBER,
            "advanced_ae:quantum_infusion_bucket",
            "advanced_ae:quantum_alloy",
            "advanced_ae:quantum_alloy_plate",
            "advanced_ae:shattered_singularity",
            "extendedae:entro_crystal",
            "extendedae:entro_ingot",
            "ae2:fluix_crystal",
            "ae2:certus_quartz_crystal",
            "ae2:charged_certus_quartz_crystal");

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(
                INDEX,
                "machine.advanced_ae.index",
                "高级 AE（AdvancedAE）反应仓批量加工",
                "反应仓一次批量产出 64 颗水晶/恩特罗注入锭与量子灌注液、手持桶直点灌水/舀液、高瞬时耗电与配置键",
                "advanced_ae 高级AE 反应仓 reaction_chamber 批量 64 恩特罗水晶 福鲁伊克斯水晶 赛特斯石英 量子灌注液 流体桶"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        if (registryId == null || registryId.isBlank()) return List.of();
        if (registryId.startsWith("advanced_ae:") || RELATED_IDS.contains(registryId)) {
            return entries();
        }
        return List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!INDEX.equals(uri)) return null;
        String text = """
                # 高级 AE（AdvancedAE）反应仓批量加工

                ## 反应仓（`advanced_ae:reaction_chamber`）用途
                - 反应仓是中后期批量加工核心机器，可一次批量产出 **64 颗**恩特罗水晶（`extendedae:entro_crystal`）、福鲁伊克斯水晶（`ae2:fluix_crystal`）、赛特斯石英水晶（`ae2:certus_quartz_crystal`）、充能赛特斯石英（`ae2:charged_certus_quartz_crystal`）与恩特罗注入锭（`extendedae:entro_ingot`），彻底替代往水池里扔原料的慢速手搓。
                - 反应仓还支持产出流体（如量子灌注液 `advanced_ae:quantum_infusion`），流体产物进入输出流体槽（槽 0）。

                ## 加流体与舀取流体产物（无需开界面）
                - **加流体**：直接手持水桶或岩浆桶等满桶右键反应仓方块本身，一次向输入流体槽（槽 1，上限 16,000 mB）灌入 1000 mB，主手变为 `minecraft:bucket`，不会打开界面。
                - **舀流体产物**：手持空桶右键反应仓方块，直接从输出流体槽（槽 0，上限 16,000 mB）舀出 1000 mB 流体装满桶。
                - **异种流体不强倒**：若输入流体槽里已有另一种流体，`machine_run` 会直接报错并提示先执行 `machine_configure(flush_fluid_in=true)` 清空，不会盲倒。

                ## 供电与连网要点
                - 反应仓**六面全部可连 ME 线缆并受电**，占用 1 个 ME 频道，内部缓冲 500,000 AE。
                - **高瞬时耗电警告**：单次批量配方耗电较高（数万至数十万 AE），接入的 ME 网络务必配备足够的能源元件（如 `ae2:energy_cell` / `ae2:dense_energy_cell`）和发电来源，否则容易触发 `show_warning` 缺电告警甚至跳电停工。

                ## 支持的 `machine_configure` 配置键
                - `auto_export`：`yes` / `no`（自动向允许输出面推送成品）。
                - `upgrade.0` .. `upgrade.3`：`ae2:speed_card` / `empty`（最多 4 张加速卡）。
                - `flush_fluid_in`：`true`（清空输入流体槽 1）。
                - `flush_fluid_out`：`true`（清空输出流体槽 0）。
                - `output_side.front` / `back` / `left` / `right` / `top` / `bottom`：`true` / `false`（经输出方向子菜单配置相对六面输出）。
                """;
        return new KnowledgeDocument(INDEX, "machine.advanced_ae.index",
                "高级 AE（AdvancedAE）反应仓批量加工", "高级 AE 机器资料", text);
    }

    @Override public String status() {
        List<String> ids = new ArrayList<>(RELATED_IDS);
        return "高级 AE 资料：可用，1 篇反应仓指南（关联 " + ids.size() + " 项方块与物品）";
    }
}
