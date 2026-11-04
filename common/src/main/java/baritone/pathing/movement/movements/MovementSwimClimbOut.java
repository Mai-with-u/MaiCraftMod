// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 从水面游上比水面高出一截的岸沿：水中只差这一个动作面，既有 TRAVERSE（同层）与 ASCEND（+1 落进岸壁里）都表达不了
 * 「水面节点 → 岸顶站立格」的 +2 过渡。身体侧由原版水中水平碰撞助推完成——贴岸游并按跳，身体被托出水面后靠惯性落上沿顶。
 * 托举只在水里有，出水后只剩惯性，脚最高到水面以上约 1 格：实心方块的岸沿（顶比水面高 1.11 格）上不去，
 * 只有台阶这类矮一截的岸沿算这一步。
 */
public class MovementSwimClimbOut extends Movement {

    /**
     * 岸顶最多比水面高这么多格还爬得上去。原版水里那一支：水平撞墙、身体往上挪一点不碰东西时，竖直速度直接设成 0.3；
     * 脚一出水托举就停，只剩惯性再往上约 0.7 格。开发客户端逐刻实测（一格深水池、水面 79.889）：贴墙按住前进，
     * 每轮被托到最高 80.914，即水面以上 1.025 格，落回水里再来，岸顶 81 始终够不着。取 1 格，不指望刚好擦到峰顶。
     */
    static final double CLIMB_ABOVE_SURFACE = 1.0;

    public MovementSwimClimbOut(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        // 水中不挖掘：岸顶两格不通透时这条出路直接不可行，交给重新寻路。
        super(baritone, src, dest, new BetterBlockPos[0]);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos overshoot = new BetterBlockPos(dest.offset(getDirection()));
        return ImmutableSet.of(src, src.above(), src.above(2), dest, overshoot);
    }

    /** 攀出水面要看的格子：是不是水、水面多高、能不能站在上面、顶面多高、身体能不能穿过去。 */
    public interface Shore {
        boolean water(int x, int y, int z);

        /** 这一格水的水面离格底多高（0–1，满的水源是 8/9）。 */
        double waterSurface(int x, int y, int z);

        boolean standOn(int x, int y, int z);

        /** 这一格碰撞箱的顶离格底多高（0–1，整块是 1，下半台阶是 0.5）。 */
        double topOf(int x, int y, int z);

        boolean passable(int x, int y, int z);
    }

    /**
     * 只从水面节点起攀：头顶还是水就先上浮，原版出水助推只在水面贴岸处生效；
     * 岸顶必须现成可站可立，不在水里垫块。
     */
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        return climbable(shore(context), x, y, z, destX, destZ)
                ? WALK_ONE_IN_WATER_COST + JUMP_ONE_BLOCK_COST + context.jumpPenalty
                : COST_INF;
    }

    /**
     * 从水面这一格 (x,y,z) 能不能攀上旁边 (destX,destZ) 那一格岸，纯函数：脚下这格是水、头上那格不是水
     * （人浮在水面），旁边 y+1 那块站得住，岸顶往上两格和自己头上那格都空着，而且岸顶比水面高出不到
     * {@link #CLIMB_ABOVE_SURFACE}——高过脚出水后能蹿到的地方就上不去，不算一步，寻路去找别的出路。
     */
    public static boolean climbable(Shore shore, int x, int y, int z, int destX, int destZ) {
        if (!shore.water(x, y, z) || shore.water(x, y + 1, z)) return false;
        if (!shore.standOn(destX, y + 1, destZ)) return false;
        double rimAboveSurface = 1 + shore.topOf(destX, y + 1, destZ) - shore.waterSurface(x, y, z);
        // 格子高度相加减会有一点浮点误差：正好 1 格的岸算爬得上。
        if (rimAboveSurface > CLIMB_ABOVE_SURFACE + 1e-6) return false;
        return shore.passable(destX, y + 2, destZ) && shore.passable(destX, y + 3, destZ)
                && shore.passable(x, y + 1, z);
    }

    // 算路时按计算上下文读格子：和原先逐格读的是同一份判断。
    private static Shore shore(CalculationContext context) {
        return new Shore() {
            @Override public boolean water(int x, int y, int z) {
                return MovementHelper.isWater(context.get(x, y, z));
            }

            @Override public double waterSurface(int x, int y, int z) {
                return context.get(x, y, z).getFluidState().getOwnHeight();
            }

            @Override public boolean standOn(int x, int y, int z) {
                BlockState ledge = context.get(x, y, z);
                return MovementHelper.canWalkOn(context, x, y, z, ledge);
            }

            @Override public double topOf(int x, int y, int z) {
                VoxelShape shape = context.get(x, y, z).getCollisionShape(context.bsi.access, new BlockPos(x, y, z));
                return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
            }

            @Override public boolean passable(int x, int y, int z) {
                return MovementHelper.canWalkThrough(context, x, y, z);
            }
        };
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (ctx.playerFeet().y < src.y) {
            // 下沉到起点之下说明没贴住水面，按不可达交回重规划，不做水下挖掘自救。
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (feet.equals(dest) || feet.equals(dest.offset(getDirection()))) {
            // 冲过头落在同层前方一格也算上岸成功
            return state.setStatus(MovementStatus.SUCCESS);
        }
        MovementHelper.moveTowards(ctx, state, dest);
        if (!ctx.player().isInWater()) {
            // 已离开水体还差最后一截：按普通上台阶补跳
            if (ctx.player().onGround() && feet.y < dest.y) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }
        // 水中贴岸前进 + 跳：原版对水中水平碰撞的角色给一次竖直助推，把身体抬上 1 格沿
        return state.setInput(Input.JUMP, true);
    }
}
