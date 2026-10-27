// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import java.util.List;

import org.maiwithu.maicraft.kernel.task.TickContext;

/** 夜里先备武器要读的两件事：身上有没有近战武器，有哪些剑、斧可做。生产实现读客户端，测试给定值。 */
public interface ReadsMeleeWeapons {

    /** 身上（背包与双手）有没有剑、斧、重锤、三叉戟这类近战武器；镐铲锄是工具，不算。 */
    boolean carryingMelee(TickContext context);

    /** 剑与斧（按物品类或剑、斧标签认，模组的也算），按武器分从高到低：先问最好的那把做不做得出。 */
    List<String> swordsAndAxesByScore();
}
