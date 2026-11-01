// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 角色自己的观察事实：位置、脸的朝向（东南西北）、生命、饥饿、氧气、手持、护甲、
 * 状态效果，以及落地或入水。场景里的方位都拿它当基准，所以先有它再整理别的观察。
 *
 * @param facingYaw 角色的视角角：0 朝南，顺时针增大
 * @param heldItem 手上拿着的物品注册 ID，两手都空时为 null
 * @param gearBars 身上装备与手里东西自己画的那条还剩多少（耐久、背罐的气、电量）
 * @param offhandItem 副手拿着的物品注册 ID，空着为 null（整理背包时看得出副手攥着什么）
 * @param health 生命值原样（带小数，一格是半颗心）；对外展示用 {@link #shownHealth()}
 */
public record SceneSelf(
        double x, double y, double z, float facingYaw, String facingWord,
        float health, int food, int air, int maxAir,
        String heldItem, List<String> armor, List<String> effects,
        boolean onGround, boolean inWater, List<SelfSight.GearBar> gearBars, String offhandItem) {

    public SceneSelf {
        armor = List.copyOf(armor);
        effects = List.copyOf(effects);
        gearBars = gearBars == null ? List.of() : List.copyOf(gearBars);
    }

    /** 不读副手的一份：离线测试用。 */
    public SceneSelf(double x, double y, double z, float facingYaw, String facingWord, float health, int food, int air,
                     int maxAir, String heldItem, List<String> armor, List<String> effects, boolean onGround,
                     boolean inWater, List<SelfSight.GearBar> gearBars) {
        this(x, y, z, facingYaw, facingWord, health, food, air, maxAir, heldItem, armor, effects, onGround, inWater,
                gearBars, null);
    }

    /** 不带物品条的一份：离线测试用。 */
    public SceneSelf(double x, double y, double z, float facingYaw, String facingWord, float health, int food, int air,
                     int maxAir, String heldItem, List<String> armor, List<String> effects, boolean onGround,
                     boolean inWater) {
        this(x, y, z, facingYaw, facingWord, health, food, air, maxAir, heldItem, armor, effects, onGround, inWater,
                List.of());
    }

    /**
     * 对外展示的血量：保留一位小数，向上取到 0.1。还活着（血量大于 0）就不会显示成 0——
     * 服务端血 0.83 时显示 0.9，LLM 不会把还能行动的角色读成已死。死没死不看这个数，看死亡事件。
     */
    public double shownHealth() {
        return shownHealth(health);
    }

    /** 把一个生命值换成展示用的数：大于 0 的向上取到 0.1，不大于 0 的显示 0。 */
    public static double shownHealth(float health) {
        if (!(health > 0)) return 0;
        // 先按 0.001 去掉浮点尾差，免得 18.0000001 被向上取成 18.1。
        double thousandths = Math.round(health * 1000.0);
        return Math.ceil(thousandths / 100.0) / 10.0;
    }

    /** 角色脚底所在的方块位置，观察编号登记与记忆写入都用它。 */
    public WorldPosition position() {
        return WorldPosition.here((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
