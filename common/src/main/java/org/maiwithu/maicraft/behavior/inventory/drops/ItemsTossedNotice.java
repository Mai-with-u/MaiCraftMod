// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 朝玩家扔出物品的通报器：当角色当面朝某位玩家扔出物品且游戏已确认扣减数量后，
 * 经任务事件出口发一条 {@code items_tossed} 事件，如实写明对方、扔出坐标与确认扔出的物品件数。
 *
 * <p>未确认的投掷（游戏没等到扣减确认）不经此通报，也不假定对方已捡起。
 */
public final class ItemsTossedNotice {

    /** 没接事件出口时的空通报器：离线单测不关心事件时可直接使用。 */
    public static final ItemsTossedNotice NONE = new ItemsTossedNotice(TaskEventSink.NONE);

    private final TaskEventSink events;

    public ItemsTossedNotice(TaskEventSink events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 确认朝某位玩家扔出了若干件物品后发一条结构化事件。
     * 仅对确认件数大于 0 且接收方名字、UUID 与物品 ID 齐全的投掷发出；挂在当前推进的目标与能力名下。
     */
    public void tossed(String to, String toUuid, WorldPosition at, String itemId, int count) {
        // 未确认或件数为 0 时不发事件，绝不把未确认的投掷冒充成已扔出。
        if (count <= 0 || to == null || to.isBlank() || toUuid == null || toUuid.isBlank()
                || itemId == null || itemId.isBlank()) {
            return;
        }
        String message = "朝 " + to + " 扔出了 " + count + " 个 " + itemId;
        ItemsTossed facts = new ItemsTossed(to, toUuid, at, List.of(new ItemsTossed.Item(itemId, count)));
        events.publishInAction(TaskEvent.Kind.ITEMS_TOSSED, message, facts);
    }
}
