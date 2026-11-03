// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 车万女仆五子棋盘的记谱与几何，全部是纯函数。
 *
 * <p>记谱站在下棋的人这一侧（她座位的对面）、面朝她看：列 A–O 从左到右，行 1–15 离你最近的是 1，H8 是天元。
 *
 * <p>棋盘占 3×3 个方块位置：中心一整格加四周各半格，看上去 2×2 格大、2 像素高。每路交叉点能点中的只有顶面上
 * 0.07 格见方的一小块，三段的起点各不相同（车万女仆按点中的位置换算是哪一路）：
 * 靠 −X/−Z 那半格从 0.54 起；中心格 X 从 0.08 起、Z 从 0.07 起；靠 +X 那半格从 −0.01 起、靠 +Z 那半格从 0 起；路距 0.1316。
 */
final class GomokuNotation {

    /** 列的字母：A 到 O。 */
    private static final String COLUMNS = "ABCDEFGHIJKLMNO";
    /** 路距。 */
    private static final double SPACING = 0.1316;
    /** 一路能点中的那一块有多宽。 */
    private static final double WINDOW = 0.07;
    /** 瞄的那一块取能点中范围的中间，四边各留出这么多，免得点在边上被算成没点中。 */
    private static final double AIM_HALF = 0.02;
    /** 棋盘顶面在方块里的高度：2 像素。 */
    private static final double BOARD_TOP = 2.0 / 16;
    /** 棋盒顶面在方块里的高度：4 像素。 */
    private static final double BOX_TOP = 4.0 / 16;

    private GomokuNotation() {}

    /** 棋盘上的一路：x 沿世界 +X、y 沿世界 +Z。 */
    record Point(int x, int y) {}

    /** 要点的一块：点在哪一格方块上、那一块的世界坐标范围。 */
    record Spot(BlockPos cell, AABB area) {}

    /** 记谱写法换成棋盘上的一路；写法不对（不是 A–O 加 1–15）给空。 */
    static Optional<Point> parse(String move, Direction facing) {
        if (move == null) return Optional.empty();
        String text = move.trim().toUpperCase(Locale.ROOT);
        if (text.length() < 2 || text.length() > 3) return Optional.empty();
        int column = COLUMNS.indexOf(text.charAt(0));
        if (column < 0) return Optional.empty();
        int row;
        try {
            row = Integer.parseInt(text.substring(1));
        } catch (NumberFormatException notNumber) {
            return Optional.empty();
        }
        if (row < 1 || row > GomokuState.SIZE) return Optional.empty();
        return Optional.of(point(column, row, facing));
    }

    /** 棋盘上的一路写成记谱写法。 */
    static String write(int x, int y, Direction facing) {
        Direction toward = towardHer(facing);
        Direction right = toward.getClockWise();
        boolean rowAlongX = toward.getAxis() == Direction.Axis.X;
        int rowIndex = rowAlongX ? x : y;
        int columnIndex = rowAlongX ? y : x;
        int row = positive(toward) ? rowIndex + 1 : GomokuState.SIZE - rowIndex;
        int column = positive(right) ? columnIndex : GomokuState.SIZE - 1 - columnIndex;
        return COLUMNS.charAt(column) + Integer.toString(row);
    }

    // 第 column 列（0 起）第 row 行（1 起）在数组里是哪一路：行沿着朝她的方向数，列沿着她的方向右手边数。
    private static Point point(int column, int row, Direction facing) {
        Direction toward = towardHer(facing);
        Direction right = toward.getClockWise();
        int rowIndex = positive(toward) ? row - 1 : GomokuState.SIZE - row;
        int columnIndex = positive(right) ? column : GomokuState.SIZE - 1 - column;
        return toward.getAxis() == Direction.Axis.X ? new Point(rowIndex, columnIndex) : new Point(columnIndex, rowIndex);
    }

    /** 此刻的棋盘画成 15 行：X 你、O 她、. 空；第一行是第 15 路（离你最远），每行从 A 列到 O 列。 */
    static List<String> rows(GomokuState state) {
        List<String> rows = new ArrayList<>();
        for (int row = GomokuState.SIZE; row >= 1; row--) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < GomokuState.SIZE; column++) {
                Point at = point(column, row, state.facing());
                line.append(switch (state.stone(at.x(), at.y())) {
                    case GomokuState.YOURS -> 'X';
                    case GomokuState.HERS -> 'O';
                    default -> '.';
                });
            }
            rows.add(line.toString());
        }
        return rows;
    }

    /** 落在这一路要点的那一块：在哪一格方块的顶面上，取能点中范围的中间。 */
    static Spot spot(BlockPos center, Point point) {
        Segment alongX = segmentX(point.x());
        Segment alongZ = segmentZ(point.y());
        BlockPos cell = center.offset(alongX.cellOffset(), 0, alongZ.cellOffset());
        double x = cell.getX() + alongX.middle();
        double z = cell.getZ() + alongZ.middle();
        double top = cell.getY() + BOARD_TOP;
        return new Spot(cell, new AABB(x - AIM_HALF, top - AIM_HALF, z - AIM_HALF, x + AIM_HALF, top, z + AIM_HALF));
    }

    // 一路在一个方向上落在哪一段：那一段是中心格左右哪一格、这一路能点中的那一块从哪起。
    private record Segment(int cellOffset, double start) {
        double middle() {
            return start + WINDOW / 2;
        }
    }

    private static Segment segmentX(int index) {
        if (index < 4) return new Segment(-1, 0.54 + index * SPACING);
        if (index <= 10) return new Segment(0, 0.08 + (index - 4) * SPACING);
        return new Segment(1, -0.01 + (index - 11) * SPACING);
    }

    private static Segment segmentZ(int index) {
        if (index < 4) return new Segment(-1, 0.54 + index * SPACING);
        if (index <= 10) return new Segment(0, 0.07 + (index - 4) * SPACING);
        return new Segment(1, (index - 11) * SPACING);
    }

    /**
     * 棋盒（点它重开）：随朝向的轴在两个对角上各有一块，离 from 近的在前。
     * 朝向沿 Z 轴时在 +X−Z 角与 −X+Z 角，沿 X 轴时在 −X−Z 角与 +X+Z 角。
     */
    static List<Spot> chessBoxes(BlockPos center, Direction facing, Vec3 from) {
        List<Spot> boxes = facing.getAxis() == Direction.Axis.Z
                ? List.of(box(center, 1, -1, 0.5625, 0.875, 0.6875, 1.0), box(center, -1, 1, 0.125, 0.4375, 0.0, 0.3125))
                : List.of(box(center, -1, -1, 0.6875, 1.0, 0.125, 0.4375), box(center, 1, 1, 0.0, 0.3125, 0.5625, 0.875));
        if (from == null) return boxes;
        return boxes.stream().sorted((a, b) -> Double.compare(a.area().getCenter().distanceToSqr(from),
                b.area().getCenter().distanceToSqr(from))).toList();
    }

    // 棋盒顶面上内缩一圈的那一块：车万女仆只按点中的水平位置认棋盒。
    private static Spot box(BlockPos center, int dx, int dz, double minX, double maxX, double minZ, double maxZ) {
        BlockPos cell = center.offset(dx, 0, dz);
        double inset = 0.03;
        double top = cell.getY() + BOX_TOP;
        return new Spot(cell, new AABB(cell.getX() + minX + inset, top - inset, cell.getZ() + minZ + inset,
                cell.getX() + maxX - inset, top, cell.getZ() + maxZ - inset));
    }

    /** 她的座位：棋盘中心朝她那边 1.5 格处。 */
    static Vec3 seat(BlockPos center, Direction facing) {
        Direction toward = towardHer(facing);
        return new Vec3(center.getX() + 0.5 + toward.getStepX() * 1.5, center.getY() + 0.1,
                center.getZ() + 0.5 + toward.getStepZ() * 1.5);
    }

    // 她坐在棋盘朝向顺时针转一格的那一边；下棋的人在对面。
    private static Direction towardHer(Direction facing) {
        return facing.getClockWise();
    }

    private static boolean positive(Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    }
}
