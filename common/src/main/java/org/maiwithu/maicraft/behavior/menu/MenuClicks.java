// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 精确搬运的点击序列：从源格往目标格搬恰好几件的点法（纯计算）。
 * 整堆都搬用两次左键；只搬一部分时左键拿起、按搬运计划拆成半堆放进目标格、左键把余量放回源格——
 * 绝不整堆快移超额。会拿到光标上的那几下带着登记标记，中途关界面才放得回原处。
 */
public final class MenuClicks {

    private MenuClicks() {}

    /**
     * 从 sourceSlotId 往 targetSlotId 搬 amount 件的点击序列；source 是源格现在的样子。
     * amount 与整堆相等时直接整堆放过去，少一件都不行就逐件放。
     */
    public static List<MenuClick> exact(int sourceSlotId, int targetSlotId, SlotSnapshot source, int amount) {
        List<MenuClick> clicks = new ArrayList<>();
        for (TransferPlan.Step step : TransferPlan.exactAmount(source.count(), amount,
                source.stack().getMaxStackSize()).steps()) {
            boolean onSource = step.where() == TransferPlan.Where.SOURCE;
            int slotId = onSource ? sourceSlotId : targetSlotId;
            clicks.add(switch (step.kind()) {
                case QUICK_MOVE -> new MenuClick(slotId, true, 0, false);
                case TAKE_ALL -> new MenuClick(slotId, false, 0, true);
                case TAKE_HALF -> new MenuClick(slotId, false, 1, true);
                case GIVE_ALL -> new MenuClick(slotId, false, 0, false);
                case GIVE_ONE, PUT_BACK_ONE -> new MenuClick(slotId, false, 1, false);
            });
        }
        return clicks;
    }
}
