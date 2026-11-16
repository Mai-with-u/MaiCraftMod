// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原版硬碰撞实体寻路障碍测试：船、运输船、潜影贝类硬壳实体按硬碰撞箱避让，排除角色自身与当前所乘载具。 */
class SolidEntityObstaclesTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 模拟潜影贝这类无须加载世界即可构造的硬壳碰撞实体（canBeCollidedWith 为 true）。 */
    private static final class SolidShellEntity extends Entity {
        SolidShellEntity() {
            super(EntityType.SHULKER, null);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override public boolean canBeCollidedWith() {
            return true;
        }
    }

    /** 模拟离线的步行或乘船角色替身，避免无世界环境下触发原版骑乘事件分发。 */
    private static final class OfflineRider extends Entity {
        private final Entity mount;

        OfflineRider(Entity mount) {
            super(EntityType.ARMOR_STAND, null);
            this.mount = mount;
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override public Entity getVehicle() {
            return mount;
        }
    }

    @Test
    void recognizesSolidCollisionEntitiesAndExcludesSelfOrCurrentMount() {
        // 停在路上的船、运输船与潜影贝类硬壳实体属于硬碰撞障碍；掉落物与普通矿车可穿过，角色自身与正坐着的船不挡自己。
        Boat riddenBoat = new Boat(EntityType.BOAT, null);
        riddenBoat.setPos(0.5, 64.0, 0.5);

        OfflineRider viewer = new OfflineRider(riddenBoat);
        viewer.setPos(0.5, 64.0, 0.5);

        Boat parkedBoat = new Boat(EntityType.BOAT, null);
        parkedBoat.setPos(2.5, 64.0, 0.5);

        ChestBoat chestBoat = new ChestBoat(EntityType.CHEST_BOAT, null);
        chestBoat.setPos(4.5, 64.0, 0.5);

        SolidShellEntity shell = new SolidShellEntity();
        shell.setPos(6.5, 64.0, 0.5);

        assertTrue(SolidEntityObstacles.isSolidObstacle(parkedBoat, viewer));
        assertTrue(SolidEntityObstacles.isSolidObstacle(chestBoat, viewer));
        assertTrue(SolidEntityObstacles.isSolidObstacle(shell, viewer));
        assertFalse(SolidEntityObstacles.isSolidObstacle(viewer, viewer), "角色自身不能当成障碍");
        assertFalse(SolidEntityObstacles.isSolidObstacle(riddenBoat, viewer), "当前正乘坐的船不能当成障碍");
        assertFalse(SolidEntityObstacles.isSolidObstacle(new Minecart(EntityType.MINECART, null), viewer));
        assertFalse(SolidEntityObstacles.isSolidObstacle(new ItemEntity(EntityType.ITEM, null), viewer));
    }

    @Test
    void convertsParkedAndMovingBoatsForObstacleSnapshot() {
        // 停稳的船生成静止碰撞盒，划动中的船生成平移前导碰撞盒，且世界障碍源拼接始终包含原版硬碰撞实体源。
        List<ObstacleSource> combined = SolidEntityObstacles.with(List.of());
        assertEquals(1, combined.size());

        Boat parked = new Boat(EntityType.BOAT, null);
        parked.setPos(2.0, 64.0, 0.5);
        parked.xo = 2.0;
        parked.yo = 64.0;
        parked.zo = 0.5;
        MovingStructure still = SolidEntityObstacles.toStructure(parked);
        assertInstanceOf(MovingStructure.Motion.Still.class, still.motion());

        Boat rowing = new Boat(EntityType.BOAT, null);
        rowing.setPos(2.25, 64.0, 0.5);
        rowing.xo = 2.0;
        rowing.yo = 64.0;
        rowing.zo = 0.5;
        MovingStructure sliding = SolidEntityObstacles.toStructure(rowing);
        assertInstanceOf(MovingStructure.Motion.Sliding.class, sliding.motion());

        NavigationObstacles.View view = NavigationObstacles.convert(List.of(still), Set.of());
        assertFalse(view.all().clearSegment(new Vec3(0.5, 64.0, 0.5), new Vec3(3.5, 64.0, 0.5), 0.6, 1.8),
                "穿过停靠船只的直线走廊必须被拦截");
    }
}
