// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 泡在岩浆里往哪出去：从脚下这格起，沿着身体穿得过去的格子（岩浆、空气这类没碰撞的）在水平几格内
 * 找能站上去的岸格，按要游几格排（往上游的格子也算步数），最近的在前。往下只看一层；往上沿着岩浆和岩浆面上那一层
 * 最多看 4 层：沉在两格深的岩浆底，往上游一格就看得见的岸也算。墙后的空格游不过去，不算出口。
 * 附近没有岸时，再看身边哪一格岩浆垫一块方块就成了岸。纯函数，离线可测。
 */
public final class LavaExits {

    /** 先在这么多格内找岸：出岩浆的第一步。 */
    public static final int NEAR = 3;
    /** 近处没有岸、也没药没方块时，朝这么多格内最近的岸游。 */
    public static final int FAR = 16;
    /** 沿着岩浆往上最多看这么多层：岩浆里按住跳能一直往上游。 */
    static final int UP_THROUGH_LAVA = 4;

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private LavaExits() {}

    /** 找出口要看的格子事实。 */
    public interface Cells {
        /** 这一格是岩浆。 */
        boolean lava(BlockPos cell);

        /** 这一格身体穿得过去：没有碰撞（空气、岩浆、水、火、草都算）。 */
        boolean open(BlockPos cell);

        /** 站在这一格顶上踩得住：有碰撞，不是岩浆块、点着的营火这类站上去要烫的。 */
        boolean firm(BlockPos cell);

        /** 这一格是火、灵魂火或点着的营火：站进去要着火。 */
        boolean fire(BlockPos cell);
    }

    /** 这一格是不是出口：脚格、头格都不是岩浆、不是火也没碰撞，脚下那格踩得住；落在火里上了岸还得再迈一步。 */
    public static boolean exit(Cells cells, BlockPos feet) {
        BlockPos head = feet.above();
        return cells.open(feet) && !cells.lava(feet) && !cells.fire(feet)
                && cells.open(head) && !cells.lava(head) && !cells.fire(head)
                && cells.firm(feet.below());
    }

    /**
     * 从脚下沿身体穿得过去的格子找出口，按要游几格排，最近的在前；同样远的按找到的先后。
     *
     * @param reach 水平最多离脚下几格；往下只看一层，往上沿岩浆最多看 {@link #UP_THROUGH_LAVA} 层
     */
    public static List<BlockPos> find(Cells cells, BlockPos feet, int reach) {
        List<BlockPos> exits = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(feet);
        queue.add(feet);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (BlockPos next : neighbours(at)) {
                int dy = next.getY() - feet.getY();
                if (Math.abs(next.getX() - feet.getX()) > reach || Math.abs(next.getZ() - feet.getZ()) > reach
                        || dy < -1 || dy > UP_THROUGH_LAVA || !seen.add(next) || !cells.open(next)) {
                    continue;
                }
                // 到了岸上就记下，不再从岸上往外找：要的是从岩浆里出去的那一步。
                if (exit(cells, next)) {
                    exits.add(next);
                } else if (dy <= 1 || cells.lava(next) || cells.lava(next.below())) {
                    // 脚边上下一层照旧都往外找；再往上只沿着岩浆和浮在岩浆面上的那一层找，空中游不上去。
                    queue.add(next);
                }
            }
        }
        return List.copyOf(exits);
    }

    /**
     * 身边垫一块方块就成了岸的岩浆格：脚下这层或低一层的四个邻格里，是岩浆、垫上之后上面两格既不是岩浆也没碰撞
     * （人能站上去），垫上的顶面比脚高不超过 1 格。贴不贴得住（旁边或下面有没有实心面）由动手时按现场看。
     */
    public static List<BlockPos> footholds(Cells cells, BlockPos feet) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dy = 0; dy >= -1; dy--) {
            for (Direction side : HORIZONTAL) {
                BlockPos spot = feet.relative(side).offset(0, dy, 0);
                BlockPos stand = spot.above();
                if (cells.lava(spot) && cells.open(stand) && !cells.lava(stand)
                        && cells.open(stand.above()) && !cells.lava(stand.above())) {
                    spots.add(spot);
                }
            }
        }
        return List.copyOf(spots);
    }

    // 往四周、往上、往下各一格：岩浆里能往上游，也会往下沉。
    private static List<BlockPos> neighbours(BlockPos at) {
        List<BlockPos> list = new ArrayList<>(6);
        for (Direction side : HORIZONTAL) list.add(at.relative(side));
        list.add(at.above());
        list.add(at.below());
        return list;
    }
}
