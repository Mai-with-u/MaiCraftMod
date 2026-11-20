// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * AE2 物质炮远程武器、上色器直切颜色、熵变仪与充能法杖只读设置离线测试。
 */
class HandheldToolsAndMatterCannonTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 物质炮排除零伤彩弹与低电并给瞬发直线弹道与伤害估算() {
        // 彩弹（paintBall=true）严排，不作为杀伤弹药返回。
        Ae2ToolReads.MatterCannonSnapshot onlyPaint = new Ae2ToolReads.MatterCannonSnapshot(
                200000.0, 200000.0, 0,
                List.of(new Ae2ToolReads.CannonAmmoEntry("ae2:red_paint_ball", 64, 0.0f, true)));
        assertTrue(MatterCannonWeapon.firstLethalShot(onlyPaint).isEmpty());
        assertTrue(MatterCannonWeapon.allLethalShots(onlyPaint).isEmpty());
        assertEquals(0, MatterCannonWeapon.estimateDamage(onlyPaint));

        // 电量低于 1600 AE 不返回可射击方案。
        Ae2ToolReads.MatterCannonSnapshot lowPower = new Ae2ToolReads.MatterCannonSnapshot(
                1599.0, 200000.0, 0,
                List.of(new Ae2ToolReads.CannonAmmoEntry("minecraft:gold_nugget", 16, 196.97f, false)));
        assertTrue(MatterCannonWeapon.firstLethalShot(lowPower).isEmpty());
        assertEquals(0, MatterCannonWeapon.estimateDamage(lowPower));

        // AE2 MatterCannonItem.fireCannon 固定发射首位条目，不会跳过首位彩弹去打后面的金粒：
        // 首位为彩弹 + 次位为金粒时，必须返回空 Shot 与 0 伤害，避免远程战斗空耗电打零伤彩弹。
        Ae2ToolReads.MatterCannonSnapshot paintFirst = new Ae2ToolReads.MatterCannonSnapshot(
                10000.0, 200000.0, 1,
                List.of(
                        new Ae2ToolReads.CannonAmmoEntry("ae2:blue_paint_ball", 32, 0.0f, true),
                        new Ae2ToolReads.CannonAmmoEntry("minecraft:gold_nugget", 16, 196.97f, false)));
        assertTrue(MatterCannonWeapon.firstLethalShot(paintFirst).isEmpty(), "首位彩弹不得跳过发射次位金粒");
        assertTrue(MatterCannonWeapon.allLethalShots(paintFirst).isEmpty());
        assertEquals(0, MatterCannonWeapon.estimateDamage(paintFirst));

        // 首位为金粒（带 1 张加速卡）+ 次位彩弹：下一发为金粒，弹道直线瞬发，伤害 ceil(196.97 * 2 / 20) = 20。
        Ae2ToolReads.MatterCannonSnapshot goldFirst = new Ae2ToolReads.MatterCannonSnapshot(
                10000.0, 200000.0, 1,
                List.of(
                        new Ae2ToolReads.CannonAmmoEntry("minecraft:gold_nugget", 16, 196.97f, false),
                        new Ae2ToolReads.CannonAmmoEntry("ae2:blue_paint_ball", 32, 0.0f, true)));
        RangedWeapon.Shot shot = MatterCannonWeapon.firstLethalShot(goldFirst).orElseThrow();
        assertEquals("minecraft:gold_nugget", shot.ammoItem());
        assertEquals(-1, shot.ammoSlot());
        assertEquals(0, shot.chargeTicks());
        assertEquals(0, shot.cooldownTicks());
        assertEquals(0.0, shot.ballistics().gravity());
        assertEquals(1.0, shot.ballistics().drag());
        assertEquals(MatterCannonWeapon.RAY_RANGE, shot.ballistics().speed());
        assertEquals(20, MatterCannonWeapon.estimateDamage(goldFirst));
    }

    @Test
    void 物质炮经内部电量或内部弹药数减少确认已发射() {
        ItemStack beforeEnergy = new ItemStack(Items.CROSSBOW, 1);
        ItemStack afterEnergy = new ItemStack(Items.BOW, 1);
        ItemStack same = new ItemStack(Items.CROSSBOW, 1);

        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<EnergyReading> readEnergy(ItemStack stack) {
                return Optional.of(stack.is(Items.CROSSBOW)
                        ? new EnergyReading(20000.0, 200000.0)
                        : new EnergyReading(18400.0, 200000.0));
            }

            @Override public long matterCannonTotalAmmo(ItemStack stack) {
                return stack.is(Items.TRIDENT) ? 15L : 16L;
            }
        };
        MatterCannonWeapon weapon = new MatterCannonWeapon(ToolTestFakes.compat(), reads,
                stack -> stack.is(Items.CROSSBOW) || stack.is(Items.BOW) || stack.is(Items.TRIDENT));

        assertTrue(weapon.usesInternalAmmo());
        assertTrue(weapon.shotFired(beforeEnergy, afterEnergy, null), "电量减少应确认已发射");
        assertFalse(weapon.shotFired(beforeEnergy, same, null), "电量与弹药均未减少不得确认");
        // 即使电量不变（如创造充能场景），只要内部弹药减少也确认发射。
        Ae2ToolReads ammoOnlyReads = new Ae2ToolReads() {
            @Override public Optional<EnergyReading> readEnergy(ItemStack stack) {
                return Optional.of(new EnergyReading(20000.0, 200000.0));
            }

            @Override public long matterCannonTotalAmmo(ItemStack stack) {
                return stack.is(Items.TRIDENT) ? 15L : 16L;
            }
        };
        MatterCannonWeapon ammoWeapon = new MatterCannonWeapon(ToolTestFakes.compat(), ammoOnlyReads,
                stack -> stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT));
        assertTrue(ammoWeapon.shotFired(beforeEnergy, new ItemStack(Items.TRIDENT), null));
    }

    @Test
    void 上色器规范颜色别名并暴露电量颜色余量与直切动作() {
        assertEquals(Optional.of("transparent"), ColorApplicatorSettings.normalizeColor("fluix"));
        assertEquals(Optional.of("transparent"), ColorApplicatorSettings.normalizeColor("TRANSPARENT"));
        assertEquals(Optional.of("red"), ColorApplicatorSettings.normalizeColor("Red"));
        assertTrue(ColorApplicatorSettings.normalizeColor("rainbow").isEmpty());

        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<ColorApplicatorSnapshot> readColorApplicator(ItemStack stack) {
                return Optional.of(new ColorApplicatorSnapshot(
                        12000.0, 20000.0, "blue", Map.of("blue", 64L, "transparent", 16L)));
            }
        };
        ColorApplicatorSettings settings = new ColorApplicatorSettings(
                ToolTestFakes.compat(),
                reads,
                ToolTestFakes.services(() -> null, Map.of(), List.of()),
                stack -> stack.is(Items.BRUSH) ? ColorApplicatorSettings.COLOR_APPLICATOR : "");

        ItemStack brush = new ItemStack(Items.BRUSH);
        Map<String, String> map = asMap(settings.settings(brush));
        assertEquals("12000", map.get("energy"));
        assertEquals("20000", map.get("energy_max"));
        assertEquals("blue", map.get("active_color"));
        assertEquals("blue", map.get("color"));
        assertTrue(map.get("pigments").contains("blue=64"));
        assertTrue(map.get("pigments").contains("transparent=16"));

        assertTrue(settings.holds(brush, "color", "blue"));
        assertFalse(settings.holds(brush, "color", "red"));
        assertTrue(settings.change(0, Map.of("color", "transparent"), Permissions.DEFAULT).isPresent());
        assertTrue(settings.change(0, Map.of("energy", "20000"), Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 熵变仪物质炮与充能法杖只读设置展示电量弹药与充能器补电提示() {
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<EnergyReading> readEnergy(ItemStack stack) {
                if (stack.is(Items.FLINT_AND_STEEL)) return Optional.of(new EnergyReading(800.0, 200000.0));
                if (stack.is(Items.BLAZE_ROD)) return Optional.of(new EnergyReading(200.0, 8000.0));
                return Optional.empty();
            }

            @Override public Optional<MatterCannonSnapshot> readMatterCannon(ItemStack stack, Level level) {
                return Optional.of(new MatterCannonSnapshot(50000.0, 200000.0, 0,
                        List.of(new CannonAmmoEntry("minecraft:iron_nugget", 32, 55.85f, false))));
            }
        };
        PoweredToolSettings settings = new PoweredToolSettings(
                ToolTestFakes.compat(),
                reads,
                ToolTestFakes.services(() -> null, Map.of(), List.of()),
                stack -> stack.is(Items.FLINT_AND_STEEL) ? PoweredToolSettings.ENTROPY_MANIPULATOR
                        : stack.is(Items.CROSSBOW) ? MatterCannonWeapon.MATTER_CANNON
                        : stack.is(Items.BLAZE_ROD) ? PoweredToolSettings.CHARGED_STAFF
                        : "");

        List<MachineSetting> entropy = settings.settings(new ItemStack(Items.FLINT_AND_STEEL));
        assertEquals("800", asMap(entropy).get("energy"));
        assertTrue(entropy.getFirst().description().contains("1600 AE"));
        assertTrue(entropy.getFirst().description().contains("充能器"));

        List<MachineSetting> cannon = settings.settings(new ItemStack(Items.CROSSBOW));
        Map<String, String> cannonMap = asMap(cannon);
        assertEquals("50000", cannonMap.get("energy"));
        assertEquals("minecraft:iron_nugget", cannonMap.get("ammo"));
        assertEquals("32", cannonMap.get("ammo_count"));
        assertEquals("3", cannonMap.get("estimated_damage"));
        assertTrue(cannon.get(2).description().contains("ME 箱子"));

        List<MachineSetting> staff = settings.settings(new ItemStack(Items.BLAZE_ROD));
        assertEquals("200", asMap(staff).get("energy"));
        assertEquals("8000", asMap(staff).get("energy_max"));
        assertTrue(staff.getFirst().description().contains("300 AE"));
        assertTrue(staff.getFirst().description().contains("6 点魔法伤害"));
        assertTrue(settings.change(0, Map.of("energy", "8000"), Permissions.DEFAULT).isEmpty());
    }

    private static Map<String, String> asMap(List<MachineSetting> list) {
        Map<String, String> map = new LinkedHashMap<>();
        for (MachineSetting setting : list) map.put(setting.key(), setting.value());
        return map;
    }
}
