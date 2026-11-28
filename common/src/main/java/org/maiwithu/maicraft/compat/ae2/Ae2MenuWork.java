// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开着 AE2 设备界面按步骤做一件事的公共骨架：点开界面 → 等界面读数同步 → 界面里按子类说的步骤做 →
 * 做成或做不下去都关上界面再收场。
 * 发包之后必须等界面同步（@GuiSync）回传、不能连发，这条经验只在这一份里体现：
 * 同步等待上限、界面没打开、被拒与没等到怎么收场都由基类定，子类只写做哪一步、怎样算做成。
 */
public abstract class Ae2MenuWork implements Action {

    /** 每次界面操作或同步的等待上限（刻）。F 游戏事实。 */
    public static final int SYNC_WAIT_TICKS = 40;

    private enum Stage { OPEN, WORK, CLOSE, DONE }

    private final MenuOpening opening;
    /** 界面点开了却拿不到时的收场说明，例如"物质聚合器界面没打开"。 */
    private final String notOpenProblem;
    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    /** 离上次重置又过了几刻，给同步等待计时；换步骤或刚发过一次操作就归零。 */
    private int waited;
    private Problem problem;

    protected Ae2MenuWork(MenuOpening opening, String notOpenProblem) {
        this.opening = Objects.requireNonNull(opening, "opening");
        this.notOpenProblem = Objects.requireNonNull(notOpenProblem, "notOpenProblem");
    }

    /** 界面开着时的一刻：做完了或做不下去了调 {@link #closeWith}；要等读数就给 running。 */
    protected abstract ActionStatus work(TickContext tick);

    /** 界面里正在做什么，给描述用。 */
    protected abstract String doing();

    /** 界面刚打开、还没开始做事时的一次性准备（如声明跟随服务端换页），做不了事的收场不在这里。 */
    protected void menuOpened(OpenedMenu menu) {
    }

    @Override public final ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case OPEN -> open(tick);
            case WORK -> work(tick);
            case CLOSE -> close(tick);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 点开界面：打开没成照实收场；开好了先做子类的开窗准备，再进界面里的步骤。
    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        OpenedMenu opened = opening.opened().orElse(null);
        if (opened == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, notOpenProblem));
        menu = opened;
        menuOpened(opened);
        waited = 0;
        stage = Stage.WORK;
        return ActionStatus.progressed();
    }

    private ActionStatus close(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        closing = null;
        return finish(problem);
    }

    /** 界面里的事做完（reason 为空）或做不下去了：关上界面，关完按 reason 收场。 */
    protected final ActionStatus closeWith(Problem reason) {
        problem = reason;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.DONE;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    /** 开着的界面，给子类读槽位、发点击用。 */
    protected final OpenedMenu menu() {
        return menu;
    }

    /** 距上次归零又过了一刻；超过同步等待上限返回真，该收场了。 */
    protected final boolean syncWaitElapsed() {
        return ++waited > SYNC_WAIT_TICKS;
    }

    /** 同步等待重新计时：换步骤或刚发过一次操作时调用。 */
    protected final void resetWait() {
        waited = 0;
    }

    /** 当前累计等了几刻，给节奏不一样的等待（如先等一刻再核对）用。 */
    protected final int waitedTicks() {
        return waited;
    }

    @Override public void pause() {
        if (closing != null) closing.pause();
        else if (menu == null) opening.pause();
    }

    // 中途收场：界面还开着就请游戏关一次，不把界面留在画面上。
    @Override public void close() {
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
        else if (menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return doing();
    }
}
