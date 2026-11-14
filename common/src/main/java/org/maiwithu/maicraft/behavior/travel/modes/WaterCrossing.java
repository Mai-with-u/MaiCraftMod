// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.travel.planning.TravelPlanner;

/**
 * 隔水勘测（纯函数）：人和目的地之间隔着一片连起来的活水吗，水有多宽，两头岸在哪。
 * 沿出发点到目的地连一线，逐柱找地表：顶上是水算水柱，是冰算冻住，别的是实地。
 * 人一开始就站在水里时，脚下往前的这片水也算隔着的水段，出发侧的岸记出发点这一柱；
 * 这里只管连线上那片水的形状，站在不在水里由勘测沿线自己显出来。
 */
public final class WaterCrossing {

    /** 连成这么宽（格）的水才算一片要坐船过的水：更窄的溪沟走路踩得过去或一步跳得过。 */
    static final int MIN_WATER_WIDTH = 6;
    /** 勘测最远看多远（格）：更远的目的地先不做放船判断，走路与别的办法先答。 */
    static final int MAX_SURVEY_DISTANCE = 384;
    /** 找地表时从两头脚位往上、往下各看几格。 */
    static final int Y_WINDOW_ABOVE = 8;
    static final int Y_WINDOW_BELOW = 12;

    /** 勘测结论。 */
    public enum Answer {
        /** 隔着一片能划的水，两头都有岸。 */
        ACROSS_WATER,
        /** 连线上没找到成片的水。 */
        NO_WATER,
        /** 目的地那一头到线走完还是水：划过去也上不了岸。 */
        WATER_AT_END,
        /** 水面冻住了（冰封的湖海），船放不下去。 */
        FROZEN
    }

    /** 一片水：出发侧的岸、对面的岸，水有多宽（格）。 */
    public record Span(BlockPos nearShore, BlockPos farShore, int waterWidth) {}

    /** 一次勘测的收成：结论加隔的那片水（只在 ACROSS_WATER 时有）。 */
    public record Survey(Answer answer, Span span) {}

    private WaterCrossing() {}

    /**
     * 沿出发点到目的地连一线逐柱看地表，找第一段两头都接着实地、宽度够的水。
     * 读不到的柱（没加载）按实地算：不因为那一片没加载就以为有水。
     *
     * @param read 读一格方块；没加载的格给空
     */
    public static Survey between(Function<BlockPos, Optional<BlockState>> read, BlockPos from, BlockPos to) {
        BlockPos start = from.immutable();
        BlockPos end = to.immutable();
        double distance = Math.sqrt(start.distSqr(end));
        if (distance < 1) return new Survey(Answer.NO_WATER, null);
        // 逐柱沿连线走；太远时按比例加大步距，水宽按步距折回格数。
        int step = Math.max(1, (int) Math.ceil(distance / 256.0));
        int steps = (int) Math.ceil(distance / step);
        BlockPos previous = null;
        int run = 0;
        BlockPos runStartLand = null;
        boolean frozenSeen = false;
        for (int i = 1; i <= steps; i++) {
            BlockPos cell = lerp(start, end, i * step / distance);
            Kind kind = surfaceKind(read, cell);
            if (kind == Kind.FROZEN) frozenSeen = true;
            if (kind == Kind.WATER) {
                if (run == 0) runStartLand = previous;
                run += step;
                continue;
            }
            // 一段水到头了：够宽、对岸是实地才算隔水。出发侧没有实地岸头的（线一开头就是水，
            // 人站在水里）也算——脚下往前这片水正是隔着的水，出发侧的岸记出发点这一柱；
            // 只看目的地方向有没有实地，出发侧不回头要求岸，水里下达与岸边下达同一个答法。
            // 读不到的柱（没加载）也算实地：不因为那一片没加载就以为有水。
            if (run >= MIN_WATER_WIDTH && kind == Kind.LAND && (runStartLand != null || previous == null)) {
                return new Survey(Answer.ACROSS_WATER,
                        new Span(runStartLand != null ? runStartLand : start, cell, run));
            }
            run = 0;
            runStartLand = null;
            previous = cell;
        }
        if (run > 0) return new Survey(Answer.WATER_AT_END, null);
        return new Survey(frozenSeen ? Answer.FROZEN : Answer.NO_WATER, null);
    }

    /** 这一趟划水的耗时估计（刻）：走到出发侧岸边、放船上船下船的零头、水上按约每秒 8 格、下船后走到目的地。 */
    public static long estimateTicks(BlockPos from, Span span, BlockPos to) {
        long overhead = 20L * 6;
        long walking = TravelPlanner.walkTicks(from, span.nearShore()) + TravelPlanner.walkTicks(span.farShore(), to);
        long paddling = Math.round(span.waterWidth() / 8.0 * 20);
        return walking + paddling + overhead;
    }

    /**
     * 一柱的地表：从上往下找第一个真正构成水面、冰面或结实地表的方块，跳过草、花、甘蔗、藤蔓与伸到水边的树叶；
     * 全是空气（或整柱没加载）给空。
     */
    static Optional<Top> surface(Function<BlockPos, Optional<BlockState>> read, int x, int z, int yHigh, int yLow) {
        for (int y = yHigh; y >= yLow; y--) {
            Optional<BlockState> state = read.apply(new BlockPos(x, y, z));
            if (state.isEmpty() || ignoresForSurface(state.get())) continue;
            return Optional.of(new Top(new BlockPos(x, y, z), state.get()));
        }
        return Optional.empty();
    }

    // 空气、岸边伸出的树冠树叶、以及没有碰撞体积的草花甘蔗藤蔓不算地表，继续往下找真正的地面或水面。
    private static boolean ignoresForSurface(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LeavesBlock) return true;
        if (kindOf(state) != Kind.LAND) return false;
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** 这一柱的地表是什么：水、冻住的水，还是实地；整柱没有实遮挡（没加载或悬空）算实地，不凭空判成水。 */
    static Kind surfaceKind(Function<BlockPos, Optional<BlockState>> read, BlockPos anywhere) {
        Optional<Top> top = surface(read, anywhere.getX(), anywhere.getZ(),
                anywhere.getY() + Y_WINDOW_ABOVE, anywhere.getY() - Y_WINDOW_BELOW);
        return top.map(t -> kindOf(t.state())).orElse(Kind.LAND);
    }

    /** 一个方块的类别：水、冰封的水，其余算实地（睡莲算水，划的时候再看它挡不挡）。 */
    static Kind kindOf(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE) return Kind.FROZEN;
        // 判水按流体状态走权威判断：方块类型判水认不出流动水（水面一段流动的水被当成实地，靠岸点挑到水上）。
        // 气泡水柱的流体就是水，被 isWater 覆盖；睡莲没有流体，单独算水。
        if (BlockPassability.isWater(state) || block == Blocks.LILY_PAD) return Kind.WATER;
        return Kind.LAND;
    }

    /** 顶格：那一格的位置与方块状态。 */
    record Top(BlockPos pos, BlockState state) {}

    /** 地表类别。 */
    enum Kind { WATER, FROZEN, LAND }

    /**
     * 这一柱站得住的干地脚位：从上往下找下面一格是已加载结实干地、脚与头两格没有液体和实心阻挡的位置；
     * 岸边有伸出来的树冠或悬崖檐时也能落到树下真正的水边地面，整柱未加载或顶上是水给空。
     */
    static OptionalInt standFeet(Function<BlockPos, Optional<BlockState>> read, int x, int z, int yHint) {
        Optional<Top> top = surface(read, x, z, yHint + Y_WINDOW_ABOVE, yHint - Y_WINDOW_BELOW);
        if (top.isEmpty() || kindOf(top.get().state()) != Kind.LAND) return OptionalInt.empty();
        for (int y = top.get().pos().getY(); y >= yHint - Y_WINDOW_BELOW; y--) {
            Optional<BlockState> ground = read.apply(new BlockPos(x, y, z));
            if (ground.isEmpty()) continue;
            BlockState below = ground.get();
            if (kindOf(below) == Kind.WATER || kindOf(below) == Kind.FROZEN) return OptionalInt.empty();
            if (ignoresForSurface(below)) continue;
            BlockPos feet = new BlockPos(x, y + 1, z);
            if (headroomClear(read, feet) && headroomClear(read, feet.above())) {
                return OptionalInt.of(y + 1);
            }
        }
        return OptionalInt.empty();
    }

    private static boolean headroomClear(Function<BlockPos, Optional<BlockState>> read, BlockPos cell) {
        Optional<BlockState> state = read.apply(cell);
        if (state.isEmpty()) return true;
        BlockState s = state.get();
        if (!s.getFluidState().isEmpty()) return false;
        return s.isAir() || s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /**
     * 对岸还在未加载区块之外（或船在大海中央四周已加载范围全是水）时，沿目的地方向在已加载活水前缘挑一格水面航点：
     * 船先划到那里把前方新区块加载出来，下一轮再接着找对岸下船。
     */
    static Optional<BlockPos> loadedWaterWaypoint(Function<BlockPos, Optional<BlockState>> read,
            BlockPos from, BlockPos toward, int maxDistance) {
        double dx = toward.getX() - from.getX();
        double dz = toward.getZ() - from.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1.0) return Optional.empty();
        double ux = dx / len;
        double uz = dz / len;
        int limit = (int) Math.min(maxDistance, Math.floor(len));
        BlockPos furthestWater = null;
        for (int dist = MIN_WATER_WIDTH; dist <= limit; dist += 2) {
            int x = (int) Math.round(from.getX() + ux * dist);
            int z = (int) Math.round(from.getZ() + uz * dist);
            Optional<Top> top = surface(read, x, z, from.getY() + 4, from.getY() - 6);
            if (top.isEmpty()) break;
            if (kindOf(top.get().state()) != Kind.WATER || top.get().state().getBlock() == Blocks.LILY_PAD) break;
            furthestWater = top.get().pos();
        }
        return Optional.ofNullable(furthestWater);
    }

    /** 线上按比例取一点所在的柱。 */
    private static BlockPos lerp(BlockPos start, BlockPos end, double fraction) {
        return new BlockPos((int) Math.round(start.getX() + (end.getX() - start.getX()) * fraction),
                start.getY(), (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * fraction));
    }
}
