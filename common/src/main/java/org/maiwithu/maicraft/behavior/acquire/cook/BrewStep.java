// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Objects;

import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 酿造的一步：瓶位里是上一种药水，材料格放一样材料，冒完泡变成这一种。
 * 例如水瓶加地狱疣变粗制的药水，粗制的药水加岩浆膏变抗火药水，抗火药水加火药变喷溅的抗火药水。
 *
 * @param from       瓶位里原来的那种（带药水种类的写法）
 * @param ingredient 材料格放的那样材料（物品 ID），一炉用一件
 * @param to         酿完变成的那种
 */
public record BrewStep(StackKey from, String ingredient, StackKey to) {

    public BrewStep {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (ingredient == null || ingredient.isBlank()) throw new IllegalArgumentException("酿造的一步要有材料");
    }
}
