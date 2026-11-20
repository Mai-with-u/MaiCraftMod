// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 换到主手的执行接缝：把身上（背包或副手）指定的一种物品换到主手上。
 *
 * <p>吃东西、按原版规矩穿戴护甲都要求东西在主手；从背包格换到快捷栏、从副手换到主手
 * 都是背包界面的原生操作，接在容器界面轨上。东西已经在主手时任务不会来找它；
 * 接缝没接上传 {@code Optional.empty()}，任务如实交代"换不到主手"。
 */
public interface MovesToMainhand {

    /** 把这件物品换到主手的动作；接缝没接上或身上没有它时为 empty。 */
    Optional<Action> moveToMainhand(String itemId);

    /**
     * 腾出空手的动作：主手已经空着就是一个立刻做完的动作；不然选中一个空着的快捷栏格，
     * 快捷栏没有空格就把主手的东西收进背包。空手右键置物台、工作盆是取东西，手里拿着东西就成了放东西。
     * 接缝没接上、快捷栏与背包都没有空格时为 empty。
     */
    default Optional<Action> emptyMainhand() {
        return Optional.empty();
    }

    /**
     * 打开容器前遭遇游戏拒绝时，按玩家常识切到空手或快捷栏里的普通非工具格以重试一次。
     * 优先腾空主手；快捷栏与主背包全满且当前主手拿着工具或可损耗物品时，切到快捷栏里第一格非工具普通物品；
     * 主手本已为空或无更合适格子可切时返回 empty。
     */
    default Optional<Action> clearHeldForOpen() {
        return emptyMainhand();
    }
}
