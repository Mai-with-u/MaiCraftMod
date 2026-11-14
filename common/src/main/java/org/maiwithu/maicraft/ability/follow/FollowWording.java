// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 跟随状况事件与问题说明的文案整理：坐标拼接、四舍五入与地形许可提示。
 */
final class FollowWording {

    private FollowWording() {}

    static String spot(FollowView.Sighting him) {
        if (him == null) return "未知位置";
        WorldPosition p = him.position();
        return p.x() + ", " + p.y() + ", " + p.z();
    }

    static String mine(FollowView.Sighting him) {
        if (him == null) return "原地";
        Vec3 me = him.me();
        return (int) Math.floor(me.x) + ", " + (int) Math.floor(me.y) + ", " + (int) Math.floor(me.z);
    }

    static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    static String openingHint(TerrainPermit permit) {
        return permit.mayChangeTerrain()
                ? "允许的地形改动已经用上还是过不去；等它走回来，或取消跟随"
                : "这次任务不允许改地形（change_blocks=none）；把 change_blocks 提到 temporary 或更高，也许就能跟过去";
    }

    static Problem unreachable(Problem blockedBecause, long limitTicks, TerrainPermit permit) {
        String cause = blockedBecause == null ? "路走不通" : blockedBecause.message();
        return Problem.of(Problem.Kind.UNREACHABLE,
                "跟不上目标：连续 " + limitTicks / 20 / 60 + " 分钟都过不去——" + cause,
                openingHint(permit));
    }

    static Problem waterDanger(DangerousWater.Hazard hazard, long limitTicks) {
        return Problem.of(Problem.Kind.DANGER,
                "跟不上目标：连续 " + limitTicks / 20 / 60 + " 分钟前面都隔着危险水域（"
                        + hazard.reason() + "），没有下水",
                "先清掉水里的敌对生物、坐船过去，或等他上岸后再跟");
    }
}
