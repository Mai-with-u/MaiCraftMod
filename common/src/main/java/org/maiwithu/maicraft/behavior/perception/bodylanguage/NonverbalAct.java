// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

/**
 * 认出来的一个肢体动作：哪一种、哪一刻、冲没冲着角色，和一句细节（拿出了什么、挥向哪、扔了什么落在哪）。
 *
 * @param kind     哪一种
 * @param tick     认出来的那一刻（蹲起是站起来那刻，点头是最后一下抬回来那刻）
 * @param count    这一下算几下（点头、摇头一串认一次，写几下；别的都是 1）
 * @param towardMe 冲着角色：看着角色做的、挥向角色的、扔到角色身边的、拍到角色的
 * @param detail   细节：拿出的东西、挥向的东西、扔的东西与落点、走开的方向与格数；没有为空串
 */
public record NonverbalAct(Kind kind, long tick, int count, boolean towardMe, String detail) {

    /** 肢体动作的种类，和能力 body_language 的 operation 同一套词。 */
    public enum Kind {
        /** 按下潜行又松开，蹲着不到 1 秒。 */
        CROUCH,
        /** 对着什么挥了一下手：不是在挖方块、不是打中了别的东西、不是放方块、不是扔东西带的那一下。 */
        SWING,
        /** 拍了角色一下：打中角色，或对着够得着的角色挥手。 */
        POKE,
        /** 主手换了东西。 */
        HOLD,
        /** 点头：上下点视角。 */
        NOD,
        /** 摇头：左右晃视角。 */
        SHAKE_HEAD,
        /** 原地跳：离地升高、再落地，水平挪动不到 1 格。 */
        JUMP,
        /** 扔东西：新掉落物从他手里抛出。 */
        TOSS,
        /** 一段肢体动作之后走开几格，又回头看着角色。 */
        WALK
    }

    public NonverbalAct {
        if (detail == null) detail = "";
    }
}
