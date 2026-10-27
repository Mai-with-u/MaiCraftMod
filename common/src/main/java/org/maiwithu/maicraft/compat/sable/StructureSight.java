// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 从一处眼睛看物理结构上的一格：瞄哪几点、看不看得见、够不够得着。地块里的点按结构此刻的位姿换到世界，
 * 射线交给游戏——Sable 让射线照样打得中结构上的格，打中时给的是地块里的格、地块里的命中点和地块里的面，
 * 和真人对着船上的方块点下去时交给服务器的一样。看见的点按世界坐标交出去，给转头用。
 */
final class StructureSight {

    /** 射线比瞄准点多射出去这么一截（格），免得浮点误差让射线停在表面前面。 */
    static final double OVERSHOOT = 0.25;
    /** 面上往里收的四角离边多远（按那一面的边长算）。 */
    static final double INSET = 0.25;

    private StructureSight() {}

    /**
     * 一格外形上可以瞄的点（地块坐标）：没给面时是外形中心和外形六个面的中心；给了面就是外形每个框朝这边那一面的中心，
     * 加上往里收一点的四角（细轴、轮缘这类一面只有窄窄一条，中心被挡时还有别处可瞄）。外形是空的按整格算。
     *
     * @param boxes 这一格的外形，以格子最小角为原点（0~1）
     */
    static List<Vec3> aimPoints(BlockPos cell, List<AABB> boxes, Direction face) {
        List<AABB> shape = boxes.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : boxes;
        List<Vec3> points = new ArrayList<>();
        if (face == null) {
            AABB bounds = shape.getFirst();
            for (AABB box : shape) bounds = bounds.minmax(box);
            points.add(bounds.getCenter().add(Vec3.atLowerCornerOf(cell)));
            for (Direction side : Direction.values()) points.add(faceCenter(bounds, side).add(Vec3.atLowerCornerOf(cell)));
            return points;
        }
        for (AABB box : shape) {
            Vec3 center = faceCenter(box, face);
            points.add(center.add(Vec3.atLowerCornerOf(cell)));
            for (double a : new double[]{INSET, 1 - INSET}) {
                for (double b : new double[]{INSET, 1 - INSET}) {
                    Vec3 corner = switch (face.getAxis()) {
                        case X -> new Vec3(center.x, box.minY + a * box.getYsize(), box.minZ + b * box.getZsize());
                        case Y -> new Vec3(box.minX + a * box.getXsize(), center.y, box.minZ + b * box.getZsize());
                        case Z -> new Vec3(box.minX + a * box.getXsize(), box.minY + b * box.getYsize(), center.z);
                    };
                    points.add(corner.add(Vec3.atLowerCornerOf(cell)));
                }
            }
        }
        return points;
    }

    /** 盒子朝 side 那一面的中心。 */
    static Vec3 faceCenter(AABB box, Direction side) {
        Vec3 center = box.getCenter();
        return switch (side) {
            case UP -> new Vec3(center.x, box.maxY, center.z);
            case DOWN -> new Vec3(center.x, box.minY, center.z);
            case EAST -> new Vec3(box.maxX, center.y, center.z);
            case WEST -> new Vec3(box.minX, center.y, center.z);
            case SOUTH -> new Vec3(center.x, center.y, box.maxZ);
            case NORTH -> new Vec3(center.x, center.y, box.minZ);
        };
    }

    /**
     * 从 eye（世界坐标）挨个看这些点：够得着、射线第一下就打中 target（给了 face 还要打在那一面上）的第一个点。
     * 给出的命中是地块里的格与面，命中点换成世界坐标（转头瞄它）；一个都不行为 null。
     */
    static BlockHitResult visible(ClientLevel level, Player player, SableStructures.Pose pose, Vec3 eye, BlockPos target,
            Direction face, double reach, List<Vec3> localPoints) {
        for (Vec3 local : localPoints) {
            Vec3 world = pose.toWorld(local);
            double distance = eye.distanceTo(world);
            if (distance > reach || distance < 1.0e-6) continue;
            BlockHitResult hit = clip(level, player, eye, world.subtract(eye).scale((distance + OVERSHOOT) / distance));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) continue;
            if (face != null && hit.getDirection() != face) continue;
            return new BlockHitResult(world, hit.getDirection(), target, false);
        }
        return null;
    }

    /** 从 eye 沿 delta 射一条原版射线（看方块外形、不看流体）：打中结构上的格时给地块里的格和命中点。 */
    static BlockHitResult clip(ClientLevel level, Player player, Vec3 eye, Vec3 delta) {
        return level.clip(new ClipContext(eye, eye.add(delta), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }
}
