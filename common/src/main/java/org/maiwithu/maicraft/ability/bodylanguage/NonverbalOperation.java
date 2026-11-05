// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.Arrays;
import java.util.Optional;

/**
 * 角色做的一种肢体动作：参数里的写法、不写 count 时做几下、最多几下、说法。
 * 拿出东西、捡东西、拍一下只做一下；扔东西的 count 是扔几件。
 */
enum NonverbalOperation {
    LOOK("look", 1, 1, "看着"),
    CROUCH("crouch", 3, 10, "蹲起"),
    SWING("swing", 2, 6, "挥手"),
    NOD("nod", 2, 5, "点头"),
    SHAKE_HEAD("shake_head", 2, 5, "摇头"),
    JUMP("jump", 2, 5, "原地跳"),
    HOLD("hold", 1, 1, "拿出"),
    TOSS("toss", 1, 64, "扔给"),
    PICK_UP("pick_up", 1, 1, "捡起"),
    POKE("poke", 1, 1, "拍一下");

    final String param;
    final int defaultCount;
    final int maxCount;
    final String words;

    NonverbalOperation(String param, int defaultCount, int maxCount, String words) {
        this.param = param;
        this.defaultCount = defaultCount;
        this.maxCount = maxCount;
        this.words = words;
    }

    /** 参数写法对应的那一种；写错为空。 */
    static Optional<NonverbalOperation> of(String param) {
        return Arrays.stream(values()).filter(op -> op.param.equals(param)).findFirst();
    }

    /** 参数规格里能选的写法。 */
    static String[] params() {
        return Arrays.stream(values()).map(op -> op.param).toArray(String[]::new);
    }

    /** 必须对着一个人做：扔给他、拍他一下没人就做不成。 */
    boolean needsSomeone() {
        return this == TOSS || this == POKE;
    }

    /** 要走过去：扔东西走到他 2 格内、拍一下走到够得着他、捡东西走到那件旁边。 */
    boolean walks() {
        return this == TOSS || this == POKE || this == PICK_UP;
    }
}
