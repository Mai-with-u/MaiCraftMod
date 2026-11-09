// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.StarvingFoodReport;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsFoodValues.FoodValue;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 饿急弄吃的的通报：饿到危险线才带，只拿普通口粮、只拿刚好吃回线以上的件数，
 * 一次弄吃的只从一只不是自己放的箱子拿；拿到了按箱子是谁的写清事实和那句话。
 */
class StarvingTakeTest {

    private static final WorldPosition CHEST = new WorldPosition(12, 64, -8, "minecraft:overworld");

    // 面包补 5、甜浆果补 2；金苹果饱腹也能吃、腐肉带饥饿效果，都不是普通口粮。
    private static final ReadsFoodValues FOODS = itemId -> Optional.ofNullable(Map.of(
            "minecraft:bread", new FoodValue("minecraft:bread", 5, 6f, 1.6f, false, List.of()),
            "minecraft:sweet_berries", new FoodValue("minecraft:sweet_berries", 2, 0.4f, 1.6f, false, List.of()),
            "minecraft:golden_apple", new FoodValue("minecraft:golden_apple", 4, 9.6f, 1.6f, true, List.of()),
            "minecraft:rotten_flesh", new FoodValue("minecraft:rotten_flesh", 4, 0.8f, 1.6f, false,
                    List.of(new ReadsFoodValues.FoodEffect("minecraft:hunger", false)))).get(itemId));

    /** 记下吃饭任务听到的：事实与那半句话。 */
    private final List<OthersFoodTaken> facts = new ArrayList<>();
    private final List<String> whats = new ArrayList<>();

    private StarvingFoodReport allowance(int food, boolean losingHealth) {
        return StarvingTake.when(new HungerNeed.Facts(food, losingHealth, 0), FOODS, (taken, what) -> {
            facts.add(taken);
            whats.add(what);
        });
    }

    @Test
    void 刚好吃回危险线以上的件数() {
        assertEquals(1, StarvingTake.countToClimb(3, 5), "饱食度 3 拿面包：1 个就到 8");
        assertEquals(4, StarvingTake.countToClimb(0, 2), "饱食度 0 拿甜浆果：4 个才到 8");
        assertEquals(2, StarvingTake.countToClimb(1, 5), "饱食度 1 拿面包：1 个只到 6，还在线上，要 2 个");
        assertEquals(1, StarvingTake.countToClimb(6, 2), "正好在线上：至少 1 个");
    }

    @Test
    void 没饿到危险线不带通报() {
        assertSame(StarvingFoodReport.NONE, allowance(7, false), "饱食度 7 还能疾跑，照平常的路拿");
        assertTrue(allowance(6, false).open(), "饱食度 6 到了线上，带通报");
        assertSame(StarvingFoodReport.NONE, StarvingTake.when(null, FOODS, (taken, what) -> {}),
                "读不到饥饿处境不带通报");
    }

    @Test
    void 只拿普通口粮() {
        StarvingFoodReport allowance = allowance(3, false);
        assertEquals(1, allowance.atMost("minecraft:bread"));
        assertEquals(0, allowance.atMost("minecraft:golden_apple"), "金苹果这类饱腹也能吃的不拿");
        assertEquals(0, allowance.atMost("minecraft:rotten_flesh"), "带坏效果的不拿");
        assertEquals(0, allowance.atMost("minecraft:wheat"), "不能吃的不拿");
    }

    @Test
    void 拿到一次就不再通报_事实写清是谁家的() {
        StarvingFoodReport allowance = allowance(3, false);
        StarvingFoodReport bound = allowance.at(CHEST, "Alex");
        assertTrue(bound.bound());
        assertFalse(allowance.bound(), "没绑到箱子上的通报不限件数");
        bound.taken(List.of(new StarvingFoodReport.Taken("minecraft:bread", "面包", 1)));
        bound.taken(List.of(new StarvingFoodReport.Taken("minecraft:bread", "面包", 1)));

        assertEquals(1, facts.size(), "一次弄吃的只发一条");
        assertFalse(allowance.open(), "拿到过就不再通报");
        assertEquals(0, allowance.atMost("minecraft:bread"));
        OthersFoodTaken taken = facts.getFirst();
        assertEquals(OthersFoodTaken.Whose.PLAYER, taken.whose());
        assertEquals("Alex", taken.owner());
        assertEquals(List.of(new OthersFoodTaken.Item("minecraft:bread", 1)), taken.items());
        assertEquals(3, taken.food());
        assertEquals("饿到饱食度 3，从 Alex 家的箱子（12, 64, -8）拿了 1 个面包", taken.message(whats.getFirst()));
    }

    @Test
    void 说不清是谁的照实写() {
        allowance(0, true).at(CHEST, null)
                .taken(List.of(new StarvingFoodReport.Taken("minecraft:sweet_berries", "甜浆果", 4)));
        assertEquals("从不知道是谁的箱子（12, 64, -8）拿了 4 个甜浆果", whats.getLast());
        assertEquals(OthersFoodTaken.Whose.UNKNOWN, facts.getLast().whose());
        assertNull(facts.getLast().owner());
        assertTrue(facts.getLast().losingHealth());
        assertEquals("饿到饱食度 0、已经在掉血，从不知道是谁的箱子（12, 64, -8）拿了 4 个甜浆果",
                facts.getLast().message(whats.getLast()));
    }
}
