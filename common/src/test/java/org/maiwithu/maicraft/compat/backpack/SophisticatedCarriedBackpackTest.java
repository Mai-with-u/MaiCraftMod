// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
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
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;

/**
 * 随身背包存放与观察的离线测试：未见过的包保底空槽数不死锁、禁套娃、
 * 与取货来源共享同一本存货账本、接口对不上安全退回。
 */
class SophisticatedCarriedBackpackTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
        @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
    }

    private static BackpackView carrying(String... itemIds) {
        List<BackpackStack> stacks = List.of(itemIds).stream()
                .map(itemId -> new BackpackStack(itemId, 1, 64, false, false, false, false)).toList();
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return stacks; }
            @Override public int usedSlots() { return stacks.size(); }
            @Override public int totalSlots() { return 36; }
        };
    }

    private static PlayerServices servicesWith(BackpackView backpack) {
        return new PlayerServices(() -> null, (target, permissions) -> null,
                new Interactions(null),
                itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> null,
                new Protection((dimension, x, y, z) -> Optional.empty(), List::of, name -> Optional.empty(), "self"),
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE,
                backpack, DigToolRule.HELD);
    }

    private static BackpackMenus menusWithCarried(List<BackpackSlot> carried) {
        return new BackpackMenus() {
            @Override public boolean isBackpackMenu(AbstractContainerMenu menu) { return false; }
            @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
                return Optional.empty();
            }
            @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) { return List.of(); }
            @Override public List<BackpackSlot> carriedBackpacks(Player player) { return carried; }
            @Override public void open(BackpackSlot.Location location) { }
        };
    }

    @Test
    void 刚拿到还没开过的包返回保底空槽数_开包后刷新真实剩余空槽() {
        BackpackSlot curioPack = new BackpackSlot("curios", "back", 0, "sophisticatedbackpacks:backpack");
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                menusWithCarried(List.of(curioPack)), () -> NOW);
        SeenBackpackStock seen = new SeenBackpackStock();
        SophisticatedCarriedBackpack carried = new SophisticatedCarriedBackpack(
                compat, servicesWith(carrying("minecraft:flint")), seen, () -> NOW);

        // 刚背上还没开过：绝不返 0，给保底空槽数触发腾格子第一次开包。
        assertEquals(SophisticatedCarriedBackpack.UNSEEN_ASSUMED_FREE_SLOTS, carried.freeSlots());
        assertTrue(carried.store(new BackpackStack("minecraft:flint", 64, 64, false, false, false, false),
                new CollectedRecords()).isPresent());

        // 开包看到里面只剩 4 格空位：立刻按真实 4 格报。
        seen.saw(curioPack.location(), Map.of("minecraft:flint", 128), 4, NOW.minusSeconds(5));
        assertEquals(4, carried.freeSlots());

        // 开包发现已经塞满（0 空槽）且没有圆石可并堆：freeSlots 归 0，圆石不再白开这只包。
        seen.saw(curioPack.location(), Map.of("minecraft:flint", 1728), 0, NOW);
        assertEquals(0, carried.freeSlots());
        assertTrue(carried.store(new BackpackStack("minecraft:cobblestone", 64, 64, false, false, false, true),
                new CollectedRecords()).isEmpty());
    }

    @Test
    void 禁止把精妙背包本身塞进随身背包套娃() {
        BackpackSlot curioPack = new BackpackSlot("curios", "back", 0, "sophisticatedbackpacks:iron_backpack");
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                menusWithCarried(List.of(curioPack)), () -> NOW);
        SophisticatedCarriedBackpack carried = new SophisticatedCarriedBackpack(
                compat, servicesWith(carrying("sophisticatedbackpacks:backpack", "minecraft:flint")),
                new SeenBackpackStock(), () -> NOW);

        assertTrue(carried.store(
                new BackpackStack("sophisticatedbackpacks:backpack", 1, 1, false, false, false, false),
                new CollectedRecords()).isEmpty());
        assertTrue(carried.store(
                new BackpackStack("sophisticatedbackpacks:gold_backpack", 1, 1, false, false, false, false),
                new CollectedRecords()).isEmpty());
    }

    @Test
    void 登记表里取货来源与随身背包存放共享同一份存货账本() {
        BackpackSlot curioPack = new BackpackSlot("curios", "back", 0, "sophisticatedbackpacks:iron_backpack");
        BackpackMenus menus = menusWithCarried(List.of(curioPack));
        SupportedMod<CompatModule> mod = new SupportedMod<>(
                BackpackCompat.MOD_ID, "精妙背包", new VerifiedVersions("3.25.69", "3.27"),
                () -> new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"), menus, () -> NOW));
        CompatRegistry registry = CompatRegistry.load(List.of(mod), loaderWith("3.26.17.2232"));
        PlayerServices services = servicesWith(carrying("minecraft:cobblestone"));

        CarriedBackpack carried = registry.carriedBackpacks(services).getFirst();
        ItemSource source = registry.itemSources(services).getFirst();
        // 同一份 PlayerServices 拿到两个入口；刚背上未开过时 carried() 标记 seen=false。
        assertEquals(1, carried.carried().size());
        assertFalse(carried.carried().getFirst().seen());
        assertEquals("curios:back", carried.carried().getFirst().where());

        // 通过同一个 BackpackCompat 记入存货：两边立刻都能读到新鲜存货与空槽数。
        BackpackCompat registered = (BackpackCompat) registry.modules().getFirst();
        registered.stockFor(services).saw(curioPack.location(), Map.of("minecraft:torch", 24), 19, NOW.minusSeconds(10));

        assertEquals(19, carried.freeSlots());
        CarriedBackpack.CarriedPack summary = carried.carried().getFirst();
        assertTrue(summary.seen());
        assertEquals(19, summary.freeSlots());
        assertEquals(24, summary.contents().get("minecraft:torch"));

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "补火把"),
                        SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT)));
        assertEquals(8, offer.obtainableCount());

        // 超过五分钟后观察摘要回到 seen=false，空槽恢复保底可尝试值。
        registered.stockFor(services).saw(curioPack.location(), Map.of("minecraft:torch", 24), 0,
                NOW.minus(Duration.ofMinutes(6)));
        assertFalse(carried.carried().getFirst().seen());
        assertEquals(SophisticatedCarriedBackpack.UNSEEN_ASSUMED_FREE_SLOTS, carried.freeSlots());
    }

    @Test
    void 读写端碰到签名不兼容时随身背包包装层安全退回零空槽() {
        BackpackMenus brokenMenus = new BackpackMenus() {
            @Override public boolean isBackpackMenu(AbstractContainerMenu menu) { return false; }
            @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
                return Optional.empty();
            }
            @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) { return List.of(); }
            @Override public List<BackpackSlot> carriedBackpacks(Player player) {
                throw new NoSuchMethodError("runOnBackpacks");
            }
            @Override public void open(BackpackSlot.Location location) { }
        };
        SupportedMod<CompatModule> mod = new SupportedMod<>(
                BackpackCompat.MOD_ID, "精妙背包", new VerifiedVersions("3.25.69", "3.27"),
                () -> new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                        brokenMenus, () -> NOW));
        CompatRegistry registry = CompatRegistry.load(List.of(mod), loaderWith("3.26.17"));
        CarriedBackpack carried = registry.carriedBackpacks(servicesWith(carrying("minecraft:flint"))).getFirst();

        assertEquals(0, carried.freeSlots());
        assertTrue(carried.carried().isEmpty());
        assertTrue(carried.store(new BackpackStack("minecraft:flint", 64, 64, false, false, false, false),
                new CollectedRecords()).isEmpty());
        assertFalse(registry.modules().getFirst().active());
    }

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
