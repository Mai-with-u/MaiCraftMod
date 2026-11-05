// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.spi.OthersStorageAllowance;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 饿急了从别人的箱子拿了吃的：挂在 others_food_taken 事件上给宿主按字段处理的事实。
 * 别人可能会问起，宿主据此知道拿了谁的、在哪、拿了什么几件、当时多饿。
 *
 * @param whose        箱子是谁的：归属记录上的人、记住的区域里、看着像玩家放的、归属还没问到
 * @param owner        放它的人，在线写名字，不在线写编号；只在 {@link Whose#PLAYER} 时有
 * @param region       记住的区域叫什么；只在 {@link Whose#REGION} 时有
 * @param at           箱子在哪
 * @param items        拿到的东西，每样从箱里确认少了几件
 * @param food         拿之前的饱食度，和 observe(self) 的 food 是同一个数
 * @param losingHealth 那时是不是已经饿得掉血
 */
public record OthersFoodTaken(Whose whose, String owner, String region, WorldPosition at, List<Item> items, int food,
        boolean losingHealth) implements EventFacts {

    /** 箱子是谁的。 */
    public enum Whose { PLAYER, REGION, LOOKS_PLAYER_MADE, UNKNOWN }

    /** 拿到的一样：物品 ID 与件数。 */
    public record Item(String item, int count) {}

    @Override public String field() {
        return "others_food_taken";
    }

    /** 按这只箱子是谁的、拿到了什么记下事实；玩家编号经 names 换成名字。 */
    static OthersFoodTaken of(StorageUse use, WorldPosition at, List<OthersStorageAllowance.Taken> taken, int food,
            boolean losingHealth, Function<String, String> names) {
        List<Item> items = new ArrayList<>();
        for (OthersStorageAllowance.Taken one : taken) items.add(new Item(one.itemId(), one.count()));
        return switch (use.kind()) {
            case PLACED_BY_OTHER -> new OthersFoodTaken(Whose.PLAYER, names.apply(use.placedBy()), null, at, items, food,
                    losingHealth);
            case IN_REMEMBERED_REGION -> new OthersFoodTaken(Whose.REGION, null, use.place(), at, items, food, losingHealth);
            case LOOKS_PLAYER_MADE -> new OthersFoodTaken(Whose.LOOKS_PLAYER_MADE, null, null, at, items, food, losingHealth);
            default -> new OthersFoodTaken(Whose.UNKNOWN, null, null, at, items, food, losingHealth);
        };
    }

    /**
     * "从谁家的箱子拿了几个什么"那半句，例如"从 Alex 家的箱子（12, 64, -8）拿了 2 个面包"；
     * 事件消息和吃饭任务结束时那句都用它。
     */
    static String what(StorageUse use, WorldPosition at, List<OthersStorageAllowance.Taken> taken,
            Function<String, String> names) {
        String from = switch (use.kind()) {
            case PLACED_BY_OTHER -> "从 " + names.apply(use.placedBy()) + " 家的箱子";
            case IN_REMEMBERED_REGION -> "从记住的区域「" + use.place() + "」里的箱子";
            case LOOKS_PLAYER_MADE -> "从看着像玩家放的箱子";
            default -> "从不知道是谁的箱子";
        };
        List<String> parts = new ArrayList<>();
        for (OthersStorageAllowance.Taken one : taken) parts.add(one.count() + " 个" + one.name());
        return from + "（" + at.x() + ", " + at.y() + ", " + at.z() + "）拿了 " + String.join("、", parts);
    }

    /** 事件消息：先说多饿，再说从谁家的箱子拿了什么。 */
    String message(String what) {
        return "饿到饱食度 " + food + (losingHealth ? "、已经在掉血" : "") + "，" + what;
    }
}
