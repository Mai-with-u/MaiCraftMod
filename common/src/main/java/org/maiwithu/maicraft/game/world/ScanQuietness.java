// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

/**
 * 方块扫描的安静判定：连续多少个游戏刻索引没有新入账（没有补建新的区块段条目），这一片就算看稳了。
 * 纯记账，不读世界；读端每次查询把扫描服务报的累计入账数连同当前游戏刻一起喂进来，安静按刻走、不按喂的次数走。
 *
 * <p>方块索引靠视野渐进补齐，刚清空或刚登记新目标时查询很快就能"走完"已建的部分，
 * 但这时下的"没有"其实是"还没看清"。读端在安静之前不得把空结果当成结论；
 * 入账数变少（换世界清账后从头补）与变多一样算"还在补"，重新计时。
 * 问价的等扫描窗只有几秒，期间隔一两百毫秒才轮询一次：安静若按喂的次数计，
 * 二十次"刻"要喂满五秒，恰好卡死在窗里永远到不了，扫描早停了也答不完——
 * 所以计时以游戏刻为准，隔着几秒再来问，一眼就能确认补账停了没。
 */
public final class ScanQuietness {

    /** 连续这么多刻没有新入账就算安静：一秒足够确认补账真的停了，不拖慢正常回答。 */
    public static final int DEFAULT_QUIET_TICKS = 20;

    private final int quietAfterTicks;
    private long lastBuilt;
    private long lastChangeTick;
    private boolean observed;

    public ScanQuietness() {
        this(DEFAULT_QUIET_TICKS);
    }

    public ScanQuietness(int quietAfterTicks) {
        if (quietAfterTicks <= 0) {
            throw new IllegalArgumentException("安静判定的刻数必须为正");
        }
        this.quietAfterTicks = quietAfterTicks;
    }

    /** 读端把扫描服务报的累计入账数连同当前游戏刻喂进来；与上次一样就不动安静计时，不一样（含变少）就从头计。 */
    public void observe(long sectionsBuilt, long nowTick) {
        if (!observed || sectionsBuilt != lastBuilt) {
            lastChangeTick = nowTick;
        }
        lastBuilt = sectionsBuilt;
        observed = true;
    }

    /** 到这一刻为止安静满限定刻数了吗；一次都没喂过时不算安静。 */
    public boolean quiet(long nowTick) {
        return observed && nowTick - lastChangeTick >= quietAfterTicks;
    }
}
