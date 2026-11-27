// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * AE2 扩展（ExtendedAE）认得的全部设备目录：列出每种设备的编号、中文名、机器角色、是否挂接 ME 网络，
 * 以及覆盖的全部方块注册 ID。组装矩阵 6 种构成方块归入同一多方块设备条目。
 */
public final class ExtendedAeMachineCatalog {

    public static final String CRYSTAL_ASSEMBLER = "extendedae:crystal_assembler";
    public static final String CIRCUIT_CUTTER = "extendedae:circuit_cutter";
    public static final String EX_INSCRIBER = "extendedae:ex_inscriber";
    public static final String EX_CHARGER = "extendedae:ex_charger";
    public static final String CRYSTAL_FIXER = "extendedae:crystal_fixer";
    public static final String EX_PATTERN_PROVIDER = "extendedae:ex_pattern_provider";
    public static final String EX_DRIVE = "extendedae:ex_drive";
    public static final String EX_INTERFACE = "extendedae:ex_interface";
    public static final String OVERSIZE_INTERFACE = "extendedae:oversize_interface";
    public static final String EX_MOLECULAR_ASSEMBLER = "extendedae:ex_molecular_assembler";
    public static final String EX_IO_TRANSFER = "extendedae:ex_io_port";
    public static final String INGREDIENT_BUFFER = "extendedae:ingredient_buffer";
    public static final String CANER = "extendedae:caner";
    public static final String WIRELESS_CONNECT = "extendedae:wireless_connect";
    public static final String WIRELESS_HUB = "extendedae:wireless_hub";
    public static final String ASSEMBLER_MATRIX = "extendedae:assembler_matrix_frame";

    /**
     * 一种 ExtendedAE 设备定义。
     *
     * @param id     设备编号（主方块注册 ID）
     * @param name   面向玩家与 LLM 的中文名
     * @param role   在机器能力里的角色分类
     * @param onGrid 是否接入 ME 网络
     * @param blocks 覆盖的全部方块注册 ID
     */
    public record Kind(String id, String name, MachineRole role, boolean onGrid, List<String> blocks) {
        public Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        }
    }

    private ExtendedAeMachineCatalog() {}

    /** 返回全部认得的 ExtendedAE 设备种类。 */
    public static List<Kind> kinds() {
        return List.of(
                kind("crystal_assembler", "水晶组装器", MachineRole.PROCESSING, true),
                kind("circuit_cutter", "电路切片器", MachineRole.PROCESSING, true),
                kind("ex_inscriber", "扩展压印器", MachineRole.PROCESSING, true),
                kind("ex_charger", "扩展充能器", MachineRole.PROCESSING, true),
                kind("crystal_fixer", "水晶修复器", MachineRole.PROCESSING, true),
                kind("ex_pattern_provider", "扩展样板供应器", MachineRole.LOGISTICS, true),
                kind("ex_drive", "ME 扩展驱动器", MachineRole.STORAGE, true),
                kind("ex_interface", "ME 扩展接口", MachineRole.LOGISTICS, true),
                kind("oversize_interface", "ME 超大接口", MachineRole.LOGISTICS, true),
                kind("ex_molecular_assembler", "扩展分子组装室", MachineRole.PROCESSING, true),
                kind("ex_io_port", "ME 扩展 IO 传输站", MachineRole.LOGISTICS, true),
                kind("ingredient_buffer", "ME 原料缓存器", MachineRole.STORAGE, false),
                kind("caner", "ME 装罐机", MachineRole.PROCESSING, true),
                kind("wireless_connect", "ME 无线连接器", MachineRole.CABLE, true),
                kind("wireless_hub", "ME 无线集线器", MachineRole.CABLE, true),
                kind("assembler_matrix_frame", "组装矩阵", MachineRole.PROCESSING, true,
                        "assembler_matrix_frame", "assembler_matrix_wall", "assembler_matrix_glass",
                        "assembler_matrix_pattern", "assembler_matrix_crafter", "assembler_matrix_speed"));
    }

    private static Kind kind(String shortId, String name, MachineRole role, boolean onGrid, String... shortBlocks) {
        List<String> ids = (shortBlocks.length == 0 ? List.of(shortId) : List.of(shortBlocks)).stream()
                .map(block -> "extendedae:" + block)
                .toList();
        return new Kind("extendedae:" + shortId, name, role, onGrid, ids);
    }
}
