// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 对一只生物说一句话的任务输入。不可变；它会不会听、听没听到、回了什么都在任务里。
 *
 * @param seenId      那只生物的观察编号 e#
 * @param message     要说的话；以 / 开头的也照原话说给它听，不当命令
 * @param maxSeconds  说完以后最多站着等回话几秒；0 表示确认它听到了就走
 * @param permissions 走过去能动多少地形
 */
record TalkInput(String seenId, String message, int maxSeconds, Permissions permissions) implements TaskInput {

    TalkInput {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("要说的话不能为空");
        if (maxSeconds < 0) throw new IllegalArgumentException("等回话的秒数不能为负：" + maxSeconds);
    }

    @Override
    public String describe() {
        return "对 " + seenId + " 说：" + message;
    }
}
