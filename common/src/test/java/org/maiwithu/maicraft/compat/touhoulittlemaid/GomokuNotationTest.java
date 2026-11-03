// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 五子棋的记谱与几何：记谱换算按她座位的方向转；每一路要点的那一块按车万女仆自己的换算公式落回同一路；
 * 棋盒点在车万女仆认的范围里；棋盘画出来第一行是离你最远的第 15 路。
 */
class GomokuNotationTest {

    private static final BlockPos CENTER = new BlockPos(10, 64, -20);
    private static final List<Direction> FACINGS = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST);

    @Test
    void 天元_不管朝向都是正中那一路() {
        for (Direction facing : FACINGS) {
            assertEquals(Optional.of(new GomokuNotation.Point(7, 7)), GomokuNotation.parse("H8", facing));
        }
    }

    @Test
    void 朝北_她坐东边_A1在你左手边最近的角() {
        // 朝北的棋盘她坐在东边（+X），你在西边面朝东：行沿 +X 数，左手边是北（−Z），A 列在 z=0。
        assertEquals(Optional.of(new GomokuNotation.Point(0, 0)), GomokuNotation.parse("A1", Direction.NORTH));
        assertEquals(Optional.of(new GomokuNotation.Point(14, 14)), GomokuNotation.parse("O15", Direction.NORTH));
        assertEquals(Optional.of(new GomokuNotation.Point(0, 14)), GomokuNotation.parse("o1", Direction.NORTH));
    }

    @Test
    void 朝南_她坐西边_换算跟着转() {
        // 朝南的棋盘她坐在西边（−X），你在东边面朝西：离你最近的第 1 行在 x=14，左手边是南（+Z）。
        assertEquals(Optional.of(new GomokuNotation.Point(14, 14)), GomokuNotation.parse("A1", Direction.SOUTH));
    }

    @Test
    void 每一路写出来再读回来不变() {
        for (Direction facing : FACINGS) {
            for (int x = 0; x < 15; x++) {
                for (int y = 0; y < 15; y++) {
                    String written = GomokuNotation.write(x, y, facing);
                    assertEquals(Optional.of(new GomokuNotation.Point(x, y)), GomokuNotation.parse(written, facing),
                            facing + " " + written);
                }
            }
        }
    }

    @Test
    void 写法不对_认不出() {
        for (String bad : List.of("", "H", "P8", "H0", "H16", "8H", "H8a", "天元")) {
            assertTrue(GomokuNotation.parse(bad, Direction.NORTH).isEmpty(), bad);
        }
    }

    @Test
    void 每一路要点的那一块_按车万女仆的换算都落回这一路() {
        for (int x = 0; x < 15; x++) {
            for (int y = 0; y < 15; y++) {
                GomokuNotation.Spot spot = GomokuNotation.spot(CENTER, new GomokuNotation.Point(x, y));
                AABB area = spot.area();
                // 那一块的四个角和中间都要点得中这一路，瞄到哪儿都不偏。
                for (double px : new double[] {area.minX, area.getCenter().x, area.maxX}) {
                    for (double pz : new double[] {area.minZ, area.getCenter().z, area.maxZ}) {
                        assertArrayEquals(new int[] {x, y}, tlmChessPos(spot.cell(), px, pz), x + "," + y);
                    }
                }
                assertEquals(CENTER.getY() + 2.0 / 16, area.maxY, 1e-9, "点在棋盘顶面上");
            }
        }
    }

    @Test
    void 棋盒点在车万女仆认的范围里_挑离你近的那个() {
        for (Direction facing : FACINGS) {
            for (GomokuNotation.Spot box : GomokuNotation.chessBoxes(CENTER, facing, null)) {
                Vec3 middle = box.area().getCenter();
                assertTrue(tlmClicksChessBox(box.cell(), middle.x, middle.z, facing), facing + " " + box.cell());
            }
        }
        Vec3 nearSecond = Vec3.atCenterOf(CENTER.offset(-3, 0, 3));
        assertEquals(CENTER.offset(-1, 0, 1),
                GomokuNotation.chessBoxes(CENTER, Direction.NORTH, nearSecond).getFirst().cell());
    }

    @Test
    void 她的座位在朝向顺时针那边一格半() {
        Vec3 seat = GomokuNotation.seat(CENTER, Direction.NORTH);
        assertEquals(CENTER.getX() + 2.0, seat.x, 1e-9);
        assertEquals(CENTER.getZ() + 0.5, seat.z, 1e-9);
    }

    @Test
    void 棋盘画出来_第一行是第15路_每行从A到O() {
        int[][] stones = MaidFakes.emptyStones();
        stones[0][0] = GomokuState.YOURS;
        stones[14][14] = GomokuState.HERS;
        GomokuState board = MaidFakes.board(stones, true, GomokuState.Ending.NOT_OVER, 2, 14, 14,
                GomokuState.HERS);
        List<String> rows = GomokuNotation.rows(board);
        assertEquals(15, rows.size());
        assertEquals("X..............", rows.getLast(), "A1 在最后一行最左");
        assertEquals("..............O", rows.getFirst(), "O15 在第一行最右");
        assertEquals(Optional.of("O15"), board.lastMove());
    }

    // 车万女仆按点中的位置换算是哪一路：照它的常量与写法抄一份，用来核对瞄的那一块落在哪一路。
    private static int[] tlmChessPos(BlockPos cell, double worldX, double worldZ) {
        int partX = cell.getX() - CENTER.getX();
        int partZ = cell.getZ() - CENTER.getZ();
        double x = worldX - cell.getX();
        double y = worldZ - cell.getZ();
        double xOffset = partX == -1 ? 0.505 : partX == 0 ? 0.037 : -0.037;
        double xStart = partX == -1 ? 0.54 : partX == 0 ? 0.08 : -0.01;
        int xIndexOffset = partX == -1 ? 0 : partX == 0 ? 4 : 11;
        double yOffset = partZ == -1 ? 0.505 : partZ == 0 ? 0.037 : 0;
        double yStart = partZ == -1 ? 0.54 : partZ == 0 ? 0.07 : 0;
        int yIndexOffset = partZ == -1 ? 0 : partZ == 0 ? 4 : 11;
        int xIndex = (int) ((x - xOffset) / 0.1316);
        int yIndex = (int) ((y - yOffset) / 0.1316);
        double xFrom = xStart + xIndex * 0.1316;
        double yFrom = yStart + yIndex * 0.1316;
        boolean inside = xFrom < x && x < xFrom + 0.07 && yFrom < y && y < yFrom + 0.07;
        return inside ? new int[] {xIndex + xIndexOffset, yIndex + yIndexOffset} : null;
    }

    // 车万女仆认不认这一下点在棋盒上：同样照它的范围抄一份。
    private static boolean tlmClicksChessBox(BlockPos cell, double worldX, double worldZ, Direction facing) {
        int partX = cell.getX() - CENTER.getX();
        int partZ = cell.getZ() - CENTER.getZ();
        double x = worldX - cell.getX();
        double y = worldZ - cell.getZ();
        if (facing.getAxis() == Direction.Axis.Z) {
            if (partX == 1 && partZ == -1) return 0.5625 <= x && x <= 0.875 && 0.6875 <= y && y <= 1;
            if (partX == -1 && partZ == 1) return 0.125 <= x && x <= 0.4375 && 0 <= y && y <= 0.3125;
        } else {
            if (partX == -1 && partZ == -1) return 0.6875 <= x && x <= 1 && 0.125 <= y && y <= 0.4375;
            if (partX == 1 && partZ == 1) return 0 <= x && x <= 0.3125 && 0.5625 <= y && y <= 0.875;
        }
        return false;
    }
}
