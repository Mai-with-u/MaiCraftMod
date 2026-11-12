// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.util.Objects;

/**
 * 角色身上某一只精妙背包所在的位置与物品注册 ID。
 *
 * <p>精妙背包除了放在原版主背包、胸甲格与副手，还常背在 Curios、Accessories 等饰品栏里；
 * 各栏位在模组内部都有独立的栏位名、子分组名和从 0 起算的槽位下标，不能混用原版背包格号。
 *
 * @param inventoryName 模组登记的栏位名（例如 curios、armor、offhand、main）
 * @param slotGroup     栏位下的子分组名（饰品栏是槽位类型名如 back，原版栏位为空串）
 * @param slotIndex     该分组内从 0 起算的槽位下标
 * @param itemId        这只背包本身的物品注册 ID
 */
public record BackpackSlot(String inventoryName, String slotGroup, int slotIndex, String itemId) {

    public BackpackSlot {
        Objects.requireNonNull(inventoryName, "inventoryName");
        Objects.requireNonNull(slotGroup, "slotGroup");
        Objects.requireNonNull(itemId, "itemId");
    }

    /** 这只背包所在的栏位定位，用作存货记忆的键与精确开包的目标。 */
    public Location location() {
        return new Location(inventoryName, slotGroup, slotIndex);
    }

    /**
     * 随身背包在角色身上的栏位定位（不含物品种类）：同一格开包读到的存货记在这个定位名下，
     * 发打开请求时也照这三项原样交给服务端。
     */
    public record Location(String inventoryName, String slotGroup, int slotIndex) {

        /** 不指定栏位，请服务端按模组默认顺序找身上第一只精妙背包。 */
        public static final Location FIRST_CARRIED = new Location("", "", -1);

        public Location {
            Objects.requireNonNull(inventoryName, "inventoryName");
            Objects.requireNonNull(slotGroup, "slotGroup");
        }

        /** 原版主背包（0..35 格）里的定位。 */
        public static Location mainSlot(int slotIndex) {
            return new Location("main", "", slotIndex);
        }

        /** 是否位于原版主背包 36 格内：开着这只包搬东西时，主背包这一格本身就是这只包，不能再点起它。 */
        public boolean inMainInventory() {
            return inventoryName.equals("main") && slotIndex >= 0 && slotIndex < 36;
        }

        /** 给观察视图看的简短栏位名：饰品栏带分组名，胸甲、副手、主背包用统一叫法。 */
        public String whereCode() {
            return switch (inventoryName) {
                case "curios", "accessories" -> slotGroup.isEmpty() ? inventoryName : inventoryName + ":" + slotGroup;
                case "armor" -> "chest";
                case "offhand" -> "offhand";
                case "main" -> "inventory";
                default -> inventoryName.isEmpty() ? "carried" : inventoryName;
            };
        }

        /** 给任务记录与失败说明看的中文位置，说清卡住的是哪一只包。 */
        public String describe() {
            return switch (inventoryName) {
                case "curios", "accessories" -> "饰品栏"
                        + (slotGroup.isEmpty() ? "" : "（" + slotGroup + "）")
                        + "第 " + slotIndex + " 格的背包";
                case "armor" -> "胸甲格的背包";
                case "offhand" -> "副手的背包";
                case "main" -> "主背包第 " + slotIndex + " 格的背包";
                default -> slotIndex >= 0 ? "第 " + slotIndex + " 格的背包" : "身上的背包";
            };
        }
    }
}
