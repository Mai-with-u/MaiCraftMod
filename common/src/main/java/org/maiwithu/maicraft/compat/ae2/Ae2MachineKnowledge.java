// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 应用能源2机器这一半的总览资料：认得全部 25 种设备，说明蓝图里线缆与部件怎么写、
 * 频道与控制器的规矩、样板编码与供应器概况，并列出 6 篇进阶专题知识页入口。
 * 每种设备的配方与细部手册去物品资料页与专题页看。
 */
final class Ae2MachineKnowledge implements KnowledgeSource {

    static final String INDEX = "maicraft://knowledge/machine/ae2/index";

    /** 与全量设备登记一致的增补及改名条目：不反向依赖子包，保持包根单向清爽。 */
    private static final List<Ae2MachineCatalog.Kind> FULL_KINDS = Ae2MachineCatalog.kinds(List.of(
            Ae2MachineCatalog.of("crank", "木质曲柄", MachineRole.CONTROL, false),
            Ae2MachineCatalog.of("sky_stone_tank", "陨石储罐", MachineRole.STORAGE, false),
            Ae2MachineCatalog.of("growth_accelerator", "水晶催生器", MachineRole.PROCESSING, true),
            Ae2MachineCatalog.of("cell_workbench", "元件工作台", MachineRole.CONTROL, false),
            Ae2MachineCatalog.of("io_port", "ME IO 传输站", MachineRole.LOGISTICS, true),
            Ae2MachineCatalog.of("spatial_pylon", "空间塔", MachineRole.STORAGE, true),
            Ae2MachineCatalog.of("spatial_io_port", "空间传输站", MachineRole.STORAGE, true),
            Ae2MachineCatalog.of("spatial_anchor", "空间锚", MachineRole.CONTROL, true)));

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "machine.ae2.index", "应用能源2设备与 ME 网络",
                "认得全部 25 种 ME 设备、蓝图里线缆与部件怎么写、频道与控制器规矩及 6 篇进阶专题入口",
                "ae2 应用能源 ME 网络 线缆 终端 部件 控制器 频道 驱动器 样板编码 空间存储 量子环"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        // 查任意已登记 AE2 设备方块或线缆物品时，把总览页一并挂到物品资料结果里。
        if (registryId == null || registryId.isBlank()) return List.of();
        boolean known = FULL_KINDS.stream().anyMatch(kind -> kind.blocks().contains(registryId));
        return known || registryId.endsWith("_cable") && registryId.startsWith("ae2:") ? entries() : List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!INDEX.equals(uri)) return null;
        StringBuilder text = new StringBuilder("# 应用能源2设备与 ME 网络\n\n");
        appendBlueprintAndNetworkRules(text);
        appendAutocraftAndTopics(text);
        appendRecognizedDevices(text);
        return new KnowledgeDocument(INDEX, "machine.ae2.index", "应用能源2设备与 ME 网络", "AE2 机器资料",
                text.toString());
    }

    @Override public String status() {
        return "应用能源2设备资料：可用，" + FULL_KINDS.size() + " 种设备";
    }

    // 写清线缆安装段、部件挂载与频道/供电的基础规矩，供施工与排查网络时直接核对。
    private static void appendBlueprintAndNetworkRules(StringBuilder text) {
        text.append("## 蓝图里怎么写\n\n")
                .append("- 线缆不是方块，是装在线缆格中间的部件：写成一段安装段 `{kind: ae2:fluix_glass_cable, offsets: [一格接一格]}`")
                .append("（kind 写线缆物品 ID，不带颜色的福鲁伊克斯线缆和任何颜色都连），cells 里不写线缆格（线缆格写在安装段的 offsets 里）。\n")
                .append("- 终端、总线、面板、P2P 这类部件写进 parts：`{offset: 线缆那一格, side: 装在哪一面, item: ae2:terminal}`；")
                .append("宿主那一格要是线缆（写在线缆安装段里就行），施工先接线缆再装部件。\n")
                .append("- 设备方块（控制器、驱动器、接口、样板供应器……）照常写进 cells。\n\n");
        text.append("## 网络的规矩\n\n")
                .append("- 线缆贴着设备、设备贴着设备就连上；不同颜色的线缆互不相连。\n")
                .append("- 没有控制器的网最多 8 个频道，超了全网停摆；有控制器后每根普通线缆 8 个、致密线缆 32 个。\n")
                .append("- 网络要有电：能源接收器接外面的电、振动室烧燃料直注电网、能源元件存电。\n")
                .append("- `machine_inspect` 看网络：控制器、频道用量与上限、能量、CPU 在途明细、样板容器与设备内部配置要服务器装了 MaiCraft 才读得到；")
                .append("部件亮不亮（有电、有频道）客户端就看得见。`machine_connect network=me` 会用福鲁伊克斯玻璃线缆接到最近的网。\n\n");
    }

    // 概述样板编码终端与供应器能力，并给出 6 篇细分专题地址，方便模型按需查阅。
    private static void appendAutocraftAndTopics(StringBuilder text) {
        text.append("## 样板编码与样板供应器\n\n")
                .append("- **样板编码终端（`ae2:pattern_encoding_terminal`）**：支持 `crafting` / `processing` / `smithing` / `stonecutting` 4 种编码模式")
                .append("（线缆格按面配 `<side>.encode_mode`、`<side>.substitute`、`<side>.encode_pattern`），处理模式支持流体与大于 64 的数量；")
                .append("编码前先取出输出槽旧样板并确认配方有效，避免洗掉已有样板。\n")
                .append("- **样板供应器（`ae2:pattern_provider` / `ae2:cable_pattern_provider`）**：`machine_configure` 支持 `pattern.<产出物品>=in|out`、")
                .append("`blocking_mode`、`lock_crafting_mode`、`show_in_access_terminal`、`priority`，方块版还有 `push_direction`（单面推送面不再连 ME 网）。\n")
                .append("- 样板管理终端支持 `<side>.show_providers` 与 `<side>.put_pattern` 快速送入；合成终端支持 3×3 手搓合成格。\n\n");
        text.append("## 进阶专题资料\n\n")
                .append("- 日常单机、充能器与木质曲柄：`maicraft://knowledge/machine/ae2/daily-machines`\n")
                .append("- 自动合成闭环与样板编码：`maicraft://knowledge/machine/ae2/autocrafting`\n")
                .append("- 线缆部件按面配置与 P2P 配对：`maicraft://knowledge/machine/ae2/parts-and-p2p`\n")
                .append("- 磁盘分区、倒盘与网络运维：`maicraft://knowledge/machine/ae2/storage-and-ops`\n")
                .append("- 量子环、奇点爆炸转化与空间存储：`maicraft://knowledge/machine/ae2/quantum-and-spatial`\n")
                .append("- 便携存储元件、手持工具装填与补电：`maicraft://knowledge/machine/ae2/tools-and-power`\n\n");
    }

    // 逐行列出全部 25 种认得的设备及是否接入 ME 网络，与实际注册目录保持一致。
    private static void appendRecognizedDevices(StringBuilder text) {
        text.append("## 认得的设备（共 ").append(FULL_KINDS.size()).append(" 种）\n\n");
        for (Ae2MachineCatalog.Kind kind : FULL_KINDS) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）")
                    .append(kind.onGrid() ? "" : "：不接 ME 网的单机").append("\n");
        }
    }
}
