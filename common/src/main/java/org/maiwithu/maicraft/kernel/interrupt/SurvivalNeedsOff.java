// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 一个生存需求都不要的任务的特征接口：这件事本身就要角色不自救（例如寻死）。
 * 它是主任务时，控制循环一个生存需求都不插，不在打断规则里开后门。许可的 {@code survival_needs=off}
 * 走的是 {@link RoutineNeedsOff}，只停起居类需求。内核与需求不认识任何具体能力，只认这些接口。
 */
public interface SurvivalNeedsOff {

    /** 此刻是否一个生存需求都不插。 */
    boolean survivalNeedsOff();
}
