// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.List;

/**
 * 装备与卸下的目标栏位：主手、副手和四格护甲（头上、身上、腿上、脚上）。
 *
 * <p>参数里用小写英文名（mainhand、offhand、head、chest、legs、feet），与玩家在游戏里的说法一致；
 * 结果、说明这类给人读的文字里报栏位用中文说法（{@link #displayName()}）。
 * 整套卸下不在这里表达，由调用方逐格遍历护甲四格完成。
 */
public enum GearSlotName {
    MAINHAND("mainhand", "主手"),
    OFFHAND("offhand", "副手"),
    HEAD("head", "头上"),
    CHEST("chest", "身上"),
    LEGS("legs", "腿上"),
    FEET("feet", "脚上");

    private final String paramName;
    private final String displayName;

    GearSlotName(String paramName, String displayName) {
        this.paramName = paramName;
        this.displayName = displayName;
    }

    /** 参数里的写法，例如 mainhand。 */
    public String paramName() {
        return paramName;
    }

    /** 栏位的中文说法，例如 主手、头上：结果与说明里提到栏位都用它，参数取值才用英文名。 */
    public String displayName() {
        return displayName;
    }

    /** 护甲四格，从头到脚：清点身上穿着的、脱下护甲拿去用都按这个顺序看。 */
    public static List<GearSlotName> armor() {
        return List.of(HEAD, CHEST, LEGS, FEET);
    }

    /**
     * 这一格在角色背包界面里的槽位号：头盔 5、胸甲 6、护腿 7、靴子 8。
     *
     * <p>背包界面里排在前面的合成格与产出格占了前五个槽位，护甲四格接着排，主手与副手不在护甲格上，给 -1。
     * 穿戴与卸下都要经这个界面搬东西，按这个号点才对得上格。
     */
    public int armorScreenSlot() {
        return switch (this) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
    }

    /** 把参数取值读成栏位；写法不对时返回 null，由调用方决定怎么拒绝。 */
    public static GearSlotName fromParam(String value) {
        for (GearSlotName slot : values()) {
            if (slot.paramName.equals(value)) return slot;
        }
        return null;
    }
}
