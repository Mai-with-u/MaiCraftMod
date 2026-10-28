// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 饥饿处境的生产读取：饱食度、是否在掉血、身上有没有能直接吃的。
 *
 * <p>掉血判断：饱食度 0 时原版随即开始扣血，视同正在掉血；更高饱食度不掉血。
 */
public final class LiveHungerView implements HungerNeed.ReadsFacts {

    private final ReadsFoodValues foods;

    /** 要留的东西（预留的、正要用的）：趁空当吃看的口粮不算它们；伤得重、饿到不能疾跑时照算。 */
    private final ItemsToKeep keeps;

    public LiveHungerView(ReadsFoodValues foods, ItemsToKeep keeps) {
        this.foods = Objects.requireNonNull(foods, "foods");
        this.keeps = Objects.requireNonNull(keeps, "keeps");
    }

    @Override
    public HungerNeed.Facts read(TickContext context) {
        LocalPlayer self = self(context);
        if (self == null) {
            return null;
        }
        int food = self.getFoodData().getFoodLevel();
        // 掉了一颗心以上算受了伤：饱食度不到回血线时宁可溢出一点也吃，血才回得来。
        boolean injured = self.getHealth() <= self.getMaxHealth() - 2;
        // 血不到一半算伤得重：没有普通食物时，腐肉这类垃圾食物吃一口能过回血线也吃。
        boolean badlyHurt = self.getHealth() <= self.getMaxHealth() / 2;
        // 伤得重或饿到不能疾跑时先顾身体，预留的、正要用的吃的也算口粮；只是趁空当垫一口时才绕开它们。
        // 身上补得最少的一件普通食物：按游戏的食物组件认，和吃饭任务挑吃的用同一条规则；
        // 趁空当吃要看它能不能整份补进去。
        BackpackView backpack = context.player().backpack();
        List<FoodPicker.Carried> counted = backpack == null ? List.of()
                : HungerNeed.eatsKeptFood(food, badlyHurt) ? FoodPicker.everything(backpack.stacks(), foods)
                : FoodPicker.carried(backpack.stacks(), foods, keeps);
        int smallest = counted.stream()
                .filter(candidate -> FoodPicker.plain(candidate.value()))
                .mapToInt(candidate -> candidate.value().nutrition())
                .min().orElse(0);
        int bestJunk = counted.stream()
                .filter(candidate -> FoodPicker.junk(candidate.value()))
                .mapToInt(candidate -> candidate.value().nutrition())
                .max().orElse(0);
        return new HungerNeed.Facts(food, food <= 0, smallest, injured, badlyHurt, bestJunk);
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }
}
