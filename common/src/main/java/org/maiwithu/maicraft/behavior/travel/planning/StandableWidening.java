// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.util.SpotScan;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行目的地的容差放宽：目的地那一格（床、墙、实心方块里）到原容差一圈都站不上时，
 * 把到达容差逐格放宽到最近真有能站的格子的一圈，最多再放三格。下达解析时判一次；
 * 走路到不了、身体已经停在目的地附近时再判一次——一路挖开、搭出来之后能站的格子
 * 是这一路造出来的，下达那一刻判不了，到收场时用同一份判断重看现场，
 * 同一位置重新下达会判到、走了一路反而判走不到的口径不一致才不会出现。
 * 纯判断，读格走谓词，离线测试直接喂。
 */
public final class StandableWidening {

    /** 最多放宽几格：再远就不是"去那儿"而是另一个地方了。 */
    public static final int EXTRA = 3;

    private StandableWidening() {}

    /**
     * @param destination 高度核实过的目的地
     * @param standable   一格能不能站（脚在这格、头上空着、脚下踩得住）；读不到、没加载按不能站算，不凭空放宽
     * @return 放宽后的目的地（位置不变、容差变大、偏差说明原样带上）；原容差一圈就有能站的、或附近全站不上时原样返回
     */
    public static TravelDestination widen(TravelDestination destination, Standable standable) {
        WorldPosition at = destination.position();
        BlockPos center = new BlockPos(at.x(), at.y(), at.z());
        for (int extra = 0; extra <= EXTRA; extra++) {
            double radius = destination.radius() + extra;
            if (existsStandable(center, radius, at, standable)) {
                return extra == 0 ? destination
                        : TravelDestination.confirmed(at, radius, destination.arrivalNote());
            }
        }
        return destination;
    }

    // 这么大的一圈里有一格能站就放行；球面距离折进谓词里卡，存在性扫描交给环扫内核。
    private static boolean existsStandable(BlockPos center, double radius, WorldPosition at, Standable standable) {
        int span = (int) Math.floor(radius);
        return SpotScan.exists(center, span, span, cell -> {
            int dx = cell.getX() - at.x();
            int dy = cell.getY() - at.y();
            int dz = cell.getZ() - at.z();
            return dx * dx + dy * dy + dz * dz <= radius * radius
                    && standable.at(cell.getX(), cell.getY(), cell.getZ());
        });
    }

    /** 一格能不能站（脚在这格）；读不到给 false，放宽不能建立在没看到的现场上。 */
    public interface Standable {

        boolean at(int x, int y, int z);
    }
}
