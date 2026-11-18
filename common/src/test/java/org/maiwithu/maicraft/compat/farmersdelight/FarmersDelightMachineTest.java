// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 农夫乐事厨锅的认领与投取：认领厨锅方块、界面九格布局、投料收餐都点开界面、
 * 读数如实给供热与做好的餐；别的方块不认领，读不出时不猜。
 */
class FarmersDelightMachineTest {

    private static final BlockPos AT = new BlockPos(2, 64, 2);

    private static final FarmersDelightReads STUB_READS = new FarmersDelightReads() {
        @Override public Optional<List<MachineState.Shown>> meal(ClientLevel level, BlockPos at) {
            return at.equals(AT)
                    ? Optional.of(List.of(new MachineState.Shown("farmersdelight:vegetable_soup", 1)))
                    : Optional.empty();
        }

        @Override public Optional<Boolean> heated(ClientLevel level, BlockPos at) {
            if (at.equals(AT)) return Optional.of(false);
            return Optional.empty();
        }
    };

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FarmersDelightCompat compat() {
        return new FarmersDelightCompat(STUB_READS);
    }

    private static FarmersDelightMachineType type() {
        FarmersDelightMachineCatalog.Kind kind = FarmersDelightMachineCatalog.kinds().getFirst();
        return new FarmersDelightMachineType(kind, compat(), STUB_READS, offlineServices());
    }

    /** 不在世界里的玩家行为：角色为空，靠近与交互都碰不到；够建出机器类型、判投取动作给不给。 */
    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                        return Optional.empty();
                    }
                    @Override public Optional<Action> takeOff(GearSlotName slot) {
                        return Optional.empty();
                    }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    @Test
    void 目录登记的就是厨锅那一格_别的不认领() {
        FarmersDelightMachineCatalog.Kind kind = FarmersDelightMachineCatalog.kinds().getFirst();
        assertEquals("farmersdelight:cooking_pot", kind.id());
        assertEquals("农夫乐事厨锅", kind.name());
        assertEquals(MachineRole.PROCESSING, kind.role());
        FarmersDelightMachineType type = type();
        assertEquals("farmersdelight:cooking_pot", type.id());
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertFalse(type.covers(stone), "石头不该被厨锅认领");
        assertFalse(compat().blocksOf(List.of("farmersdelight:not_a_block")).contains(stone.getBlock()),
                "注册表里没有的 ID 不算数");
    }

    @Test
    void 界面九格证明得了_全部按机器格核对() {
        MenuLayout.Layout layout = new FarmersDelightCookingPotLayout().classify(slots(9), range(9, 44), range(0, 8));
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, layout);
        assertEquals(9, supported.containerSlots().size());
        assertEquals(9, supported.machineSlots().size(), "食材格到成品格都按机器格核对，不当普通存取格搬动");
        assertEquals(36, supported.playerSlots().size());
        assertInstanceOf(MenuLayout.Unsupported.class, new FarmersDelightCookingPotLayout().classify(slots(4),
                range(4, 39), range(0, 3)), "容器侧不是九格证明不了");
    }

    /** 前容器格后背包格的界面替身（厨锅界面是前九格工作、后三十六格背包）。 */
    private static MenuSlots slots(int containerCount) {
        int total = containerCount + 36;
        return new MenuSlots() {
            @Override public String menuTypeId() { return "farmersdelight:cooking_pot"; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot >= containerCount; }
            @Override public int playerInventoryIndex(int slot) { return slot - containerCount; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    @Test
    void 投料收餐都给点开界面的动作() {
        assertTrue(type().feed(AT, new FeedPlan.Entry("farmersdelight:raw_pasta", 3, false), Permissions.DEFAULT).orElseThrow()
                instanceof MenuFeed, "投料走点开界面放槽");
        assertTrue(type().take(AT, "farmersdelight:vegetable_soup", 1, Permissions.DEFAULT).orElseThrow()
                instanceof MenuOutputLook, "收餐是点开界面把餐搬进背包");
    }

    @Test
    void 供热与做好的餐经联动入口转发读写接缝_读不出不猜() {
        FarmersDelightCompat compat = compat();
        assertEquals(Optional.of(false), compat.potHeated(null, AT), "替身报这一格没有热源");
        assertEquals(Optional.empty(), compat.potHeated(null, AT.north()), "替身只认一格：别的格读不到厨锅");
        assertEquals(Optional.of(List.of(new MachineState.Shown("farmersdelight:vegetable_soup", 1))),
                compat.potMeal(null, AT));
        assertEquals(Optional.empty(), compat.potMeal(null, AT.north()));
    }

    @Test
    void 不在世界时读数与出口都如实说读不到() {
        assertEquals(MachineState.Activity.UNKNOWN, type().state(AT).activity());
        assertEquals(List.of(), type().output(AT));
    }
}
