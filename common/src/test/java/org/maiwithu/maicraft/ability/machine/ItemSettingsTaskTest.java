// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 物品设置读回核对测试：验证主手物品耗尽耐久碎裂变空时仍交由物品设置核对背包产出并写明已消耗完，
 * 同时保证旧实现遇空堆叠、主手换别种物品及角色不在世界时都不会误放行。
 */
class ItemSettingsTaskTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 主手物品耗尽碎裂变空时经带角色核对判成功并说明已消耗完() {
        // 模拟石英切割刀最后一点耐久刻名后碎裂：主手已变空，但物品设置能结合角色背包确认产出了命名模板。
        ItemSettings knifeSettings = new ItemSettings() {
            @Override public boolean covers(ItemStack stack) { return !stack.isEmpty(); }
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
        Map<String, String> before = Map.of("engrave_name", "none");
        Map<String, String> wanted = Map.of("engrave_name", "iron_gear");

        assertTrue(ItemSettingsTask.heldMatches("minecraft:iron_sword", ItemStack.EMPTY, null, knifeSettings, wanted));
        ItemSettingsTask.Readback result = ItemSettingsTask.evaluateReadback(
                "minecraft:iron_sword", ItemStack.EMPTY, null, true, knifeSettings, before, wanted);
        assertFalse(result.inHand());
        assertTrue(result.wrong().isEmpty(), "切割刀碎裂后只要带角色核对通过，不应再判失败");
        assertEquals("改了设置：原来第 5 格那件，操作中已消耗完", result.changeNote(5));
    }

    @Test
    void 旧实现遇空堆叠或主手换别种物品以及角色离线时一律不误放行() {
        // 模拟只读物品自身数据的旧实现（如过滤器）：空堆叠没有规则，三参数默认 holds 恒回 false。
        ItemSettings legacyFilter = new ItemSettings() {
            @Override public boolean covers(ItemStack stack) { return stack.is(Items.PAPER); }
            @Override public List<MachineSetting> settings(ItemStack stack) {
                if (stack.isEmpty()) return List.of();
                return List.of(new MachineSetting("filter.mode", "allow", List.of("allow", "deny"), "模式"));
            }
            @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
                return Optional.empty();
            }
        };
        Map<String, String> before = Map.of("filter.mode", "deny");
        Map<String, String> wanted = Map.of("filter.mode", "allow");

        assertFalse(ItemSettingsTask.heldMatches("minecraft:paper", ItemStack.EMPTY, null, legacyFilter, wanted));
        ItemSettingsTask.Readback emptyReadback = ItemSettingsTask.evaluateReadback(
                "minecraft:paper", ItemStack.EMPTY, null, true, legacyFilter, before, wanted);
        assertFalse(emptyReadback.wrong().isEmpty(), "旧实现遇空堆叠绝不误判成功");

        // 即使设置实现恒回 true，主手拿着别种无关物品（非目标且非空）或角色离线时也必须拒认。
        ItemSettings alwaysTrue = new ItemSettings() {
            @Override public boolean covers(ItemStack stack) { return true; }
            @Override public List<MachineSetting> settings(ItemStack stack) {
                return List.of(new MachineSetting("filter.mode", "allow", List.of("allow", "deny"), "模式"));
            }
            @Override public boolean holds(ItemStack stack, Player player, String key, String wantedValue) {
                return true;
            }
            @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
                return Optional.empty();
            }
        };
        ItemStack otherItem = new ItemStack(Items.COBBLESTONE);
        assertFalse(ItemSettingsTask.heldMatches("minecraft:paper", otherItem, null, alwaysTrue, wanted));
        assertFalse(ItemSettingsTask.evaluateReadback(
                "minecraft:paper", otherItem, null, true, alwaysTrue, before, wanted).wrong().isEmpty());
        assertFalse(ItemSettingsTask.evaluateReadback(
                "minecraft:paper", ItemStack.EMPTY, null, false, alwaysTrue, before, wanted).wrong().isEmpty());

        // 目标物品仍在主手且核对通过时，变更说明保持"现在在主手"。
        ItemStack paper = new ItemStack(Items.PAPER);
        assertTrue(ItemSettingsTask.heldMatches("minecraft:paper", paper, null, legacyFilter, wanted));
        ItemSettingsTask.Readback inHandResult = ItemSettingsTask.evaluateReadback(
                "minecraft:paper", paper, null, true, legacyFilter, before, wanted);
        assertTrue(inHandResult.inHand());
        assertTrue(inHandResult.wrong().isEmpty());
        assertEquals("改了设置：原来第 2 格那件，现在在主手", inHandResult.changeNote(2));
    }
}
