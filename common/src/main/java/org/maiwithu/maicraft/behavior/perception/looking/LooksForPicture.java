// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拍画面的路径交给闲时看的窄接口：要看哪里，闲时看决定转不转、怎么转，转到位了告诉它。
 * 在客户端线程上调。带一个什么都不转的实现给测试与没接上闲时看时用。
 */
public interface LooksForPicture {

    /** 什么都不转：照镜头此刻拍。 */
    LooksForPicture NONE = (context, subject, automationOwns, temporaryTask) -> new PictureLook.Turn.NoNeed();

    /**
     * 为拍画面转头。
     *
     * @param automationOwns 自动化拿着角色（人按了 F8 为假）
     * @param temporaryTask  生存需求的临时任务此刻占着身体
     */
    PictureLook.Turn lookForPicture(TickContext context, PictureLook.Subject subject, boolean automationOwns,
            boolean temporaryTask);
}
