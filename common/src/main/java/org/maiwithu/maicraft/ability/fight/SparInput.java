// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 切磋的任务输入：和哪位玩家（玩家名或观察编号，点名就是许可）、停手线多少点。
 *
 * @param opponent 点名的那位玩家
 * @param stopAt   停手线，单位血量点（一颗心 2 点），0 到 19；0 是打到有人倒下
 */
record SparInput(Target opponent, int stopAt) implements TaskInput {

    SparInput {
        Objects.requireNonNull(opponent, "opponent");
        if (stopAt < 0 || stopAt > 19) throw new IllegalArgumentException("停手线应在 0..19 点：" + stopAt);
    }

    @Override
    public String describe() {
        String who = opponent instanceof Target.Player player ? "玩家 " + player.name()
                : opponent instanceof Target.Seen seen ? seen.id() : opponent.toString();
        return "和 " + who + " 切磋（停手线 " + stopAt + " 点）";
    }
}
