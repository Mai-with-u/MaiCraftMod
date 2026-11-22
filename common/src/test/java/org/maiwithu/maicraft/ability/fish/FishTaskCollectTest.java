// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemsInUse;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.survival.hunger.FoodPicker;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.FishingHookFacts;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 钓鱼收货期防打断与产物保护测试：确认 COLLECT 全阶段返回 UNSAFE_TO_STOP（避免低优生存需求在记账前插队），
 * 且每次收竿结算后的钓获立即纳入 ItemsToKeep 短期保护，不被顺手消耗。
 */
class FishTaskCollectTest {

    @Test
    void 钓鱼收货期返回停下不安全_结算后钓获立即纳入物品保护() {
        StubContext player = new StubContext();
        player.backpack.add(FishTask.FISHING_ROD, 1);
        StubTick tick = new StubTick(player);
        StubHooks hooks = new StubHooks();
        ItemsInUse inUse = new ItemsInUse(id -> Set.of());

        PicksUpDrops pickups = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }

            @Override
            public Action pickUpNewSince(Set<Integer> before) {
                return new Action() {
                    private boolean picked;

                    @Override
                    public ActionStatus tick(TickContext context) {
                        if (!picked) {
                            picked = true;
                            // 模拟战利品进包耗时一刻，同时验证 PICKUP 子步骤同样受 UNSAFE_TO_STOP 保护。
                            player.backpack.add("minecraft:cod", 1);
                            return ActionStatus.running();
                        }
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "捡起钓获"; }
                };
            }
        };

        FishServices services = new FishServices(null, null, null, pickups, null, hooks,
                FishServices.nothingProtected(), inUse::noteGained);
        FishTask task = new FishTask(new FishInput(1, 0, Permissions.DEFAULT), services, Permissions.DEFAULT,
                FishTask.Phase.WAIT_BITE);
        task.start(tick);

        // 等咬钩阶段允许被打断。
        assertEquals(Interruptibility.BETWEEN_ACTIONS, task.interruptibility(tick));

        // 鱼咬钩：进入 REEL，再提交收竿并确认后进入 COLLECT。
        hooks.biting = true;
        assertInstanceOf(TickResult.Running.class, task.tick(tick));
        assertEquals(FishTask.Phase.REEL, task.currentPhase());

        // 提交收竿后未确认前停下不安全。
        assertInstanceOf(TickResult.Running.class, task.tick(tick));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(tick));

        // 确认收竿：进入 COLLECT 阶段（GRACE 窗口）。
        player.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        hooks.hasHook = false;
        assertInstanceOf(TickResult.Running.class, task.tick(tick));
        assertEquals(FishTask.Phase.COLLECT, task.currentPhase());
        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(tick),
                "COLLECT 冲量等待期间应为 UNSAFE_TO_STOP，禁止低优生存需求插队");

        // 推进冲量等待和捡拾：全程处于 COLLECT 时均返回 UNSAFE_TO_STOP，直到 settleLoot 记账并完成。
        TaskResult finished = null;
        int unsafeCollectTicks = 0;
        for (int i = 0; i < 40 && finished == null; i++) {
            if (task.currentPhase() == FishTask.Phase.COLLECT) {
                assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(tick));
                unsafeCollectTicks++;
            }
            TickResult result = task.tick(tick);
            tick.advance();
            if (result instanceof TickResult.Finished done) finished = done.result();
        }
        assertTrue(unsafeCollectTicks >= 20, "COLLECT 的等待与捡拾阶段都应保持 UNSAFE_TO_STOP");
        assertTrue(finished != null && finished.status() == TaskResult.Status.DONE, "钓满 1 条后正常结束");
        assertEquals(1, inUse.keep("minecraft:cod"), "刚钓上来的生鳕鱼应立即受 ItemsToKeep 保护");

        // 结合 FoodPicker.carried 验证：即使把钓获当成非生食材，受 ItemsToKeep 保护的钓获也不会被当作可消耗口粮。
        ReadsFoodValues foods = id -> "minecraft:cod".equals(id)
                ? Optional.of(new ReadsFoodValues.FoodValue(id, 2, 0.4f, 1.6f, false, List.of(), false))
                : Optional.empty();
        assertTrue(FoodPicker.carried(player.backpack.stacks(), foods, inUse).isEmpty(),
                "受保护的钓获应从平时可用口粮里扣除");
    }

    @Test
    void 多竿钓鱼第一竿结算进入冷却后_已钓获物品仍持续受保护() {
        StubContext player = new StubContext();
        player.backpack.add(FishTask.FISHING_ROD, 1);
        StubTick tick = new StubTick(player);
        StubHooks hooks = new StubHooks();
        ItemsInUse inUse = new ItemsInUse(id -> Set.of());

        PicksUpDrops pickups = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }

            @Override
            public Action pickUpNewSince(Set<Integer> before) {
                return new Action() {
                    @Override
                    public ActionStatus tick(TickContext context) {
                        player.backpack.add("minecraft:salmon", 1);
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "捡起鲑鱼"; }
                };
            }
        };

        FishServices services = new FishServices(null, null, null, pickups, null, hooks,
                FishServices.nothingProtected(), inUse::noteGained);
        // count=2：第一竿结算后任务未结束而进入 COOLDOWN，第一条鲑鱼也必须已经受保护。
        FishTask task = new FishTask(new FishInput(2, 0, Permissions.DEFAULT), services, Permissions.DEFAULT,
                FishTask.Phase.WAIT_BITE);
        task.start(tick);

        hooks.biting = true;
        task.tick(tick);
        task.tick(tick);
        player.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        hooks.hasHook = false;
        task.tick(tick);

        for (int i = 0; i < 30 && task.currentPhase() == FishTask.Phase.COLLECT; i++) {
            assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(tick));
            task.tick(tick);
            tick.advance();
        }
        assertEquals(FishTask.Phase.COOLDOWN, task.currentPhase(), "第一竿结算完应进入冷却准备下一竿");
        assertEquals(1, inUse.keep("minecraft:salmon"), "多竿钓鱼进行中刚钓起的鲑鱼应立即受保护");
    }

    /** 鱼钩事实替身：可切换咬钩与挂钩状态。 */
    private static final class StubHooks implements FishingHookFacts {
        boolean hasHook = true;
        boolean biting;

        @Override public boolean hasHook() { return hasHook; }
        @Override public boolean isBiting() { return biting; }
        @Override public boolean hookedEntity() { return false; }
        @Override public boolean hookInWater() { return true; }
    }

    /** 可增删条目的背包替身。 */
    private static final class StubBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }

        @Override public List<BackpackStack> stacks() { return List.copyOf(stacks); }
        @Override public int usedSlots() { return stacks.size(); }
        @Override public int totalSlots() { return 36; }
    }

    /** 带脚本交互提交入口的角色上下文替身。 */
    private static final class StubContext implements PlayerContext {
        final StubBackpack backpack = new StubBackpack();
        final ScriptedInteractionSender sender = new ScriptedInteractionSender();
        long tick = 100;

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() { return null; }
        @Override public InteractionSender interactionSender() { return sender; }
        @Override public MenuActions menuActions() { return null; }
        @Override public BackpackView backpack() { return backpack; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }
    }

    /** 推进游戏刻的上下文替身。 */
    private static final class StubTick implements TickContext {
        private final StubContext player;

        StubTick(StubContext player) {
            this.player = player;
        }

        void advance() { player.tick++; }
        @Override public long gameTick() { return player.tick; }
        @Override public PlayerContext player() { return player; }
    }
}
