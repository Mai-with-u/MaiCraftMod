// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 站实到达的装饰（见 {@link NavGoal#standing}）：位置条件与内层目标一致，只有到达判据收紧——
 * 水里漂进范围不算到，泡在浅水里脚踩着地也不算到。"去站到那儿"的出行目的地用：
 * 到达与乘船腿的出发点按同一份干地事实判，不然走路把水里判成到了、船又拒绝从水里出发，
 * 同一片岸两边互相矛盾。
 */
public final class StandingDry implements NavGoal {

    public final NavGoal inner;

    public StandingDry(NavGoal inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    @Override public boolean isAt(BlockPos feet) {
        return inner.isAt(feet);
    }

    @Override public boolean acceptsWaterArrival() {
        // 到达必须站在干地上：水里漂到、浅水里站着都不算。
        return false;
    }

    @Override public double heuristic(BlockPos from) {
        return inner.heuristic(from);
    }

    @Override public BlockPos center() {
        return inner.center();
    }

    @Override public SemanticFingerprint semanticFingerprint() {
        // 装饰不改位置要求，与内层目标等价；出行目的地恒套这一层，不会让重定目标认不出来。
        return inner.semanticFingerprint();
    }
}
