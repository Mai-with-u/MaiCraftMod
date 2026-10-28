// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 目标运行存储：目标推进的记录在这里保存与读回。内核只认这个接缝；真正的存盘实现
 * （SQLite 文档存储）在存储移植里接线，替换进来时目标推进的代码不用改。
 *
 * <p>内存实现只用于测试和还没接存储时的过渡。实现方负责把 {@link GoalRun} 连同它的目标、
 * 步骤、回答完整保存，重启后能原样读回。
 */
public interface GoalRunStore {

    /** 分配下一个目标运行编号；编号在全存储内唯一、只增不减。 */
    long nextId();

    /** 保存一次目标推进的记录；已存在的同编号记录整体覆盖。 */
    void save(GoalRun run);

    /** 还没结束的目标运行（进行中、等回答、暂停），按开始顺序；重启后要恢复的就是它们。 */
    List<GoalRun> unfinished();

    /**
     * 目标定下结果之前，存储链上谁要按这次的变化补一笔就在这里补（例如动了预留的就扣掉、在变化上记清几件是预留的），
     * 返回补过的结果；默认原样。包在别的存储外面的一层要把它转给里面那一层。只含目标自己的事实，
     * 临时任务（吃饭、自卫）交代的变化不在这里。
     */
    default TaskResult settling(GoalRun run, TaskResult result) {
        return result;
    }
}
