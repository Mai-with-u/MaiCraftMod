// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * AE2 扩展（ExtendedAE）中文知识页：集中说明水晶组装器配方边界、各加工机接电面差异、
 * 无界面直点机器用法（扩展充能器、水晶修复器）以及恩特罗母岩、无线连接器与组装矩阵多方块规则，
 * 并挂载到全部相关方块与核心物品的 {@code lookup} 用法查询。
 */
public final class ExtendedAeKnowledge implements KnowledgeSource {

    public static final String INDEX = "maicraft://knowledge/machine/extendedae/index";

    /** 除机器目录方块外同样挂接此知识页的核心手持工具、升级件与部件物品。 */
    private static final Set<String> EXTRA_ITEMS = Set.of(
            "extendedae:entro_seed",
            "extendedae:wireless_tool",
            "extendedae:me_packing_tape",
            "extendedae:pattern_provider_upgrade",
            "extendedae:interface_upgrade",
            "extendedae:drive_upgrade",
            "extendedae:io_bus_upgrade",
            "extendedae:pattern_terminal_upgrade",
            "extendedae:wireless_connect_upgrade",
            "extendedae:ex_pattern_access_part",
            "extendedae:ex_export_bus_part",
            "extendedae:ex_import_bus_part",
            "extendedae:tag_export_bus_part",
            "extendedae:tag_storage_bus_part",
            "extendedae:mod_export_bus_part",
            "extendedae:mod_storage_bus_part",
            "extendedae:precise_export_bus_part",
            "extendedae:precise_storage_bus_part",
            "extendedae:threshold_export_bus_part",
            "extendedae:threshold_level_emitter_part",
            "extendedae:active_formation_plane_part",
            "extendedae:smart_annihilation_plane_part");

    private final Set<String> relatedIds;

    public ExtendedAeKnowledge() {
        Set<String> ids = new LinkedHashSet<>(EXTRA_ITEMS);
        for (ExtendedAeMachineCatalog.Kind kind : ExtendedAeMachineCatalog.kinds()) {
            ids.addAll(kind.blocks());
        }
        this.relatedIds = Set.copyOf(ids);
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(
                INDEX, "machine.extendedae.index", "AE2 扩展（ExtendedAE）设备与用法",
                "水晶组装器配方边界、加工机接电面差异、无界面机器（扩展充能器/水晶修复器）、母岩与无线/组装矩阵规则",
                "extendedae AE2扩展 水晶组装器 电路切片器 扩展压印器 扩展充能器 水晶修复器 组装矩阵 无线连接器 恩特罗"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        if (registryId == null || registryId.isBlank()) return List.of();
        if (relatedIds.contains(registryId) || (registryId.startsWith("extendedae:") && registryId.endsWith("_upgrade"))) {
            return entries();
        }
        return List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!INDEX.equals(uri)) return null;
        StringBuilder text = new StringBuilder("# AE2 扩展（ExtendedAE）设备与用法\n\n");
        appendCrystalAssemblerBoundary(text);
        appendPowerSides(text);
        appendDirectMachines(text);
        appendBuddingWirelessAndMatrix(text);
        appendCatalog(text);
        return new KnowledgeDocument(INDEX, "machine.extendedae.index", "AE2 扩展（ExtendedAE）设备与用法",
                "ExtendedAE 机器资料", text.toString());
    }

    @Override public String status() {
        return "AE2 扩展设备资料：可用，" + ExtendedAeMachineCatalog.kinds().size() + " 种设备";
    }

    private static void appendCrystalAssemblerBoundary(StringBuilder text) {
        text.append("## 1. 水晶组装器（`extendedae:crystal_assembler`）配方边界\n\n")
                .append("- **能做**：福鲁伊克斯水晶（+100 mB 水）、恩特罗注入锭（+100 mB 水）、恩特罗母岩（+200 mB 水）、")
                .append("陨石金属锭（+100 mB 岩浆）、4 倍处理器与全部 ExtendedAE 高级机械/组件。\n")
                .append("- **不能做**：恩特罗水晶和赛特斯石英水晶不在水晶组装器配方内，需走泡水转化（`obtain`）或反应仓。\n")
                .append("- 流体原料不会由手持水桶右键方块直接灌入，而是打开界面往储罐槽倒桶（`machine_run` 会自动按余量备桶并在界面里倒桶）；")
                .append("若储罐内已有异种流体，需先抽空或换机。\n\n");
    }

    private static void appendPowerSides(StringBuilder text) {
        text.append("## 2. 各加工机接电与连网面差异\n\n")
                .append("- **水晶组装器（`extendedae:crystal_assembler`）**：除顶面外的五面可连 ME 网与受电。\n")
                .append("- **电路切片器（`extendedae:circuit_cutter`）**：除相对正面与背面外的四面可连 ME 网与受电。\n")
                .append("- **扩展压印器（`extendedae:ex_inscriber`）与扩展充能器（`extendedae:ex_charger`）**：")
                .append("除相对正面外的五面可连 ME 网、受电或安装木质曲柄。\n")
                .append("- **水晶修复器（`extendedae:crystal_fixer`）**：**仅相对背面一面**可连 ME 网与受电，正面必须紧贴待修复母岩。\n")
                .append("- **ME 装罐机（`extendedae:caner`）**：**仅相对顶面与底面两面**可连 ME 网与受电。\n\n");
    }

    private static void appendDirectMachines(StringBuilder text) {
        text.append("## 3. 无界面直点机器\n\n")
                .append("- **扩展充能器（`extendedae:ex_charger`）**：没有界面，主手持可充能物品或 AE2 带电工具右键直接放入 4 槽中的空槽")
                .append("（最多 4 件并发充能），空手右键依次弹出；`machine_run` 自动完成并发充能与补电。\n")
                .append("- **水晶修复器（`extendedae:crystal_fixer`）**：没有界面，主手持燃料右键整叠装入槽 0，空手右键取出全部燃料；")
                .append("修复赛特斯母岩喂 `ae2:charged_certus_quartz_crystal`，修复恩特罗母岩喂 `#c:dusts/ender_pearl`。\n\n");
    }

    private static void appendBuddingWirelessAndMatrix(StringBuilder text) {
        text.append("## 4. 母岩、无线配对与组装矩阵\n\n")
                .append("- **恩特罗母岩**：手持恩特罗种子（`extendedae:entro_seed`）直接 `use` 右键已放置的福鲁伊克斯块即可转化。\n")
                .append("- **无线连接器与集线器**：手持无线连接工具（`extendedae:wireless_tool`）依次 `use` 右键两端配对；")
                .append("无线集线器最多挂接 8 条通道，`machine_configure` 支持升级卡与 `disconnect_port`（0..7）。\n")
                .append("- **组装矩阵多方块**：用框架/墙壁/玻璃（`extendedae:assembler_matrix_frame/wall/glass`）围成至少 3×3×3 的完整长方体外壳，")
                .append("内部填入样板核心（`assembler_matrix_pattern`）、合成核心（`assembler_matrix_crafter`）与速度核心（`assembler_matrix_speed`）；")
                .append("`machine_configure` 设 `cancel_all=true` 可取消矩阵全部在途合成作业。\n")
                .append("- **一次性升级件与封装胶带**：`*_upgrade` 和 `extendedae:me_packing_tape` 直接用原生 `use` 右键目标方块。\n\n");
    }

    private static void appendCatalog(StringBuilder text) {
        text.append("## 5. 认得的设备\n\n");
        for (ExtendedAeMachineCatalog.Kind kind : ExtendedAeMachineCatalog.kinds()) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）\n");
        }
    }
}
