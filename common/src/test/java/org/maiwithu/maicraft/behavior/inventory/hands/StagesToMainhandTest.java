// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备料到主手的动作：身上有就换到主手，身上没有按缺东西去取，只准用现成的那种身上没有如实失败；
 * 换手做完了主手还拿的不是它就留在原地重判，不装作备好了。
 */
class StagesToMainhandTest {

    private static final String COAL = "minecraft:coal";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 身上有就换到主手换完收场() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(5, new ItemStack(Items.COAL, 3));
        Rig rig = new Rig(inventory);
        // 换手替身：先做一刻搬运，第二刻把第 5 格的东西换到选中的快捷栏格第 0 格并完成。
        boolean[] moved = {false};
        rig.movesToMainhand = itemId -> Optional.of(rig.step(tick -> {
            if (!moved[0]) {
                moved[0] = true;
                return ActionStatus.progressed();
            }
            inventory.setItem(0, inventory.getItem(5));
            inventory.setItem(5, ItemStack.EMPTY);
            inventory.selected = 0;
            return ActionStatus.done();
        }));
        StagesToMainhand action = rig.stagesToMainhand(COAL, 1, "测试备料", null);

        assertTrue(action.tick(rig.tick()) instanceof ActionStatus.Running, "换手在做的一刻报在做");
        assertTrue(action.tick(rig.tick()) instanceof ActionStatus.Done, "主手拿到就是备好了");
    }

    @Test
    void 换手接缝给空时如实失败() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(5, new ItemStack(Items.COAL));
        Rig rig = new Rig(inventory);
        rig.movesToMainhand = itemId -> Optional.empty();
        StagesToMainhand action = rig.stagesToMainhand(COAL, 1, "测试备料", null);

        ActionStatus status = action.tick(rig.tick());
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().equals("无法将 " + COAL + " 换到主手"), "换不了主手照实说");
    }

    @Test
    void 身上没有且只准用现成时按给定的说明失败() {
        Rig rig = new Rig(new Inventory(null));
        StagesToMainhand action = rig.stagesToMainhand(COAL, 1, "测试备料", "身上没有待补电的 " + COAL);

        ActionStatus status = action.tick(rig.tick());
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().kind() == Problem.Kind.NEED_ITEM
                && failed.problem().message().equals("身上没有待补电的 " + COAL), "不另造新件，缺了照实说");
        assertTrue(rig.asked.isEmpty(), "没有去取新的");
    }

    @Test
    void 身上没有时按缺东西去取_要几件为什么都照说() {
        Inventory inventory = new Inventory(null);
        Rig rig = new Rig(inventory);
        // 取货替身：记下请求，做完时把一件煤放进选中的快捷栏格，主手就有了。
        rig.needs = (request, permissions) -> {
            rig.asked.add(request);
            return rig.step(tick -> {
                inventory.setItem(inventory.selected, new ItemStack(Items.COAL));
                return ActionStatus.done();
            });
        };
        StagesToMainhand action = rig.stagesToMainhand(COAL, 4, "充能器充能原料", null);

        assertTrue(action.tick(rig.tick()) instanceof ActionStatus.Done, "取到并已在主手，一次收场");
        assertEquals(1, rig.asked.size(), "只发一次取货请求");
        assertEquals(COAL, rig.asked.get(0).wanted().specifier());
        assertEquals(4, rig.asked.get(0).count(), "身上没有几件就取几件");
        assertEquals("充能器充能原料", rig.asked.get(0).purpose());
    }

    @Test
    void 取到了背包但主手拿的不是它时继续换手不装作备好() {
        Inventory inventory = new Inventory(null);
        Rig rig = new Rig(inventory);
        // 取货替身做完但东西只落在背包格，主手还没拿上。
        rig.needs = (request, permissions) -> rig.step(tick -> ActionStatus.done());
        StagesToMainhand action = rig.stagesToMainhand(COAL, 1, "测试备料", null);

        assertTrue(action.tick(rig.tick()) instanceof ActionStatus.Running running && running.progressed(),
                "取到了但主手没拿上，报推进继续换手");
    }

    /** 按脚本一步做完的动作替身。 */
    private interface Spec {
        ActionStatus tick(TickContext tick);
    }

    /** 离线替身全套：真实 Inventory 本体加什么都不接的玩家行为服务，换手与取货按测试需要接上。 */
    private static final class Rig {
        final Inventory inventory;
        final List<ItemRequest> asked = new ArrayList<>();
        Function<String, Optional<Action>> movesToMainhand = itemId -> Optional.empty();
        BiFunction<ItemRequest, Permissions, Action> needs = (request, permissions) -> {
            asked.add(request);
            return step(tick -> ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "取不到")));
        };

        Rig(Inventory inventory) { this.inventory = inventory; }

        StagesToMainhand stagesToMainhand(String itemId, int count, String acquireLabel, String missingMessage) {
            return new StagesToMainhand(services(), Permissions.DEFAULT, itemId, count, acquireLabel,
                    missingMessage, context -> inventory);
        }

        TickContext tick() {
            return new TickContext() {
                @Override public long gameTick() { return 0; }
                @Override public PlayerContext player() { return emptyContext; }
            };
        }

        /** 只为过"在不在世界里"这一道关的空角色替身：备料动作经 read 办法读的是替身 Inventory 本体。 */
        private final PlayerContext emptyContext = new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return null; }
            @Override public long clientTick() { return 0; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return false; }
            @Override public boolean tryClaimInteraction() { return false; }
        };

        static Action step(Spec spec) {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) { return spec.tick(tick); }
                @Override public String describe() { return "替身动作"; }
            };
        }

        private PlayerServices services() {
            Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                    name -> Optional.empty(), "self");
            Function<String, Optional<Action>> move = movesToMainhand;
            BiFunction<ItemRequest, Permissions, Action> need = needs;
            BringsPlayerClose bringsNowhere = new BringsPlayerClose() {
                @Override public Action toward(ApproachTarget target, Permissions permissions) {
                    return step(tick -> ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "走不过去")));
                }
            };
            GearChanges noGear = new GearChanges() {
                @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
            };
            return new PlayerServices(() -> null, bringsNowhere, new Interactions(null),
                    move::apply, noGear, inner -> inner,
                    need::apply, nobodyOwns, itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(),
                    ServerReads.NONE, new BackpackView() {
                        @Override public List<BackpackStack> stacks() { return List.of(); }
                        @Override public int usedSlots() { return 0; }
                        @Override public int totalSlots() { return 36; }
                    }, DigToolRule.HELD);
        }
    }
}
