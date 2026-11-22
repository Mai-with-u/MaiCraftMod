// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 2×1 双格安全穿沙井的纯只读判断与选位：当开挖点脚下或上方探测到连续重力覆盖层（厚度至少 2 格），
 * 且在客户端已加载区块内垂直向下预查确认 16 格内存在实岩顶面、沿途及实岩顶板下方均无流体或空腔时，
 * 选出一组相邻双格 {@code (colA, colB)} 交替安全下掘至头顶拥有 3 格实岩顶板的深度，再切入标准阶梯下矿。
 */
final class SandShaftPlan {

    /** 触发 2×1 穿沙井所需的最小连续重力方块厚度（格）。 */
    static final int MIN_GRAVITY_THICKNESS = 2;
    /** 垂直向下预查实岩顶面的最大深度（格）。 */
    static final int MAX_SCAN_DEPTH = 16;
    /** 进入实岩后在头顶上方保留的最小实岩顶板厚度（格）：保证切回阶梯后上方沙层被实岩彻底封住。 */
    static final int ROCK_ROOF_LAYERS = 3;
    /** 实岩顶面之下至少继续核验几格无流体与空腔，避免停在薄壳上坠入洞穴或岩浆。 */
    static final int EXTRA_FLOOR_CHECK = 3;
    /** 当前井位下方有空腔或流体时，尝试横移重选安全井位的最大水平半径（格）。 */
    static final int SHIFT_RADIUS = 2;

    private final BlockPos colA;
    private final BlockPos colB;
    private final int rockTopY;
    private final int targetFeetY;

    SandShaftPlan(BlockPos colA, BlockPos colB, int rockTopY, int targetFeetY) {
        this.colA = colA.immutable();
        this.colB = colB.immutable();
        this.rockTopY = rockTopY;
        this.targetFeetY = targetFeetY;
    }

    /** 角色起始站立列（只用 X、Z 坐标）。 */
    BlockPos colA() {
        return colA;
    }

    /** 与起始列紧邻的另一列（只用 X、Z 坐标）。 */
    BlockPos colB() {
        return colB;
    }

    /** 两列共同进入实岩的最高顶面高度。 */
    int rockTopY() {
        return rockTopY;
    }

    /** 穿沙井完成并切回标准阶梯时的目标脚部高度（头顶上方恰好有 3 格实岩顶板）。 */
    int targetFeetY() {
        return targetFeetY;
    }

    /**
     * 在当前脚位评估是否满足 2×1 安全穿沙井条件；满足时挑出第一组安全的相邻双格。
     *
     * @param excludedB 本轮已经因中途遇险中止过的 B 列位置，重选时跳过
     */
    static Optional<SandShaftPlan> evaluate(ClientStairsDown.Site site, TickContext context, BlockPos feet,
            Direction preferredFacing, Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed,
            Set<BlockPos> excludedB) {
        if (feet == null || forbiddenCell.test(feet)) return Optional.empty();
        // 按面朝方向优先、顺时针遍历四面，挑出第一组路径与实岩顶板均安全无流体无空腔的 2×1 双格。
        for (Direction side : directionOrder(preferredFacing)) {
            BlockPos colB = feet.relative(side);
            if (excludedB.contains(columnKey(colB))) continue;
            Optional<SandShaftPlan> plan = evaluatePair(site, context, feet, colB, forbiddenCell, digAllowed);
            if (plan.isPresent()) return plan;
        }
        return Optional.empty();
    }

    /** 判断当前脚位附近是否存在连续重力覆盖层（厚度 ≥ 2 格），用于决定是否尝试横移重选井位。 */
    static boolean hasGravityOverburden(ClientStairsDown.Site site, TickContext context, BlockPos feet) {
        if (feet == null) return false;
        if (gravityRunDown(site, context, feet, feet.getY() - 1) >= MIN_GRAVITY_THICKNESS) return true;
        return gravityRunUp(site, context, feet, feet.getY() + 2) >= MIN_GRAVITY_THICKNESS;
    }

    /**
     * 当前井位下方有空腔或流体而无法下井时，在附近 2 格内寻找一处可站立且满足安全穿沙井条件的横移起井点。
     */
    static Optional<BlockPos> findShiftedStart(ClientStairsDown.Site site, TickContext context, BlockPos origin,
            Direction preferredFacing, Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed) {
        if (origin == null) return Optional.empty();
        for (int radius = 1; radius <= SHIFT_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos candidate = origin.offset(dx, 0, dz);
                    if (forbiddenCell.test(candidate) || !site.standable(context, candidate)) continue;
                    if (evaluate(site, context, candidate, preferredFacing, forbiddenCell, digAllowed, Set.of()).isPresent()) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // 核验一组相邻双格 (colA, colB)：必须穿过连续 ≥2 格重力层、16 格内见实岩、沿途及头顶 3 格实岩板下均无流体与空腔。
    private static Optional<SandShaftPlan> evaluatePair(ClientStairsDown.Site site, TickContext context,
            BlockPos colA, BlockPos colB, Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed) {
        int startY = colA.getY();
        if (!clearOrDiggableHeadroom(site, context, colB, startY, forbiddenCell, digAllowed)) return Optional.empty();
        Integer rockTopA = findRockTop(site, context, colA, startY - 1, forbiddenCell, digAllowed);
        Integer rockTopB = findRockTop(site, context, colB, startY - 1, forbiddenCell, digAllowed);
        if (rockTopA == null || rockTopB == null) return Optional.empty();
        int gravityA = maxGravityThickness(site, context, colA, startY, rockTopA);
        int gravityB = maxGravityThickness(site, context, colB, startY, rockTopB);
        if (Math.max(gravityA, gravityB) < MIN_GRAVITY_THICKNESS) return Optional.empty();
        int rockTopY = Math.min(rockTopA, rockTopB);
        // 切回阶梯时脚站 targetFeetY、头占 targetFeetY + 1，故上方 [targetFeetY + 2 .. rockTopY] 恰有 3 格实岩顶板。
        int targetFeetY = rockTopY - ROCK_ROOF_LAYERS - 1;
        int checkBottomY = Math.min(targetFeetY - 1, rockTopY - EXTRA_FLOOR_CHECK);
        if (!shaftPathSafe(site, context, colA, colB, startY, rockTopY, targetFeetY, checkBottomY,
                forbiddenCell, digAllowed)) {
            return Optional.empty();
        }
        return Optional.of(new SandShaftPlan(colA, colB, rockTopY, targetFeetY));
    }

    // B 列与当前站立层齐平及头顶两格不能是设施、流体、建筑或不可挖方块，且四周不能紧挨流体。
    private static boolean clearOrDiggableHeadroom(ClientStairsDown.Site site, TickContext context,
            BlockPos colB, int startY, Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed) {
        for (int y = startY; y <= startY + 1; y++) {
            BlockPos cell = new BlockPos(colB.getX(), y, colB.getZ());
            if (forbiddenCell.test(cell)) return false;
            ClientStairsDown.Ground ground = site.ground(context, cell);
            if (ground == ClientStairsDown.Ground.DIGGABLE) {
                if (!digAllowed.test(cell)) return false;
            } else if (ground != ClientStairsDown.Ground.OPEN) {
                return false;
            }
            if (touchesFluidAround(site, context, cell)) return false;
        }
        return true;
    }

    // 从 startFloorY 垂直向下扫描至多 MAX_SCAN_DEPTH 格，寻找实岩顶面；途中遇流体、空腔、不可挖或受保护格立即判否。
    private static Integer findRockTop(ClientStairsDown.Site site, TickContext context, BlockPos column,
            int startFloorY, Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed) {
        for (int depth = 0; depth <= MAX_SCAN_DEPTH; depth++) {
            BlockPos cell = new BlockPos(column.getX(), startFloorY - depth, column.getZ());
            if (forbiddenCell.test(cell)) return null;
            ClientStairsDown.Ground ground = site.ground(context, cell);
            if (ground != ClientStairsDown.Ground.DIGGABLE || site.hurtsToStandOn(context, cell)) return null;
            if (!digAllowed.test(cell)) return null;
            if (site.solidRock(context, cell)) return cell.getY();
        }
        return null;
    }

    // 核验整条 2×1 下掘路径（含实岩下 3 格与最终井底）均无流体、无空腔、无烫脚块且允许挖掘。
    private static boolean shaftPathSafe(ClientStairsDown.Site site, TickContext context, BlockPos colA, BlockPos colB,
            int startY, int rockTopY, int targetFeetY, int checkBottomY,
            Predicate<BlockPos> forbiddenCell, Predicate<BlockPos> digAllowed) {
        for (BlockPos col : List.of(colA, colB)) {
            for (int y = startY - 1; y >= checkBottomY; y--) {
                BlockPos cell = new BlockPos(col.getX(), y, col.getZ());
                if (forbiddenCell.test(cell)) return false;
                ClientStairsDown.Ground ground = site.ground(context, cell);
                if (ground != ClientStairsDown.Ground.DIGGABLE || site.hurtsToStandOn(context, cell)) return false;
                if (y >= targetFeetY && !digAllowed.test(cell)) return false;
                // 进入实岩顶面以下的井身与井底都必须是连续实心非重力实岩，防止下方突然暴露空腔或夹层落沙。
                if (y <= rockTopY && !site.solidRock(context, cell)) return false;
                if (touchesFluidAround(site, context, cell)) return false;
            }
            // 最终井底正下方再看一格：不能踩在一层薄岩壳下就是岩浆或洞穴的位置。
            BlockPos underFloor = new BlockPos(col.getX(), targetFeetY - 2, col.getZ());
            ClientStairsDown.Ground underGround = site.ground(context, underFloor);
            if (underGround == ClientStairsDown.Ground.FLUID || underGround == ClientStairsDown.Ground.OPEN) {
                return false;
            }
        }
        return true;
    }

    // 统计一列在脚下至实岩顶面之间（以及头顶正上方）的最大连续重力方块厚度；中间隔着空气或非重力方块不算连续。
    private static int maxGravityThickness(ClientStairsDown.Site site, TickContext context, BlockPos column,
            int startY, int rockTopY) {
        int best = gravityRunUp(site, context, column, startY + 2);
        int current = 0;
        for (int y = startY + 1; y > rockTopY; y--) {
            BlockPos cell = new BlockPos(column.getX(), y, column.getZ());
            if (site.fallingBlock(context, cell)) {
                current++;
                if (current > best) best = current;
            } else {
                current = 0;
            }
        }
        return best;
    }

    private static int gravityRunDown(ClientStairsDown.Site site, TickContext context, BlockPos column, int topY) {
        int count = 0;
        for (int depth = 0; depth < MAX_SCAN_DEPTH; depth++) {
            BlockPos cell = new BlockPos(column.getX(), topY - depth, column.getZ());
            if (!site.fallingBlock(context, cell)) break;
            count++;
        }
        return count;
    }

    private static int gravityRunUp(ClientStairsDown.Site site, TickContext context, BlockPos column, int bottomY) {
        int count = 0;
        for (int up = 0; up < MAX_SCAN_DEPTH; up++) {
            BlockPos cell = new BlockPos(column.getX(), bottomY + up, column.getZ());
            if (!site.fallingBlock(context, cell)) break;
            count++;
        }
        return count;
    }

    /** 水平四邻是否紧挨水或岩浆：井壁挨着流体时绝不在此开井。 */
    static boolean touchesFluidAround(ClientStairsDown.Site site, TickContext context, BlockPos cell) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (site.ground(context, cell.relative(side)) == ClientStairsDown.Ground.FLUID) return true;
        }
        return false;
    }

    static BlockPos columnKey(BlockPos pos) {
        return new BlockPos(pos.getX(), 0, pos.getZ());
    }

    private static List<Direction> directionOrder(Direction preferred) {
        Direction start = preferred != null && preferred.getAxis().isHorizontal() ? preferred : Direction.NORTH;
        List<Direction> order = new ArrayList<>(4);
        Direction cur = start;
        for (int i = 0; i < 4; i++) {
            order.add(cur);
            cur = cur.getClockWise();
        }
        return order;
    }
}
