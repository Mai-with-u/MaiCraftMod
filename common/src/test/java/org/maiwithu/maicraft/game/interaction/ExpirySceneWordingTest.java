// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.result.RawTextLeaks;

/**
 * 跟在确认记录后面的现场说明写成中文：确认窗过期的现场（原版当场接没接、命中的拒收条件、开着的界面、
 * 游戏模式）与右键方块的点击现场，不夹英文句子，也不夹界面类名、枚举原名这类原始写法。
 */
class ExpirySceneWordingTest {

    /** 过期现场的每一种组合：原版结论、拒收条件、开着的界面、游戏模式。 */
    @Test
    void everyExpirySceneReadsAsChinese() {
        List<String> texts = new ArrayList<>();
        List<EntityUseScene> scenes = Arrays.asList(null,
                new EntityUseScene(true, 1.5, 3.0, false, null),
                new EntityUseScene(true, 5.0, 3.0, true, 60.0F),
                new EntityUseScene(false, 1.0, 3.0, false, null));
        List<GameType> modes = new ArrayList<>(Arrays.asList(GameType.values()));
        modes.add(null);
        for (Boolean answer : Arrays.asList(null, true, false)) {
            for (EntityUseScene scene : scenes) {
                for (String screen : Arrays.asList(null, "", "箱子")) {
                    for (GameType mode : modes) {
                        texts.add(ExpiryContext.describe(answer, scene, screen, mode));
                    }
                }
            }
        }
        RawTextLeaks.assertNoneIn(texts);
        assertTrue(ExpiryContext.describe(null, null, "箱子", GameType.SURVIVAL).contains("开着「箱子」界面"));
        assertTrue(ExpiryContext.describe(null, null, null, GameType.CREATIVE).endsWith("游戏模式是创造"));
    }

    /** 右键方块点击现场里手、面、原版结算的说法：每一个枚举值都换成玩家的说法。 */
    @Test
    void useOnTraceWordsCoverEveryHandFaceAndNativeResult() {
        List<String> texts = new ArrayList<>();
        for (InteractionHand hand : InteractionHand.values()) texts.add(UseOnTrace.handName(hand));
        for (Direction face : Direction.values()) texts.add(UseOnTrace.faceName(face) + "面");
        for (InteractionResult result : InteractionResult.values()) texts.add(UseOnTrace.nativeVerdict(result));
        RawTextLeaks.assertNoneIn(texts);
        assertEquals("拒绝了这一下", UseOnTrace.nativeVerdict(InteractionResult.FAIL));
        assertEquals("接受了这一下", UseOnTrace.nativeVerdict(InteractionResult.CONSUME));
    }
}
