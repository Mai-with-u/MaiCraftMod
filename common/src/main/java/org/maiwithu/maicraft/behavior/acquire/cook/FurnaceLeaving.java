// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

/**
 * 装炉就走的判断：这一炉还要烧多久、值不值得走开，回到留下的那台时里面还是不是那一炉、有什么要照实说。
 * 只看给进来的数，不碰游戏。
 */
final class FurnaceLeaving {

    /** 这一炉还要烧这么久以上（一分钟）就装好炉先走：小活站着等完，不来回跑。 */
    static final int LEAVE_AFTER_TICKS = 20 * 60;

    private FurnaceLeaving() {}

    /**
     * 回到留下的那台点开看到的。
     *
     * @param ours    里面还是自己那一炉：投入口是这次的原料，或产出格是这次的成品
     * @param finding 要照实记进 attempts 的一句；没什么要说为 null
     */
    record Return(boolean ours, String finding) {}

    /** 投入口 loaded 件、手上这件烧了 cooking 成，这一炉还要烧几刻：按配方写的烧一件多久估。 */
    static long cookTicksLeft(int loaded, float cooking, int cookTicks) {
        return Math.max(0, Math.round((loaded - Math.max(0f, Math.min(1f, cooking))) * cookTicks));
    }

    /** 这一炉还要烧这么多刻，值不值得装好就走：一分钟以上才走，小活站着等完。 */
    static boolean leavesNow(long cookTicksLeft) {
        return cookTicksLeft > LEAVE_AFTER_TICKS;
    }

    /**
     * 回到留下的那台点开：投入口里有几件这次的原料、产出格里有几件这次的成品。都没有就是被人动过了
     * （取走、换成别的、漏斗抽走），不当成烧好了；投入口里还有、却已经过了估的烧完时刻，是炉子停过
     * （角色走远时区块不加载就不烧），照实说烧好几件、还有几件没烧。
     *
     * @param inside 当时炉子里放着什么的说法，被人动过时原样写进去
     */
    static Return onReturn(LeftCooking cooking, int ingredientsInside, int resultsInside, String inside, long now) {
        if (ingredientsInside <= 0 && resultsInside <= 0) {
            return new Return(false, "里面已经不是留下的那一炉：" + inside + "；可能被人取走、或被漏斗抽走了，不当成烧好了");
        }
        if (ingredientsInside > 0 && now >= cooking.readyAtGameTick()) {
            return new Return(true, "到点了还没烧完：烧好 " + resultsInside + " 个，还有 " + ingredientsInside
                    + " 个没烧（角色走远时炉子所在的区块不加载，炉子就停着），接着烧");
        }
        return new Return(true, null);
    }

    /** 回哪台取什么：attempts 里那一条的 tried。 */
    static String returnTried(LeftCooking cooking) {
        return "回 " + cooking.where() + " 的" + cooking.kind().displayName() + "取" + cooking.resultName();
    }
}
