// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

/**
 * 翻开配方页：配方查看器（EMI、JEI）把一样东西的配方页或用途页翻开给人看，像玩家按 R、按 U 那样。
 *
 * <p>查配方本身照旧只读（{@link RecipeViewer}）；翻页是闲时看在 LLM 查过配方后另外叫的，只是给观众看。
 * 只在客户端线程上调用。
 */
public interface OpensRecipePage {

    /**
     * 翻到这样东西的配方页（uses 为真翻用途页）。
     *
     * @param itemId 物品 ID，例如 minecraft:torch
     * @return 页面真的翻开了；查看器里没有这一样、还没加载完、联动停用时为假
     */
    boolean open(String itemId, boolean uses);
}
