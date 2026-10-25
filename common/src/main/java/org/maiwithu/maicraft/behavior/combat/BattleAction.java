// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 一场仗包成的动作：战斗引擎每刻读回的是战况不是动作状态，包一层登记成分阶段任务手上的一件事，
 * 任务基类被生存需求打断时跟着暂停、结束时跟着收尾、问能不能打断时问到引擎。
 * 每刻推进仍由任务自己调战斗引擎，读回战况再定下一步，这一层推进时只交还"还在做"。
 */
final class BattleAction implements Action {
    private final Battle battle;

    BattleAction(Battle battle) {
        this.battle = battle;
    }

    @Override public ActionStatus tick(TickContext context) {
        return ActionStatus.running();
    }

    @Override public void pause() {
        battle.pause();
    }

    @Override public void close() {
        battle.close();
    }

    @Override public Interruptibility interruptibility() {
        return battle.interruptibility();
    }

    @Override public String describe() {
        return battle.describe();
    }
}
