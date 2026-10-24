// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.List;
import java.util.Optional;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemRequest;

/**
 * 开箱取物的执行接缝：走到容器跟前，打开界面，核对现场后把要的物品搬进背包，再关上界面。
 * 记忆只是线索，箱子里有什么开了才算数，到了要核对——这些现场动作（靠近、点开、按搬运计划取放、
 * 关界面、把看到的内容记进世界记忆）由游戏接口层用靠近与界面会话的公开接口实现，测试用替身。
 * 原料来源不是容器、或现在打不开时返回 empty。
 */
public interface ContainerTakes {

    /**
     * 为一次取物生成动作：做完时想要的东西已在背包里（拿到多少以重新清点为准）。
     * 一次只取一样时用它。
     */
    default Optional<Action> take(KnownContainer container, ItemRequest request, Permissions permissions) {
        return take(container, List.of(request), permissions);
    }

    /**
     * 开一次箱取几样：走过去、点开，把清单里箱里有的都搬进背包，再关上。
     * 每样写着这一刻还缺几件；箱里没有的那样不碰，也不因此失败。
     */
    Optional<Action> take(KnownContainer container, List<ItemRequest> wants, Permissions permissions);
}
