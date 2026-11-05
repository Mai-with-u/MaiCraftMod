// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

/**
 * 别的玩家某一刻的样子：在哪、在不在地上、头朝哪、低头抬头多少、蹲没蹲、主手拿着什么、看没看着角色。
 * 每刻对看得见的玩家采一次，认蹲起、点头、摇头、跳、拿出东西、走开又回头都从这串采样里认。
 *
 * @param tick        游戏刻
 * @param headYaw     头的朝向（度，原版口径：0 朝南，顺时针增大）
 * @param pitch       俯仰（度，往下为正）
 * @param crouching   按着潜行、蹲着的姿势
 * @param mainHand    主手拿着的东西的名字；空手为空串
 * @param lookingAtMe 他的视线离角色身体不到 1 格
 */
public record PlayerPose(long tick, double x, double y, double z, boolean onGround, float headYaw, float pitch,
                         boolean crouching, String mainHand, boolean lookingAtMe) {
}
