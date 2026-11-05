// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 放一格的一次尝试：这一格能从哪几面点（先直接点目标格里可替换的东西，再按先下、四周、上的顺序点邻格的支撑面），
 * 现在试到第几面、预测到的放法、点了几下、各面没放成的缘故。换一面就是换一个站位重来。
 *
 * <p>对着放的那一格（placeAt）多数就是目标格；联动的放置规则说要对着别的格放时（动力辊会被下面的实心面抬高一格），
 * 候选面围着 placeAt 找，原版以为在放 placeAt，落下来的是目标格。
 */
final class PlacementAttempt {

    /** 一个候选：点哪一格的哪一面；direct 表示点的就是目标格里现有的东西（水、草、半砖的第一片）。 */
    record Candidate(BlockPos clicked, Direction face, boolean direct) {}

    /**
     * 一面没放成的缘故。这一格每一面都试过还没放成时，按出现次数最多的那种交代（并列取排在前面的）：
     * 前三种是站位和视线的事，算到不了；后三种是点了不成，算卡住。原因写成不带坐标的一句，同样的格合成一组。
     */
    enum WhyNotPlaced {
        /** 站到跟前也点不着那一面：视线被挡住、看不见，或点下去射线先碰上了别的方块。 */
        HIDDEN(Problem.Kind.UNREACHABLE, "要贴的那一面站到跟前也被挡住或看不见"),
        /** 走不到能点那一面的站位。 */
        NO_SPOT(Problem.Kind.UNREACHABLE, "走不到能点着要贴那一面的站位"),
        /** 旁边没有能贴着放的面。 */
        NO_FACE(Problem.Kind.UNREACHABLE, "旁边没有能贴着放的面"),
        /** 原版从能点的几面放下去都不是要的样子。 */
        WRONG_SHAPE(Problem.Kind.STUCK, "原版从能点的面都放不成要的样子"),
        /** 点下去没生效。 */
        NO_EFFECT(Problem.Kind.STUCK, "点下去没生效"),
        /** 材料换不到手上。 */
        NOT_IN_HAND(Problem.Kind.STUCK, "材料换不到手上");

        final Problem.Kind kind;
        final String reason;

        WhyNotPlaced(Problem.Kind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }
    }

    final PlannedCell cell;
    final BlockPos placeAt;
    final boolean temporary;
    private final List<Candidate> candidates;
    private int index = -1;
    PlacementPrediction.Placement placement;
    PlacementConfirmation confirmation;
    int uses;
    /**
     * 挡着这一格的生物和它在哪（"站在这一格里的 阿缕（touhou_little_maid:maid）"）：有一面原版说落不下来、格里站着生物，
     * 或者点下去点到了挡在跟前的生物时记下；每一面都试过还没放成，这格就等它走开再回头。没有为 null。
     */
    String creatureInTheWay;
    /** 原版放出来朝向不对、能拿扳手转到位：照放，放下后转。 */
    boolean turnAfter;
    /** 还能拿扳手点几下。 */
    int turnsLeft = 4;
    /** 这一轮各面没放成的缘故各几次。 */
    private final Map<WhyNotPlaced, Integer> misses = new EnumMap<>(WhyNotPlaced.class);

    PlacementAttempt(PlannedCell cell, BlockPos placeAt, boolean temporary, List<Candidate> candidates) {
        this.cell = cell;
        this.placeAt = placeAt.immutable();
        this.temporary = temporary;
        this.candidates = List.copyOf(candidates);
    }

    /** 从现场看对着 placeAt 放这一格能从哪几面点：围着 placeAt 找。 */
    static List<Candidate> candidates(ReadsBlocks world, PlannedCell cell, BlockPos placeAt) {
        List<Candidate> out = new ArrayList<>();
        if (world.loaded(placeAt)) {
            BlockState live = world.state(placeAt);
            // 那一格里是水、草这类可替换的东西，或同类可叠加的方块（半砖的第一片），直接点它。
            boolean stacking = placeAt.equals(cell.pos()) && live.is(cell.state().getBlock()) && PlacementPrediction.maximumUses(cell) > 1;
            if (!live.isAir() && (live.canBeReplaced() || stacking)) {
                out.add(new Candidate(placeAt, Direction.UP, true));
            }
        }
        for (Direction toward : PlacementPrediction.SUPPORT_ORDER) {
            BlockPos clicked = placeAt.relative(toward);
            if (world.loaded(clicked) && TemporaryBlocks.solid(world, clicked)) out.add(new Candidate(clicked, toward.getOpposite(), false));
        }
        return out;
    }

    boolean hasNext() {
        return index + 1 < candidates.size();
    }

    Candidate next() {
        index++;
        placement = null;
        confirmation = null;
        return candidates.get(index);
    }

    /** 下一面是哪一面，不往前挪。 */
    Candidate peek() {
        return candidates.get(index + 1);
    }

    Candidate current() {
        return index < 0 ? null : candidates.get(index);
    }

    int candidateCount() {
        return candidates.size();
    }

    /** 这一面没放成，记下缘故。 */
    void missed(WhyNotPlaced why) {
        misses.merge(why, 1, Integer::sum);
    }

    /** 这一轮各面试下来出现最多的缘故（并列取排在前面的）；一面都没记过时为 null。 */
    WhyNotPlaced mostly() {
        WhyNotPlaced most = null;
        for (var entry : misses.entrySet()) {
            if (most == null || entry.getValue() > misses.get(most)) most = entry.getKey();
        }
        return most;
    }

    /** 放下这一格会占住的格：目标格、对着放的那一格和它的另一半；站位的脚下与头顶都要避开。 */
    Set<BlockPos> occupied() {
        Set<BlockPos> cells = new HashSet<>();
        cells.add(cell.pos());
        cells.add(placeAt);
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), cell.state())) cells.add(effect.pos());
        return cells;
    }
}
