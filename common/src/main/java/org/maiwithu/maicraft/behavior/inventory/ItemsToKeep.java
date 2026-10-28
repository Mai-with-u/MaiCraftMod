// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 要留的东西：角色自己做主花背包里的东西时，每种至少留几件。全仓只有这一道关——现做工具、自动挑工具、
 * 垫脚与临时方块、挖楼梯填空洞、战斗垫柱子、趁空吃一口、腾背包、不给物品的存放，都读同一份。
 *
 * <p>它由两半合起来：调用方用 reserve 写下的预留（{@link ReservedItems}），加上自动算的正要用的东西
 * （上一个目标刚拿到的、手上这件事正在拿的）。挖了三块圆石要做石镐，爬上来时又垫下去；攒着盖房的木板，
 * 挖路时被拿去做了镐——这一道关就是防这两种事。保命时（饿到不能疾跑、血不到一半）由调用方自己先用不留的、
 * 不够再动留着的，不在这里开口子。
 */
@FunctionalInterface
public interface ItemsToKeep {

    /** 什么都不留：只给测试摆现场用，生产代码里一律传真的要留的东西。 */
    ItemsToKeep NOTHING = itemId -> 0;

    /** 这一样全都留着：多少件都不动。 */
    int ALL = Integer.MAX_VALUE;

    /** 这种物品要留几件；不留的给 0，全都留着给 {@link #ALL}。 */
    int keep(String itemId);

    /** 两份要留的合起来：同一种物品两笔各算各的、加在一起留（宁可多留）；有一笔是全都留着就全都留着。 */
    default ItemsToKeep plus(ItemsToKeep other) {
        return itemId -> {
            long sum = (long) keep(itemId) + other.keep(itemId);
            return sum >= ALL ? ALL : (int) sum;
        };
    }
}
