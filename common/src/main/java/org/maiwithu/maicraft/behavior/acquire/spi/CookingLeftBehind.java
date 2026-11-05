// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 拿东西途中有没有装好一炉就走：原料燃料留在炉子里接着烧，人先走了。拿东西的引擎每推进一步看一眼，
 * 这一步里留下了一炉，就整场当场收场、不再换别的路——东西还在炉子里烧着，换路去弄是白跑一趟。
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

    /** 这个世界里到此刻一共装好炉就走过几回：一步前后比一比，多了就是这一步留下的。 */
    int timesLeft();

    /** 最近留下的那一炉怎么跟 LLM 说：哪台炉子在哪、烧几件什么、约几分钟好、计时编号；只在留下过之后问。 */
    Problem lastLeft();
}
