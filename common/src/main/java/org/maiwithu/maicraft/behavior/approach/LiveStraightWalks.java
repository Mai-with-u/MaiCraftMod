// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.StraightWalk;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 直走的组装：先用短途直走判断问这条路是不是快路，是就包一个导航的直走动作，按靠近的口径定容差与期限。
 * 三格内的路按键走过去最多三秒，走不到就报被挡，回退由发起方做。
 */
public final class LiveStraightWalks implements StraightWalks {

    /** 停步容差（格）：进了目标格边沿就算到，到了之后由发起方核对够不够得着。 */
    private static final double STOP_TOLERANCE = 0.3;
    /** 直走的期限（刻）：三格加一次跳跃走不完三秒，就是被挡住了。 */
    private static final int MAX_TICKS = 60;

    private final Supplier<PlayerContext> context;

    public LiveStraightWalks(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> toward(BlockPos from, BlockPos to, ApproachWorldView world, ProtectedCells guarded) {
        if (!NearbyWalks.passable(from, to, world, guarded)) return Optional.empty();
        boolean climb = to.getY() > from.getY();
        return Optional.of(new StraightWalk(context, Vec3.atBottomCenterOf(to), STOP_TOLERANCE, MAX_TICKS,
                "走到旁边的站位", climb));
    }
}
