// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 眼前视野：目标此刻在不在角色第一人称镜头朝向的前方视野锥里。
 *
 * <p>{@link ObservationVisibility} 只管眼睛到目标有没有方块挡住（四面八方都算），
 * 本类再管"角色有没有朝那边看"——背对着的东西就算没遮挡也不在画面里，不能算亲眼看见。
 * 纯几何部分不依赖客户端运行时，与遮挡判断合在一起就是"在眼前亲眼看见"。
 */
public final class FieldOfView {

    /** 第一人称宽屏画面的半视角余弦（60 度）：原版默认垂直视野 70 度，宽屏水平视野约 110 度。F 游戏事实，来源：原版镜头投影。 */
    public static final double HALF_ANGLE_COS = 0.5;

    /** 视线直接穿过外框时的近身擦边容差（格）：贴在眼前的大体型实体八角可能撑出视角外，视线正指着它仍算看见。F 游戏事实，来源：外框几何。 */
    private static final double RAY_INFLATE = 0.2;

    private FieldOfView() {}

    /** 外框在不在眼睛前方 {@code maxRange} 格以内的视野锥里。 */
    public static boolean inFront(Vec3 eye, Vec3 lookDirection, AABB box, double maxRange) {
        if (eye == null || lookDirection == null || box == null || maxRange <= 0.0) {
            return false;
        }
        double dirLenSqr = lookDirection.lengthSqr();
        if (dirLenSqr < 1.0e-8 || nearestDistanceSqr(eye, box) > maxRange * maxRange) {
            return false;
        }
        Vec3 dir = lookDirection.scale(1.0 / Math.sqrt(dirLenSqr));
        if (pointInCone(eye, dir, box.getCenter(), maxRange)) {
            return true;
        }
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    if (pointInCone(eye, dir, new Vec3(x, y, z), maxRange)) {
                        return true;
                    }
                }
            }
        }
        double along = box.getCenter().subtract(eye).dot(dir);
        if (along <= 0.0) {
            return false;
        }
        Vec3 onLookRay = eye.add(dir.scale(Math.min(along, maxRange)));
        return box.inflate(RAY_INFLATE).contains(onLookRay);
    }

    /** 一格方块在不在眼睛前方 {@code maxRange} 格以内的视野锥里。 */
    public static boolean inFront(Vec3 eye, Vec3 lookDirection, BlockPos cell, double maxRange) {
        return cell != null && inFront(eye, lookDirection, new AABB(cell), maxRange);
    }

    /** 角色此刻是不是在眼前亲眼看见这只实体（在前方视野锥内且视线没有方块遮挡）。 */
    public static boolean canSeeEntity(LocalPlayer player, Entity target, double maxRange) {
        return player != null && target != null && !target.isRemoved()
                && inFront(player.getEyePosition(), player.getViewVector(1.0f), target.getBoundingBox(), maxRange)
                && ObservationVisibility.entity(player, target);
    }

    /** 角色此刻是不是在眼前亲眼看见这一格方块（在前方视野锥内且视线没有方块遮挡）。 */
    public static boolean canSeeBlock(LocalPlayer player, BlockPos cell, double maxRange) {
        return player != null && cell != null
                && inFront(player.getEyePosition(), player.getViewVector(1.0f), cell, maxRange)
                && ObservationVisibility.block(player, cell);
    }

    private static boolean pointInCone(Vec3 eye, Vec3 unitDir, Vec3 point, double maxRange) {
        Vec3 delta = point.subtract(eye);
        double distSqr = delta.lengthSqr();
        if (distSqr > maxRange * maxRange) {
            return false;
        }
        if (distSqr < 1.0e-8) {
            return true;
        }
        return delta.dot(unitDir) / Math.sqrt(distSqr) >= HALF_ANGLE_COS;
    }

    private static double nearestDistanceSqr(Vec3 point, AABB box) {
        double dx = axisGap(point.x, box.minX, box.maxX);
        double dy = axisGap(point.y, box.minY, box.maxY);
        double dz = axisGap(point.z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisGap(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }
}
