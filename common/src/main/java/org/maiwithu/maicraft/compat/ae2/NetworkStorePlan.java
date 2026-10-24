// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

/**
 * 往网络里存几件：按件存准，count 是件数不是组数。纯函数，不读世界也不动手。
 *
 * <p>快速移动（Shift 点背包格）一下就是一整格，只要存的量还够一整格才用得上；剩最后不足一整格时，
 * 从挑中的那一格把整份拿上光标、把多余的几件一件件退回源格，凑出正好要存的几件再整份交给网络——
 * 不截量的话 count=8 会把一整格 64 件全塞进网络。格子的挑法：整格用得上的先挑最大的那格（存完正好腾出一整格），
 * 每格都比要存的多才拆第一格。
 */
public final class NetworkStorePlan {

    private NetworkStorePlan() {}

    /** 下一步做什么。 */
    public sealed interface Move {
    }

    /** 快速移动背包的这一整格：格里的件数不超过还要存的。 */
    public record QuickMove(int slotIndex) implements Move {
    }

    /** 把这一格整份拿上光标、退回多余的件，光标上留正好 keep 件交给网络。 */
    public record TrimToCursor(int slotIndex, int slotCount, int keep) implements Move {
    }

    /** 这一样存完了：够数了，或背包里这种东西没了。 */
    public record Done() implements Move {
    }

    /**
     * 算下一步：从背包里这种东西的各格件数里挑怎么存。
     *
     * @param remaining  还要存几件
     * @param slotCounts 背包里这种东西每一格的件数，按格子顺序；空格不传
     */
    public static Move next(int remaining, List<Integer> slotCounts) {
        if (remaining <= 0) return new Done();
        int whole = -1;
        int first = -1;
        for (int i = 0; i < slotCounts.size(); i++) {
            int count = slotCounts.get(i);
            if (count <= 0) continue;
            if (first < 0) first = i;
            if (count <= remaining && (whole < 0 || count > slotCounts.get(whole))) whole = i;
        }
        if (whole >= 0) return new QuickMove(whole);
        if (first >= 0) return new TrimToCursor(first, slotCounts.get(first), remaining);
        return new Done();
    }
}
