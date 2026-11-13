// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按住潜行完成的瞄准右键动作：委托 {@link HoldsSneak} 在每一刻先按下潜行并等潜行落到角色身上再推进瞄准与提交，
 * 同时向外保留内部 {@link AimAndInteract#result()} 的结算结论，供上层核对这一下是否生效。
 */
public final class SneakingUse implements Action {

    private final AimAndInteract inner;
    private final Action sneaking;

    public SneakingUse(AimAndInteract inner, HoldsSneak sneak) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.sneaking = sneak == null ? inner : sneak.sneakingInARow(inner);
    }

    /** 做完之后的交互结论与现场；动作还没结束时为 null。 */
    public InteractionResult result() {
        return inner.result();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return sneaking.tick(context);
    }

    @Override
    public void pause() {
        sneaking.pause();
    }

    @Override
    public void close() {
        sneaking.close();
    }

    @Override
    public Interruptibility interruptibility() {
        return sneaking.interruptibility();
    }

    @Override
    public String describe() {
        return sneaking.describe();
    }
}
