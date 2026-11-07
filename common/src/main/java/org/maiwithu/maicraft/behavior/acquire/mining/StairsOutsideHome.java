// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 人在自己家里要往下挖楼梯时，先走到哪一格再开挖：离得最近、站得住的天然地面，
 * 这一格不在家里，朝四面再走三格也都不在家里（楼梯往哪边挖都不会一下拐回家底下）。
 * 真人要挖坑会先走出营地，不在自家床底、地板底下开洞。
 */
final class StairsOutsideHome {

    /** 最远在几格内找：再远就不算"走出家门"，如实说找不到。 */
    static final int RADIUS = 24;
    /** 家外这一格朝四面再走几格也不在家里。 */
    static final int MARGIN = 3;
    /** 上下最多差几格：家外的地面和屋里的地面高低差不多。 */
    static final int HEIGHT = 4;

    private StairsOutsideHome() {
    }

    /**
     * 由近到远一圈一圈找：每一格从高到低看上下几格，第一格站得住、自己和四周三格外都不在家里的就是。
     *
     * @param atHome    这一格在不在自己家里
     * @param standable 这一格能不能站（脚和头空着、脚下是天然的实地）
     */
    static Optional<BlockPos> pick(BlockPos feet, Predicate<BlockPos> atHome, Predicate<BlockPos> standable) {
        for (int ring = 1; ring <= RADIUS; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy = HEIGHT; dy >= -HEIGHT; dy--) {
                        BlockPos cell = feet.offset(dx, dy, dz);
                        if (!standable.test(cell) || !clearOfHome(cell, atHome)) continue;
                        double distance = cell.distSqr(feet);
                        if (distance < bestDistance) {
                            best = cell;
                            bestDistance = distance;
                        }
                        break;
                    }
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    // 这一格和它脚下不在家里，朝四面走三格也不在家里。
    private static boolean clearOfHome(BlockPos cell, Predicate<BlockPos> atHome) {
        if (atHome.test(cell) || atHome.test(cell.below())) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (atHome.test(cell.relative(side, MARGIN))) return false;
        }
        return true;
    }
}
