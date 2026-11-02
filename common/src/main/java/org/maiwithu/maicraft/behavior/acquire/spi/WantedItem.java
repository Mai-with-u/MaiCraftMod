// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 想要的东西：一种具体的物品，或一个标签下的任一物品。
 * 写法沿用游戏里的注册 ID：{@code minecraft:oak_log} 是具体物品，{@code #minecraft:logs} 是标签；
 * 点名了药水种类的写成带种类的物品写法 {@code minecraft:potion[potion=minecraft:fire_resistance]}。
 * 要不要某个具体物品，问它自己：给一个物品 ID（或带种类的写法）和它挂着的标签，这里只做匹配。
 */
public record WantedItem(String specifier) {

    /** 标签写法的开头，与游戏里 "/give @s #minecraft:logs" 的习惯一致。 */
    private static final String TAG_PREFIX = "#";

    public WantedItem {
        if (specifier == null || specifier.isBlank()) {
            throw new IllegalArgumentException("想要的东西不能为空");
        }
    }

    /** 一种具体的物品，例如 minecraft:coal。 */
    public static WantedItem ofItem(String itemId) {
        if (itemId.startsWith(TAG_PREFIX)) {
            throw new IllegalArgumentException("物品 ID 不能以 # 开头：" + itemId);
        }
        return new WantedItem(itemId);
    }

    /** 一种药水：药水瓶物品加药水种类，例如普通药水瓶里的抗火药水。 */
    public static WantedItem ofPotion(String itemId, String potion) {
        return ofItem(new StackKey(itemId, potion).written());
    }

    /** 一个标签下的任一物品，例如 #minecraft:logs。 */
    public static WantedItem ofTag(String tagId) {
        return new WantedItem(TAG_PREFIX + tagId);
    }

    public boolean isTag() {
        return specifier.startsWith(TAG_PREFIX);
    }

    /** 具体物品的 ID（点名了药水种类的也只给物品 ID）；标签时没有意义，返回 null。 */
    public String itemId() {
        return isTag() ? null : StackKey.parse(specifier).itemId();
    }

    /** 点名的药水种类；没点名、或是标签时为 null。 */
    public String potion() {
        return isTag() ? null : StackKey.parse(specifier).potion();
    }

    /** 标签的 ID；具体物品时返回 null。 */
    public String tagId() {
        return isTag() ? specifier.substring(TAG_PREFIX.length()) : null;
    }

    /**
     * 一个物品算不算想要的：具体物品按 ID 相等，标签按这个物品挂着的标签里有没有它。
     * 点名了药水种类的，对方也得写着同一种才算：只有物品 ID、分不出是哪种的不算，免得把水瓶当成抗火药水；
     * 没点名种类的，带种类的写法照样按物品 ID 认。物品的标签由调用方从游戏读出来传进来，这里只做纯匹配。
     */
    public boolean matches(String candidateItemId, Set<String> candidateTags) {
        Objects.requireNonNull(candidateItemId, "candidateItemId");
        if (isTag()) return candidateTags.contains(tagId());
        StackKey wanted = StackKey.parse(specifier);
        StackKey seen = StackKey.parse(candidateItemId);
        return wanted.itemId().equals(seen.itemId())
                && (wanted.potion() == null || wanted.potion().equals(seen.potion()));
    }

    /** 背包里的一格算不算想要的：格子快照带着药水种类，按种类认。 */
    public boolean matches(BackpackStack stack, Set<String> stackTags) {
        return matches(StackKey.of(stack).written(), stackTags);
    }

    /** 给结果与日志用的一句话，例如"minecraft:coal"或"#minecraft:logs 里的任一物品"。 */
    public String describe() {
        return isTag() ? specifier + " 里的任一物品" : specifier;
    }
}
