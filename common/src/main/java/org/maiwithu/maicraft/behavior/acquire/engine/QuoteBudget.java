// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

/**
 * 一次估算的问价额度：向一个来源问一次价扣一次；做法排先后时为一样原料问遍各来源一遍，也扣一次。
 * 扣完了再问一律不答，并数下被拒了几回：拿不全的原料价排出来的报价、缺料清单都不作数，由估算说没估完。
 *
 * <p>额度按次数数、不按时间量：同样的现场估几遍结果都一样，离线测试也复现得出。
 */
final class QuoteBudget {

    private final int limit;
    private int used;
    private int ingredientAsks;
    private int refused;

    QuoteBudget(int limit) {
        this.limit = limit;
    }

    /** 向一个来源问一次价：还有额度就扣一次答应，没有了数一回被拒。 */
    boolean takeQuote() {
        return take();
    }

    /** 排做法时为一样原料问遍各来源一遍：同样扣一次，另记一笔，日志里分得清是哪种问价多。 */
    boolean takeIngredientAsk() {
        if (!take()) return false;
        ingredientAsks++;
        return true;
    }

    private boolean take() {
        if (used >= limit) {
            refused++;
            return false;
        }
        used++;
        return true;
    }

    /** 已经扣了几次。 */
    int used() {
        return used;
    }

    /** 扣的里面有几次是排做法时问原料价。 */
    int ingredientAsks() {
        return ingredientAsks;
    }

    /** 额度用完后被拒了几回：前后一比，就知道中间那次报价、那次列缺料有没有拿到不全的原料价。 */
    int refused() {
        return refused;
    }
}
