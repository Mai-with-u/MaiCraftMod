// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.spi.OthersStorageAllowance;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 饿急了从别人的箱子拿吃的的放行：饱食度到了危险线（不能疾跑那条），这一次弄吃的可以从别人的箱子拿口粮。
 *
 * <p>只放行普通口粮（没坏效果、不是金苹果这类随时能吃的），每样只拿到刚好吃回危险线以上的件数；
 * 一次弄吃的只从一只别人的箱子拿，拿到过就不再放行。拿到了经 {@link Told} 告诉吃饭任务，由它发事件让宿主知道。
 * 哪几类口粮带这份放行由弄吃的定：现做才能吃的生肉、生鱼那一大类不带。
 */
final class StarvingTake implements OthersStorageAllowance {

    /** 从别人的箱子拿到了：附带的事实，和"从谁家的箱子拿了几个什么"那半句。 */
    interface Told {
        void took(OthersFoodTaken facts, String what);
    }

    private final int food;
    private final boolean losingHealth;
    private final ReadsFoodValues foods;
    /** 玩家编号换成名字：在线写名字，不在线写编号。 */
    private final Function<String, String> names;
    private final Told told;
    /** 已经从一只别人的箱子拿到过：这一次弄吃的不再放行。 */
    private boolean spent;

    private StarvingTake(int food, boolean losingHealth, ReadsFoodValues foods, Function<String, String> names, Told told) {
        this.food = food;
        this.losingHealth = losingHealth;
        this.foods = Objects.requireNonNull(foods, "foods");
        this.names = Objects.requireNonNull(names, "names");
        this.told = Objects.requireNonNull(told, "told");
    }

    /**
     * 这一回弄吃的给不给放行：饱食度在危险线及以下才给（和饥饿报"不能疾跑"同一条线）；
     * 没饿到线（只是想垫一口、伤得重想吃点回血）、读不到处境，都不放行，照旧不碰别人的箱子。
     */
    static OthersStorageAllowance when(HungerNeed.Facts hunger, ReadsFoodValues foods, Function<String, String> names,
            Told told) {
        if (hunger == null || hunger.food() > HungerNeed.SPRINT_FLOOR) return NONE;
        return new StarvingTake(hunger.food(), hunger.losingHealth(), foods, names, told);
    }

    /**
     * 拿几件刚好吃回危险线以上：最少几件，使"饱食度 + 件数 × 这一样一件补的饱食度"超过危险线，至少 1 件。
     * 饱食度 3 拿面包（补 5）拿 1 个；饱食度 0 拿甜浆果（补 2）拿 4 个。
     */
    static int countToClimb(int food, int nutrition) {
        if (nutrition <= 0) return 0;
        return Math.max(1, Math.max(0, HungerNeed.SPRINT_FLOOR - food) / nutrition + 1);
    }

    @Override public boolean open() {
        return !spent;
    }

    // 普通口粮才放行，件数按这一样一件补多少算；拿到过一次之后一律不放行。
    @Override public int atMost(String itemId) {
        if (spent) return 0;
        return foods.of(itemId).filter(FoodPicker::plain).map(value -> countToClimb(food, value.nutrition())).orElse(0);
    }

    @Override public OthersStorageAllowance at(StorageUse use, WorldPosition position) {
        return new AtStorage(use, position);
    }

    @Override public boolean bound() {
        return false;
    }

    // 还没绑到具体的箱子上，拿到什么都记不了是谁的：不会被叫到，叫到也不记。
    @Override public void taken(List<Taken> items) {
    }

    /** 绑到一只别人的箱子上的放行：拿到了按这只箱子是谁的、在哪告诉吃饭任务，之后整份放行就用掉了。 */
    private final class AtStorage implements OthersStorageAllowance {

        private final StorageUse use;
        private final WorldPosition position;

        AtStorage(StorageUse use, WorldPosition position) {
            this.use = use;
            this.position = position;
        }

        @Override public boolean open() {
            return StarvingTake.this.open();
        }

        @Override public int atMost(String itemId) {
            return StarvingTake.this.atMost(itemId);
        }

        @Override public OthersStorageAllowance at(StorageUse other, WorldPosition where) {
            return StarvingTake.this.at(other, where);
        }

        @Override public boolean bound() {
            return true;
        }

        // 一次弄吃的只从一只别人的箱子拿：报过一次就用掉，后面再报（同一只箱子重开）不再发第二条事件。
        @Override public void taken(List<Taken> items) {
            if (spent || items.isEmpty()) return;
            spent = true;
            OthersFoodTaken facts = OthersFoodTaken.of(use, position, items, food, losingHealth, names);
            told.took(facts, OthersFoodTaken.what(use, position, items, names));
        }
    }
}
