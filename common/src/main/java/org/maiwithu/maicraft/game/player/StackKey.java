// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Set;

/**
 * 带种类的物品写法：物品 ID 分不清的东西，在后面括号里写上是哪种。
 * 现在只有药水种类一项：三种药水瓶（还有药箭）物品 ID 各只有一个，是哪种药水另记在物品里，
 * 写成 {@code minecraft:potion[potion=minecraft:fire_resistance]}；没有种类的东西就是物品 ID 本身。
 *
 * <p>身上清点、世界记忆里记的容器内容、结果里的变化都用这一种写法，读回来也由这里拆开，全仓只有这一处拼法。
 *
 * @param itemId 物品的注册 ID
 * @param potion 药水种类（药水注册表的 ID，例如 {@code minecraft:long_fire_resistance}）；不是药水或读不出时为 null
 */
public record StackKey(String itemId, String potion) {

    /** 能点名药水种类的三种药水瓶：普通的、喷溅的、滞留的；酿造台的瓶位也只收这三种。 */
    public static final Set<String> POTION_BOTTLES = Set.of(
            "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion");
    /** 不说是哪种瓶子、只点名药水种类时当普通药水瓶。 */
    public static final String PLAIN_POTION = "minecraft:potion";

    private static final String POTION_OPEN = "[potion=";
    private static final String CLOSE = "]";

    public StackKey {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("物品的注册 ID 不能为空");
        if (potion != null && potion.isBlank()) potion = null;
    }

    /** 一格物品的写法：快照里带着药水种类就写进去。 */
    public static StackKey of(BackpackStack stack) {
        return new StackKey(stack.itemId(), stack.potion());
    }

    /** 读回一种写法：括号里认得的种类拆出来，没有括号就只是物品 ID；括号写坏的整串当物品 ID，不猜。 */
    public static StackKey parse(String written) {
        int open = written.indexOf(POTION_OPEN);
        if (open > 0 && written.endsWith(CLOSE)) {
            return new StackKey(written.substring(0, open), written.substring(open + POTION_OPEN.length(),
                    written.length() - CLOSE.length()));
        }
        return new StackKey(written, null);
    }

    /** 写出来的样子：有种类时 {@code 物品ID[potion=种类]}，没有就是物品 ID。 */
    public String written() {
        return potion == null ? itemId : itemId + POTION_OPEN + potion + CLOSE;
    }

    @Override public String toString() {
        return written();
    }
}
