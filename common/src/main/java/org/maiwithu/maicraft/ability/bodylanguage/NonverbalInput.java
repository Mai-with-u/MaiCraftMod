// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 做一种肢体动作的任务输入：做哪种、几下、手里拿着什么、看多久、对着谁。次数与时长在这里已经是校验过的。
 *
 * @param count       做几下；扔东西是扔几件
 * @param itemId      拿出、扔出的东西，或做的时候手里拿着的（物品 ID 或 # 标签）；不拿为 null
 * @param lookTicks   只用于看着：看多少刻
 * @param target      对着谁（捡东西是地上那件）；不给为 null，对着最近冲角色做过肢体动作的人
 */
record NonverbalInput(NonverbalOperation operation, int count, String itemId, int lookTicks, Target target)
        implements TaskInput {

    NonverbalInput {
        if (operation == null) throw new IllegalArgumentException("要做哪种肢体动作不能为空");
        if (count < 1 || count > operation.maxCount) throw new IllegalArgumentException("次数超出范围：" + count);
    }

    @Override
    public String describe() {
        String what = switch (operation) {
            case LOOK -> "看着他 " + lookTicks / 20 + " 秒";
            case HOLD -> itemId == null ? "空出手" : "拿出 " + itemId;
            case TOSS -> "扔给他 " + count + " 件 " + itemId;
            case PICK_UP -> "捡起扔过来的东西";
            case POKE -> "拍他一下";
            default -> operation.words + " " + count + " 下";
        };
        return itemId != null && operation != NonverbalOperation.HOLD && operation != NonverbalOperation.TOSS
                ? "拿着 " + itemId + " " + what : what;
    }
}
