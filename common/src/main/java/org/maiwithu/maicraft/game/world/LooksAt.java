// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一个活体是不是看着另一个实体：从他眼睛顺着视线的那条线，离对方身体不到 1 格就算看着。
 * 活体的视线取头的朝向（别的玩家的视角每 2 刻同步一次，按一圈 256 份取整，约 1.4 度），
 * 所以只能这样宽一格地算。纯几何，全仓只有这一份：观察里写"看着你"、认别的玩家冲角色做的肢体动作都用它。
 */
public final class LooksAt {

    /** 看多远内的才算（格）：和感知"看得见"的范围一样。 */
    public static final double RANGE = 16.0;
    /** 视线离身体多近算看着（格）。 */
    static final double MARGIN = 1.0;

    private LooksAt() {}

    /** 他此刻是不是看着对方。 */
    public static boolean looksAt(LivingEntity watcher, Entity target) {
        if (watcher == null || target == null || watcher == target) return false;
        return ray(watcher.getEyePosition(), watcher.getViewVector(1.0f), target.getBoundingBox());
    }

    /**
     * 从眼睛顺着视线方向的那条线，16 格内离这个身体不到 1 格就算看着：身体按中轴从脚到头取五个点，
     * 只算在他前方的，横向偏开不超过半个身宽加 1 格。挨得很近时也照这样算，不因站得近就算看着。
     */
    public static boolean ray(Vec3 eye, Vec3 look, AABB body) {
        Vec3 dir = look.normalize();
        double reach = body.getXsize() / 2 + MARGIN;
        double centerX = (body.minX + body.maxX) / 2;
        double centerZ = (body.minZ + body.maxZ) / 2;
        for (int i = 0; i <= 4; i++) {
            Vec3 to = new Vec3(centerX, body.minY + body.getYsize() * i / 4, centerZ).subtract(eye);
            double along = to.dot(dir);
            if (along <= 0 || along > RANGE) continue;
            if (to.subtract(dir.scale(along)).length() <= reach) return true;
        }
        return false;
    }
}
