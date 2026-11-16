// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSink;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;

/**
 * 原版硬碰撞实体障碍来源：把船、运输船、潜影贝这类会像方块一样挡住玩家身体的实体交给寻路与直线走廊避让。
 *
 * <p>进世界时始终安装，不依赖任何联动模组；角色自己与当前正在乘坐的载具排除在外，避免把自己脚下坐着的船算成墙。
 */
public final class SolidEntityObstacles implements ObstacleSource {

    /** 每刻位移小于该阈值视为停稳静止。 */
    private static final double STILL = 1e-3;
    /** 单刻合理平移速度上限，避免实体瞬移同步时把预测前导盒拉得过长。 */
    private static final double MAX_SLIDE_PER_TICK = 4.0;

    /** 在联动模组障碍来源前拼上原版硬碰撞实体障碍源，保证未装模组时船和潜影贝也照常避让。 */
    public static List<ObstacleSource> with(List<ObstacleSource> modSources) {
        List<ObstacleSource> all = new ArrayList<>(1 + modSources.size());
        all.add(new SolidEntityObstacles());
        all.addAll(modSources);
        return List.copyOf(all);
    }

    @Override
    public void obstacles(Level level, Player viewer, AABB region, ObstacleSink sink) {
        if (level == null || region == null) {
            return;
        }
        // 只挑区域内具备硬碰撞箱且非角色自身、非当前所乘载具的实体转成寻路障碍。
        for (Entity entity : level.getEntities(viewer, region, candidate -> isSolidObstacle(candidate, viewer))) {
            sink.add(toStructure(entity));
        }
    }

    /** 判断实体是否对当前步行角色构成硬碰撞阻挡：排除观察者自身、旁观态及当前正乘坐的同一载具链。 */
    static boolean isSolidObstacle(Entity entity, Entity viewer) {
        if (entity == null || entity == viewer || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        if (!entity.canBeCollidedWith()) {
            return false;
        }
        if (viewer != null && (entity == viewer.getVehicle() || viewer.isPassengerOfSameVehicle(entity))) {
            return false;
        }
        AABB box = entity.getBoundingBox();
        return box.getXsize() > 0 && box.getYsize() > 0 && box.getZsize() > 0;
    }

    /** 将硬碰撞实体转换为寻路统一消费的障碍结构，移动中的船按平移速度附带前导避让。 */
    static MovingStructure toStructure(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 step = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
        double speedSqr = step.lengthSqr();
        MovingStructure.Motion motion = speedSqr > STILL * STILL && speedSqr <= MAX_SLIDE_PER_TICK * MAX_SLIDE_PER_TICK
                ? new MovingStructure.Motion.Sliding(step)
                : new MovingStructure.Motion.Still();
        return new MovingStructure("entity:" + entity.getStringUUID(), List.of(box), box, true, motion, null, false);
    }
}
