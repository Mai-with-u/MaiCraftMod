// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 闲时看开、关的界面：打开背包、打开手边的容器看一眼、翻开配方页，看完关上；
 * 也回答屏幕上是不是有别人开的界面（聊天框、暂停菜单、人开的箱子），有就不做闲时看。
 *
 * <p>只关自己开的：别人开的界面任何时候都不碰。生产实现读写当刻的角色界面，离线测试换成记账的替身。
 */
interface LookScreens {

    /** 屏幕上开着不是闲时看开的界面。 */
    boolean othersOpen();

    /** 闲时看开的界面还开着（配方页退回背包也算还开着）。 */
    boolean oursOpen();

    /** 打开自己的背包；界面真的开出来了返回真，还没好返回假，下一刻再问。 */
    boolean showInventory();

    /** 打开手边这只容器看一眼的动作：站在原地点开、等内容同步完、记进世界记忆就做完；没开成按失败收场，不重试。 */
    Action peek(LookPlans.Screen.Container container);

    /** 装了能翻开配方页的查看器（EMI、JEI）。 */
    boolean opensRecipePages();

    /** 翻到这样东西的配方页（uses 为真翻用途页）；翻开了返回真。 */
    boolean openRecipePage(String itemId, boolean uses);

    /** 关上闲时看开的界面：容器按原版关（光标上的东西放回），配方页、背包一样关掉。关不完的下一刻接着关。 */
    void closeOurs();
}
