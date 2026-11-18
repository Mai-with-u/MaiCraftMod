// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 经界面存放与机器投料测试：按 #标签 经界面向机器投料能确认送达件数；
 * 机器附加格或漏斗在放进瞬间吃掉物品（passedThrough）时计入已投件数，不重复多投也不误判零件失败。
 */
class StoresInContainerTest {

    private static final BlockPos AT = new BlockPos(4, 64, 4);
    private static final TagKey<Item> GOLD_INGOTS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "ingots/gold"));
    private static final TagKey<Item> BOATS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("minecraft", "boats"));

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void 绑定测试标签() {
        BuiltInRegistries.ITEM.bindTags(Map.of(
                GOLD_INGOTS, List.of(holder(Items.GOLD_INGOT)),
                BOATS, List.of(holder(Items.OAK_BOAT), holder(Items.SPRUCE_BOAT))));
    }

    private static Holder<Item> holder(Item item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
    }

    private static MenuOpening instantOpen(OpenedMenu menu) {
        return new MenuOpening() {
            private boolean opened;
            @Override public ActionStatus tick(TickContext context) {
                opened = true;
                return ActionStatus.done();
            }
            @Override public Optional<OpenedMenu> opened() {
                return opened ? Optional.of(menu) : Optional.empty();
            }
            @Override public String describe() { return "打开测试机器界面"; }
        };
    }

    @Test
    void 经界面按物品标签投料能确认送达并关上界面() {
        ContainerTransferTest.FakeTransferMenu menu = new ContainerTransferTest.FakeTransferMenu(false);
        menu.player.set(0, new ItemStack(Items.GOLD_INGOT, 4));
        MenuFeed feed = new MenuFeed("压印器", AT, "ae2:inscriber", "#c:ingots/gold", 4,
                Permissions.DEFAULT, null, (cell, allowed) -> instantOpen(menu));

        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < 30 && !(last instanceof ActionStatus.Done); i++) {
            last = feed.tick(TickContext.of(i, null));
        }
        assertInstanceOf(ActionStatus.Done.class, last);
        assertEquals(4, feed.delivered());
        assertTrue(menu.closed);
    }

    @Test
    void 快速机器放进瞬间吃掉物品时计入已投件数_不多投也不误报一件没放进去() {
        // 身上有 5 块煤，只需往灌注机附加格投 1 块煤，机器点击瞬间就把这 1 块煤吃成灌注物（容器格仍空）：
        // MenuFeed 经 passedThrough 记入 1 件已送达后立即收工，背包剩 4 块煤，不再多投第 2 轮或误报失败。
        ContainerTransferTest.FakeTransferMenu menu = new ContainerTransferTest.FakeTransferMenu(true);
        menu.player.set(0, new ItemStack(Items.COAL, 5));
        MenuFeed feed = new MenuFeed("冶金灌注机", AT, "mekanism:metallurgic_infuser", "minecraft:coal", 1,
                Permissions.DEFAULT, null, (cell, allowed) -> instantOpen(menu));

        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < 40 && !(last instanceof ActionStatus.Done) && !(last instanceof ActionStatus.Failed); i++) {
            last = feed.tick(TickContext.of(i, null));
        }
        assertInstanceOf(ActionStatus.Done.class, last);
        assertEquals(1, feed.delivered());
        assertEquals(4, menu.player.get(0).getCount(), "只投了要的 1 件，没有因为容器格被吃空再多投一轮");
        assertTrue(!feed.notes().isEmpty(), "留下一条瞬间被抽走或吃掉的说明");
    }
}
