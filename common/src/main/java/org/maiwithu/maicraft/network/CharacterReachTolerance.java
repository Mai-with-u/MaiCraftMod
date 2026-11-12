// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

/**
 * 角色距离容差的握手能力与容差常数：原版服务端在方块与实体的触及距离之外留有 1.0 格网络与移动余量，
 * 服务端 MaiCraft 对名单内的角色账号再加 1.0 格刹车与挤退余量（合计 2.0 格），普通玩家完全不变。
 * 客户端经握手协商确认这台服务端是否带有这项能力，未升级的旧服务端自动退回原版 1.0 格口径。
 */
public final class CharacterReachTolerance {

    /** 握手协商的能力编号：非变更能力，仅用于在欢迎包里向客户端表明服务端已开启角色距离容差。 */
    public static final String OPERATION = "character.reach";

    /** 握手能力的协议版本。 */
    public static final int VERSION = 1;

    /** 原版服务端处理方块右键、破坏与实体交互时自带的距离余量（格）。类别：游戏事实。 */
    public static final double VANILLA_PADDING = 1.0;

    /** 服务端对准入的角色账号额外放开的刹车与挤退距离余量（格）。类别：游戏事实。 */
    public static final double CHARACTER_EXTRA_PADDING = 1.0;

    /** 角色账号在新服务端上的总距离余量（格）：原版 1.0 格加角色额外 1.0 格。类别：游戏事实。 */
    public static final double CHARACTER_PADDING = VANILLA_PADDING + CHARACTER_EXTRA_PADDING;

    private CharacterReachTolerance() {}

    /** 按本次连接握手是否协商到角色距离容差给出服务端实际放行的距离余量（格）。 */
    public static double serverPadding(boolean negotiated) {
        return negotiated ? CHARACTER_PADDING : VANILLA_PADDING;
    }
}
