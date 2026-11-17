// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.List;
import java.util.Locale;

import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 穿卸装备的任务输入：穿还是卸、哪个栏位、穿哪件。栏位写 armor 表示整套护甲卸下，
 * 只配合卸下——穿戴永远一次一件。
 *
 * <p>结果里的说法按栏位分家：往主手、副手拿东西是"拿到/腾出"，四格护甲才是"穿上/卸下"——
 * 往主手拿一把剑报成"穿上"会让人以为在穿盔甲。这里同时知道穿还是卸与目标栏位，说法都从这里出。
 *
 * @param unequip     true 是卸下，false 是穿上
 * @param slot        目标栏位；整套护甲卸下时为 null
 * @param itemId      要穿的物品 ID；卸下与候选自动选时不为空由任务决定，这里只在点名时带值
 * @param permissions 这次任务的许可：卸下前腾背包要走路，走过去能动多少地形按它来
 */
record EquipInput(boolean unequip, GearSlotName slot, String itemId, Permissions permissions) implements TaskInput {

    /** 整套护甲的四格。 */
    static final List<GearSlotName> ARMOR_SLOTS =
            List.of(GearSlotName.HEAD, GearSlotName.CHEST, GearSlotName.LEGS, GearSlotName.FEET);

    EquipInput {
        if (slot == null && !unequip) throw new IllegalArgumentException("穿装备必须指明栏位");
        permissions = permissions == null ? Permissions.DEFAULT : permissions;
    }

    /** 这次要动的栏位：单个栏位就是它自己，armor 是四格护甲。 */
    List<GearSlotName> slots() {
        if (slot != null) return List.of(slot);
        return ARMOR_SLOTS;
    }

    /** 主手与副手：动作说法是拿到/腾出，与护甲的穿上/卸下分开。 */
    static boolean handSlot(GearSlotName slot) {
        return slot == GearSlotName.MAINHAND || slot == GearSlotName.OFFHAND;
    }

    /** 这次动作的动词：手部栏位是拿到/腾出，四格护甲是穿上/卸下。 */
    private String verb() {
        return handSlot(slot) ? (unequip ? "腾出" : "拿到") : (unequip ? "卸下" : "穿上");
    }

    /** 这次动作的说法：手部栏位动词自带栏位（"拿到主手"），护甲只有动词（"穿上"）。 */
    String actionWord() {
        if (slot != null && handSlot(slot)) return verb() + slot.displayName();
        return verb();
    }

    /** 一个栏位的动作说法：手部是"拿到主手"，护甲是"穿上 头上"；记试过的动作、还差哪栏都列它。 */
    String actionLabel(GearSlotName slot) {
        return handSlot(slot) ? verb() + slot.displayName() : verb() + " " + slot.displayName();
    }

    /** 没能确认时"确认某栏位结果"的说法，列进还差的事里。 */
    String confirmLabel(GearSlotName slot) {
        return handSlot(slot)
                ? "确认" + slot.displayName() + (unequip ? "腾出没有" : "拿到没有")
                : "确认 " + slot.displayName() + " 的" + (unequip ? "卸下" : "穿上") + "结果";
    }

    /** 穿好了一个栏位的记账说法：手部是"拿到了主手"，护甲是"穿在了 头上"。 */
    String wornNote(GearSlotName slot) {
        return handSlot(slot) ? verb() + "了" + slot.displayName() : verb() + "在了 " + slot.displayName();
    }

    /** 穿上了却没能确认的记账说法：手部是"拿到主手的操作没能确认"，护甲是"穿上 头上 的操作没能确认"。 */
    String unconfirmedNote(GearSlotName slot) {
        return handSlot(slot) ? actionLabel(slot) + "的操作没能确认" : verb() + " " + slot.displayName() + " 的操作没能确认";
    }

    @Override
    public String describe() {
        String itemText = itemId == null ? "" : " " + itemId;
        // 整套护甲卸下没有单栏位的动作说法；穿戴必须点名栏位，走不到这里。
        if (slot == null) return "卸下 整套护甲";
        return actionLabel(slot) + itemText;
    }

    /** 把参数里的栏位取值读成栏位；armor 是整套护甲卸下的写法，不在这里展开。 */
    static GearSlotName parseSlot(String value) {
        if ("armor".equals(value)) return null;
        GearSlotName slot = GearSlotName.fromParam(value);
        if (slot == null) {
            throw new IllegalArgumentException("栏位取值不对：" + value.toLowerCase(Locale.ROOT));
        }
        return slot;
    }
}
