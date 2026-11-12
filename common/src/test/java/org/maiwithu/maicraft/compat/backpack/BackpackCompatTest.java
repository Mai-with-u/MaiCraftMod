// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ModApiMismatch;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** 精妙背包的联动读数：认出各栏位背包、精确发打开请求、读写端对不上就停用、装了且版本在范围内才登记。 */
class BackpackCompatTest {

    /** 界面读写端的替身：测试不真开背包界面。 */
    private final BackpackMenus menus = new BackpackMenus() {
        @Override public boolean isBackpackMenu(AbstractContainerMenu menu) { return false; }
        @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
            return Optional.empty();
        }
        @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) {
            return List.of();
        }
        @Override public void open(BackpackSlot.Location location) { }
    };

    /** 背包视图的替身：只有几格固定的东西。 */
    private static BackpackView carrying(String... itemIds) {
        List<BackpackStack> stacks = List.of(itemIds).stream()
                .map(itemId -> new BackpackStack(itemId, 1, 64, false, false, false, false)).toList();
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return stacks; }
            @Override public int usedSlots() { return stacks.size(); }
            @Override public int totalSlots() { return 36; }
        };
    }

    @Test
    void 认出身上哪几格是精妙背包() {
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                menus, Instant::now);
        List<BackpackStack> found = compat.carriedBackpacks(carrying(
                "minecraft:cobblestone", "sophisticatedbackpacks:iron_backpack", "minecraft:torch",
                "sophisticatedbackpacks:backpack"));
        assertEquals(List.of("sophisticatedbackpacks:iron_backpack", "sophisticatedbackpacks:backpack"),
                found.stream().map(BackpackStack::itemId).toList());
        assertTrue(compat.active());
    }

    @Test
    void 按结构化栏位枚举饰品栏胸甲与副手背包并原样发打开请求() {
        List<BackpackSlot.Location> opened = new ArrayList<>();
        List<BackpackSlot> equipped = List.of(
                new BackpackSlot("curios", "back", 0, "sophisticatedbackpacks:diamond_backpack"),
                new BackpackSlot("armor", "", 0, "sophisticatedbackpacks:iron_backpack"),
                new BackpackSlot("offhand", "", 0, "sophisticatedbackpacks:backpack"));
        BackpackMenus slotMenus = new BackpackMenus() {
            @Override public boolean isBackpackMenu(AbstractContainerMenu menu) { return false; }
            @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
                return Optional.empty();
            }
            @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) { return List.of(); }
            @Override public List<BackpackSlot> carriedBackpacks(Player player) { return equipped; }
            @Override public void open(BackpackSlot.Location location) { opened.add(location); }
        };
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                slotMenus, Instant::now);

        List<BackpackSlot> found = compat.carriedSlots(() -> null, carrying("minecraft:cobblestone"));
        assertEquals(equipped, found);
        assertEquals("curios:back", found.get(0).location().whereCode());
        assertEquals("chest", found.get(1).location().whereCode());
        assertEquals("offhand", found.get(2).location().whereCode());

        for (BackpackSlot pack : found) {
            compat.openBackpack(pack.location());
        }
        // 胸甲与副手传给开包请求的都是该栏位内从 0 起算的下标，不混入原版格号 38 或 40。
        assertEquals(List.of(
                new BackpackSlot.Location("curios", "back", 0),
                new BackpackSlot.Location("armor", "", 0),
                new BackpackSlot.Location("offhand", "", 0)), opened);
    }

    @Test
    void 读写端的类对不上时停用并抛模组接口对不上() {
        BackpackCompat compat = new BackpackCompat(itemId -> { throw new NoClassDefFoundError("BackpackItem"); },
                menus, Instant::now);
        assertThrows(ModApiMismatch.class, () -> compat.carriedBackpacks(carrying("minecraft:torch")));
        assertFalse(compat.active());
        assertTrue(compat.disabledReason().orElseThrow().contains("认背包物品"));
    }

    @Test
    void 装了且版本在范围内才登记() {
        SupportedMod<CompatModule> supported = new SupportedMod<>(
                BackpackCompat.MOD_ID, "精妙背包", new VerifiedVersions("3.25.69", "3.27"),
                () -> new BackpackCompat(itemId -> false, menus, Instant::now));
        CompatRegistry baseline = CompatRegistry.load(List.of(supported), loaderWith("3.25.69"));
        assertEquals(List.of(BackpackCompat.MOD_ID),
                baseline.modules().stream().map(CompatModule::modId).toList());
        assertEquals("精妙背包（sophisticatedbackpacks）：已登记，版本 3.25.69", baseline.decisions().getFirst());

        CompatRegistry livePack = CompatRegistry.load(List.of(supported), loaderWith("3.26.17.2232"));
        assertEquals(List.of(BackpackCompat.MOD_ID),
                livePack.modules().stream().map(CompatModule::modId).toList());
        assertEquals("精妙背包（sophisticatedbackpacks）：已登记，版本 3.26.17.2232", livePack.decisions().getFirst());

        CompatRegistry newer = CompatRegistry.load(List.of(supported), loaderWith("3.27.0"));
        assertTrue(newer.modules().isEmpty());
        assertTrue(newer.decisions().getFirst().contains("不登记"), newer.decisions().getFirst());
    }

    /** 只装了精妙背包、版本给定的加载器环境替身。 */
    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(BackpackCompat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of(version) : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }
}
