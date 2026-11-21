// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ExternalKeyWrappers;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 应用机械独立联动入口与外部键包装接缝离线测试：
 * 验证正常登记、不进随身背包、外部键注册与停用/回滚隔离安全。
 */
class AppMekCompatTest {

    @BeforeAll
    static void 初始化注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 外部键包装表支持登记查询包装撤销且只读空表不可修改() {
        assertFalse(ExternalKeyWrappers.NONE.supports(AppMekCompat.CHEMICAL_KEY_TYPE));
        assertTrue(ExternalKeyWrappers.NONE.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 1000L).isEmpty());
        assertThrows(UnsupportedOperationException.class, () ->
                ExternalKeyWrappers.NONE.register(AppMekCompat.CHEMICAL_KEY_TYPE, (id, amt) -> Optional.empty()));

        ExternalKeyWrappers wrappers = new ExternalKeyWrappers();
        AtomicBoolean available = new AtomicBoolean(true);
        wrappers.register(AppMekCompat.CHEMICAL_KEY_TYPE, available::get,
                (id, amt) -> "mekanism:hydrogen".equals(id)
                        ? Optional.of(new ItemStack(Items.GLASS_BOTTLE)) : Optional.empty());

        assertTrue(wrappers.supports("APPMEK:CHEMICAL"));
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 1000L).isPresent());
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 0L).isEmpty());
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:unknown", 1000L).isEmpty());

        // 可用性变为否时立即不可查也不可包装。
        available.set(false);
        assertFalse(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE));
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 1000L).isEmpty());

        available.set(true);
        wrappers.unregister(AppMekCompat.CHEMICAL_KEY_TYPE);
        assertFalse(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE));
    }

    @Test
    void 正常登记贡献布局设置知识与外部键且绝不登记随身背包() {
        ExternalKeyWrappers wrappers = new ExternalKeyWrappers();
        AppMekReads reads = new AppMekReads() {
            @Override public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
                return "mekanism:hydrogen".equals(chemicalId)
                        ? Optional.of(new ItemStack(Items.PAPER)) : Optional.empty();
            }

            @Override public Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
                return Optional.empty();
            }

            @Override public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
                return false;
            }
        };
        CompatRegistry registry = CompatRegistry.load(List.of(
                new SupportedMod<>(AppMekCompat.MOD_ID, "应用机械", new VerifiedVersions("1.6.3", "1.7"),
                        () -> new AppMekCompat(reads, wrappers))),
                loaderWith(Map.of(AppMekCompat.MOD_ID, "1.6.3")));

        assertEquals(List.of(AppMekCompat.MOD_ID), registry.installedModIds());
        assertTrue(registry.whyNotWorking(AppMekCompat.MOD_ID).isEmpty());

        PlayerServices services = offlineServices();
        assertEquals(1, registry.itemSettings(services).size());
        assertEquals(1, registry.knowledgeSources().size());
        assertTrue(registry.carriedBackpacks(services).isEmpty(), "便携化学品元件绝不能注册进随身背包");
        assertTrue(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE));
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 1000L).isPresent());
    }

    @Test
    void 运行期接口对不上停用后立即撤回外部键包装且不抛异常出去() {
        ExternalKeyWrappers wrappers = new ExternalKeyWrappers();
        AppMekReads broken = new AppMekReads() {
            @Override public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
                throw new NoSuchMethodError("simulated appmek api drift");
            }

            @Override public Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
                return Optional.empty();
            }

            @Override public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
                return false;
            }
        };
        AppMekCompat compat = new AppMekCompat(broken, wrappers);
        CompatRegistry.load(List.of(
                new SupportedMod<>(AppMekCompat.MOD_ID, "应用机械", new VerifiedVersions("1.6.3", "1.7"), () -> compat)),
                loaderWith(Map.of(AppMekCompat.MOD_ID, "1.6.3")));

        assertTrue(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE));
        assertTrue(wrappers.wrap(AppMekCompat.CHEMICAL_KEY_TYPE, "mekanism:hydrogen", 500L).isEmpty());
        assertFalse(compat.active(), "遇到 LinkageError 后应停用应用机械联动");
        assertFalse(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE), "停用后外部键包装应立即失效");
    }

    @Test
    void 登记中途碰撞回滚时不会残留外部键包装器() {
        ExternalKeyWrappers wrappers = new ExternalKeyWrappers();
        CompatModule conflict = new CompatModule("other", "冲突占位") {
            @Override public void contribute(CompatRegistry registry) {
                registry.menuLayout(this, new MenuLayoutProof() {
                    @Override public Set<String> menuTypes() {
                        return Set.of(AppMekPortableCellLayout.MENU_PORTABLE_CHEMICAL_CELL);
                    }

                    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots,
                                                                List<Integer> otherSlots) {
                        return new MenuLayout.Supported(playerSlots, List.of(), Set.of());
                    }
                });
            }
        };
        CompatRegistry registry = CompatRegistry.load(List.of(
                new SupportedMod<>("other", "冲突占位", new VerifiedVersions("1.0", "2.0"), () -> conflict),
                new SupportedMod<>(AppMekCompat.MOD_ID, "应用机械", new VerifiedVersions("1.6.3", "1.7"),
                        () -> new AppMekCompat(AppMekReads.NONE, wrappers))),
                loaderWith(Map.of("other", "1.0", AppMekCompat.MOD_ID, "1.6.3")));

        assertFalse(registry.installedModIds().contains(AppMekCompat.MOD_ID));
        assertFalse(wrappers.supports(AppMekCompat.CHEMICAL_KEY_TYPE), "登记失败回滚后不能残留化学品包装器");
    }

    private static LoaderEnvironment loaderWith(Map<String, String> versions) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "neoforge"; }
            @Override public boolean isModLoaded(String modId) { return versions.containsKey(modId); }
            @Override public Optional<String> modVersion(String modId) { return Optional.ofNullable(versions.get(modId)); }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return itemId -> 0; }
        };
    }

    private static PlayerServices offlineServices() {
        Protection protection = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        GearChanges noGear = new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
            @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
        };
        BackpackView emptyBackpack = new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), noGear, inner -> inner, (request, permissions) -> null, protection,
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, emptyBackpack,
                DigToolRule.HELD);
    }
}
