// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 为拍画面转头：LLM 要看一样东西的画面时，角色腾得出头就用闲时看的那一回看转过去，到位了再拍；
 * 停一会儿再按闲时看的规矩慢慢转回站定朝向。手上有活、骑着坐着、人按着 F8、屏幕上开着别人的界面时不转，
 * 照镜头此刻拍，并说清为什么没转。
 *
 * <p>这里放判断（纯函数）和请求、结论的形状；转头本身由闲时看做，全仓转头看东西只有那一份。
 */
public final class PictureLook {

    /** 转过去以后在那儿停多久再转回（刻）：读后看看一样东西的最短时间，零点八秒。 */
    static final int HOLD_TICKS = 16;
    /** 不给要看的东西、只是先转回站定朝向时停多久（刻）：到了就拍，不多停。 */
    static final int STANDING_HOLD_TICKS = 2;

    private PictureLook() {}

    /** 此刻为什么不转头。 */
    public enum WhyNot {
        /** 人按 F8 拿着角色：鼠标在人手里，拍的就是人正看着的。 */
        HUMAN_CONTROLS,
        /** 生存需求的临时任务占着身体（自卫、换气、灭火……）。 */
        TEMPORARY_TASK,
        /** 主目标在推进：走路、挖方块、放方块都按镜头朝向结算，扭头会带歪它。 */
        WORKING,
        /** 没脚踏实地：骑着坐着（马跟着视角转身）、在飞（鞘翅靠视角操纵）、在睡、悬在空中。 */
        NOT_ON_FOOT,
        /** 屏幕上开着别人开的界面（聊天框、暂停菜单、人开的箱子）。 */
        OTHERS_SCREEN
    }

    /** 要看哪里。 */
    public sealed interface Subject {
        /** 观察编号或记住的地点名：看得见的对准它，看不见的只朝大致方位瞥。 */
        record Seen(String idOrLandmark) implements Subject {}

        /**
         * 世界里的一点（建筑档案的外框中心）。
         *
         * @param roughly 此刻看不见：只朝大致方位瞥，不精确对准
         */
        record Point(double x, double y, double z, boolean roughly) implements Subject {}

        /** 不看哪样东西，只要镜头回到站定朝向（闲时看正转头看着别处时）。 */
        record Standing() implements Subject {}
    }

    /** 这次转不转。 */
    public sealed interface Turn {
        /** 不转，照镜头此刻拍。 */
        record NotTurning(WhyNot why) implements Turn {}

        /** 用不着转：没给要看的东西，镜头本来就在站定朝向（或要看的那样东西已经找不到了）。 */
        record NoNeed() implements Turn {}

        /** 在转：转到位（真）或转够两秒还没到位（假）时交回，被收手时也交回假。 */
        record Turning(CompletableFuture<Boolean> arrived) implements Turn {}
    }

    /**
     * 为什么此刻不能为拍画面转头；能转为空。纯函数。
     *
     * <p>人在操作最先看（鼠标是人的），其次临时任务、主目标在推进，最后是身体与屏幕：
     * 和闲时看"只转头"的条件同一份，只是实例配置的开关不管这里（这是 LLM 要看，不是演给观众看）。
     *
     * @param automationOwns   自动化拿着角色
     * @param temporaryTask    生存需求的临时任务占着身体
     * @param playerCanLook    脚踏实地，没骑着、没坐着、没睡着、没飞着，背包已同步
     * @param othersScreenOpen 屏幕上开着别人开的界面
     */
    public static Optional<WhyNot> whyNotTurn(IdleLooking.Availability availability, boolean automationOwns,
            boolean temporaryTask, boolean playerCanLook, boolean othersScreenOpen) {
        if (!automationOwns) return Optional.of(WhyNot.HUMAN_CONTROLS);
        if (temporaryTask) return Optional.of(WhyNot.TEMPORARY_TASK);
        if (availability == IdleLooking.Availability.TAKEN) return Optional.of(WhyNot.WORKING);
        if (!playerCanLook) return Optional.of(WhyNot.NOT_ON_FOOT);
        if (othersScreenOpen) return Optional.of(WhyNot.OTHERS_SCREEN);
        return Optional.empty();
    }
}
