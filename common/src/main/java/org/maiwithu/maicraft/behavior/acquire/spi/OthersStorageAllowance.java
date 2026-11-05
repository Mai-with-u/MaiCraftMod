// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 别人的存储放行：这一次拿东西能不能从别人的箱子里拿、哪几样各最多拿几件，拿到了告诉谁。
 *
 * <p>平时拿东西一律不碰别人的箱子，请求上带的是 {@link #NONE}。生存上的例外（饿到危险线、身上和能用的箱子里都没口粮）
 * 由发起的那一层给一份放行，挂在最外面那一次请求上；来源备料时发的里层请求不带它，
 * 现做吃的要的燃料、原料照旧不碰别人的箱子。
 *
 * <p>挑箱子的一层（容器来源）只在能用的箱子都没有时才看别人的箱子，看中一只就把放行绑到那只上（{@link #at}）
 * 再交给开箱的一层；开箱的一层按 {@link #atMost} 只拿一样、拿到件数为止，关箱前把从箱里确认少了的报回 {@link #taken}。
 */
public interface OthersStorageAllowance {

    /** 不放行：平时的拿东西都是这一种。 */
    OthersStorageAllowance NONE = new OthersStorageAllowance() {
        @Override public boolean open() {
            return false;
        }

        @Override public int atMost(String itemId) {
            return 0;
        }

        @Override public OthersStorageAllowance at(StorageUse use, WorldPosition position) {
            return this;
        }

        @Override public boolean bound() {
            return false;
        }

        @Override public void taken(List<Taken> items) {
        }
    };

    /** 拿到的一样：物品 ID、游戏里显示的名字、从箱里确认少了几件。 */
    record Taken(String itemId, String name, int count) {}

    /** 还放不放行：没放行，或者已经从一只别人的箱子拿到过（一次只从一只拿），都不再放行。 */
    boolean open();

    /** 这一样从别人的箱子里最多拿几件；不能拿为 0。 */
    int atMost(String itemId);

    /** 绑到一只具体的别人的箱子上：拿到了，放行的人才知道是哪只、记在谁名下。 */
    OthersStorageAllowance at(StorageUse use, WorldPosition position);

    /** 是不是已经绑到一只别人的箱子上：开箱的一层只对绑好的放行按件数拿、报拿到了什么。 */
    boolean bound();

    /** 从绑定的那只箱子确认拿到了这些（箱里确认少了的件数）；一件都没拿到时不叫它。 */
    void taken(List<Taken> items);
}
