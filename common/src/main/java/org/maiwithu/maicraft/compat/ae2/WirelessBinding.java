// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把身上的无线终端绑到一台无线访问点上的动作：走到访问点跟前点开它的界面，
 * 按 {@link BindingPlan} 一步步点——拿起终端、放进绑定格、等绑定副本出现在产出格、取回背包——再关上，
 * 最后按身上的物品核对绑定真的落上了。每一步做完以现场为准，对不上、一直没动静都如实收场。
 *
 * <p>对访问点右键只会打开这份界面，绑定是界面里的槽位流程（AE2 服务端在格子变动时把副本放进产出格），
 * 所以绑定动作点的是格子，不发模组协议。
 */
public final class WirelessBinding implements Action {

    /** 一下点击等现场变化的期限（刻）；一直没动静按没生效收场。 */
    private static final int CLICK_TIMEOUT_TICKS = 40;
    /** 终端进了绑定格后，等副本出现在产出格的期限（刻）；服务端结算只要一两刻，等不到就是绑不上。 */
    private static final int COPY_WAIT_TICKS = 60;
    /** 一次绑定的总期限（刻）：走近归打开动作管，界面里这几下加关界面，半分钟足够。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    private enum Stage { OPEN, BIND, CLOSE, VERIFY, DONE }

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final Ae2Terminals.AccessPoint point;
    private final String terminalItemId;
    private final MenuOpening opening;
    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    private Problem stopReason;
    private long waited;
    private int copyWaited;
    /** 正在等现场变化的那一下点击：点了哪一格、点之前那格的样子；不在等点击时格号为 -1。 */
    private int clickedSlot = -1;
    private SlotSnapshot slotBefore = SlotSnapshot.empty();
    private int clickQuiet;

    WirelessBinding(Ae2Compat compat, PlayerServices services, Ae2Terminals.AccessPoint point,
            String terminalItemId, Permissions permissions) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.point = Objects.requireNonNull(point, "point");
        this.terminalItemId = Objects.requireNonNull(terminalItemId, "terminalItemId");
        this.opening = new ClientMenuOpening(point.block(), permissions, services.menuLayouts(),
                services.approach(), services.interactions(), services.context());
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CLOSE.ordinal()) {
            return stopNow(Problem.of(Problem.Kind.STUCK, "在 " + point.describe() + " 绑定"
                    + terminalItemId + "超时", null));
        }
        return switch (stage) {
            case OPEN -> open(tick);
            case BIND -> bind(tick);
            case CLOSE -> closeMenu(tick);
            case VERIFY -> verify();
            case DONE -> result();
        };
    }

    // 走过去点开访问点的界面；点不开、界面认不出，都把打开动作的原因原样交回，引擎换路。
    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElseThrow();
        PlayerContext player = services.context().get();
        if (player == null || !compat.accessPointMenu(player.localPlayer().containerMenu)) {
            return stopNow(Problem.of(Problem.Kind.TARGET_GONE,
                    "点开的不是 " + point.describe() + " 的界面", null));
        }
        stage = Stage.BIND;
        return ActionStatus.progressed();
    }

    // 按计划点一下、等一下；现场对不上计划说的（点了没动静、格子被别人动了）如实收场。
    private ActionStatus bind(TickContext tick) {
        PlayerContext player = services.context().get();
        if (player == null || menu.reading().isEmpty()) {
            return stopNow(Problem.of(Problem.Kind.TARGET_GONE, "访问点的界面刚打开就被关上了", null));
        }
        AbstractContainerMenu raw = player.localPlayer().containerMenu;
        // 正在等上一下点击的现场变化：先看生效没有，再看是不是一直没动静。
        if (clickedSlot >= 0) {
            if (menu.busy()) return ActionStatus.running();
            if (clickApplied(raw)) {
                clickedSlot = -1;
                return ActionStatus.progressed();
            }
            if (++clickQuiet > CLICK_TIMEOUT_TICKS) {
                return stopNow(Problem.of(Problem.Kind.STUCK, "在 " + point.describe()
                        + " 的界面里点了一直没动静，先停手", null));
            }
            return ActionStatus.running();
        }
        if (menu.busy()) return ActionStatus.running();
        Optional<Integer> inputSlot = compat.bindingInputSlot(raw);
        Optional<Integer> outputSlot = compat.bindingOutputSlot(raw);
        BindingPlan.Step step = BindingPlan.next(new BindingPlan.Input(
                SlotSnapshot.of(raw.getCarried()),
                menu.reading().orElseThrow().playerSlotIds(),
                menu.reading().orElseThrow().playerSnapshots(),
                inputSlot, inputSlot.map(id -> SlotSnapshot.of(raw.getSlot(id).getItem())),
                outputSlot, outputSlot.map(id -> SlotSnapshot.of(raw.getSlot(id).getItem())),
                terminalItemId,
                compat.linkedWirelessTerminal(raw.getCarried()),
                outputSlot.map(id -> compat.linkedWirelessTerminal(raw.getSlot(id).getItem())).orElse(false)));
        return switch (step) {
            case BindingPlan.PickUp pickUp -> click(pickUp.slotId(), raw);
            case BindingPlan.PutIn putIn -> click(putIn.slotId(), raw);
            case BindingPlan.TakeCopy takeCopy -> click(takeCopy.slotId(), raw);
            case BindingPlan.PutDown putDown -> click(putDown.slotId(), raw);
            // 绑定格里有、副本还没来：服务端结算只要一两刻，等不到就是绑不上（终端绑不了、访问点没电）。
            case BindingPlan.WaitCopy waitCopy -> ++copyWaited > COPY_WAIT_TICKS
                    ? stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "终端放进绑定格后，"
                            + point.describe() + " 的产出格里一直没出现绑定好的副本，绑不上", null))
                    : ActionStatus.running();
            case BindingPlan.Finished finished -> {
                stage = Stage.CLOSE;
                yield ActionStatus.progressed();
            }
            case BindingPlan.Blocked blocked -> stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "在 " + point.describe() + " 绑定" + terminalItemId + "绑不了：" + blocked.why(), null));
        };
    }

    // 点之前记下那格的样子，生效判定按"点完现场该是什么样"来，只看两侧内容变没变；本刻点不出去就下一刻再点。
    private ActionStatus click(int slotId, AbstractContainerMenu raw) {
        SlotSnapshot before = SlotSnapshot.of(raw.getSlot(slotId).getItem());
        if (!menu.click(slotId, 0)) return ActionStatus.running();
        if (!before.isEmpty()) menu.noteCursorTakenFrom(slotId);
        clickedSlot = slotId;
        slotBefore = before;
        clickQuiet = 0;
        return ActionStatus.progressed();
    }

    // 这一下点击生效了没有：拿起是光标有了、原格空了；放进与放下是光标空了、那格变了。
    private boolean clickApplied(AbstractContainerMenu raw) {
        SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
        SlotSnapshot clicked = SlotSnapshot.of(raw.getSlot(clickedSlot).getItem());
        if (cursor.isEmpty()) return !clicked.sameIdentity(slotBefore);
        return !cursor.sameIdentity(slotBefore) && clicked.isEmpty();
    }

    // 关界面：打开者负责关闭。关不上不冒充没绑过，照常收场。
    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        stage = Stage.VERIFY;
        return ActionStatus.progressed();
    }

    // 绑定做没做成都以身上的物品为准：绑定位置写在物品上，开过界面只是过程。
    private ActionStatus verify() {
        PlayerContext player = services.context().get();
        if (player == null) return stopNow(Problem.of(Problem.Kind.STUCK, "角色不在世界里，核对不了绑定", null));
        if (compat.carriedWirelessTerminal(player.localPlayer()).filter(terminalItemId::equals).isEmpty()) {
            return stopNow(Problem.of(Problem.Kind.STUCK, "绑定做完了，身上却没有绑好访问点的"
                    + terminalItemId + "，核对不上", null));
        }
        stage = Stage.DONE;
        return result();
    }

    // 收场：还没关界面的先去关，关完再核对；已经关了直接给结论。
    private ActionStatus stopNow(Problem reason) {
        if (stopReason == null) stopReason = reason;
        if (menu == null || stage.ordinal() >= Stage.CLOSE.ordinal()) {
            stage = Stage.DONE;
            return result();
        }
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus result() {
        return stopReason == null ? ActionStatus.done() : ActionStatus.failed(stopReason);
    }

    // 被打断：界面关上，光标上的东西由原版的关闭流程还回背包。
    @Override public void pause() {
        opening.pause();
        if (menu != null) menu.abandon();
    }

    // 点击发出去、光标上可能拿着东西时停不得；走近与等副本是空当。
    @Override public Interruptibility interruptibility() {
        return clickedSlot >= 0 ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public void close() {
        opening.close();
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
    }

    @Override public String describe() {
        return "在 " + point.describe() + " 绑定身上的" + terminalItemId;
    }
}
