// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 饿急弄吃的的通报：饱食度到了危险线的那一次拿口粮，从不是角色自己放的箱子里拿到现成口粮时，
 * 通报发起的一方（由它发 others_food_taken 事件让宿主知道拿了谁家的、在哪、拿了几件、当时多饿）。
 *
 * <p>谁放的箱子都能拿（归属不拦），但饿急时只拿现成口粮、每样只拿到刚好吃回危险线以上的件数，
 * 一次弄吃的只从一只不是自己放的箱子拿，拿到过就不再通报。通报挂在最外面那一次请求上；
 * 来源备料时发的里层请求不带它。从自己放的箱子里拿不绑通报，也不限件数。
 */
public interface StarvingFoodReport {

    /** 不通报：平时的拿东西都是这一种。 */
    StarvingFoodReport NONE = new StarvingFoodReport() {
        @Override public boolean open() {
            return false;
        }

        @Override public int atMost(String itemId) {
            return 0;
        }

        @Override public StarvingFoodReport at(WorldPosition position, String ownerName) {
            return this;
        }

        @Override public boolean bound() {
            return false;
        }

        @Override public void taken(List<Taken> items) {
        }
    };

    /** 拿到的一样：物品 ID、游戏里显示的名字、从箱里确认少了几件。 */
    record Taken(String itemId, String name, int count) {}

    /** 还通报不通报：没绑到箱子，或者已经从一只不是自己放的箱子拿到过（一次只从一只拿），都不再通报。 */
    boolean open();

    /** 这一样最多拿几件（刚好够吃回危险线以上）；不能拿为 0。 */
    int atMost(String itemId);

    /** 绑到一只箱子上：带箱子在哪、放它的人的名字（角色自己放的与归属没问到的给 null），拿到了才通报得了。 */
    StarvingFoodReport at(WorldPosition position, String ownerName);

    /** 是不是已经绑到一只箱子上：开箱的一层只对绑好的按件数拿、报拿到了什么。 */
    boolean bound();

    /** 从绑定的那只箱子确认拿到了这些（箱里确认少了的件数）；一件都没拿到时不叫它。 */
    void taken(List<Taken> items);
}
