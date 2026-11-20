// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 拿东西途中有没有装好一炉或投进箱式加工站就走：原料留在设施里接着加工，人先走了。
 * 拿东西的引擎每推进一步看一眼，这一步里留下了，就整场当场收场、不再换别的路——东西还在加工中，换路去弄是白跑一趟。
 */
public interface CookingLeftBehind {

    /** 没接上留着烧的记录：从来没留下过一炉。 */
    CookingLeftBehind NONE = new CookingLeftBehind() {
        @Override public int timesLeft() {
            return 0;
        }

        @Override public Problem lastLeft() {
            throw new IllegalStateException("没有留下过一炉，不该问怎么说");
        }
    };

    /** 合并两份在跑留场账本（例如原版熔炉与箱式加工站）：任意一份新增记录时都能及时拦住引擎干净收场。 */
    static CookingLeftBehind combine(CookingLeftBehind first, CookingLeftBehind second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return new CookingLeftBehind() {
            private int lastFirst = first.timesLeft();
            private int lastSecond = second.timesLeft();
            private CookingLeftBehind latest;

            @Override public int timesLeft() {
                int nowFirst = first.timesLeft();
                int nowSecond = second.timesLeft();
                if (nowSecond > lastSecond) {
                    latest = second;
                } else if (nowFirst > lastFirst) {
                    latest = first;
                }
                lastFirst = nowFirst;
                lastSecond = nowSecond;
                return nowFirst + nowSecond;
            }

            @Override public Problem lastLeft() {
                timesLeft();
                if (latest != null) return latest.lastLeft();
                if (second.timesLeft() > 0) return second.lastLeft();
                return first.lastLeft();
            }
        };
    }

    /** 这个世界里到此刻一共装好就走过几回：一步前后比一比，多了就是这一步留下的。 */
    int timesLeft();

    /** 最近留下的那一笔怎么跟 LLM 说：在哪、加工几件什么、约多久好、计时编号；只在留下过之后问。 */
    Problem lastLeft();
}
