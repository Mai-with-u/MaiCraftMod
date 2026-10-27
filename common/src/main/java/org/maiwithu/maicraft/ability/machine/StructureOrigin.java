// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;

/**
 * 结构局部坐标的原点：物理结构上的格给 LLM 看、让 LLM 写偏移时都以它为零。结构上有跟过来的机器档案时是档案锚点
 * （造的时候蓝图的偏移照用），没有时是结构上的物理组装器，再没有是地块中心（组装时收进的第一块方块）。
 * x、z 是组装那一刻的世界东西、南北（组装不转向），y 向上。地块里的大数只在内部用，给出去之前换成局部坐标。
 *
 * @param plot    原点在结构地块里的格
 * @param how     原点是哪一格：archive 档案锚点 / assembler 物理组装器 / plot_center 地块中心
 * @param archive 跟在这个结构上的机器档案名；没有为 null
 */
record StructureOrigin(BlockPos plot, String how, String archive) {

    static final String ARCHIVE = "archive";
    static final String ASSEMBLER = "assembler";
    static final String PLOT_CENTER = "plot_center";

    /** 文字里写的一格坐标（"x, y, z"，方块坐标的短写法）。 */
    private static final Pattern CELL = Pattern.compile("(-?\\d+), (-?\\d+), (-?\\d+)");
    /** 地块范围往外放这么多格，靠着船边的格（要放的、站的）也认得出是地块里的。 */
    private static final int MARGIN = 16;

    StructureOrigin {
        plot = Objects.requireNonNull(plot, "plot").immutable();
        Objects.requireNonNull(how, "how");
    }

    /** 按档案锚点、物理组装器、地块中心的顺序取原点。 */
    static StructureOrigin of(StructureWorksites.Worksite worksite, Optional<MachineArchive> archive) {
        if (archive.isPresent()) return new StructureOrigin(archive.get().anchor(), ARCHIVE, archive.get().name());
        return worksite.assembler().map(at -> new StructureOrigin(at, ASSEMBLER, null))
                .orElseGet(() -> new StructureOrigin(worksite.plotCenter(), PLOT_CENTER, null));
    }

    /** 地块里的格换成局部坐标。 */
    BlockPos local(BlockPos plotCell) {
        return plotCell.subtract(plot);
    }

    /** 一句话说原点是哪一格。 */
    String describe() {
        return switch (how) {
            case ARCHIVE -> "机器档案「" + archive + "」的锚点（造的时候蓝图的原点）";
            case ASSEMBLER -> "结构上的物理组装器";
            default -> "地块中心（组装时收进的第一块方块）";
        };
    }

    /**
     * 把文字里落在这个结构地块里的格坐标换成局部坐标：施工一路记下的"放 x, y, z""清障 x, y, z"、问题里列的格，
     * 给 LLM 看之前都换掉；不在地块范围里的数（世界里的格、件数）原样留着。
     *
     * @param min 地块里方块范围的最小角
     * @param max 地块里方块范围的最大角
     */
    String localize(String text, BlockPos min, BlockPos max) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = CELL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replaced = matcher.group();
            try {
                BlockPos cell = new BlockPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                if (inside(cell, min, max)) replaced = local(cell).toShortString();
            } catch (NumberFormatException tooLong) {
                // 不是格坐标（超出整数范围的长数字）：原样留着。
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean inside(BlockPos cell, BlockPos min, BlockPos max) {
        return cell.getX() >= min.getX() - MARGIN && cell.getX() <= max.getX() + MARGIN
                && cell.getY() >= min.getY() - MARGIN && cell.getY() <= max.getY() + MARGIN
                && cell.getZ() >= min.getZ() - MARGIN && cell.getZ() <= max.getZ() + MARGIN;
    }
}
