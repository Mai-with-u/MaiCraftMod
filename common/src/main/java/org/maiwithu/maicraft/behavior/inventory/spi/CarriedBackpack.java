// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 随身背包接缝：便携模组给的随身容器（精妙背包之类），腾地方时先把杂物塞进去，观察自身时一并呈现背在身上的包。
 *
 * <p>实现由联动模组提供，启动时按模组是否安装登记；还没有实现方时，腾地方跳过这一步，自身观察也不列这一栏。
 */
public interface CarriedBackpack {

    /** 随身背包还空着几格（刚进世界还没打开过的包按保底可尝试格数报，开包后刷新真实空槽数）。 */
    int freeSlots();

    /**
     * 把主背包里的一堆放进随身背包的动作：放进去并确认了算做完，放不下、点了没回音按问题失败；
     * 确认放进去的（记成存进了随身背包）与没能确认的交互由动作记进 records。这种东西放不进随身背包时给空。
     */
    Optional<Action> store(BackpackStack stack, TaskRecords records);

    /** 此刻角色身上背着或揣着的全部随身背包摘要；没有包时为空列表。 */
    default List<CarriedPack> carried() {
        return List.of();
    }

    /**
     * 角色身上某一只随身背包的观察摘要：穿在 Curios 等非原版栏位时也能在自身观察里看清，
     * 五分钟内打开过的还附带主存储剩余空槽数与看到的物品计数。
     *
     * @param item      这只背包本身的物品注册 ID
     * @param where     所在栏位代号（例如 curios:back、chest、offhand、inventory）
     * @param seen      五分钟内是否打开并读到过主存储
     * @param freeSlots 读到过时主存储剩余通用空槽数
     * @param contents  读到过时主存储里的物品 ID → 件数
     */
    record CarriedPack(String item, String where, boolean seen, int freeSlots, Map<String, Integer> contents) {

        public CarriedPack {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(where, "where");
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }
    }
}
