// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.menu.OpenScreen;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;

/**
 * 结束事件附的一行自身摘要：在哪（含维度）、血、饱食、在水里时的氧气、手上拿着什么、开着什么界面。
 * 例如"在 minecraft:overworld 12, 64, -8；血 18，饱食 15；手上 minecraft:iron_pickaxe；开着储物界面（minecraft:generic_9x3，任务点开的）"。
 *
 * <p>只一行，不附背包：LLM 读到目标结束要接着决定下一步，先知道人在哪、血够不够、手上什么、界面关没关；
 * 背包大，要看用 observe(self)。和 observe(self) 读的是同一份场景里的自身观察。
 */
public final class SelfBrief {
    private SelfBrief() {}

    /**
     * @param self      角色自己的观察事实
     * @param dimension 所在维度；读不到为 null
     * @param screen    开着的界面；没开为空
     */
    public static String line(SceneSelf self, String dimension, Optional<OpenScreen> screen) {
        StringBuilder text = new StringBuilder("在 ");
        if (dimension != null) text.append(dimension).append(' ');
        text.append((int) Math.floor(self.x())).append(", ").append((int) Math.floor(self.y())).append(", ")
                .append((int) Math.floor(self.z()));
        text.append("；血 ").append(healthText(self.shownHealth())).append("，饱食 ").append(self.food());
        // 氧气只在水里写：陆上总是满的，写了也是噪音；水里憋着气时是要不要先上浮的依据。
        if (self.inWater()) {
            text.append("；在水里，氧气 ").append(self.air()).append('/').append(self.maxAir());
        }
        text.append("；").append(hands(self));
        screen.ifPresent(open -> text.append("；").append(MenuView.phrase(open)));
        return text.toString();
    }

    // 整数血照旧写成"18"，带小数的写一位（"0.9"）：低于 1 的存活血量不写成 0，免得读成已死。
    static String healthText(double shown) {
        return shown == Math.rint(shown) ? Long.toString((long) shown) : Double.toString(shown);
    }

    // 主手与副手分别写明：主手空而副手有物时写清主手空，两手都拿着东西（哪怕同种）也一并写清副手。
    private static String hands(SceneSelf self) {
        String main = self.heldItem();
        String offhand = self.offhandItem();
        if (main == null && offhand == null) return "两手空着";
        if (main == null) return "主手空（副手 " + offhand + "）";
        if (offhand == null) return "手上 " + main;
        return "手上 " + main + "（副手 " + offhand + "）";
    }
}
