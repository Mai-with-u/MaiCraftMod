// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 轮到这一步时才按当刻现场建出来的动作：前一步备斧头或中间件可能消耗了身上的共用原料，
 * 等前一步做完再清点背包缺几件，缺了才去拿，够了就直接跳过，不提前按旧库存算死件数。
 */
final class DeferredStep implements Action {

    private final String label;
    private final Supplier<Optional<Action>> supplier;
    private boolean resolved;
    private Action inner;

    DeferredStep(String label, Supplier<Optional<Action>> supplier) {
        this.label = Objects.requireNonNull(label, "label");
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    @Override
    public ActionStatus tick(TickContext context) {
        if (!resolved) {
            resolved = true;
            inner = supplier.get().orElse(null);
            if (inner == null) return ActionStatus.done();
        }
        return inner.tick(context);
    }

    @Override
    public void pause() {
        if (inner != null) inner.pause();
    }

    @Override
    public void close() {
        if (inner != null) inner.close();
    }

    @Override
    public Interruptibility interruptibility() {
        return inner != null ? inner.interruptibility() : Interruptibility.BETWEEN_ACTIONS;
    }

    @Override
    public String describe() {
        return inner != null ? inner.describe() : label;
    }
}
