// SPDX-License-Identifier: GPL-3.0-only
/**
 * 肢体动作能力 {@code maicraft:body_language}：看着一个人蹲起、挥手、点头、摇头、原地跳、拿出东西、扔东西给他、
 * 捡他扔来的、拍他一下，或者只是看着他。执行方式是"插一下"，几秒做完不替换手上的目标。
 *
 * <p>蹲起、点头、摇头、跳、挥手的节奏现在只有这一个调用方，先放在能力里。换手、走路、扔、捡、转头、按攻击键
 * 都用玩家行为层与游戏接口层现成的那一份。Java 类型名里不用 Body，肢体动作写 Nonverbal。
 */
package org.maiwithu.maicraft.ability.bodylanguage;
