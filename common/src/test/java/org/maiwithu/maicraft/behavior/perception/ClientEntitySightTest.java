// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实体观察过滤规则测试：剔除无交互入口的技术标记与强力胶附着实体，保留动态装置、座椅、战利品矿车与掉落物。 */
class ClientEntitySightTest {

    @BeforeAll
    static void bootRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 模拟已涂抹在方块上的强力胶选区：不可选中、忽略触发与活塞、无周期位移同步且视线高度为零。 */
    private static final class StaticGlueAttachment extends Entity {
        StaticGlueAttachment(Level level) {
            super(EntityType.AREA_EFFECT_CLOUD, level);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override public EntityDimensions getDimensions(Pose pose) {
            return EntityDimensions.scalable(1.0f, 1.0f).withEyeHeight(0.0f);
        }

        @Override public boolean isIgnoringBlockTriggers() {
            return true;
        }

        @Override public PushReaction getPistonPushReaction() {
            return PushReaction.IGNORE;
        }
    }

    /** 模拟动态结构装置实体：虽不可准星单选且忽略活塞与触发，但具备正尺寸视线高度与周期位置同步（间隔为 3 刻）。 */
    private static final class MovingContraption extends Entity {
        MovingContraption(Level level) {
            super(EntityType.MINECART, level);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override public EntityDimensions getDimensions(Pose pose) {
            return EntityDimensions.scalable(1.0f, 1.0f);
        }

        @Override public boolean isPickable() {
            return false;
        }

        @Override public boolean isIgnoringBlockTriggers() {
            return true;
        }

        @Override public PushReaction getPistonPushReaction() {
            return PushReaction.IGNORE;
        }
    }

    /** 模拟座椅实体：不可直接准星选中且更新间隔为无穷大，但保留常规活塞反应且不忽略方块触发。 */
    private static final class SeatMount extends Entity {
        SeatMount(Level level) {
            super(EntityType.AREA_EFFECT_CLOUD, level);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override public EntityDimensions getDimensions(Pose pose) {
            return EntityDimensions.scalable(0.25f, 0.35f);
        }
    }

    @Test
    void filtersVanillaTechnicalMarkersInteractionsAndPlacedGlue() {
        // 原版标记、隐形触发盒与强力胶选区都不应发观察编号。
        assertFalse(ClientEntitySight.observable(new Marker(EntityType.MARKER, null)));
        assertFalse(ClientEntitySight.observable(new Interaction(EntityType.INTERACTION, null)));
        assertFalse(ClientEntitySight.observable(new StaticGlueAttachment(null)));
    }

    @Test
    void preservesContraptionsSeatsVehiclesAndDrops() {
        // 动态结构装置、座椅、船、箱子矿车（含战利品矿车基类）、掉落物、经验球与盔甲架全部保留。
        assertTrue(ClientEntitySight.observable(new MovingContraption(null)));
        assertTrue(ClientEntitySight.observable(new SeatMount(null)));
        assertTrue(ClientEntitySight.observable(new Boat(EntityType.BOAT, null)));
        assertTrue(ClientEntitySight.observable(new MinecartChest(EntityType.CHEST_MINECART, null)));
        assertTrue(ClientEntitySight.observable(new ItemEntity(EntityType.ITEM, null)));
        assertTrue(ClientEntitySight.observable(new ExperienceOrb(EntityType.EXPERIENCE_ORB, null)));
        assertTrue(ClientEntitySight.observable(new ArmorStand(EntityType.ARMOR_STAND, null)));
    }
}
