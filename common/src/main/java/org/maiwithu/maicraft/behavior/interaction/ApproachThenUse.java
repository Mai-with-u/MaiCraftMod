// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 先走到目标方块够得着的地方、再对它右键一次的小组合动作：走近做完才建右键动作，
 * 右键做成没有由调用方在出手那一刻给确认条件。往机器里放料、空手取出产物、摇曲柄都是这一段；
 * 走不过去时整个动作按靠近的问题收场，右键一次都不出手。
 */
public final class ApproachThenUse implements Action {

    private final String what;
    private final ApproachTarget target;
    private final Permissions permissions;
    private final PlayerServices services;
    private final Supplier<Action> useFactory;
    private boolean approached;
    private Action current;

    /**
     * @param what       在做什么，给描述，例如"靠近并右键 10 64 10"
     * @param target     要靠近并对它右键的那一格
     * @param useFactory 走近之后建右键动作的办法：确认条件按出手前那一刻的现场定
     */
    public ApproachThenUse(String what, ApproachTarget target, Permissions permissions,
            PlayerServices services, Supplier<Action> useFactory) {
        this.what = Objects.requireNonNull(what, "what");
        this.target = Objects.requireNonNull(target, "target");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.services = Objects.requireNonNull(services, "services");
        this.useFactory = Objects.requireNonNull(useFactory, "useFactory");
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (current == null) {
            current = approached ? useFactory.get() : services.approach().toward(target, permissions);
        }
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed) return status;
        if (!approached) {
            approached = true;
            return ActionStatus.progressed();
        }
        return ActionStatus.done();
    }

    @Override public void pause() { if (current != null) current.pause(); }

    @Override public void close() { if (current != null) { current.close(); current = null; } }

    @Override public String describe() { return what; }
}
