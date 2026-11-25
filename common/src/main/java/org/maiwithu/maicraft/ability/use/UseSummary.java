// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 组装 use 任务最终一句话结论的纯判断助手：把各类特殊现场（未能放入而落块在旁格、放进未知加工容器、
 * 点开未知界面、拆解转化、顺手塞入模组界面、营火放食、普通生效）统一收口，减轻任务主类的状态拼装负担。
 */
final class UseSummary {

    private UseSummary() {}

    /** 收场拼装所需的只读现场快照。 */
    record Facts(String targetLabel, String requestedItem, long appliedTimes, String tail,
            boolean placedIntoUnrecognizedProcessor,
            boolean openedUnrecognizedBlockMenu, boolean consumedHeldInUse,
            String openedMenu, String disassembledSummary,
            UseSeams.MenuLook.Insertion menuInsertion, String menuInsertItem,
            String campfireSummary) {}

    /** 根据交互后的真实世界变化拼出面向玩家与上层 Agent 的最终任务结果。 */
    static TaskResult finish(Facts facts) {
        if (facts.placedIntoUnrecognizedProcessor()
                || (facts.openedUnrecognizedBlockMenu() && facts.consumedHeldInUse())) {
            return TaskResult.done(facts.targetLabel()
                    + "：放进去了，不确认会不会做出东西（确认生效了 " + facts.appliedTimes() + " 次）" + facts.tail());
        }
        if (facts.openedUnrecognizedBlockMenu()) {
            String note = facts.requestedItem() != null
                    ? "：点开了不认识的加工或容器界面，手上的 " + facts.requestedItem() + " 没有放进去，不确认会不会做出东西，已关上界面"
                    : "：点开了不认识的界面，看不出里面有什么、也不确认会不会做出东西，已关上界面";
            return TaskResult.done(facts.targetLabel() + note + facts.tail());
        }
        String openedNote = facts.openedMenu() == null || facts.openedMenu().isBlank()
                ? "" : "（里面有：" + facts.openedMenu() + "）";
        if (facts.disassembledSummary() != null) {
            return TaskResult.done(facts.targetLabel() + "：手上的 " + facts.disassembledSummary() + facts.tail());
        }
        if (facts.menuInsertion() == UseSeams.MenuLook.Insertion.INSERTED && facts.menuInsertItem() != null) {
            return TaskResult.done(facts.targetLabel() + "：已把 " + facts.menuInsertItem()
                    + " 放进界面" + openedNote + facts.tail());
        }
        if (facts.menuInsertion() == UseSeams.MenuLook.Insertion.REJECTED && facts.menuInsertItem() != null) {
            return TaskResult.done(facts.targetLabel() + "：点开了界面，但手上的 " + facts.menuInsertItem()
                    + " 没有放进去（槽位已满或界面不收此物品）" + openedNote + facts.tail());
        }
        if (facts.campfireSummary() != null) {
            return TaskResult.done(facts.targetLabel() + "：" + facts.campfireSummary() + facts.tail());
        }
        return TaskResult.done(facts.targetLabel() + "：确认生效了 " + facts.appliedTimes() + " 次"
                + openedNote + facts.tail());
    }

    /** 把点开的容器/界面内容表格式化为可读清单（空表返回“空的”）。 */
    static String formatMenuContents(Map<String, Integer> contents) {
        if (contents.isEmpty()) return "空的";
        List<String> lines = new ArrayList<>(contents.size());
        contents.forEach((itemId, count) -> lines.add(itemId + " ×" + count));
        return String.join("、", lines);
    }

    /** 阶段名称的中文展示，用于进度日志与状态回报。 */
    static String describePhase(UseTask.Phase value) {
        return switch (value) {
            case RESOLVE -> "找目标";
            case TRAVEL -> "走到目标那一片";
            case FETCH -> "去拿要用的东西";
            case HAND -> "准备手上的东西";
            case APPROACH -> "靠近目标";
            case CLEAR -> "打掉压在目标上面的草木";
            case INTERACT -> "对目标用一下";
            case WRITE -> "在告示牌上写字";
            case LOOK -> "看看点开的界面";
            case COLLECT -> "捡起掉出的东西";
        };
    }
}
