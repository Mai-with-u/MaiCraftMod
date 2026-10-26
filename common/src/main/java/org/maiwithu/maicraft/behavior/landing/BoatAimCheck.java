// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 放船那一下的瞄准核对：准星要正落在落点下面那一格的顶面上、点中的位置就在落点那一格里面、
 * 高度对得上放船的那一点。只核对射线指没指对；放不放得下、下得了船另问世界。
 */
public final class BoatAimCheck {

    /** 核对的结论：指对了就带上点中的那一点，船就放在这里；没指对时没有点。 */
    public record Verdict(boolean aimed, Vec3 at) {}

    private BoatAimCheck() {}

    /** 这一刻的射线指没指到放船的那一点；液体也算命中，对着水面时才点得到顶面。 */
    public static Verdict judge(LandingScene scene, BoatPlan plan) {
        BlockHitResult hit = scene.sightHitThroughFluids();
        BlockPos floor = plan.landing().below();
        Vec3 at = hit.getLocation();
        boolean aimed = hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && hit.getBlockPos().equals(floor)
                && Math.abs(at.y - plan.spawn().y) <= .01
                && at.x >= plan.landing().getX() && at.x <= plan.landing().getX() + 1
                && at.z >= plan.landing().getZ() && at.z <= plan.landing().getZ() + 1;
        return new Verdict(aimed, aimed ? at : null);
    }
}
