// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 收场事件上能给更具体说明的临时任务的特征接口：夜里自己去睡了一觉（时间一下跳到了天亮）、
 * 自卫的战况与位移，这类任务自己才知道的收场细节。
 *
 * <p>临时任务的开始与结束事件由控制循环统一发布；任务实现了它，结束事件就用它给的一句话，
 * 给不出（返回 null）就用交代结果的结论句。
 */
public interface AnnouncesFinish {

    /** 收尾时要告诉 LLM 的一句话；这次不用说为 null。 */
    String finishedNote(TaskResult result);
}
