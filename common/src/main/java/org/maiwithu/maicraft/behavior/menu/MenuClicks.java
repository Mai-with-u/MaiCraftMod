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
     * 源格装有堆叠升级时当前数量可能超过物品自身的堆叠上限，因此把源格当前数量与鼠标光标单次可抓上限分开传入计划。
     */
    public static List<MenuClick> exact(int sourceSlotId, int targetSlotId, SlotSnapshot source, int amount) {
        List<MenuClick> clicks = new ArrayList<>();
        // 光标单次最多只能抓起物品自身的最大堆叠数（圆石 64、工具 1），与超叠源格里的当前件数分开计算。
        int cursorMax = Math.max(1, source.stack().getMaxStackSize());
        for (TransferPlan.Step step : TransferPlan.exactAmount(source.count(), amount, cursorMax).steps()) {
            boolean onSource = step.where() == TransferPlan.Where.SOURCE;
            int slotId = onSource ? sourceSlotId : targetSlotId;
            clicks.add(switch (step.kind()) {
                case QUICK_MOVE -> new MenuClick(slotId, true, 0, false);
                case TAKE_ALL -> new MenuClick(slotId, false, 0, true);
                case TAKE_HALF -> new MenuClick(slotId, false, 1, true);
                case GIVE_ALL, PUT_BACK_ALL -> new MenuClick(slotId, false, 0, false);
                case GIVE_ONE, PUT_BACK_ONE -> new MenuClick(slotId, false, 1, false);
            });
        }
        return clicks;
    }
}
