// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import java.util.List;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 改一只女仆设置的任务输入。不可变；她是不是女仆、改成了哪些都在任务里。
 *
 * @param seenId      她的观察编号 e#
 * @param settings    要改的设置，按给的顺序
 * @param permissions 走过去能动多少地形
 */
record MaidConfigureInput(String seenId, List<MaidSettings.Setting> settings, Permissions permissions)
        implements TaskInput {

    MaidConfigureInput {
        settings = List.copyOf(settings);
        if (settings.isEmpty()) throw new IllegalArgumentException("要改的设置不能为空");
    }

    @Override public String describe() {
        return "改 " + seenId + " 的设置：" + settings.stream().map(MaidSettings.Setting::key).toList();
    }
}
