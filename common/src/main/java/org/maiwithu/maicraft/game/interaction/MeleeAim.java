// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.game.world.ObservedMotion;

/**
 * 近战该瞄的那一点，全仓只有这一份：它身上离眼睛最近的那一点（身体框按它相对我的移动往前提、往里收一点），不瞄中心——
 * 对着中心那条射线到身上要走得更远，矮的羊、站在三格边上的怪够得着却打不着，一直等不到出手
 * （实机 10-14 点名杀羊，站在 3.1 格外三十秒没挥）。出手时镜头对的是它；跳劈起跳前、往上升时镜头也先压在这里，
 * 往下落那几刻不用再转。
 *
 * <p>往前提是补角色自己的镜头：镜头像真人一样平滑转（约 0.11 秒，对着动的目标稳定落后约 2 刻的位移），
 * 出手的瞄点每刻又只跟过去 55%（再落后约 1 刻），合起来提前 3 刻。3 格外疾跑横移的玩家（每刻 0.28 格）不提前，
 * 视线落在他身后 0.8 格左右，是半个身宽的两倍多，一刀都砍不到。镜头落后的是双方之间的相对移动，所以减掉我自己的移动；
 * 移动按看到的位置变化读（别的玩家的速度客户端读不到）。
 *
 * <p>最近的那一点被方块挡着（它站在柱顶、台沿上，眼睛到它脚边的射线擦过台面）就像玩家一样改瞄它露出来的部位：
 * 先抬到身体中间、再抬到头顶那一截，够得着又看得见的第一点就是瞄点；哪一点都挡着就还瞄最近那点，
 * 由出手那边如实报准星打到了方块，不在这儿假装打得到。
 */
public final class MeleeAim {

    /** 水平往前提几刻的移动。 */
    static final double LEAD_TICKS = 3.0;
    /** 水平最多提这么远（格）：喝了速度药水疾跑横移的人按这个封顶。 */
    static final double MAX_HORIZONTAL_LEAD = 1.0;
    /** 上下按几刻的移动提：跳起来的人上下变得快，多提反而偏。 */
    static final double VERTICAL_LEAD_TICKS = 0.75;
    /** 上下最多提这么远（格）。 */
    static final double MAX_VERTICAL_LEAD = 0.5;

    private MeleeAim() {}

    /** 此刻该瞄它身上的哪一点：离眼睛最近、够得着、没被方块挡住的那一点。 */
    public static Vec3 point(LocalPlayer player, Entity target) {
        return visiblePoint(player, leadBox(player, target));
    }

    /** 这个身体框上该瞄的那一点：最近点被方块挡着就改瞄露出来的部位（够得着的里面挑）。 */
    static Vec3 visiblePoint(LocalPlayer player, AABB box) {
        Vec3 eye = player.getEyePosition();
        double reach = InteractionRange.entityReach(player);
        return visiblePoint(eye, box, reach, point -> player.level().clip(new ClipContext(eye, point,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS);
    }

    /**
     * 挑瞄点的纯函数：最近点没被挡就是它；挡着就按身体中间、头顶那一截的顺序找，够得着（眼睛到那点不超过
     * 交互距离）又没被挡的第一点；都不行还回最近点。
     *
     * @param clear 眼睛到这一点之间有没有方块挡着（没挡为真）
     */
    static Vec3 visiblePoint(Vec3 eye, AABB box, double reach, Predicate<Vec3> clear) {
        Vec3 nearest = insetClamp(eye, box);
        if (clear.test(nearest)) return nearest;
        double middle = (box.minY + box.maxY) / 2.0;
        for (Vec3 candidate : List.of(new Vec3(nearest.x, middle, nearest.z), new Vec3(nearest.x, box.maxY, nearest.z))) {
            Vec3 inset = insetClamp(candidate, box);
            if (eye.distanceTo(inset) <= reach && clear.test(inset)) return inset;
        }
        return nearest;
    }

    /** 准星落在了什么上、眼睛离瞄点几格：出手等着的原因与打不到的收场话共用这一句。 */
    static String aimBlockedBy(HitResult aimed, double eyeToAim) {
        return "准星打到的是 " + hitName(aimed) + "，眼睛离瞄点 " + String.format("%.2f", eyeToAim) + " 格";
    }

    /** 这一刻瞄准的一句话诊断：打谁、瞄点在哪（离眼睛几格）、准星打到了什么、镜头到没到位。 */
    static String describe(LocalPlayer player, Entity target, Vec3 aimPoint, HitResult aimed, boolean settled) {
        return "打 " + target.getType().getDescriptionId() + "：瞄点 " + aimPoint + "（离眼睛 "
                + String.format("%.2f", player.getEyePosition().distanceTo(aimPoint)) + "），准星 " + hitName(aimed)
                + "，镜头到位 " + settled;
    }

    private static String hitName(HitResult aimed) {
        return aimed instanceof EntityHitResult hit ? hit.getEntity().getType().getDescriptionId()
                : aimed instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? "方块 " + hit.getBlockPos().toShortString() : "空气";
    }

    /** 它的身体框按它相对我的移动往前提。 */
    static AABB leadBox(LocalPlayer player, Entity target) {
        return target.getBoundingBox().move(lead(ObservedMotion.of(target), ObservedMotion.of(player)));
    }

    /**
     * 身体框往哪挪多少：它的移动减我的移动，水平提 3 刻、最多 1 格，上下提 0.75 刻、最多 0.5 格。
     *
     * @param targetMotion 它这一刻挪了多少（格/刻）
     * @param selfMotion   我这一刻挪了多少（格/刻）
     */
    static Vec3 lead(Vec3 targetMotion, Vec3 selfMotion) {
        Vec3 relative = targetMotion.subtract(selfMotion);
        double x = relative.x * LEAD_TICKS;
        double z = relative.z * LEAD_TICKS;
        double horizontal = Math.sqrt(x * x + z * z);
        if (horizontal > MAX_HORIZONTAL_LEAD) {
            x *= MAX_HORIZONTAL_LEAD / horizontal;
            z *= MAX_HORIZONTAL_LEAD / horizontal;
        }
        double y = Mth.clamp(relative.y * VERTICAL_LEAD_TICKS, -MAX_VERTICAL_LEAD, MAX_VERTICAL_LEAD);
        return new Vec3(x, y, z);
    }

    /** 把一点夹进往里收了一点的身体框里：贴着框边的那一点射线容易擦过去。 */
    static Vec3 insetClamp(Vec3 point, AABB box) {
        double insetX = Math.min(0.12, box.getXsize() * 0.18);
        double insetY = Math.min(0.16, box.getYsize() * 0.18);
        double insetZ = Math.min(0.12, box.getZsize() * 0.18);
        return new Vec3(
                Mth.clamp(point.x, box.minX + insetX, box.maxX - insetX),
                Mth.clamp(point.y, box.minY + insetY, box.maxY - insetY),
                Mth.clamp(point.z, box.minZ + insetZ, box.maxZ - insetZ));
    }
}
