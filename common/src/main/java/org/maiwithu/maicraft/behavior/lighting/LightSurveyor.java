// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.world.ReadsLightLevels;

/**
 * 量光：把范围里的格分成"样本（要验收亮度）"与"敏感面（不落灯不改动）"，逐格记下方块光；
 * 放灯之后在冻结的样本上逐格复测。两次都分几刻量完，一刻只读一小段，不把刻占满。
 *
 * <p>采样窗口：每列从给定高度向下 6 格、向上 6 格。没加载的列如实记下，不当空气量。
 */
public final class LightSurveyor {

    /** 采样窗口：从列的基准高度向下、向上各看多少格。 */
    static final int WINDOW = 6;

    /** 原始天空光的满档：一格头顶没有任何遮挡时天空光就是这个值，说明这格在天光直照下。 */
    static final int SKY_LIT = 15;

    private LightSurveyor() {}

    /**
     * 量出来的现场：冻结的样本、采样时的方块光、敏感面、没加载的列、露天格。
     * 露天格（天光直照）不是验收样本：它们的白天亮暗由太阳管，补灯只管头顶有遮挡的封闭格，
     * 不然站在封顶结构顶上量一圈，把屋顶外面的天光格当样本，封闭的暗格一间都量不到。
     */
    public record LightSurvey(List<LightSample> samples, Map<BlockPos, Integer> lights,
                              Set<BlockPos> sensitive, Set<BlockPos> unloaded, Set<BlockPos> openSky) {}

    /** 复测出的一格：光等级；格没加载时为 null，不猜。 */
    public record Measured(BlockPos pos, SampleRules.SampleKind kind, Integer light) {}

    /** 分几刻的采样扫描：逐列读窗口内的方块，挑样本、记敏感面与没加载的列。 */
    public static final class SampleScan {

        /** 一刻最多读多少格：采样不是这一刻的正事。 */
        static final int READS_PER_TICK = 512;

        private final LightingWorld world;
        private final ReadsLightLevels lights;
        private final List<BlockPos> columns;
        private final boolean cropsOnly;
        private int next;
        private final List<LightSample> samples = new ArrayList<>();
        private final Map<BlockPos, Integer> values = new LinkedHashMap<>();
        private final Set<BlockPos> sensitive = new LinkedHashSet<>();
        private final Set<BlockPos> unloaded = new LinkedHashSet<>();
        private final Set<BlockPos> openSky = new LinkedHashSet<>();

        public SampleScan(LightingWorld world, ReadsLightLevels lights, List<BlockPos> columns, boolean cropsOnly) {
            this.world = world;
            this.lights = lights;
            this.columns = columns;
            this.cropsOnly = cropsOnly;
        }

        /** 推进一刻；扫完返回 true。 */
        public boolean tick() {
            int budget = READS_PER_TICK;
            while (budget > 0 && next < columns.size()) {
                BlockPos column = columns.get(next++);
                budget -= scanColumn(column);
            }
            return next >= columns.size();
        }

        /** 扫完没有。 */
        public boolean complete() {
            return next >= columns.size();
        }

        /** 扫完后的现场；没扫完时是已扫到的部分，不能当成全部。 */
        public LightSurvey survey() {
            return new LightSurvey(List.copyOf(samples), Map.copyOf(values),
                    Set.copyOf(sensitive), Set.copyOf(unloaded), Set.copyOf(openSky));
        }

        /** 扫一列，返回读掉的格数。 */
        private int scanColumn(BlockPos column) {
            if (!world.loaded(column)) {
                // 那一列没加载：先走过去让它加载；走不到的照实留在没加载名单里。
                unloaded.add(column);
                return 1;
            }
            int reads = 0;
            BlockState[] window = new BlockState[WINDOW * 2 + 2];
            for (int dy = -WINDOW; dy <= WINDOW + 1; dy++) {
                window[dy + WINDOW] = world.state(column.offset(0, dy, 0));
                reads++;
            }
            for (int dy = -WINDOW; dy <= WINDOW; dy++) {
                BlockPos at = column.offset(0, dy, 0);
                BlockState state = window[dy + WINDOW];
                BlockState above = window[dy + WINDOW + 1];
                if (SampleRules.sensitive(state)) sensitive.add(at);
                if (cropsOnly) {
                    classifyCrop(at, state, above);
                } else if (SampleRules.passable(state) && SampleRules.passable(above)
                        && world.sturdy(at.below(), Direction.UP)) {
                    // 能站的格先看头顶：天光直照的露天格不进验收样本（夜里暗是昼夜的事，不是缺灯），
                    // 头顶有遮挡的格才量方块光、才判暗——补光管的是封顶结构里面。
                    OptionalInt sky = lights.skyLight(at);
                    if (sky.isPresent() && sky.getAsInt() >= SKY_LIT) {
                        openSky.add(at);
                    } else {
                        addSample(at, SampleRules.SampleKind.WALKABLE);
                    }
                }
            }
            return reads;
        }

        /** 农田档只收庄稼与耕地：庄稼格记庄稼，耕地上面的格记耕地上方（庄稼格优先）。 */
        private void classifyCrop(BlockPos at, BlockState state, BlockState above) {
            if (SampleRules.isCrop(state)) {
                addSample(at, SampleRules.SampleKind.CROP);
            } else if (above.isAir() || SampleRules.passable(above)) {
                BlockState support = world.state(at.below());
                if (support.getBlock() instanceof FarmBlock) {
                    addSample(at, SampleRules.SampleKind.FARMLAND);
                }
            }
        }

        private void addSample(BlockPos at, SampleRules.SampleKind kind) {
            if (values.containsKey(at)) return;
            OptionalInt light = lights.blockLight(at);
            if (light.isEmpty()) return;
            samples.add(new LightSample(at, kind));
            values.put(at, light.getAsInt());
        }
    }

    /** 分几刻的复测：在冻结的样本上逐格实测方块光。 */
    public static final class Recheck {

        /** 一刻最多复测多少格。 */
        static final int READS_PER_TICK = 512;

        private final ReadsLightLevels lights;
        private final List<LightSample> samples;
        private int next;
        private final List<Measured> measured = new ArrayList<>();

        public Recheck(ReadsLightLevels lights, List<LightSample> samples) {
            this.lights = lights;
            this.samples = samples;
        }

        /** 推进一刻；量完返回 true。 */
        public boolean tick() {
            int budget = READS_PER_TICK;
            while (budget-- > 0 && next < samples.size()) {
                LightSample sample = samples.get(next++);
                OptionalInt light = lights.blockLight(sample.pos());
                // 没加载的格如实记空：复测时它不算进能验收的分母，也不猜个亮度。
                measured.add(new Measured(sample.pos(), sample.kind(),
                        light.isEmpty() ? null : light.getAsInt()));
            }
            return next >= samples.size();
        }

        /** 量完没有。 */
        public boolean complete() {
            return next >= samples.size();
        }

        /** 复测结果；没量完时是已量到的部分。 */
        public List<Measured> result() {
            return List.copyOf(measured);
        }
    }

    /** 庄稼按原版规则要光照够 9 才长：农田档的默认验收亮度按它定。 */
    public static final int CROP_GROWTH_LIGHT = 9;
}
