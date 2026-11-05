// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.result.RawTextLeaks;

/**
 * 确认记录写成中文结论句：交互提交方每一种收场留下的那句话、任务边界收场的说法，
 * 都说清确认到了什么、没确认到什么，不夹英文句子，也不夹类名这类原始写法。
 */
class ConfirmationWordingTest {

    @Test
    void everySettlementOfTheRealSenderReadsAsChinese() {
        List<String> details = SettledConfirmations.all();
        RawTextLeaks.assertNoneIn(details);
        // 每一种收场都确实走到了：生效、没生效各有一句自己的话，没有空串。
        assertEquals("游戏确认这一下生效了", details.get(0));
        assertEquals("游戏确认这一下没有生效", details.get(1));
        assertTrue(details.stream().noneMatch(String::isBlank));
    }

    /** 放置类到期按没生效收场：句子说清看了哪几样、都没变，所以这一下没有生效。 */
    @Test
    void anExpiredPlacementSaysWhatWasWatchedAndThatNothingChanged() {
        String detail = SettledConfirmations.expiredWithoutChange();
        RawTextLeaks.assertNone(detail);
        assertTrue(detail.contains("手上的东西、目标格、界面、附近实体都没有变化"));
        assertTrue(detail.endsWith("这一下没有生效"));
    }

    /** 任务边界收场的说法：调用方给的原因在前，后面说提交出去的这一下可能已经生效。 */
    @Test
    void boundaryWordingKeepsTheCallersReasonFirst() {
        RawTextLeaks.assertNoneIn(List.of(DefaultInteractionSender.mayHaveApplied(null),
                DefaultInteractionSender.mayHaveApplied("挖方块的任务结束时切工具还在等确认"),
                MiningActions.TASK_ENDED, MiningActions.STOP_THREW));
        assertEquals("走到停下时右键还没等到游戏确认；提交出去的这一下可能已经生效",
                DefaultInteractionSender.mayHaveApplied("走到停下时右键还没等到游戏确认"));
    }
}
