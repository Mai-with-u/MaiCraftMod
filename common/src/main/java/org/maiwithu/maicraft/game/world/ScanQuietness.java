// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

/**
 * 方块扫描的安静判定：连续多少刻索引没有新入账（没有补建新的区块段条目），这一片就算看稳了。
 * 纯记账，不读世界；读端每刻把扫描服务报的累计入账数喂进来一次。
 *
 * <p>方块索引靠视野渐进补齐，刚清空或刚登记新目标时查询很快就能"走完"已建的部分，
 * 但这时下的"没有"其实是"还没看清"。读端在安静之前不得把空结果当成结论；
 * 入账数变少（换世界清账后从头补）与变多一样算"还在补"，重新计时。
 */
public final class ScanQuietness {

    /** 连续这么多刻没有新入账就算安静：一秒足够确认补账真的停了，不拖慢正常回答。 */
    public static final int DEFAULT_QUIET_TICKS = 20;

    private final int quietAfterTicks;
    private long lastBuilt;
    private boolean observed;
    private int quietTicks;

    public ScanQuietness() {
        this(DEFAULT_QUIET_TICKS);
    }

    public ScanQuietness(int quietAfterTicks) {
        if (quietAfterTicks <= 0) {
            throw new IllegalArgumentException("安静判定的刻数必须为正");
        }
        this.quietAfterTicks = quietAfterTicks;
    }

    /** 读端每刻喂一次扫描服务报的累计入账数；与上次一样就在安静计时上走一刻，不一样（含变少）就重新计时。 */
    public void observe(long sectionsBuilt) {
        if (observed && sectionsBuilt == lastBuilt) {
            quietTicks++;
        } else {
            quietTicks = 0;
        }
        lastBuilt = sectionsBuilt;
        observed = true;
    }

    /** 连续安静刻数够了吗；一次都没喂过时不算安静。 */
    public boolean quiet() {
        return observed && quietTicks >= quietAfterTicks;
    }
}
