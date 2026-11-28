// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 联动模组停用或接口对不上时，包装类对调用方的降级回答，全部接缝共用这一份：
 * 模组停用了就不碰模组，直接交调用方给的"用不了"答案；还开着就照常读，
 * 读到模组接口对不上（联动入口已转成 ModApiMismatch 并停用模组）也交同一个答案。
 * 模组装错版本不是程序错误，不能让调用方的任务以内部错误收场。
 *
 * <p>停用原因的标准说法由 {@link #reason} 给出（"某模组的联动已停用：原因"）；
 * 接口对不上时对不上环节的说明取自 ModApiMismatch 自己的消息。
 */
final class DegradedAnswer {

    private DegradedAnswer() {}

    /** 读一样东西：停用了或对不上时交回给定的那个答案。 */
    static <T> T ask(CompatModule module, Supplier<? extends T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }

    /** 读一样东西：停用了或对不上时，把原因（停用原因，或对不上的说明）交给调用方拼出自己的回答。 */
    static <T> T ask(CompatModule module, Supplier<? extends T> read, Function<String, ? extends T> whenUnavailable) {
        if (!module.active()) return whenUnavailable.apply(reason(module));
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable.apply(broken.getMessage());
        }
    }

    /** 做一件不回答什么的动作：停用了就不做；做的时候对不上就收住，不再往外抛。 */
    static void run(CompatModule module, Runnable doIt) {
        if (!module.active()) return;
        try {
            doIt.run();
        } catch (ModApiMismatch ignored) {
            // 已经停用，联动入口的日志里写过原因。
        }
    }

    /** 停用原因的标准说法；没停用时调它没有意义。 */
    static String reason(CompatModule module) {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
