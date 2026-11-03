// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

/**
 * 挖下来的格子各用了什么工具、磨了多少、花了多久。给结果里那句工具现场用。
 *
 * <p>挖掘时装上一件工具就攒一段：同一件工具连着挖的几格合成一段，换了工具（方块种类变了重判、点名那一件换上来）
 * 就另起一段。这里只装现场事实，拼成话、算省了多少由玩家行为层做。
 *
 * @param toolName  手里那件叫什么（物品名；空手时是"空手"）
 * @param toolId    物品注册表 ID；空手时是空串
 * @param blocks    这一段挖了几格
 * @param wear      这一段一共磨掉多少耐久
 * @param ticks     这一段一共花了多少游戏刻
 * @param bareTicks 同样这几格徒手要多少游戏刻：报省了多少时作对照
 * @param named     这一段是不是目标里点名要用的那件
 */
public record DigToolUse(String toolName, String toolId, int blocks, int wear, int ticks, int bareTicks, boolean named) {

    /** 同一件工具（同一个名字、同样是空手、同样是不是点名）连着挖的合成一段。 */
    public boolean sameToolAs(DigToolUse other) {
        return named == other.named && toolId.equals(other.toolId) && toolName.equals(other.toolName);
    }

    /** 接上同样一件工具又挖了几格。 */
    public DigToolUse plus(int moreBlocks, int moreWear, int moreTicks, int moreBareTicks) {
        return new DigToolUse(toolName, toolId, blocks + moreBlocks, wear + moreWear, ticks + moreTicks,
                bareTicks + moreBareTicks, named);
    }
}
