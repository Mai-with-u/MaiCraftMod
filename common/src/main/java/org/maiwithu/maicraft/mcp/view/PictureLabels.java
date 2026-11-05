// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 画面标注怎么摆：标哪些、按什么先后、标签摆在哪、挤了哪些不标。纯函数。
 *
 * <p>标签是一个小方块，写编号本身，放在那一点正上方，下边一个小尖角指着那一点。先后：要看的那样东西 >
 * 玩家 > 敌对生物 > 设施与告示牌（模组的东西同档）> 其他实体 > 地形特征，同一档近的先。每个标签先试正上方，
 * 压到已经摆好的就依次试右、左、下，再往上挪，最多挪三个标签高；还放不下就不标。图上最多标
 * {@link #MAX_LABELS} 个。不标不是删：调用方照样把它和它在图上的位置列出来。
 */
public final class PictureLabels {

    /** 图上最多标几个：再多就糊成一片，看不清哪个是哪个。 */
    public static final int MAX_LABELS = 24;
    /** 标签往上挪最多几个标签高。 */
    static final int LIFT_STEPS = 3;

    private PictureLabels() {}

    /** 种类的先后档：越靠前越先摆；画标签时边框颜色也按它分。 */
    public enum Rank { PLAYER, HOSTILE, FACILITY, ENTITY, FEATURE }

    /** 画面上的一个方块区域（像素，左上角起）。 */
    public record Rect(double x, double y, double width, double height) {
        boolean overlaps(Rect other) {
            return x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height;
        }

        boolean inside(int imageWidth, int imageHeight) {
            return x >= 0 && y >= 0 && x + width <= imageWidth && y + height <= imageHeight;
        }

        double area() {
            return width * height;
        }
    }

    /**
     * 一个要不要标的编号：它在图上的位置（标注点）与离镜头多远。
     *
     * @param target 是给了 id 要看的那一样：排在所有档之前，标签边框加粗
     */
    public record Candidate(String id, Rank rank, boolean target, double x, double y, double distance) {}

    /**
     * 摆好的结果。
     *
     * @param tag               标签在图上的方块；不标为 null
     * @param notLabeledBecause 不标的原因；标了或整张图都不标时为 null
     */
    public record Placement(Candidate candidate, Rect tag, String notLabeledBecause) {
        public boolean labeled() {
            return tag != null;
        }
    }

    /** 标签字高（像素）：图高的四十分之一，720 高时约 18 像素，最小 12。 */
    public static int fontSize(int imageHeight) {
        return Math.max(12, Math.round(imageHeight / 40f));
    }

    /** 标签四周留白（像素）。 */
    public static int padding(int imageHeight) {
        return Math.max(3, Math.round(fontSize(imageHeight) * 0.3f));
    }

    /** 小尖角高（像素）：标签下边到那一点的距离。 */
    public static int pointer(int imageHeight) {
        return Math.max(4, Math.round(fontSize(imageHeight) * 0.35f));
    }

    /**
     * 摆标签。
     *
     * @param candidates 落在画面里的编号
     * @param avoid      要看的那样东西在图上的外框：其他标签不压进去；没有为 null，占了大半个画面时不管
     * @param unlabeled  整张图不标：都按不标交回，原因留空
     */
    public static List<Placement> place(List<Candidate> candidates, int width, int height, Rect avoid,
                                        boolean unlabeled) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        // 要看的那一样最先，其后按种类的档，同一档近的先。
        ordered.sort(Comparator.comparing((Candidate candidate) -> !candidate.target())
                .thenComparing(Candidate::rank).thenComparingDouble(Candidate::distance));
        Rect keepOut = avoid != null && avoid.area() <= width * (double) height / 2 ? avoid : null;
        List<Rect> placed = new ArrayList<>();
        List<Placement> out = new ArrayList<>();
        for (Candidate candidate : ordered) {
            if (unlabeled) {
                out.add(new Placement(candidate, null, null));
                continue;
            }
            if (placed.size() >= MAX_LABELS) {
                out.add(new Placement(candidate, null, "画面上最多标 " + MAX_LABELS + " 个"));
                continue;
            }
            Rect tag = findSpot(candidate, width, height, placed, candidate.target() ? null : keepOut);
            if (tag == null) {
                out.add(new Placement(candidate, null, "太挤"));
            } else {
                placed.add(tag);
                out.add(new Placement(candidate, tag, null));
            }
        }
        return out;
    }

    // 依次试正上方、右、左、下，再把正上方往上挪；都压到别的就放不下。
    private static Rect findSpot(Candidate candidate, int width, int height, List<Rect> placed, Rect keepOut) {
        double tagWidth = tagWidth(candidate.id(), height);
        double tagHeight = fontSize(height) + 2.0 * padding(height);
        double gap = pointer(height);
        double x = candidate.x();
        double y = candidate.y();
        List<Rect> tries = new ArrayList<>();
        tries.add(new Rect(x - tagWidth / 2, y - gap - tagHeight, tagWidth, tagHeight));
        tries.add(new Rect(x + gap, y - tagHeight / 2, tagWidth, tagHeight));
        tries.add(new Rect(x - gap - tagWidth, y - tagHeight / 2, tagWidth, tagHeight));
        tries.add(new Rect(x - tagWidth / 2, y + gap, tagWidth, tagHeight));
        for (int step = 1; step <= LIFT_STEPS; step++) {
            tries.add(new Rect(x - tagWidth / 2, y - gap - tagHeight * (step + 1), tagWidth, tagHeight));
        }
        for (Rect tag : tries) {
            if (!tag.inside(width, height)) continue;
            if (keepOut != null && tag.overlaps(keepOut)) continue;
            boolean clear = true;
            for (Rect other : placed) {
                if (tag.overlaps(other)) {
                    clear = false;
                    break;
                }
            }
            if (clear) return tag;
        }
        return null;
    }

    /** 标签宽：按字数估（编号都是一个字母加数字），左右各留白。画的时候按同一个宽度画，字居中。 */
    public static double tagWidth(String id, int imageHeight) {
        return id.length() * fontSize(imageHeight) * 0.62 + 2.0 * padding(imageHeight);
    }
}
