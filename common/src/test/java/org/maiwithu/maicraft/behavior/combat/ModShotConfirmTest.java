// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;

/**
 * 联动远程武器的内部弹药默认行为与零重力直线弹道核对：
 * 旧实现（土豆加农炮）默认不走内部弹药；零重力无阻力（drag=1、gravity=0）不除零、不额外抬高仰角。
 */
class ModShotConfirmTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 默认远程武器保持背包供弹与向后兼容() {
        RangedWeapon legacy = new RangedWeapon() {
            @Override public boolean covers(ItemStack weapon) { return false; }
            @Override public Optional<Shot> nextShot(ItemStack weapon, Player player) { return Optional.empty(); }
            @Override public List<Shot> possibleShots(ItemStack weapon, Player player) { return List.of(); }
        };
        assertFalse(legacy.usesInternalAmmo());
        assertFalse(legacy.shotFired(ItemStack.EMPTY, ItemStack.EMPTY, null));
        assertFalse(ModShot.confirmedShot(legacy, "minecraft:potato", 10, ItemStack.EMPTY, null));
    }

    @Test
    void 零重力直线射线弹道不除零且不抬高仰角() {
        Vec3 start = new Vec3(0.0, 64.0, 0.0);
        Vec3 target = new Vec3(20.0, 64.0, 0.0);
        ArrowAim.Physics straight = new ArrowAim.Physics(0.0, 1.0, false);

        Vec3 low = ArrowAim.lowArc(start, target, 255.0, 0.0);
        assertNotNull(low);
        assertEquals(1.0, low.x, 1.0e-6);
        assertEquals(0.0, low.y, 1.0e-6, "零重力直线武器不得向上抬角");
        assertEquals(0.0, low.z, 1.0e-6);

        // 零阻力时 directionByTime 走直线匀加速公式，不因 (drag - 1) == 0 返回 null。
        Vec3 byTime = ArrowAim.directionByTime(start, target, 4.0, 5.0, straight);
        assertNotNull(byTime);
        assertEquals(1.0, byTime.x, 1.0e-6);
        assertEquals(0.0, byTime.y, 1.0e-6);
        assertEquals(0.0, byTime.z, 1.0e-6);

        // 瞬发直线射线判定：零重力、无阻力且射程覆盖瞄准上限时为 true，弓箭与慢速弹为 false。
        assertTrue(ArrowAim.isInstantRay(straight, 255.0, 32.0));
        assertFalse(ArrowAim.isInstantRay(straight, 5.0, 32.0));
        assertFalse(ArrowAim.isInstantRay(ArrowAim.ARROW, 255.0, 32.0));
    }
}
