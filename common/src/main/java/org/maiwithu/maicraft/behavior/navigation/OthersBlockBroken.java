// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

/**
 * 被别人的方块封死在小坑里时挖开的一格别人放的方块：在哪、什么方块、谁放的。
 * 出行结算逐格列出，LLM 据此决定要不要还、跟谁说一声。
 *
 * @param at       坐标（短写法 "x, y, z"）
 * @param block    方块 ID
 * @param placedBy 放它的玩家：在线的写名字，不在线的写玩家编号（UUID）
 */
public record OthersBlockBroken(String at, String block, String placedBy) {}
