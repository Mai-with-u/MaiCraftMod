// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatMenuFeed;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 压印器认领了就要投取得动：投料给经界面的动作、收产物给点开界面取的动作、输出槽读数经读写接缝转发；
 * 不收料的 ME 设备照旧给空，不是压印器读不出输出。
 */
class Ae2MachineFeedTest {

    private static final BlockPos AT = new BlockPos(1, 64, 1);
    private static final String INGOT = "minecraft:iron_ingot";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 终端查找的空替身：压印器的测试用不到终端。 */
    private static final Ae2Terminals NO_TERMINALS = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of();
        }

        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
            return false;
        }
    };

    /** 终端菜单的空替身：压印器的测试用不到终端界面。 */
    private static final Ae2TerminalMenu NO_MENU = new Ae2TerminalMenu() {
        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                Direction side) {
            return false;
        }

        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
            return Optional.empty();
        }

        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
            return Optional.empty();
        }

        @Override public void takeStack(AbstractContainerMenu menu, long serial) {
        }

        @Override public void takeOne(AbstractContainerMenu menu, long serial) {
        }

        @Override public void putBack(AbstractContainerMenu menu) {
        }
    };

    /** 压印器读写端替身：固定一格报输出槽里有两件印刷硅。 */
    private static final Ae2Inscribers STUB_INSCRIBERS = new Ae2Inscribers() {
        @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
            return at.equals(AT) ? Optional.of(List.of(new MachineState.Shown("ae2:printed_silicon", 2)))
                    : Optional.empty();
        }
    };

    private static Ae2MachineType typeOf(String kindId) {
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals(kindId)).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, STUB_INSCRIBERS);
        return new Ae2MachineType(kind, compat, STUB_INSCRIBERS, offlineServices(),
                Ae2Compat.blocksOf(kind.blocks()));
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
    void 压印器给经界面的投料动作_不收料的设备照旧给空() {
        assertTrue(typeOf("ae2:inscriber").feed(AT, INGOT, 4, Permissions.DEFAULT).orElseThrow()
                instanceof CompatMenuFeed, "压印器的投料走点开界面放槽");
        assertTrue(typeOf("ae2:charger").feed(AT, INGOT, 1, Permissions.DEFAULT).isEmpty(),
                "充能器这类没接投料方法的设备不给动作");
    }

    @Test
    void 压印器给点开界面收产物的动作_别的设备不给() {
        assertTrue(typeOf("ae2:inscriber").take(AT, "ae2:printed_silicon", 2, Permissions.DEFAULT).orElseThrow()
                instanceof MenuOutputLook, "收产物是点开界面把产物搬进背包");
        assertTrue(typeOf("ae2:condenser").take(AT, "ae2:matter", 1, Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 输出槽读数经联动入口转发读写接缝() {
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, STUB_INSCRIBERS);
        assertEquals(Optional.of(List.of(new MachineState.Shown("ae2:printed_silicon", 2))),
                compat.inscriberOutput(null, AT));
        assertEquals(Optional.empty(), compat.inscriberOutput(null, AT.north()),
                "替身只认一格：别的格没有压印器，读不出输出");
    }

    @Test
    void 压印器才报输出_别的设备没有输出槽() {
        assertEquals(List.of(), typeOf("ae2:controller").output(AT), "控制器没有输出槽");
        assertEquals(List.of(), typeOf("ae2:charger").output(AT));
    }
}
