// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 关上一份开着的界面：认领的界面会话请游戏关，到期限没关上时补试一次——重认领会话再请游戏关一次，
 * 期限按新会话重算，补试也到期限才认输。会话认领不了（光标上挂着不是自己拿的东西）时照样请游戏关一次，
 * 东西由原版的关闭流程还回背包。界面先一步没了（玩家关掉、被顶掉）也算关上了。
 * 只请一次就收场的话界面可能还开着，格里的东西看着像没了。谁打开谁关闭：跑完这段是打开者的责任。
 */
public final class MenuClose implements Action {

    /** 认领不了会话时自己数的期限，与会话的关闭期限同量级。 */
    private static final int UNOWNED_CLOSE_TICKS = 100;

    private final MenuChannel channel;
    private final MenuSessionSource sessions;
    private final MenuCloseRetry retry = new MenuCloseRetry();
    private boolean askedWithoutSession;
    private int waitedWithoutSession;

    public MenuClose(MenuChannel channel, MenuSessionSource sessions) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (!channel.stillOpen()) return ActionStatus.done();
        Optional<MenuSession> owned = sessions.current();
        if (owned.isEmpty()) {
            // 光标上挂着不是自己拿的东西，会话认领不了：照样请游戏关一次，东西由原版的关闭流程还回背包。
            if (!askedWithoutSession) {
                askedWithoutSession = true;
                channel.requestClose();
            }
            return ++waitedWithoutSession > UNOWNED_CLOSE_TICKS
                    ? ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "界面到期限还没有关上", null))
                    : ActionStatus.running();
        }
        return switch (owned.get().closeNow(channel, tick.player().clientTick())) {
            case MenuSession.Closing.Closed closed -> ActionStatus.done();
            case MenuSession.Closing.Failed failed -> {
                if (retry.grantOne()) {
                    sessions.renew();
                    yield ActionStatus.running();
                }
                yield ActionStatus.failed(failed.problem());
            }
            default -> ActionStatus.running();
        };
    }

    @Override public void close() {
    }

    @Override public String describe() {
        return "关上容器界面";
    }
}
