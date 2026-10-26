// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 点开机器的界面看一眼出口：走到跟前右键点开（和开箱子同一个打开动作），数界面里有几件要的东西；
 * 要收就按取货一轮把它们整堆搬进背包，最后关上界面。收了几件以背包前后清点为准。
 *
 * <p>界面里认得出的格都数（通用机械的机器把产物放在输出格，原料是另一样东西，不会数混）；
 * 点不开、界面认不出两侧，都按打开动作的原因如实失败，调用方据此收手，不对着打不开的机器反复跑。
 */
final class MenuOutputLook implements OutputLook {

    /** 点开以后看完、搬完、关上的总期限（刻）：到点如实收场，不在一台机器的界面上耗死。 */
    private static final int GIVE_UP_TICKS = 20 * 20;

    private final String itemId;
    private final boolean collect;
    private final MenuOpening opening;
    private final Supplier<PlayerContext> contexts;
    private final List<String> notes = new ArrayList<>();
    private OpenedMenu menu;
    /** 要收时的取货一轮；只看不收时为 null，直接关界面。 */
    private MenuTakeRound round;
    private Action closing;
    private int seen;
    private int carriedBefore = -1;
    private int taken;
    private int waited;

    MenuOutputLook(String itemId, boolean collect, MenuOpening opening, Supplier<PlayerContext> contexts) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.collect = collect;
        this.opening = Objects.requireNonNull(opening, "opening");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    // 点开 → 数 → 要收就搬 → 关上；点开之后的总期限到了，点出去的那几下照实交回再收场。
    @Override public ActionStatus tick(TickContext tick) {
        if (menu == null) return open(tick);
        if (++waited > GIVE_UP_TICKS) {
            if (round != null) round.abandonAndNote("总期限到了");
            menu.abandon();
            settleTaken();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "点开机器界面看出口超时", null));
        }
        if (round != null) {
            ActionStatus status = round.tick(tick, menu.reading());
            if (!(status instanceof ActionStatus.Running)) {
                notes.addAll(round.unconfirmedFacts());
                settleTaken();
            }
            return status;
        }
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            notes.add("看完关界面没能确认关上：" + failed.problem().message());
            return ActionStatus.done();
        }
        return status;
    }

    // 点开：走不到、点不开、界面认不出都把打开动作的原因原样交回；点开了先数一遍，记下背包里原有几件。
    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElseThrow();
        Optional<MenuContent.Reading> reading = menu.reading();
        seen = reading.map(this::inMachine).orElse(0);
        carriedBefore = carried();
        if (collect && seen > 0) round = new MenuTakeRound(menu, List.of(new TakeAll()), new Endings());
        return ActionStatus.progressed();
    }

    // 界面容器那一侧装着几件要的东西。
    private int inMachine(MenuContent.Reading reading) {
        int total = 0;
        for (SlotSnapshot snapshot : reading.containerSnapshots()) {
            if (matches(snapshot)) total += snapshot.count();
        }
        return total;
    }

    private boolean matches(SlotSnapshot snapshot) {
        return !snapshot.isEmpty() && idOf(snapshot.stack()).equals(itemId);
    }

    // 收了几件按背包前后清点：点了几下、界面里少了几件都不作数，进了背包才算。
    private void settleTaken() {
        if (carriedBefore < 0) return;
        taken = Math.max(0, carried() - carriedBefore);
    }

    private int carried() {
        PlayerContext current = contexts.get();
        if (current == null || current.localPlayer() == null) return 0;
        var inventory = current.localPlayer().getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public int seen() {
        return seen;
    }

    @Override public int taken() {
        return taken;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    // 搬的那一笔光标上拿着东西时停不得；别的时候照常干活。
    @Override public Interruptibility interruptibility() {
        if (menu == null) return opening.interruptibility();
        return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    // 被打断：走着、点着的先停；界面开着就请游戏关上，回来重新点开再看。
    @Override public void pause() {
        opening.pause();
        if (menu != null) menu.abandon();
    }

    @Override public void close() {
        opening.close();
        if (round != null) {
            round.close(true);
        } else if (menu != null && closing == null) {
            menu.abandon();
        }
        if (closing != null) closing.close();
        settleTaken();
    }

    @Override public String describe() {
        return menu == null ? opening.describe() : collect ? "从机器界面里拿出 " + itemId : "看机器界面里的 " + itemId;
    }

    /** 要收的那一样：界面里还剩几件就还缺几件，整堆搬完为止。 */
    private final class TakeAll implements MenuTakeRound.Want {
        @Override public String label() {
            return "从机器界面里拿 " + itemId;
        }

        @Override public Predicate<SlotSnapshot> matcher() {
            return MenuOutputLook.this::matches;
        }

        @Override public int stillNeeded(MenuContent.Reading reading) {
            return inMachine(reading);
        }

        // 对不上账的一笔之后不再盘这一样：搬走多少按背包清点，别对着说不清的格子接着点。
        @Override public boolean settleAfterDiverge() {
            return true;
        }
    }

    /** 收场：机器界面不记进世界记忆（不是箱子）；关上或中途没了都照常算完，收了几件按清点。 */
    private static final class Endings implements MenuTakeRound.Endings {
        @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
        }

        @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
            return ActionStatus.done();
        }

        @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
            return ActionStatus.done();
        }
    }
}
