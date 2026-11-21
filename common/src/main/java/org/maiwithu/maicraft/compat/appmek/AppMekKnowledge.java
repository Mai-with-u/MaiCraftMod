// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 应用机械（Applied Mekanistics）的中文专题知识来源：
 * 覆盖全部 12 件 {@code appmek:*} 物品（化学品元件外壳、5 档 ME 化学品存储元件、5 档便携化学品元件、化学品 P2P 通道），
 * 以及化学品总线/接口配置、处理样板编码、P2P 调谐配对、QIO 仪表盘挂载、放射性化学品与物质聚合器限制要点。
 */
public final class AppMekKnowledge implements KnowledgeSource {

    public static final String URI_INDEX = "maicraft://knowledge/machine/appmek/index";

    private static final Set<String> RELATED_IDS = Set.of(
            "appmek:chemical_cell_housing",
            "appmek:chemical_storage_cell_1k",
            "appmek:chemical_storage_cell_4k",
            "appmek:chemical_storage_cell_16k",
            "appmek:chemical_storage_cell_64k",
            "appmek:chemical_storage_cell_256k",
            "appmek:portable_chemical_cell_1k",
            "appmek:portable_chemical_cell_4k",
            "appmek:portable_chemical_cell_16k",
            "appmek:portable_chemical_cell_64k",
            "appmek:portable_chemical_cell_256k",
            "appmek:chemical_p2p_tunnel",
            "mekanism:qio_dashboard");

    private static final KnowledgeDocument.Entry ENTRY = new KnowledgeDocument.Entry(
            URI_INDEX,
            "machine.appmek.index",
            "应用机械（Applied Mekanistics）化学品存储、传输与自动化总览",
            "化学品元件、便携化学品元件、化学品总线/接口/样板编码、化学品 P2P、QIO 挂载与放射性限制",
            "appmek 应用机械 化学品 存储元件 便携化学品元件 P2P QIO 聚合器 放射性 chemical mekanism");

    private static final String CONTENT = """
            # 应用机械（Applied Mekanistics）总览

            应用机械（`appmek`）把通用机械（Mekanism）的化学品（气体、灌注、浆液、颜料统一为化学品）接进应用能源2（AE2）网络，通道键类型为 `appmek:chemical`，计量单位为毫桶（`mB`），每字节可存储 `8,000 mB`。

            ## 全部物品一览
            - **ME 化学品元件外壳**（`appmek:chemical_cell_housing`）：合成各级化学品存储元件的外壳。
            - **ME 化学品存储元件**（`appmek:chemical_storage_cell_1k` / `4k` / `16k` / `64k` / `256k`）：放入 ME 驱动器或 ME 箱子，**单盘最多存 5 种化学品**，支持反相卡、均分卡、虚空卡，**禁止存入放射性化学品**。
            - **便携化学品元件**（`appmek:portable_chemical_cell_1k` / `4k` / `16k` / `64k` / `256k`）：随身携带的化学品盘，**单盘最多存 18 种化学品**，支持反相卡与能量卡，同样禁止放射性化学品；可用 `machine_inspect(item=...)` 免开窗查看 `energy`、`energy_max`、`bytes`、`types` 与 `chemicals`（单位 `mB`），低电放充能器（`ae2:charger`）走 `machine_run` 补电，不参与随身物品腾背包或 `obtain`。打开时用普通右键，**不要潜行右键空元件以免拆解**。
            - **化学品 P2P 通道**（`appmek:chemical_p2p_tunnel`）：在 ME 网上点对点传输通用机械化学品。

            ## 总线、接口、监视器与元件工作台配置
            - **输入总线（`ae2:import_bus`）**：安装应用机械后自动多出 `key_type.chemical`（`true` / `false`），与 `key_type.item`、`key_type.fluid` 同走键类型切换包。
            - **过滤与分区槽（输入/输出/存储总线、成型面板、发信器、元件工作台）**：`filter.0..62` 可直接填化学品注册 ID（如 `mekanism:hydrogen`）；若某种化学品与流体同名（例如硫酸等）或要强制指定介质，使用显式前缀 `chemical:<id>`（如 `chemical:mekanism:sulfuric_acid`）。
            - **ME 接口（`ae2:interface` / `ae2:cable_interface`）**：`stock.0..8` 支持化学品备料（如 `chemical:mekanism:hydrogen 16000`，单位 `mB`）。安装应用机械后，AE2 的通用内部库存自动暴露通用机械化学品能力，可用加压管道直接连 ME 接口与样板供应器。
            - **存储/转换监视器**：`displayed` 可设为化学品 ID 或 `chemical:<id>`，手持盛有对应化学品的化学品储罐二次右键切到内含化学品。
            - **服务端只读查询**：`ae2.device` 遇到含流体或化学品的过滤/备料槽时会附带 `config_slot_types` / `storage_slot_types`（`item` / `fluid` / `chemical`）；`ae2.network` 会在网络存有化学品时附带 `chemical_types` 总种类数与按存量降序的 `stored_chemicals[]`。

            ## 处理样板编码（`ae2:pattern_encoding_terminal`）
            - `mode="processing"` 的 `inputs` / `outputs` 支持化学品条目：写法为 `{"kind":"chemical","id":"mekanism:hydrogen","amount":1000}` 或简写 `{"chemical":"mekanism:hydrogen","amount":1000}`（单位 `mB`，支持超过 64 的任意正数量）。
            - 编码后的处理样板放入样板供应器后，样板供应器可直接把化学品推送给相邻通用机械机器或通过加压管道衔接。

            ## 化学品 P2P 通道调谐与配对
            - 调谐（`attunement=chemical`）：持任意等级通用机械化学品储罐（基础/高级/精英/终极/创造，标签 `#appmek:p2p_attunements/chemical_p2p_tunnel`）右键 P2P 通道即可调谐为 `appmek:chemical_p2p_tunnel`，不消耗储罐。
            - 配对（`p2p_role=input|output`）：与其余 AE2 P2P 一致，强制要求主手持有内存卡（`ae2:memory_card`），输入端潜行右键分配频率，输出端普通右键绑定频率。

            ## QIO 挂载与放射性 / 聚合器红线
            - **QIO 仪表盘挂载**：为通用机械 `mekanism:qio_dashboard` 设好频率后，必须把 AE2 存储总线（`ae2:storage_bus`）贴在仪表盘的**背面**（朝向的反面），才能将整个 QIO 存储挂载进 ME 网络（按网格节点拥有者校核 QIO 安全权限）；且该挂载**仅支持物品，不支持化学品与流体**。
            - **放射性化学品与物质聚合器限制**：应用机械主动拒绝放射性化学品进入任何 ME 化学品存储元件、拒绝虚空溢流卡销毁放射性化学品，也拒绝放射性化学品进入物质聚合器（`ae2:condenser`）；核废料、钚、钋等放射性化学品必须使用通用机械专用放射性废料桶与防辐射管线处理。
            """;

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(ENTRY);
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        if (registryId == null || registryId.isBlank()) return List.of();
        String lower = registryId.trim().toLowerCase(Locale.ROOT);
        if (RELATED_IDS.contains(lower) || lower.startsWith("appmek:")) {
            return List.of(ENTRY);
        }
        return List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!URI_INDEX.equals(uri)) return null;
        return new KnowledgeDocument(ENTRY.uri(), ENTRY.name(), ENTRY.title(), "应用机械专题资料", CONTENT);
    }

    @Override public String status() {
        return "应用机械专题资料：可用，1 篇总览";
    }
}
