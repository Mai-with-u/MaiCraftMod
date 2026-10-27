// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 主目标的许可把起居类生存需求关掉了（survival_needs=off）的特征接口：吃饭、睡觉、补光、捡回遗物这类
 * 按身体状态安排的需求不再插队，角色能把手里的一件事干完；对眼前危险的反应（自卫、换气、脱险）照插，
 * 不能干活干到淹死。目标外壳（目标推进器）带着这条许可声明它；寻死这类一个生存需求都不要的任务
 * 声明的是 {@link SurvivalNeedsOff}。目标暂停、等回答时不关。
 */
public interface RoutineNeedsOff {

    /** 是否关掉起居类的生存需求。 */
    boolean routineNeedsOff();
}
