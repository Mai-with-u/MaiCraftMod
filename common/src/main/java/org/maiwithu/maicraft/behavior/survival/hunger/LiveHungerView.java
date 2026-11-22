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
        // 血不到一半算伤得重：没有普通食物时，腐肉这类垃圾食物也先吃，吃到回血线或吃完为止。
        boolean badlyHurt = self.getHealth() <= self.getMaxHealth() / 2;
        // 身上补得最少的一件可直接吃的普通食物（排除可烹饪生食材）：
        // 不把生鱼、生肉计入趁空当触发阈值，消除饱食 18 刚钓起一条营养 2 的生鱼就立刻引爆吃饭的问题。
        BackpackView backpack = context.player().backpack();
        List<FoodPicker.Carried> counted = backpack == null ? List.of()
                : HungerNeed.eatsKeptFood(food, badlyHurt) ? FoodPicker.everything(backpack.stacks(), foods)
                : FoodPicker.carried(backpack.stacks(), foods, keeps);
        int smallest = counted.stream()
                .filter(candidate -> FoodPicker.readyPlain(candidate.value()))
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
