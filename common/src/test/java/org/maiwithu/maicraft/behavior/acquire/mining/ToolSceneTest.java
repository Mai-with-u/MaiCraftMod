// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.kernel.result.Attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具现场那句话：用了什么工具、磨了多少、比徒手省了几秒。同一件工具连着挖的合成一段；
 * 换过工具就按先后一件件说；空手说空手；没磨耐久就说没磨；不到一秒不提省。
 */
class ToolSceneTest {

    private static DigToolUse use(String name, String id, int blocks, int wear, int ticks, int bareTicks,
            boolean named) {
        return new DigToolUse(name, id, blocks, wear, ticks, bareTicks, named);
    }

    @Test
    void oneToolForTheWholeBatchSaysWhatItWasAndWhatItCost() {
        // 石镐挖 3 格圆石：磨 3 点，徒手要 60 刻、用了 24 刻，折成 1 秒。
        Optional<Attempt> told = ToolScene.tell(List.of(use("石镐", "minecraft:stone_pickaxe", 3, 3, 24, 60, false)));
        assertEquals(Optional.of(new Attempt("用石镐挖了 3 格", "磨掉 3 点耐久，比徒手快约 1 秒")), told);
    }

    @Test
    void bareHandsNeverWearAnything() {
        // 空手挖 2 格泥土：没磨耐久，也不比徒手快。
        assertEquals(Optional.of(new Attempt("空手挖了 2 格", "没磨耐久")),
                ToolScene.tell(List.of(use("空手", "", 2, 0, 40, 40, false))));
    }

    @Test
    void aChangedToolIsToldInOrder() {
        // 中途换了工具：按先后说两段，磨损算在一起。
        assertEquals(Optional.of(new Attempt("先用石镐挖了 2 格，又用铁镐挖了 1 格", "磨掉 2 点耐久")),
                ToolScene.tell(List.of(use("石镐", "minecraft:stone_pickaxe", 2, 2, 30, 30, false),
                        use("铁镐", "minecraft:iron_pickaxe", 1, 0, 20, 20, false))));
    }

    @Test
    void aNamedToolIsToldAsTheOneThatWasAskedFor() {
        assertEquals(Optional.of(new Attempt("用你点名的钻石镐挖了 1 格", "磨掉 1 点耐久")),
                ToolScene.tell(List.of(use("钻石镐", "minecraft:diamond_pickaxe", 1, 1, 30, 30, true))));
    }

    @Test
    void theSameToolBackToBackIsOneSegment() {
        // 同一件工具连着的两格中间没有别的工具：合成一段，不和并成两句。
        assertEquals(Optional.of(new Attempt("用石镐挖了 2 格", "磨掉 2 点耐久")),
                ToolScene.tell(List.of(use("石镐", "minecraft:stone_pickaxe", 1, 1, 15, 15, false),
                        use("石镐", "minecraft:stone_pickaxe", 1, 1, 15, 15, false))));
    }

    @Test
    void nothingDugMeansNoAttempt() {
        assertTrue(ToolScene.tell(List.of()).isEmpty());
        assertTrue(ToolScene.tell(List.of(use("石镐", "minecraft:stone_pickaxe", 0, 0, 0, 0, false))).isEmpty());
    }
}
