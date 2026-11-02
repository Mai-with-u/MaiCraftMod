// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 在酿造台上动手的执行接缝：照一整条把药水酿出来。生产实现走到跟前点开、放燃料放瓶子放材料、关着界面等，
 * 测试用替身。备料、清点身上有什么由拿东西的来源交进来。
 */
public interface BrewingRuns {

    /**
     * 照这一整条酿药水的动作；接缝没接上、不在世界里时为空。
     *
     * @param stands   记得的几台酿造台，近的在前：第一台瓶位里放着别人的东西就换下一台
     * @param needs    等的时候弄后面几炉的料、眼前缺的料，都经它回到拿东西的引擎
     * @param carried  一种东西身上有几件（带药水种类的写法或物品 ID）
     * @param occupied 点开发现放着别人东西的那台，告诉来源一声：下回问价先不算它
     */
    Optional<Action> brew(BrewPlan plan, List<WorldPosition> stands, Permissions permissions, TaskRecords records,
            ItemNeeds needs, ToIntFunction<String> carried, Consumer<WorldPosition> occupied);
}
