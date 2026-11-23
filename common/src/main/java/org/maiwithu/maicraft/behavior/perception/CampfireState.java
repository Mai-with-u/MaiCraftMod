// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.Objects;

/**
 * 原版营火此刻的实时状态：是否点燃、总烤位数与当前架上正在烤的食物列表。
 *
 * <p>空位不写入列表，避免编造空槽；原版客户端只同步架上物品而不发送已烤刻数，
 * 读不到实时进度时 {@link CookingItem#elapsedSeconds()} 与 {@link CookingItem#remainingSeconds()} 为 null。
 *
 * @param lit      营火此刻是否点燃
 * @param capacity 总烤位数（原版营火固定为 4）
 * @param cooking  当前架上非空烤位的食物列表
 */
public record CampfireState(boolean lit, int capacity, List<CookingItem> cooking) {

    public CampfireState {
        cooking = cooking == null ? List.of() : List.copyOf(cooking);
    }

    /**
     * 营火一个非空烤位上的食物。
     *
     * @param item             物品注册 ID
     * @param cookSeconds      配方总烤制时长（秒）；查不到对应营火配方时为 null
     * @param elapsedSeconds   已烤制秒数；客户端读不到进度时为 null，不编造
     * @param remainingSeconds 预计剩余秒数；客户端读不到进度时为 null，不编造
     */
    public record CookingItem(String item, Integer cookSeconds, Integer elapsedSeconds, Integer remainingSeconds) {

        public CookingItem {
            Objects.requireNonNull(item, "item");
        }

        /** 仅知晓物品与配方总时长时的构造（客户端未同步实时进度时使用）。 */
        public CookingItem(String item, Integer cookSeconds) {
            this(item, cookSeconds, null, null);
        }
    }

    /** 给观察详情用的客观说明：按当前点燃状态与是否放有食物说明烤熟自动弹出规则。 */
    public String note() {
        if (lit) {
            return cooking.isEmpty()
                    ? "营火已点燃，当前没有正在烤的食物；放上可烤食物后烤熟（原版约 30 秒）会自动弹出成掉落物，无需右键取下"
                    : "点燃状态下烤熟（原版约 30 秒）会自动弹出成掉落物，无需右键取下";
        }
        return cooking.isEmpty()
                ? "营火目前未点燃，不会烤熟食物；请先用打火石或火焰弹点火"
                : "营火目前未点燃，架上的食物不会烤熟且无法右键取下；请先用打火石或火焰弹点火";
    }
}
