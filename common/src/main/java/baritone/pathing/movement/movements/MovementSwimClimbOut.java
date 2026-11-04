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
import net.minecraft.world.level.block.state.BlockState;

/**
 * 从水面游上一格高岸沿：水中只差这一个动作面，既有 TRAVERSE（同层）与 ASCEND（+1 落进岸壁里）都表达不了
 * 「水面节点 → 岸顶站立格」的 +2 过渡。身体侧由原版水中水平碰撞助推完成——贴岸游并按跳，身体被抬上沿顶。
 */
public class MovementSwimClimbOut extends Movement {

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

    /** 攀出水面要看的三种格子：是不是水、能不能站在上面、身体能不能穿过去。 */
    public interface Shore {
        boolean water(int x, int y, int z);

        boolean standOn(int x, int y, int z);

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
     * （人浮在水面），旁边 y+1 那块站得住（岸顶在 y+2，比水面高出约 1.1 格），岸顶往上两格和自己头上那格都空着。
     */
    public static boolean climbable(Shore shore, int x, int y, int z, int destX, int destZ) {
        if (!shore.water(x, y, z) || shore.water(x, y + 1, z)) return false;
        if (!shore.standOn(destX, y + 1, destZ)) return false;
        return shore.passable(destX, y + 2, destZ) && shore.passable(destX, y + 3, destZ)
                && shore.passable(x, y + 1, z);
    }

    // 算路时按计算上下文读格子：和原先逐格读的是同一份判断。
    private static Shore shore(CalculationContext context) {
        return new Shore() {
            @Override public boolean water(int x, int y, int z) {
                return MovementHelper.isWater(context.get(x, y, z));
            }

            @Override public boolean standOn(int x, int y, int z) {
                BlockState ledge = context.get(x, y, z);
                return MovementHelper.canWalkOn(context, x, y, z, ledge);
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
