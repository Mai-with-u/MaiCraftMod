// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.player.PlayerContext;

import java.util.function.Consumer;

/**
 * 任务边界欠下的一次主手停用：持用中的任务结束时本刻没有交互机会，先把"该停哪一下、手上是什么"
 * 记在这里，下一刻由控制权边界先结清（经取消入口停手），再让新动作接手。
 * 后来者不继承旧欠账：换身体、换世界或新的一次性动作接手时旧账作废。
 */
final class PendingMainHandStop {

    private record Held(PendingInteraction pending, ItemStack item) {}

    private Held held;

    /** 还有没结清的停用欠账时为真：新的一次性动作要等它结清才接手。 */
    boolean owed() {
        return held != null;
    }

    /** 记下要停的这一下：连手上是什么一起冻结，取消时按原物停，不停到后来者换上的东西上。 */
    void owe(PlayerContext context, PendingInteraction pending) {
        held = new Held(pending, context.localPlayer().getUseItem().copy());
    }

    /** 交还角色、换世界或新动作接手时丢弃旧账，不替后来者松手。 */
    void drop() {
        held = null;
    }

    /**
     * 结清一刻：欠账不属于这个身体了就当没欠过；否则本刻有机会时经给定的取消入口停手。
     * 返回真表示欠账还在（这一刻先处理它，不走普通推进）。
     */
    boolean settle(PlayerContext context, Consumer<PendingInteraction> cancel) {
        Held current = held;
        if (current == null) return false;
        if (!current.pending().fromSamePlayer(context)) {
            drop();
            return false;
        }
        // 只读看一眼本刻还有没有机会；真正发出取消时由取消入口自己占用。
        if (context.canInteractThisTick()) {
            cancel.accept(current.pending());
        }
        return true;
    }
}
