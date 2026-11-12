// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 精妙背包的联动入口。角色从随身背包里取东西、腾地方往随身背包里存东西都经这里碰模组：
 * 枚举身上全部栏位（饰品栏、胸甲、副手、主背包）里的精妙背包、认点开的背包界面、读主存储、发精确打开请求，
 * 每一下都经联动入口包一层，装的模组和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>同一趟进世界里的取货来源与随身背包存放共享同一份 {@link SeenBackpackStock}，换世界重建玩家行为时自动换新账本。
 */
public final class BackpackCompat extends CompatModule {

    public static final String MOD_ID = "sophisticatedbackpacks";

    private final BackpackItems items;
    private final BackpackMenus menus;
    private final Supplier<Instant> clock;
    /** 当前这一趟进世界所绑定的玩家行为与共用的开包存货账本；换世界时跟着更新。 */
    private PlayerServices activeServices;
    private SeenBackpackStock activeStock;

    public BackpackCompat(BackpackItems items, BackpackMenus menus, Supplier<Instant> clock) {
        super(MOD_ID, "精妙背包");
        this.items = Objects.requireNonNull(items, "items");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 随身背包来源与腾地方存放共用同一份开包存货账本：取货或存货开过包，另一边马上知道里面有什么、还剩几格空位。
        registry.itemSource(this, services ->
                new SophisticatedBackpackSource(this, services, stockFor(services), clock));
        registry.carriedBackpack(this, services ->
                new SophisticatedCarriedBackpack(this, services, stockFor(services), clock));
    }

    /** 同一份玩家行为（同一趟进世界）共用同一本开包存货记录，换世界新建玩家行为时换新账本。 */
    synchronized SeenBackpackStock stockFor(PlayerServices services) {
        if (services != activeServices || activeStock == null) {
            activeServices = services;
            activeStock = new SeenBackpackStock();
        }
        return activeStock;
    }

    /** 这个物品注册 ID 是不是精妙背包。 */
    public boolean isBackpackItem(String itemId) {
        return call("认背包物品", () -> items.isBackpack(itemId));
    }

    /** 角色主背包视图里放着精妙背包的物品堆；认物品经读写端，接口对不上时停用联动。 */
    public List<BackpackStack> carriedBackpacks(BackpackView backpack) {
        List<BackpackStack> found = new ArrayList<>();
        for (BackpackStack stack : backpack.stacks()) {
            if (isBackpackItem(stack.itemId())) {
                found.add(stack);
            }
        }
        return List.copyOf(found);
    }

    /** 玩家身上全部栏位（饰品栏、胸甲、副手、主背包）里的精妙背包定位列表。 */
    public List<BackpackSlot> carriedSlots(Player player) {
        return call("扫随身背包栏位", () -> menus.carriedBackpacks(player));
    }

    /**
     * 汇总角色身上的全部精妙背包：有真实本地玩家或界面替身给出了结构化栏位时直接采用（覆盖饰品栏、胸甲、副手与主背包）；
     * 离线测试只喂了主背包视图时退回按主背包视图清点。
     */
    public List<BackpackSlot> carriedSlots(Supplier<PlayerContext> playerContext, BackpackView fallbackBackpack) {
        PlayerContext current = playerContext.get();
        Player player = current == null ? null : current.localPlayer();
        List<BackpackSlot> fromMenus = carriedSlots(player);
        if (!fromMenus.isEmpty() || player != null) {
            return fromMenus;
        }
        List<BackpackStack> mainStacks = carriedBackpacks(fallbackBackpack);
        List<BackpackSlot> fallback = new ArrayList<>(mainStacks.size());
        for (int i = 0; i < mainStacks.size(); i++) {
            fallback.add(new BackpackSlot("main", "", i, mainStacks.get(i).itemId()));
        }
        return List.copyOf(fallback);
    }

    /** 当前是否拿得到每只包真实的结构化栏位定位（实机有本地玩家，或测试替身显式给了栏位列表）。 */
    boolean hasExactSlotLocations(Supplier<PlayerContext> playerContext) {
        PlayerContext current = playerContext.get();
        if (current != null && current.localPlayer() != null) {
            return true;
        }
        return !carriedSlots((Player) null).isEmpty();
    }

    /** 点开的界面是不是角色顶层精妙背包的界面。 */
    public boolean isBackpackMenu(AbstractContainerMenu menu) {
        return call("认背包界面", () -> menus.isBackpackMenu(menu));
    }

    /** 背包界面的升级占列信息是否已经同步完。 */
    public boolean isMenuSynchronized(AbstractContainerMenu menu) {
        return call("核对背包列同步", () -> menus.isSynchronized(menu));
    }

    /** 背包界面的可用主存储槽位号；无限格升级或异常大小给空。 */
    public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
        return call("读背包主存储槽位", () -> menus.mainStorageSlotIds(menu));
    }

    /** 背包界面里角色主背包一侧的槽位号。 */
    public List<Integer> playerSlotIds(AbstractContainerMenu menu) {
        return call("读背包玩家侧槽位", () -> menus.playerSlotIds(menu));
    }

    /** 主存储这一格能不能放进这堆物品。 */
    public boolean canStoreInSlot(AbstractContainerMenu menu, int slotId, ItemStack stack) {
        return call("核对背包槽位可放入", () -> menus.canStoreInSlot(menu, slotId, stack));
    }

    /** 主存储这一格是不是没设记忆筛选的通用空槽。 */
    public boolean isUnfilteredEmptySlot(AbstractContainerMenu menu, int slotId) {
        return call("核对背包空槽筛选", () -> menus.isUnfilteredEmptySlot(menu, slotId));
    }

    /** 按结构化栏位定位请服务端打开指定那只随身背包。 */
    public void openBackpack(BackpackSlot.Location location) {
        call("发打开背包请求", () -> {
            menus.open(location);
            return null;
        });
    }
}
