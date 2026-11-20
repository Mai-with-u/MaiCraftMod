// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动包装层对新增公共接缝方法的转发核对：按面部件设置、带角色物品核对与内部弹药武器确认；
 * 模组活跃时原样转发，停用或接口不匹配时收住并回退安全空值。
 */
class CompatSpiForwardingTest {

    private static final BlockPos HOST = new BlockPos(4, 64, 4);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CompatModule activeModule() {
        return new CompatModule("testmod", "测试模组") {
            @Override public void contribute(CompatRegistry registry) {
            }
        };
    }

    private static void disable(CompatModule module) {
        assertThrows(ModApiMismatch.class, () -> module.call("触发停用", () -> {
            throw new NoSuchMethodError("test.missingMethod");
        }));
    }

    @Test
    void 机器设置的按面部件设置正常转发并在停用后回空() {
        CompatModule module = activeModule();
        MachineSettings raw = new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) { return List.of(); }
            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return Optional.empty();
            }
            @Override public List<MachineSetting> partSettings(BlockPos host, Direction side) {
                return side == Direction.NORTH
                        ? List.of(new MachineSetting("redstone_mode", "ignore", List.of("ignore", "high"), "红石模式"))
                        : List.of();
            }
        };
        CompatMachineSettings wrapped = new CompatMachineSettings(module, raw);

        List<MachineSetting> north = wrapped.partSettings(HOST, Direction.NORTH);
        assertEquals(1, north.size());
        assertEquals("redstone_mode", north.getFirst().key());
        assertTrue(wrapped.partSettings(HOST, Direction.SOUTH).isEmpty());

        disable(module);
        assertTrue(wrapped.partSettings(HOST, Direction.NORTH).isEmpty(), "停用后按面部件设置必须回空");
    }

    @Test
    void 物品设置带角色核对正常转发且默认回退到三参数版本() {
        CompatModule module = activeModule();
        ItemSettings withPlayer = new ItemSettings() {
            @Override public boolean covers(ItemStack stack) { return true; }
            @Override public List<MachineSetting> settings(ItemStack stack) {
                return List.of(new MachineSetting("engrave_name", "none", List.of(), "刻名"));
            }
            @Override public boolean holds(ItemStack stack, Player player, String key, String wanted) {
                return key.equals("engrave_name") && wanted.equals("iron_gear");
            }
            @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
                return Optional.empty();
            }
        };
        CompatItemSettings wrapped = new CompatItemSettings(module, withPlayer);
        assertTrue(wrapped.holds(ItemStack.EMPTY, null, "engrave_name", "iron_gear"));
        assertFalse(wrapped.holds(ItemStack.EMPTY, null, "engrave_name", "other"));

        // 未重写四参数重载的旧实现应默认回退到三参数 holds。
        ItemSettings legacy = new ItemSettings() {
            @Override public boolean covers(ItemStack stack) { return true; }
            @Override public List<MachineSetting> settings(ItemStack stack) {
                return List.of(new MachineSetting("mode", "whitelist", List.of("whitelist", "blacklist"), "模式"));
            }
            @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
                return Optional.empty();
            }
        };
        assertTrue(legacy.holds(ItemStack.EMPTY, null, "mode", "whitelist"));
        assertFalse(legacy.holds(ItemStack.EMPTY, null, "mode", "blacklist"));

        disable(module);
        assertFalse(wrapped.holds(ItemStack.EMPTY, null, "engrave_name", "iron_gear"), "停用后回 false");
    }

    @Test
    void 远程武器内部弹药与发射确认正常转发并在停用后回假() {
        CompatModule module = activeModule();
        RangedWeapon raw = new RangedWeapon() {
            @Override public boolean covers(ItemStack weapon) { return true; }
            @Override public Optional<Shot> nextShot(ItemStack weapon, Player player) { return Optional.empty(); }
            @Override public List<Shot> possibleShots(ItemStack weapon, Player player) { return List.of(); }
            @Override public boolean usesInternalAmmo() { return true; }
            @Override public boolean shotFired(ItemStack before, ItemStack after, Player player) {
                return before.getCount() > after.getCount();
            }
        };
        CompatRangedWeapon wrapped = new CompatRangedWeapon(module, raw);

        assertTrue(wrapped.usesInternalAmmo());
        assertTrue(wrapped.shotFired(new ItemStack(Items.STICK, 2), new ItemStack(Items.STICK, 1), null));
        assertFalse(wrapped.shotFired(new ItemStack(Items.STICK, 2), new ItemStack(Items.STICK, 2), null));

        disable(module);
        assertFalse(wrapped.usesInternalAmmo(), "停用后不报内部弹药");
        assertFalse(wrapped.shotFired(new ItemStack(Items.STICK, 2), new ItemStack(Items.STICK, 1), null), "停用后不确认发射");
    }
}
